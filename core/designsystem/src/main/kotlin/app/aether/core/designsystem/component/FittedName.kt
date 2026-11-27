package app.aether.core.designsystem.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import app.aether.core.designsystem.theme.Ink
import app.aether.core.model.withoutEmoji

/**
 * A page's own name (an artist's, an album's, a playlist's), set as large as its place allows and
 * never broken inside a word. A name that fits stands at full size. One a little too wide is set a
 * little smaller to keep to one line. One that must go over several lines breaks only at its spaces,
 * its lines balanced, and is set smaller still, so that two lines of it don't stand twice as tall as
 * one. A single word too wide for the screen ("PinkPantheress") gets smaller until it fits whole.
 */
@Composable
fun FittedName(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    align: TextAlign = TextAlign.Center,
    maxLines: Int = 3,
    /** How small, of its full size, a name may get to stay on one line. */
    oneLineLeast: Float = NameFit.ONE_LINE_LEAST,
    /** The largest, of its full size, a name over several lines is set. */
    severalLinesMost: Float = NameFit.SEVERAL_LINES_MOST,
) {
    val shown = remember(text) { text.withoutEmoji().trim() }
    val measurer = rememberTextMeasurer()
    val base = remember(style) { style.copy(lineBreak = LineBreak.Heading) }
    // The text takes its own width; in a place wider than it, it stands where it's aligned.
    val place = when (align) {
        TextAlign.Center -> Alignment.TopCenter
        TextAlign.End, TextAlign.Right -> Alignment.TopEnd
        else -> Alignment.TopStart
    }
    BoxWithConstraints(modifier, contentAlignment = place) {
        val room = constraints.maxWidth
        val bounded = constraints.hasBoundedWidth
        val fitted = remember(shown, base, room, bounded, maxLines, oneLineLeast, severalLinesMost) {
            if (!bounded || shown.isEmpty()) return@remember base
            fun width(s: String) = measurer.measure(s, base, softWrap = false, maxLines = 1).size.width.toFloat()
            val words = shown.split(Spaces).filter(String::isNotEmpty)
            val k = NameFit.scale(
                oneLine = width(shown),
                widestWord = words.maxOfOrNull(::width) ?: 0f,
                room = room.toFloat(),
                maxLines = maxLines,
                oneLineLeast = oneLineLeast,
                severalLinesMost = severalLinesMost,
            ) { scale -> measurer.measure(shown, base.scaled(scale), constraints = Constraints(maxWidth = room)).lineCount }
            base.scaled(k)
        }
        BasicText(shown, style = fitted.copy(color = color, textAlign = align), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** How large a name is set, from how wide it measures. Pure, so it's tested without a screen. */
object NameFit {
    const val ONE_LINE_LEAST = 0.62f
    const val SEVERAL_LINES_MOST = 0.7f

    /** Below this a name over several lines takes another line rather than getting smaller. */
    private const val FLOOR = 0.42f

    /**
     * Room left for what a width doesn't measure: a script's swashes reach past their letters (the
     * last "d" of The Neighbourhood ran to 8 dp from the screen's edge at 0.98), and a smaller size's
     * width isn't exactly in proportion.
     */
    private const val SAFETY = 0.95f
    private const val STEP = 0.95f

    /**
     * The scale to set a name at, of its full size. [oneLine] is the whole name's width on one line at
     * full size, [widestWord] its widest word's, [room] the width there is; [linesAt] says how many
     * lines it takes at a scale, broken only at spaces. A word is never made to break: the scale is
     * never more than the widest word allows, below the floor too.
     */
    fun scale(
        oneLine: Float,
        widestWord: Float,
        room: Float,
        maxLines: Int,
        oneLineLeast: Float = ONE_LINE_LEAST,
        severalLinesMost: Float = SEVERAL_LINES_MOST,
        linesAt: (Float) -> Int,
    ): Float {
        if (room <= 0f || oneLine <= 0f) return 1f
        if (oneLine <= room) return 1f
        val oneFit = room / oneLine * SAFETY
        if (oneFit >= oneLineLeast || maxLines <= 1) return oneFit
        val wordFit = if (widestWord > 0f) (room / widestWord * SAFETY).coerceAtMost(1f) else 1f
        val floor = minOf(FLOOR, wordFit)
        var k = minOf(severalLinesMost, wordFit)
        while (k > floor && linesAt(k) > maxLines) k = maxOf(floor, k * STEP)
        return k
    }
}

private val Spaces = Regex("""\s+""")

private fun TextStyle.scaled(k: Float): TextStyle = if (k >= 1f) this else copy(
    fontSize = fontSize * k,
    lineHeight = if (lineHeight.isSpecified) lineHeight * k else lineHeight,
    letterSpacing = if (letterSpacing.isSp) letterSpacing * k else letterSpacing,
)
