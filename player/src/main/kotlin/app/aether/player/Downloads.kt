package app.aether.player

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.aether.core.model.MediaId
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import java.io.File
import java.util.concurrent.Executors

/**
 * Where kept songs live: one store on the phone, its index in a database, nothing ever evicted.
 * A song is kept under its own id (`track:<id>`), never under the stream's address, which changes
 * every few hours; so the player finds a kept song whatever its address is today.
 */
@OptIn(UnstableApi::class)
internal object AudioStore {
    @Volatile private var database: StandaloneDatabaseProvider? = null
    @Volatile private var cache: SimpleCache? = null
    @Volatile private var manager: DownloadManager? = null

    private fun database(context: Context) = database ?: synchronized(this) {
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
    }

    fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(File(context.applicationContext.filesDir, "kept"), NoOpCacheEvictor(), database(context)).also { cache = it }
    }

    @Volatile private var automix: SimpleCache? = null

    /**
     * Automix's own audio cache: songs fetched whole for its analysis, the least recently used going
     * first past [AUTOMIX_CACHE_BYTES]. Apart from the keep, which is never evicted.
     */
    fun automixCache(context: Context): SimpleCache = automix ?: synchronized(this) {
        automix ?: SimpleCache(
            File(context.applicationContext.cacheDir, "automix-audio"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(AUTOMIX_CACHE_BYTES),
            database(context),
        ).also { automix = it }
    }

    private const val AUTOMIX_CACHE_BYTES = 256L * 1024 * 1024

    /** Whether the whole of [id]'s audio is kept in the store (a song kept before kept songs went to Music). */
    fun kept(context: Context, id: MediaId): Boolean = runCatching {
        val key = "track:${id.value}"
        val store = cache(context)
        val length = androidx.media3.datasource.cache.ContentMetadata.getContentLength(store.getContentMetadata(key))
        length > 0 && store.isCached(key, 0, length)
    }.getOrDefault(false)

    /** The key a song's audio is kept under. */
    fun keyOf(spec: DataSpec): String = spec.uri.trackId()?.let { "track:${it.value}" } ?: spec.key ?: spec.uri.toString()

    /**
     * Streams as the player gets them: resolved from the song's id as they're read. The app's HTTP
     * client is asked for only when a request is made, so this can be built while the app still is.
     */
    fun upstream(context: Context, purpose: StreamPurpose): DataSource.Factory {
        val env = context.applicationContext as PlaybackEnvironment
        // In bounded ranges (BitChord's [ChunkedDataSource]): asked for whole, googlevideo sends a song at
        // about playback speed, so keeping one took minutes.
        return ResolvingDataSource.Factory(ChunkedDataSource.Factory(DefaultDataSource.Factory(context.applicationContext, http(env))), Resolver(env, purpose))
    }

    /**
     * Streams as the player hears them: resolved as they're read, and taken from Automix's cache
     * where its analysis already fetched that very stream (the next song, studied while this one
     * played), so it isn't downloaded a second time. Nothing is written there by listening.
     */
    fun listening(context: Context): DataSource.Factory {
        val env = context.applicationContext as PlaybackEnvironment
        // In bounded ranges, so the song being heard buffers ahead at the line's speed, not the song's.
        val network = ChunkedDataSource.Factory(DefaultDataSource.Factory(context.applicationContext, http(env)))
        val fetchedAhead = CacheDataSource.Factory()
            .setCache(automixCache(context))
            .setUpstreamDataSourceFactory(network)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return ResolvingDataSource.Factory(fetchedAhead, Resolver(env, StreamPurpose.LISTEN))
    }

    /** The app's own HTTP client, asked for only when a request is made. */
    fun http(env: PlaybackEnvironment): OkHttpDataSource.Factory = OkHttpDataSource.Factory(Call.Factory { request -> env.http.newCall(request) })

    /** The one download manager, shared by the screens and the service. Main thread only. */
    fun manager(context: Context): DownloadManager = manager ?: synchronized(this) {
        manager ?: DownloadManager(
            context.applicationContext,
            database(context),
            cache(context),
            CacheDataSource.Factory().setCache(cache(context)).setCacheKeyFactory(::keyOf).setUpstreamDataSourceFactory(upstream(context, StreamPurpose.KEEP)),
            Executors.newFixedThreadPool(2),
        ).apply { maxParallelDownloads = 2 }.also { manager = it }
    }
}

/**
 * Songs kept on the phone to play without a connection (PODIUM's offline listening, on Media3's
 * downloads). Each keeps its whole track with it, so what's kept can be listed and played with no
 * network at all. Keeping runs in [KeepService], in the background, with its progress shown.
 */
@OptIn(UnstableApi::class)
class Downloads(
    private val context: Context,
    private val scope: CoroutineScope,
    /** A song's words as LRC, for its file's tags, when they're known; null otherwise. */
    private val lyricsOf: suspend (Track) -> String? = { null },
    /** A song's cover as a JPEG, for its file's tags; null when it can't be had. */
    private val coverOf: suspend (Track) -> ByteArray? = { null },
) {
    enum class Status { Queued, Keeping, Kept, Failed }

    /** A song being kept or kept: the track, how it stands, and how far along (0..1). */
    data class Entry(val track: Track, val status: Status, val progress: Float)

    private val manager by lazy { AudioStore.manager(context) }
    private val _entries = MutableStateFlow<Map<MediaId, Entry>>(emptyMap())
    val entries: StateFlow<Map<MediaId, Entry>> = _entries.asStateFlow()
    private var ticker: Job? = null

    /** Kept songs as files in the listener's Music folder. */
    val files = KeptFiles(context)

    /** From the manager: what's being kept, and what's whole in the app's store. */
    private var downloading: Map<MediaId, Entry> = emptyMap()

    /** Songs being written out to the Music folder now, and those that couldn't be this session. */
    private val writing = java.util.Collections.synchronizedSet(HashSet<MediaId>())
    private val couldNot = java.util.Collections.synchronizedSet(HashSet<MediaId>())
    private var exporter: Job? = null

    /** Where [id]'s file is in the Music folder, when it's been kept there. */
    fun keptFile(id: MediaId): android.net.Uri? = files.uriOf(id)

    /** Starts listening once the app has finished starting (the manager needs the app whole). */
    fun start() {
        Handler(Looper.getMainLooper()).post {
            manager.addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) = refresh()
                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) = refresh()
                override fun onInitialized(downloadManager: DownloadManager) = refresh()
            })
            refresh()
        }
        scope.launch {
            files.prune()
            files.entries.collect { publish() }
        }
    }

    fun keep(tracks: List<Track>) {
        tracks.filter { it.id !in _entries.value }.forEach { t ->
            val request = DownloadRequest.Builder(t.id.value, trackUri(t.id)).setCustomCacheKey("track:${t.id.value}").setData(t.toBytes()).build()
            DownloadService.sendAddDownload(context, KeepService::class.java, request, false)
        }
    }

    fun forget(id: MediaId) {
        DownloadService.sendRemoveDownload(context, KeepService::class.java, id.value, false)
        scope.launch { files.forget(id) }
    }

    private fun refresh() {
        val map = LinkedHashMap<MediaId, Entry>()
        var busy = false
        val all = ArrayList<Download>()
        manager.downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) all += cursor.download }
        // The last asked for first.
        all.sortByDescending { it.startTimeMs }
        run {
            for (d in all) {
                val track = trackFromBytes(d.request.data) ?: continue
                val status = when (d.state) {
                    Download.STATE_COMPLETED -> Status.Kept
                    Download.STATE_DOWNLOADING -> Status.Keeping.also { busy = true }
                    Download.STATE_FAILED -> Status.Failed
                    Download.STATE_REMOVING -> continue
                    else -> Status.Queued.also { busy = true }
                }
                val progress = if (status == Status.Kept) 1f else (d.percentDownloaded.takeIf { it >= 0f } ?: 0f) / 100f
                map[track.id] = Entry(track, status, progress)
            }
        }
        downloading = map
        publish()
        exportWhole()
        // The manager says when a song starts and ends, not how far it's got: while any is under way, look again each second.
        if (busy && ticker?.isActive != true) ticker = scope.launch {
            while (isActive) {
                delay(1000)
                val inFlight = manager.currentDownloads
                if (inFlight.isEmpty()) { refresh(); break }
                downloading = downloading.mapValues { (id, e) ->
                    inFlight.firstOrNull { it.request.id == id.value }?.let { d -> e.copy(status = Status.Keeping, progress = (d.percentDownloaded.takeIf { it >= 0f } ?: 0f) / 100f) } ?: e
                }
                publish()
            }
        }
    }

    /**
     * What the screens see: a song is kept once it's whole on the phone, in the Music folder or still
     * in the app's store while it's being written out there (it plays from either); writing it out is
     * the app's own business, and shows nowhere. The last kept first: what's being kept, or was a moment
     * ago, then the Music folder's from the newest (a song kept now used to go to the foot of the list).
     */
    private fun publish() {
        val out = LinkedHashMap<MediaId, Entry>()
        val written = files.entries.value
        downloading.forEach { (id, e) -> if (id !in written) out[id] = e }
        written.entries.reversed().forEach { (id, f) -> if (id !in out) out[id] = Entry(f.track, Status.Kept, 1f) }
        _entries.value = out
    }

    /**
     * Each song whole in the app's store, written out to the Music folder one at a time, then let go
     * of in the store, so a kept song takes its room once. One that can't be written out stays in the
     * store, kept and playing, and isn't tried again this session.
     */
    private fun exportWhole() {
        if (exporter?.isActive == true) return
        exporter = scope.launch {
            while (isActive) {
                val next = downloading.values.firstOrNull { it.status == Status.Kept && it.track.id !in files.entries.value && it.track.id !in couldNot && it.track.id !in writing } ?: break
                val id = next.track.id
                writing += id
                val lyrics = runCatching { lyricsOf(next.track) }.getOrNull()
                val cover = runCatching { coverOf(next.track) }.getOrNull()
                val written = files.export(next.track, lyrics, cover)
                writing -= id
                if (written != null) {
                    // In the Music folder now: the store's copy goes, and the manager forgets it.
                    DownloadService.sendRemoveDownload(context, KeepService::class.java, id.value, false)
                } else {
                    couldNot += id
                }
                publish()
            }
        }
    }
}

/** Keeps songs in the background, with their progress in a notification. */
@OptIn(UnstableApi::class)
class KeepService : DownloadService(
    NOTIFICATION,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL,
    R.string.keep_channel,
    R.string.keep_channel_about,
) {
    override fun getDownloadManager(): DownloadManager = AudioStore.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL).buildProgressNotification(this, R.drawable.aether_keep, null, null, downloads, notMetRequirements)

    private companion object {
        const val NOTIFICATION = 7101
        const val CHANNEL = "keep"
    }
}
