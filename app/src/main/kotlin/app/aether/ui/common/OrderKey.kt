package app.aether.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Glyph
import app.aether.ui.LocalActions

/** How a list stands, the orders it offers, and the way to choose again. */
class Ordering(val order: ListOrder, val offered: List<ListOrder>, val choose: (ListOrder) -> Unit)

/**
 * How the list named [list] stands: as last chosen for it, kept on the phone; [fallback] until then.
 * Choosing [fallback] again forgets the choice.
 */
@Composable
fun rememberOrdering(list: String, offered: List<ListOrder>, fallback: ListOrder = ListOrder.Added): Ordering {
    val settings = LocalContainer.current.settings
    val orders by settings.songOrders.collectAsStateWithLifecycle()
    val order = ListOrder.of(orders[list], offered, fallback)
    return remember(list, order, offered, fallback) {
        Ordering(order, offered) { chosen -> settings.setSongOrder(list, chosen.key.takeIf { chosen != fallback }) }
    }
}

/**
 * The order a list stands in, named on a small key of glass with the sort mark. Touched, the orders the
 * list offers open from it in a pane of glass, the one in force ticked ([ChoiceMenu]).
 */
@Composable
fun OrderKey(ordering: Ordering, modifier: Modifier = Modifier) {
    val choices = LocalActions.current.choices
    val me = remember { Any() }
    var at by remember { mutableStateOf(Rect.Zero) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        GlassPill(
            ordering.order.short,
            Modifier.onGloballyPositioned { at = it.boundsInRoot() },
            icon = Glyph.Sort,
            small = true,
            lit = choices.isOpenFor(me),
            onClick = {
                choices.show(me, at, ordering.offered.map { o -> Choice(o.label, o == ordering.order) { ordering.choose(o) } })
            },
        )
    }
}
