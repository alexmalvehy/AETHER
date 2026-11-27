package app.aether.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.PlaybackState
import app.aether.core.model.PlaybackStatus
import app.aether.ui.LocalActions
import app.aether.core.designsystem.motion.anchor
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The song that's playing, wherever you are: a capsule of glass above the rooms. Tap or lift it to
 * open Now Playing (its play and next keys only do what they say); a hairline of light along its
 * foot is where the song has got to. Now Playing grows out of it ([PlayerWindow]), so it says where
 * it and its cover are. [face] draws only what's written on it (no glass, no cover, nothing to
 * touch): the island's copy of it, riding the island's top edge for the first moment of the way.
 */
@Composable
fun MiniPlayer(state: PlaybackState, onOpen: () -> Unit, modifier: Modifier = Modifier, face: Boolean = false) {
    val track = state.current ?: return
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val liquid = rememberLiquidState()
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(
                if (face) Modifier else Modifier
                    .anchor(actions.anchors, PillKey)
                    .semantics { contentDescription = "Now playing: ${track.title}, ${track.artistLine}. Open" }
                    .liquidGlass(Corners.Pill, GlassWeight.Control, liquid)
                    .liquidTouch(liquid, LocalReduceMotion.current, childrenFirst = true) { onOpen() }
                    .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -12) onOpen() } },
            ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            if (face) Spacer(Modifier.size(44.dp))
            else TreatedArtwork(track.artwork, ArtTreatment.Original, Modifier.size(44.dp).anchor(actions.anchors, PillArtKey).clip(Corners.Pill), sizeHintPx = 160)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                app.aether.core.designsystem.component.MarkedLine(track.title, track.explicit, Type.TitleSmall)
                Txt(if (state.status == PlaybackStatus.ERROR) state.error ?: "" else track.artistLine, Type.Meta, color = Ink.Grey, maxLines = 1)
            }
            IconAction(
                if (state.isPlaying || state.status == PlaybackStatus.BUFFERING || state.status == PlaybackStatus.LOADING) Glyph.Pause else Glyph.Play,
                if (state.isPlaying) "Pause" else "Play",
                { if (!face) { haptics.strong(); actions.playback.toggle() } },
            )
            IconAction(Glyph.Next, "Next", { if (!face) { haptics.strong(); actions.playback.next() } })
        }
        // The position is read while drawing: a tick redraws this line and nothing else.
        val position = actions.playback.position.collectAsStateWithLifecycle()
        val duration = state.durationMs
        Canvas(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).padding(horizontal = 22.dp)) {
            val fraction = if (duration > 0) (position.value.toFloat() / duration).coerceIn(0f, 1f) else 0f
            drawLine(Ink.White.copy(alpha = 0.12f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            drawLine(Ink.White.copy(alpha = 0.8f), Offset(0f, 0f), Offset(size.width * fraction, 0f), 1.dp.toPx())
        }
    }
}
