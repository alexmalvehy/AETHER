@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.lyrics

import app.aether.ui.player.isVideo
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.DragState
import app.aether.core.designsystem.component.Grabber
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.drawLightCross
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.FluidPoint
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.FluidValue
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Serif
import app.aether.core.designsystem.theme.Type
import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.WordTiming
import app.aether.core.model.Track
import app.aether.ui.common.Load
import app.aether.ui.common.LocalContainer
import app.aether.ui.lyrics.line.ContinuousLine
import app.aether.ui.lyrics.line.LineScript
import app.aether.ui.player.rememberLyrics
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import app.aether.core.designsystem.component.GlassPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The song's words, as the lyrics view finds them ([wordsFor]), asked again when the consent changes. */
@Composable
private fun rememberWords(track: Track): Words {
    val container = LocalContainer.current
    val consent by container.settings.timedLyrics.collectAsStateWithLifecycle()
    val service = rememberLyrics(track, fetch = !track.isVideo)
    val result by produceState<Words>(Words.Waiting, track.id, consent, service) {
        value = wordsFor(container, track, (service as? Load.Ready)?.value, ownPending = service is Load.Loading)
    }
    return result
}

/**
 * Lyrics as constellations (the listener's reference). Each line of the song is a constellation in a
 * night sky: as each word is sung its star (AETHER's cross of light) blooms, its word rising large
 * and glowing, joined to the last by a hairline that draws itself across; once sung it settles to a
 * small word beside its star. When the line ends the camera drifts across the sky to where the next
 * line forms, on a slow elastic spring, drawing back a little while it travels and settling in as
 * it arrives. Nothing here can be touched: it plays, and pulls down (or the grabber) to go. Or, as
 * the listener chooses in Settings, the song is written by hand as one line ([ContinuousLine]).
 */
@Composable
fun LyricsStage(track: Track, pane: DragState, following: androidx.compose.runtime.MutableState<Boolean> = remember { androidx.compose.runtime.mutableStateOf(true) }) {
    val container = LocalContainer.current
    val words = rememberWords(track)
    val position = container.playback.position.collectAsStateWithLifecycle()
    val playback by container.playback.state.collectAsStateWithLifecycle()
    val style by container.settings.lyricsStyle.collectAsStateWithLifecycle()
    val romanise by container.settings.romanise.collectAsStateWithLifecycle()
    val latinFirst by container.settings.latinFirst.collectAsStateWithLifecycle()
    val asLine = style == 1
    val asVerse = style == 2
    val positionNow = remember(container) { { container.playback.positionNow() } }
    // Each line in Latin letters, worked out on the phone, off the main thread; null where a line needs none.
    val timedLines = (words as? Words.Timed)?.lines
    // Started afresh with each song, so a new song's lines never wear the last one's letters, even for a frame.
    val glossState = remember(timedLines) { androidx.compose.runtime.mutableStateOf<List<String?>>(emptyList()) }
    LaunchedEffect(timedLines) {
        glossState.value = timedLines?.let { lines -> withContext(Dispatchers.Default) { Romaniser.lines(lines) } }.orEmpty()
    }
    val glosses = glossState.value
    val readable = glosses.any { it != null }
    val shownGlosses = if (romanise && readable) glosses else emptyList()
    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        when (words) {
            Words.Waiting -> GridDots(Modifier.align(Alignment.Center))
            is Words.Timed -> when {
                asLine -> {
                    // The hand writes Latin letters: in another script, it writes the words as they're read.
                    val lines = remember(words.lines, shownGlosses) {
                        if (shownGlosses.isEmpty()) words.lines
                        else words.lines.mapIndexed { i, l -> shownGlosses.getOrNull(i)?.let { g -> l.copy(text = g, words = emptyList(), background = null) } ?: l }
                    }
                    val script = remember(lines, track.durationMs) { LineScript.synced(lines, track.durationMs) }
                    ContinuousLine(positionNow, script, track.id.toString(), playback.isPlaying, "Lyrics of ${track.title}, written as one line")
                }
                asVerse -> {
                    Verse(
                        words.lines,
                        shownGlosses,
                        positionNow,
                        playback.isPlaying,
                        onSeek = { container.playback.seekTo(it) },
                        onFollowing = { following.value = it },
                        latinFirst = latinFirst,
                        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 44.dp),
                    )
                    // The page goes into the black under the song's name and above its credit, as a page under glass.
                    Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(0.45f to Ink.Black, 1f to Color.Transparent)))
                    Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to Ink.Black)))
                }
                else -> Constellations(words.lines, if (shownGlosses.isEmpty()) null else Romaniser.japanese(words.lines.map { it.text }), position, playback.isPlaying, Modifier.fillMaxSize())
            }
            is Words.Untimed -> {
                // Words with no times are a page read by hand: it scrolls before the pane goes.
                androidx.compose.runtime.SideEffect { following.value = false }
                Untimed(words.lines, Modifier.fillMaxSize())
            }
            Words.Instrumental -> if (asLine) {
                // A song without words is drawn too: one composition across it.
                val script = remember(track.durationMs) { LineScript.instrumental(track.durationMs) }
                ContinuousLine(positionNow, script, track.id.toString(), playback.isPlaying, "Instrumental. This song has no words.")
            } else {
                Quiet("Instrumental", "No words: just listen.", Modifier.align(Alignment.Center))
            }
            is Words.Silent -> Quiet(words.title, words.body, Modifier.align(Alignment.Center))
        }
        Column(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Grabber(pane, touchHeight = 22.dp)
            Spacer(Modifier.height(8.dp))
            Txt(track.title, Type.Label, color = Ink.Grey, upper = true, maxLines = 1, modifier = Modifier.padding(horizontal = 96.dp))
        }
        // Words in another script can be read in Latin letters as well: the key says so, and is lit when they are.
        if (readable) {
            GlassPill(
                "Latin",
                onClick = { container.settings.setRomanise(!romanise) },
                lit = romanise,
                small = true,
                modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 12.dp, end = 16.dp),
            )
        }
        val credit = when (words) {
            is Words.Timed -> "Words: ${words.credit}" + when {
                words.estimated -> "  ·  timed by AETHER"
                words.lines.any { it.words.isNotEmpty() } -> "  ·  word by word"
                else -> ""
            }
            is Words.Untimed -> "Words: ${words.credit}"
            Words.Instrumental -> if (asLine) "Instrumental" else null
            else -> null
        }
        if (credit != null) {
            Txt(credit, Type.Label, color = Ink.Dim, upper = true, modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(bottom = 18.dp))
        }
    }
}

@Composable
private fun Quiet(title: String, body: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LightCross(size = 56.dp, intensity = 0.7f)
        Spacer(Modifier.height(18.dp))
        Txt(title, Type.Display, align = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Txt(body, Type.Body, color = Ink.Grey, align = TextAlign.Center)
    }
}

/** Words without times (the song's length unknown): as they are, centred, to be read. */
@Composable
private fun Untimed(lines: List<String>, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach { Txt(it, Type.Title.copy(fontSize = 21.sp, lineHeight = 30.sp), color = Ink.White.copy(alpha = 0.85f), align = TextAlign.Center) }
    }
}

/** A word's star, where it is in the sky, when it's sung, and how it reads beside its star (in Latin letters, when asked for). */
private class Star(val at: Offset, val word: String, val start: Long, val reading: String)

/** A line's constellation: its stars, where it's centred, and when it's sung. */
private class Figure(val centre: Offset, val stars: List<Star>, val start: Long, val end: Long)

/**
 * Charts the sky: each sung line a figure, its words left to right with a wandering rise and fall,
 * each figure a screen's width from the last in a heading that wanders too. A phrase in a script
 * written without spaces (Chinese, Japanese) is a star to each character, as it's sung. With
 * [japanese] given (romanisation asked for), each star's small word is its reading in Latin letters.
 * Deterministic: the same song always makes the same sky.
 */
private fun chart(lines: List<LyricsLine>, w: Float, h: Float, japanese: Boolean?): List<Figure> {
    val figures = ArrayList<Figure>()
    var centre = Offset.Zero
    var heading = 0f
    var n = 0
    lines.forEachIndexed { i, line ->
        if (line.text.isBlank()) return@forEachIndexed
        val next = lines.drop(i + 1).firstOrNull()?.startMs
        val units = WordTiming.units(line, next)
        if (units.isEmpty()) return@forEachIndexed
        val end = WordTiming.end(line, next)
        val rnd = Random(i * 7919 + 17)
        if (n > 0) {
            heading = (heading + (rnd.nextFloat() - 0.5f) * 2.1f).coerceIn(-1.25f, 1.25f)
            centre += Offset(cos(heading), sin(heading) * 0.85f) * (w * 0.95f)
        }
        val k = units.size
        val spread = min(w * 0.74f, k * w * 0.15f)
        var y = 0f
        val stars = units.mapIndexed { j, unit ->
            val x = if (k == 1) 0f else -spread / 2f + spread * j / (k - 1) + (rnd.nextFloat() - 0.5f) * w * 0.05f
            y = (y + (rnd.nextFloat() - 0.5f) * h * 0.16f).coerceIn(-h * 0.17f, h * 0.17f)
            val reading = japanese?.let { Romaniser.romanise(unit.text, it) } ?: unit.text
            Star(centre + Offset(x, y), unit.text, unit.startMs, reading)
        }
        figures += Figure(centre, stars, line.startMs, end)
        n++
    }
    return figures
}

/** The background: faint points, some brighter, twinkling slowly. */
private class Dust(val x: Float, val y: Float, val r: Float, val a: Float, val phase: Float)

/*
 * The springs the sky moves on (Apple's response and damping, solved exactly at each frame's time,
 * so the motion is the same at any refresh rate and never jumps when interrupted).
 */
private val CameraSpring = FluidSpring(1.15f, 0.78f)
private val ZoomSpring = FluidSpring(0.7f, 0.82f)
private val PopSpring = FluidSpring(0.52f, 0.62f)
private val BloomSpring = FluidSpring(0.38f, 0.82f)
private val FadeSpring = FluidSpring(0.34f, 1f)
private val DrawSpring = FluidSpring(0.5f, 1f)
private val DimSpring = FluidSpring(0.95f, 1f)

/**
 * The sky's two lights, each drawn once into a small picture and then stamped wherever it's needed:
 * AETHER's cross of light for every star, and a soft round glow for the light around a sung word.
 * Drawn fresh, a cross is nine gradients; a line of stars with three behind it was hundreds of new
 * gradients every frame, and the pauses to clear them away fell just as words appeared.
 */
private class Sprites(val cross: ImageBitmap, val glow: ImageBitmap)

private fun makeSprites(density: Density): Sprites {
    fun paint(px: Int, block: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(px, px)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat()), block)
        return image
    }
    val cross = paint(176) { drawLightCross(intensity = 1f, centre = center, extent = size.minDimension) }
    val glow = paint(128) {
        drawCircle(
            Brush.radialGradient(0f to Color.White, 0.35f to Color.White.copy(alpha = 0.36f), 1f to Color.Transparent, center = center, radius = size.minDimension / 2f),
            radius = size.minDimension / 2f,
        )
    }
    return Sprites(cross, glow)
}

/** [image] centred on [at], [extent] across (wide by [stretch]), at [alpha], its light added to what's beneath. */
private fun DrawScope.stamp(image: ImageBitmap, at: Offset, extent: Float, alpha: Float, stretch: Float = 1f) {
    if (alpha <= 0.004f || extent <= 0.5f) return
    val sx = extent * stretch / image.width
    val sy = extent / image.height
    withTransform({
        translate(at.x - extent * stretch / 2f, at.y - extent / 2f)
        scale(sx, sy, pivot = Offset.Zero)
    }) {
        drawImage(image, alpha = alpha.coerceAtMost(1f), blendMode = BlendMode.Plus)
    }
}

/** 0 to 1 along [spring] from rest, [ms] after it began: a spring's progress, as a number. */
private fun FluidSpring.progress(ms: Long): Float = if (ms <= 0) 0f else (1f + solve(-1f, 0f, ms / 1000f).first)

@Composable
private fun Constellations(lines: List<LyricsLine>, japanese: Boolean?, position: State<Long>, playing: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val figures = remember(lines, w, h, japanese) { chart(lines, w, h, japanese) }
        val dust = remember(w, h) {
            val rnd = Random(2026)
            List(150) { Dust(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.6f + rnd.nextFloat() * 1.2f, 0.08f + rnd.nextFloat() * 0.32f, rnd.nextFloat() * 6.28f) }
        }
        val labels = remember(lines) { HashMap<String, TextLayoutResult>() }
        val density = LocalDensity.current
        val sprites = remember(density) { makeSprites(density) }
        // One frame loop: the song's time, carried on smoothly between the player's reports; the
        // camera, springing toward the figure being sung; and how far it has drawn back to travel.
        var clock by remember { mutableLongStateOf(position.value) }
        val camera = remember(figures) { FluidPoint(figures.firstOrNull()?.centre ?: Offset.Zero, CameraSpring) }
        val zoom = remember(figures) { FluidValue(1f, ZoomSpring, unit = 1f / 400f) }
        var frame by remember { mutableLongStateOf(0L) }
        var seconds by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(figures, playing, reduce) {
            var measuredUpTo = -1
            var base = position.value
            var baseAt = withFrameNanos { it }
            var last = baseAt
            while (true) {
                val now = withFrameNanos { it }
                if (position.value != base) { base = position.value; baseAt = now }
                clock = if (playing) base + (now - baseAt) / 1_000_000 else base
                val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
                seconds += dt
                val current = figures.indexOfLast { it.start <= clock }.coerceAtLeast(0)
                // The words of this line and the next are measured here, between frames' drawing,
                // so a word never waits on its own layout as it appears.
                if (current != measuredUpTo) {
                    measuredUpTo = current
                    for (fi in current..minOf(current + 1, figures.lastIndex)) figures[fi].stars.forEach { star ->
                        label(measurer, labels, star.word, big = true, density = density)
                        label(measurer, labels, star.reading, big = false, density = density)
                    }
                }
                val target = figures.getOrNull(current)?.centre ?: Offset.Zero
                if (reduce) {
                    camera.snap(target)
                } else {
                    camera.retarget(target)
                    camera.step(dt)
                    // Travelling, the view draws back a little, as a camera pulls out to pan; arriving, it settles in.
                    val speed = hypot(camera.velocity.x, camera.velocity.y)
                    zoom.retarget(1f - (speed / (w * 3.2f)).coerceIn(0f, 0.14f))
                    zoom.step(dt)
                }
                frame++
                // Paused, once the camera has come to rest the sky holds still: no frames until it plays.
                if (!playing && (reduce || (hypot(camera.velocity.x, camera.velocity.y) < 1f && zoom.resting))) break
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            frame // read: one redraw per frame of the loop
            val t = clock
            val cam = camera.value
            val k = zoom.value
            val focus = Offset(size.width / 2f, size.height * 0.47f)
            val origin = focus - cam
            // The dust, drifting a quarter as fast as the sky, wrapped round the screen.
            dust.forEach { d ->
                val px = ((d.x - cam.x * 0.25f) % size.width + size.width) % size.width
                val py = ((d.y - cam.y * 0.25f) % size.height + size.height) % size.height
                val twinkle = 0.62f + 0.38f * sin(seconds * 0.9f + d.phase)
                drawCircle(Color.White.copy(alpha = d.a * twinkle), radius = d.r, center = Offset(px, py))
            }
            if (figures.isEmpty()) return@Canvas
            scale(k, k, pivot = focus) {
                val current = figures.indexOfLast { it.start <= t }
                val from = max(0, current - 3)
                val to = min(figures.lastIndex, max(current, 0))
                for (fi in from..to) {
                    val f = figures[fi]
                    val alpha = when {
                        fi == current -> 1f
                        // The line just sung dims, on a slow spring, as the next one forms.
                        fi == current - 1 -> 1f - 0.72f * DimSpring.progress(t - figures[current].start)
                        fi < current -> 0.28f / (current - fi)
                        else -> 0f
                    }
                    if (alpha <= 0.01f) continue
                    drawFigure(f, fi == current, t, alpha, origin, measurer, labels, sprites)
                }
            }
        }
    }
}

/** One constellation at [t]: its hairlines drawing themselves, its stars, the word being sung. */
private fun DrawScope.drawFigure(f: Figure, live: Boolean, t: Long, alpha: Float, origin: Offset, measurer: TextMeasurer, labels: HashMap<String, TextLayoutResult>, sprites: Sprites) {
    val shown = if (live) f.stars.count { it.start <= t } else f.stars.size
    if (shown == 0) return
    val glowWidth = 6.dp.toPx()
    val lineWidth = 1.dp.toPx()
    // The hairlines joining what's been sung, each drawn out from the last star to the new one.
    for (j in 1 until shown) {
        val a = origin + f.stars[j - 1].at
        val b = origin + f.stars[j].at
        val reach = if (live) DrawSpring.progress(t - f.stars[j].start) else 1f
        val end = a + (b - a) * reach
        drawLine(Color.White.copy(alpha = 0.07f * alpha), a, end, strokeWidth = glowWidth, cap = StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.5f * alpha), a, end, strokeWidth = lineWidth, cap = StrokeCap.Round)
    }
    for (j in 0 until shown) {
        val s = f.stars[j]
        val at = origin + s.at
        // When this word stopped being the one sung: the next word's start, or the line's end.
        val over = if (j + 1 < shown) f.stars[j + 1].start else if (live && t >= f.end) f.end else Long.MAX_VALUE
        val singing = live && t < over
        val small = label(measurer, labels, s.reading, big = false, density = this)
        if (singing) {
            val since = t - s.start
            val pop = PopSpring.progress(since)
            val bloom = BloomSpring.progress(since)
            val seen = FadeSpring.progress(since)
            // The faintest light around the word being sung: it falls away from the star with no edge
            // to it, so it reads as light, never as a shape.
            stamp(sprites.glow, at, 190.dp.toPx(), 0.075f * seen)
            stamp(sprites.cross, at, 42.dp.toPx() * (1.6f - 0.6f * bloom), 1f)
            val big = label(measurer, labels, s.word, big = true, density = this)
            val grow = 0.72f + 0.28f * pop
            val rise = (1f - pop) * 10.dp.toPx()
            scale(grow, grow, pivot = at) {
                val topLeft = at + Offset(-big.size.width / 2f, -big.size.height - 14.dp.toPx() + rise)
                // The word's own glow, stamped behind it, then the word, crisp.
                stamp(sprites.glow, topLeft + Offset(big.size.width / 2f, big.size.height / 2f), big.size.height * 2.2f, 0.3f * seen, stretch = (big.size.width / big.size.height.toFloat() * 0.62f).coerceAtLeast(1f))
                drawText(big, color = Color.White, topLeft = topLeft, alpha = seen)
            }
        } else {
            // Settling: the big word shrinks and fades into the small one beside its star.
            val since = if (over == Long.MAX_VALUE) Long.MAX_VALUE else t - over
            val settled = if (since == Long.MAX_VALUE) 1f else FadeSpring.progress(since)
            stamp(sprites.cross, at, 17.dp.toPx() + 20.dp.toPx() * (1f - settled), 0.68f * alpha)
            if (settled < 0.999f && live) {
                val big = label(measurer, labels, s.word, big = true, density = this)
                val shrink = 1f - 0.45f * settled
                scale(shrink, shrink, pivot = at) {
                    drawText(big, color = Color.White, topLeft = at + Offset(-big.size.width / 2f, -big.size.height - 14.dp.toPx()), alpha = (1f - settled) * alpha)
                }
            }
            drawText(small, color = Ink.Grey, topLeft = at + Offset(5.dp.toPx(), 4.dp.toPx()), alpha = 0.9f * alpha * settled)
        }
    }
}

private val BigWord = TextStyle(fontFamily = Serif, fontWeight = FontWeight.W500, fontSize = 34.sp, lineHeight = 38.sp)
private val SmallWord = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 10.sp, lineHeight = 12.sp)

private fun label(measurer: TextMeasurer, cache: HashMap<String, TextLayoutResult>, word: String, big: Boolean, density: Density): TextLayoutResult =
    cache.getOrPut((if (big) "B" else "s") + word) { measurer.measure(word, if (big) BigWord else SmallWord, density = density) }
