package app.aether.sources.youtube

import app.aether.core.model.AlbumPage
import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.ArtworkSize
import app.aether.core.model.Charts
import app.aether.core.model.Destination
import app.aether.core.model.Explore
import app.aether.core.model.Feed
import app.aether.core.model.FeedFilter
import app.aether.core.model.FeedSection
import app.aether.core.model.Lyrics
import app.aether.core.model.MediaEntity
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Outcome
import app.aether.core.model.PageToken
import app.aether.core.model.PlayContext
import app.aether.core.model.PlaylistPage
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SectionStyle
import app.aether.core.model.SourceError
import app.aether.core.model.Suggestions
import app.aether.core.model.Track
import app.aether.core.model.UpNext
import app.aether.ytmusic.Album
import app.aether.ytmusic.Artist
import app.aether.ytmusic.ArtistPage
import app.aether.ytmusic.BrowsePage
import app.aether.ytmusic.ChartsPage
import app.aether.ytmusic.Collection
import app.aether.ytmusic.Continuation
import app.aether.ytmusic.Country
import app.aether.ytmusic.ExplorePage
import app.aether.ytmusic.HomePage
import app.aether.ytmusic.Item
import app.aether.ytmusic.Link
import app.aether.ytmusic.MoodGroup
import app.aether.ytmusic.Playlist
import app.aether.ytmusic.Podcast
import app.aether.ytmusic.Ref
import app.aether.ytmusic.Shelf
import app.aether.ytmusic.ShelfStyle
import app.aether.ytmusic.Song
import app.aether.ytmusic.Thumbnail
import app.aether.ytmusic.WatchTarget
import app.aether.ytmusic.YtError
import app.aether.ytmusic.YtResult
import app.aether.ytmusic.urlFor
import app.aether.ytmusic.MediaKind as YtKind

/*
 * YouTube Music's answers as AETHER's model. Ids are `ytm:` plus the service's own key: a video id
 * for tracks, a browse id for albums (MPRE…), artists (UC…) and shows, a playlist id (no VL) for
 * playlists. Only this module reads those keys.
 */

internal const val SOURCE = "ytm"

internal fun id(key: String) = MediaId.of(SOURCE, key)

internal fun <T, R> YtResult<T>.toOutcome(transform: (T) -> R): Outcome<R> = when (this) {
    is YtResult.Success -> Outcome.Ok(transform(value))
    is YtResult.Failure -> Outcome.Err(error.toSourceError())
}

internal fun YtError.toSourceError(): SourceError = when (this) {
    YtError.Offline -> SourceError.Offline
    YtError.Network -> SourceError.Network
    is YtError.Server -> SourceError.Server(code)
    is YtError.RateLimited -> SourceError.RateLimited(retryAfterMs)
    YtError.AuthRequired -> SourceError.AuthRequired
    YtError.AuthExpired -> SourceError.AuthExpired
    is YtError.NotFound -> SourceError.NotFound(what)
}

// Continuations travel as page tokens: "b:" when the token goes in the body, "q:" in the query.
internal fun Continuation.toToken() = PageToken((if (inBody) "b:" else "q:") + token)

internal fun PageToken.toContinuation() = Continuation(value.substring(2), inBody = value.startsWith("b:"))

internal fun Destination.toYt() = app.aether.ytmusic.Destination(key, params)

internal fun app.aether.ytmusic.Destination.toDestination() = Destination(SOURCE, browseId, params)

/**
 * The service's images, plus the sizes its image hosts make on request (album and artist art), so
 * screens can ask for exactly what they draw.
 */
internal fun List<Thumbnail>.toArtwork(): Artwork {
    if (isEmpty()) return Artwork.None
    val given = map { ArtworkSize(it.url, it.width, it.height) }
    val largest = last()
    val host = largest.url.substringAfter("://").substringBefore('/')
    val resizable = host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com")
    if (!resizable) return Artwork(given)
    val made = RESIZED.mapNotNull { px ->
        val url = urlFor(px) ?: return@mapNotNull null
        val height = if (largest.width > 0 && largest.height > 0) px * largest.height / largest.width else px
        ArtworkSize(url, px, height)
    }
    return Artwork((given + made).distinctBy { it.width }.sortedBy { it.width })
}

private val RESIZED = listOf(226, 544, 1080, 1440)

internal fun Ref.toArtistRef() = ArtistRef(name, browseId?.let { id(it) })

internal fun Song.toTrack(): Track = Track(
    id = id(videoId),
    title = title,
    artists = artists.map { it.toArtistRef() },
    album = album?.let { AlbumRef(it.name, it.browseId?.let { b -> id(b) }) },
    durationMs = durationMs,
    artwork = thumbnails.toArtwork(),
    kind = when (kind) {
        YtKind.SONG -> MediaKind.SONG
        YtKind.MUSIC_VIDEO -> MediaKind.MUSIC_VIDEO
        YtKind.VIDEO -> MediaKind.VIDEO
        YtKind.EPISODE -> MediaKind.EPISODE
    },
    explicit = explicit,
    trackNumber = trackNumber,
    year = year,
    plays = plays,
    available = playable,
    context = if (playlistId != null || playParams != null) PlayContext(playlistId, playParams) else null,
    subtitle = subtitle,
)

internal fun Album.toAlbum() = app.aether.core.model.Album(
    id = id(browseId),
    title = title,
    artists = artists.map { it.toArtistRef() },
    year = year,
    artwork = thumbnails.toArtwork(),
    type = type,
    explicit = explicit,
    subtitle = subtitle,
)

internal fun Artist.toArtist() = app.aether.core.model.Artist(id(browseId), title, thumbnails.toArtwork(), subtitle)

internal fun Playlist.toPlaylist() =
    app.aether.core.model.Playlist(id(playlistId), title, author, trackCount, thumbnails.toArtwork(), subtitle)

internal fun Podcast.toPodcast() = app.aether.core.model.Podcast(id(browseId), title, author, thumbnails.toArtwork(), subtitle)

internal fun Link.toLink() = app.aether.core.model.Link(title, destination.toDestination(), color, icon)

internal fun Item.toEntity(): MediaEntity = when (this) {
    is Song -> toTrack()
    is Album -> toAlbum()
    is Artist -> toArtist()
    is Playlist -> toPlaylist()
    is Podcast -> toPodcast()
    is Link -> toLink()
}

internal fun Shelf.toSection() = FeedSection(
    title = title,
    items = items.map { it.toEntity() },
    strapline = strapline,
    style = when (style) {
        ShelfStyle.CAROUSEL -> SectionStyle.CAROUSEL
        ShelfStyle.ROWS -> SectionStyle.ROWS
        ShelfStyle.LIST -> SectionStyle.LIST
        ShelfStyle.GRID -> SectionStyle.GRID
    },
    wide = wide,
    more = more?.toDestination(),
)

internal fun HomePage.toFeed() = Feed(
    sections = shelves.map { it.toSection() },
    filters = chips.map { FeedFilter(it.title, it.destination.toDestination()) },
    backdrop = background.toArtwork(),
    next = continuation?.toToken(),
)

internal fun BrowsePage.toFeed() = Feed(sections = shelves.map { it.toSection() }, title = title, next = continuation?.toToken())

internal fun ExplorePage.toExplore() = Explore(links.map { it.toLink() }, shelves.map { it.toSection() })

internal fun Country.toCountry() = app.aether.core.model.Country(code, name)

internal fun ChartsPage.toCharts() = Charts(country?.toCountry(), countries.map { it.toCountry() }, shelves.map { it.toSection() })

internal fun MoodGroup.toMoodGroup() = app.aether.core.model.MoodGroup(title, moods.map { it.toLink() })

internal fun Collection.toAlbumPage(browseId: String) = AlbumPage(
    album = app.aether.core.model.Album(
        id = id(browseId),
        title = title,
        artists = artists.map { it.toArtistRef() },
        year = year,
        artwork = thumbnails.toArtwork(),
        subtitle = subtitle,
    ),
    // Album rows rarely carry their own art or album: they're the album's.
    tracks = tracks.map { song ->
        val t = song.toTrack()
        t.copy(
            artwork = if (t.artwork.isEmpty) thumbnails.toArtwork() else t.artwork,
            album = t.album ?: AlbumRef(title, id(browseId)),
            artists = t.artists.ifEmpty { artists.map { it.toArtistRef() } },
            context = t.context ?: playlistId?.let { PlayContext(it, null) },
        )
    },
    details = secondSubtitle,
    description = description,
    next = continuation?.toToken(),
)

internal fun Collection.toPlaylistPage(playlistId: String) = PlaylistPage(
    playlist = app.aether.core.model.Playlist(
        id = id(playlistId),
        title = title,
        author = artists.firstOrNull()?.name,
        trackCount = trackCount,
        artwork = thumbnails.toArtwork(),
        subtitle = subtitle,
    ),
    tracks = tracks.map { song -> song.toTrack().let { it.copy(context = it.context ?: PlayContext(playlistId, null)) } },
    details = secondSubtitle,
    description = description,
    next = continuation?.toToken(),
    editable = editable,
)

internal fun ArtistPage.toArtistPage(browseId: String) = app.aether.core.model.ArtistPage(
    artist = app.aether.core.model.Artist(id(browseId), name, thumbnails.toArtwork(), monthlyAudience),
    description = description,
    subscribers = subscribers,
    monthlyAudience = monthlyAudience,
    sections = shelves.map { it.toSection() },
    topTracks = songs.map { it.toTrack() },
    radio = radio?.toContext(),
    shuffle = shuffle?.toContext(),
)

internal fun WatchTarget.toContext() = PlayContext(playlistId, params)

internal fun app.aether.ytmusic.SearchPage.toResults() = SearchResults(
    items = items.map { it.toEntity() },
    filters = filters.map { SearchFilter(it.label, it.params) },
    next = continuation?.toToken(),
)

internal fun app.aether.ytmusic.Suggestions.toSuggestions() = Suggestions(queries, items.map { it.toEntity() })

internal fun app.aether.ytmusic.UpNext.toUpNext() = UpNext(
    tracks = songs.map { it.toTrack() },
    next = continuation?.toToken(),
    lyrics = lyrics?.toDestination(),
    related = related?.toDestination(),
)

internal fun app.aether.ytmusic.Lyrics.toLyrics() = Lyrics(text, source)
