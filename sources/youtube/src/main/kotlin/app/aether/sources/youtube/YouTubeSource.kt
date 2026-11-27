package app.aether.sources.youtube

import app.aether.ytmusic.YtResult
import app.aether.core.model.AccountEntry
import app.aether.core.model.AccountPlaylist
import app.aether.core.model.AlbumPage
import app.aether.core.model.VideoStream
import app.aether.core.model.ArtistPage
import app.aether.core.model.Capability
import app.aether.core.model.Charts
import app.aether.core.model.Destination
import app.aether.core.model.Explore
import app.aether.core.model.Feed
import app.aether.core.model.FeedFilter
import app.aether.core.model.FeedSection
import app.aether.core.model.Lyrics
import app.aether.core.model.MediaEntity
import app.aether.core.model.MediaId
import app.aether.core.model.MoodGroup
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.Page
import app.aether.core.model.PageToken
import app.aether.core.model.PlaylistPage
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Suggestions
import app.aether.core.model.Track
import app.aether.core.model.UpNext
import app.aether.ytmusic.Chip
import app.aether.ytmusic.WatchTarget
import app.aether.ytmusic.YouTubeMusic

/** YouTube Music as a [MusicSource]: its whole catalogue, and its audio through [streams]. */
class YouTubeSource(
    private val yt: YouTubeMusic,
    private val streams: YouTubeStreamResolver,
) : MusicSource {

    override val id = SOURCE
    override val name = "YouTube Music"
    override val capabilities = setOf(
        Capability.HOME, Capability.EXPLORE, Capability.CHARTS, Capability.MOODS, Capability.SEARCH, Capability.ALBUMS,
        Capability.ARTISTS, Capability.PLAYLISTS, Capability.RADIO, Capability.LYRICS, Capability.STREAM,
    )

    override suspend fun home(): Outcome<Feed> = yt.home().toOutcome { it.toFeed() }

    override suspend fun homeMore(token: PageToken): Outcome<Feed> = yt.homeMore(token.toContinuation()).toOutcome { it.toFeed() }

    override suspend fun filter(filter: FeedFilter): Outcome<Feed> =
        yt.home(Chip(filter.title, filter.destination.toYt())).toOutcome { it.toFeed() }

    override suspend fun explore(): Outcome<Explore> = yt.explore().toOutcome { it.toExplore() }

    override suspend fun charts(countryCode: String?): Outcome<Charts> = yt.charts(countryCode).toOutcome { it.toCharts() }

    override suspend fun moods(): Outcome<List<MoodGroup>> = yt.moods().toOutcome { groups -> groups.map { it.toMoodGroup() } }

    override suspend fun browse(destination: Destination): Outcome<Feed> = when (destination.key) {
        // Explore's entries that are pages of their own kind.
        YouTubeMusic.CHARTS -> charts(null).let { r -> if (r is Outcome.Ok) Outcome.Ok(Feed(r.value.sections, title = "Charts")) else r as Outcome.Err }
        // A "See all" that is a playlist (Explore's Trending, say): its songs and videos, as one section.
        else -> if (destination.key.startsWith("VL")) {
            yt.playlist(destination.key).toOutcome { c ->
                val page = c.toPlaylistPage(destination.key.removePrefix("VL"))
                Feed(
                    sections = listOf(app.aether.core.model.FeedSection(page.playlist.title, page.tracks, wide = page.tracks.isNotEmpty() && page.tracks.all { it.kind == app.aether.core.model.MediaKind.MUSIC_VIDEO || it.kind == app.aether.core.model.MediaKind.VIDEO })),
                    title = page.playlist.title,
                )
            }
        } else {
            yt.browse(destination.toYt()).toOutcome { it.toFeed() }
        }
    }


    override suspend fun browseMore(token: PageToken): Outcome<Feed> = yt.browseMore(token.toContinuation()).toOutcome { it.toFeed() }

    /** Albums filed by their music videos are played as their songs, where the songs can be found ([SongVersions]). */
    private val versions = SongVersions(yt)

    override suspend fun album(id: MediaId): Outcome<AlbumPage> {
        val page = yt.album(id.key)
        val album = (page as? YtResult.Success)?.value ?: return page.toOutcome { it.toAlbumPage(id.key) }
        return YtResult.Success(versions.songsOf(album)).toOutcome { it.toAlbumPage(id.key) }
    }

    override suspend fun artist(id: MediaId): Outcome<ArtistPage> = yt.artist(id.key).toOutcome { it.toArtistPage(id.key) }

    override suspend fun account(): Outcome<app.aether.core.model.ListenerAccount> =
        yt.account().toOutcome { app.aether.core.model.ListenerAccount(it.name, it.handle, it.photo.toArtwork()) }

    override suspend fun accountPlaylists(): Outcome<List<app.aether.core.model.Playlist>> {
        val first = yt.library(YouTubeMusic.LIBRARY_PLAYLISTS)
        val page = (first as? app.aether.ytmusic.YtResult.Success)?.value ?: return first.toOutcome { emptyList() }
        val all = page.items.toMutableList()
        var next = page.continuation
        // A long library comes a page at a time; twenty pages is far past any real one.
        var pages = 0
        while (next != null && pages++ < 20) {
            val more = (yt.libraryMore(next) as? app.aether.ytmusic.YtResult.Success)?.value ?: break
            all += more.items
            next = more.continuation
        }
        return Outcome.Ok(all.map { it.toEntity() }.filterIsInstance<app.aether.core.model.Playlist>().distinctBy { it.id })
    }

    override suspend fun playlist(id: MediaId): Outcome<PlaylistPage> = yt.playlist(id.key).toOutcome { it.toPlaylistPage(id.key) }

    // --- The listener's own account, changed ----------------------------------------------------------

    override suspend fun accountPlaylist(id: MediaId): Outcome<AccountPlaylist> =
        yt.playlistWhole(id.key).toOutcome { c ->
            val page = c.toPlaylistPage(id.key.removePrefix("VL"))
            AccountPlaylist(page.playlist, c.tracks.zip(page.tracks) { song, track -> AccountEntry(track, song.setVideoId) }, c.editable)
        }

    override suspend fun createAccountPlaylist(title: String, description: String?, tracks: List<Track>): Outcome<MediaId> =
        yt.createPlaylist(title, description, tracks.videoIds()).toOutcome { id(it) }

    override suspend fun addToAccountPlaylist(id: MediaId, tracks: List<Track>): Outcome<Unit> {
        val ids = tracks.videoIds()
        return if (ids.isEmpty()) Outcome.Ok(Unit) else yt.addToPlaylist(id.key, ids).toOutcome { }
    }

    override suspend fun removeFromAccountPlaylist(id: MediaId, entries: List<AccountEntry>): Outcome<Unit> {
        // Without its own id there, an entry can't be named to the account: none is taken out on a guess.
        val named = entries.filter { it.track.id.source == SOURCE }.map { e -> e.track.id.key to (e.entryId ?: return Outcome.Err(SourceError.NotFound("entry"))) }
        return if (named.isEmpty()) Outcome.Ok(Unit) else yt.removeFromPlaylist(id.key, named).toOutcome { }
    }

    override suspend fun renameAccountPlaylist(id: MediaId, title: String): Outcome<Unit> = yt.renamePlaylist(id.key, title).toOutcome { }

    override suspend fun deleteAccountPlaylist(id: MediaId): Outcome<Unit> = yt.deletePlaylist(id.key).toOutcome { }

    override suspend fun accountLiked(): Outcome<List<Track>> =
        yt.playlistWhole(YouTubeMusic.LIKED_PLAYLIST).toOutcome { c -> c.tracks.map { it.toTrack() } }

    override suspend fun setAccountLiked(track: Track, liked: Boolean): Outcome<Unit> =
        if (track.id.source != SOURCE) Outcome.Err(SourceError.Unsupported) else yt.like(track.id.key, liked).toOutcome { }

    /** The songs of [this] that are YouTube Music's, each once. */
    private fun List<Track>.videoIds(): List<String> = filter { it.id.source == SOURCE }.map { it.id.key }.distinct()

    override suspend fun tracksMore(token: PageToken): Outcome<Page<Track>> =
        yt.tracksMore(token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toTrack() }, p.continuation?.toToken()) }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> =
        yt.search(query, filter?.let { app.aether.ytmusic.SearchFilter(it.label, it.key) }).toOutcome { it.toResults() }

    override suspend fun searchMore(query: String, token: PageToken): Outcome<Page<MediaEntity>> =
        yt.searchMore(query, token.toContinuation()).toOutcome { p -> Page(p.items.map { it.toEntity() }, p.continuation?.toToken()) }

    override suspend fun searchSongs(query: String, token: PageToken?): Outcome<Page<Track>> =
        if (token == null) {
            yt.searchSongs(query).toOutcome { p -> Page(p.items.mapNotNull { it.toEntity() as? Track }, p.continuation?.toToken()) }
        } else {
            yt.searchSongsMore(query, token.toContinuation()).toOutcome { p -> Page(p.items.mapNotNull { it.toEntity() as? Track }, p.continuation?.toToken()) }
        }

    override suspend fun suggestions(input: String): Outcome<Suggestions> = yt.suggestions(input).toOutcome { it.toSuggestions() }

    override suspend fun upNext(track: Track): Outcome<UpNext> {
        if (track.id.source != SOURCE) return Outcome.Err(SourceError.Unsupported)
        val ctx = track.context
        val target = if (ctx?.key != null) WatchTarget(track.id.key, ctx.key, ctx.params) else WatchTarget.radio(track.id.key)
        return yt.upNext(target).toOutcome { it.toUpNext() }
    }

    override suspend fun lyrics(destination: Destination): Outcome<Lyrics?> = yt.lyrics(destination.toYt()).toOutcome { it?.toLyrics() }

    override suspend fun related(destination: Destination): Outcome<List<FeedSection>> =
        yt.related(destination.toYt()).toOutcome { shelves -> shelves.map { it.toSection() } }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> = streams.resolve(id.key, efficient)

    override suspend fun video(id: MediaId, maxHeight: Int): Outcome<VideoStream> = streams.video(id.key, maxHeight)

    override fun invalidate(id: MediaId) = streams.forget(id.key)
}
