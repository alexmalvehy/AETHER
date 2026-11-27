// Ported from BitChord's lyrics panel (sharedUi/.../player/PlayerLyrics.kt, GPL-3.0; its sweep after
// binimum's am-lyrics): its drawing, timings and falloff as BitChord has them, set in the book serif,
// and moving on AETHER's springs where BitChord runs its 400 ms curve.
package app.aether.ui.lyrics

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Serif
import app.aether.core.lyrics.LyricsLine
import app.aether.ui.lyrics.line.LyricClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Verse: the song as a page that's sung, as BitChord sings it (after am-lyrics, Apple Music's): every
 * line in the book serif at its heaviest, the line being sung bright and the rest a step back and a
 * little out of focus; each word lights as it's sung behind a soft leading edge, syllable by syllable
 * where the library times them, and lifts a hair off the line as it lands; a held note swells letter
 * by letter and gives off light. The answering vocal opens beneath its line only while it's sung, a
 * duet's second voice from the other side, Latin letters beneath a line in another script. Between
 * verses three points count the break out. The page moves a beat ahead of the singing, the lines after
 * the one it's moving to arriving a breath behind one another; touch a line to go to it, or turn the
 * page by hand, and it finds its place again once let be.
 */

/** The book serif at its heaviest, as BitChord's lyrics are set in their face's ExtraBold. */
private val Lead = TextStyle(fontFamily = Serif, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 41.sp)
private val BackingSize = 23.sp
private val BackingLeading = 29.sp

/** Latin letters under a line in another script: the serif's italic, as an aside is. */
private val GlossStyle = TextStyle(fontFamily = Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.W500, fontSize = 20.sp, lineHeight = 25.sp)

/**
 * Latin letters first: the words in their own script under their Latin letters, at the aside's size
 * but upright (a script with no italic of its own would only be slanted).
 */
private val OriginalStyle = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W500, fontSize = 20.sp, lineHeight = 26.sp)

/** What the words not yet sung of a line are held back to, so that a sung word reads at a glance. */
private const val UnsungAlpha = 0.45f

/** The bloom on held letters: how bright, how soft, and the room each line keeps around itself for it. */
private const val GlowAlpha = 0.62f
private val GlowRadius = 6.dp
private val GlowRoom = 10.dp

private const val BackingAlpha = 0.72f

/** The answering vocal opens this long before its first word, and folds away once its last is done. */
private const val BackingLeadMs = 250L
private const val BackingCloseDelayMs = 450L
private val BackingRise = 6.dp
private const val BackingRestScale = 0.94f
private const val BackingFadeLead = 1.6f

private const val GlossAlpha = 0.85f
private val GlossTuck = 6.dp

/** How far ahead of the singing the lit part fades out: lit solid up to now, gone over the next 30 dp. */
private val WipeFeather = 30.dp

/** How far a sung word lifts off its line, and how much room above its row a swelling letter gets. */
private val WordRise = 2.dp
private const val GrowHeadroom = 3f

/** In a duet, the room each voice leaves on the other's side. */
private val DuetLane = 44.dp
private val Gutter = 30.dp

private val GapRowHeight = 40.dp
private val GapRowSpacing = 16.dp
private const val GapDots = 3
private val GapDotSize = 13.dp
private val GapDotGap = 5.dp
private const val GapDotRest = 0.25f
private const val GapRestScale = 0.76f

/** Every other line, by its distance from the one being sung: shallow, so the lines around it stay readable. */
private val Falloff = floatArrayOf(1f, 0.74f, 0.6f, 0.48f, 0.4f, 0.34f)

/** And a little out of focus, more the further off it is (dp). */
private val FalloffBlur = floatArrayOf(0f, 1f, 1f, 1.7f, 2.4f)

/** Read by hand, the page flattens to one brightness so that no line is being pointed at. */
private const val BrowsingAlpha = 0.8f

/** The page sits fractionally back and the line being sung comes forward to meet you; a line under a finger dips. */
private const val InactiveScale = 0.98f
private const val PressedScale = 0.96f
private const val PressDelayMs = 90L

/** How long before a line is sung the page starts for it: the silence before it, kept between these. */
private const val ScrollLeadMinMs = 350L
private const val ScrollLeadMaxMs = 500L

/** The rows after the one the page is moving to set off a little later each, over this many rows. */
private const val StaggerSteps = 3
private const val StaggerFraction = 0.06f

/** BitChord's handover runs 400 ms on a curve that leaves quickly and lands softly: the spring it approximates. */
private const val SettleS = 0.4f

/** Where the line being sung stands, as a share of the page's height from its top. */
private const val Anchor = 0.3f

/** Once a turn by hand stops: back to the singing this soon if its line is in sight, this soon if not. */
private const val ReturnOnScreenMs = 600L
private const val ReturnAwayMs = 5_000L

/** Paused, how often the page looks for a seek. */
private const val PausedPollMs = 120L

private const val PhaseBefore = 0
private const val PhaseMoving = 1
private const val PhaseAfter = 2
private const val GapRunning = -1f

@Composable
internal fun Verse(
    lines: List<LyricsLine>,
    glosses: List<String?>,
    positionNow: () -> Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the page is following the song (true) or being read by hand (false). */
    onFollowing: (Boolean) -> Unit = {},
    /** The Latin letters sung as the lines, word by word, with the words in their own script beneath. */
    latinFirst: Boolean = false,
) {
    val reduce = LocalReduceMotion.current
    val rows = remember(lines) { VLine.of(lines) }
    val reveal = rememberGlossReveal(lines, glosses)
    // Latin letters first: each line's own timed words given in Latin letters, worked out once for the
    // song, off the main thread; a line with none (or the setting off) keeps its own words as its line.
    val shownGlosses = reveal.glosses
    val latinRows by produceState(emptyList<VLine?>(), rows, shownGlosses, latinFirst) {
        value = if (!latinFirst || shownGlosses.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            val japanese = Romaniser.japanese(lines.map { it.text })
            rows.map { row ->
                shownGlosses.getOrNull(row.origin)?.takeIf { it.isNotBlank() }?.let { whole -> VLine.latin(row, whole) { Romaniser.romanise(it, japanese) } }
            }
        }
    }
    val clock = rememberVerseClock(rows, positionNow, playing)
    // Only a song that names a second voice is laid out as a duet.
    val duet = remember(rows) { rows.any { it.alignEnd } }
    val glowing = !reduce && Build.VERSION.SDK_INT >= 31

    val active by remember(rows) { derivedStateOf { activeRows(rows, clock.longValue) } }
    // The uppermost line still sounding holds the page; brightness follows it, never the lead below.
    val scrollLine = active.firstOrNull() ?: -1
    // Where the page is heading, a beat ahead of the singing, so the words are under your eye as they land.
    val leadLine by remember(rows) {
        derivedStateOf {
            val now = clock.longValue
            activeRows(rows, now + scrollLead(rows, now)).firstOrNull() ?: -1
        }
    }
    val focus = if (leadLine >= 0) leadLine else scrollLine

    val list = remember(rows) { LazyListState() }
    val page = remember(rows) { Page() }
    val scope = rememberCoroutineScope()
    var browsing by remember(rows) { mutableStateOf(false) }
    var placed by remember(rows) { mutableStateOf(false) }

    LaunchedEffect(browsing) { onFollowing(!browsing) }
    // A finger on the page suspends the following, and nothing else.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) browsing = true }
    }
    val currentFocus by rememberUpdatedState(focus)
    val focusOnScreen by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.index == currentFocus } } }
    // Paused there's no song to follow back to: a turn by hand stays where it was left.
    LaunchedEffect(browsing, focusOnScreen, list.isScrollInProgress, playing) {
        if (playing && browsing && focusOnScreen && !list.isScrollInProgress) {
            delay(ReturnOnScreenMs)
            browsing = false
        }
    }
    LaunchedEffect(browsing, list.isScrollInProgress, playing) {
        if (playing && browsing && !list.isScrollInProgress) {
            delay(ReturnAwayMs)
            browsing = false
        }
    }
    // The page follows the song. A newer line takes over from a move still under way, keeping its speed.
    LaunchedEffect(focus, browsing, rows) {
        if (browsing || focus !in rows.indices) return@LaunchedEffect
        snapshotFlow { list.layoutInfo }.first { it.viewportSize.height > 0 }
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == focus }
        when {
            !placed || reduce -> {
                page.stop()
                list.scrollToItem(focus)
                placed = true
            }
            // On the page: its distance is known, so the move is the run-up's own length.
            item != null -> page.handOver(item.offset.toFloat(), focus, scrollLead(rows, clock.longValue), scope, list)
            // Off it, after a seek: the list's own way there.
            else -> {
                page.stop()
                list.animateScrollToItem(focus)
            }
        }
    }

    BoxWithConstraints(modifier) {
        val anchor = (maxHeight * Anchor - GlowRoom).coerceAtLeast(0.dp)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            // Each row keeps GlowRoom around itself for the bloom; the list hands that much back.
            contentPadding = PaddingValues(top = anchor, bottom = maxHeight * 0.8f, start = Gutter - GlowRoom, end = Gutter - GlowRoom),
        ) {
            itemsIndexed(rows, key = { i, _ -> i }) { i, line ->
                // Off the line being sung, not the one the page is heading for: brightness can't run ahead of the words.
                val offset = if (scrollLine < 0) 0 else i - scrollLine
                val step = min(abs(offset), Falloff.lastIndex)
                val isActive = i in active
                val blur by animateFloatAsState(
                    if (!glowing || browsing || isActive) 0f else FalloffBlur[min(step, FalloffBlur.lastIndex)],
                    Motion.fluid(SettleS, 1f, 0.01f), label = "verseBlur",
                )
                val lineAlpha by animateFloatAsState(
                    when {
                        isActive -> 1f
                        browsing -> BrowsingAlpha
                        else -> Falloff[step]
                    },
                    Motion.fluid(SettleS, 1f, 0.001f), label = "verseAlpha",
                )
                val defocus = if (blur > 0.01f) Modifier.blur(blur.dp, BlurredEdgeTreatment.Unbounded) else Modifier
                if (line.isGap) {
                    Gap(line, rows.getOrNull(i + 1)?.timeMs ?: line.endMs, isActive, clock, { lineAlpha }, defocus, onSeek)
                } else {
                    // Latin first, the Latin letters are the line and its own words go beneath it, lit as they are.
                    val latin = latinRows.getOrNull(i)
                    val gloss = if (latin != null) remember(latin) { VLine.gloss(line.text, latin) }
                    else reveal.glosses.getOrNull(line.origin)?.takeIf { it.isNotBlank() }?.let { text -> remember(line, text) { VLine.gloss(text, line) } }
                    VerseRow(
                        index = i,
                        line = latin ?: line,
                        gloss = gloss,
                        glossStyle = if (latin != null) OriginalStyle else GlossStyle,
                        glossOpen = reveal.progress,
                        clock = clock,
                        isActive = isActive,
                        sung = offset < 0,
                        focus = focus,
                        duet = duet,
                        browsing = browsing,
                        glowing = glowing,
                        reduce = reduce,
                        lineAlpha = { lineAlpha },
                        defocus = defocus,
                        page = page,
                        onSeek = onSeek,
                    )
                }
            }
        }
    }
}

/**
 * The song's time, frame by frame while it plays (AETHER's clock, leaning onto the player's reports),
 * as state read only where it's drawn. Paused, it follows a seek without asking the screen for frames.
 */
@Composable
private fun rememberVerseClock(key: Any, positionNow: () -> Long, playing: Boolean): MutableLongState {
    val clock = remember(key) { mutableLongStateOf(positionNow()) }
    val engine = remember(key) { LyricClock() }
    LaunchedEffect(engine, playing) {
        engine.reset()
        if (!playing) {
            while (true) {
                clock.longValue = positionNow()
                delay(PausedPollMs)
            }
        }
        while (true) {
            withFrameNanos { nanos -> clock.longValue = engine.update(nanos, positionNow(), true).toLong() }
        }
    }
    return clock
}

/** How long before a line lands the page starts for it, and how long the move takes: the run-up, bounded. */
private fun scrollLead(rows: List<VLine>, positionMs: Long): Long {
    val current = rows.indexOfLast { it.timeMs <= positionMs }
    if (current < 0) return ScrollLeadMinMs
    val next = rows.getOrNull(current + 1) ?: return ScrollLeadMinMs
    return (next.timeMs - rows[current].endMs).coerceIn(ScrollLeadMinMs, ScrollLeadMaxMs)
}

/**
 * The page's journey. Each handover is a spring of its own, from where the page will be once the
 * ones under way have landed, and the page goes the sum of them: a line coming while the last move is
 * still travelling takes over its speed rather than starting from rest. The rows after the line a move
 * is going to are held back against it by the same spring a little later, so they arrive one behind
 * another; a row with nothing to catch up on never reads the clock.
 */
@Stable
private class Page {
    class Run(val delta: Float, val focus: Int, val spring: FluidSpring, val startNanos: Long, val staggerMs: Float)

    var runs by mutableStateOf(emptyList<Run>())
        private set

    /** The frame the page last moved on. */
    val frame = mutableLongStateOf(0L)

    /** How far the runs that have landed took it, this journey. */
    private var landed = 0f
    private var driver: Job? = null

    private fun at(run: Run, nanos: Long, lateMs: Float = 0f): Float {
        val ms = (nanos - run.startNanos) / 1e6f - lateMs
        return if (ms <= 0f) 0f else 1f + run.spring.solve(-1f, 0f, ms / 1000f).first
    }

    fun handOver(offset: Float, focus: Int, spanMs: Long, scope: CoroutineScope, list: LazyListState) {
        val going = runs
        val now = frame.longValue
        val distance = offset - going.fold(0f) { s, r -> s + r.delta * (1f - at(r, now)) }
        if (abs(distance) < 0.5f) return
        val start = if (going.isEmpty()) System.nanoTime() else now
        runs = going + Run(distance, focus, FluidSpring(spanMs / 1000f, 1f), start, StaggerFraction * spanMs)
        if (driver?.isActive != true) driver = scope.launch { drive(list) }
    }

    private suspend fun drive(list: LazyListState) {
        try {
            list.scroll(MutatePriority.Default) {
                var applied = 0f
                while (runs.isNotEmpty()) {
                    val nanos = withFrameNanos { it }
                    frame.longValue = nanos
                    val target = runs.fold(landed) { s, r -> s + r.delta * at(r, nanos) }
                    scrollBy(target - applied)
                    applied = target
                    // A run is done once its last row has arrived too.
                    val (done, still) = runs.partition { r -> abs(r.delta * (1f - at(r, nanos, StaggerSteps * r.staggerMs))) < 0.5f }
                    if (done.isNotEmpty()) {
                        landed += done.fold(0f) { s, r -> s + r.delta }
                        runs = still
                    }
                }
            }
        } finally {
            runs = emptyList()
            landed = 0f
        }
    }

    /** A jump, a seek or a hand on the page: the journey is over. */
    fun stop() {
        driver?.cancel()
        driver = null
        runs = emptyList()
        landed = 0f
    }

    /** How far row [index] is held back against the page. */
    fun lag(index: Int): Float {
        val going = runs
        if (going.isEmpty()) return 0f
        var nanos = Long.MIN_VALUE
        var sum = 0f
        for (r in going) {
            // Rows behind the one being moved to fan out; the ones it's moving away from arrive together.
            val behind = if (r.delta >= 0f) index - r.focus else r.focus - index
            if (behind <= 0) continue
            if (nanos == Long.MIN_VALUE) nanos = frame.longValue
            sum += r.delta * (at(r, nanos) - at(r, nanos, min(behind, StaggerSteps) * r.staggerMs))
        }
        return sum
    }
}

/** The Latin letters under the lines, and how far they've opened: they open out of their line, and fold back into it. */
private class GlossReveal(val progress: State<Float>, private val held: State<List<String?>>) {
    val glosses: List<String?> get() = held.value
}

@Composable
private fun rememberGlossReveal(key: Any, target: List<String?>): GlossReveal {
    val shown = remember(key) { mutableStateOf(target) }
    val progress = remember(key) { Animatable(if (target.isNotEmpty()) 1f else 0f) }
    LaunchedEffect(key, target) {
        if (target.isEmpty()) {
            // Held through the fold, so the words go back into their line rather than vanish.
            if (shown.value.isNotEmpty() && progress.value > 0f) progress.animateTo(0f, Motion.fluid(0.26f, 1f, 0.001f))
            shown.value = target
        } else {
            shown.value = target
            progress.animateTo(1f, Motion.fluid(0.46f, 1f, 0.001f))
        }
    }
    return remember(key) { GlossReveal(progress.asState(), shown) }
}

@Composable
private fun VerseRow(
    index: Int,
    line: VLine,
    gloss: VLine?,
    glossStyle: TextStyle,
    glossOpen: State<Float>,
    clock: MutableLongState,
    isActive: Boolean,
    sung: Boolean,
    focus: Int,
    duet: Boolean,
    browsing: Boolean,
    glowing: Boolean,
    reduce: Boolean,
    lineAlpha: () -> Float,
    defocus: Modifier,
    page: Page,
    onSeek: (Long) -> Unit,
) {
    val alignEnd = duet && line.alignEnd
    val style = Lead.copy(textAlign = if (alignEnd) TextAlign.Right else TextAlign.Left)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when {
            pressed -> PressedScale
            isActive -> 1f
            else -> InactiveScale
        },
        Motion.fluid(if (pressed) 0.24f else SettleS, 1f, 0.0005f), label = "verseScale",
    )
    // The line's light comes up as it's sung and goes down as the next one's comes up.
    val glow by animateFloatAsState(if (isActive && glowing && !browsing) GlowAlpha else 0f, Motion.fluid(0.42f, 1f, 0.005f), label = "verseGlow")
    val backingOpen = line.background?.let { rememberBackingOpen(it, clock, isActive || index == focus, index < focus, reduce) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = if (duet && alignEnd) DuetLane else 0.dp, end = if (duet && !alignEnd) DuetLane else 0.dp)
            .graphicsLayer {
                // From the edge it's written from, so the words don't slide under the light.
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                alpha = lineAlpha()
                translationY = if (reduce) 0f else page.lag(index)
            }
            .then(defocus)
            .pointerInput(line) {
                detectTapGestures(
                    // The dip waits a tap's length, as a press in a scrolling list does, so a turn by
                    // hand doesn't start with every line under the finger flinching.
                    onPress = {
                        coroutineScope {
                            val dip = launch {
                                delay(PressDelayMs)
                                pressed = true
                            }
                            tryAwaitRelease()
                            dip.cancel()
                            pressed = false
                        }
                    },
                    onTap = { onSeek(line.timeMs) },
                )
            },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Voice(line, clock, style, isActive, sung, browsing, glow, GlowRoom, alignEnd, rise = !reduce, Modifier.fillMaxWidth())
        gloss?.let { g ->
            Voice(
                g, clock, glossStyle.copy(textAlign = style.textAlign), isActive, sung, browsing, 0f, 0.dp, alignEnd, rise = false,
                Modifier
                    .fillMaxWidth()
                    .revealBelow(glossOpen)
                    // Tucked up into the line's own room, so the two read as one line in two scripts.
                    .padding(start = GlowRoom, end = GlowRoom, bottom = GlowRoom)
                    .offset(y = -GlossTuck)
                    .graphicsLayer { alpha = GlossAlpha },
            )
        }
        line.background?.let { backing ->
            // No bloom and no lift on the second voice: the light marks what's sung at you.
            Voice(
                backing, clock, style.copy(fontSize = BackingSize, lineHeight = BackingLeading), isActive, sung, browsing, 0f, 0.dp, alignEnd, rise = false,
                Modifier
                    .fillMaxWidth()
                    .then(backingOpen?.let { Modifier.revealBacking(it, alignEnd) } ?: Modifier)
                    .padding(start = GlowRoom, end = GlowRoom, bottom = GlowRoom)
                    .graphicsLayer { alpha = BackingAlpha },
            )
        }
    }
}

/**
 * Whether the answering vocal is open: from just before its first word until its last is done, as
 * Apple Music keeps it (on its line's turn where it has no times of its own). A line the page has left
 * waits for the move to land before it folds, or the row folding would drag the line below with it.
 */
@Composable
private fun rememberBackingOpen(backing: VLine, clock: MutableLongState, rowFocused: Boolean, left: Boolean, reduce: Boolean): State<Float> {
    val due by remember(backing, clock) {
        derivedStateOf {
            val now = clock.longValue
            now >= backing.timeMs - BackingLeadMs && now < backing.endMs
        }
    }
    val wanted = if (backing.isWordSynced) due else rowFocused
    val open = remember(backing) { Animatable(if (wanted) 1f else 0f) }
    LaunchedEffect(wanted, reduce) {
        when {
            reduce -> open.snapTo(if (wanted) 1f else 0f)
            wanted -> open.animateTo(1f, Motion.fluid(SettleS, 1f, 0.001f))
            else -> {
                if (left) delay(BackingCloseDelayMs)
                open.animateTo(0f, Motion.fluid(0.45f, 1f, 0.001f))
            }
        }
    }
    return open.asState()
}

/** One voice of a row: the lead, its Latin letters, or the answering vocal. */
@Composable
private fun Voice(
    line: VLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    sung: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    alignEnd: Boolean,
    rise: Boolean,
    modifier: Modifier,
) {
    if (line.isWordSynced) {
        // Every timed line goes through the sweep: one sung is lit whole, one to come isn't, and a
        // finished line's tail closes up as it dims rather than popping in a frame.
        val tail by animateFloatAsState(if (sung) 1f else UnsungAlpha, Motion.fluid(SettleS, 1f, 0.001f), label = "verseTail")
        SweptLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = { tail },
            modifier = modifier,
            glowAlpha = if (browsing) 0f else glowAlpha,
            glowRoom = room,
            feather = isActive && !browsing,
            rise = rise,
            alignEnd = alignEnd,
        )
    } else {
        // Timed by the line: it lights whole as it starts, held back until then as a timed line's words are.
        val lit by animateFloatAsState(if (sung || isActive) 1f else UnsungAlpha, Motion.fluid(SettleS, 1f, 0.001f), label = "verseLit")
        BasicText(
            line.text,
            style = style,
            color = { Ink.White.copy(alpha = lit) },
            modifier = modifier.then(if (room > 0.dp) Modifier.padding(room) else Modifier),
        )
    }
}

/**
 * A line with the sung part lit, the rest held back, and the boundary travelling across the words in
 * time with the voice: two copies of the same text stacked (a dim one, and a bright one cut to what's
 * been sung, its edge feathered ahead into what's to come), laid out identically. A third, blurred,
 * behind them and cut to the letters of a held word, is the bloom: blurred after the cut, so it bleeds
 * past the letter it belongs to, as light coming off a carried note. Only a line moving reads the clock.
 */
@Composable
private fun SweptLine(
    line: VLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: () -> Float,
    modifier: Modifier,
    glowAlpha: Float,
    glowRoom: Dp,
    feather: Boolean,
    rise: Boolean,
    alignEnd: Boolean,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    // Shared by all three copies, which draw one after another: only ever one letter in hand.
    val growth = remember { CharGrowth() }
    val phase = remember(line, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now < line.animatesFromMs -> PhaseBefore
                now > line.animatesUntilMs -> PhaseAfter
                else -> PhaseMoving
            }
        }
    }
    val drawnAt: () -> Long = {
        when (phase.value) {
            PhaseMoving -> clock.longValue
            PhaseBefore -> line.animatesFromMs - 1
            else -> line.animatesUntilMs + 1
        }
    }
    // Every copy carries the same room, so they lay out the same; it's where the bloom spreads into.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier
    // Outside the room and the sweep, so what lifts is the finished word, lit head and dim tail together.
    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(measured, line, drawnAt(), glowRoom.toPx(), WordRise.toPx(), growth)
                    }
                }
                .then(inner)
        }
    }
    val sweep = Modifier.drawWithContent {
        val position = drawnAt()
        when {
            position >= line.endMs -> drawContent()
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position), line.sweepSpans, feather) }
        }
    }
    Box(modifier, contentAlignment = if (alignEnd) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft) {
        BasicText(
            line.text,
            style = style,
            color = { Ink.White.copy(alpha = dimAlpha()) },
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )
        if (glowAlpha > 0.01f) {
            BasicText(
                line.text,
                style = style,
                color = { Ink.White },
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(GlowRadius, BlurredEdgeTreatment.Unbounded)
                    // Each letter is masked to its own brightness, which needs a layer of its own to
                    // erase into; the room inside it, so a letter can swell up into it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .then(room)
                    .drawWithContent {
                        val measured = layout ?: return@drawWithContent
                        glowGrown(measured, line, drawnAt(), WordRise.toPx(), growth)
                    },
            )
        }
        BasicText(
            line.text,
            style = style,
            color = { Ink.White },
            // The feather erases into a layer of its own, only while there's an edge to soften.
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer { compositingStrategy = if (feather) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * The text cut to the letters of the words being held, each at its own brightness: the light the
 * singing gives off. Nothing at all on a line of ordinary syllables: the words that light up are
 * the ones held long enough to have earned it.
 */
private fun ContentDrawScope.glowGrown(layout: TextLayoutResult, line: VLine, positionMs: Long, peak: Float, growth: CharGrowth) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val row = layout.getLineForOffset(char)
            val from = layout.xOn(char, row, 0f)
            val to = layout.xOn(char + 1, row, 0f)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val top = layout.getLineTop(row)
            val bottom = layout.getLineBottom(row)
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(left = from - overhang + dx, top = top - peak * GrowHeadroom, right = to + overhang + dx, bottom = bottom) {
                translate(left = dx, top = dy) {
                    scale(growth.scale, growth.scale, Offset((from + to) / 2f, (top + bottom) / 2f)) {
                        this@glowGrown.drawContent()
                    }
                }
                // Within this letter's own cut, so it takes this letter down and leaves its neighbours.
                drawRect(color = Color.White.copy(alpha = growth.bloom), blendMode = BlendMode.DstIn)
            }
        }
    }
}

/**
 * The row redrawn with the word being sung lifted off the line and the ones behind it settling back,
 * cut at word boundaries so no glyph is sliced; whatever's on the floor goes down in one piece, cut to
 * exactly its row's band (opened upwards it would draw a sliver of the row above twice). A line with
 * nothing lifted draws once.
 */
private fun ContentDrawScope.riseWith(layout: TextLayoutResult, line: VLine, positionMs: Long, inset: Float, peak: Float, growth: CharGrowth) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (row in 0 until layout.lineCount) {
        val rowStart = layout.getLineStart(row)
        val rowEnd = layout.getLineEnd(row, visibleEnd = true)
        val top = layout.getLineTop(row) + inset
        val bottom = layout.getLineBottom(row) + inset
        var at = rowStart
        var edge = layout.getLineLeft(row) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, rowStart)
            val end = minOf(span.last + 1, rowEnd)
            if (start >= end) continue
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, row, inset)
            val to = layout.xOn(end, row, inset)
            if (to <= from) continue
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(layout, held, line, positionMs, row, start, end, top, bottom, inset, peak, em, growth)
            } else {
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < rowEnd) sliceRisen(edge, top, layout.getLineRight(row) + inset, bottom, 0f)
    }
}

/**
 * One held word redrawn a letter at a time, each at its own swell and height, each cut to where its
 * letter is going rather than where it sits, so a swelling glyph isn't shaved.
 */
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: VLine,
    positionMs: Long,
    row: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    // Room to swell into above the row; the pivot stays on the row's own middle.
    val ceiling = top - peak * GrowHeadroom
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, row, inset)
        val to = layout.xOn(char + 1, row, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipShiftedDown(from - overhang + dx, ceiling, to + overhang + dx, bottom, dy) {
            translate(left = dx) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

/**
 * Where a character offset sits across a row, in pixels, on the row it was cut out of (the offset one
 * past a wrapped row's last letter belongs to the next row, and asked straight answers its left edge).
 */
private fun TextLayoutResult.xOn(offset: Int, row: Int, inset: Float): Float {
    val rowStart = getLineStart(row)
    val rowEnd = getLineEnd(row, visibleEnd = true)
    val left = getLineLeft(row) + inset
    val right = getLineRight(row) + inset
    val rtl = getParagraphDirection(rowStart.coerceIn(0, (layoutInput.text.length - 1).coerceAtLeast(0))) == ResolvedTextDirection.Rtl
    return when {
        offset <= rowStart -> if (rtl) right else left
        offset >= rowEnd -> if (rtl) left else right
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset).coerceIn(left, right)
    }
}

/** One piece of a row, cut to its own width and drawn at its own height. */
private fun ContentDrawScope.sliceRisen(from: Float, top: Float, to: Float, bottom: Float, dy: Float) {
    if (to <= from) return
    clipShiftedDown(from, top, to, bottom, dy) { this@sliceRisen.drawContent() }
}

private inline fun DrawScope.clipShiftedDown(left: Float, top: Float, right: Float, bottom: Float, dy: Float, block: DrawScope.() -> Unit) {
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        translate(top = dy) { block() }
    }
}

/**
 * Where a fractional character index sits across a row: inside a timed piece that sits whole on the
 * row, a share of the piece laid across its width, so the edge crosses a "w" at the pace of an "i";
 * elsewhere letter by letter.
 */
private fun horizontalAt(layout: TextLayoutResult, chars: Float, row: Int, spans: List<IntRange>): Float {
    val rowStart = layout.getLineStart(row)
    val rowEndVisible = layout.getLineEnd(row, visibleEnd = true)
    val rowEndAll = layout.getLineEnd(row, visibleEnd = false)
    val piece = spans.firstOrNull { chars >= it.first && chars < it.last + 1 }
    if (piece != null && piece.first >= rowStart && piece.last + 1 <= rowEndVisible) {
        val from = layout.xOn(piece.first, row, 0f)
        val to = layout.xOn(piece.last + 1, row, 0f)
        return from + (to - from) * (chars - piece.first) / (piece.last + 1 - piece.first)
    }
    val index = chars.toInt().coerceIn(rowStart, rowEndAll)
    val here = layout.xOn(index, row, 0f)
    val textLength = layout.layoutInput.text.length
    val nextIndex = index + 1
    // On the last letter of a wrapped row the next position belongs to the row below: use this row's far edge.
    val spills = layout.getLineForOffset(nextIndex.coerceIn(0, textLength)) != row
    val next = when {
        !spills -> layout.xOn(nextIndex, row, 0f)
        nextIndex > rowEndVisible -> {
            val rtl = layout.getParagraphDirection(rowStart.coerceIn(0, (textLength - 1).coerceAtLeast(0))) == ResolvedTextDirection.Rtl
            if (rtl) layout.getLineLeft(row) else layout.getLineRight(row)
        }
        else -> layout.xOn(nextIndex.coerceAtMost(rowEndVisible), row, 0f)
    }
    return here + (next - here) * (chars - index)
}

/**
 * The text cut to its first [revealedChars] characters, a row at a time: rows passed drawn whole, the
 * row holding the boundary cut at it, the rest left to the dim copy. The boundary is feathered ahead of
 * itself, into what's still to come, over [WipeFeather] (grown in over a row's first letters), so the
 * letter being sung is already bright and the line finishes whole; the mask is kept to the row's band.
 */
private fun ContentDrawScope.sweepTo(layout: TextLayoutResult, revealedChars: Float, spans: List<IntRange>, feather: Boolean) {
    if (revealedChars <= 0f) return
    val textLength = layout.layoutInput.text.length
    if (revealedChars >= textLength) {
        drawContent()
        return
    }
    val first = layout.getLineStart(0).coerceIn(0, (textLength - 1).coerceAtLeast(0))
    val rtl = layout.getParagraphDirection(first) == ResolvedTextDirection.Rtl
    for (row in 0 until layout.lineCount) {
        val rowStart = layout.getLineStart(row)
        val rowEndAll = layout.getLineEnd(row, visibleEnd = false)
        if (revealedChars <= rowStart) return
        val cut = revealedChars < rowEndAll
        val left = layout.getLineLeft(row)
        val right = layout.getLineRight(row)
        val x = (if (cut) horizontalAt(layout, revealedChars, row, spans) else if (rtl) left else right).coerceIn(left, right)
        val featherPx = if (feather && cut) minOf(WipeFeather.toPx(), if (rtl) right - x else x - left) else 0f
        val lead = if (rtl) (x - featherPx).coerceAtLeast(left) else (x + featherPx).coerceAtMost(right)
        val (clipLeft, clipRight) = if (rtl) lead to right else left to lead
        if (clipLeft >= clipRight) continue
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        clipRect(left = clipLeft, top = top, right = clipRight, bottom = bottom) { this@sweepTo.drawContent() }
        if (featherPx < 0.5f) continue
        // DstIn erases what the brush doesn't cover; kept to this row's band, so the rows above and below are left alone.
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.White, 1f to Color.Transparent, startX = x, endX = if (rtl) x - featherPx else x + featherPx),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** Opens downward out of the line above: the room grows from nothing while the words slide down from behind it and fade up. */
private fun Modifier.revealBelow(progress: State<Float>): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val open = progress.value.coerceIn(0f, 1f)
    layout(placeable.width, (placeable.height * open).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            translationY = -placeable.height * (1f - open) * 0.6f
            alpha = open * open
        }
    }
}

/**
 * Opens the answering vocal under its lead: the room it takes opens with it so the rows below make
 * way, the words fade in a little ahead of the room and rise the last few pixels into place from a hair
 * smaller, from the edge the line is written from.
 */
private fun Modifier.revealBacking(progress: State<Float>, alignEnd: Boolean): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val open = progress.value.coerceIn(0f, 1f)
    val rise = BackingRise.toPx()
    layout(placeable.width, (placeable.height * open).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            alpha = (open * BackingFadeLead).coerceAtMost(1f)
            translationY = rise * (1f - open)
            val grow = BackingRestScale + (1f - BackingRestScale) * open
            scaleX = grow
            scaleY = grow
            transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0f)
        }
    }
}

/**
 * A break between verses: three points lighting in turn across it, so a long one reads as time running
 * down; the row opens and closes with the break, so the page carries no hole through the verses.
 */
@Composable
private fun Gap(line: VLine, until: Long, isActive: Boolean, clock: MutableLongState, lineAlpha: () -> Float, defocus: Modifier, onSeek: (Long) -> Unit) {
    // Held at 0 before the break and 1 after, so a break not playing never reads the clock.
    val fill = remember(line, until, clock) {
        derivedStateOf(structuralEqualityPolicy()) {
            val now = clock.longValue
            when {
                now <= line.timeMs -> 0f
                now >= until -> 1f
                else -> GapRunning
            }
        }
    }
    val swell by animateFloatAsState(if (isActive) 1f else 0f, Motion.fluid(if (isActive) SettleS else 0.35f, 1f, 0.001f), label = "verseGap")
    Box(
        Modifier
            .height((GapRowHeight + GapRowSpacing) * swell.coerceIn(0f, 1f))
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .then(defocus)
                .pointerInput(line) { detectTapGestures { onSeek(line.timeMs) } }
                .padding(GlowRoom)
                .size(width = GapDotSize * GapDots + GapDotGap * (GapDots - 1), height = GapDotSize)
                .graphicsLayer {
                    val grow = GapRestScale + (1f - GapRestScale) * swell
                    scaleX = grow
                    scaleY = grow
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    alpha = lineAlpha() * swell
                }
                .drawBehind {
                    val span = (until - line.timeMs).coerceAtLeast(1L)
                    val through = fill.value.takeUnless { it == GapRunning } ?: ((clock.longValue - line.timeMs).toFloat() / span).coerceIn(0f, 1f)
                    val radius = GapDotSize.toPx() / 2f
                    val stride = (GapDotSize + GapDotGap).toPx()
                    repeat(GapDots) { dot ->
                        // Each point owns its share of the break and fills across it, so they light in turn.
                        val lit = (through * GapDots - dot).coerceIn(0f, 1f)
                        drawCircle(Ink.White.copy(alpha = GapDotRest + (1f - GapDotRest) * lit), radius, Offset(radius + dot * stride, size.height / 2f))
                    }
                },
        )
    }
}
