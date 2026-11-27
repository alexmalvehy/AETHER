package app.aether.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import kotlinx.coroutines.launch

/**
 * A thin line of a slider (BitChord's ThinSlider, in AETHER's face): what's chosen is the white part,
 * the rest a faint line. It thickens under the finger (6 to 10 points, on a spring) and goes back when
 * let go. Touched anywhere along it, the value goes there; dragged, it follows. [value] is 0 to 1.
 */
@Composable
fun ThinSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val held = remember { Animatable(0f) }
    val change by rememberUpdatedState(onValueChange)
    Spacer(
        modifier
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { target -> change(target.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    scope.launch { held.animateTo(1f, Thickens) }
                    change((down.position.x / size.width).coerceIn(0f, 1f))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        change((c.position.x / size.width).coerceIn(0f, 1f))
                    }
                    scope.launch { held.animateTo(0f, Thickens) }
                }
            }
            .drawBehind {
                val thick = (6f + 4f * held.value).dp.toPx()
                val top = (size.height - thick) / 2f
                val radius = CornerRadius(thick / 2f)
                drawRoundRect(Ink.White.copy(alpha = 0.26f), Offset(0f, top), Size(size.width, thick), radius)
                val filled = size.width * value.coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(Ink.White.copy(alpha = 0.92f), Offset(0f, top), Size(filled.coerceAtLeast(thick), thick), radius)
            },
    )
}

private val Thickens = Motion.fluid<Float>(0.3f, 1f, 0.001f)
