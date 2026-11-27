// The explicit mark and the line that carries it are ported from BitChord
// (sharedUi/.../ui/components/SongText.kt: ExplicitBadge, ExplicitSongTitle; GPL-3.0), set in AETHER's grotesk.
package app.aether.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import kotlin.math.roundToInt

/**
 * The explicit mark, as the catalogues draw it: a small filled tile with the E cut out of it, so
 * whatever is behind (a row, a card's frost, the player's film) shows through the letter. Grey, the
 * colour of the line beneath a title, never the title's white: it marks a song without shouting.
 *
 * The letter is a real hole rather than a letter painted black, which is why the tile draws into a
 * layer of its own: there DstOut can only erase the tile, never what's under it. Square, the capital
 * centred on its own height rather than its line's, which aren't the letter's.
 */
@Composable
fun ExplicitMark(side: Dp, modifier: Modifier = Modifier, color: Color = Ink.Grey) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // The capital takes [CapShare] of the tile's height, as on Apple Music's mark.
    val letterSize = with(density) { (side.toPx() * CapShare / MontserratCap).toSp() }
    val style = remember(letterSize) { TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = letterSize, letterSpacing = 0.sp) }
    val letter = remember(measurer, style) { measurer.measure("E", style) }
    Canvas(
        modifier
            .size(side)
            .semantics { contentDescription = "Explicit" }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val cap = style.fontSize.toPx() * MontserratCap
        drawRoundRect(color = color, cornerRadius = CornerRadius(size.minDimension * Rounding))
        drawText(
            textLayoutResult = letter,
            topLeft = Offset((size.width - letter.size.width) / 2f, (size.height + cap) / 2f - letter.firstBaseline),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * A line of text with the explicit mark at its head when [explicit]: a song's name in a row, the
 * mini player and Now Playing; an artist line on a card. The mark keeps its place at the start
 * however long the line runs, and the line gives way (an ellipsis) before the mark does.
 *
 * The mark is centred on the line's capitals, found from its baseline, not on its line box, whose
 * room for descenders would set it low beside a name in capitals. Laid out by hand rather than as a
 * row, so it holds in a line of any width, a drifting one included.
 */
@Composable
fun MarkedLine(
    text: String,
    explicit: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    markColor: Color = Ink.Grey,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    if (!explicit) {
        Txt(text, style, modifier, color, align, maxLines)
        return
    }
    Marked(style, modifier, markColor, align) { Txt(text, style, color = color, align = align, maxLines = maxLines) }
}

/**
 * [line], text set in [style], with the explicit mark at its head: for a line that isn't one plain
 * string, like a tracklist's song and who made it in one. [line] gets the width the mark leaves.
 */
@Composable
fun Marked(style: TextStyle, modifier: Modifier = Modifier, markColor: Color = Ink.Grey, align: TextAlign? = null, line: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capPx = with(density) { style.fontSize.toPx() } * if (style.fontFamily == Sans) MontserratCap else GaramondCap
    val side = with(density) { (capPx * MarkOverCap).toDp() }
    Layout(
        content = {
            ExplicitMark(side, color = markColor)
            line()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = MarkGap.roundToPx()
        val mark = measurables[0].measure(Constraints())
        val room = if (constraints.hasBoundedWidth) (constraints.maxWidth - mark.width - gap).coerceAtLeast(0) else Constraints.Infinity
        val line = measurables[1].measure(Constraints(maxWidth = room, maxHeight = constraints.maxHeight))
        val baseline = line[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: line.height
        val markTop = (baseline - capPx / 2f - mark.height / 2f).roundToInt()
        val lift = minOf(0, markTop)
        val contentWidth = mark.width + gap + line.width
        val width = contentWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (maxOf(line.height, markTop + mark.height) - lift).coerceIn(constraints.minHeight, constraints.maxHeight)
        // Centred text keeps the mark beside it, the pair centred together.
        val left = if (align == TextAlign.Center) ((width - contentWidth) / 2).coerceAtLeast(0) else 0
        layout(width, height) {
            mark.place(left, markTop - lift)
            line.place(left + mark.width + gap, -lift)
        }
    }
}

/** How much of the tile's height its capital takes, as measured off Apple Music's mark (BitChord). */
private const val CapShare = 0.52f

/** The capital heights of AETHER's grotesk and serif, as fractions of their size. */
private const val MontserratCap = 0.70f
private const val GaramondCap = 0.65f

/** The tile beside a line stands this much taller than the line's capitals. */
private const val MarkOverCap = 1.32f

/** Corner radius as a share of the tile's side: BitChord's 2 dp on 13 dp, a touch softer. */
private const val Rounding = 0.17f

private val MarkGap = 6.dp
