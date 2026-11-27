// Ported from BitChord (app/.../playback/cast/CastPlayback.kt and RemoteSpeaker.kt, kushagrasinghx/BitChord;
// GPL-3.0). Its model, its queue window and its reconciling are BitChord's; what the TV is handed is
// LastWave-Native's way (CastStreamServer): every song from this phone, as this phone plays it.
package app.aether.player.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.Result
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.coroutines.resume

/** Told when the TV changes something the session's player reports as its own. */
interface CastSink {
    /** Play state, buffering, or the playhead moved. */
    fun onRemotePlaybackChanged()

    /** The music moved to a TV, or back. */
    fun onRemoteRouteChanged()

    /** The TV's volume changed, from here or from its own remote. */
    fun onRemoteVolumeChanged()
}

/**
 * What plays on the TV while a cast is up.
 *
 * The phone's player stays the owner of the queue (every row, reorder, AutoPlay refill, lyric and
 * widget keeps reading it as before) and is held still while the TV is the speaker. The TV owns the
 * clock: [positionMs], [isPlaying] and the rest come from it, and the session's player reports them in
 * place of the phone's, so the app, the lock screen and the notification all show what the TV is doing.
 * Two things keep the halves in step:
 *
 *  - The phone moving to another song (a skip, a touch in Up next) loads that song on the TV.
 *  - The TV moving to another song (it finished one, or somebody used its own remote) moves the phone's
 *    player to the same one.
 *
 * Which is which is told by comparing media ids rather than by a flag: the player reports its own
 * moves a beat after they happen, whereas "the TV is already on this song" stays true.
 *
 * Every song is handed over from this phone ([CastStreamServer], LastWave's way): a kept song is a
 * file here, a stream is fetched as AETHER's own app fetches it, and a TV can do neither. The TV's
 * queue holds the playing song and [AHEAD] after it, so it moves on by itself with no gap. Everything
 * runs on the main thread.
 */
class CastPlayback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val localPlayer: () -> ExoPlayer?,
    private val resolve: suspend (MediaItem) -> CastSource?,
    /** The TV finished a song and went on to the next by itself (what "sleep after this song" waits for). */
    private val onReceiverAdvanced: () -> Unit = {},
) {
    var sink: CastSink? = null

    init {
        scope.launch { CastController.volume.drop(1).collect { if (active) sink?.onRemoteVolumeChanged() } }
    }

    /** Whether the TV is the speaker. Everything else here means something only while it is. */
    var active = false
        private set

    /** Set by [CastController.disconnect]: the listener chose this phone, so it plays on here. */
    var resumeHereOnEnd = false

    private var session: CastSession? = null
    private var client: RemoteMediaClient? = null
    private var server: CastStreamServer? = null

    /** The listener wants sound; what [playWhenReady] reports. */
    private var wantsPlay = false

    /** A song is on its way to the TV and nothing has come back yet. */
    private var loading = false

    /** The TV ran out of queue. */
    private var finished = false

    /** Where a load or a seek asked the TV to be, until it says where it is. */
    private var requestedPositionMs = 0L
    private var requestedAt = 0L

    private var loadGeneration = 0
    private var loadJob: Job? = null
    private var reconcileJob: Job? = null
    private var reconcileAgain = false
    private var lastItemId = MediaQueueItem.INVALID_ITEM_ID

    /** The song the phone last asked the TV to move to, until the TV is there ([Expected]). */
    private val expected = Expected()

    /** What the TV was doing when its session started to end. */
    private var endPositionMs = 0L
    private var endMediaId: String? = null
    private var endWasPlaying = false

    val isPlaying: Boolean
        get() = active && !loading && client?.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING

    val playWhenReady: Boolean get() = wantsPlay

    /** In [Player]'s own terms, so the session's player can answer with it directly. */
    val playbackState: Int
        get() = when {
            // A song put on the TV paused is paused while the TV fetches it: shown loading, the phone
            // would say it was about to play.
            loading -> if (wantsPlay) Player.STATE_BUFFERING else Player.STATE_READY
            finished -> Player.STATE_ENDED
            else -> when (client?.mediaStatus?.playerState) {
                MediaStatus.PLAYER_STATE_BUFFERING, MediaStatus.PLAYER_STATE_LOADING -> if (wantsPlay) Player.STATE_BUFFERING else Player.STATE_READY
                else -> Player.STATE_READY
            }
        }

    val positionMs: Long
        get() {
            val live = client?.takeIf { !loading }?.approximateStreamPosition
            // A seek, or a song put on the TV at a place in it, is asked of the TV and answered a moment
            // later (seconds, for a song it has to fetch first); until it says it's there, its own clock
            // reads the old place or the song's start, and the hand would spring back to it.
            return if (live == null || Requested.held(requestedAt, SystemClock.elapsedRealtime())) requestedPositionMs else live.coerceAtLeast(0L)
        }

    val durationMs: Long get() = client?.streamDuration?.takeIf { it > 0 } ?: C.TIME_UNSET

    /** The TV's own volume as the whole number 0..100 a player's device expects. */
    val deviceVolume: Int get() = (CastController.volume.value * 100f).toInt()

    /** The id of the song playing on the TV, or null. */
    val mediaIdOnTv: String? get() = remoteMediaId()

    fun setVolume(fraction: Float) = CastController.setVolume(fraction)

    fun play() {
        if (!active) return
        wantsPlay = true
        finished = false
        client?.play()
        sink?.onRemotePlaybackChanged()
    }

    fun pause() {
        if (!active) return
        wantsPlay = false
        client?.pause()
        sink?.onRemotePlaybackChanged()
    }

    fun seekTo(positionMs: Long) {
        val client = client ?: return
        if (!active) return
        requestedPositionMs = positionMs.coerceAtLeast(0L)
        requestedAt = SystemClock.elapsedRealtime()
        client.seek(MediaSeekOptions.Builder().setPosition(requestedPositionMs).build())
        sink?.onRemotePlaybackChanged()
    }

    // --- The session's life ------------------------------------------------------------------------

    private val callback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = onStatus()
    }

    fun onSessionBegan(newSession: CastSession, resumed: Boolean) {
        try {
            beginSession(newSession, resumed)
        } catch (t: Throwable) {
            // This runs inside the framework's callback, where an exception ends the app: a TV that
            // couldn't be set up is a cast that failed.
            Log.e(TAG, "onSessionBegan threw", t)
            release()
            CastController.note("Couldn't start playing on the TV")
        }
    }

    private fun beginSession(newSession: CastSession, resumed: Boolean) {
        val remote = newSession.remoteMediaClient ?: return
        if (active && remote === client) return
        release()
        // The TV reads every song from this phone: without a local network there's nothing it can reach.
        val server = CastStreamServer.start(context, http) ?: run {
            CastController.note("Put this phone on the same Wi-Fi as the TV")
            CastController.disconnect(resumeHere = true)
            return
        }
        this.server = server
        session = newSession
        client = remote
        remote.registerCallback(callback)
        active = true
        finished = false

        val player = localPlayer()
        if (player != null) {
            val onTv = remote.mediaStatus?.queueItems?.firstOrNull { it.itemId == remote.mediaStatus?.currentItemId }?.mediaId()
            val tvPlaying = remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
            val currentId = player.currentMediaItem?.mediaId
            // A session that outlived the app: its songs were served by a phone that's gone, so the TV is
            // given them again, from where it had got to.
            val position = if (resumed && currentId != null && currentId == onTv) remote.approximateStreamPosition.coerceAtLeast(0L) else player.currentPosition.coerceAtLeast(0L)
            val play = if (resumed && currentId == onTv) tvPlaying else player.playWhenReady && player.playbackState != Player.STATE_ENDED
            // The phone goes quiet first, whatever happens next, so the two are never both heard. It stays
            // ready, paused (it keeps each song's length, and buffers little ahead while paused).
            player.pause()
            load(player.currentMediaItem, position, play)
            if (!resumed && player.currentMediaItem != null) CastController.handedOver()
        }
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
    }

    fun onSessionEnding(ending: CastSession) {
        val remote = ending.remoteMediaClient ?: client ?: return
        endPositionMs = remote.approximateStreamPosition.coerceAtLeast(0L)
        endMediaId = remote.mediaStatus?.let { status -> status.queueItems.firstOrNull { it.itemId == status.currentItemId }?.mediaId() }
        endWasPlaying = wantsPlay && remote.mediaStatus?.playerState == MediaStatus.PLAYER_STATE_PLAYING
    }

    /**
     * The TV let go. Its place is carried back to the phone's player, so the song and the second in it are
     * still there. Whether sound follows depends on who asked: a listener who chose "this phone" wants it
     * to play on here; a TV switched off, or a Wi-Fi that dropped, shouldn't have the phone start playing
     * out loud unasked.
     */
    fun onSessionEnded(error: Int) {
        if (!active) return
        val resume = resumeHereOnEnd && endWasPlaying
        resumeHereOnEnd = false
        release()
        val player = localPlayer()
        if (player != null) {
            val index = indexOfMediaId(player, endMediaId)
            if (index != null) player.seekTo(index, endPositionMs) else player.seekTo(endPositionMs)
            if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) player.prepare()
            if (resume) player.play()
        }
        Log.i(TAG, "session ended: error=$error resumeHere=$resume")
        sink?.onRemoteRouteChanged()
        sink?.onRemotePlaybackChanged()
    }

    fun onConnectFailed() = CastController.note("Couldn't reach the TV")

    /** Lets go of the TV without touching the phone's player. */
    fun release() {
        loadJob?.cancel()
        reconcileJob?.cancel()
        client?.unregisterCallback(callback)
        client = null
        session = null
        active = false
        loading = false
        wantsPlay = false
        finished = false
        lastItemId = MediaQueueItem.INVALID_ITEM_ID
        expected.expect(null, 0L, 0L)
        server?.stop()
        server = null
    }

    // --- The phone's player moved ------------------------------------------------------------------

    /** A new song became the phone's current one. */
    fun onLocalTransition(item: MediaItem?, reason: Int) {
        if (!active || item == null) return
        val remote = client ?: return
        if (item.mediaId == remoteMediaId()) {
            // Already there: the TV moved first and this is the phone catching up, or a queue edit that
            // left the playing song alone.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) scheduleReconcile()
            return
        }
        val queued = remote.mediaStatus?.queueItems?.firstOrNull { it.mediaId() == item.mediaId }
        if (queued != null && !loading) {
            // A song the TV already holds is a jump, not a reload, and the jump is at once.
            wantsPlay = true
            finished = false
            requestedPositionMs = 0L
            requestedAt = SystemClock.elapsedRealtime()
            expected.expect(item.mediaId, requestedAt, JUMP_WAIT_MS)
            remote.queueJumpToItem(queued.itemId, null)
            sink?.onRemotePlaybackChanged()
        } else {
            load(item, 0L, play = true)
        }
    }

    /** The phone's queue changed under the playing song. */
    fun onLocalQueueChanged() = scheduleReconcile()

    fun onLocalRepeatChanged(repeatMode: Int) {
        if (!active) return
        client?.queueSetRepeatMode(receiverRepeat(repeatMode), null)
    }

    /**
     * Something other than the session's player started the phone's own player while the TV is the
     * speaker. The phone goes quiet again and the TV is asked instead.
     */
    fun onLocalStartedPlaying(player: Player) {
        if (!active) return
        player.pause()
        play()
    }

    // --- The TV moved ------------------------------------------------------------------------------

    private fun onStatus() {
        val remote = client ?: return
        val status = remote.mediaStatus ?: return
        val now = SystemClock.elapsedRealtime()
        val onTv = status.getQueueItemById(status.currentItemId)?.mediaId()
        val idle = status.playerState == MediaStatus.PLAYER_STATE_IDLE
        if (expected.mediaId != null && onTv == expected.mediaId && idle && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
            // The song the phone asked for couldn't be played: as when any song can't be.
            expected.expect(null, 0L, 0L)
            loading = false
            lastItemId = status.currentItemId
            onReceiverFailed()
            sink?.onRemotePlaybackChanged()
            return
        }
        val wasOnItsWay = expected.mediaId != null
        if (expected.onTheWay(onTv, idle, now)) {
            // Still on its way to the song the phone asked for: what it says is about the song it's leaving
            // (still playing, finished, or one step of two skips), and the phone, already there, doesn't
            // follow it back. Its place stays the one asked for.
            sink?.onRemotePlaybackChanged()
            return
        }
        // There: what follows it on the TV can be set now.
        if (wasOnItsWay) scheduleReconcile()
        // The phone's last ask is answered once the TV says it's there, or has had long enough to be.
        if (Requested.answered(remote.approximateStreamPosition, requestedPositionMs, requestedAt, SystemClock.elapsedRealtime())) requestedAt = 0L
        when (status.playerState) {
            MediaStatus.PLAYER_STATE_PLAYING -> {
                wantsPlay = true
                loading = false
                finished = false
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                wantsPlay = false
                loading = false
            }
            MediaStatus.PLAYER_STATE_IDLE -> {
                if (!loading && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    wantsPlay = false
                    onReceiverFinished()
                } else if (status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    loading = false
                    onReceiverFailed()
                }
            }
        }
        val itemId = status.currentItemId
        if (itemId != MediaQueueItem.INVALID_ITEM_ID && itemId != lastItemId) {
            val moved = lastItemId != MediaQueueItem.INVALID_ITEM_ID
            lastItemId = itemId
            val mediaId = status.getQueueItemById(itemId)?.mediaId()
            val player = localPlayer()
            if (mediaId != null && player != null && player.currentMediaItem?.mediaId != mediaId) {
                indexOfMediaId(player, mediaId)?.let { player.seekTo(it, 0L) }
                if (moved) onReceiverAdvanced()
            }
            scheduleReconcile()
        }
        sink?.onRemotePlaybackChanged()
    }

    /** The TV played its last queued song to the end. */
    private fun onReceiverFinished() {
        val player = localPlayer() ?: return
        when {
            // The TV holds only a few songs ahead, so running out of them isn't the end of the phone's queue.
            player.hasNextMediaItem() -> {
                player.seekToNextMediaItem()
                onReceiverAdvanced()
            }
            player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> player.seekTo(0, 0L)
            else -> finished = true
        }
    }

    /** The TV couldn't play a song (a format it can't decode, a phone it couldn't reach): the next one. */
    private fun onReceiverFailed() {
        CastController.note("The TV couldn't play this one")
        val player = localPlayer()
        if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem() else wantsPlay = false
    }

    // --- Loading -----------------------------------------------------------------------------------

    /**
     * Puts [item] on the TV, replacing its queue, then lets [reconcileOnce] fill in what follows. Only the
     * playing song is published before the TV is asked to start; the rest while it plays, so a cast
     * begins as soon as one song can.
     */
    private fun load(item: MediaItem?, positionMs: Long, play: Boolean) {
        val remote = client ?: return
        loadJob?.cancel()
        val generation = ++loadGeneration
        wantsPlay = play
        loading = true
        finished = false
        requestedPositionMs = positionMs
        requestedAt = SystemClock.elapsedRealtime()
        expected.expect(item?.mediaId, requestedAt, LOAD_WAIT_MS)
        sink?.onRemotePlaybackChanged()
        loadJob = scope.launch {
            try {
                loadNow(item, positionMs, play, generation, remote)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "load threw", t)
                expected.expect(null, 0L, 0L)
                loading = false
                wantsPlay = false
                CastController.note("Couldn't start playing on the TV")
                sink?.onRemotePlaybackChanged()
            }
        }
    }

    private suspend fun loadNow(item: MediaItem?, positionMs: Long, play: Boolean, generation: Int, remote: RemoteMediaClient) {
        val queueItem = item?.let { buildQueueItem(it, play) }
        if (generation != loadGeneration || !active) return
        if (queueItem == null) {
            // Nothing to play, so nothing to wait for: the TV is left as it was, only the phone's idea of it set right.
            expected.expect(null, 0L, 0L)
            loading = false
            wantsPlay = false
            if (item != null) CastController.note("This one can't play on the TV")
            sink?.onRemotePlaybackChanged()
            return
        }
        val repeat = receiverRepeat(localPlayer()?.repeatMode ?: Player.REPEAT_MODE_OFF)
        val request = MediaLoadRequestData.Builder()
            .setQueueData(MediaQueueData.Builder().setItems(listOf(queueItem)).setStartIndex(0).setRepeatMode(repeat).setStartTime(positionMs).build())
            .setAutoplay(play)
            .setCurrentTime(positionMs)
            .build()
        val result = remote.load(request).awaitResult()
        if (generation != loadGeneration || !active) return
        if (!result.status.isSuccess) {
            Log.w(TAG, "load failed: ${result.status.statusCode}")
            expected.expect(null, 0L, 0L)
            loading = false
            wantsPlay = false
            CastController.note("The TV couldn't play this one")
            sink?.onRemotePlaybackChanged()
            return
        }
        lastItemId = remote.mediaStatus?.currentItemId ?: lastItemId
        loading = false
        sink?.onRemotePlaybackChanged()
        scheduleReconcile()
    }

    private fun scheduleReconcile() {
        if (!active) return
        if (reconcileJob?.isActive == true) {
            reconcileAgain = true
            return
        }
        reconcileJob = scope.launch {
            do {
                reconcileAgain = false
                // Let the TV's own status catch up with whatever just changed it: read earlier, its queue
                // is the old one.
                delay(RECONCILE_DELAY_MS)
                if (active && !loading) {
                    try {
                        reconcileOnce()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e(TAG, "reconcile threw", t)
                    }
                }
            } while (reconcileAgain && active)
        }
    }

    /**
     * Makes what the TV has queued after the playing song match what the phone's queue says comes next,
     * [AHEAD] songs of it. The matching start is kept (it may already be buffering); the first song that
     * differs and everything after it is replaced.
     */
    private suspend fun reconcileOnce() {
        val remote = client ?: return
        val player = localPlayer() ?: return
        val status = remote.mediaStatus ?: return
        val queue = status.queueItems
        // Only once the TV is on the phone's song. Before, the window would be cut from where the TV was,
        // taking away the very song it was on its way to (it then jumped to the one after: two skips).
        if (expected.mediaId != null) return
        val here = queue.indexOfFirst { it.itemId == status.currentItemId }
        if (here < 0) return
        if (queue[here].mediaId() != player.currentMediaItem?.mediaId) return
        val remoteAhead = queue.drop(here + 1)
        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return
        val wanted = upNext(player, AHEAD)
        val kept = QueueWindow.kept(remoteAhead.map { it.mediaId() }, wanted.map { it.mediaId })
        if (kept == remoteAhead.size && kept == wanted.size) return
        if (kept < remoteAhead.size) remote.queueRemoveItems(remoteAhead.drop(kept).map { it.itemId }.toIntArray(), null).awaitResult()
        for (next in wanted.drop(kept)) {
            val queueItem = buildQueueItem(next, autoplay = true) ?: break
            if (!active) return
            remote.queueInsertItems(arrayOf(queueItem), MediaQueueItem.INVALID_ITEM_ID, null).awaitResult()
        }
    }

    /** The songs that come after the playing one, in the order they'll play (shuffled or not), up to [count]. */
    private fun upNext(player: Player, count: Int): List<MediaItem> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val out = ArrayList<MediaItem>(count)
        var at = player.currentMediaItemIndex
        while (out.size < count) {
            at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (at == C.INDEX_UNSET) break
            out += player.getMediaItemAt(at)
        }
        return out
    }

    private suspend fun buildQueueItem(item: MediaItem, autoplay: Boolean): MediaQueueItem? {
        val server = server ?: return null
        val source = try {
            resolve(item)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't get ${item.mediaId} ready for the TV", e)
            null
        } ?: return null
        val url = server.publish(source)
        val metadata = item.mediaMetadata
        val music = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            metadata.title?.let { putString(MediaMetadata.KEY_TITLE, it.toString()) }
            metadata.artist?.let { putString(MediaMetadata.KEY_ARTIST, it.toString()) }
            metadata.albumTitle?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it.toString()) }
            metadata.artworkUri?.takeIf { it.scheme == "https" }?.let { addImage(WebImage(it)) }
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(source.mimeType)
            .setMetadata(music)
            .setCustomData(JSONObject().put(KEY_MEDIA_ID, item.mediaId))
            .apply { metadata.durationMs?.takeIf { it > 0 }?.let(::setStreamDuration) }
            .build()
        return MediaQueueItem.Builder(info).setAutoplay(autoplay).build()
    }

    // --- Small helpers ---------------------------------------------------------------------------

    private fun MediaQueueItem.mediaId(): String? = media?.customData?.optString(KEY_MEDIA_ID)?.ifEmpty { null }

    private fun remoteMediaId(): String? {
        val status = client?.mediaStatus ?: return null
        return status.getQueueItemById(status.currentItemId)?.mediaId()
    }

    /** The row of [mediaId] nearest the one playing: a song queued twice is where more than one answers. */
    private fun indexOfMediaId(player: Player, mediaId: String?): Int? {
        if (mediaId == null) return null
        val at = player.currentMediaItemIndex.coerceAtLeast(0)
        return (0 until player.mediaItemCount).filter { player.getMediaItemAt(it).mediaId == mediaId }.minByOrNull { kotlin.math.abs(it - at) }
    }

    /**
     * Not named `await`: [PendingResult] has a blocking member of that name, and a member always beats an
     * extension (BitChord found the blocking one ran, on the main thread, and threw).
     */
    private suspend fun <R : Result> PendingResult<R>.awaitResult(): R =
        suspendCancellableCoroutine { continuation ->
            setResultCallback { continuation.resume(it) }
            continuation.invokeOnCancellation { cancel() }
        }

    companion object {
        private const val TAG = "AETHER cast"
        private const val KEY_MEDIA_ID = "mediaId"

        /** Songs kept queued on the TV after the playing one. */
        private const val AHEAD = 3
        private const val RECONCILE_DELAY_MS = 300L

        /** How long a jump the TV already holds the song for, and a load (the song fetched first), may take. */
        private const val JUMP_WAIT_MS = 10_000L
        private const val LOAD_WAIT_MS = 30_000L

        /** Repeat-one is the TV's to do; repeat-all is the phone's (the TV holds a window, and looping it isn't looping the queue). */
        internal fun receiverRepeat(repeatMode: Int): Int =
            if (repeatMode == Player.REPEAT_MODE_ONE) MediaStatus.REPEAT_MODE_REPEAT_SINGLE else MediaStatus.REPEAT_MODE_REPEAT_OFF
    }
}

/**
 * The song the phone last asked the TV to move to (a jump in its queue, or a load), until the TV says
 * it's there and has begun it. In between, what the TV says is about the song it's leaving, or a step on
 * its way (a TV asked twice moves twice): the phone, already on the song it asked for, doesn't follow it,
 * and leaves the TV's queue alone. A move the TV never finishes is given up on, and the TV believed.
 */
internal class Expected {
    var mediaId: String? = null
        private set
    private var until = 0L

    fun expect(mediaId: String?, now: Long, forMs: Long) {
        this.mediaId = mediaId
        until = if (mediaId == null) 0L else now + forMs
    }

    /** Whether the TV, saying it's on [onTv] ([idle] while it hasn't begun it), is still on its way. */
    fun onTheWay(onTv: String?, idle: Boolean, now: Long): Boolean {
        val wanted = mediaId ?: return false
        if ((onTv == wanted && !idle) || now >= until) {
            mediaId = null
            return false
        }
        return true
    }
}

/**
 * The place the phone asked the TV to be, standing in for the TV's own until the TV says it's there:
 * within [NEAR_MS] of it, or [LONGEST_MS] on, whichever comes first (a place the TV can't reach is
 * given up on, not held for ever).
 */
internal object Requested {
    const val NEAR_MS = 2_000L
    const val LONGEST_MS = 8_000L

    fun held(requestedAt: Long, now: Long): Boolean = requestedAt != 0L && now - requestedAt < LONGEST_MS

    fun answered(reportedMs: Long, requestedMs: Long, requestedAt: Long, now: Long): Boolean =
        requestedAt == 0L || kotlin.math.abs(reportedMs - requestedMs) <= NEAR_MS || now - requestedAt >= LONGEST_MS
}

/** How much of what the TV holds after the playing song can stay: the run that already matches the phone's. */
internal object QueueWindow {
    fun kept(onTv: List<String?>, wanted: List<String>): Int {
        var kept = 0
        while (kept < onTv.size && kept < wanted.size && onTv[kept] == wanted[kept]) kept++
        return kept
    }
}
