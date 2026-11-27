package app.aether.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import kotlin.math.roundToInt

/** One answer in a menu of choices; [chosen] is the one in force. */
class Choice(val label: String, val chosen: Boolean, val onChoose: () -> Unit)

/**
 * The menu of choices a key on a page has opened, and where on the screen that key stands. Held by the
 * shell, which draws it ([ChoiceMenu]) above the frost and the dock.
 */
@Stable
class Choices {
    internal var owner: Any? by mutableStateOf(null)
        private set
    internal var anchor: Rect by mutableStateOf(Rect.Zero)
        private set
    internal var entries: List<Choice> by mutableStateOf(emptyList())
        private set
    var open: Boolean by mutableStateOf(false)
        private set

    /** Opens [entries] from the key [owner], which stands at [anchor] (in the window). */
    fun show(owner: Any, anchor: Rect, entries: List<Choice>) {
        this.owner = owner
        this.anchor = anchor
        this.entries = entries
        open = true
    }

    fun close() {
        open = false
    }

    fun isOpenFor(owner: Any): Boolean = open && this.owner === owner
}

/**
 * The menu a key opened, as the page's ⋯ menu is made: a pane of the sheets' glass growing from the key,
 * under it (over it, near the foot, where the dock is), its choices in the grotesk's capitals and the one
 * in force ticked. A touch anywhere else, or Back, puts it away. Drawn by the shell above the frost and the
 * dock, so it stays sharp and the touches round it only close it.
 */
@Composable
fun ChoiceMenu(choices: Choices, footRoom: Dp) {
    val shown by animateFloatAsState(if (choices.open) 1f else 0f, Motion.fluid(0.34f, 0.86f, 0.002f), label = "choices")
    if (shown <= 0.002f) return
    BackHandler(enabled = choices.open) { choices.close() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (choices.open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { choices.close() } })
        Layout(content = { ChoicePane(choices) }) { measurables, constraints ->
            val edge = Space.edge.roundToPx()
            val pane = measurables.first().measure(Constraints(maxWidth = (constraints.maxWidth - 2 * edge).coerceAtLeast(0), maxHeight = constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val key = choices.anchor.translate(-origin.x, -origin.y)
                val gap = 10.dp.roundToPx()
                val x = (key.center.x - pane.width / 2f).roundToInt().coerceIn(edge, (constraints.maxWidth - edge - pane.width).coerceAtLeast(edge))
                // Under the key, unless that would run under the dock: then over it.
                val below = key.bottom + gap + pane.height <= constraints.maxHeight - footRoom.roundToPx()
                val y = if (below) key.bottom.roundToInt() + gap else (key.top.roundToInt() - gap - pane.height).coerceAtLeast(0)
                val pivot = ((key.center.x - x) / pane.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                pane.placeWithLayer(x, y) {
                    alpha = shown.coerceIn(0f, 1f)
                    val k = 0.82f + 0.18f * shown
                    scaleX = k
                    scaleY = k
                    transformOrigin = TransformOrigin(pivot, if (below) 0f else 1f)
                }
            }
        }
    }
}

@Composable
private fun ChoicePane(choices: Choices) {
    Column(
        Modifier
            .widthIn(min = 236.dp)
            .width(IntrinsicSize.Max)
            .liquidGlass(RoundedCornerShape(24.dp), GlassWeight.Pane)
            .padding(vertical = 8.dp),
    ) {
        choices.entries.forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .quietClick {
                        choices.close()
                        c.onChoose()
                    }
                    .padding(start = 22.dp, end = 20.dp, top = 15.dp, bottom = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(c.label, Type.Label, upper = true, maxLines = 1)
                Spacer(Modifier.widthIn(min = 22.dp).weight(1f))
                Box(Modifier.size(15.dp)) { if (c.chosen) Icon(Glyph.Check, size = 15.dp, color = Ink.White) }
            }
        }
    }
}
