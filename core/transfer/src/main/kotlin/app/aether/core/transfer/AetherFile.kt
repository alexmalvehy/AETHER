package app.aether.core.transfer

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.ArtworkSize
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AETHER's own file of playlists: every playlist with each song whole (its id, names, length, cover),
 * so that another phone, or this one after a reset, brings them back exactly. Written in plain words
 * and versioned, apart from how AETHER stores things inside, so a file written today still reads
 * after AETHER's own storage changes.
 *
 * Each playlist carries a `uuid` that stays with it for good: importing the same file twice, or the
 * file of a playlist that's already here, adds only what's missing ([ImportedPlaylist.origin]).
 *
 * Checked before anything is taken: a file that isn't AETHER's, or that a newer AETHER wrote in a shape
 * this one can't read, is refused whole, never half-imported.
 */
object AetherFile {
    const val VERSION = 1
    private const val APP = "aether"
    private const val KIND = "playlists"

    /** At most this many songs in one file: a file larger than any real library is refused, not read. */
    private const val MAX_SONGS = 200_000

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }

    /** A playlist as it goes out. */
    data class Out(val uuid: String, val title: String, val description: String?, val folder: String?, val tracks: List<Track>)

    fun write(playlists: List<Out>, liked: List<Track>?, appVersion: String, exportedAt: String): String = json.encodeToString(
        FileDto.serializer(),
        FileDto(
            app = APP,
            kind = KIND,
            version = VERSION,
            appVersion = appVersion,
            exportedAt = exportedAt,
            playlists = playlists.map { p -> PlaylistDto(p.uuid, p.title, p.description, p.folder, p.tracks.map(::song)) },
            liked = liked?.map(::song),
        ),
    )

    /** Whether [text] looks like an AETHER file at all, before it's read whole. */
    fun looksLike(text: String): Boolean = text.trimStart().startsWith("{") && "\"app\"" in text.take(400) && "\"aether\"" in text.take(400)

    fun read(text: String): ImportRead {
        val file = runCatching { json.decodeFromString(FileDto.serializer(), text) }.getOrNull()
            ?: return ImportRead.Failed("This doesn't read as an AETHER file.")
        if (file.app != APP || file.kind != KIND) return ImportRead.Failed("This file is from another app.")
        if (file.version > VERSION) return ImportRead.Failed("A newer AETHER wrote this file. Update AETHER, then import it.")
        if (file.playlists.sumOf { it.songs.size } + (file.liked?.size ?: 0) > MAX_SONGS) return ImportRead.Failed("This file is larger than AETHER reads.")
        val playlists = file.playlists.mapNotNull { p ->
            val uuid = Text.clean(p.uuid, 100) ?: return@mapNotNull null
            ImportedPlaylist(
                name = Text.clean(p.title) ?: "Imported playlist",
                items = p.songs.mapNotNull(::item),
                origin = "aether:$uuid",
                description = Text.clean(p.description, 1_000),
                folder = Text.clean(p.folder),
            )
        }
        return ImportRead.Ok(ImportedLibrary(ImportFormat.AETHER, playlists, file.liked?.mapNotNull(::item).orEmpty()))
    }

    private fun song(t: Track) = SongDto(
        id = t.id.value,
        title = t.title,
        artists = t.artists.map { NamedDto(it.name, it.id?.value) },
        album = t.album?.let { NamedDto(it.title, it.id?.value) },
        durationMs = t.durationMs,
        artwork = t.artwork.sizes.map { PictureDto(it.url, it.width, it.height) },
        kind = t.kind.name,
        explicit = t.explicit,
        trackNumber = t.trackNumber,
        year = t.year,
    )

    /** A song back, whole when its id is one; its names alone otherwise (still found by matching). */
    private fun item(s: SongDto): ImportItem? {
        val title = Text.clean(s.title) ?: return null
        val artists = s.artists.mapNotNull { Text.clean(it.name) }
        val id = s.id.trim().takeIf { ID.matches(it) }?.let(::MediaId)
        val track = id?.let {
            Track(
                id = it,
                title = title,
                artists = s.artists.mapNotNull { a -> Text.clean(a.name)?.let { n -> ArtistRef(n, a.id?.takeIf(ID::matches)?.let(::MediaId)) } },
                album = s.album?.let { a -> Text.clean(a.name)?.let { n -> AlbumRef(n, a.id?.takeIf(ID::matches)?.let(::MediaId)) } },
                durationMs = s.durationMs?.takeIf { d -> d in 1..MAX_DURATION_MS },
                artwork = Artwork(s.artwork.filter { p -> p.url.startsWith("https://") || p.url.startsWith("http://") }.map { p -> ArtworkSize(p.url, p.width.coerceAtLeast(0), p.height.coerceAtLeast(0)) }),
                kind = runCatching { MediaKind.valueOf(s.kind) }.getOrDefault(MediaKind.SONG),
                explicit = s.explicit,
                trackNumber = s.trackNumber,
                year = s.year,
            )
        }
        return ImportItem(
            title = title,
            artists = artists,
            album = Text.clean(s.album?.name),
            durationMs = s.durationMs?.takeIf { it in 1..MAX_DURATION_MS },
            explicit = s.explicit,
            youtubeId = id?.takeIf { it.source == "ytm" }?.key?.let { YouTubeLinks.videoId("ytm:$it") },
            track = track,
            reference = id?.value,
        )
    }

    /** `source:key`, as AETHER's ids are, of sane length. */
    private val ID = Regex("""[a-z0-9-]{1,40}:[^\s]{1,500}""")
    private const val MAX_DURATION_MS = 24L * 3_600_000

    @Serializable
    private data class FileDto(
        val app: String = "",
        val kind: String = "",
        val version: Int = 0,
        val appVersion: String = "",
        val exportedAt: String = "",
        val playlists: List<PlaylistDto> = emptyList(),
        val liked: List<SongDto>? = null,
    )

    @Serializable
    private data class PlaylistDto(
        val uuid: String = "",
        val title: String = "",
        val description: String? = null,
        val folder: String? = null,
        val songs: List<SongDto> = emptyList(),
    )

    @Serializable
    private data class SongDto(
        val id: String = "",
        val title: String = "",
        val artists: List<NamedDto> = emptyList(),
        val album: NamedDto? = null,
        val durationMs: Long? = null,
        val artwork: List<PictureDto> = emptyList(),
        val kind: String = "SONG",
        val explicit: Boolean = false,
        val trackNumber: Int? = null,
        val year: Int? = null,
    )

    @Serializable
    private data class NamedDto(val name: String = "", val id: String? = null)

    @Serializable
    private data class PictureDto(val url: String = "", val width: Int = 0, val height: Int = 0)
}
