package app.aether.ui.menu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import app.aether.core.designsystem.glass.LocalReduceMotion
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.GlassPanel
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.PanelDivider
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.launch
import app.aether.player.Downloads
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import app.aether.core.designsystem.component.IconAction
import kotlinx.coroutines.delay
import app.aether.player.SleepTimer
import app.aether.core.designsystem.component.ThinSlider
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler

/**
 * What can be done with a track: grouped glass rows, each with its flat mark. Opened from Now Playing
 * ([inPlayer]) it has the player's own too: the sleep timer, which is about the listening, not the song.
 */
@Composable
fun TrackMenu(track: Track, close: () -> Unit, inPlayer: Boolean = false) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val scope = rememberCoroutineScope()
    val liked by container.library.isLiked(track.id).collectAsStateWithLifecycle(false)
    val playlists by container.library.playlists.collectAsStateWithLifecycle(emptyList())
    val kept by container.downloads.entries.collectAsStateWithLifecycle()
    val keeping = kept[track.id]
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf<SleepPage?>(null) }
    // Back steps out of the timer's pages one at a time, before it closes the menu.
    BackHandler(enabled = sleep != null) { sleep = if (sleep == SleepPage.Custom) SleepPage.Picker else null }
    val reduce = LocalReduceMotion.current
    val thumb = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // Keeping is sent down from the cover it's about: Now Playing's if that's open, or the one here.
    fun keep() {
        container.downloads.keep(listOf(track))
        actions.keep.fly(track, actions.keep.boundsOf(if (actions.player.value > 0.5f) actions.keep.cover else thumb[0]), reduce)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Space.edge, vertical = 14.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TreatedArtwork(track.artwork, ArtTreatment.Cover, Modifier.size(58.dp).clip(Corners.Small).onPlaced { thumb[0] = it }, sizeHintPx = 200)
            Spacer(Modifier.width(14.dp))
            Column {
                app.aether.core.designsystem.component.MarkedLine(track.title, track.explicit, Type.TitleSmall, maxLines = 2)
                Txt(track.artistLine, Type.Meta, color = Ink.Grey, maxLines = 1)
            }
        }
        if (sleep == SleepPage.Picker) {
            SleepTimerPicker(onBack = { sleep = null }, onCustom = { sleep = SleepPage.Custom })
        } else if (sleep == SleepPage.Custom) {
            CustomSleepTimer(onBack = { sleep = SleepPage.Picker }, onStart = { minutes -> SleepTimer.start(minutes); sleep = null })
        } else if (!choosing) {
            Head("This song")
            Entry(Glyph.Next, "Play next") { container.playback.playNext(listOf(track)); close() }
            Entry(Glyph.Queue, "Add to queue") { container.playback.addToQueue(listOf(track)); close() }
            Entry(Glyph.Plus, "Add to playlist") { choosing = true }
            Entry(if (liked) Glyph.HeartFilled else Glyph.Heart, if (liked) "Remove from liked songs" else "Like") {
                scope.launch { container.library.setLiked(track, !liked) }
                close()
            }
            // A song on the phone is on the phone already.
            if (track.id.source != container.local.id) {
                when (keeping?.status) {
                    null -> Entry(Glyph.Download, "Keep on this phone") { keep(); close() }
                    Downloads.Status.Kept -> Entry(Glyph.Check, "Kept on this phone  ·  Remove") { container.downloads.forget(track.id); close() }
                    Downloads.Status.Failed -> Entry(Glyph.Download, "Couldn't keep it  ·  Try again") { container.downloads.forget(track.id); keep(); close() }
                    else -> Entry(Glyph.Download, "Keeping  ${(keeping.progress * 100).toInt()}%  ·  Stop") { container.downloads.forget(track.id); close() }
                }
            }
            Entry(Glyph.Share, "Share song card") { actions.card.openSong(track); close() }
            Head("Go to")
            Entry(Glyph.Discover, "Start radio") { actions.play(listOf(track)); close() }
            track.album?.id?.let { album -> Entry(Glyph.Library, "Go to album") { actions.go(Route.Album(album)) } }
            track.artists.firstOrNull { it.id != null }?.let { a -> Entry(Glyph.Listen, "Go to ${a.name}") { actions.go(Route.Artist(a.id!!)) } }
            if (inPlayer) {
                Head("Listening")
                Entry(Glyph.Sleep, "Sleep timer", value = sleepTimerStatus()) { sleep = SleepPage.Picker }
            }
        } else {
            Head("Add to playlist")
            if (naming) NewPlaylistLine(track.title) { name ->
                scope.launch { container.library.createPlaylist(name, tracks = listOf(track)) }
                actions.keep.note("Added to $name")
                close()
            } else Entry(Glyph.Plus, "New playlist") { naming = true }
            playlists.forEach { p ->
                Entry(Glyph.Library, p.title) {
                    scope.launch { container.library.add(p.id, listOf(track)) }
                    actions.keep.note("Added to ${p.title}")
                    close()
                }
            }
        }
    }
}

/**
 * "New playlist", become the place its name is written: the words go in on this very line, where the
 * label was, with the keyboard up below it. Left empty, the playlist takes the song's name.
 */
@Composable
private fun NewPlaylistLine(fallback: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val finish = { onDone(name.trim().ifEmpty { fallback }) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Plus, size = 17.dp, color = Ink.White)
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).padding(vertical = 17.dp)) {
            if (name.isEmpty()) Txt("New playlist", Type.TitleSmall, color = Ink.Dim, maxLines = 1)
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = Type.TitleSmall.copy(color = Ink.White),
                cursorBrush = SolidColor(Ink.White),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        IconAction(Glyph.Check, "Make the playlist", { finish() }, size = 17.dp)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.White.copy(alpha = 0.6f)))
}

/** A group's name in small spaced capitals, over a hairline: no boxes, the type carries it. */
@Composable
private fun Head(title: String) {
    Txt(title, Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.padding(start = 4.dp, top = 30.dp, bottom = 10.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

/** One thing to do: its mark, its name in the engraved capitals, a hairline under it; what it's set to, at its end. */
@Composable
private fun Entry(glyph: Glyph?, label: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onClick).padding(horizontal = 4.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) Icon(glyph, size = 17.dp, color = Ink.Grey) else Spacer(Modifier.width(17.dp))
        Spacer(Modifier.width(20.dp))
        Txt(label, Type.TitleSmall, maxLines = 1, modifier = Modifier.weight(1f))
        if (value != null) Txt(value, Type.Meta, color = Ink.Grey, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

// --- The sleep timer (BitChord's picker, in AETHER's face) ------------------------------------------

private enum class SleepPage { Picker, Custom }

/** End of the song or a duration, plus a way out once one is running. */
@Composable
private fun SleepTimerPicker(onBack: () -> Unit, onCustom: () -> Unit) {
    val chosen by SleepTimer.minutes.collectAsStateWithLifecycle()
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    val countdown = sleepTimerCountdown()
    // A running timer no preset matches is a custom one: the row ticks and names it.
    val custom = chosen?.takeIf { it !in SleepTimer.PRESETS }
    SleepTimerHead(
        title = "Sleep timer",
        subtitle = when {
            countdown != null -> "$countdown until playback pauses"
            afterTrack -> "Pausing when this song ends"
            else -> "Pause playback after a while"
        },
        onBack = onBack,
    )
    // Finishing the song is the one people reach for at the end of a listen, so it leads rather than
    // sitting under the durations.
    Option("After this song", selected = afterTrack) { SleepTimer.startAfterTrack(); onBack() }
    SleepTimer.PRESETS.forEach { minutes ->
        Option(if (minutes == 60) "1 hour" else "$minutes minutes", selected = minutes == chosen) { SleepTimer.start(minutes); onBack() }
    }
    Option(custom?.let { "Custom  \u00b7  ${sleepDuration(it)}" } ?: "Custom", selected = custom != null, onClick = onCustom)
    if (chosen != null || afterTrack) Entry(Glyph.Close, "Turn off timer") { SleepTimer.cancel(); onBack() }
}

/**
 * Any length up to twelve hours: a large reading over a thin slider, with less and more for a single
 * step either way. A page of the menu, not something over it, so it stays with the presets it sits with.
 */
@Composable
private fun CustomSleepTimer(onBack: () -> Unit, onStart: (Int) -> Unit) {
    val running = SleepTimer.minutes.collectAsStateWithLifecycle().value?.takeIf { it !in SleepTimer.PRESETS }
    var minutes by remember { mutableIntStateOf(running ?: 90) }
    SleepTimerHead(title = "Custom", subtitle = "Pause playback after a while", onBack = onBack)
    Txt(sleepDuration(minutes), Type.Display, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Glyph.Minus, "Less", { if (minutes > SleepTimer.STEP_MINUTES) minutes -= SleepTimer.STEP_MINUTES }, size = 17.dp)
        ThinSlider(SleepTimer.fractionOf(minutes), { minutes = SleepTimer.minutesAt(it) }, Modifier.weight(1f).padding(horizontal = 8.dp))
        IconAction(Glyph.Plus, "More", { if (minutes < SleepTimer.MAX_MINUTES) minutes += SleepTimer.STEP_MINUTES }, size = 17.dp)
    }
    Spacer(Modifier.height(10.dp))
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
    Entry(Glyph.Sleep, "Start timer") { onStart(minutes) }
}

/** The way back, the page's name and a line under it: the top of both of the timer's pages. */
@Composable
private fun SleepTimerHead(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction(Glyph.Back, "Back", onBack, size = 17.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, Type.TitleSmall, maxLines = 1)
            Txt(subtitle, Type.Meta, color = Ink.Grey, maxLines = 1)
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

/** A choice of the picker: the tick leads, in the marks' column, on the one that's set. */
@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) = Entry(if (selected) Glyph.Check else null, label, onClick = onClick)

/** "45 min", or "1h 25m" from an hour up. */
private fun sleepDuration(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"

/** What the sleep timer's row shows at its end, or null when none is set. */
@Composable
private fun sleepTimerStatus(): String? {
    val afterTrack by SleepTimer.afterTrack.collectAsStateWithLifecycle()
    return sleepTimerCountdown() ?: "After this song".takeIf { afterTrack }
}

/** "m:ss" until the timer pauses playback, live, or null when none is running. */
@Composable
private fun sleepTimerCountdown(): String? {
    val deadline by SleepTimer.deadline.collectAsStateWithLifecycle()
    val remaining by produceState(initialValue = SleepTimer.remainingMs(), deadline) {
        while (deadline != null) {
            value = SleepTimer.remainingMs()
            delay(1_000)
        }
        value = null
    }
    return remaining?.let {
        val seconds = it / 1000
        "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }
}
