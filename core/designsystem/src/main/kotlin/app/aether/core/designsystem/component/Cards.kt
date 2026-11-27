package app.aether.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import app.aether.core.designsystem.art.Picture
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.glass.translucent
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Album
import app.aether.core.model.Artist
import app.aether.core.model.Link
import app.aether.core.model.MediaKind
import app.aether.core.model.Playlist
import app.aether.core.model.Podcast
import app.aether.core.model.Track
import androidx.compose.foundation.layout.ColumnScope
import app.aether.core.model.Artwork

/** A tap with no ink ripple: AETHER answers touch with light where the finger is ([FingertipLight]). */
@Composable
fun Modifier.quietClick(onClick: () -> Unit): Modifier {
    val haptics = LocalHaptics.current
    return this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = FingertipLight) { haptics.select(); onClick() }
}

/**
 * A track as a row: a small picture, the title in the serif, the artist in the grotesk, and its
 * length. [index] numbers album tracks instead of a picture. [playing] lights the title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    playing: Boolean = false,
    treatment: ArtTreatment = ArtTreatment.Cover,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = LocalHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = FingertipLight,
                onLongClick = onLongClick?.let { { haptics.strong(); it() } },
                onClick = { haptics.select(); onClick() },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Txt(index.toString().padStart(2, '0'), Type.Meta, color = if (playing) Ink.White else Ink.Dim, modifier = Modifier.width(34.dp))
        } else {
            TreatedArtwork(track.artwork, treatment, Modifier.size(46.dp).clip(Corners.Small), sizeHintPx = 160)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            MarkedLine(
                track.title,
                track.explicit,
                Type.TitleSmall,
                color = if (track.available) Ink.White else Ink.Faint,
                markColor = if (!track.available) Ink.Faint else if (playing) Ink.White.copy(alpha = 0.8f) else Ink.Grey,
            )
            val second = buildList {
                if (track.kind == MediaKind.MUSIC_VIDEO || track.kind == MediaKind.VIDEO) add("Video")
                if (track.artists.isNotEmpty()) add(track.artistLine)
                track.plays?.let { add(it) }
            }.joinToString("  ·  ")
            if (second.isNotEmpty()) Txt(second, Type.Meta, color = if (playing) Ink.White.copy(alpha = 0.8f) else Ink.Grey, maxLines = 1)
        }
        if (trailing != null) trailing() else track.durationMs?.let { Txt(clock(it), Type.Meta, color = Ink.Dim, modifier = Modifier.padding(start = 12.dp)) }
    }
}

/** A square cover with its title and line beneath, centred. */
@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp, treatment: ArtTreatment = ArtTreatment.Cover) {
    CoverCard(album.title, album.subtitle ?: album.artists.joinToString { it.name }, album.artwork, onClick, modifier, width) {
        TreatedArtwork(album.artwork, treatment, it, sizeHintPx = 450)
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp) {
    CoverCard(playlist.title, playlist.subtitle ?: playlist.author, playlist.artwork, onClick, modifier, width) {
        TreatedArtwork(playlist.artwork, ArtTreatment.Cover, it, sizeHintPx = 450)
    }
}

@Composable
fun PodcastCard(podcast: Podcast, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp) {
    CoverCard(podcast.title, podcast.author, podcast.artwork, onClick, modifier, width) {
        TreatedArtwork(podcast.artwork, ArtTreatment.Cover, it, sizeHintPx = 450)
    }
}

/**
 * A card's glass: [frost] of its own picture beneath, and over it the tab bar's glass, clear (its
 * light from above and its lit rim, no tint, no lens), holding what the card holds. Every card on a
 * page is made this way. A card in a scrolling row answers the finger with light, not with the
 * bar's stretch, which would fight the scroll.
 */
@Composable
private fun GlassCard(frost: Artwork, onClick: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.clip(Corners.Large)) {
        Frost(frost, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxWidth().liquidGlass(Corners.Large, GlassWeight.Control, tint = Color.Transparent, backdrop = null).quietClick(onClick).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

/** A track as a card: square for songs, 16:9 for videos and episodes. */
@Composable
fun TrackCard(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 150.dp, wide: Boolean = false) {
    val w = if (wide) width * 1.45f else width
    GlassCard(track.artwork, onClick, modifier.width(w)) {
        TreatedArtwork(
            track.artwork,
            ArtTreatment.Cover,
            Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 1f).clip(Corners.Medium),
            sizeHintPx = 480,
        )
        Spacer(Modifier.height(10.dp))
        Txt(track.title, Type.TitleSmall, maxLines = 2, align = TextAlign.Center)
        // The mark heads the line under the title: a centred title over two lines has no one start.
        MarkedLine(track.artistLine, track.explicit, Type.Meta, color = Ink.Grey, maxLines = 1, align = TextAlign.Center)
    }
}

@Composable
private fun CoverCard(title: String, line: String?, artwork: Artwork, onClick: () -> Unit, modifier: Modifier, width: Dp, art: @Composable (Modifier) -> Unit) {
    GlassCard(artwork, onClick, modifier.width(width)) {
        art(Modifier.fillMaxWidth().aspectRatio(1f).clip(Corners.Medium))
        Spacer(Modifier.height(10.dp))
        Txt(title, Type.TitleSmall, maxLines = 2, align = TextAlign.Center)
        if (!line.isNullOrBlank()) Txt(line, Type.Meta, color = Ink.Grey, maxLines = 1, align = TextAlign.Center)
    }
}

/** An artist: a round monochrome portrait, the name beneath in the serif. */
@Composable
fun ArtistCard(artist: Artist, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 120.dp) {
    GlassCard(artist.artwork, onClick, modifier.width(width)) {
        TreatedArtwork(artist.artwork, ArtTreatment.Monochrome(contrast = 1.2f, grain = 0f), Modifier.size(width - 20.dp).clip(CircleShape), sizeHintPx = 400)
        Spacer(Modifier.height(10.dp))
        Txt(artist.name, Type.TitleSmall, maxLines = 1, align = TextAlign.Center)
        artist.subtitle?.let { Txt(it, Type.Meta, color = Ink.Dim, maxLines = 1, align = TextAlign.Center) }
    }
}

/**
 * A mood, a genre, a way in: a word in italic serif on black, behind a hairline. The service's
 * catalogue's colour isn't used at all: the room stays black and white, and only a photograph, itself
 * without colour, brings a tile anything. Square-ish, the picture at the right fading to black at the
 * left, where the name sits.
 */
@Composable
fun LinkTile(title: String, picture: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1.16f)
            .clip(Corners.Medium)
            .background(Ink.Coal)
            .quietClick(onClick),
    ) {
        if (picture != null) {
            // The picture stands at the right; on the left it's gone to black, where the name is.
            Picture(picture, TilePicturePx, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.8f))
        }
        Box(Modifier.fillMaxSize().background(TileFade))
        Txt(title, Type.TitleSmall, maxLines = 3, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(0.72f).padding(start = 12.dp, bottom = 12.dp))
    }
}

/** What a tile's picture is asked for at: the same picture everywhere, kept in memory once. */
private const val TilePicturePx = 420

private val TileFade = Brush.horizontalGradient(
    0f to Color.Black,
    0.22f to Color.Black,
    0.34f to Color.Black.copy(alpha = 0.86f),
    0.48f to Color.Black.copy(alpha = 0.6f),
    0.66f to Color.Black.copy(alpha = 0.26f),
    0.9f to Color.Transparent,
)

/** A block standing in for content that is still loading: black, with a hairline. */
@Composable
fun Skeleton(modifier: Modifier) {
    Box(modifier.translucent(Corners.Medium))
}

/** Centred: the strapline in spaced capitals, the name large in the serif, and an optional way to see more. */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier, strapline: String? = null, onMore: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.edge), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (strapline != null) {
            Strapline(strapline)
            Spacer(Modifier.height(12.dp))
        }
        Txt(title, Type.Display, align = TextAlign.Center, maxLines = 3)
        if (onMore != null) {
            Spacer(Modifier.height(12.dp))
            GlassPill("See all", trailingIcon = Glyph.Forward, onClick = onMore, small = true)
        }
    }
}

/** A message where content would be: why, and what to do. */
@Composable
fun Notice(title: String, body: String?, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(title, Type.Display, align = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Txt(body, Type.Body, color = Ink.Grey, align = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(22.dp))
            GlassPill(action, onClick = onAction)
        }
    }
}

/** Full-width centre for a loader. */
@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { GridDots() }
}

/** The room's darkness rising from below, so text over a picture always reads. */
fun Modifier.fadeToBlack(from: Float = 0.45f): Modifier = this.background(
    Brush.verticalGradient(from to Color.Transparent, 1f to Color.Black),
)

@Composable
fun FillBlack(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize().background(Ink.Black))
