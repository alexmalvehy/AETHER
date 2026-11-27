@file:OptIn(UnstableApi::class)

package app.aether.player

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.Stream
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import app.aether.player.automix.AnalysisAudio
import app.aether.player.automix.AnalysisCopy
import app.aether.player.automix.LocalAudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Automix's audio: each song whole, as its analyser reads it.
 *
 * - On the phone, read where it is: a kept song's file, a song kept in the app's store, the phone's own.
 * - Streamed, fetched once into Automix's own cache ([AudioStore.automixCache]). A song not playing yet
 *   (the next one) is fetched as it will be heard ([StreamPurpose.LISTEN]: the copy the player will
 *   choose), and the player reads it from there when it gets to it, so it isn't downloaded twice. A song
 *   already playing gets the song's own stream, the lightest there is (BitChord, too, measures the
 *   song's own recording when the player is on another copy). A copy in pieces (DASH, HLS) or above
 *   48 kHz isn't one file to decode whole in a phone's memory: the song's own stream stands in.
 *
 * Nothing here blocks the caller: what isn't known yet is looked for on [scope], and asked again on
 * the controller's next tick.
 */
internal class AutomixAudio(
    private val context: Context,
    private val env: PlaybackEnvironment,
    private val scope: CoroutineScope,
) : AnalysisAudio {
    /** Where each song's audio was found. */
    private sealed interface Found {
        class OnPhone(val uri: Uri) : Found
        class Cached(val cache: Cache, val key: String, val length: Long) : Found
        data object Unreadable : Found
    }

    /** A song as the controller last asked about it, and whether it was playing then. */
    private class Asked(val track: Track, val playing: Boolean)

    private val asked = ConcurrentHashMap<String, Asked>()
    private val found = ConcurrentHashMap<String, Found>()
    private val fetching = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /** The controller is about to ask for [item]'s analysis; [playing] if it's the song heard now. */
    fun know(item: MediaItem, playing: Boolean) {
        val track = item.toTrack() ?: return
        asked[item.mediaId] = Asked(track, playing)
    }

    override fun copyOf(trackId: String): AnalysisCopy? {
        val id = MediaId(trackId)
        // A kept song is read from its file in the Music folder, wherever its stream would come from.
        env.keptFile(id)?.let { return phoneCopy(it) }
        when (val known = found[trackId]) {
            is Found.OnPhone -> return phoneCopy(known.uri)
            is Found.Cached -> return AnalysisCopy(known.key) { CacheMediaDataSource(known.cache, known.key, known.length) }
            Found.Unreadable -> return null
            null -> fetch(id)
        }
        return null
    }

    private fun phoneCopy(uri: Uri) = AnalysisCopy(uri.toString()) { LocalAudioSource.open(context.contentResolver, uri) }

    /** Finds where [id]'s audio is, fetching a stream whole into the cache; never twice at once. */
    private fun fetch(id: MediaId) {
        val trackId = id.value
        failedAt[trackId]?.let { if (SystemClock.elapsedRealtime() - it < RETRY_AFTER_MS) return }
        if (!fetching.add(trackId)) return
        scope.launch(Dispatchers.IO) {
            try {
                found[trackId] = find(id) ?: return@launch
                failedAt.remove(trackId)
            } catch (e: Exception) {
                failedAt[trackId] = SystemClock.elapsedRealtime()
                Log.w(TAG, "couldn't fetch $trackId for Automix: ${e.message}")
            } finally {
                fetching.remove(trackId)
            }
        }
    }

    private suspend fun find(id: MediaId): Found? {
        // Kept in the app's store (songs kept before they went to the Music folder).
        if (AudioStore.kept(context, id)) {
            val store = AudioStore.cache(context)
            val key = "track:${id.value}"
            return Found.Cached(store, key, ContentMetadata.getContentLength(store.getContentMetadata(key)))
        }
        val ask = asked[id.value]
        val listening = ask != null && !ask.playing
        var stream = streamOf(id, if (listening) StreamPurpose.LISTEN else StreamPurpose.KEEP, ask?.track)
        if (listening && !wholeAndLight(stream)) stream = streamOf(id, StreamPurpose.KEEP, null)
        val uri = Uri.parse(stream.url)
        if (LocalAudioSource.isLocal(uri)) return Found.OnPhone(uri)
        if (!wholeAndLight(stream)) {
            Log.d(TAG, "${id.value}: no one file to measure (${stream.manifest}, ${stream.sampleRateHz} Hz)")
            return Found.Unreadable
        }
        val began = SystemClock.elapsedRealtime()
        val cache = AudioStore.automixCache(context)
        val length = write(cache, stream)
        if (length <= 0L || !cache.isCached(stream.cacheKey, 0, length)) throw IOException("the cache didn't keep it whole")
        Log.d(TAG, "fetched ${id.value} for Automix (${stream.cacheKey}, ${length / 1024} KB) in ${SystemClock.elapsedRealtime() - began} ms")
        return Found.Cached(cache, stream.cacheKey, length)
    }

    private suspend fun streamOf(id: MediaId, purpose: StreamPurpose, track: Track?): Stream =
        when (val r = env.stream(id, purpose, track)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> throw IOException("no stream: ${r.error}")
        }

    /** One file, at a rate a phone can decode whole for measuring. */
    private fun wholeAndLight(stream: Stream): Boolean =
        stream.manifest == Stream.Manifest.NONE &&
            (stream.sampleRateHz ?: 0) <= MAX_RATE_HZ &&
            (stream.contentLength ?: 0L) <= MAX_BYTES

    /** [stream] whole into [cache] (only what isn't there already is downloaded); its length. */
    private fun write(cache: Cache, stream: Stream): Long {
        // In bounded ranges: the next song is fetched whole for measuring, and asked for whole googlevideo
        // sends it at about playback speed.
        val upstream = ChunkedDataSource(DefaultDataSource.Factory(context, AudioStore.http(env)).createDataSource(), CHUNK_BYTES)
        // Blocking on the cache: should the player be reading this song from it just then, the
        // writer waits its turn rather than going round the cache and keeping nothing.
        val source = CacheDataSource(cache, upstream, CacheDataSource.FLAG_BLOCK_ON_CACHE)
        val spec = DataSpec.Builder()
            .setUri(stream.url)
            .setHttpRequestHeaders(stream.headers)
            .setKey(stream.cacheKey)
            .build()
        CacheWriter(source, spec, null, null).cache()
        return ContentMetadata.getContentLength(cache.getContentMetadata(stream.cacheKey))
    }

    private companion object {
        const val RETRY_AFTER_MS = 60_000L
        const val MAX_RATE_HZ = 48_000
        const val MAX_BYTES = 120L * 1024 * 1024
        const val TAG = "AetherAutomix"
    }
}

/**
 * A song in a [Cache] as a [MediaDataSource], so Android's extractor reads it there: random access
 * through a window read ahead, since the extractor asks for a few kilobytes at a time, mostly in order.
 * Read-only; never touches the network.
 */
internal class CacheMediaDataSource(
    private val cache: Cache,
    private val key: String,
    private val length: Long,
) : MediaDataSource() {
    private val window = ByteArray(WINDOW)
    private var windowStart = -1L
    private var windowLength = 0

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length || position < 0) return -1
        if (size == 0) return 0
        if (windowStart < 0 || position < windowStart || position >= windowStart + windowLength) fill(position)
        if (windowLength <= 0) return -1
        val from = (position - windowStart).toInt()
        val n = minOf(size, windowLength - from)
        System.arraycopy(window, from, buffer, offset, n)
        return n
    }

    private fun fill(position: Long) {
        windowStart = position
        windowLength = 0
        val source = CacheDataSource(cache, null)
        try {
            val want = minOf(WINDOW.toLong(), length - position).toInt()
            source.open(DataSpec.Builder().setUri(Uri.parse("cache://$key")).setKey(key).setPosition(position).setLength(want.toLong()).build())
            while (windowLength < want) {
                val n = source.read(window, windowLength, want - windowLength)
                if (n < 0) break
                windowLength += n
            }
        } catch (e: IOException) {
            // A piece gone from the cache: what was read stands, and the decoder stops short there.
            Log.w("AetherAutomix", "cache read of $key at $position stopped: ${e.message}")
        } finally {
            runCatching { source.close() }
        }
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun close() {
        windowStart = -1L
        windowLength = 0
    }

    private companion object {
        const val WINDOW = 512 * 1024
    }
}
