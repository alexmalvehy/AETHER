package app.aether.ui.player

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.DotMorph
import app.aether.core.designsystem.art.DotMorphLayer
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Motion
import app.aether.core.model.Track
import app.aether.player.Blend
import app.aether.ui.PrintPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Automix's change of song, on the cover: the cover goes up into the air as dots of its own colour
 * and comes down as the next song's, over exactly the blend (Home's figures change the same way). The
 * dots move with the music, not a clock of their own: each frame reads where the blend is.
 */
@Stable
internal class CoverMorphState {
    /** How much of the print shows: 1 but while the dots stand for it. */
    val veil = mutableFloatStateOf(1f)

    /**
     * True while the dots carry the change, so the print's own change of song (its wipe) stands
     * down: the handoff halfway through changes the song under them, unseen.
     */
    var carrying by mutableStateOf(false)
        internal set
}

/** Where on its way a morph at [t] is: the print fades to dots at the start, and the next print from them at the end. */
private fun printShown(t: Float): Float = when {
    t < 0.05f -> 1f - t / 0.05f
    t < 0.94f -> 0f
    else -> ((t - 0.94f) / 0.06f).coerceIn(0f, 1f)
}

private fun dotsShown(t: Float): Float = (t / 0.05f).coerceIn(0f, 1f) * (1f - ((t - 0.94f) / 0.06f).coerceIn(0f, 1f))

@Composable
internal fun CoverMorph(
    blends: StateFlow<Blend?>,
    planned: StateFlow<Pair<String, String>?>,
    queue: List<Track>,
    currentId: String,
    treatment: ArtTreatment,
    state: CoverMorphState,
    modifier: Modifier = Modifier,
) {
    // Phones before Android 14 have no programmable mesh, and reduced motion wants nothing flying:
    // the print changes as it always has.
    if (Build.VERSION.SDK_INT < 34 || LocalReduceMotion.current) return
    CoverMorph34(blends, planned, queue, currentId, treatment, state, modifier)
}

@RequiresApi(34)
@Composable
private fun CoverMorph34(
    blends: StateFlow<Blend?>,
    planned: StateFlow<Pair<String, String>?>,
    queue: List<Track>,
    currentId: String,
    treatment: ArtTreatment,
    state: CoverMorphState,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val songs by rememberUpdatedState(queue)
    val current by rememberUpdatedState(currentId)
    // The cover's own colour where the print shows it; a print in black and white, white dots.
    val colour = treatment == ArtTreatment.Original
    var box by remember { mutableStateOf(IntSize.Zero) }
    var pair by remember { mutableStateOf<Pair<String, String>?>(null) }
    var dots by remember { mutableStateOf<DotMorph?>(null) }
    val t = remember { mutableFloatStateOf(0f) }
    // Let go of early (a skip, a seek), the dots fade where they are and the print comes back.
    val presence = remember { Animatable(1f) }
    // Made ahead, while the transition's window shows, so nothing is made as the music starts to move.
    val made = remember { mutableStateOf<Made?>(null) }
    // One made at a time: the blend's start waits for the one being made ahead, and takes it.
    val making = remember { Mutex() }

    suspend fun make(from: String, to: String): DotMorph? = making.withLock {
        made.value?.takeIf { it.from == from && it.to == to && it.colour == colour && it.box == box }?.let { return@withLock it.morph }
        val a = songs.firstOrNull { it.id.value == from }?.artwork?.url(PrintPx)
        val b = songs.firstOrNull { it.id.value == to }?.artwork?.url(PrintPx)
        val size = box
        runCatching {
            val first = Pictures.bitmap(context, a, PrintPx) ?: return@runCatching null
            val next = Pictures.bitmap(context, b, PrintPx) ?: return@runCatching null
            withContext(Dispatchers.Default) {
                DotMorph.build(first, next, size.width, size.height, CoverDotDp * density, CoverAngle, CoverContrast, density, colour = colour)
            }
        }.onFailure { Log.w("AETHER", "cover morph unavailable", it) }.getOrNull()
            ?.also { made.value = Made(from, to, colour, size, it) }
    }

    LaunchedEffect(blends) {
        blends.map { b -> b?.let { it.fromId to it.toId } }.distinctUntilChanged().collect { pair = it }
    }
    // Let go of mid-morph (Now Playing closed under it): the print is given back as it was, never left veiled.
    androidx.compose.runtime.DisposableEffect(state) {
        onDispose {
            state.carrying = false
            state.veil.floatValue = 1f
        }
    }

    // The window is showing: the morph for that change is made now, in the background.
    LaunchedEffect(planned, colour) {
        planned.collect { next ->
            if (next == null || pair != null || next.first != current) return@collect
            snapshotFlow { box }.first { it != IntSize.Zero }
            make(next.first, next.second)
        }
    }

    LaunchedEffect(pair) {
        val (from, to) = pair ?: run {
            // The blend is over, or was let go of: what's still in the air fades where it is, and the
            // print comes back as it goes.
            if (dots != null && t.floatValue < 0.94f) {
                val hidden = 1f - printShown(t.floatValue)
                presence.animateTo(0f, Motion.fluid(0.35f, 1f)) { state.veil.floatValue = 1f - hidden * value }
            }
            dots = null
            made.value = null
            state.carrying = false
            state.veil.floatValue = 1f
            presence.snapTo(1f)
            return@LaunchedEffect
        }
        snapshotFlow { box }.first { it != IntSize.Zero }
        // The print of the cover coming, in memory before the song changes under the dots.
        songs.firstOrNull { it.id.value == to }?.artwork?.url(PrintPx)?.let { Pictures.prefetch(context, it, PrintPx) }
        val morph = make(from, to) ?: return@LaunchedEffect
        // Only from the cover it starts on: had the song already changed under the print (the dots
        // came too late), the print has made its own change, and one change is enough.
        if (current != from) return@LaunchedEffect
        dots = morph
        state.carrying = true
        presence.snapTo(1f)
        // The dots keep a clock of their own, running at the blend's own rate, and drawn gently toward
        // where the engine says the blend is: its word comes in steps (the player's position is coarse,
        // the phase lock nudges the speed), the dots move without them.
        var shown = -1f
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val blend = blends.value
                if (blend != null && blend.fromId == from && blend.toId == to) {
                    val truth = blend.progressAt(now)
                    val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    val ahead = shown + blend.perSecond * dt
                    shown = when {
                        shown < 0f || abs(truth - ahead) > ResyncAt -> truth
                        else -> ahead + (truth - ahead) * (dt / FollowSeconds).coerceAtMost(1f)
                    }.coerceIn(0f, 1f)
                    t.floatValue = shown
                }
                last = now
                state.veil.floatValue = printShown(t.floatValue)
            }
        }
    }

    Box(modifier.onSizeChanged { box = it }) {
        dots?.let { morph ->
            DotMorphLayer(
                morph,
                { t.floatValue },
                Modifier.fillMaxSize().graphicsLayer { alpha = dotsShown(t.floatValue) * presence.value },
            )
        }
    }
}

/** A morph made for one change of song, as the screen stood when it was made. */
private class Made(val from: String, val to: String, val colour: Boolean, val box: IntSize, val morph: DotMorph)

/** Further than this from where the engine says, the dots are put there; nearer, they're drawn to it. */
private const val ResyncAt = 0.05f

/** How long the dots take to come to where the engine says they should be. */
private const val FollowSeconds = 0.35f

/** The dots' screen: finer than a print's halftone, so a cover still reads as itself in them. */
private const val CoverDotDp = 3.1f
private const val CoverAngle = 0.6f
private const val CoverContrast = 1.15f
