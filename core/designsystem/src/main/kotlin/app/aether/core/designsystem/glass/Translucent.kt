package app.aether.core.designsystem.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.glass.backdrop.drawBackdrop
import app.aether.core.designsystem.glass.backdrop.effects.blur

/**
 * Glass for the content plane. What sits on a page can't sample the page it's part of (glass never
 * samples glass), so this is glass's depth without its refraction: a faint frost brighter at the
 * top, a rim of light that fades down its sides, and the darkness behind kept. Over black, where
 * almost everything in AETHER sits, it reads as the same material as the floating glass.
 * [lit] brightens it: a chosen tab, a selected chip.
 */
fun Modifier.translucent(shape: Shape, lit: Boolean = false, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(
                Ink.White.copy(alpha = (if (lit) 0.20f else 0.085f) * strength),
                Ink.White.copy(alpha = (if (lit) 0.11f else 0.035f) * strength),
            ),
        ),
    )
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val rim = Brush.verticalGradient(
            listOf(Ink.White.copy(alpha = (if (lit) 0.55f else 0.30f) * strength), Ink.White.copy(alpha = 0.06f * strength), Ink.White.copy(alpha = 0.12f * strength)),
        )
        val width = 0.8.dp.toPx()
        onDrawWithContent {
            drawContent()
            drawOutline(outline, rim, style = Stroke(width))
        }
    }

private const val EdgeScale = 0.25f

/** Which edge of the screen a page meets. */
enum class Edge { Top, Bottom }

/**
 * Where a page meets the edge of the screen it frosts and darkens progressively, as iOS's scroll
 * edge does (with simple glass it only darkens, as PODIUM's does): at the top, so the clock and battery read over anything; at the bottom, so the mini
 * player and the rooms float over frost instead of over words. Blurs of rising strength, each
 * faded in at its own depth, so the frost has no seam; then the room's darkness, falling off
 * softly toward the page, and at the top gathering a little in the corners.
 */
@Composable
fun BoxScope.EdgeBlur(edge: Edge, height: Dp, modifier: Modifier = Modifier) {
    val backdrop = LocalGlassBackdrop.current
    val density = LocalDensity.current
    val top = edge == Edge.Top
    val zone = modifier.align(if (top) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth().height(height)
    // Stops are written from the screen's edge (0) to the zone's inner end (1), and flipped for the bottom.
    fun along(vararg stops: Pair<Float, Color>): Brush =
        Brush.verticalGradient(*(if (top) stops else stops.map { (1f - it.first) to it.second }.reversed().toTypedArray()))
    if (LocalGlassQuality.current == GlassQuality.Simple || backdrop == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        // PODIUM's scroll edge: the page fades into the black instead of frosting. Over a black room
        // that's the same as fading the page itself, and costs one gradient.
        Box(
            zone.background(
                if (top) along(0f to Color.Black.copy(alpha = 0.9f), 0.5f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
                else along(0f to Color.Black.copy(alpha = 0.92f), 0.45f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent),
            ),
        )
        return
    }
    // Two frosts of rising strength, each recorded at a quarter of the screen's resolution: the blur
    // hides the upscale, and the edge costs a sixteenth of what it did. Two, not one: a single frost
    // faded in shows the sharp page ghosting through it where the fade is half way.
    // Each fades in by its depth inside that quarter-resolution recording. Faded at full resolution, it
    // took a layer of the zone's whole size of its own, drawn again on every frame the page moved
    // under it (measured on the A059: the two edges' frosts were half of a scroll's dropped frames).
    val layers = if (top) listOf(6.dp to 1f, 22.dp to 0.62f) else listOf(8.dp to 1f, 24.dp to 0.62f)
    var zoneSize by remember { mutableStateOf(Size.Zero) }
    layers.forEach { (radius, reach) ->
        // From the screen's edge to the zone's inner end, and held beyond both: past the inner end the
        // blur's margin stays clear, so no frost reaches into the page.
        val stops = if (top) arrayOf(0f to Color.Black, reach * 0.55f to Color.Black, reach to Color.Transparent)
        else arrayOf((1f - reach) to Color.Transparent, (1f - reach * 0.55f) to Color.Black, 1f to Color.Black)
        Box(
            zone
                .onSizeChanged { zoneSize = it.toSize() }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { RectangleShape },
                    effects = { blur(with(density) { radius.toPx() } * EdgeScale) },
                    highlight = null,
                    shadow = null,
                    backdropScale = EdgeScale,
                    // Nothing in it to clip: no layer of its own for that.
                    clipContent = false,
                    onDrawBackdrop = { drawPage ->
                        drawPage()
                        // Drawn in the zone's own measure, inside the quarter-resolution recording.
                        val z = zoneSize
                        if (z.height > 0f) {
                            drawRect(
                                Brush.verticalGradient(*stops, startY = 0f, endY = z.height),
                                topLeft = Offset(-z.height, -z.height),
                                size = Size(z.width + z.height * 2f, z.height * 3f),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
                ),
        )
    }
    Box(
        zone.background(
            if (top) along(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent)
            else along(0f to Color.Black.copy(alpha = 0.62f), 0.5f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent),
        ),
    )
    if (top) {
        Box(
            zone
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.32f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.32f)))
                    drawRect(along(0f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
        )
    }
}
