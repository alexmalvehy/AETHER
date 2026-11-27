@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.designsystem.component.quietClick

/** One entry of a page's menu. */
class PageAction(val glyph: Glyph, val label: String, val onClick: () -> Unit)

/** The menu the open page has given the shell, and whether it's open. */
@androidx.compose.runtime.Stable
class PageCorner {
    var entries by androidx.compose.runtime.mutableStateOf<List<PageAction>?>(null)
    var open by androidx.compose.runtime.mutableStateOf(false)
}

/** A page's menu, held by the shell while the page is open: [entries] change as the page does. */
@Composable
fun PageMenu(entries: List<PageAction>) {
    val corner = app.aether.ui.LocalActions.current.corner
    corner.entries = entries
    androidx.compose.runtime.DisposableEffect(corner) {
        onDispose {
            corner.entries = null
            corner.open = false
        }
    }
}

/**
 * The open page's ⋯ key, at the top right where the back key stands at the left, and the menu it
 * opens: a pane of the sheets' glass growing from the key, its entries in the grotesk's capitals. A touch
 * anywhere else puts it away. Drawn by the shell, above the frost, so it stays sharp.
 */
@Composable
fun BoxScope.PageCornerMenu(corner: PageCorner, shown: Boolean) {
    val entries = corner.entries
    val there by animateFloatAsState(if (shown && entries != null) 1f else 0f, Motion.fluid(0.34f, 0.82f, 0.002f), label = "page key")
    if (there <= 0.002f) return
    val open = corner.open && shown
    // Back folds the menu away first.
    androidx.activity.compose.BackHandler(enabled = open) { corner.open = false }
    val menu by animateFloatAsState(if (open) 1f else 0f, Motion.fluid(0.34f, 0.86f, 0.002f), label = "page menu")
    if (menu > 0.002f) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { corner.open = false } })
    GlassCircle(
        Glyph.More,
        "More",
        { corner.open = !corner.open },
        Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(end = Space.edge, top = 6.dp)
            .graphicsLayer {
                val k = 0.6f + 0.4f * there
                scaleX = k
                scaleY = k
                alpha = there.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(1f, 0.5f)
            },
        size = 44.dp,
        lit = open,
        live = true,
    )
    if (menu <= 0.002f || entries == null) return
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(end = Space.edge, top = 58.dp)
            .graphicsLayer {
                alpha = menu.coerceIn(0f, 1f)
                val k = 0.85f + 0.15f * menu
                scaleX = k
                scaleY = k
                transformOrigin = TransformOrigin(1f, 0f)
            }
            .width(220.dp)
            .liquidGlass(RoundedCornerShape(24.dp), GlassWeight.Pane)
            .padding(vertical = 8.dp),
    ) {
        entries.forEach { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .quietClick {
                        corner.open = false
                        e.onClick()
                    }
                    .padding(horizontal = 20.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(e.glyph, size = 17.dp, color = Ink.White)
                Spacer(Modifier.width(14.dp))
                Txt(e.label, Type.Label, upper = true)
            }
        }
    }
}

/**
 * A question before something that can't be undone: what will go, said plainly, and the two answers;
 * the one that destroys is the lit one, and only it goes ahead. A touch outside keeps everything.
 */
@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // Back keeps everything, as a touch outside does; it never takes the page with it.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                // Black under the glass: the question is read, not the page through it.
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(28.dp))
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(title, Type.Display, align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Txt(body, Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton("Keep", onClick = onDismiss)
                GlassButton(confirm, onClick = onConfirm, lit = true)
            }
        }
    }
}

/**
 * The search key that becomes a field: touched, it opens on a spring into a capsule as wide as the row,
 * ready to type, with a tick at its end. The tick closes it back into its key, and the page is as it
 * was; the keyboard's own key only puts the keyboard away.
 */
@Composable
fun ExpandingSearch(
    open: Boolean,
    query: String,
    onOpen: (Boolean) -> Unit,
    onQuery: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    closed: @Composable () -> Unit,
) {
    val grow by animateFloatAsState(if (open) 1f else 0f, Motion.fluid(0.42f, 0.86f, 0.002f), label = "search")
    if (grow <= 0.002f) {
        Box(modifier) { closed() }
        return
    }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(open) { if (open) focus.requestFocus() }
    // Back closes the field back into its key before it leaves the page.
    androidx.activity.compose.BackHandler(enabled = open) {
        focusManager.clearFocus()
        onOpen(false)
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = 50.dp + (maxWidth - 50.dp) * grow.coerceIn(0f, 1.1f)
        Row(
            Modifier
                .width(width)
                .height(50.dp)
                .liquidGlass(Corners.Pill, GlassWeight.Key)
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyph.Search, size = 17.dp, color = Ink.Grey)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f).graphicsLayer { alpha = ((grow - 0.4f) / 0.6f).coerceIn(0f, 1f) }) {
                if (query.isEmpty()) Txt(hint, Type.TitleSmall, color = Ink.Dim, maxLines = 1)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = Type.TitleSmall.copy(color = Ink.White, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.02.em, fontFeatureSettings = null),
                    cursorBrush = SolidColor(Ink.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            IconAction(Glyph.Check, "Done searching", {
                focusManager.clearFocus()
                onOpen(false)
            }, size = 18.dp)
        }
    }
}
