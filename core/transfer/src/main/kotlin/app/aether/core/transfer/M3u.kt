// Reading follows LastWave-Native's parseM3u (app/.../data/playlist/CsvPlaylistImporter.kt, GPL-3.0):
// `artist="…"` and `title="…"` attributes first, else the words after the comma, else the file's name.
package app.aether.core.transfer

import app.aether.core.model.Track
import java.net.URLEncoder

/**
 * M3U and M3U8, the playlist every music player reads: `#EXTINF:<seconds>,<Artist> - <Title>`, then
 * where the song is. A YouTube Music link names the very song (a claim, checked when it's read); a
 * path or any other address is found by the words beside it, or failing those its file's name.
 */
object M3u {
    /** At most this many songs read from one file. */
    private const val MAX_ITEMS = 50_000

    /**
     * [name]'s songs as an M3U8. A YouTube song links to itself; a song with no address another player
     * could open (one on this phone, one from a source of yours) links to a YouTube Music search for it,
     * so the line is still an entry, and AETHER finds the song by its words when the file comes back.
     */
    fun write(name: String, tracks: List<Track>): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(oneLine(name)).append('\n')
        for (t in tracks) {
            val seconds = t.durationMs?.let { it / 1000 } ?: -1
            val words = listOfNotNull(oneLine(t.artistLine).takeIf { it.isNotEmpty() }, oneLine(t.title)).joinToString(" - ")
            append("#EXTINF:").append(seconds).append(',').append(words).append('\n')
            t.album?.title?.let { append("#EXTALB:").append(oneLine(it)).append('\n') }
            val id = if (t.id.source == "ytm") YouTubeLinks.videoId(t.id.value) else null
            append(id?.let(YouTubeLinks::link) ?: searchLink(t)).append('\n')
        }
    }

    fun looksLike(text: String, fileName: String?): Boolean =
        text.trimStart().startsWith("#EXTM3U", ignoreCase = true) ||
            fileName?.lowercase()?.let { it.endsWith(".m3u") || it.endsWith(".m3u8") } == true

    fun read(text: String, fileName: String?): ImportRead {
        var name: String? = null
        var pending: ImportItem? = null
        var album: String? = null
        val items = ArrayList<ImportItem>()
        for (raw in Text.lines(text)) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = Text.clean(line.substringAfter(':'))
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    // Two #EXTINF lines in a row: the first had no address, and is still a song.
                    pending?.let { items += it }
                    pending = extinf(line.substringAfter(':'))
                }
                line.startsWith("#EXTALB:", ignoreCase = true) -> album = Text.clean(line.substringAfter(':'))
                line.startsWith("#") -> Unit
                else -> {
                    item(line, pending, album)?.let { items += it }
                    pending = null
                    album = null
                }
            }
            if (items.size >= MAX_ITEMS) break
        }
        pending?.let { items += it }
        if (items.isEmpty()) return ImportRead.Failed("This playlist file has no songs in it.")
        val title = name ?: Files.playlistName(fileName)
        return ImportRead.Ok(ImportedLibrary(ImportFormat.M3U, listOf(ImportedPlaylist(title, items, origin = Files.origin(title)))))
    }

    /** `215,Artist - Title`, attributes before the comma (`-1 artist="A" title="T",…`) taken first. */
    private fun extinf(body: String): ImportItem? {
        val comma = firstCommaOutsideQuotes(body)
        val head = if (comma < 0) body else body.substring(0, comma)
        val words = if (comma < 0) "" else body.substring(comma + 1)
        val seconds = head.trim().substringBefore(' ').toLongOrNull()?.takeIf { it > 0 }
        val artist = ATTR_ARTIST.find(head)?.groupValues?.get(1)
        val title = ATTR_TITLE.find(head)?.groupValues?.get(1)
        val song = if (artist != null && title != null) ImportItem(Text.clean(title) ?: return null, Lines.credits(artist)) else Lines.song(words, conventional = true)
        return song?.copy(durationMs = seconds?.times(1000), reference = null)
    }

    private fun item(location: String, info: ImportItem?, album: String?): ImportItem? {
        val videoId = YouTubeLinks.videoId(location)
        val base = info
            ?: if (videoId != null) ImportItem(title = "", youtubeId = videoId)
            else Files.stem(location)?.let(Lines::song)?.copy(local = true)
            ?: return null
        val local = videoId == null && !location.startsWith("http://", ignoreCase = true) && !location.startsWith("https://", ignoreCase = true)
        return base.copy(
            album = base.album ?: album,
            youtubeId = videoId ?: base.youtubeId,
            // A guessed reading keeps the words it was read from, for reading them the other ways.
            reference = if (base.loose) base.reference else Text.clean(location, 1_000),
            local = base.local || local,
        )
    }

    private fun firstCommaOutsideQuotes(s: String): Int {
        var quoted = false
        for ((i, c) in s.withIndex()) {
            if (c == '"') quoted = !quoted
            if (c == ',' && !quoted) return i
        }
        return -1
    }

    private fun searchLink(t: Track): String =
        "https://music.youtube.com/search?q=" + URLEncoder.encode("${t.title} ${t.artistLine}".trim(), "UTF-8")

    private fun oneLine(s: String) = Text.clean(s) ?: ""

    private val ATTR_ARTIST = Regex("""artist="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val ATTR_TITLE = Regex("""title="([^"]+)"""", RegexOption.IGNORE_CASE)
}
