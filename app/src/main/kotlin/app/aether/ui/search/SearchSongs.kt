package app.aether.ui.search

import app.aether.core.model.MediaEntity
import app.aether.core.model.MediaKind
import app.aether.core.model.Track

/**
 * The songs a search lists, in order: the mixed results' own first, the catalogue's best guesses
 * (its top result's songs among them), then those of the songs searched alone. A song in both is
 * listed once, where the mixed results put it, and whole: each copy fills in what the other left out.
 * The mixed results leave out a lot (a top result's songs name no artist, its list rows give no
 * length), and the songs searched alone have it all.
 */
internal fun listedSongs(mixed: List<MediaEntity>, alone: List<Track>): List<Track> {
    val full = alone.associateBy { it.id }
    val first = mixed.filterIsInstance<Track>().filter { it.kind == MediaKind.SONG }.map { m -> full[m.id]?.let(m::filledFrom) ?: m }
    return (first + alone.filter { it.kind == MediaKind.SONG }).distinctBy { it.id }
}

/** This copy of a song with what [other], another copy of it, says and this one doesn't. */
internal fun Track.filledFrom(other: Track): Track = copy(
    // The fuller credit: "PinkPantheress" from a card, "PinkPantheress & Ice Spice" from its own row.
    artists = if (other.artists.size > artists.size) other.artists else artists,
    album = album ?: other.album,
    durationMs = durationMs ?: other.durationMs,
    artwork = if (artwork.isEmpty) other.artwork else artwork,
    explicit = explicit || other.explicit,
    year = year ?: other.year,
    plays = plays ?: other.plays,
)
