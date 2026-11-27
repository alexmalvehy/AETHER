package app.aether.player

import app.aether.automix.AutomixPerformanceMode
import app.aether.automix.MixBlend
import app.aether.automix.MixTransitionView
import app.aether.automix.SmartAnalysis
import app.aether.automix.TransitionWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automix as it stands, shared by the engine and the screens (they live in one process): the
 * settings BitChord's controller reads from its `AppSettings`, under the same names, and what it
 * publishes about the transition it's planning or playing. The engine writes; screens only read,
 * through [PlaybackConnection.automix].
 */
object AutomixState {
    // --- Read by the controller (set by the engine from the listener's settings) ---

    /** Automix is on. */
    val smartFadeEnabled = MutableStateFlow(false)

    /**
     * The listener's crossfade, in seconds, while Automix is on: the length of a plain crossfade
     * when a pair can't be mixed. Zero while Automix is off, so the controller does nothing then and
     * the engine's own crossfade plays as before.
     */
    val crossfadeSeconds = MutableStateFlow(0)

    /** AETHER has no speed control: always 1. */
    val playbackSpeed = MutableStateFlow(1f)

    /** No mix view in AETHER yet: the controller's previews never run. */
    val mixViewOpen = MutableStateFlow(false)

    /** How hard analysis may work. */
    val automixPerformanceMode = MutableStateFlow(AutomixPerformanceMode.BALANCED)

    // --- Published by the controller ---

    /** Where in the playing song the next transition is planned, as fractions of it; null when none is. */
    val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)

    /** A real mix is playing now: one a plain crossfade couldn't be (BitChord's `isRealMix`). */
    val smartMixInProgress = MutableStateFlow(false)

    /** The beat the blend in flight is running on, for drawing on it. */
    val smartMixBlend = MutableStateFlow<MixBlend?>(null)

    /** The two songs of a real mix in flight. */
    val smartMixView = MutableStateFlow<MixTransitionView?>(null)

    /** Unused in AETHER (it lays out the mix view before a blend). */
    val smartMixPreview = MutableStateFlow<MixTransitionView?>(null)

    /** How far through the real mix in flight, 0..1. */
    val smartMixProgress = MutableStateFlow(0f)

    /** Where the playing and next songs' analyses stand. */
    val smartAnalysis = MutableStateFlow(SmartAnalysis())

    /** One line on what Automix is doing, for the curious. */
    val automixDetail = MutableStateFlow<String?>(null)

    // --- AETHER's own ---

    private val _planned = MutableStateFlow<Pair<String, String>?>(null)

    /**
     * The two songs (media ids) of the transition planned for the playing song, while its window
     * shows: what the cover's morph can be made for ahead of time.
     */
    val planned: StateFlow<Pair<String, String>?> = _planned.asStateFlow()

    internal fun publishPlanned(pair: Pair<String, String>?) {
        _planned.value = pair
    }

    private val _incoming = MutableStateFlow<String?>(null)

    /**
     * The song (media id) Automix has started bringing in, from the moment it's loaded on the player
     * standing by until it has the session: it's already playing, unheard or rising, so it can't be
     * moved or taken out of the queue meanwhile.
     */
    val incoming: StateFlow<String?> = _incoming.asStateFlow()

    internal fun publishIncoming(id: String?) {
        _incoming.value = id
    }

    private val _blend = MutableStateFlow<Blend?>(null)

    /**
     * Any Automix transition in flight, real mix or plain crossfade: the two songs and how far
     * through. What the cover's morph follows.
     */
    val blend: StateFlow<Blend?> = _blend.asStateFlow()

    internal fun publish(blend: Blend?) {
        _blend.value = blend
    }

    /**
     * Debug builds only (`--es aether.preview morph`): the cover's morph from [fromId] to [toId] over
     * [seconds], as a mix would drive it, with the music left as it is. For looking at it and timing
     * its frames on a phone without waiting for a song to end.
     */
    fun preview(scope: kotlinx.coroutines.CoroutineScope, fromId: String, toId: String, seconds: Float) {
        scope.launch {
            publish(Blend(fromId, toId, 0f, System.nanoTime(), 1f / seconds))
            delay((seconds * 1000).toLong())
            publish(Blend(fromId, toId, 1f, System.nanoTime(), 0f))
            delay(200)
            publish(null)
        }
    }
}

/**
 * An Automix transition in flight, from [fromId] to [toId]. [progress] is where it was at
 * [atNanos] (`System.nanoTime()`), moving on by [perSecond] each second while it plays, so what's
 * drawn between the engine's ticks follows the music exactly; 0 when paused.
 */
data class Blend(
    val fromId: String,
    val toId: String,
    val progress: Float,
    val atNanos: Long,
    val perSecond: Float,
) {
    /** Where the blend is this instant. */
    fun progressAt(nanos: Long): Float =
        (progress + perSecond * ((nanos - atNanos).coerceAtLeast(0L) / 1e9f)).coerceIn(0f, 1f)
}
