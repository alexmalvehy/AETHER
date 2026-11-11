package app.aether.player

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.aether.core.model.EqProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.aether.core.model.HeardFormat
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.PlayRecorder
import app.aether.core.model.QueueStore
import app.aether.core.model.RepeatMode
import app.aether.core.model.SavedQueue
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import android.os.Build
import android.os.Bundle

/** What the service needs from the app: streams, a place for the queue, a record of plays. */
interface PlaybackEnvironment {
    /**
     * The audio for [id]. Listening, with the whole [track] in hand, an exact copy from one of the
     * listener's own sources may be chosen; read again without it, the same choice. Keeping is the
     * song's own source.
     */
    suspend fun stream(id: MediaId, purpose: StreamPurpose = StreamPurpose.LISTEN, track: Track? = null): Outcome<Stream>
    fun invalidate(id: MediaId)

    /** Where a kept song's file is on the phone (the listener's Music folder), when it's been kept there. */
    fun keptFile(id: MediaId): android.net.Uri? = null

    /** A song as AETHER knows it in full (kept on the phone, say), to mend a saved queue that lost its details. */
    fun knownTrack(id: MediaId): Track? = null
    val queueStore: QueueStore
    val recorder: PlayRecorder
    val http: OkHttpClient

    /** What the music should go through; null when nothing should touch it. */
    val equaliser: StateFlow<EqProfile?>

    /** Seconds the end of one song and the start of the next overlap; 0 is off. */
    val crossfadeSeconds: StateFlow<Int>

    /** A song's id, each time a copy of it from one of the listener's sources turns up after it began. */
    val lateCopies: Flow<MediaId> get() = emptyFlow()

    /** That copy, to carry the song over to; null once it's gone or been chosen. */
    fun lateCopy(id: MediaId): Stream? = null

    /** The song is heard from its late copy now, or from its own source again. */
    fun hearingCopy(id: MediaId, hearing: Boolean) {}

    /** The song goes over to its late copy for good: read again, it's the copy. False when there isn't one. */
    fun adoptCopy(id: MediaId): Boolean = false

    /** The late copy wouldn't play: it isn't offered again for a while. */
    fun dropLateCopy(id: MediaId) {}
    fun sessionActivity(): PendingIntent?

    /** Reports continuous playback of [track] upstream when history sync is enabled. */
    suspend fun reportPlayback(track: Track, positionMs: Long) {}

    /** Observed set of liked track IDs so the system media notification heart updates live. */
    val likedIds: Flow<Set<MediaId>> get() = emptyFlow()

    /** Checks whether [id] is currently in the listener's Liked Songs. */
    suspend fun isLiked(id: MediaId): Boolean = false

    /** Toggles the liked state of [track] (local DB + two-way YouTube sync) and returns the new state. */
    suspend fun toggleLike(track: Track): Boolean = false

    /** Fetches the radio mix for [track] via InnerTube `next` endpoint with radio params. */
    suspend fun startRadio(track: Track): List<Track> = emptyList()

    /** Optional Google Cast & System Output Switcher manager. */
    val castManager: CastManager? get() = null
}

/**
 * The engine. It owns the player, runs in the foreground while music plays, survives the screens
 * going away, and is the only place that touches ExoPlayer: screens send commands through a
 * [PlaybackConnection] and read its state.
 *
 * - Streams resolve when the player first reads an item (`aether://track/<id>`), never earlier, so
 *   an address can't expire waiting in the queue. A stream refused mid-song is resolved afresh
 *   once and picks up where it stopped.
 * - Audio leaves the decoder as float where the device takes it: nothing above 16 bits is cut,
 *   nothing is resampled here. With no tuning chosen, the equaliser passes every sample through.
 * - The queue is written on every change and restored on a cold start, paused where it was.
 * - A song counts as played after 30 s, or half of it if shorter. Nothing else is recorded.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var env: PlaybackEnvironment
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var notificationActions: NotificationActionHandler
    private var saveJob: Job? = null
    /** The song last recovered, how many times in a row, and when last. */
    private var retried: Triple<String, Int, Long>? = null
    private var counted: String? = null
    private var trackedHistory: String? = null
    private var continuousPlayKey: String? = null
    private var continuousPlayMs: Long = 0L

    /** The bit-perfect route watcher (Android 14 and later); typed loosely so older phones never load it. */
    private var bitPerfect: Any? = null
    private var route = Route(null, false)
    private val equaliser = EqualiserProcessor()

    /** Crossfade's second player hears through an equaliser of its own, kept in step with the first's. */
    private val helperEqualiser = EqualiserProcessor()

    /** Each player's loudness, measured only while a copy is being lined up with its song by its sound. */
    private val meter = AudioMeter()
    private val helperMeter = AudioMeter()
    private lateinit var audio: CacheDataSource.Factory
    private lateinit var chosen: ChosenSources
    private lateinit var crossfader: Crossfader

    /** A copy found mid-song that couldn't be carried over to then: the song goes over at its next pause or seek. */
    private var pendingCopy: MediaId? = null

    /** What's heard while a song is carried by its copy: the copy's format, not the first player's. */
    private var carried: HeardFormat? = null

    override fun onCreate() {
        super.onCreate()
        env = application as PlaybackEnvironment
        // Kept songs play from the phone; everything else streams (and is never written to the keep).
        audio = CacheDataSource.Factory()
            .setCache(AudioStore.cache(this))
            .setCacheKeyFactory(AudioStore::keyOf)
            .setUpstreamDataSourceFactory(AudioStore.upstream(this, StreamPurpose.LISTEN))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        chosen = ChosenSources(this, env, DefaultMediaSourceFactory(audio))
        player = ExoPlayer.Builder(this, ShapedRenderers(this, equaliser, meter).setEnableAudioFloatOutput(true))
            // Each song's way of playing is chosen as the player reaches it (ChosenSources); a song is
            // prepared only then, never the whole queue at once.
            .setMediaSourceFactory(chosen)
            .setUseLazyPreparation(true)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(Watcher())
        crossfader = Crossfader(
            main = player,
            newHelper = ::buildHelper,
            songAt = ::crossfadeSong,
            scope = scope,
            // Bit-perfect, the mixer takes one stream through untouched: two songs at once aren't that.
            blocked = { route.bitPerfect },
            onCarrying = ::onCarrying,
            onCarryEnded = ::onCarryEnded,
            soundAlignment = { SoundAlignment(meter, helperMeter) },
        )
        // The player's notification wears AETHER's mark in the status bar, not Media3's play symbol.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.aether_mark) },
        )
        notificationActions = NotificationActionHandler(scope, env, { player }, { session })
        session = MediaLibrarySession.Builder(this, player, Callback())
            .apply { env.sessionActivity()?.let { setSessionActivity(it) } }
            .build()
        notificationActions.refreshCustomLayout()
        if (Build.VERSION.SDK_INT >= 34) {
            bitPerfect = BitPerfect(this, player) { now ->
                route = now
                reportRoute()
            }.also { it.start() }
        }
        scope.launch {
            env.equaliser.collect {
                equaliser.update(it)
                helperEqualiser.update(it)
                reportRoute()
            }
        }
        scope.launch { env.crossfadeSeconds.collect { crossfader.seconds = it } }
        scope.launch { env.lateCopies.collect(::carryOver) }
        scope.launch { env.likedIds.collect { notificationActions.refreshCustomLayout() } }
        scope.launch { restore() }
        scope.launch { countPlays() }
    }

    /** Samples an equaliser has changed aren't the file's, whatever the path does with them afterwards. */
    private fun reportRoute() {
        session?.setSessionExtras(Bundle().apply {
            putString(EXTRA_OUTPUT, route.device)
            putBoolean(EXTRA_BIT_PERFECT, route.bitPerfect && !equaliser.shaping)
            carried?.let { putBundle(EXTRA_CARRIED, it.toBundle()) }
        })
    }

    // --- A better copy found mid-song (PODIUM's D-92) ------------------------------------------------

    /**
     * A copy of a song from one of the listener's sources turned up after the song had begun from its
     * own source. If that song is playing, it's carried over to the copy now, unheard but for a short
     * crossfade; paused, it simply goes over; when neither can be done (a DAC playing bit-perfect,
     * a crossfade under way, too little left), it goes over at the next pause or seek.
     */
    private fun carryOver(id: MediaId) {
        val item = player.currentMediaItem ?: return
        // The song it's for has gone by: it's that song's copy from its next play.
        if (item.mediaId != id.value) return
        val copy = env.lateCopy(id) ?: return
        when {
            !player.playWhenReady -> switchToCopy(id, player.currentPosition)
            !crossfader.carry(id.value, chosen.copySource(copy, item)) -> pendingCopy = id
        }
    }

    private fun onCarrying(uid: String) {
        // What's heard is the copy now: its format, measured by its own player, and where it's from.
        carried = crossfader.helperPlayer?.currentTracks?.heardFormat()
        env.hearingCopy(MediaId(uid), true)
        reportRoute()
    }

    private fun onCarryEnded(end: Crossfader.CarryEnd) {
        val id = MediaId(end.uid)
        carried = null
        reportRoute()
        when (end.reason) {
            // Paused, or sought: [player] goes over to the copy, unheard, where the music is.
            Crossfader.CarryReason.PAUSED -> switchToCopy(id, if (end.heard) end.positionMs else player.currentPosition)
            Crossfader.CarryReason.SOUGHT -> switchToCopy(id, player.currentPosition)
            // The song is over: the copy is its own from its next play.
            Crossfader.CarryReason.ENDED, Crossfader.CarryReason.MOVED_ON -> env.hearingCopy(id, false)
            // The copy wouldn't play: it isn't offered again for a while.
            Crossfader.CarryReason.COPY_FAILED -> env.dropLateCopy(id)
            // It couldn't be got level in time, or the moment went: over at the next pause or seek.
            Crossfader.CarryReason.GAVE_UP, Crossfader.CarryReason.FAILED, Crossfader.CarryReason.CANCELLED -> later(id, end.heard)
        }
    }

    private fun later(id: MediaId, heard: Boolean) {
        if (heard) env.hearingCopy(id, false)
        pendingCopy = id
    }

    /**
     * [player] goes over to [id]'s copy for good, at [positionMs]: the same song in the queue, played
     * the new way (ChosenSources chooses it afresh, and the copy is now the one chosen).
     */
    private fun switchToCopy(id: MediaId, positionMs: Long) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (item.mediaId != id.value) return
        pendingCopy = null
        if (!env.adoptCopy(id)) return
        player.replaceMediaItem(index, item)
        player.seekTo(index, positionMs)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Closing the app while music plays keeps it playing; with nothing playing, the service goes.
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 34) (bitPerfect as? BitPerfect)?.stop()
        saveNow()
        crossfader.release()
        session?.release()
        session = null
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Crossfade's second player: the same sound path and the same way of choosing each song's audio,
     * silent until it carries a song's end. It never takes the audio focus or the session; [player]
     * keeps both.
     */
    private fun buildHelper(): ExoPlayer = ExoPlayer.Builder(this, ShapedRenderers(this, helperEqualiser, helperMeter).setEnableAudioFloatOutput(true))
        .setMediaSourceFactory(ChosenSources(this, env, DefaultMediaSourceFactory(audio)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
        .build()

    /** What crossfade's rules need of the song at a place in the queue. */
    private fun crossfadeSong(index: Int): CrossfadeRules.Song? {
        if (index !in 0 until player.mediaItemCount) return null
        val track = player.getMediaItemAt(index).toTrack() ?: return null
        val duration = track.durationMs ?: player.duration.takeIf { index == player.currentMediaItemIndex && it != C.TIME_UNSET && it > 0 }
        // The album by its id where the source gives one; on the phone, by its name and its maker.
        val album = track.album?.let { it.id?.value ?: "${it.title}|${track.artists.firstOrNull()?.name.orEmpty()}" }
        return CrossfadeRules.Song(album, track.trackNumber, duration)
    }

    private suspend fun restore() {
        if (player.mediaItemCount > 0) return
        val saved = withContext(Dispatchers.IO) { runCatching { env.queueStore.load() }.getOrNull() } ?: return
        if (player.mediaItemCount > 0) return
        // A queue saved while a kept song played from its file could lose that song's details (1.x and
        // 2.0): mended from what's known of it.
        val tracks = saved.tracks.map { t -> if (t.title.isBlank()) env.knownTrack(t.id) ?: t else t }
        player.setMediaItems(tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = when (saved.repeat) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.prepare()
    }

    private fun snapshot(): SavedQueue = SavedQueue(
        tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toTrack() },
        index = player.currentMediaItemIndex.coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        shuffle = player.shuffleModeEnabled,
        repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        },
    )

    private fun saveSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            val q = snapshot()
            withContext(Dispatchers.IO + NonCancellable) { runCatching { env.queueStore.save(q) } }
        }
    }

    private fun saveNow() {
        val q = snapshot()
        runBlocking(Dispatchers.IO) { runCatching { env.queueStore.save(q) } }
    }

    /** Every few seconds while playing: the position for a restart, and whether this song now counts as played. */
    private suspend fun countPlays() {
        var ticks = 0
        while (scope.isActive) {
            delay(2_000)
            if (!player.isPlaying) continue
            val item = player.currentMediaItem ?: continue
            val key = "${player.currentMediaItemIndex}|${item.mediaId}"
            if (continuousPlayKey != key) {
                continuousPlayKey = key
                continuousPlayMs = 2_000L
            } else {
                continuousPlayMs += 2_000L
            }
            if (trackedHistory != key && continuousPlayMs >= 12_000L) {
                trackedHistory = key
                val pos = player.currentPosition.coerceAtLeast(12_000L)
                item.toTrack()?.let { t -> scope.launch(Dispatchers.IO) { runCatching { env.reportPlayback(t, pos) } } }
            }
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            if (counted != key && player.currentPosition >= minOf(30_000L, duration / 2)) {
                counted = key
                item.toTrack()?.let { t -> scope.launch(Dispatchers.IO) { runCatching { env.recorder.played(t) } } }
            }
            if (++ticks % 5 == 0) saveSoon()
        }
    }

    private inner class Watcher : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = saveSoon()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retried = null
            continuousPlayKey = null
            continuousPlayMs = 0L
            if (pendingCopy?.value != mediaItem?.mediaId) pendingCopy = null
            notificationActions.refreshCustomLayout()
            if (mediaItem != null && env.castManager?.isCasting?.value == true) {
                env.castManager?.transferToCast(mediaItem, 0L, player.playWhenReady)
            }
            saveSoon()
        }

        // A copy waiting for its moment: a pause, or a seek (which is a wait for the music anyway).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) pendingCopy?.let { switchToCopy(it, player.currentPosition) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) pendingCopy?.let { switchToCopy(it, player.currentPosition) }
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            // A new song may be a new format: the route is looked at again.
            if (Build.VERSION.SDK_INT >= 34) (bitPerfect as? BitPerfect)?.reconsider()
        }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSoon()
        override fun onRepeatModeChanged(repeatMode: Int) = saveSoon()
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) saveSoon() }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            // A song that fails, however it fails, is asked for afresh from where it stopped, twice at
            // most in a row: a copy from one of the listener's sources gives way to the song's own
            // stream, and a stream of its own that stopped being served (or never was) is resolved
            // again, from another of YouTube's apps or formats. Only then is the failure the listener's
            // to see. A song that plays a while before failing again starts over.
            val now = SystemClock.elapsedRealtime()
            val last = retried?.takeIf { it.first == item.mediaId && now - it.third < RecoveryWindowMs }
            val attempts = last?.second ?: 0
            if (attempts >= MaxRecoveries) return
            retried = Triple(item.mediaId, attempts + 1, now)
            Log.w("AETHER", "playback failed (${error.errorCodeName}); recovering ${item.mediaId}, attempt ${attempts + 1}", error)
            env.invalidate(MediaId(item.mediaId))
            val at = player.currentPosition
            player.seekTo(player.currentMediaItemIndex, at)
            // The listener's play or pause is kept as it was.
            player.prepare()
        }
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val currentTrack = player.currentMediaItem?.toTrack()
            val isLiked = currentTrack?.let { runBlocking { runCatching { env.isLiked(it.id) }.getOrDefault(false) } } ?: false
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(notificationActions.availableSessionCommands())
                .setCustomLayout(notificationActions.buildCustomLayout(isLiked))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<androidx.media3.session.SessionResult> {
            notificationActions.onCustomCommand(session, customCommand, args)?.let { return it }
            env.castManager?.onCustomCommand(session, customCommand, args)?.let { return Futures.immediateFuture(it) }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.playable() }.toMutableList())

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                val saved = withContext(Dispatchers.IO) { runCatching { env.queueStore.load() }.getOrNull() }
                    ?: throw UnsupportedOperationException("Nothing to resume")
                MediaSession.MediaItemsWithStartPosition(saved.tracks.map { it.toMediaItem() }, saved.index, saved.positionMs)
            }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder().setMediaId("root").setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder().setIsBrowsable(true).setIsPlayable(false).setTitle("AETHER").build(),
                    ).build(),
                    params,
                ),
            )

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf((0 until player.mediaItemCount).map { player.getMediaItemAt(it) }), params))
    }
}

/**
 * Turns `aether://track/<id>` into the source's stream as the player reads it: address, the
 * headers the source requires, and a cache key that survives the address changing. For listening
 * it's the stream chosen when the song was prepared (an exact copy, when one was chosen); for
 * keeping, always the song's own.
 */
@OptIn(UnstableApi::class)
internal class Resolver(private val env: PlaybackEnvironment, private val purpose: StreamPurpose) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.uri.trackId() ?: return dataSpec
        val outcome = runBlocking { env.stream(id, purpose) }
        val stream = when (outcome) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw IOException(describe(outcome.error))
        }
        return dataSpec.buildUpon()
            .setUri(stream.url)
            .setHttpRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
            .setKey(stream.cacheKey)
            .build()
    }

    private fun describe(error: SourceError): String = when (error) {
        SourceError.Offline -> "You're offline"
        SourceError.Network -> "The connection dropped"
        is SourceError.RateLimited -> "The music service is busy"
        is SourceError.NotFound -> "This song is no longer available"
        is SourceError.NotPlayable -> "This song can't play right now"
        else -> "This song couldn't be loaded"
    }
}

/** A failing song is recovered at most this many times in a row, a run ending once it has played this long. */
private const val MaxRecoveries = 2
private const val RecoveryWindowMs = 60_000L

internal const val EXTRA_OUTPUT = "aether.output"
internal const val EXTRA_CARRIED = "aether.carried"
internal const val EXTRA_BIT_PERFECT = "aether.bitPerfect"
