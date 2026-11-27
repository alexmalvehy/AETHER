package app.aether.ytmusic

/*
 * What YouTube Music's pages contain, as the app sees them. Ids are the service's own: a video id
 * for anything playable, a browse id ("MPRE…" album, "UC…" artist, "VL…" playlist) for pages.
 * Text (titles, subtitles, shelf names) comes in the language the client asks in, so screens show
 * it as it is and never branch on it.
 */

/** How a playable item sounds and looks: an audio track, a music video, another video, or a podcast episode. */
enum class MediaKind { SONG, MUSIC_VIDEO, VIDEO, EPISODE }

/** One size of an image. Lists of these are sorted smallest first; see [urlFor]. */
data class Thumbnail(val url: String, val width: Int, val height: Int)

/** A name, with the browse id of its page when it links somewhere. */
data class Ref(val name: String, val browseId: String? = null)

/** Where a tile, chip or "More" goes: a page id and the page's own parameters. */
data class Destination(val browseId: String, val params: String? = null)

/** What starts playing: a song, a playlist (from one song in it), with the service's own parameters (radio, shuffle). */
data class WatchTarget(val videoId: String? = null, val playlistId: String? = null, val params: String? = null) {
    companion object {
        /** A song's radio: the song, then music like it, as "Start radio" plays it. */
        fun radio(videoId: String) = WatchTarget(videoId, "RDAMVM$videoId", "wAEB")
    }
}

/** How to get more of a list. Opaque to the app: hand it back to the call that gave it. */
data class Continuation(val token: String, val inBody: Boolean)

/** A page of a list that continues. */
data class Page<T>(val items: List<T>, val continuation: Continuation?)

/** Anything a shelf, grid or list holds. [subtitle] is the detail line exactly as the service writes it. */
sealed interface Item {
    val title: String
    val subtitle: String?
    val thumbnails: List<Thumbnail>
}

data class Song(
    val videoId: String,
    override val title: String,
    val artists: List<Ref>,
    val album: Ref? = null,
    val durationMs: Long? = null,
    override val thumbnails: List<Thumbnail> = emptyList(),
    val explicit: Boolean = false,
    val kind: MediaKind = MediaKind.SONG,
    override val subtitle: String? = null,
    /** "1B plays", "13M views", when the page says. */
    val plays: String? = null,
    /** The playlist this row plays within (an album's, a chart's, its radio), when the page says. */
    val playlistId: String? = null,
    /** The page's own parameters for playing it (a radio's), with [playlistId]. */
    val playParams: String? = null,
    /** The entry's id within a playlist (needed to edit it there). */
    val setVideoId: String? = null,
    /** False when the page greys it out (not available here). */
    val playable: Boolean = true,
    val trackNumber: Int? = null,
    val year: Int? = null,
) : Item {
    /** What tapping it plays, as the page says: the song within its playlist or radio, else its own radio. */
    val watchTarget: WatchTarget
        get() = if (playlistId != null) WatchTarget(videoId, playlistId, playParams) else WatchTarget.radio(videoId)
}

data class Album(
    val browseId: String,
    override val title: String,
    val artists: List<Ref>,
    val year: Int? = null,
    override val thumbnails: List<Thumbnail> = emptyList(),
    val explicit: Boolean = false,
    /** "Album", "Single", "EP": as the service labels it (in English pages). */
    val type: String? = null,
    override val subtitle: String? = null,
) : Item

data class Artist(
    val browseId: String,
    override val title: String,
    override val thumbnails: List<Thumbnail> = emptyList(),
    /** "2.31M subscribers", "557M monthly audience": as the service writes it. */
    override val subtitle: String? = null,
) : Item

data class Playlist(
    /** Without the "VL" browse prefix. */
    val playlistId: String,
    override val title: String,
    val author: String? = null,
    val trackCount: Int? = null,
    override val thumbnails: List<Thumbnail> = emptyList(),
    override val subtitle: String? = null,
) : Item

/** A podcast show; its episodes are [Song]s of [MediaKind.EPISODE]. */
data class Podcast(
    val browseId: String,
    override val title: String,
    val author: String? = null,
    override val thumbnails: List<Thumbnail> = emptyList(),
    override val subtitle: String? = null,
) : Item

/** A tile that opens a page: a mood or genre (with its colour), or one of Explore's entries (with its icon). */
data class Link(
    override val title: String,
    val destination: Destination,
    /** The tile's stripe colour as ARGB, for moods and genres. */
    val color: Long? = null,
    /** The service's icon name ("TRENDING_UP", "MUSIC_NEW_RELEASE"…), for Explore's entries. */
    val icon: String? = null,
) : Item {
    override val subtitle: String? get() = null
    override val thumbnails: List<Thumbnail> get() = emptyList()
}

/** How a shelf lays out its items, as the service does. */
enum class ShelfStyle {
    /** A row of cards: albums, playlists, artists, videos. */
    CAROUSEL,

    /** Song rows a few deep, paged sideways ("Quick picks"). */
    ROWS,

    /** A vertical list of rows (an artist's top songs). */
    LIST,

    /** A grid of cards or tiles. */
    GRID,
}

data class Shelf(
    val title: String,
    val items: List<Item>,
    /** The small line above the title ("DANCE YOUR STRESS AWAY"). */
    val strapline: String? = null,
    val style: ShelfStyle = ShelfStyle.CAROUSEL,
    /** Its cards are 16:9 (videos, episodes) rather than square. */
    val wide: Boolean = false,
    /** Where "More" goes, when the shelf has one. */
    val more: Destination? = null,
)

/** A mood or activity filter on Home ("Relax", "Workout"). */
data class Chip(val title: String, val destination: Destination)

data class HomePage(
    val shelves: List<Shelf>,
    /** Home's filters; on its first page only. */
    val chips: List<Chip> = emptyList(),
    /** The image Home's first page is painted over, smallest first. */
    val background: List<Thumbnail> = emptyList(),
    val continuation: Continuation? = null,
)

/** Explore: its entries (New releases, Charts, Moods & genres, Podcasts) and its shelves. */
data class ExplorePage(val links: List<Link>, val shelves: List<Shelf>)

/** A charts country. "ZZ" is Global. */
data class Country(val code: String, val name: String)

data class ChartsPage(
    /** The country these charts are for. */
    val country: Country?,
    /** Every country the charts offer, in the service's order. */
    val countries: List<Country>,
    val shelves: List<Shelf>,
)

data class MoodGroup(val title: String, val moods: List<Link>)

/** Any other page of shelves: a mood or genre, new releases, podcasts, a shelf's "More". */
data class BrowsePage(val title: String?, val shelves: List<Shelf>, val continuation: Continuation? = null)

/** An album's or a playlist's page. */
data class Collection(
    val title: String,
    /** "Album • 2026", "Chart • 2026". */
    val subtitle: String?,
    /** "9 songs • 43 minutes". */
    val secondSubtitle: String?,
    val description: String?,
    val artists: List<Ref>,
    val year: Int?,
    val thumbnails: List<Thumbnail>,
    val tracks: List<Song>,
    /** The playlist that plays this collection in order (an album's own playlist id). */
    val playlistId: String?,
    val trackCount: Int?,
    val continuation: Continuation?,
    val isAlbum: Boolean,
    /** The signed-in listener's own playlist: its page offers to edit it. */
    val editable: Boolean = false,
)

data class ArtistPage(
    val name: String,
    val description: String?,
    /** "2.31M". */
    val subscribers: String?,
    /** "657M monthly audience". */
    val monthlyAudience: String?,
    val thumbnails: List<Thumbnail>,
    /** Every shelf in the page's own order (Top songs, Albums, Singles & EPs, Videos, Fans might also like…). */
    val shelves: List<Shelf>,
    val songs: List<Song>,
    /** A playlist holding all their songs, when the page links one. */
    val songsPlaylistId: String?,
    val albums: List<Album>,
    val singles: List<Album>,
    val videos: List<Song>,
    val playlists: List<Playlist>,
    val related: List<Artist>,
    val radio: WatchTarget?,
    val shuffle: WatchTarget?,
)

data class SearchFilter(val label: String, val params: String)

data class SearchPage(
    val items: List<Item>,
    /** The filters the answer offers (Songs, Albums, Artists, Community playlists, Episodes…). */
    val filters: List<SearchFilter>,
    val continuation: Continuation?,
    val noResults: Boolean,
)

/** What the search box suggests as the listener types: queries, and items to open directly. */
data class Suggestions(val queries: List<String>, val items: List<Item>)

/** The queue that follows a song, and where its lyrics and related music are. */
data class UpNext(
    val songs: List<Song>,
    val continuation: Continuation?,
    val lyrics: Destination?,
    val related: Destination?,
)

/** Plain lyrics, with the credit the service shows under them. */
data class Lyrics(val text: String, val source: String?)

/** The signed-in account. */
data class Account(val name: String, val handle: String?, val photo: List<Thumbnail>)

/** A song in the account's history, under the service's own label for when ("Today"). */
data class HistoryItem(val song: Song, val period: String?)
