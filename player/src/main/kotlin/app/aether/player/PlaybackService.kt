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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import app.aether.player.automix.TrackAnalyzer
import app.aether.player.cast.CastController
import app.aether.player.cast.CastMime
import app.aether.player.cast.CastPlayback
import app.aether.player.cast.CastSource
import app.aether.player.audio.DspChain
import app.aether.player.audio.OutputRoute
import app.aether.player.audio.ReplayGainProcessor
import app.aether.player.audio.ReplayGainUtil
import app.aether.player.audio.bitperfect.BitPerfectOutput
import app.aether.player.automix.TransitionFilterProcessor
import app.aether.player.automix.TransitionFilters
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

    /** What the home-screen widgets show has changed ([WidgetState] has the new answer): redraw them. */
    fun widgetsChanged() = Unit
    val recorder: PlayRecorder
    val http: OkHttpClient

    /** What the music should go through; null when nothing should touch it. */
    val equaliser: StateFlow<EqProfile?>

    /** Seconds the end of one song and the start of the next overlap; 0 is off. */
    val crossfadeSeconds: StateFlow<Int>

    /** Automix: songs measured and mixed into each other (BitChord's). */
    val automix: StateFlow<Boolean> get() = AutomixOff

    /** ReplayGain (Gramophone's): 0 off, 1 by song, 2 by album. */
    val replayGain: StateFlow<Int> get() = ReplayGainOff

    /** Lossless songs to a USB DAC untouched (BitChord's Android 14 bit-perfect output): the listener's choice. */
    val bitPerfect: StateFlow<Boolean> get() = AutomixOff

    /** What the song's own catalogue would play after [track] (its radio); empty when it has nothing. */
    suspend fun suggestionsAfter(track: Track): List<Track> = emptyList()

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
}

/**
 * The engine. It owns the player, runs in the foreground while music plays, survives the screens
 * going away, and is the only place that touches ExoPlayer: screens send commands through a
 * [PlaybackConnection] and read its state.
 *
 * - Streams resolve when the player first reads an item (`aether://track/<id>`), never earlier, so
 *   an address can't expire waiting in the queue. A stream refused mid-song is resolved afresh
 *   once and picks up where it stopped.
 * - Every decoded format goes through BitChord's precision sink: the equaliser, Automix's filters,
 *   ReplayGain and the meter run in float on 16-bit, 24-bit and float audio alike, and with nothing
 *   switched on every sample leaves as it came. What leaves is float where the route takes it (a DAC,
 *   headphones that say so) and 16-bit on the phone's own speaker (BitChord's rule); a decoder is
 *   asked for float only where a float track will open. Nothing is resampled here.
 * - FLAC goes to Android's own decoder, never Samsung's, which garbled 16-bit FLAC asked for float
 *   (issues #15, #16); Apple Lossless to Gramophone's decoder, ahead of any the phone has.
 * - With the listener's setting and a USB DAC, a lossless song goes to it untouched (BitChord's
 *   Android 14 bit-perfect output), past everything above.
 * - The queue is written on every change and restored on a cold start, paused where it was.
 * - A song counts as played after 30 s, or half of it if shorter. Nothing else is recorded.
 * - The queue never runs out by surprise (BitChord's AutoPlay): ten songs are kept waiting after the
 *   listener's own, suggested by the playing song's catalogue as each song begins ([topUp]).
 * - Two players, built the same way ([Peer]): one is the session's, the other stands by. Automix
 *   starts the next song on the one standing by, at its cue, and halfway through the blend the two
 *   trade places ([adopt]): the session, the audio focus and everything that follows the song move to
 *   the player now carrying it, with nothing re-buffered and nothing heard twice (BitChord's design).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var env: PlaybackEnvironment

    /** A TV as the speaker (BitChord's and LastWave's casting): the queue stays this phone's, the clock is the TV's. */
    private lateinit var cast: CastPlayback

    /** The player the session sees: the engine's, or, while a TV plays, the TV's ([SessionPlayer]). */
    private val speaker: Player get() = session?.player ?: player

    /**
     * One of the engine's two players with the sound path it was built with: its own equaliser,
     * meter, Automix filter and ReplayGain go where it goes.
     */
    private class Peer(
        val player: ExoPlayer,
        val equaliser: EqualiserProcessor,
        val meter: AudioMeter,
        val filter: TransitionFilterProcessor,
        val replayGain: ReplayGainProcessor,
    )

    /** The session's player: everything the listener sees and hears is from it, but a blend's other half. */
    private lateinit var active: Peer

    /** The player standing by for Automix, built when Automix is first on. */
    private var spare: Peer? = null
    private val player: ExoPlayer get() = active.player
    private val watcher = Watcher()
    private var session: MediaLibrarySession? = null
    private var saveJob: Job? = null
    /** The song last recovered, how many times in a row, and when last. */
    private var retried: Triple<String, Int, Long>? = null
    private var counted: String? = null

    /** What the bit-perfect output (BitChord's) last said: the DAC and whether a song is going to it untouched. */
    private var route = Route(null, false)

    /** Where the music is going, and so whether a float track is real there (BitChord's output policy). */
    private lateinit var output: OutputRoute

    /** Crossfade's second player hears through an equaliser of its own, kept in step with the first's. */
    private val helperEqualiser = EqualiserProcessor()

    /** Its loudness, measured only while a copy is being lined up with its song by its sound. */
    private val helperMeter = AudioMeter()

    /** And its ReplayGain, so a carried copy is levelled as its song is. */
    private val helperReplayGain = ReplayGainProcessor()
    private lateinit var audio: CacheDataSource.Factory
    private lateinit var chosen: ChosenSources
    private lateinit var crossfader: Crossfader

    /** A copy found mid-song that couldn't be carried over to then: the song goes over at its next pause or seek. */
    private var pendingCopy: MediaId? = null

    /** What's heard while a song is carried by its copy: the copy's format, not the first player's. */
    private var carried: HeardFormat? = null

    private lateinit var automix: AutomixController
    private lateinit var analyzer: TrackAnalyzer
    private lateinit var automixAudio: AutomixAudio

    override fun onCreate() {
        super.onCreate()
        env = application as PlaybackEnvironment
        output = OutputRoute(this).also { it.start() }
        // Lossless songs to a USB DAC untouched, while the listener has asked for it (BitChord's).
        BitPerfectOutput.enabled = { env.bitPerfect.value }
        BitPerfectOutput.watch(this)
        // Kept songs play from the phone; everything else streams (and is never written to the keep).
        audio = CacheDataSource.Factory()
            .setCache(AudioStore.cache(this))
            .setCacheKeyFactory(AudioStore::keyOf)
            .setUpstreamDataSourceFactory(AudioStore.listening(this))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        chosen = ChosenSources(this, env, DefaultMediaSourceFactory(audio))
        active = buildPeer(ownsSession = true, sources = chosen)
        // Before anything that asks whether a TV is playing (the crossfade, Automix) is built.
        cast = CastPlayback(this, scope, env.http, localPlayer = { player }, resolve = ::castSourceOf, onReceiverAdvanced = ::onTvAdvanced)
        player.addListener(watcher)
        crossfader = Crossfader(
            main = player,
            newHelper = ::buildHelper,
            songAt = ::crossfadeSong,
            scope = scope,
            // Bit-perfect, the mixer takes one stream through untouched: two songs at once aren't that.
            // And the sleep timer due at this song's end: there's no next song to run into (BitChord's).
            // And a TV playing: it plays one song at a time, from this phone, with no second player to mix in.
            blocked = { BitPerfectOutput.ready.value || SleepTimer.afterTrack.value || cast.active },
            onCarrying = ::onCarrying,
            onCarryEnded = ::onCarryEnded,
            soundAlignment = { SoundAlignment(active.meter, helperMeter) },
        )
        startAutomix()
        watchSleepTimer()
        // The player's notification wears AETHER's mark in the status bar, not Media3's play symbol.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.aether_mark) },
        )
        session = MediaLibrarySession.Builder(this, SessionPlayer(player, cast), Callback())
            .apply { env.sessionActivity()?.let { setSessionActivity(it) } }
            .build()
        publishWidget()
        CastController.ensureStarted(this)
        CastController.attach(cast)
        scope.launch {
            // Bit-perfect only where the song reached the DAC as it is: BitChord's own measure of it.
            BitPerfectOutput.status.collect { status ->
                route = Route(status.deviceName.takeIf { status.active }, status.active && status.exact)
                reportRoute()
            }
        }
        scope.launch { env.bitPerfect.collect { BitPerfectOutput.refreshReady(this@PlaybackService) } }
        scope.launch {
            env.equaliser.collect {
                active.equaliser.update(it)
                spare?.equaliser?.update(it)
                helperEqualiser.update(it)
                reportRoute()
            }
        }
        scope.launch {
            env.replayGain.collect {
                val mode = replayGainMode(it)
                active.replayGain.setMode(mode)
                spare?.replayGain?.setMode(mode)
                helperReplayGain.setMode(mode)
            }
        }
        // Automix on, it does every song's ending (a plain crossfade where a pair can't be mixed) and
        // the engine's own crossfade stands down; off, the crossfade plays as it always has.
        scope.launch {
            combine(env.automix, env.crossfadeSeconds) { on, seconds -> on to seconds }.collect { (on, seconds) ->
                if (on && spare == null) spare = buildPeer(ownsSession = false)
                AutomixState.crossfadeSeconds.value = if (on) seconds else 0
                AutomixState.smartFadeEnabled.value = on
                crossfader.seconds = if (on) 0 else seconds
            }
        }
        scope.launch { env.lateCopies.collect(::carryOver) }
        scope.launch { restore() }
        scope.launch { countPlays() }
    }

    /**
     * The bit-perfect output takes a song past the equaliser and everything else that changes samples, so
     * what it says is what reached the DAC (BitChord's status, exact only while no sample was rounded).
     */
    private fun reportRoute() {
        session?.setSessionExtras(Bundle().apply {
            putString(EXTRA_OUTPUT, route.device)
            putBoolean(EXTRA_BIT_PERFECT, route.bitPerfect)
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
            // Mid-blend, the session's player is half of a mix: the copy waits for the next pause or seek.
            automix.isTransitioning() -> pendingCopy = id
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
        if (automix.isTransitioning()) return
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
        if (!speaker.playWhenReady || player.mediaItemCount == 0) {
            saveNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        output.stop()
        saveNow()
        widgetShown?.let { if (it.playing) { WidgetState.save(this, it.copy(playing = false)); env.widgetsChanged() } }
        CastController.detach(cast)
        cast.release()
        automix.release()
        analyzer.release()
        crossfader.release()
        session?.release()
        session = null
        player.release()
        spare?.player?.release()
        spare = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * One of the two players, built the same way as the other: the same sound path, with its own
     * equaliser, meter and Automix filter, and the same way of choosing each song's audio (each song's
     * way of playing is chosen as the player reaches it; a song is prepared only then, never the whole
     * queue at once). Only the one with the session has the audio focus and hears headphones go.
     */
    private fun buildPeer(ownsSession: Boolean, sources: ChosenSources = ChosenSources(this, env, DefaultMediaSourceFactory(audio))): Peer {
        val equaliser = EqualiserProcessor().also { it.update(env.equaliser.value) }
        val meter = AudioMeter()
        val filter = TransitionFilterProcessor()
        val replayGain = ReplayGainProcessor().also { it.setMode(replayGainMode(env.replayGain.value)) }
        val dsp = DspChain(equaliser, filter, replayGain, meter)
        val renderers = ShapedRenderers(this, dsp, output, BitPerfectOutput.forContext(this)).setEnableAudioFloatOutput(true)
        val built = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(sources)
            .setUseLazyPreparation(true)
            .setAudioAttributes(Music, ownsSession)
            .setHandleAudioBecomingNoisy(ownsSession)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        return Peer(built, equaliser, meter, filter, replayGain)
    }

    private fun standby(): Peer = spare ?: buildPeer(ownsSession = false).also { spare = it }

    /**
     * Automix (BitChord's): the analyser, the audio it reads, and the controller that plans and plays
     * each transition across the two players.
     */
    private fun startAutomix() {
        automixAudio = AutomixAudio(this, env, scope)
        analyzer = TrackAnalyzer(
            context = this,
            audio = automixAudio,
            performance = { AutomixState.automixPerformanceMode.value },
            // Not while a TV plays: nothing is mixed there, and measuring would only spend the phone.
            wanted = { AutomixState.smartFadeEnabled.value && !cast.active },
        )
        automix = AutomixController(
            scope,
            active = { player },
            standby = { standby().player },
            onHandoff = ::adopt,
            analysisFor = { item -> analyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                val playing = item.mediaId == player.currentMediaItem?.mediaId
                automixAudio.know(item, playing = playing)
                // The song coming next is what a mix waits on: moved there, it's measured first.
                analyzer.request(item.mediaId, durationMs / 1000.0, urgent = !playing)
            },
            // "Incoming" and "outgoing" here are the session's player and the one standing by; the
            // controller puts its two songs on them by which side of the handoff it's on.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) = active.filter.setCutoffs(lowPassHz, highPassHz)
                override fun outgoing(lowPassHz: Float, highPassHz: Float) {
                    spare?.filter?.setCutoffs(lowPassHz, highPassHz)
                }
                // How far ahead of what's heard each filter is running: the sink measures it (BitChord's).
                override fun incomingLeadMs(): Long = active.filter.leadUs / 1000
                override fun outgoingLeadMs(): Long = spare?.filter?.leadUs?.div(1000) ?: 0L
                override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) = active.filter.setEcho(delaySeconds, send, dry)
                override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
                    spare?.filter?.setEcho(delaySeconds, send, dry)
                }
                override fun parkEchoes() {
                    active.filter.parkEcho()
                    spare?.filter?.parkEcho()
                }
            },
            analysisRunningFor = { item -> analyzer.isAnalysing(item.mediaId) },
            // Both sinks, not a role: the trim is about what the two sum to.
            onBlendHeadroom = { trim ->
                active.filter.setBlendTrim(trim)
                spare?.filter?.setBlendTrim(trim)
            },
            // A carry (or a plain crossfade) holds the session's player; the two never run at once.
            versionSwapActive = { crossfader.busy },
            // One stream at a time: bit-perfect, or a TV playing.
            bitPerfect = { BitPerfectOutput.ready.value || cast.active },
            mixable = ::mixable,
            // The sleep timer due at this song's end: Automix fades it out instead of mixing (BitChord's).
            sleepAfterTrack = { SleepTimer.afterTrack.value },
            onSleptAfterTrack = { SleepTimer.cancel() },
        )
        automix.start()
    }

    /** AETHER's rule: never across an album's next track (it's mastered to run into it). */
    private fun mixable(current: MediaItem, next: MediaItem): Boolean {
        val a = songOf(current) ?: return true
        val b = songOf(next) ?: return true
        return !CrossfadeRules.gapless(a, b)
    }

    private fun songOf(item: MediaItem): CrossfadeRules.Song? {
        val track = item.toTrack() ?: return null
        val album = track.album?.let { it.id?.value ?: "${it.title}|${track.artists.firstOrNull()?.name.orEmpty()}" }
        return CrossfadeRules.Song(album, track.trackNumber, track.durationMs)
    }

    /**
     * The handoff: halfway through a blend the session moves onto the player that started the incoming
     * song. Nothing is sought or re-buffered. The outgoing player lets go of the audio focus before the
     * incoming one takes it, so the app never holds two requests at once (BitChord's `adoptPlayer`).
     */
    private fun adopt(outgoing: ExoPlayer, incoming: ExoPlayer) {
        val from = active
        val to = spare?.takeIf { it.player === incoming } ?: return
        if (from.player !== outgoing) return
        outgoing.setAudioAttributes(Music, false)
        outgoing.setHandleAudioBecomingNoisy(false)
        incoming.setAudioAttributes(Music, true)
        incoming.setHandleAudioBecomingNoisy(true)
        outgoing.removeListener(watcher)
        // The roles move before anything is told, so what's read first describes the new arrangement.
        active = to
        spare = from
        incoming.addListener(watcher)
        crossfader.adopt(incoming)
        session?.player = SessionPlayer(incoming, cast)
        // A blend's incoming song becomes current without the queue moving on by itself, so "sleep after
        // this song" is honoured here too (BitChord's adoptPlayer).
        if (SleepTimer.afterTrack.value) sleepNow()
        // The incoming song began as its player's first item, which nothing heard: what a song
        // changing does, done here.
        songChanged(incoming.currentMediaItem)
        publishWidget()
        reportRoute()
    }

    /** A different song is the one playing now: a recovery run ends, a copy for another song waits no more. */
    // --- The sleep timer (BitChord's) ------------------------------------------------------------------

    /**
     * Pauses when the timer's deadline passes (`collectLatest`: setting it again restarts the wait
     * rather than both firing), and, with "after this song" and only a crossfade (Automix fades its own),
     * fades the song out over the crossfade's length as it ends, the crossfade itself standing down.
     */
    private fun watchSleepTimer() {
        scope.launch {
            SleepTimer.deadline.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                // Through the session's player: a TV playing is paused too.
                speaker.pause()
                SleepTimer.cancel()
            }
        }
        scope.launch {
            SleepTimer.afterTrack.collectLatest { on ->
                if (!on) {
                    sleepFadeEnd()
                    return@collectLatest
                }
                while (isActive) {
                    sleepFadeStep()
                    delay(SleepFadeStepMs)
                }
            }
        }
    }

    /** Whether [sleepFadeStep] has the session's player below full volume. */
    private var sleepFading = false

    private fun sleepFadeStep() {
        // A TV's song ends as it ends: there's no fading the phone's player under it.
        if (cast.active) return sleepFadeEnd()
        val seconds = crossfader.seconds
        if (AutomixState.smartFadeEnabled.value || seconds <= 0) return sleepFadeEnd()
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val fadeMs = seconds * 1000L
        val remaining = duration - player.currentPosition
        if (remaining > fadeMs) return sleepFadeEnd()
        // Equal power, as a crossfade's outgoing side falls.
        player.volume = CrossfadeRules.fadeOut(1f - remaining.toFloat() / fadeMs)
        sleepFading = true
    }

    private fun sleepFadeEnd() {
        if (!sleepFading) return
        sleepFading = false
        player.volume = 1f
    }

    /** "After this song" is due: paused here, at full volume for whenever play is pressed again. */
    private fun sleepNow() {
        speaker.pause()
        sleepFadeEnd()
        SleepTimer.cancel()
    }

    /** A TV went on to the next song by itself: what "sleep after this song" waits for there. */
    private fun onTvAdvanced() {
        if (SleepTimer.afterTrack.value) sleepNow()
    }

    /**
     * What the TV is handed for [item]: the song as this phone plays it. A kept song is its file here;
     * otherwise its stream as chosen for listening (the listener's own copy, when one was chosen), or its
     * own source's where that copy is a manifest that can't be handed on whole.
     */
    private suspend fun castSourceOf(item: MediaItem): CastSource? {
        val id = MediaId(item.mediaId)
        env.keptFile(id)?.let { file -> return CastSource(file, emptyMap(), CastMime.ofFile(this, file)) }
        val track = item.toTrack()
        for (purpose in listOf(StreamPurpose.LISTEN, StreamPurpose.KEEP)) {
            val stream = (withContext(Dispatchers.IO) { env.stream(id, purpose, track) } as? Outcome.Ok)?.value ?: continue
            if (CastMime.isManifest(stream.url, stream.mimeType)) continue
            return CastSource(android.net.Uri.parse(stream.url), stream.headers, CastMime.ofStream(this, stream.url, stream.mimeType))
        }
        return null
    }

    private fun songChanged(item: MediaItem?) {
        retried = null
        if (pendingCopy?.value != item?.mediaId) pendingCopy = null
        saveSoon()
        topUp()
    }

    // --- What plays on (BitChord's AutoPlay) ---------------------------------------------------------

    private var suggestJob: Job? = null

    /** The song the last suggestions were asked after: asked once a song, as it begins. */
    private var suggestedAfter: String? = null

    /**
     * Keeps [SuggestAhead] songs waiting after the one playing, asking its catalogue for what follows
     * it whenever fewer are left. Seeded on the song playing now, so what's suggested moves with the
     * music; never a song already queued, nor the same recording as another upload of it, nor more
     * than a few by any one artist ([Station]). Run as each song begins, here in the engine (not on a
     * screen), so a long listen with the app closed never runs dry; never while the queue repeats.
     */
    private fun topUp() {
        val p = player
        if (p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        val current = p.currentMediaItem?.toTrack() ?: return
        if (songsAhead(p) >= SuggestAhead) return
        if (suggestedAfter == current.id.value || suggestJob?.isActive == true) return
        suggestedAfter = current.id.value
        suggestJob = scope.launch {
            val suggested = runCatching { env.suggestionsAfter(current) }.getOrDefault(emptyList())
            val now = player
            // The song moved on meanwhile: the next one asks for itself.
            if (now.currentMediaItem?.mediaId != current.id.value || now.repeatMode != Player.REPEAT_MODE_OFF) return@launch
            val room = SuggestAhead - songsAhead(now)
            if (room <= 0) return@launch
            val queued = (0 until now.mediaItemCount).mapNotNull { now.getMediaItemAt(it).toTrack() }
            val fresh = Station.extend(queued, suggested, room, seed = current, preferVideos = current.kind != app.aether.core.model.MediaKind.SONG)
            if (fresh.isNotEmpty()) now.addMediaItems(fresh.map { it.toMediaItem() })
        }
    }

    /** How many songs follow the one playing, in the order they'll play (shuffled or not), up to enough. */
    private fun songsAhead(p: Player): Int {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return 0
        var count = 0
        var at = p.currentMediaItemIndex
        while (count < SuggestAhead) {
            at = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (at == C.INDEX_UNSET) break
            count++
        }
        return count
    }

    /**
     * Crossfade's second player: the same sound path and the same way of choosing each song's audio,
     * silent until it carries a song's end. It never takes the audio focus or the session; [player]
     * keeps both.
     */
    private fun buildHelper(): ExoPlayer = ExoPlayer.Builder(
        this,
        // A filter of its own that nothing moves, so its sound path is the players' own; and no
        // bit-perfect output, which is one stream at a time (crossfades stand down for it anyway).
        ShapedRenderers(this, DspChain(helperEqualiser, TransitionFilterProcessor(), helperReplayGain, helperMeter), output).setEnableAudioFloatOutput(true),
    )
        .setMediaSourceFactory(ChosenSources(this, env, DefaultMediaSourceFactory(audio)))
        .setAudioAttributes(Music, false)
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
        positionMs = speaker.currentPosition.coerceAtLeast(0),
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

    /** What the widgets last showed, so they're only redrawn when it changes. */
    private var widgetShown: WidgetState? = null

    /**
     * The widgets' state, as the session's player has it (a TV's, while one plays): saved, and the widgets
     * told, when it has changed. On the player's thread, so it hands everything else off.
     */
    private fun publishWidget() {
        val p = speaker
        val track = p.currentMediaItem?.toTrack()
        val state = if (track == null) WidgetState.EMPTY else WidgetState(
            id = track.id.value,
            title = track.title,
            artist = track.artistLine,
            artwork = track.artwork.url(WidgetState.ART_PX),
            playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED,
            hasNext = p.hasNextMediaItem(),
            nextArtwork = p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { p.getMediaItemAt(it).toTrack()?.artwork?.url(WidgetState.ART_PX) },
        )
        if (state == widgetShown) return
        widgetShown = state
        WidgetState.save(this, state)
        env.widgetsChanged()
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
            // What the widgets show, as the session's player has it: a TV, while one plays, changes without
            // the phone's player knowing.
            publishWidget()
            // The session's player: a song heard on a TV counts the same.
            val heard = speaker
            if (!heard.isPlaying) continue
            val item = player.currentMediaItem ?: continue
            val key = "${player.currentMediaItemIndex}|${item.mediaId}"
            val duration = heard.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
            if (counted != key && heard.currentPosition >= minOf(30_000L, duration / 2)) {
                counted = key
                item.toTrack()?.let { t -> scope.launch(Dispatchers.IO) { runCatching { env.recorder.played(t) } } }
            }
            if (++ticks % 5 == 0) saveSoon()
        }
    }

    private inner class Watcher : Player.Listener {
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            saveSoon()
            publishWidget()
            cast.onLocalQueueChanged()
            // A queue just played, or cut short: what plays on after it, once it has settled.
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scope.launch { topUp() }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // "Sleep after this song": the queue moving on by itself is the moment the song the listener
            // meant has finished. A repeat counts too, or the timer would never fire with repeat-one on.
            if (SleepTimer.afterTrack.value &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            ) sleepNow()
            songChanged(mediaItem)
            publishWidget()
            // A TV, if it's the speaker, is put on whatever became current (or is already there: it moved
            // first, which [CastPlayback] tells apart by the song).
            cast.onLocalTransition(mediaItem, reason)
        }

        // Nothing follows the last song, so there's nothing to pause on: the queue simply ran out, and
        // the timer is spent.
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) SleepTimer.cancel()
            publishWidget()
        }

        // A copy waiting for its moment: a pause, or a seek (which is a wait for the music anyway).
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishWidget()
            if (!playWhenReady) pendingCopy?.let { switchToCopy(it, player.currentPosition) }
            // Something started the phone's own player while a TV is the speaker: the TV plays instead.
            if (playWhenReady && cast.active) cast.onLocalStartedPlaying(player)
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) pendingCopy?.let { switchToCopy(it, player.currentPosition) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveSoon()
        override fun onRepeatModeChanged(repeatMode: Int) {
            saveSoon()
            cast.onLocalRepeatChanged(repeatMode)
            if (repeatMode == Player.REPEAT_MODE_OFF) {
                suggestedAfter = null
                topUp()
            }
        }
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

/** Songs kept waiting after the one playing (BitChord's `MAX_QUEUED_AUTOPLAY`). */
private const val SuggestAhead = 10

/** Music, as the players play it. */
private val Music: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

/** How often the sleep timer's fade-out (a crossfade's length, at the song's end) steps the volume. */
private const val SleepFadeStepMs = 50L

/** Automix's setting for an environment that has none (and bit-perfect's). */
private val AutomixOff: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(false)

/** ReplayGain's setting for an environment that has none. */
private val ReplayGainOff: StateFlow<Int> = kotlinx.coroutines.flow.MutableStateFlow(0)

/** The setting's number as Gramophone's mode: 1 by song, 2 by album, anything else off. */
private fun replayGainMode(setting: Int): ReplayGainUtil.Mode = when (setting) {
    1 -> ReplayGainUtil.Mode.Track
    2 -> ReplayGainUtil.Mode.Album
    else -> ReplayGainUtil.Mode.None
}

/** How the music leaves the phone: the DAC it plays to, and whether that path is bit-perfect. */
internal data class Route(val device: String?, val bitPerfect: Boolean)

internal const val EXTRA_OUTPUT = "aether.output"
internal const val EXTRA_CARRIED = "aether.carried"
internal const val EXTRA_BIT_PERFECT = "aether.bitPerfect"
