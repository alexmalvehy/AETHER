package app.aether.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.runtime.State
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.FluidValue
import app.aether.core.designsystem.theme.retained

/** The dial's foot: how far its pivot sits above the bottom of its box. */
private val Foot = 6.dp

/** Room above the arc for the hand, which runs a little past the marks. */
private val Crown = 14.dp

/** Every third degree a mark; every fifth mark (each 15°) a long one. */
private const val Marks = 60

/**
 * A half dial standing on the foot of the screen, read like a watch: fine marks around the arc, one
 * thin red hand at how far the song has got (the song's time, not the clock's), and the time gone
 * and left at its heart. The marks the hand has passed are brighter.
 *
 * Seek by dragging around the arc, and only around it: a drag has to start on the marks and travel
 * along them, so a pull down to leave, or a stray touch, never moves the song. The hand follows the
 * finger, ticking as it passes each long mark, and the song moves when the finger lifts. For
 * accessibility services it's a slider with the position as its value.
 *
 * Its box should be twice as wide as it is tall, give or take; the arc is fitted to it.
 *
 * [window], when there is one, is where Automix will run this song into the next, as fractions of
 * the song: that stretch of the dial's inner ring is drawn brighter.
 */
@Composable
fun ClockDial(
    position: State<Long>,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScrubbing: (Boolean) -> Unit = {},
    running: Boolean = true,
    window: () -> Pair<Float, Float>? = { null },
) {
    val haptics = LocalHaptics.current
    val positionMs = position.value
    val seek by rememberUpdatedState(onSeek)
    val scrubbingNow by rememberUpdatedState(onScrubbing)
    val head = rememberPlayhead(positionMs, durationMs, playing && running)
    val hand = head.shown
    // Where the finger has the hand, and where the hand is: it glides after the finger on a stiff
    // spring with no overshoot, so the touch screen's jitter never shows as a jump.
    var scrub by remember { mutableStateOf<Float?>(null) }
    val glide = remember { FluidValue(0f, FluidSpring(0.11f, 1f), unit = 1f / 900f) }
    var glided by remember { mutableFloatStateOf(0f) }
    val scrubbing = scrub != null
    LaunchedEffect(scrubbing) {
        if (!scrubbing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            scrub?.let { glide.retarget(it) }
            glided = glide.step(((now - last) / 1e9f).coerceIn(0f, 0.05f))
            last = now
        }
    }
    // Automix's window, as drawn: a new plan (a song moved into next, a plan refined) glides there; one
    // that appears comes up where it is, and one let go of fades where it was. Never a jump.
    val mix = remember { MixArc() }
    LaunchedEffect(Unit) { snapshotFlow { window() }.collectLatest { mix.glideTo(it) } }
    // The words change once a second; only the drawing follows every frame. They say where the song
    // is (or where the finger has it), never where a travelling hand happens to be.
    val second by remember(durationMs) { derivedStateOf { ((scrub ?: head.time.floatValue) * durationMs).toLong() / 1000 } }
    val shownMs = second * 1000

    Box(
        modifier
            .semantics {
                contentDescription = "Position"
                stateDescription = if (durationMs > 0) "${clock(positionMs)} of ${clock(durationMs)}" else "Not started"
                progressBarRangeInfo = ProgressBarRangeInfo(if (durationMs > 0) positionMs.toFloat() / durationMs else 0f, 0f..1f)
                setProgress { target -> if (durationMs > 0) { seek((target.coerceIn(0f, 1f) * durationMs).toLong()); true } else false }
            }
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (durationMs <= 0) return@awaitEachGesture
                    val r = radiusOf(size.width.toFloat(), size.height.toFloat(), Foot.toPx(), Crown.toPx())
                    val pivot = Offset(size.width / 2f, size.height - Foot.toPx())
                    val from = down.position - pivot
                    val d = from.getDistance()
                    // On the marks, give or take a fingertip, and above the dial's foot.
                    if (d < r * 0.62f || d > r * 1.12f || from.y > 12.dp.toPx()) return@awaitEachGesture
                    val a0 = atan2(-from.y, from.x).coerceIn(0f, PI.toFloat())
                    // The way round the arc that moves the song on, and the way out from its heart.
                    val along = Offset(sin(a0), cos(a0))
                    val out = Offset(cos(a0), -sin(a0))
                    var scrubbing = false
                    var mark = -1
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = change.position - down.position
                        if (!scrubbing) {
                            if (moved.getDistance() < viewConfiguration.touchSlop) continue
                            // Travelling round the arc seeks; anything else (a pull down) is left to the page.
                            val round = abs(moved.x * along.x + moved.y * along.y)
                            val across = abs(moved.x * out.x + moved.y * out.y)
                            if (round < across * 0.9f) return@awaitEachGesture
                            scrubbing = true
                            // The hand sets off from where it's drawn, this frame: nothing to wait for.
                            glide.snap(hand.floatValue)
                            glided = hand.floatValue
                            scrubbingNow(true)
                            haptics.light()
                        }
                        change.consume()
                        val p = change.position - pivot
                        var a = atan2(-p.y, p.x)
                        // Below the foot it holds at the nearer end.
                        if (a < 0f) a = if (p.x < 0f) PI.toFloat() else 0f
                        val t = (1f - a / PI.toFloat()).coerceIn(0f, 1f)
                        scrub = t
                        val m = (t * Marks / 5).toInt()
                        if (m != mark) { if (mark >= 0) haptics.tick(); mark = m }
                    }
                    val target = scrub
                    if (scrubbing && target != null) {
                        // The hand stays where the finger left it while the song gets there.
                        head.landing(glided, target)
                    }
                    scrub = null
                    if (scrubbing) scrubbingNow(false)
                    if (scrubbing && target != null) {
                        haptics.light()
                        seek((target * durationMs).toLong())
                    }
                }
            },
    ) {
        Spacer(
            // A picture of itself, drawn again only when its hand moves (twelve times a second at
            // most): under something that moves every frame (Automix's dots), its sixty marks aren't
            // drawn again on each one.
            Modifier.fillMaxSize().retained().drawWithCache {
                // The marks, made once: every short mark one path, every long one another. A frame
                // then draws them twice (dim, and bright where the hand has passed, through a wedge).
                val r = radiusOf(size.width, size.height, Foot.toPx(), Crown.toPx())
                val pivot = Offset(size.width / 2f, size.height - Foot.toPx())
                val long = 13.dp.toPx()
                val short = 6.dp.toPx()
                val minor = Path()
                val major = Path()
                for (i in 0..Marks) {
                    val a = PI.toFloat() * (1f - i / Marks.toFloat())
                    val dir = Offset(cos(a), -sin(a))
                    val isMajor = i % 5 == 0
                    val from = pivot + dir * (r - if (isMajor) long else short)
                    val to = pivot + dir * r
                    (if (isMajor) major else minor).apply { moveTo(from.x, from.y); lineTo(to.x, to.y) }
                }
                val minorStroke = Stroke(1.dp.toPx(), cap = StrokeCap.Round)
                val majorStroke = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
                val inner = r - long - 7.dp.toPx()
                val wedge = Path()
                val reach = r + 16.dp.toPx()
                onDrawBehind {
                    val shown = if (scrub != null) glided else hand.floatValue
                    drawPath(minor, Ink.White.copy(alpha = 0.18f), style = minorStroke)
                    drawPath(major, Ink.White.copy(alpha = 0.4f), style = majorStroke)
                    if (shown > 0f) {
                        // The marks the hand has passed, brighter: the same paths, through a wedge to the hand.
                        wedge.reset()
                        wedge.moveTo(pivot.x, pivot.y)
                        wedge.arcTo(androidx.compose.ui.geometry.Rect(pivot, reach), 180f, 180f * shown + 0.6f, false)
                        wedge.close()
                        clipPath(wedge) {
                            drawPath(minor, Ink.White.copy(alpha = 0.58f), style = minorStroke)
                            drawPath(major, Ink.White.copy(alpha = 0.92f), style = majorStroke)
                        }
                    }
                    // A hairline arc just inside the marks, so the dial reads as one instrument.
                    drawArc(
                        Ink.White.copy(alpha = 0.08f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(pivot.x - inner, pivot.y - inner),
                        size = androidx.compose.ui.geometry.Size(inner * 2, inner * 2),
                        style = Stroke(1.dp.toPx()),
                    )
                    // Where Automix will mix: that stretch of the inner ring, brighter.
                    if (mix.shown > 0.002f) {
                        val from = mix.start.coerceIn(0f, 1f)
                        val to = mix.end.coerceIn(from, 1f)
                        if (to - from > 0.002f) drawArc(
                            Ink.White.copy(alpha = 0.72f * mix.shown.coerceIn(0f, 1f)),
                            startAngle = 180f + 180f * from,
                            sweepAngle = 180f * (to - from),
                            useCenter = false,
                            topLeft = Offset(pivot.x - inner, pivot.y - inner),
                            size = androidx.compose.ui.geometry.Size(inner * 2, inner * 2),
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    // The hand: thin, red, from inside the marks to just past them, with a bead at its foot.
                    val a = PI.toFloat() * (1f - shown)
                    val dir = Offset(cos(a), -sin(a))
                    val foot = pivot + dir * (r * 0.74f)
                    drawLine(Ink.Signal, foot, pivot + dir * (r + 7.dp.toPx()), strokeWidth = if (scrub != null) 2.4.dp.toPx() else 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Ink.Signal, radius = 2.6.dp.toPx(), center = foot)
                }
            },
        )
        // The time gone, large in the serif, and what's left beneath it, at the dial's heart.
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = Foot + 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(clock(shownMs), Type.Title.copy(fontSize = 22.sp, lineHeight = 26.sp), color = if (scrub != null) Ink.White else Ink.White.copy(alpha = 0.92f))
            Txt(if (durationMs > 0) "−" + clock((durationMs - shownMs).coerceAtLeast(0)) else "", Type.Meta, color = Ink.Grey)
        }
    }
}

private fun radiusOf(width: Float, height: Float, foot: Float, crown: Float): Float = min(width / 2f - crown, height - foot - crown).coerceAtLeast(1f)

/** Further than this between where the hand is and where the song is, the hand travels there. */
private const val JumpFraction = 0.015f

/**
 * Where the song is, as a fraction ([time]), and where the hand is drawn ([shown]). They're the same
 * but when the song jumps (another song, a skip back, a line of the lyrics touched): then the hand
 * travels to it on a spring and doesn't leap, so a new song's hand sweeps back to its start.
 */
@androidx.compose.runtime.Stable
private class Playhead {
    val time = mutableFloatStateOf(0f)
    val shown = mutableFloatStateOf(0f)
    private var reportMs = 0L
    private var reportAt = 0L
    private var durationMs = 0L
    private var playing = false
    private var placed = false

    /** A seek just made by the finger: where the song will be, until the player says so. */
    private var expected = -1f
    private var expectedAt = 0L
    val travel = FluidValue(0f, FluidSpring(0.55f, 1f), unit = 1f / 900f)
    var traveling = false
    var lastFrame = 0L

    /** Where the song is at [now] (`System.nanoTime()`), from the player's last report. */
    fun truthAt(now: Long): Float {
        if (durationMs <= 0) return 0f
        val ms = reportMs + if (playing) ((now - reportAt) / 1_000_000).coerceAtLeast(0L) else 0L
        return (ms.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    /** The player's word: where the song is; and whether the hand should travel there. */
    fun report(positionMs: Long, duration: Long, isPlaying: Boolean, now: Long) {
        val sameSong = duration == durationMs
        reportMs = positionMs
        reportAt = now
        durationMs = duration
        playing = isPlaying
        // A seek the finger made shows where it went until the player has got there.
        if (expected >= 0f) {
            val there = sameSong && abs(truthAt(now) - expected) < JumpFraction
            if (there || now - expectedAt > ExpectNanos || !sameSong) {
                expected = -1f
            } else {
                reportMs = (expected * durationMs).toLong() + if (isPlaying) (now - expectedAt) / 1_000_000 else 0L
            }
        }
        val target = truthAt(now)
        time.floatValue = target
        when {
            !placed -> {
                placed = true
                shown.floatValue = target
            }
            traveling -> Unit
            abs(target - shown.floatValue) > JumpFraction -> {
                travel.snap(shown.floatValue)
                travel.retarget(target)
                traveling = true
                lastFrame = 0L
            }
            else -> shown.floatValue = target
        }
    }

    /** A scrub let go at [at] (where the hand is) for [target]: the song is on its way there. */
    fun landing(at: Float, target: Float) {
        traveling = false
        shown.floatValue = at
        expected = target
        expectedAt = System.nanoTime()
        reportMs = (target * durationMs).toLong()
        reportAt = expectedAt
        time.floatValue = target
        if (abs(at - target) > 1e-4f) {
            travel.snap(at)
            travel.retarget(target)
            traveling = true
            lastFrame = 0L
        }
    }

    private companion object {
        /** A seek that the player hasn't confirmed by then is taken as having gone where the player says. */
        const val ExpectNanos = 1_500_000_000L
    }
}

/**
 * The song's position, moving smoothly between the player's reports while it plays: each report says
 * where it is, and the frames in between carry it on at the speed of time. A jump is travelled.
 */
@Composable
private fun rememberPlayhead(positionMs: Long, durationMs: Long, playing: Boolean): Playhead {
    val head = remember { Playhead() }
    LaunchedEffect(positionMs, durationMs, playing) {
        head.report(positionMs, durationMs, playing, System.nanoTime())
        var last = 0L
        while (head.traveling || (playing && durationMs > 0)) {
            withFrameNanos { now ->
                if (head.traveling) {
                    // Travelling: every frame, after a target that moves with the music, until it's there.
                    val dt = if (head.lastFrame == 0L) 1f / 90f else ((now - head.lastFrame) / 1e9f).coerceIn(0f, 0.05f)
                    head.lastFrame = now
                    val at = head.truthAt(now)
                    head.travel.retarget(at)
                    head.shown.floatValue = head.travel.step(dt)
                    head.time.floatValue = at
                    if (head.travel.resting) head.traveling = false
                } else if (now - last >= 83_000_000L) {
                    // Twelve steps a second: at a song's pace the hand moves a tenth of a degree a step,
                    // far under a pixel, and the screen isn't drawn again on every frame for nothing.
                    last = now
                    val at = head.truthAt(now)
                    head.time.floatValue = at
                    head.shown.floatValue = at
                }
            }
        }
    }
    return head
}

/**
 * Automix's window on the dial as it's drawn ([start], [end] as fractions of the song, [shown] how
 * brightly). A new window glides from the one drawn, keeping its speed if it was already moving; one
 * appearing comes up in place, with nothing sliding in from elsewhere; one let go of fades where it was.
 */
@androidx.compose.runtime.Stable
private class MixArc {
    var start by mutableFloatStateOf(0f)
        private set
    var end by mutableFloatStateOf(0f)
        private set
    var shown by mutableFloatStateOf(0f)
        private set
    private val from = FluidValue(0f, Glides, unit = 1f / 900f)
    private val to = FluidValue(0f, Glides, unit = 1f / 900f)
    private val alpha = FluidValue(0f, Fades, unit = 1f / 255f)

    suspend fun glideTo(target: Pair<Float, Float>?) {
        if (target != null) {
            if (shown <= 0.002f) {
                from.snap(target.first)
                to.snap(target.second)
            } else {
                from.retarget(target.first)
                to.retarget(target.second)
            }
            alpha.retarget(1f)
        } else {
            alpha.retarget(0f)
        }
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceIn(0f, 1f / 30f)
            last = now
            start = from.step(dt)
            end = to.step(dt)
            shown = alpha.step(dt)
            if (from.resting && to.resting && alpha.resting) break
        }
    }

    private companion object {
        val Glides = FluidSpring(0.55f, 1f)
        val Fades = FluidSpring(0.4f, 1f)
    }
}
