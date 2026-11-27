package app.aether.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.dp
import app.aether.ui.LocalPage

/**
 * A page's list that keeps what's just off the screen composed and laid out, a screen and a half
 * either way: a fast fling, or a fling back, finds its rows already made instead of making them as
 * they arrive (the stutter, and the jump when one came out a different height). The page knows its
 * list, so a drag on a page flying away over it can scroll it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPageListState(): LazyListState {
    val state = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 1200.dp, behind = 1200.dp))
    val page = LocalPage.current
    SideEffect { page?.list = state }
    return state
}
