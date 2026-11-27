package app.aether.ui.detail

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.FingertipLight
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.clock
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.Artwork
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.aether.core.designsystem.theme.Serif
import app.aether.core.designsystem.theme.separate
import app.aether.core.model.withoutEmoji
import app.aether.ui.folderHead
import app.aether.ui.pageLanded
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.player.Downloads
import app.aether.ui.common.LocalContainer

/*
 * A playlist's page as a record's sleeve: the folder that was touched flies up and stays open and
 * large at the head of the page; the tracklist under it; the label's credit sits at the foot.
 */

/** How wide a folder stands at the head of its page. */
val FolderHeadWidth = 300.dp

/**
 * The folder held open at the head, where a folder that was touched lands, and the page's actions
 * under it. Its [files] are the first songs, or (before the list has loaded) what the folder showed.
 */
@Composable
fun SleeveHead(title: String, detail: String?, artwork: Artwork, files: List<FolderFile>, actions: @Composable () -> Unit) {
    Column(Modifier.separate().fillMaxWidth().padding(top = 34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FolderCard(
            title = title,
            detail = detail,
            artwork = artwork,
            files = files,
            onOpen = {},
            width = FolderHeadWidth,
            opened = true,
            modifier = Modifier.folderHead(),
        )
        Spacer(Modifier.height(28.dp))
        actions()
        Spacer(Modifier.height(34.dp))
    }
}

/**
 * Shuffle, play, keep it in the library (when it can be), and keep its songs on the phone. A playlist
 * or album says which one it is ([follows]): keeping it then keeps what's added to it later as well,
 * and the same key stops that.
 */
@Composable
fun SleeveActions(tracks: List<Track>, saved: Boolean?, onSave: (() -> Unit)?, follows: String? = null, keepKey: Boolean = true, third: (@Composable () -> Unit)? = null, last: (@Composable () -> Unit)? = null) {
    val actions = LocalActions.current
    val container = LocalContainer.current
    val kept by container.downloads.entries.collectAsStateWithLifecycle()
    val allKept = tracks.isNotEmpty() && tracks.all { kept[it.id]?.status == Downloads.Status.Kept }
    val following by container.settings.keptCollections.collectAsStateWithLifecycle()
    val followed = follows != null && follows in following
    val done = allKept || followed
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCircle(Glyph.Shuffle, "Shuffle", { actions.shuffle(tracks) }, size = 50.dp)
        GlassButton("Play", onClick = { actions.play(tracks, 0) })
        when {
            third != null -> third()
            saved != null && onSave != null -> GlassCircle(if (saved) Glyph.HeartFilled else Glyph.Heart, if (saved) "Saved" else "Save", onSave, size = 50.dp, lit = saved)
            else -> GlassCircle(Glyph.Plus, "Add to queue", { actions.playback.addToQueue(tracks) }, size = 50.dp)
        }
        if (keepKey) GlassCircle(
            if (done) Glyph.Check else Glyph.Download,
            when { followed -> "Keeping new songs. Stop"; allKept -> "Kept on this phone"; else -> "Keep on this phone" },
            {
                when {
                    followed -> {
                        container.keepSync.stop(follows!!)
                        actions.keep.note("New songs won't be kept")
                    }
                    else -> {
                        if (!allKept) container.downloads.keep(tracks)
                        if (follows != null && tracks.isNotEmpty()) {
                            container.keepSync.follow(follows, tracks)
                            actions.keep.note("New songs will be kept on Wi-Fi")
                        }
                    }
                }
            },
            size = 50.dp,
            lit = done,
        )
        last?.invoke()
    }
}

private val CreditStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 17.sp, lineHeight = 21.sp)
private val RowStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W500, fontSize = 13.5.sp, lineHeight = 18.sp)
private val ByStyle = SpanStyle(fontFamily = Serif, fontWeight = FontWeight.W400, fontSize = 14.5.sp, color = Ink.Grey)

/** How far in a row's words and its line stand from the screen's edge. */
private val RowEdge = Space.edge + 4.dp

/** How far the three dots' key reaches past the dots themselves: it's the dots that line up with the edge. */
private val DotsReach = 16.dp

/**
 * The tracklist: a head (Length, Tracklist), then each song with its length, a mark (pause on the one
 * that's playing), its name and who it's by, and a line between one song and the next. Touch to play
 * from there; hold, or touch the three dots, for the song's menu.
 */
fun LazyListScope.sleeveTracks(tracks: List<Track>, playing: MediaId?, isPlaying: Boolean, keyPrefix: String, onPlay: (Int) -> Unit) {
    if (tracks.isEmpty()) return
    val lengths = tracks.any { it.durationMs != null }
    item(key = "$keyPrefix:head") { SleeveHeader(lengths) }
    itemsIndexed(tracks, key = { i, t -> "$keyPrefix:$i:${t.id}" }) { i, t ->
        SleeveRow(
            track = t,
            playing = t.id == playing,
            isPlaying = isPlaying,
            first = i == 0,
            lengths = lengths,
            onClick = { onPlay(i) },
        )
    }
}

/** The tracklist's head: Length (when the source gives lengths), Tracklist. */
@Composable
private fun SleeveHeader(lengths: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = RowEdge).padding(bottom = 4.dp)) {
        if (lengths) Txt("Length", Type.Label, color = Ink.Dim, upper = true, modifier = Modifier.width(78.dp))
        else Spacer(Modifier.width(22.dp))
        Txt("Tracklist", Type.Label, color = Ink.Dim, upper = true)
    }
}

/**
 * A tracklist drawn whole: for a list that sits among a page's other things (an
 * album's songs, an artist's best, a day of history). Touch to play from there; hold for the menu.
 */
@Composable
fun SleeveList(tracks: List<Track>, playContext: List<Track> = tracks, alone: Boolean = false, artists: Boolean = true) {
    if (tracks.isEmpty()) return
    val lengths = tracks.any { it.durationMs != null }
    val container = LocalContainer.current
    val actions = LocalActions.current
    val playback by container.playback.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SleeveHeader(lengths)
        tracks.forEachIndexed { i, t ->
            SleeveRow(
                track = t,
                playing = t.id == playback.current?.id,
                isPlaying = playback.isPlaying,
                first = i == 0,
                lengths = lengths,
                artists = artists,
                // Alone, a song brings its own radio: what follows it is what's like it, not its neighbours in the list.
                onClick = { if (alone) actions.playRadio(t) else actions.play(playContext, playContext.indexOf(t).coerceAtLeast(0)) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SleeveRow(track: Track, playing: Boolean, isPlaying: Boolean, first: Boolean, lengths: Boolean, artists: Boolean = true, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    val actions = LocalActions.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = FingertipLight,
                onLongClick = { haptics.strong(); actions.openMenu(track) },
                onClick = { haptics.select(); onClick() },
            )
            // Drawn with the row and not a piece of its own: a long list has one of these for every song.
            .drawBehind {
                if (!first) {
                    val inset = RowEdge.toPx()
                    drawLine(RowLine, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = 0.5.dp.toPx())
                }
            }
            .padding(start = RowEdge, end = RowEdge - DotsReach),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lengths) Txt(track.durationMs?.let { clock(it) } ?: "", Type.Meta, color = Ink.Dim, maxLines = 1, modifier = Modifier.width(56.dp))
        Icon(if (playing && isPlaying) Glyph.Pause else Glyph.Play, size = 10.dp, color = if (playing) Ink.White else Ink.Grey)
        Spacer(Modifier.width(12.dp))
        // The song first, and who it's by after it, so it's their names the line runs out on and never
        // the song's. Each in its own voice, as everywhere: the name in the grotesk, who in the serif.
        val line = remember(track.title, track.artistLine, artists) {
            buildAnnotatedString {
                append(track.title.withoutEmoji())
                if (artists && track.artistLine.isNotBlank()) {
                    withStyle(ByStyle) {
                        append("   ")
                        append(track.artistLine.withoutEmoji())
                    }
                }
            }
        }
        val lineStyle = RowStyle.copy(color = if (playing) Ink.White else Ink.White.copy(alpha = if (track.available) 0.88f else 0.35f))
        if (track.explicit) {
            // The mark heads the song's name, in the grey of who made it (BitChord, Apple Music).
            app.aether.core.designsystem.component.Marked(RowStyle, Modifier.weight(1f), markColor = if (track.available) Ink.Grey else Ink.Faint) {
                BasicText(line, style = lineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            BasicText(line, Modifier.weight(1f), style = lineStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Whether it's on the phone: the arrow of keeping, grey when it's kept, dim while it's coming down.
        val kept = LocalContainer.current.downloads.entries.collectAsStateWithLifecycle().value[track.id]?.status
        when (kept) {
            Downloads.Status.Kept -> Icon(Glyph.Download, Modifier.padding(start = 10.dp).semantics { contentDescription = "Kept on this phone" }, size = 12.dp, color = Ink.Grey)
            Downloads.Status.Keeping, Downloads.Status.Queued -> Icon(Glyph.Download, Modifier.padding(start = 10.dp), size = 12.dp, color = Ink.Dim)
            else -> Unit
        }
        IconAction(Glyph.More, "More", { actions.openMenu(track) }, size = 16.dp, tint = Ink.Grey)
    }
}

/** The line between two songs: a little more than a hairline, so it's still there on a dim screen. */
private val RowLine = Ink.White.copy(alpha = 0.16f)

/** The label's credit at the foot of the sleeve: the cover small, the name, who made it, and its count and length. */
@Composable
fun SleeveLabel(artwork: Artwork, title: String, by: String?, tracks: List<Track>) {
    val total = tracks.sumOf { it.durationMs ?: 0L }
    val length = when {
        total <= 0 -> null
        total >= 3_600_000 -> "${total / 3_600_000} hr ${(total % 3_600_000) / 60_000} min"
        else -> "${total / 60_000} min"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 4.dp).padding(top = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        TreatedArtwork(artwork, ArtTreatment.Cover, Modifier.size(64.dp).clip(Corners.Small), sizeHintPx = 200)
        Spacer(Modifier.width(16.dp))
        Column {
            Txt(title, CreditStyle, maxLines = 2)
            if (!by.isNullOrBlank()) Txt(by, Type.Body, color = Ink.Grey, maxLines = 1)
            Txt(listOfNotNull(songCount(tracks.size), length).joinToString("  ·  "), Type.Label, color = Ink.Dim, upper = true, maxLines = 1)
        }
    }
}
