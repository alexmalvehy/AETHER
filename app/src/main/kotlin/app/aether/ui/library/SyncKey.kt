package app.aether.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Motion
import app.aether.core.model.Playlist
import app.aether.ui.LocalActions
import app.aether.ui.common.LocalContainer
import app.aether.ui.Route
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** The arrows turn once a second while a list is being brought into step. */
private const val DegreesPerSecond = 360f

/**
 * Keeps the list [key] ([app.aether.PlaylistSync]) in step with the listener's YouTube Music account: lit
 * while it's on, its arrows turning while it syncs. Signed out, it says what it needs.
 */
@Composable
fun SyncKey(key: String, size: Dp = 50.dp) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val sync = container.playlistSync
    val links by sync.links.collectAsStateWithLifecycle()
    val busy by sync.syncing.collectAsStateWithLifecycle()
    val signedIn by sync.signedIn.collectAsStateWithLifecycle()
    val on = links[key]?.on == true
    val turning = key in busy
    val angle = rememberTurn(turning)
    GlassCircle(
        Glyph.Sync,
        when {
            turning -> "Syncing with YouTube Music"
            on -> "Synced with YouTube Music. Stop syncing"
            else -> "Sync with YouTube Music"
        },
        {
            when {
                !signedIn -> actions.keep.note("Sign in to YouTube Music to sync")
                on -> {
                    sync.setOn(key, false)
                    actions.keep.note("No longer synced. It stays as it is")
                }
                else -> {
                    sync.setOn(key, true)
                    actions.keep.note("Syncing with YouTube Music")
                }
            }
        },
        size = size,
        lit = on,
        iconModifier = Modifier.graphicsLayer { rotationZ = angle() },
    )
}

/**
 * The key for one of the account's own playlists, seen from YouTube Music: on, it becomes one of this
 * phone's playlists kept in step with the account, and its page is that one's.
 */
@Composable
fun AdoptKey(playlist: Playlist, size: Dp = 50.dp) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val signedIn by container.playlistSync.signedIn.collectAsStateWithLifecycle()
    var turning by remember { mutableStateOf(false) }
    val angle = rememberTurn(turning)
    GlassCircle(
        Glyph.Sync,
        if (turning) "Syncing with YouTube Music" else "Sync with YouTube Music",
        {
            when {
                turning -> Unit
                !signedIn -> actions.keep.note("Sign in to YouTube Music to sync")
                else -> scope.launch {
                    turning = true
                    val id = container.playlistSync.adopt(playlist)
                    turning = false
                    if (id != null) {
                        actions.keep.note("Synced with YouTube Music")
                        actions.nav.open(Route.LocalPlaylist(id))
                    }
                }
            }
        },
        size = size,
        iconModifier = Modifier.graphicsLayer { rotationZ = angle() },
    )
}

/**
 * The arrows' turn: steady while [turning]; then on to where they rest (they look the same every half
 * turn) on a spring that carries their speed, so they slow into place rather than stop dead. Still,
 * with motion reduced.
 */
@Composable
private fun rememberTurn(turning: Boolean): () -> Float {
    val angle = remember { Animatable(0f) }
    val spun = remember { BooleanArray(1) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(turning, reduce) {
        if (turning && !reduce) {
            spun[0] = true
            var last = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (last >= 0L) angle.snapTo(angle.value + ((now - last) / 1e9f).coerceAtMost(1f / 20f) * DegreesPerSecond)
                last = now
            }
        } else if (spun[0]) {
            spun[0] = false
            val rest = ceil(angle.value / 180f) * 180f
            angle.animateTo(rest, Motion.fluid(0.5f, 0.9f), initialVelocity = DegreesPerSecond)
            angle.snapTo(rest % 360f)
        }
    }
    return { angle.value }
}
