@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.profile

import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.component.Glyph
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.GlassButton
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.TrackRow
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.designsystem.theme.separate
import app.aether.core.model.Artwork
import app.aether.core.model.ListenerAccount
import app.aether.core.model.Outcome
import app.aether.core.model.Track
import app.aether.core.model.withoutEmoji
import app.aether.ui.LocalActions
import app.aether.ui.LocalRoomsHidden
import app.aether.ui.common.Colophon
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.NameDialog
import app.aether.ui.common.rememberPageListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** How many of the most played go on the stack of cards. */
private const val DeckSize = 6

/** How many artists are asked about, for what the listener's music is. */
private const val GenreArtists = 12

/**
 * The listener, as what they've played: a stack of their most played artists, the numbers, when and
 * how they listen, and a metal card of it all to send to someone. Worked out on the phone from what's
 * been played here, from the very first song.
 */
/**
 * Plays of kept songs that 2.0 wrote down blank (no name, no artist) are told again from what the
 * downloads know of them, so they count for whoever made them.
 */
internal fun mended(history: List<app.aether.core.data.Played>, container: app.aether.Container): List<app.aether.core.data.Played> {
    val kept = container.downloads.entries.value
    return history.map { p ->
        if (p.track.title.isNotBlank() && p.track.artists.isNotEmpty()) p
        else kept[p.track.id]?.track?.takeIf { it.title.isNotBlank() }?.let { p.copy(track = it) } ?: p
    }
}

@Composable
fun ProfileScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val library = container.library
    val stats by remember { library.history(StatsWindow).map { profileStats(mended(it, container)) }.flowOn(Dispatchers.Default) }.collectAsStateWithLifecycle(null)
    val chosenName by container.settings.profileName.collectAsStateWithLifecycle()
    val account by produceState<ListenerAccount?>(null) { value = (container.sources.primary.account() as? Outcome.Ok)?.value }
    val consent by container.settings.artistArt.collectAsStateWithLifecycle()
    val faces = remember { Faces(container) }
    var naming by remember { mutableStateOf(false) }
    val list = rememberPageListState()
    val hidden = LocalRoomsHidden.current
    val name = chosenName ?: account?.name ?: "You"
    val current = stats

    // What the music is: asked of the artists played most, and left out where nobody knows.
    val artists = current?.artists?.take(GenreArtists).orEmpty()
    val genres by produceState(emptyList<Pair<String, Float>>(), artists.map { it.name to it.plays }, consent) {
        if (!consent || artists.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        val weights = LinkedHashMap<String, Int>()
        artists.forEach { a -> container.artistArt.genreOf(a.name)?.let { g -> weights[g] = (weights[g] ?: 0) + a.plays } }
        val all = weights.values.sum().coerceAtLeast(1)
        value = weights.entries.sortedByDescending { it.value }.map { it.key to it.value.toFloat() / all }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 0.dp)) {
            item(key = "head") { Head(name, current, account?.photo ?: Artwork.None, onName = { naming = true }, onExport = {
                val s = current
                if (s != null && !s.isEmpty) exportCard(actions, faces, name, s, genres.firstOrNull()?.first)
            }) }
            when {
                current == null -> Unit
                current.isEmpty -> item { Notice("It starts with one song", "Everything you play shows up here: who you come back to, when you listen, and what it adds up to. It stays on this phone.") }
                else -> {
                    if (current.artists.isNotEmpty()) item(key = "deck") {
                        Column(Modifier.fillMaxWidth().padding(top = 34.dp)) {
                            SectionHeading("Most played", strapline = "Throw one aside")
                            Spacer(Modifier.height(52.dp))
                            ArtistDeck(current.artists.take(DeckSize), faces)
                        }
                    }
                    item(key = "band") {
                        Column(Modifier.separate().fillMaxWidth().padding(top = 56.dp)) {
                            SectionHeading("What it adds up to")
                            Spacer(Modifier.height(24.dp))
                            StatsBand(bandOf(current), onScreen = { shows(list, "band", hidden) })
                        }
                    }
                    item(key = "rings") {
                        Column(Modifier.separate().fillMaxWidth().padding(top = 56.dp)) {
                            SectionHeading("How you listen", strapline = "Hold one")
                            Spacer(Modifier.height(26.dp))
                            OrbitRings(ringsOf(current), onScreen = { shows(list, "rings", hidden) })
                        }
                    }
                    item(key = "clock") { When(current) }
                    if (genres.isNotEmpty()) item(key = "genres") { Shares("What you listen to", "By the artists you play most", genres.take(6).map { it.first to it.second }) }
                    item(key = "types") { Types(current) }
                    if (current.prompts.isNotEmpty()) item(key = "prompts") { Prompts(current) }
                    item(key = "songs") { TopSongs(current) }
                }
            }
            item(key = "colophon") { Colophon() }
        }
        if (naming) NameDialog("Your name", chosenName ?: account?.name ?: "", onDone = { naming = false; container.settings.setProfileName(it) }, onDismiss = { naming = false })
    }
}

internal const val StatsWindow = 20_000

private fun shows(list: LazyListState, key: String, hidden: () -> Boolean): Boolean =
    !hidden() && list.layoutInfo.visibleItemsInfo.any { it.key == key }

private fun exportCard(actions: app.aether.ui.Actions, faces: Faces, name: String, s: ProfileStats, genre: String?) {
    actions.card.launch.launch {
        val top = s.artists.take(3)
        val found = withContext(Dispatchers.Default) { top.map { faces.of(it) } }
        actions.card.open(
            CardData(
                name = name,
                persona = s.persona,
                since = s.sinceLabel,
                artists = s.artists.take(4),
                faces = found,
                hours = hoursLabel(s.minutes),
                plays = s.plays,
                artistCount = s.artistCount,
                bestStreak = s.bestStreak,
                song = s.songs.firstOrNull()?.track?.title,
                genre = genre,
            ),
        )
    }
}

/** "48 min" up to an hour; then hours, with a decimal only while it says something. */
private fun hoursLabel(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 600 -> String.format(java.util.Locale.US, "%.1f hr", minutes / 60f)
    else -> "${minutes / 60} hr"
}

@Composable
private fun Head(name: String, stats: ProfileStats?, photo: Artwork, onName: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.separate().fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility).padding(top = 44.dp, start = 20.dp, end = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!photo.isEmpty) {
            TreatedArtwork(photo, ArtTreatment.Print, Modifier.size(76.dp).clip(CircleShape), sizeHintPx = 240)
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.quietClick(onName), verticalAlignment = Alignment.CenterVertically) {
            app.aether.core.designsystem.component.FittedName(name, Type.name(name), maxLines = 2, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Icon(Glyph.Edit, size = 16.dp, color = Ink.Grey)
        }
        if (stats != null && !stats.isEmpty) {
            Spacer(Modifier.height(10.dp))
            Strapline(listOfNotNull(stats.persona, stats.secondPersona).joinToString("  ·  "), color = Ink.White)
            stats.sinceLabel?.let {
                Spacer(Modifier.height(6.dp))
                Txt("Listening here since $it", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            }
            Spacer(Modifier.height(22.dp))
            GlassButton("Export card", onClick = onExport)
        }
    }
}

private fun bandOf(s: ProfileStats): List<BandStat> {
    val hours = s.minutes / 60.0
    val small = s.minutes < 60
    val bins = s.artists.take(18).map { it.plays }
    return listOf(
        BandStat(
            label = if (small) "Minutes" else "Hours",
            value = if (small) s.minutes.toDouble() else hours,
            decimals = if (!small && hours < 10) 1 else 0,
            detail = "of music played",
            context = "Counted from the length of each song you stayed with",
            visual = BandVisual.Trend(s.minutesByDay),
        ),
        BandStat(
            label = "Plays",
            value = s.plays.toDouble(),
            detail = "${s.songCount} different ${if (s.songCount == 1) "song" else "songs"}",
            context = "Each bar is one of the last thirty days",
            visual = BandVisual.Bars(s.playsByDay),
        ),
        BandStat(
            label = "Artists",
            value = s.artistCount.toDouble(),
            // Plays can come with no artist named (an upload, a file): then nobody leads yet.
            detail = s.artists.firstOrNull()?.let { "${it.name.withoutEmoji()} leads" } ?: "None named yet",
            context = "Lit bars are the three you play most",
            visual = BandVisual.Distribution(bins.ifEmpty { listOf(0) }, marked = 3),
        ),
        BandStat(
            label = "Streak",
            value = s.streak.toDouble(),
            suffix = if (s.streak == 1) "day" else "days",
            detail = "best ever ${s.bestStreak}",
            context = "Played something on ${s.activeDays} ${if (s.activeDays == 1) "day" else "days"} in all",
            visual = BandVisual.Strip(s.playsByDay.map { it > 0 }),
        ),
    )
}

private fun percent(f: Float) = (f * 100f).toInt().coerceIn(0, 100)

private fun ringsOf(s: ProfileStats): List<Orbit> = listOf(
    Orbit("On repeat", percent(s.repeatShare), "${s.comebacks} ${if (s.comebacks == 1) "song" else "songs"} came back", "Songs you return to"),
    Orbit("Loyalty", percent(s.topThreeShare), s.artists.take(3).joinToString(", ") { it.name.withoutEmoji() }, "Plays by your top three"),
    Orbit("After dark", percent(s.afterDarkShare), "${s.afterDarkPlays} after 9 p.m.", "Nine at night to five"),
    Orbit("Discovery", percent(s.freshShare), "${s.freshSongs} new this month", "First listens this month"),
)

private fun hourName(h: Int): String = when (h) {
    0 -> "midnight"
    12 -> "noon"
    in 1..11 -> "$h a.m."
    else -> "${h - 12} p.m."
}

private val DayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

@Composable
private fun When(s: ProfileStats) {
    Column(Modifier.separate().fillMaxWidth().padding(top = 60.dp, start = Space.edge, end = Space.edge), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionHeading("Your hours", strapline = "When the music's on")
        Spacer(Modifier.height(24.dp))
        // Wider than it is tall: the dial fills the height, and the words at its sides stand clear of its spokes.
        Box(Modifier.fillMaxWidth(0.96f).aspectRatio(1.12f)) {
            ListeningClock(s.hours, Modifier.fillMaxSize().padding(22.dp))
            Txt("Midnight", Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.align(Alignment.TopCenter))
            Txt("Noon", Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.align(Alignment.BottomCenter))
            Txt("6 am", Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.align(Alignment.CenterEnd))
            Txt("6 pm", Type.Label, color = Ink.Grey, upper = true, modifier = Modifier.align(Alignment.CenterStart))
        }
        Spacer(Modifier.height(18.dp))
        s.peakHour?.let { Txt("Most of all around ${hourName(it)}", Type.Aside, align = TextAlign.Center) }
        Spacer(Modifier.height(26.dp))
        WeekBars(s.weekdays)
        s.peakDay?.let {
            Spacer(Modifier.height(12.dp))
            Txt("${DayNames[it]} is your day", Type.Meta, color = Ink.Grey, align = TextAlign.Center)
        }
    }
}

/** A day as a dial: midnight at the top, each hour a spoke as long as how much was played then. */
@Composable
private fun ListeningClock(hours: List<Int>, modifier: Modifier) {
    Canvas(modifier) {
        val top = max(hours.max(), 1)
        val inner = size.minDimension * 0.2f
        val reach = size.minDimension * 0.5f - inner
        val line = 7.dp.toPx()
        hours.forEachIndexed { h, n ->
            rotate(h * 15f) {
                val long = (n.toFloat() / top) * reach
                drawLine(Ink.White.copy(alpha = 0.1f), Offset(center.x, center.y - inner), Offset(center.x, center.y - inner - reach), line, StrokeCap.Round)
                if (n > 0) drawLine(if (n == top) Ink.White else Ink.White.copy(alpha = 0.62f), Offset(center.x, center.y - inner), Offset(center.x, center.y - inner - long), line, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun WeekBars(days: List<Int>) {
    val top = max(days.max(), 1)
    Row(Modifier.fillMaxWidth().height(86.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        days.forEachIndexed { i, n ->
            Column(Modifier.weight(1f).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().weight(1f)) {
                    val h = (n.toFloat() / top * size.height).coerceAtLeast(3.dp.toPx())
                    drawRoundRect(if (n == top && n > 0) Ink.White else Ink.White.copy(alpha = if (n > 0) 0.42f else 0.1f), Offset(0f, size.height - h), Size(size.width, h), CornerRadius(4.dp.toPx()))
                }
                Spacer(Modifier.height(8.dp))
                Txt(DayNames[i].take(1), Type.Label, color = Ink.Grey)
            }
        }
    }
}

/** Rows of a name and its share, a line beneath each drawn as long as the share. */
@Composable
private fun Shares(title: String, strapline: String?, rows: List<Pair<String, Float>>) {
    Column(Modifier.separate().fillMaxWidth().padding(top = 60.dp, start = Space.edge, end = Space.edge), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionHeading(title, strapline = strapline)
        Spacer(Modifier.height(24.dp))
        val top = rows.maxOf { it.second }.coerceAtLeast(0.01f)
        rows.forEach { (name, share) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(name, Type.TitleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                    Txt("${percent(share)}%", Type.Meta, color = Ink.Grey)
                }
                Spacer(Modifier.height(7.dp))
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    drawRoundRect(Ink.White.copy(alpha = 0.1f), cornerRadius = CornerRadius(size.height))
                    drawRoundRect(Ink.White, size = Size(size.width * share / top, size.height), cornerRadius = CornerRadius(size.height))
                }
            }
        }
    }
}

@Composable
private fun Types(s: ProfileStats) {
    Shares("What kind of songs", "From what they're called", s.types.take(6).map { it.label to it.share })
    Row(Modifier.separate().fillMaxWidth().padding(top = 30.dp, start = Space.edge, end = Space.edge)) {
        Trait(if (s.averageLengthMs > 0) clockOf(s.averageLengthMs) else "–", "Average song", Modifier.weight(1f))
        Trait("${percent(s.collabShare)}%", "Collaborations", Modifier.weight(1f))
        Trait("${percent(s.explicitShare)}%", "Explicit", Modifier.weight(1f))
    }
}

private fun clockOf(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@Composable
private fun Trait(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(value, Type.Display.copy(fontFeatureSettings = "lnum"), align = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Txt(label, Type.Label, color = Ink.Grey, upper = true, align = TextAlign.Center, maxLines = 1)
    }
}

/** Questions a profile answers, each with the song that answers it. */
@Composable
private fun Prompts(s: ProfileStats) {
    val actions = LocalActions.current
    Column(Modifier.separate().fillMaxWidth().padding(top = 60.dp, start = Space.edge, end = Space.edge), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionHeading("In your own words", strapline = "Touch to play")
        Spacer(Modifier.height(16.dp))
        s.prompts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
            Prompted(p) { actions.playRadio(p.track) }
        }
    }
}

@Composable
private fun Prompted(p: Prompt, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().quietClick(onPlay).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(p.question, Type.Aside, color = Ink.Grey, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            Txt(p.track.title, Type.TitleSmall, maxLines = 1)
            Txt(listOf(p.track.artistLine, p.detail).filter { it.isNotBlank() }.joinToString("  ·  "), Type.Meta, color = Ink.Grey, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        TreatedArtwork(p.track.artwork, ArtTreatment.Cover, Modifier.size(62.dp).clip(Corners.Small), sizeHintPx = 200)
    }
}

@Composable
private fun TopSongs(s: ProfileStats) {
    val actions = LocalActions.current
    val tracks: List<Track> = s.songs.take(5).map { it.track }
    Column(Modifier.separate().fillMaxWidth().padding(top = 60.dp, start = Space.edge, end = Space.edge), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionHeading("Top songs", strapline = "Played the most")
        Spacer(Modifier.height(16.dp))
        s.songs.take(5).forEachIndexed { i, song ->
            TrackRow(
                song.track,
                onClick = { actions.play(tracks, i) },
                onLongClick = { actions.openMenu(song.track) },
                trailing = { Txt("${song.plays}", Type.Meta, color = Ink.Grey, modifier = Modifier.padding(start = 12.dp)) },
            )
        }
    }
}
