package app.aether.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.edgeRefraction
import app.aether.core.designsystem.art.gentleEdgeRefraction
import app.aether.core.designsystem.component.AlbumCard
import app.aether.core.designsystem.component.ArtistCard
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.component.GlassPanel
import app.aether.core.designsystem.component.LinkTile
import app.aether.core.designsystem.component.PanelDivider
import app.aether.core.designsystem.component.PodcastCard
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.component.TrackCard
import app.aether.core.designsystem.component.TrackRow
import app.aether.core.designsystem.component.StepIndicator
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import app.aether.core.designsystem.theme.Space
import app.aether.core.model.Album
import app.aether.core.model.Artist
import app.aether.core.model.FeedSection
import app.aether.core.model.Link
import app.aether.core.model.MediaEntity
import app.aether.core.model.Playlist
import app.aether.core.model.Podcast
import app.aether.core.model.SectionStyle
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.core.designsystem.theme.separate
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.platform.LocalContext
import app.aether.ui.FolderLink
import app.aether.ui.FolderPreview
import app.aether.ui.detail.SleeveList

/** One section of any feed: its heading, then its items in the form that suits them. */
@Composable
fun SectionBlock(section: FeedSection, modifier: Modifier = Modifier, topSpace: Dp = 52.dp, playAsList: Boolean = false) {
    val actions = LocalActions.current
    Column(modifier.separate().fillMaxWidth().padding(top = topSpace)) {
        SectionHeading(
            section.title,
            strapline = section.strapline,
            onMore = section.more?.let { d -> { actions.nav.open(Route.Browse(d, section.title)) } },
        )
        Spacer(Modifier.height(18.dp))
        val tracks = section.items.filterIsInstance<Track>()
        when {
            section.style == SectionStyle.ROWS && tracks.isNotEmpty() -> QuickPicks(tracks)
            section.style == SectionStyle.LIST && tracks.isNotEmpty() -> TrackList(tracks.take(6))
            section.items.all { it is Link } -> LinkGrid(section.items.filterIsInstance<Link>())
            else -> CardRow(section.items, wide = section.wide, playAsList = playAsList)
        }
    }
}

/** Song rows, four to a pane of glass, paged sideways: Quick picks. */
@Composable
fun QuickPicks(tracks: List<Track>) {
    val actions = LocalActions.current
    val columns = tracks.chunked(4)
    val width = LocalConfiguration.current.screenWidthDp.dp - Space.edge * 2 - 24.dp
    val state = rememberLazyListState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            modifier = Modifier.fillMaxWidth().edgeRefraction(state, edgeFraction = 0.12f, liftFraction = 0.06f),
            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(columns) { column ->
                GlassPanel(Modifier.width(width), frost = column.firstOrNull { !it.artwork.isEmpty }?.artwork) {
                    column.forEachIndexed { i, t ->
                        if (i > 0) PanelDivider()
                        TrackRow(t, onClick = { actions.play(listOf(t)) }, onLongClick = { actions.openMenu(t) })
                    }
                }
            }
        }
        StepIndicator(state, Modifier.padding(top = 6.dp))
    }
}

/** Tracks, everywhere in the app, set as on a record's sleeve (see [SleeveList]). */
@Composable
fun TrackList(tracks: List<Track>, numbered: Boolean = false, playContext: List<Track> = tracks, alone: Boolean = false, artists: Boolean = true) {
    SleeveList(tracks, playContext, alone, artists)
}

/**
 * A row of cards that bends light at the screen's edges as they pass. With [playAsList], a song or video
 * in it plays with the row's others after it (an artist's music videos, one after another), as a list
 * does; otherwise it plays with its radio.
 */
@Composable
fun CardRow(items: List<MediaEntity>, wide: Boolean = false, playAsList: Boolean = false) {
    val actions = LocalActions.current
    val state = rememberLazyListState()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A row of playlists is a row of folders: its edge glass is the gentler one.
        val folders = items.isNotEmpty() && items.all { it is Playlist }
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().then(if (folders) Modifier.gentleEdgeRefraction(state) else Modifier.edgeRefraction(state)),
            contentPadding = PaddingValues(horizontal = Space.edge, vertical = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The catalogue sometimes lists a card twice; a repeated key would end the app.
            val cards = items.distinctBy { it.stableKey() }
            val songs = if (playAsList) cards.filterIsInstance<Track>() else emptyList()
            items(cards, key = { it.stableKey() }) { card ->
                val at = if (card is Track) songs.indexOf(card) else -1
                if (at >= 0 && songs.size > 1) TrackCard(card as Track, onClick = { actions.play(songs, at) }, wide = wide)
                else EntityCard(card, wide)
            }
        }
        // Beneath the cards (the row keeps room above for what it lifts), where the row is.
        StepIndicator(state, Modifier.offset(y = (-6).dp))
    }
}

/** Moods, genres, ways in: two columns of photographs, each fading to black under its name. */
@Composable
fun LinkGrid(links: List<Link>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(TileGap)) {
        links.chunked(2).forEach { LinkRow(it) }
    }
}

private val TileGap = 10.dp

/** Two tiles side by side. */
@Composable
private fun LinkRow(pair: List<Link>, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val context = LocalContext.current
    Row(modifier.separate().fillMaxWidth().padding(horizontal = Space.edge), horizontalArrangement = Arrangement.spacedBy(TileGap)) {
        pair.forEach { link ->
            LinkTile(link.title, MoodPictures.of(context, link.title), onClick = { actions.open(link) }, modifier = Modifier.weight(1f))
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

/**
 * Sections as rows of a list. A grid of tiles comes row by row, so a shelf of fifty moods and genres
 * is made as it's reached, not all at once in the frame it first appears.
 */
fun LazyListScope.sections(sections: List<FeedSection>, topSpace: Dp = 52.dp, tileLimit: Int = Int.MAX_VALUE, onAllTiles: (() -> Unit)? = null, whole: Boolean = false) {
    sections.forEachIndexed { index, section ->
        val links = section.items.filterIsInstance<Link>()
        val tracks = section.items.filterIsInstance<Track>()
        if (whole && links.isEmpty() && section.items.isNotEmpty()) {
            // A page of its own: every song, video, album, single, playlist or artist in it, two to a
            // row, down the page.
            if (section.title.isNotBlank()) item(key = "h:$index:${section.title}") {
                SectionHeading(section.title, Modifier.padding(top = topSpace, bottom = 18.dp), strapline = section.strapline)
            } else item(key = "h:$index") { Spacer(Modifier.height(18.dp)) }
            val rows = section.items.distinctBy { it.stableKey() }.chunked(2)
            items(rows.size, key = { "g:$index:$it" }) { i -> GridRow(rows[i], wide = section.wide, tracks = tracks) }
            return@forEachIndexed
        }
        if (links.isNotEmpty() && links.size == section.items.size) {
            val more = if (links.size > tileLimit) onAllTiles else null
            item(key = "h:$index:${section.title}") {
                SectionHeading(section.title, Modifier.padding(top = topSpace, bottom = 18.dp), strapline = section.strapline, onMore = more)
            }
            val rows = links.take(tileLimit).chunked(2)
            items(rows.size, key = { "l:$index:$it:${rows[it].first().title}" }) { i ->
                LinkRow(rows[i], Modifier.padding(bottom = TileGap))
            }
        } else {
            item(key = "s:$index:${section.title}") { SectionBlock(section, topSpace = topSpace) }
        }
    }
}

/**
 * Two cards of a page's grid, each half the width: a song plays among the page's songs; an album, a
 * single, a playlist or an artist opens. A playlist is a folder, at its own size, in its half.
 */
@Composable
private fun GridRow(pair: List<MediaEntity>, wide: Boolean, tracks: List<Track>) {
    val actions = LocalActions.current
    BoxWithConstraints(Modifier.separate().fillMaxWidth().padding(horizontal = Space.edge).padding(bottom = 14.dp)) {
        val each = (maxWidth - 14.dp) / 2
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            pair.forEach { e ->
                Box(Modifier.width(each), contentAlignment = Alignment.TopCenter) {
                    when (e) {
                        // A wide card already runs half again as wide: it's given its half and no more.
                        is Track -> TrackCard(e, onClick = { actions.play(tracks, tracks.indexOf(e).coerceAtLeast(0)) }, width = if (wide) each / 1.45f else each, wide = wide)
                        is Album -> AlbumCard(e, onClick = { actions.open(e) }, width = each)
                        is Podcast -> PodcastCard(e, onClick = { actions.open(e) }, width = each)
                        is Artist -> ArtistCard(e, onClick = { actions.open(e) }, width = each)
                        else -> EntityCard(e, wide)
                    }
                }
            }
        }
    }
}

/** Any item as a card: playlists are folders, artists are portraits, tracks and albums are covers. */
@Composable
fun EntityCard(entity: MediaEntity, wide: Boolean = false) {
    val actions = LocalActions.current
    when (entity) {
        is Track -> TrackCard(entity, onClick = { actions.open(entity) }, wide = wide)
        is Album -> AlbumCard(entity, onClick = { actions.open(entity) })
        is Artist -> ArtistCard(entity, onClick = { actions.open(entity) })
        is Playlist -> {
            val files = (entity.subtitle ?: "").split(',', '•', '·', '&').map { it.trim() }.filter { it.isNotEmpty() && !it.endsWith("views") }.take(3)
                .map { FolderFile(it, entity.artwork) }
            val detail = entity.trackCount?.let { "$it songs" } ?: entity.author
            // A source's playlist names its artists, not its songs: those become its peeking files.
            FolderLink(FolderPreview("folder:${entity.id}", entity.title, detail, entity.artwork, files), Route.Playlist(entity.id))
        }
        is Podcast -> PodcastCard(entity, onClick = { actions.open(entity) })
        is Link -> LinkTile(entity.title, MoodPictures.of(LocalContext.current, entity.title), onClick = { actions.open(entity) }, modifier = Modifier.width(170.dp))
    }
}

private fun MediaEntity.stableKey(): String = when (this) {
    is Track -> "t:${id}"
    is Album -> "a:${id}"
    is Artist -> "r:${id}"
    is Playlist -> "p:${id}"
    is Podcast -> "c:${id}"
    is Link -> "l:${destination.key}:${destination.params}"
}
