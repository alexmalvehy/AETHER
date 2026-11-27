package app.aether.core.model

/** How a section lays its items out. */
enum class SectionStyle {
    /** A row of cards. */
    CAROUSEL,

    /** Track rows a few deep, paged sideways ("Quick picks"). */
    ROWS,

    /** A vertical list. */
    LIST,

    /** A grid of cards or tiles. */
    GRID,
}

data class FeedSection(
    val title: String,
    val items: List<MediaEntity>,
    /** The small line above the title. */
    val strapline: String? = null,
    val style: SectionStyle = SectionStyle.CAROUSEL,
    /** Its cards are 16:9 rather than square. */
    val wide: Boolean = false,
    val more: Destination? = null,
)

/** A mood or activity filter over a feed. */
data class FeedFilter(val title: String, val destination: Destination)

/** A page of sections: Home, a filter of it, a mood, new releases, a shelf's "more". */
data class Feed(
    val sections: List<FeedSection>,
    val title: String? = null,
    val filters: List<FeedFilter> = emptyList(),
    /** The picture the page is painted over. */
    val backdrop: Artwork = Artwork.None,
    val next: PageToken? = null,
)

data class Explore(val links: List<Link>, val sections: List<FeedSection>)

data class Country(val code: String, val name: String)

data class Charts(val country: Country?, val countries: List<Country>, val sections: List<FeedSection>)

data class MoodGroup(val title: String, val moods: List<Link>)

data class AlbumPage(
    val album: Album,
    val tracks: List<Track>,
    /** "9 songs • 43 minutes". */
    val details: String? = null,
    val description: String? = null,
    val next: PageToken? = null,
)

data class PlaylistPage(
    val playlist: Playlist,
    val tracks: List<Track>,
    val details: String? = null,
    val description: String? = null,
    val next: PageToken? = null,
    /** The listener's own, in their account: it can be changed there (and kept in step with this phone). */
    val editable: Boolean = false,
)

/** A playlist in the listener's account, as changing it there needs it. */
data class AccountPlaylist(val playlist: Playlist, val entries: List<AccountEntry>, val editable: Boolean)

/** A song in an account's playlist, with the account's own id for it there (what taking it out takes). */
data class AccountEntry(val track: Track, val entryId: String?)

data class ArtistPage(
    val artist: Artist,
    val description: String? = null,
    /** "2.31M". */
    val subscribers: String? = null,
    /** "657M monthly audience". */
    val monthlyAudience: String? = null,
    /** Every section in the source's own order. */
    val sections: List<FeedSection> = emptyList(),
    val topTracks: List<Track> = emptyList(),
    val radio: PlayContext? = null,
    val shuffle: PlayContext? = null,
)

data class SearchFilter(val label: String, val key: String)

data class SearchResults(val items: List<MediaEntity>, val filters: List<SearchFilter>, val next: PageToken?)

data class Suggestions(val queries: List<String>, val items: List<MediaEntity>)

/** The tracks that follow one (its radio, its playlist), and where its lyrics and related music are. */
data class UpNext(
    val tracks: List<Track>,
    val next: PageToken? = null,
    val lyrics: Destination? = null,
    val related: Destination? = null,
)

data class LyricLine(val startMs: Long, val text: String)

data class Lyrics(val text: String, val source: String? = null, val synced: List<LyricLine>? = null)

/**
 * What an artist's page can show beyond its source's own picture. Decoration, from somewhere other
 * than the catalogue: any of it may be missing, and the page is whole without it.
 */
data class ArtistArt(
    /** A portrait. */
    val picture: Artwork,
    /** The artist's name as it's lettered for them, a transparent picture; most artists have none. */
    val logo: String? = null,
    /** The lettering's width over its height, so it can be laid out before it arrives. */
    val logoAspect: Float = 1f,
    /** A short looping film, video only and unencrypted (an HLS playlist); many artists have none. */
    val film: String? = null,
)

/** Where [ArtistArt] comes from. Never throws: having nothing to show is an answer. */
interface ArtistArtProvider {
    /** What's already known of the artist [id], without asking anyone. */
    fun cached(id: String): ArtistArt?

    /** The art for the artist [id] named [name]; [titles] are their songs and releases, the proof it's really them. */
    suspend fun find(id: String, name: String, titles: Collection<String>): ArtistArt?

    /** What kind of music the artist [name] makes, if anyone says. */
    suspend fun genreOf(name: String): String? = null
}

/** Who's signed in to a source. */
data class ListenerAccount(val name: String, val handle: String?, val photo: Artwork)

/**
 * What an album's page can show beyond its source's cover. Decoration, as [ArtistArt] is: any of it
 * may be missing, and the page is whole without it.
 */
data class AlbumArt(
    /** The cover's short looping film, square, video only and unencrypted (an HLS playlist); most albums have none. */
    val film: String? = null,
    /** Who made it, as they're lettered (see [ArtistArt.logo]): albums have no lettering of their own. */
    val logo: String? = null,
    /** The lettering's width over its height. */
    val logoAspect: Float = 1f,
)

/** Where [AlbumArt] comes from. Never throws: having nothing to show is an answer. */
interface AlbumArtProvider {
    /** What's already known of the album [id], without asking anyone. */
    fun cachedAlbum(id: String): AlbumArt?

    /** The art for the album [id] called [title] by [artist], whose own id here is [artistId]. */
    suspend fun findAlbum(id: String, title: String, artist: String, artistId: String?): AlbumArt?

    /** What's already known of the film for the song [id], without asking anyone. */
    fun cachedSongFilm(id: String): String? = null

    /** A short looping film of the song's cover (an HLS playlist, video only and unencrypted), if its album has one. */
    suspend fun findSongFilm(id: String, title: String, artist: String, album: String?): String? = null
}

/** A song's moving cover: a short, silent, looping film, played where its print stands. */
data class Canvas(
    /** The film, unencrypted and video only: an HLS playlist (Apple's) or a video file. */
    val url: String,
    val from: CanvasSource,
)

/** Who a [Canvas] came from. */
enum class CanvasSource {
    /** Apple Music's motion artwork for the song's album. */
    APPLE,

    /** The community's index of looping covers (ViMusic's, as BitChord and LastWave ask it). */
    COMMUNITY,
}

/** Where a song's [Canvas] comes from. Never throws: having none is an answer. */
interface CanvasProvider {
    /** What's already known for the song [id], without asking anyone; Apple's only when [apple]. */
    fun cached(id: String, apple: Boolean): Canvas?

    /**
     * The moving cover for the song [id], [title] by [artist] on [album]. Apple is asked only when
     * [apple] (the listener's consent to sending it the song's title and artist).
     */
    suspend fun find(id: String, title: String, artist: String, album: String?, apple: Boolean): Canvas?
}
