package app.aether.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.aether.core.model.HeardFormat
import app.aether.core.model.PlaybackState
import app.aether.core.model.PlaybackStatus
import app.aether.core.model.RepeatMode
import app.aether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.Bundle

/**
 * The screens' side of the engine: one [state] to read, and commands. It never holds playback
 * logic of its own; everything goes to the service through a Media3 controller.
 */
class PlaybackConnection(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _position = MutableStateFlow(0L)

    /**
     * Where the song has got to, in milliseconds: four times a second while it plays. Kept apart
     * from [state] (whose `positionMs` is where it was at the last change) so a tick redraws a
     * progress line, not every screen that shows the song.
     */
    val position: StateFlow<Long> = _position.asStateFlow()

    /** Where in the playing song Automix will run into the next, as fractions of it; null when it won't. */
    val automixWindow: StateFlow<app.aether.automix.TransitionWindow?> get() = AutomixState.smartTransitionWindow

    /** A real mix is playing (one a plain crossfade couldn't be): the note at the bottom says so. */
    val automixing: StateFlow<Boolean> get() = AutomixState.smartMixInProgress

    /** The song Automix is bringing in now: in Up Next it stays where it is until it plays. */
    val automixIncoming: StateFlow<String?> get() = AutomixState.incoming

    /** The pair Automix has planned for the playing song, while its window shows. */
    val automixPlanned: StateFlow<Pair<String, String>?> get() = AutomixState.planned

    /** Any Automix transition in flight, and how far through: the cover's morph follows it. */
    val automixBlend: StateFlow<Blend?> get() = AutomixState.blend

    /** Where the music is this instant, for what's drawn frame by frame ([position] moves four times a second). */
    fun positionNow(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: _position.value

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()
    private var queue: List<Track> = emptyList()
    private var seeking = false
    private var readyOnce = false
    private var ticker: Job? = null
    private var route: Bundle = Bundle.EMPTY

    fun connect() {
        if (controller != null) return
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching {
                MediaController.Builder(context, token)
                    .setListener(object : MediaController.Listener {
                        override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
                            route = extras
                            publish()
                        }
                    })
                    .buildAsync().await()
            }.getOrNull() ?: return@launch
            route = c.sessionExtras
            controller = c
            c.addListener(Listener())
            rebuildQueue(c)
            publish()
            pending.toList().forEach { it(c) }
            pending.clear()
        }
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun with(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { pending += block; connect() }
    }

    // --- Commands ---

    /** Replaces the queue with [tracks] and plays from [index]. */
    fun play(tracks: List<Track>, index: Int = 0, positionMs: Long = 0) = with { c ->
        if (tracks.isEmpty()) return@with
        c.setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(0, tracks.lastIndex), positionMs)
        c.shuffleModeEnabled = false
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<Track>) = with { c ->
        if (c.mediaItemCount == 0) return@with play(tracks)
        c.addMediaItems(c.currentMediaItemIndex + 1, tracks.map { it.toMediaItem() })
    }

    fun addToQueue(tracks: List<Track>) = with { c ->
        if (c.mediaItemCount == 0) return@with play(tracks)
        c.addMediaItems(tracks.map { it.toMediaItem() })
    }

    /** Appends what follows the current track (its radio), so playback never runs out. */
    fun extend(tracks: List<Track>) = with { c ->
        val known = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }.toSet()
        val fresh = tracks.filter { it.id.value !in known }
        if (fresh.isNotEmpty()) c.addMediaItems(fresh.map { it.toMediaItem() })
    }

    /** A song in the queue told better (same song, same sound): its words change where it stands. */
    fun mend(track: Track) = with { c ->
        (0 until c.mediaItemCount).filter { c.getMediaItemAt(it).mediaId == track.id.value }.forEach { c.replaceMediaItem(it, track.toMediaItem()) }
    }

    fun move(from: Int, to: Int) = with { c -> if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to) }
    fun remove(index: Int) = with { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }
    fun clearUpcoming() = with { c -> if (c.currentMediaItemIndex + 1 < c.mediaItemCount) c.removeMediaItems(c.currentMediaItemIndex + 1, c.mediaItemCount) }
    fun skipTo(index: Int) = with { c -> if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0); c.play() } }

    fun toggle() = with { c ->
        when {
            c.playbackState == Player.STATE_ENDED -> { c.seekTo(0, 0); c.play() }
            c.playbackState == Player.STATE_IDLE -> { c.prepare(); c.play() }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    fun next() = with { c -> c.seekToNext() }

    /** Back to the start of the song, or to the one before it when near its start. */
    fun previous() = with { c -> c.seekToPrevious() }

    fun seekTo(positionMs: Long) = with { c ->
        seeking = true
        publish()
        c.seekTo(positionMs)
    }

    fun setShuffle(on: Boolean) = with { c -> c.shuffleModeEnabled = on }

    fun setRepeat(mode: app.aether.core.model.RepeatMode) = with { c ->
        c.repeatMode = when (mode) {
            app.aether.core.model.RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            app.aether.core.model.RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun cycleRepeat() = with { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // --- State ---

    /** Tracks already read from the session, by song, with what they were read from. */
    private val known = HashMap<String, Pair<String, Track>>()

    /**
     * The queue as the session has it. A song read before, with the same name, artist and length, is
     * the same [Track] (reading one is unpacking its whole bundle off the session); a queue that came
     * out the same is the same list, so nothing that shows it is asked again.
     */
    private fun rebuildQueue(c: MediaController) {
        val seen = HashSet<String>()
        val next = (0 until c.mediaItemCount).mapNotNull { i ->
            val item = c.getMediaItemAt(i)
            val meta = item.mediaMetadata
            val mark = "${meta.title}\u0000${meta.artist}\u0000${meta.durationMs}"
            seen += item.mediaId
            known[item.mediaId]?.takeIf { it.first == mark }?.second
                ?: item.toTrack()?.also { known[item.mediaId] = mark to it }
        }
        known.keys.retainAll(seen)
        if (next != queue) queue = next
    }

    private fun publish() {
        val c = controller ?: return
        val status = when {
            c.playerError != null -> PlaybackStatus.ERROR
            c.mediaItemCount == 0 -> PlaybackStatus.IDLE
            seeking -> PlaybackStatus.SEEKING
            c.playbackState == Player.STATE_ENDED -> PlaybackStatus.ENDED
            c.playbackState == Player.STATE_BUFFERING -> if (readyOnce) PlaybackStatus.BUFFERING else PlaybackStatus.LOADING
            c.isPlaying -> PlaybackStatus.PLAYING
            c.playbackState == Player.STATE_IDLE -> PlaybackStatus.PAUSED
            else -> PlaybackStatus.PAUSED
        }
        val next = PlaybackState(
            status = status,
            queue = queue,
            index = if (c.mediaItemCount == 0) -1 else c.currentMediaItemIndex,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (queue.getOrNull(c.currentMediaItemIndex)?.durationMs ?: 0),
            bufferedMs = c.bufferedPosition.coerceAtLeast(0),
            shuffle = c.shuffleModeEnabled,
            repeat = when (c.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else -> RepeatMode.OFF
            },
            // Carried over to a copy mid-song, what's heard is the copy, measured by its own player.
            heard = route.getBundle(EXTRA_CARRIED)?.heardFormat() ?: c.currentTracks.heardFormat(),
            error = c.playerError?.let { describe(it) },
            outputDevice = route.getString(EXTRA_OUTPUT),
            bitPerfect = route.getBoolean(EXTRA_BIT_PERFECT, false),
        )
        // Where the song is moves on its own flow ([position]): an event that changed nothing but the
        // position or how much is buffered (the player reports one each time it loads a little more)
        // isn't a new state, so it doesn't ask every screen showing the song to be composed again.
        val now = _state.value
        if (next.copy(positionMs = now.positionMs, bufferedMs = now.bufferedMs) != now) _state.value = next
        _position.value = c.currentPosition.coerceAtLeast(0)
        ensureTicker(c)
    }

    /** Position moves on its own while playing: published four times a second, only then. */
    private fun ensureTicker(c: MediaController) {
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    delay(250)
                    val cc = controller ?: break
                    _position.value = cc.currentPosition.coerceAtLeast(0)
                }
            }
        }
    }

    private fun describe(error: PlaybackException): String {
        val cause = error.cause?.message
        return when {
            cause != null && !cause.contains("http", ignoreCase = true) && cause.length < 60 -> "$cause. Try again in a moment."
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "The connection dropped. Check your network and try again."
            else -> "This song couldn't play. Try it again, or skip to the next."
        }
    }

    private inner class Listener : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) rebuildQueue(c)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) readyOnce = false
            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) readyOnce = true
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) || player.playbackState == Player.STATE_READY) seeking = false
            publish()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) = Unit
    }
}

/** What the decoder of [this]'s selected audio says it is: measured, never claimed. */
internal fun Tracks.heardFormat(): HeardFormat? {
    val group = groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return null
    val format = (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } ?: return null
    val mime = format.sampleMimeType
    val lossless = mime in setOf(MimeTypes.AUDIO_FLAC, MimeTypes.AUDIO_ALAC, MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV)
    val depth = when (format.pcmEncoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> null
    }
    return HeardFormat(
        codec = mime?.substringAfter('/')?.uppercase(),
        sampleRateHz = format.sampleRate.takeIf { it != androidx.media3.common.Format.NO_VALUE },
        channels = format.channelCount.takeIf { it != androidx.media3.common.Format.NO_VALUE },
        bitDepth = if (lossless) depth else null,
        bitrateKbps = format.bitrate.takeIf { it != androidx.media3.common.Format.NO_VALUE && it > 0 }?.let { it / 1000 },
        lossless = lossless,
    )
}

/** A measured format, carried across from the service in its session's extras. */
internal fun HeardFormat.toBundle(): android.os.Bundle = android.os.Bundle().apply {
    codec?.let { putString("codec", it) }
    sampleRateHz?.let { putInt("rate", it) }
    channels?.let { putInt("channels", it) }
    bitDepth?.let { putInt("depth", it) }
    bitrateKbps?.let { putInt("kbps", it) }
    putBoolean("lossless", lossless)
}

internal fun android.os.Bundle.heardFormat(): HeardFormat = HeardFormat(
    codec = getString("codec"),
    sampleRateHz = getInt("rate", 0).takeIf { it > 0 },
    channels = getInt("channels", 0).takeIf { it > 0 },
    bitDepth = getInt("depth", 0).takeIf { it > 0 },
    bitrateKbps = getInt("kbps", 0).takeIf { it > 0 },
    lossless = getBoolean("lossless", false),
)
