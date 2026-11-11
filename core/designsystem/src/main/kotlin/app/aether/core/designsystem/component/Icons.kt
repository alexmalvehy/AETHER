package app.aether.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.theme.Ink

/**
 * The few marks AETHER uses. The player's and the pages' marks are Phosphor's (its Light weight for
 * hairline marks, its Fill for play, pause, skip and a liked heart: one family, drawn by one hand);
 * the rooms' own marks on the tab bar (Listen, Discover, Library) and the gear stay AETHER's.
 */
enum class Glyph { Play, Pause, Next, Previous, Shuffle, Repeat, RepeatOne, Heart, HeartFilled, Queue, Lyrics, More, Down, Back, Search, Close, Plus, Check, Download, Listen, Discover, Library, Profile, Gear, Share, Video, Cast, Forward, Edit, Trash }

@Composable
fun Icon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = Ink.White, weight: Float = 1.4f) {
    // Only the gear cuts a hole in itself, so only it is drawn in a layer of its own.
    Canvas(modifier.size(size).then(if (glyph == Glyph.Gear) Modifier.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen } else Modifier)) {
        PhosphorPaths[glyph]?.let { d ->
            val k = this.size.minDimension / 256f
            scale(k, k, pivot = Offset.Zero) { drawPath(phosphor(glyph, d), color) }
            return@Canvas
        }
        val u = this.size.minDimension / 24f
        val stroke = Stroke(width = weight.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun path(block: Path.() -> Unit) = Path().apply(block)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), stroke.width, StrokeCap.Round)
        when (glyph) {
            Glyph.Play -> drawPath(path { moveTo(7f * u, 4.5f * u); lineTo(19.5f * u, 12f * u); lineTo(7f * u, 19.5f * u); close() }, color)
            Glyph.Pause -> {
                drawRect(color, p(6.5f, 4.5f), Size(3.2f * u, 15f * u))
                drawRect(color, p(14.3f, 4.5f), Size(3.2f * u, 15f * u))
            }
            Glyph.Next -> {
                drawPath(path { moveTo(4.5f * u, 5f * u); lineTo(15.5f * u, 12f * u); lineTo(4.5f * u, 19f * u); close() }, color)
                drawRect(color, p(16.8f, 5f), Size(2.4f * u, 14f * u))
            }
            Glyph.Previous -> {
                drawPath(path { moveTo(19.5f * u, 5f * u); lineTo(8.5f * u, 12f * u); lineTo(19.5f * u, 19f * u); close() }, color)
                drawRect(color, p(4.8f, 5f), Size(2.4f * u, 14f * u))
            }
            Glyph.Shuffle -> {
                drawPath(path { moveTo(3f * u, 7f * u); cubicTo(10f * u, 7f * u, 12f * u, 17f * u, 20f * u, 17f * u) }, color, style = stroke)
                drawPath(path { moveTo(3f * u, 17f * u); cubicTo(10f * u, 17f * u, 12f * u, 7f * u, 20f * u, 7f * u) }, color, style = stroke)
                arrowHead(color, p(20.5f, 7f), stroke.width, u)
                arrowHead(color, p(20.5f, 17f), stroke.width, u)
            }
            Glyph.Repeat, Glyph.RepeatOne -> {
                drawPath(path { moveTo(4f * u, 11f * u); lineTo(4f * u, 8f * u); lineTo(19f * u, 8f * u) }, color, style = stroke)
                drawPath(path { moveTo(20f * u, 13f * u); lineTo(20f * u, 16f * u); lineTo(5f * u, 16f * u) }, color, style = stroke)
                arrowHead(color, p(19.5f, 8f), stroke.width, u)
                arrowHead(color, p(4.5f, 16f), stroke.width, u, left = true)
                if (glyph == Glyph.RepeatOne) line(12f, 10.2f, 12f, 13.8f)
            }
            Glyph.Heart, Glyph.HeartFilled -> {
                val heart = path {
                    moveTo(12f * u, 20f * u)
                    cubicTo(4f * u, 14.5f * u, 2.5f * u, 10.5f * u, 4.2f * u, 7.2f * u)
                    cubicTo(6f * u, 4f * u, 10.2f * u, 4.2f * u, 12f * u, 7.6f * u)
                    cubicTo(13.8f * u, 4.2f * u, 18f * u, 4f * u, 19.8f * u, 7.2f * u)
                    cubicTo(21.5f * u, 10.5f * u, 20f * u, 14.5f * u, 12f * u, 20f * u)
                    close()
                }
                if (glyph == Glyph.HeartFilled) drawPath(heart, color) else drawPath(heart, color, style = stroke)
            }
            Glyph.Queue -> {
                line(4f, 6.5f, 20f, 6.5f); line(4f, 12f, 20f, 12f); line(4f, 17.5f, 13f, 17.5f)
                drawPath(path { moveTo(16f * u, 15f * u); lineTo(20.5f * u, 17.5f * u); lineTo(16f * u, 20f * u); close() }, color)
            }
            Glyph.Lyrics -> {
                // A pilcrow: the editor's mark for words.
                drawPath(path { moveTo(13f * u, 19.5f * u); lineTo(13f * u, 4.5f * u); lineTo(18.5f * u, 4.5f * u) }, color, style = stroke)
                line(16.5f, 4.5f, 16.5f, 19.5f)
                drawCircle(color, 4f * u, p(10f, 8.8f))
            }
            Glyph.More -> listOf(6f, 12f, 18f).forEach { drawCircle(color, 1.35f * u, p(it, 12f)) }
            Glyph.Down -> drawPath(path { moveTo(5f * u, 9f * u); lineTo(12f * u, 16f * u); lineTo(19f * u, 9f * u) }, color, style = stroke)
            Glyph.Back -> drawPath(path { moveTo(15f * u, 5f * u); lineTo(8f * u, 12f * u); lineTo(15f * u, 19f * u) }, color, style = stroke)
            Glyph.Search -> {
                drawCircle(color, 6.2f * u, p(10.5f, 10.5f), style = stroke)
                line(15f, 15f, 20f, 20f)
            }
            Glyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Glyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Glyph.Check -> drawPath(path { moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) }, color, style = stroke)
            Glyph.Download -> {
                line(12f, 4f, 12f, 15.5f)
                drawPath(path { moveTo(7f * u, 11f * u); lineTo(12f * u, 16f * u); lineTo(17f * u, 11f * u) }, color, style = stroke)
                line(5f, 20f, 19f, 20f)
            }
            Glyph.Listen -> {
                // Headphones: a band and two filled cups.
                drawArc(color, 180f, 180f, false, p(4f, 4.5f), Size(16f * u, 16f * u), style = Stroke(1.9f * u, cap = StrokeCap.Round))
                drawRoundRect(color, p(3.2f, 12.5f), Size(4.6f * u, 7.5f * u), androidx.compose.ui.geometry.CornerRadius(2f * u))
                drawRoundRect(color, p(16.2f, 12.5f), Size(4.6f * u, 7.5f * u), androidx.compose.ui.geometry.CornerRadius(2f * u))
            }
            Glyph.Discover -> {
                // A compass: its ring and a filled needle.
                drawCircle(color, 9f * u, p(12f, 12f), style = Stroke(1.6f * u))
                drawPath(path { moveTo(15.8f * u, 8.2f * u); lineTo(13.2f * u, 13.2f * u); lineTo(8.2f * u, 15.8f * u); lineTo(10.8f * u, 10.8f * u); close() }, color)
            }
            Glyph.Library -> {
                // Three records on a shelf, one leaning.
                drawRoundRect(color, p(4f, 4f), Size(3.4f * u, 16f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                drawRoundRect(color, p(9.3f, 4f), Size(3.4f * u, 16f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                rotate(-18f, p(17.5f, 20f)) {
                    drawRoundRect(color, p(15.8f, 4.5f), Size(3.4f * u, 15.5f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                }
            }
            Glyph.Profile -> {
                // A listener: a head and shoulders.
                drawCircle(color, 4.5f * u, p(12f, 8f))
                drawPath(path { moveTo(4f * u, 20.6f * u); cubicTo(4.6f * u, 15.6f * u, 8f * u, 13.8f * u, 12f * u, 13.8f * u); cubicTo(16f * u, 13.8f * u, 19.4f * u, 15.6f * u, 20f * u, 20.6f * u); close() }, color)
            }
            Glyph.Share -> {
                // Up and out of its tray.
                line(12f, 3.6f, 12f, 14.6f)
                drawPath(path { moveTo(8f * u, 7.4f * u); lineTo(12f * u, 3.4f * u); lineTo(16f * u, 7.4f * u) }, color, style = stroke)
                drawPath(path { moveTo(8.4f * u, 11f * u); lineTo(5.4f * u, 11f * u); lineTo(5.4f * u, 20.6f * u); lineTo(18.6f * u, 20.6f * u); lineTo(18.6f * u, 11f * u); lineTo(15.6f * u, 11f * u) }, color, style = stroke)
            }
            Glyph.Video -> {
                // A frame of film, playing.
                drawRoundRect(color, p(3f, 5.5f), Size(18f * u, 13f * u), androidx.compose.ui.geometry.CornerRadius(3f * u), style = stroke)
                drawPath(path { moveTo(10f * u, 9f * u); lineTo(15.4f * u, 12f * u); lineTo(10f * u, 15f * u); close() }, color)
            }
            Glyph.Gear -> {
                for (k in 0 until 8) rotate(k * 45f, p(12f, 12f)) {
                    drawRoundRect(color, p(10.4f, 2.6f), Size(3.2f * u, 4.2f * u), androidx.compose.ui.geometry.CornerRadius(0.8f * u))
                }
                drawCircle(color, 6.6f * u, p(12f, 12f))
                drawCircle(androidx.compose.ui.graphics.Color.Black, 2.6f * u, p(12f, 12f), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            }
            Glyph.Forward -> drawPath(path { moveTo(9f * u, 5f * u); lineTo(16f * u, 12f * u); lineTo(9f * u, 19f * u) }, color, style = stroke)
            Glyph.Edit -> {
                rotate(45f, p(12f, 12f)) {
                    drawRoundRect(color, p(9.6f, 2.5f), Size(4.8f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                    drawPath(path { moveTo(9.6f * u, 17.5f * u); lineTo(14.4f * u, 17.5f * u); lineTo(12f * u, 21.5f * u); close() }, color)
                }
            }
            Glyph.Trash -> {
                drawRoundRect(color, p(4f, 5f), Size(16f * u, 2.2f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                drawRoundRect(color, p(9.5f, 2.8f), Size(5f * u, 2.6f * u), androidx.compose.ui.geometry.CornerRadius(1f * u))
                drawPath(path { moveTo(5.5f * u, 8.6f * u); lineTo(18.5f * u, 8.6f * u); lineTo(17.4f * u, 20.2f * u); quadraticTo(17.3f * u, 21f * u, 16.4f * u, 21f * u); lineTo(7.6f * u, 21f * u); quadraticTo(6.7f * u, 21f * u, 6.6f * u, 20.2f * u); close() }, color)
            }
            Glyph.Cast -> {
                drawPath(path { moveTo(3f * u, 9f * u); lineTo(3f * u, 6f * u); lineTo(21f * u, 6f * u); lineTo(21f * u, 19f * u); lineTo(14f * u, 19f * u) }, color, style = stroke)
                drawCircle(color, 1.2f * u, p(4f, 18.5f))
                drawArc(color, -90f, 90f, false, p(0f, 14.5f), Size(8f * u, 8f * u), style = stroke)
                drawArc(color, -90f, 90f, false, p(-3.5f, 11f), Size(15f * u, 15f * u), style = stroke)
            }
        }
    }
}

/** Phosphor's outlines, parsed once each. */
private val parsedPhosphor = HashMap<Glyph, Path>()

private fun phosphor(glyph: Glyph, d: String): Path = parsedPhosphor.getOrPut(glyph) { PathParser().parsePathString(d).toPath() }

private fun DrawScope.arrowHead(color: Color, tip: Offset, width: Float, u: Float, left: Boolean = false) {
    val d = if (left) 1f else -1f
    drawLine(color, tip, Offset(tip.x + d * 3f * u, tip.y - 2.6f * u), width, StrokeCap.Round)
    drawLine(color, tip, Offset(tip.x + d * 3f * u, tip.y + 2.6f * u), width, StrokeCap.Round)
}
