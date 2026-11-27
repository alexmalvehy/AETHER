// parseTextTrack and normalize are ported from LastWave-Native
// (app/.../data/playlist/CsvPlaylistImporter.kt, GPL-3.0).
package app.aether.core.transfer

import java.text.Normalizer
import java.util.Locale

/** One line of text naming a song, written the ways people write them. */
internal object Lines {
    /**
     * "Artist - Title" (or – — | :), "Title by Artist", a numbered line ("1. ", "01) ", "[1] "), a
     * YouTube link, or just a title. The dash is read before "by" (LastWave read "by" first, which
     * made "Stand by Me - Ben E. King" a song called "Stand"). Where the dash is [conventional] (an
     * M3U's #EXTINF, whose words are "Artist - Title" by the format's own rule) the split is taken as
     * it is; anywhere else it's a guess, [ImportItem.loose], and the line's other [readings] are tried.
     */
    fun song(line: String, conventional: Boolean = false): ImportItem? {
        val trimmed = Text.clean(line, 1_000) ?: return null
        YouTubeLinks.videoId(trimmed)?.let { return ImportItem(title = "", youtubeId = it, reference = trimmed) }
        val cleaned = trimmed.replace(NUMBERING, "").trim().takeIf { it.isNotEmpty() } ?: return null
        return readings(cleaned).firstOrNull()?.let { (title, artist) ->
            ImportItem(title, credits(artist), reference = cleaned, loose = !(conventional && DASH.containsMatchIn(cleaned)) && (artist != null))
        }
    }

    /**
     * Every way to read one line as a song, likeliest first: "Artist - Title", "Title - Artist",
     * "Title by Artist", and the whole line as a title.
     */
    fun readings(line: String): List<Pair<String, String?>> {
        val cleaned = Text.clean(line.replace(NUMBERING, ""), 1_000) ?: return emptyList()
        return buildList {
            val dash = DASH.find(cleaned)
            dash?.let { m ->
                val left = Text.clean(cleaned.substring(0, m.range.first))
                val right = Text.clean(cleaned.substring(m.range.last + 1))
                if (left != null && right != null) {
                    add(right to left)
                    add(left to right)
                }
            }
            // A dash is the stronger mark: with one, a "by" is part of a name ("Stand by Me - Ben E. King").
            if (dash == null) BY.find(cleaned)?.let { m ->
                val title = Text.clean(cleaned.substring(0, m.range.first))
                val artist = Text.clean(cleaned.substring(m.range.last + 1))
                if (title != null && artist != null) add(title to artist)
            }
            add(cleaned to null)
        }.distinct()
    }

    /** An artist credit as a file gives it, or none when it's a placeholder ("Unknown artist"). */
    fun credits(artist: String?): List<String> = listOfNotNull(Text.clean(artist)?.takeUnless { normalize(it) in UNKNOWN })

    /** For comparing words: compatibility forms, lower case, quotes gone, every run of other signs one space. */
    fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(QUOTES, "")
            .replace(NOT_WORD, " ")
            .trim()

    private val NUMBERING = Regex("""^\s*(?:\[?\d+[.)\]]|\d+\s*[-–—])\s*""")
    private val DASH = Regex("""\s+[-–—|:]\s+""")
    private val BY = Regex("""(?i)\s+by\s+""")
    private val QUOTES = Regex("""['’"“”]""")
    private val NOT_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val UNKNOWN = setOf("unknown", "unknown artist", "various artists")
}
