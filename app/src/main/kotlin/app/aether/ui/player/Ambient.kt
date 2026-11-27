// The mesh is BitChord's (sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MeshGradient.kt,
// `MeshGradientBackground`, github.com/kushagrasinghx/BitChord, GPL-3.0): its four blobs, their anchors,
// orbits, speeds, size and strength, its dim ground, its 1.3 scale and its scrim, unchanged. What AETHER
// changes: it's drawn by one shader in place of a canvas and a 64 dp blur (each blob already falls off along
// smootherstep, over more than half the screen); it fills the song film's frame and fades round the dial in
// the film's own rings; its colours cross over on a spring instead of a 1.4 s tween; it orbits while the
// song plays and is seen (BitChord's `continuous`, its `paused`); and the last step of each colour is grained.
package app.aether.ui.player

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.aether.core.designsystem.art.AmbientColors
import app.aether.core.designsystem.art.SoftArt
import app.aether.core.designsystem.art.rememberPicture
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.retained
import app.aether.core.model.Artwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * The cover's light behind Now Playing, as Apple Music's: four luminous blobs of the cover's own colours
 * over a dim ground of the first, drifting while the song plays. They fill the frame the song's film
 * takes, across the top of the screen, and go out in the film's rings round the dial, so the foot of the
 * screen stays black.
 *
 * One shader, drawn straight onto the screen. The blobs' centres are worked out once a frame and handed
 * to it; each pixel only measures its way to them. The last step of every colour is broken up by a grain
 * finer than a step of eight-bit colour, which is what keeps a slow fade over black from showing bands.
 */
private const val MESH = """
    uniform float2 frame;
    uniform float2 a0;
    uniform float2 a1;
    uniform float2 a2;
    uniform float2 a3;
    uniform float radius;
    uniform float3 c0;
    uniform float3 c1;
    uniform float3 c2;
    uniform float3 c3;
    uniform float3 ground;
    uniform float2 pivot;
    uniform float gone;
    uniform float full;

    float smoother(float t) {
        t = clamp(t, 0.0, 1.0);
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    float hash(float2 p) {
        float3 q = fract(float3(p.xyx) * 0.1031);
        q += dot(q, q.yzx + 33.33);
        return fract((q.x + q.y) * q.z);
    }

    // A blob over what's beneath it: 0.85 of its colour at its centre, falling off along smootherstep
    // (a straight falloff stops dead at the rim, and that corner shows as a ring).
    float3 blob(float3 under, float3 color, float2 q, float2 at) {
        return mix(under, color, 0.85 * (1.0 - smoother(length(q - at) / radius)));
    }

    half4 main(float2 p) {
        // The mesh is drawn 1.3 times the size of its frame, about its middle.
        float2 middle = frame * 0.5;
        float2 q = middle + (p - middle) / 1.3;
        float3 col = ground;
        col = blob(col, c0, q, a0);
        col = blob(col, c1, q, a1);
        col = blob(col, c2, q, a2);
        col = blob(col, c3, q, a3);
        // A gentle scrim, so white words stay legible over bright art: 10% black at the top to 38% at the foot.
        col *= 1.0 - mix(0.10, 0.38, clamp(q.y / frame.y, 0.0, 1.0));
        // The film's own fade: none of it near the dial, all of it once past [full], along smoothstep.
        float t = clamp((length(p - pivot) - gone) / max(full - gone, 1.0), 0.0, 1.0);
        col *= t * t * (3.0 - 2.0 * t);
        // Grain finer than one step of colour, so the fade has no steps to show; none where it's
        // black, so the black round the dial stays #000.
        float lit = clamp(max(col.r, max(col.g, col.b)) * 255.0, 0.0, 1.0);
        col += lit * (hash(p) - 0.5) / 255.0;
        return half4(clamp(col, 0.0, 1.0), 1.0);
    }
"""

/**
 * The film's fade round the dial as a mask (alpha only, for a [androidx.compose.ui.graphics.BlendMode.DstIn]):
 * the same smoothstep the gradient's stops drew, with the same grain, so it falls off without bands.
 */
private const val RING_FADE = """
    uniform float2 pivot;
    uniform float gone;
    uniform float full;

    float hash(float2 p) {
        float3 q = fract(float3(p.xyx) * 0.1031);
        q += dot(q, q.yzx + 33.33);
        return fract((q.x + q.y) * q.z);
    }

    half4 main(float2 p) {
        float t = clamp((length(p - pivot) - gone) / max(full - gone, 1.0), 0.0, 1.0);
        float a = t * t * (3.0 - 2.0 * t);
        // Grain only inside the fade: none of the film by the dial, all of it beyond.
        float inside = clamp(min(a, 1.0 - a) * 255.0, 0.0, 1.0);
        a = clamp(a + inside * (hash(p) - 0.5) / 255.0, 0.0, 1.0);
        return half4(a);
    }
"""

/** BitChord's blobs: where each sits in its frame (fractions of it), and how fast and which way it orbits. */
private val AnchorX = floatArrayOf(0.20f, 0.80f, 0.75f, 0.25f)
private val AnchorY = floatArrayOf(0.25f, 0.20f, 0.80f, 0.75f)
private val Speeds = floatArrayOf(1f, -0.7f, 0.85f, -1.15f)

/** How far each blob strays from its anchor, as a fraction of its frame. */
private const val Orbit = 0.16f

/** A blob's radius, as a fraction of its frame's longer side. */
private const val BlobRadius = 0.62f

/** One full turn of the orbit: BitChord's continuous drift, four of its 8-second drifts. */
private const val TurnSeconds = 32f

/** How a new song's light comes in over the last one's: a spring of BitChord's 1.4 s crossfade. */
private const val ChangeResponse = 1.4f

/** Between the orbit's steps, about thirty a second whatever the display's rate. */
private const val OrbitStepMs = 25L

/** The cover is read this small: four colours are all that's wanted of it. */
private const val PalettePx = 128

/** Covers already read, by address: the same cover gives the same light at once. */
private val ambientCache = object : LinkedHashMap<String, List<Color>>(0, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, List<Color>>) = size > 64
}

/**
 * The light behind Now Playing, in a frame the screen's width and [frame] tall (the song film's). [running]
 * while it's seen and the song plays: the blobs orbit then, and hold where they are otherwise (and always,
 * with motion reduced), and while it [rests]. [fade] is the film's, so the light leaves the dial alone
 * exactly as the film does.
 */
@Composable
fun AmbientLight(artwork: Artwork, fade: FilmFade, frame: Dp, running: Boolean, modifier: Modifier = Modifier, upcoming: Artwork? = null, rests: () -> Boolean = { false }) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // Before Android 13 there's no shader to draw it with: the cover's soft glow, as it was.
        Box(modifier) {
            SoftArt(artwork, Modifier.fillMaxSize().graphicsLayer { alpha = 0.34f }, px = 32)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.2f), 0.5f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black)))
        }
        return
    }
    Mesh(artwork, upcoming, fade, frame, running, rests, modifier)
}

/** No light: a cover with no colour in it, or none read yet. */
private val Unlit = List(5) { Color.Black }

/** A cover's four colours and its ground, as the shader takes them. */
private fun lightOf(colors: List<Color>): List<Color> = if (colors.isEmpty()) Unlit else colors + AmbientColors.dimmed(colors.first())

/** The light of the cover at [url], if its colours have been read (as this song began, or while the last one played). */
private fun knownLight(url: String?): List<Color>? = url?.let { synchronized(ambientCache) { ambientCache[it] } }?.let(::lightOf)

/** Reads [picture]'s colours, off the main thread, and keeps them for [url]. */
private suspend fun readLight(url: String, picture: ImageBitmap): List<Color> {
    val found = withContext(Dispatchers.Default) { runCatching { AmbientColors.of(picture) }.getOrDefault(emptyList()) }
    synchronized(ambientCache) { ambientCache[url] = found }
    return lightOf(found)
}

/** Debug builds (`--es aether.preview light`): the light changed to other colours, as a new song would, with no song changed. */
object AmbientPreview {
    val turn = mutableIntStateOf(0)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Mesh(artwork: Artwork, upcoming: Artwork?, fade: FilmFade, frame: Dp, running: Boolean, rests: () -> Boolean, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    val url = artwork.url(PalettePx)
    val picture = rememberPicture(url, PalettePx, software = true)
    // The next song's colours, read while this one plays: its light is ready the moment it begins, as its
    // cover comes in, not once its picture has been fetched.
    val nextUrl = upcoming?.url(PalettePx)
    val nextPicture = rememberPicture(nextUrl, PalettePx, software = true)
    LaunchedEffect(nextUrl, nextPicture) {
        if (nextUrl != null && nextPicture != null && knownLight(nextUrl) == null) readLight(nextUrl, nextPicture)
    }

    // Two lights: the one a song brings, over the one it replaces. Each is drawn once for its colours and
    // kept, and the new one only grows less transparent, on a spring (BitChord's 1.4 s), so a change of
    // song costs no more a frame than the light standing still and goes as smoothly as the screen can
    // draw. (Changed colour by colour, the whole light was drawn again every frame of the change, on the
    // busiest frames there are, and it stepped.) Songs skipped through quickly make one change, to the
    // last of them.
    val under = remember { mutableStateOf(knownLight(url) ?: Unlit) }
    val over = remember { mutableStateOf<List<Color>?>(null) }
    val through = remember { Animatable(0f) }
    val wanted = remember { mutableStateOf<List<Color>?>(null) }
    LaunchedEffect(url, picture) {
        wanted.value = knownLight(url) ?: if (url != null && picture != null) readLight(url, picture) else return@LaunchedEffect
    }
    val preview by AmbientPreview.turn
    LaunchedEffect(preview) {
        // Asked again, back to the cover's own.
        if (preview > 0) wanted.value = if (preview % 2 == 1) lightOf(PreviewCool) else knownLight(url) ?: Unlit
    }
    LaunchedEffect(reduce) {
        while (true) {
            val next = snapshotFlow { wanted.value }.first { it != null && it != under.value } ?: continue
            if (reduce) {
                under.value = next
                continue
            }
            over.value = next
            through.snapTo(0f)
            through.animateTo(1f, Motion.fluid(ChangeResponse, 1f))
            // The light beneath takes the new colours unseen, under the one over it; then that one goes.
            under.value = next
            withFrameNanos { }
            withFrameNanos { }
            over.value = null
            through.snapTo(0f)
        }
    }

    // The orbit's clock: it runs while the song plays where it's seen, and holds where it was otherwise,
    // asking for no frames at all while the light rests.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running, reduce) {
        if (!running || reduce) return@LaunchedEffect
        var last = -1L
        while (true) {
            if (rests()) {
                snapshotFlow { rests() }.first { !it }
                last = -1L
            }
            val now = withFrameNanos { it }
            // At most a tenth of a second a step, so a long stall holds the drift rather than jumping it.
            if (last >= 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            // About thirty steps a second, not one a frame: a blob moves about a pixel between them, which
            // can't be told from a frame-by-frame drift, and the light is drawn a third as often.
            delay(OrbitStepMs)
        }
    }
    Box(modifier) {
        Light(under.value, time, fade, frame, Modifier.fillMaxSize())
        over.value?.let { colors -> Light(colors, time, fade, frame, Modifier.fillMaxSize().graphicsLayer { alpha = through.value }) }
    }
}

/** One light in [colors], drawn once and kept until its colours or the orbit ([time]) move it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Light(colors: List<Color>, time: FloatState, fade: FilmFade, frame: Dp, modifier: Modifier) {
    val density = LocalDensity.current
    val shader = remember { RuntimeShader(MESH) }
    val brush = remember(shader) { ShaderBrush(shader) }
    Spacer(
        modifier
            // A drawing of its own, kept: opening and closing Now Playing, a video's frames, the glass over
            // it and a change of song all reuse it; the shader runs only when the light itself has moved.
            .retained()
            .drawBehind {
                val fw = size.width
                val fh = with(density) { frame.toPx() }.coerceIn(1f, size.height.coerceAtLeast(1f))
                val drift = time.floatValue * (2f * PI.toFloat() / TurnSeconds)
                shader.setFloatUniform("frame", fw, fh)
                for (i in 0 until 4) {
                    shader.setFloatUniform(
                        "a$i",
                        (AnchorX[i] + Orbit * cos(drift * Speeds[i] + i * 1.7f)) * fw,
                        (AnchorY[i] + Orbit * sin(drift * Speeds[i] * 0.9f + i * 2.3f)) * fh,
                    )
                }
                shader.setFloatUniform("radius", max(fw, fh) * BlobRadius)
                for (i in 0 until 4) {
                    val c = colors[i]
                    shader.setFloatUniform("c$i", c.red, c.green, c.blue)
                }
                val g = colors[4]
                shader.setFloatUniform("ground", g.red, g.green, g.blue)
                with(density) {
                    shader.setFloatUniform("pivot", size.width / 2f, fade.pivotY.toPx())
                    shader.setFloatUniform("gone", fade.gone.toPx())
                    shader.setFloatUniform("full", fade.full.toPx())
                }
                drawRect(brush)
            },
    )
}

/** The debug preview's other light: blues and violets. */
private val PreviewCool = listOf(Color(0xFF2E44C8), Color(0xFF7A3FBF), Color(0xFF3F7ABF), Color(0xFFB05AC8))

/**
 * The film's fade round the dial, as a brush to mask with: grained, so it has no bands (Android 13 and
 * later); null before, where the caller keeps its gradient.
 */
@Composable
fun rememberRingFade(): RingFade? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) remember { RingFade() } else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class RingFade internal constructor() {
    private val shader = RuntimeShader(RING_FADE)
    private val brush = ShaderBrush(shader)

    /** The mask for a box whose fade centres on [pivotX], [pivotY], gone at [gone] and whole at [full] (pixels). */
    fun at(pivotX: Float, pivotY: Float, gone: Float, full: Float): Brush {
        shader.setFloatUniform("pivot", pivotX, pivotY)
        shader.setFloatUniform("gone", gone)
        shader.setFloatUniform("full", full)
        return brush
    }
}
