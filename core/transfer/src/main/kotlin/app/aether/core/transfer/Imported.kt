package app.aether.core.transfer

import app.aether.core.model.Track

/**
 * One song as a file or a service describes it, before it's found here. Everything is what the
 * file happened to say, used only to find the right song; [title] is empty only when a link is all
 * there is.
 */
data class ImportItem(
    val title: String,
    /** The artists as credited, one credit per entry (a credit may itself name several). */
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
    val isrc: String? = null,
    /** The YouTube video the file names (a YouTube Music link), when it names one: a claim, checked. */
    val youtubeId: String? = null,
    /** The whole song as AETHER knew it, from an AETHER file: used as itself when it plays here. */
    val track: Track? = null,
    /** What the file called it (a path, a `spotify:track:` uri, the line it was on). */
    val reference: String? = null,
    /** Read from one line of text whose split is a guess ("Title by Artist"): the whole line is tried too. */
    val loose: Boolean = false,
    /** A file of the listener's own (Spotify's local files, an M3U's path): the phone's music is looked through first. */
    val local: Boolean = false,
) {
    /** "Artist – Title", for the list of songs an import couldn't find. */
    val label: String
        get() = listOfNotNull(artists.joinToString(", ").takeIf { it.isNotBlank() }, title.ifBlank { reference }).joinToString(" – ")
}

/**
 * A playlist read from a file or a link. [origin] says where it came from, the same way every time
 * that playlist is read again: an import that finds a playlist of the same origin here adds only the
 * songs it doesn't have, and never makes a second copy.
 */
data class ImportedPlaylist(
    val name: String,
    val items: List<ImportItem>,
    val origin: String,
    val description: String? = null,
    /** The folder it sat in, in an AETHER file. */
    val folder: String? = null,
    /** The source gave only part of it (a Spotify link's first 100 songs, say). */
    val partial: Boolean = false,
)

/** What a file held: its playlists, the songs liked in it if it had them, and what kind of file it was. */
data class ImportedLibrary(
    val format: ImportFormat,
    val playlists: List<ImportedPlaylist>,
    val liked: List<ImportItem> = emptyList(),
)

enum class ImportFormat(val label: String) {
    AETHER("an AETHER file"),
    M3U("an M3U playlist"),
    CSV("a CSV file"),
    TEXT("a list of songs"),
    SPOTIFY_DATA("Spotify's data download"),
    SPOTIFY_LINK("a Spotify link"),
    APPLE_LINK("an Apple Music link"),
}

/** What reading a file or a page came to: its playlists, or why there are none, in words for the listener. Never thrown. */
sealed interface ImportRead {
    data class Ok(val library: ImportedLibrary) : ImportRead
    data class Failed(val reason: String) : ImportRead
}
