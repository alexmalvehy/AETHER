package app.aether.core.model

/** What a source call answers: a value, or why there is none. Sources never throw for the service's sake. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: SourceError) : Outcome<Nothing>
}

sealed interface SourceError {
    data object Offline : SourceError
    data object Network : SourceError
    data class Server(val code: Int?) : SourceError
    data class RateLimited(val retryAfterMs: Long?) : SourceError
    data object AuthRequired : SourceError
    data object AuthExpired : SourceError
    data class NotFound(val what: String) : SourceError

    /** Known, but not playable here (region, age, gone from every stream). */
    data class NotPlayable(val reason: String?) : SourceError

    /** This source doesn't offer this. */
    data object Unsupported : SourceError
}

inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

/** What a source can do. Screens ask before they offer. */
enum class Capability { HOME, EXPLORE, CHARTS, MOODS, SEARCH, ALBUMS, ARTISTS, PLAYLISTS, RADIO, LYRICS, STREAM }

/** The audio a source hands the player for one track. */
data class Stream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val codec: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    /** Only when the source knows it; never a guess. */
    val bitDepth: Int? = null,
    val contentLength: Long? = null,
    /** When the address stops working (epoch ms), if it says. */
    val expiresAtMs: Long? = null,
    /** Stable across address changes: the cache keys on it, never on the url. */
    val cacheKey: String,
    /** What [url] is: the audio itself, or a manifest of it in pieces. */
    val manifest: Manifest = Manifest.NONE,
    /**
     * The listener's own source this copy came from, when it isn't the song's own source (an exact
     * copy from an addon). Null for the song's own stream.
     */
    val servedBy: String? = null,
) {
    enum class Manifest { NONE, DASH, HLS }
}

/**
 * A video's pictures, without its sound: the song's own audio is what plays, and the pictures follow
 * it. For watching a music video on Now Playing.
 */
data class VideoStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val width: Int,
    val height: Int,
    val mimeType: String? = null,
    val codec: String? = null,
    val expiresAtMs: Long? = null,
)

/** Something the player may play a song from: the song's own source, or a copy from one of the listener's own. */
enum class StreamPurpose {
    /** Listening now: an exact copy from one of the listener's sources may be asked for first. */
    LISTEN,

    /** Kept on the phone: always the song's own source, as addons only stream. */
    KEEP,
}

/**
 * A music provider. Each one is isolated in its own `sources:*` module and turns its own answers
 * into `core:model` types. Every call has a default that says it isn't offered, so a source
 * implements only what it really does, and [capabilities] says which.
 */
interface MusicSource {
    /** The prefix of every id it makes (`ytm`, `local`). */
    val id: String
    val name: String
    val capabilities: Set<Capability>

    suspend fun home(): Outcome<Feed> = unsupported()
    suspend fun homeMore(token: PageToken): Outcome<Feed> = unsupported()
    suspend fun filter(filter: FeedFilter): Outcome<Feed> = unsupported()
    suspend fun explore(): Outcome<Explore> = unsupported()
    suspend fun charts(countryCode: String?): Outcome<Charts> = unsupported()
    suspend fun moods(): Outcome<List<MoodGroup>> = unsupported()
    suspend fun browse(destination: Destination): Outcome<Feed> = unsupported()
    suspend fun browseMore(token: PageToken): Outcome<Feed> = unsupported()

    /** Who's signed in here, when someone is. */
    suspend fun account(): Outcome<ListenerAccount> = unsupported()

    /** The playlists in the signed-in listener's own library on the service, every one of them. */
    suspend fun accountPlaylists(): Outcome<List<Playlist>> = unsupported()

    // The listener's own account, changed: what keeping a playlist in step with it takes.

    /** The account's playlist [id], every page of it, each song with its own id there. */
    suspend fun accountPlaylist(id: MediaId): Outcome<AccountPlaylist> = unsupported()

    /** Makes a private playlist in the account, holding those of [tracks] the source has: its id. */
    suspend fun createAccountPlaylist(title: String, description: String?, tracks: List<Track>): Outcome<MediaId> = unsupported()

    /** Adds [tracks] at the end of the account's playlist [id], none of them twice. */
    suspend fun addToAccountPlaylist(id: MediaId, tracks: List<Track>): Outcome<Unit> = unsupported()

    /** Takes [entries] (as [accountPlaylist] gave them) out of the account's playlist [id]. */
    suspend fun removeFromAccountPlaylist(id: MediaId, entries: List<AccountEntry>): Outcome<Unit> = unsupported()

    suspend fun renameAccountPlaylist(id: MediaId, title: String): Outcome<Unit> = unsupported()

    suspend fun deleteAccountPlaylist(id: MediaId): Outcome<Unit> = unsupported()

    /** Every song the account has liked, most recent first. */
    suspend fun accountLiked(): Outcome<List<Track>> = unsupported()

    suspend fun setAccountLiked(track: Track, liked: Boolean): Outcome<Unit> = unsupported()

    suspend fun album(id: MediaId): Outcome<AlbumPage> = unsupported()
    suspend fun artist(id: MediaId): Outcome<ArtistPage> = unsupported()
    suspend fun playlist(id: MediaId): Outcome<PlaylistPage> = unsupported()
    suspend fun tracksMore(token: PageToken): Outcome<Page<Track>> = unsupported()

    suspend fun search(query: String, filter: SearchFilter? = null): Outcome<SearchResults> = unsupported()
    suspend fun searchMore(query: String, token: PageToken): Outcome<Page<MediaEntity>> = unsupported()

    /**
     * Songs alone for [query], a page at a time ([token], from the page before, for the next): what a
     * search for songs gives, where the mixed results hold only a few. Search lists them first, and an
     * import finds a file's songs with it.
     */
    suspend fun searchSongs(query: String, token: PageToken? = null): Outcome<Page<Track>> = unsupported()
    suspend fun suggestions(input: String): Outcome<Suggestions> = unsupported()

    /** What follows [track]: its radio, or its playlist from where it sits. */
    suspend fun upNext(track: Track): Outcome<UpNext> = unsupported()
    suspend fun lyrics(destination: Destination): Outcome<Lyrics?> = unsupported()
    suspend fun related(destination: Destination): Outcome<List<FeedSection>> = unsupported()

    /** The audio for [id], at the best quality this source has unless [efficient]. */
    suspend fun stream(id: MediaId, efficient: Boolean = false): Outcome<Stream> = unsupported()

    /** The pictures of a video [id], at most [maxHeight] lines tall, to play in step with its audio. */
    suspend fun video(id: MediaId, maxHeight: Int): Outcome<VideoStream> = unsupported()

    /** Forget a stream the player was refused, so the next ask resolves afresh. */
    fun invalidate(id: MediaId) = Unit
}

private fun unsupported(): Outcome<Nothing> = Outcome.Err(SourceError.Unsupported)

/**
 * A source of the listener's own that can be asked for an exact copy of another source's song (an
 * addon, after BitChord's and PODIUM's custom sources): the same recording, edition and artist, never
 * a near miss. The song itself still belongs to its own source.
 */
interface CopySource {
    val id: String
    val name: String

    /** Whether it can be asked at all just now (it isn't resting after failing or being asked too often). */
    val ready: Boolean get() = true

    /** The stream of an exact copy of [track], when this source has one; null when it hasn't, or can't say. */
    suspend fun exactCopy(track: Track): Stream?
}
