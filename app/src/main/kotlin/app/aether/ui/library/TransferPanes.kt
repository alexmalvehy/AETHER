package app.aether.ui.library

import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.transfer.ImportFormat
import app.aether.transfer.Transfer
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.launch

/**
 * Bringing playlists in, on a pane of glass: from a file (AETHER's own, M3U, CSV, a list of songs,
 * Spotify's data download) or a public playlist's link (Spotify, Apple Music). While it runs, how far
 * it's got; when it's done, what came in and where, and what couldn't be found. Closing the pane
 * doesn't stop it; the shell says when it ends.
 */
@Composable
fun ImportPane(onClose: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val transfer = container.transfer
    val state by transfer.state.collectAsStateWithLifecycle()
    DisposableEffect(transfer) {
        transfer.watch(true)
        onDispose { transfer.watch(false) }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) transfer.importFile(uri) }
    val close = {
        // A finished import's account goes with the pane; a running one carries on.
        if (state is Transfer.State.Done || state is Transfer.State.Failed) transfer.dismiss()
        onClose()
    }
    BackHandler(onBack = close)
    Pane("Import", onDismiss = close) {
        when (val s = state) {
            Transfer.State.Idle -> Choose(onFile = { pick.launch(arrayOf("*/*")) }, onLink = transfer::importLink)
            is Transfer.State.Reading -> Working(s.what, detail = null, fraction = null, onStop = transfer::stop)
            is Transfer.State.Finding -> Working(
                s.playlist,
                detail = listOfNotNull(if (s.of > 1) "Playlist ${s.index} of ${s.of}" else null, "${s.done} of ${s.total} songs").joinToString("  ·  "),
                fraction = if (s.total > 0) s.done.toFloat() / s.total else null,
                onStop = transfer::stop,
            )
            is Transfer.State.Done -> Report(s.summary, onOpen = { id -> close(); actions.nav.open(Route.LocalPlaylist(id)) }, onDone = close)
            is Transfer.State.Failed -> {
                Txt("Couldn't bring it in", Type.Display, align = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Txt(s.reason, Type.Body, color = Ink.Grey, align = TextAlign.Center)
                Spacer(Modifier.height(24.dp))
                GlassButton("Try another", onClick = { transfer.dismiss() })
            }
        }
    }
}

/** A file or a link. */
@Composable
private fun Choose(onFile: () -> Unit, onLink: (String) -> Unit) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    Txt("Bring playlists in", Type.Display, align = TextAlign.Center)
    Spacer(Modifier.height(22.dp))
    GlassButton("Choose a file", onClick = onFile)
    Spacer(Modifier.height(12.dp))
    Txt(
        "AETHER's own, M3U, CSV (Exportify's too) or a list of songs; or Spotify's data download, which has every playlist whole and your liked songs.",
        Type.Meta,
        color = Ink.Grey,
        align = TextAlign.Center,
    )
    Spacer(Modifier.height(26.dp))
    Hairline()
    Spacer(Modifier.height(24.dp))
    Strapline("Or a playlist's link")
    Spacer(Modifier.height(14.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (link.isEmpty()) Txt("Spotify or Apple Music", Type.Body, color = Ink.Dim, align = TextAlign.Center)
        BasicTextField(
            value = link,
            onValueChange = { link = it },
            singleLine = true,
            textStyle = Type.Body.copy(color = Ink.White, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Ink.White),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) onLink(link) }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(8.dp))
    Hairline()
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Read only when touched: what's on the clipboard is the listener's.
        GlassPill("Paste", small = true, onClick = {
            val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            if (!clip.isNullOrBlank()) link = clip.trim()
        })
        GlassPill("Bring it in", small = true, lit = link.isNotBlank(), onClick = { if (link.isNotBlank()) onLink(link) })
    }
    Spacer(Modifier.height(12.dp))
    Txt(
        "A public playlist. A Spotify link gives its first hundred songs; the data download, all of them.",
        Type.Meta,
        color = Ink.Grey,
        align = TextAlign.Center,
    )
}

/** Under way: what, how far, and a way to stop. */
@Composable
private fun Working(title: String, detail: String?, fraction: Float?, onStop: () -> Unit) {
    Txt(title, Type.Display, align = TextAlign.Center, maxLines = 2)
    Spacer(Modifier.height(12.dp))
    Strapline(detail ?: "Reading")
    Spacer(Modifier.height(22.dp))
    if (fraction != null) {
        // The keep line's way of showing how far: a hairline, and the part done in white.
        Canvas(Modifier.fillMaxWidth(0.7f).height(2.dp)) {
            drawLine(Ink.White.copy(alpha = 0.14f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
            drawLine(Ink.White.copy(alpha = 0.85f), Offset(0f, size.height / 2), Offset(size.width * fraction.coerceIn(0f, 1f), size.height / 2), size.height)
        }
    } else {
        GridDots(size = 16.dp)
    }
    Spacer(Modifier.height(22.dp))
    Txt("You can close this: it carries on, and nothing it brings in is ever doubled.", Type.Meta, color = Ink.Grey, align = TextAlign.Center)
    Spacer(Modifier.height(18.dp))
    GlassPill("Stop", small = true, onClick = onStop)
}

/** What came in, where it went, what couldn't be found. */
@Composable
private fun Report(summary: Transfer.Summary, onOpen: (Long) -> Unit, onDone: () -> Unit) {
    val made = summary.playlists.count { it.created }
    val headline = when {
        summary.playlists.isEmpty() && summary.liked > 0 -> "${songs(summary.liked)} liked"
        summary.songsAdded == 0 && summary.liked == 0 -> "Nothing new"
        made == 1 && summary.playlists.size == 1 -> "A new playlist"
        made > 0 -> "${count(made, "new playlist")}"
        else -> "${songs(summary.songsAdded)} added"
    }
    Txt(headline, Type.Display, align = TextAlign.Center)
    summary.stopped?.let {
        Spacer(Modifier.height(10.dp))
        Txt(it, Type.Meta, color = Ink.Grey, align = TextAlign.Center)
    }
    Spacer(Modifier.height(20.dp))
    for (b in summary.playlists) {
        Row(
            Modifier.fillMaxWidth().then(if (b.playlistId != null) Modifier.quietClick { onOpen(b.playlistId) } else Modifier).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Txt(b.name, Type.TitleSmall, maxLines = 1)
                Spacer(Modifier.height(3.dp))
                Txt(line(b), Type.Meta, color = Ink.Grey, maxLines = 2)
            }
            if (b.playlistId != null) Icon(Glyph.Forward, size = 12.dp, color = Ink.Dim)
        }
        Hairline()
    }
    if (summary.liked > 0 || summary.likedMissing.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Txt("Liked songs", Type.TitleSmall, maxLines = 1)
            Spacer(Modifier.height(3.dp))
            Txt(listOfNotNull(if (summary.liked > 0) "${songs(summary.liked)} liked" else "None new", missing(summary.likedMissing.size)).joinToString("  ·  "), Type.Meta, color = Ink.Grey)
        }
        Hairline()
    }
    if (summary.playlists.any { it.partial }) {
        Spacer(Modifier.height(16.dp))
        Txt(
            if (summary.format == ImportFormat.SPOTIFY_LINK) "Spotify's page gives a link's first hundred songs. For the rest, bring in Spotify's data download (spotify.com, Account, Privacy settings): what's here already won't be doubled."
            else "The page gave only part of the playlist.",
            Type.Meta,
            color = Ink.Grey,
            align = TextAlign.Center,
        )
    }
    val lost = summary.playlists.flatMap { it.missing } + summary.likedMissing
    if (lost.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        Strapline("Not found")
        Spacer(Modifier.height(6.dp))
        Txt("Nothing close was put in their place.", Type.Meta, color = Ink.Dim, align = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        lost.distinct().take(MISSING_SHOWN).forEach { Txt(it, Type.Meta, color = Ink.Grey, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
        if (lost.distinct().size > MISSING_SHOWN) {
            Spacer(Modifier.height(6.dp))
            Txt("and ${lost.distinct().size - MISSING_SHOWN} more", Type.Meta, color = Ink.Dim)
        }
    }
    Spacer(Modifier.height(24.dp))
    GlassButton("Done", onClick = onDone)
}

private fun line(b: Transfer.Brought): String = listOfNotNull(
    when {
        b.playlistId == null -> "None of its songs could be found"
        b.created -> "${songs(b.added)}, a new playlist"
        b.added == 0 -> "Had every one already"
        else -> "${songs(b.added)} added" + if (b.already > 0) ", ${b.already} there already" else ""
    },
    missing(b.missing.size).takeIf { b.playlistId != null },
).joinToString("  ·  ")

private fun missing(n: Int) = if (n > 0) "$n not found" else null

private fun songs(n: Int) = count(n, "song")

private fun count(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

private const val MISSING_SHOWN = 60

/**
 * One playlist written out, the way chosen: AETHER's own file (whole, for any AETHER), an M3U8 (other
 * players) or a CSV (a spreadsheet). Android asks where it goes.
 */
@Composable
fun ExportPane(playlistId: Long, title: String, onClose: () -> Unit) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val transfer = container.transfer
    val launchers = Transfer.Format.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
            // Backing out of Android's picker leaves the choice here.
            if (uri != null) {
                scope.launch {
                    val ok = transfer.export(playlistId, format, uri)
                    actions.keep.note(if (ok) "“$title” written out" else "The file couldn't be written")
                }
                onClose()
            }
        }
    }
    BackHandler(onBack = onClose)
    Pane("Export", onDismiss = onClose) {
        Txt(title, Type.Display, align = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(18.dp))
        for (format in Transfer.Format.entries) {
            Row(
                Modifier.fillMaxWidth().quietClick { launchers.getValue(format).launch(transfer.fileName(title, format)) }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(format.label, Type.TitleSmall)
                    Spacer(Modifier.height(3.dp))
                    Txt(format.detail, Type.Meta, color = Ink.Grey)
                }
                Spacer(Modifier.width(12.dp))
                Icon(Glyph.Forward, size = 12.dp, color = Ink.Dim)
            }
            Hairline()
        }
    }
}

/** A pane of glass over the room, as the naming dialog's: touch outside it to close it. */
@Composable
private fun Pane(label: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tallest = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onDismiss() } }.imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .heightIn(max = tallest)
                .liquidGlass(RoundedCornerShape(28.dp), GlassWeight.Pane)
                .pointerInput(Unit) { detectTapGestures { } }
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(label, Type.Label, color = Ink.Grey, upper = true)
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}
