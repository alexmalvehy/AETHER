// Reading is ported from LastWave-Native (app/.../data/playlist/CsvPlaylistImporter.kt: parseTracks,
// parseRecords, repairCollapsedRows, detectDelimiter and its header vocabularies; GPL-3.0). AETHER adds
// the columns a Spotify export carries beside the names (length, explicit, ISRC) and keeps a row whose
// title starts with "#" ("#icanteven"): only the lines above the table are comments.
package app.aether.core.transfer

import app.aether.core.model.Track

/**
 * A table of songs (CSV, TSV, semicolons or bars, whatever wrote it: Exportify, Soundiiz, TuneMyMusic,
 * a spreadsheet, AETHER), or a list of them one a line (.txt). Read by its header when it has one, in
 * any of several languages; by what its columns hold when it hasn't.
 */
object Csv {
    private const val MAX_ROWS = 50_000

    /** The byte-order mark, written by code so no invisible character sits in this file. */
    private val BOM = Char(0xFEFF)

    /** [tracks] as AETHER writes them: title, artists, album, length, and a YouTube Music link where there is one. */
    fun write(tracks: List<Track>): String = buildString {
        // The mark tells spreadsheets the file is UTF-8, so accented names open as themselves.
        append(BOM)
        append(row(listOf("Title", "Artists", "Album", "Duration", "YouTube Music")))
        for (t in tracks) {
            val id = if (t.id.source == "ytm") YouTubeLinks.videoId(t.id.value) else null
            append(row(listOf(t.title, t.artistLine, t.album?.title.orEmpty(), t.durationMs?.let(::clock).orEmpty(), id?.let(YouTubeLinks::link).orEmpty())))
        }
    }

    fun read(text: String, fileName: String?): ImportRead {
        val items = items(text.removePrefix(BOM.toString()), fileName)
        if (items.isEmpty()) return ImportRead.Failed("AETHER found no songs in this file. It reads songs' titles and artists, or YouTube Music links.")
        val name = Files.playlistName(fileName)
        val format = if (fileName?.endsWith(".txt", ignoreCase = true) == true) ImportFormat.TEXT else ImportFormat.CSV
        return ImportRead.Ok(ImportedLibrary(format, listOf(ImportedPlaylist(name, items, origin = Files.origin(name)))))
    }

    internal fun items(text: String, fileName: String?): List<ImportItem> {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        if (lines.isEmpty()) return emptyList()
        // A .txt with no table in it: one song a line.
        if (fileName?.endsWith(".txt", ignoreCase = true) == true && lines.none { '\t' in it } &&
            !lines.first().split(',', ';').map(Lines::normalize).all { it in TITLE_HEADERS || it in ARTIST_HEADERS || it in URL_HEADERS }
        ) {
            return lines.filterNot(::isComment).take(MAX_ROWS).mapNotNull(Lines::song)
        }
        val table = withoutPreamble(text)
        val delimiter = detectDelimiter(table)
        val records = repairCollapsedRows(parseRecords(table, delimiter), delimiter).filter { row -> row.isNotEmpty() && row.any(String::isNotBlank) }
        val first = records.firstOrNull() ?: return emptyList()
        val headers = first.map(Lines::normalize)
        val dataRows = records.drop(1)

        fun isColMostlyNumeric(col: Int): Boolean {
            if (dataRows.isEmpty()) return false
            val nonBlank = dataRows.mapNotNull { it.getOrNull(col)?.trim() }.filter { it.isNotEmpty() }
            if (nonBlank.isEmpty()) return false
            return nonBlank.count { it.all(Char::isDigit) }.toDouble() / nonBlank.size >= 0.7
        }
        fun isIndexCol(col: Int): Boolean {
            val h = headers.getOrNull(col).orEmpty()
            return h in INDEX_HEADERS ||
                (h == "track" && headers.any { it in PRIMARY_TITLE_HEADERS || it in SECONDARY_TITLE_HEADERS }) ||
                isColMostlyNumeric(col)
        }

        val titleIndex = headers.indexOfFirst { it in PRIMARY_TITLE_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it in SECONDARY_TITLE_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it == "track" }.takeIf { it >= 0 && !isIndexCol(it) }
            ?: -1
        val artistIndex = headers.indexOfFirst { it in PRIMARY_ARTIST_HEADERS }.takeIf { it >= 0 }
            ?: headers.indexOfFirst { it in SECONDARY_ARTIST_HEADERS }.takeIf { it >= 0 }
            ?: -1
        val albumIndex = headers.indexOfFirst { it in ALBUM_HEADERS }
        val urlIndex = headers.indexOfFirst { it in URL_HEADERS }
        // Only a first row of several columns, none of them a bare number, is a header: in a file of one
        // column it can't be told from a song.
        val hasHeader = first.size >= 2 &&
            !first.any { it.trim().all(Char::isDigit) && it.trim().isNotEmpty() } &&
            (titleIndex >= 0 || artistIndex >= 0 || albumIndex >= 0 || urlIndex >= 0)
        if (first.size == 1 && !hasHeader) return records.take(MAX_ROWS).mapNotNull { Lines.song(it.first()) }

        val titleColumn = when {
            hasHeader && titleIndex >= 0 -> titleIndex
            hasHeader -> (0 until first.size).firstOrNull { it != artistIndex && it != albumIndex && it != urlIndex && !isIndexCol(it) } ?: 0
            else -> (0 until first.size).firstOrNull { !isIndexCol(it) } ?: 0
        }
        val artistColumn = when {
            hasHeader && artistIndex >= 0 -> artistIndex
            hasHeader -> -1
            else -> (0 until first.size).firstOrNull { it != titleColumn && !isColMostlyNumeric(it) } ?: 1
        }
        // What a Spotify export says beside the names (AETHER's).
        val msIndex = if (hasHeader) headers.indexOfFirst { it in DURATION_MS_HEADERS } else -1
        val lengthIndex = if (hasHeader) headers.indexOfFirst { it in DURATION_HEADERS } else -1
        val explicitIndex = if (hasHeader) headers.indexOf("explicit") else -1
        val isrcIndex = if (hasHeader) headers.indexOf("isrc") else -1

        return records.drop(if (hasHeader) 1 else 0).take(MAX_ROWS).mapNotNull { row ->
            if (hasHeader && row.map(Lines::normalize) == headers) return@mapNotNull null
            fun cell(i: Int) = if (i >= 0) Text.clean(row.getOrNull(i), 1_000) else null
            val titleCell = row.getOrNull(titleColumn).orEmpty().trim()
            val rawArtist = if (artistColumn >= 0) row.getOrNull(artistColumn).orEmpty().trim() else ""
            val linked = if (urlIndex >= 0) row.getOrNull(urlIndex)?.trim()?.let(::youtubeId) else null
            val videoId = linked ?: row.firstNotNullOfOrNull { c ->
                val t = c.trim()
                if (t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true)) youtubeId(t) else null
            }
            val title = titleCell.takeUnless { videoId != null && youtubeId(it) == videoId }
            if (title.isNullOrBlank() && videoId == null) return@mapNotNull null
            var finalTitle = Text.clean(title, 1_000).orEmpty()
            var artists = Lines.credits(rawArtist)
            var loose = false
            var reference = row.joinToString(" | ").take(300)
            // A title cell that's "Artist - Title" in a table with no artist column.
            if (artists.isEmpty() && finalTitle.isNotBlank() && videoId == null) {
                Lines.song(finalTitle)?.takeIf { it.title.isNotBlank() && it.artists.isNotEmpty() }?.let { parsed ->
                    finalTitle = parsed.title
                    artists = parsed.artists
                    loose = parsed.loose
                    reference = parsed.reference ?: reference
                }
            }
            ImportItem(
                title = finalTitle,
                artists = artists,
                album = cell(albumIndex),
                durationMs = cell(msIndex)?.toLongOrNull()?.takeIf { it > 0 } ?: cell(lengthIndex)?.let(::parseDuration),
                explicit = cell(explicitIndex)?.lowercase()?.let { it == "true" || it == "yes" || it == "1" },
                isrc = cell(isrcIndex)?.uppercase()?.takeIf { ISRC.matches(it) },
                youtubeId = videoId,
                reference = reference,
                loose = loose,
            )
        }
    }

    /** Whether [text] reads as a table of songs, for telling it from other files. */
    fun looksLike(text: String): Boolean {
        val first = withoutPreamble(text.removePrefix(BOM.toString())).substringBefore('\n')
        val cells = first.split(',', ';', '\t', '|').map(Lines::normalize)
        return cells.size >= 2 && cells.any { it in TITLE_HEADERS || it in ARTIST_HEADERS || it in URL_HEADERS }
    }

    private fun isComment(line: String) = line.startsWith("//") || line.startsWith("# ") || line == "#" || line.startsWith("#EXT", ignoreCase = true)

    /** The table without the comments and Excel's "sep=" line that may stand above it. */
    private fun withoutPreamble(text: String): String {
        var at = 0
        while (at < text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
            val line = text.substring(at, end).trim()
            if (line.isNotEmpty() && !isComment(line) && !line.startsWith("sep=", ignoreCase = true)) break
            at = end
        }
        return text.substring(at)
    }

    /**
     * Splits rows that a stray unclosed quote ran together. An unclosed quote swallows every line after
     * it into one field, so a file of 300 songs can come back as one row. A field that truly runs over
     * several lines (a quoted title with a line break) goes on in fragments that aren't rows; an unclosed
     * quote goes on in lines that each have as many fields as a row. Only those are split.
     */
    private fun repairCollapsedRows(records: List<List<String>>, delimiter: Char): List<List<String>> {
        val modal = records.groupingBy { it.size }.eachCount().maxByOrNull { it.value }?.key ?: return records
        if (modal < 2) return records
        val repaired = ArrayList<List<String>>(records.size)
        for (record in records) {
            val collapsed = record.indexOfFirst { '\n' in it }
            if (collapsed < 0) {
                repaired += record
                continue
            }
            val lines = record[collapsed].split('\n')
            val swallowed = lines.drop(1)
            if (swallowed.isEmpty() || !swallowed.all { cellsOf(it, delimiter).size == modal }) {
                repaired += record
                continue
            }
            repaired += record.take(collapsed) + cellsOf(lines.first(), delimiter)
            swallowed.forEach { repaired += cellsOf(it, delimiter) }
        }
        return repaired
    }

    private fun cellsOf(line: String, delimiter: Char): List<String> = parseRecords(line, delimiter).firstOrNull() ?: listOf(line.trim())

    /**
     * The separator that splits the file into rows of the same width. Counting separators isn't enough:
     * a comma inside a tab-separated artist field ties with the tab, and the tie once went to the comma,
     * shifting every column and importing a different song for every row.
     */
    private fun detectDelimiter(text: String): Char {
        var best = DELIMITERS.first()
        var bestScore = Int.MIN_VALUE
        for (candidate in DELIMITERS) {
            val rows = parseRecords(text, candidate, limit = SAMPLE_ROWS)
            if (rows.isEmpty()) continue
            val modal = rows.groupingBy { it.size }.eachCount().filterKeys { it >= 2 }.maxByOrNull { it.value } ?: continue
            val score = modal.value * CONSISTENCY_WEIGHT + rows.sumOf { (it.size - 1).coerceAtLeast(0) }
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /** RFC 4180 rows: quoted fields may hold the separator, doubled quotes and line breaks. */
    internal fun parseRecords(text: String, delimiter: Char, limit: Int = Int.MAX_VALUE): List<List<String>> {
        val records = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        fun finishField() {
            row += field.toString().trim()
            field.setLength(0)
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotBlank)) records += row.toList()
            row.clear()
        }
        while (index < text.length && records.size < limit && records.size <= MAX_ROWS) {
            val c = text[index]
            when {
                c == '"' && quoted && text.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                c == '"' && (quoted || field.isBlank()) -> quoted = !quoted
                c == delimiter && !quoted -> finishField()
                (c == '\n' || c == '\r') && !quoted -> {
                    finishRow()
                    if (c == '\r' && text.getOrNull(index + 1) == '\n') index++
                }
                else -> field.append(c)
            }
            index++
        }
        // A file cut short or edited by hand can leave a quote open: the end of the text closes it, so
        // every row before it still imports.
        if ((quoted || field.isNotEmpty() || row.isNotEmpty()) && records.size < limit) finishRow()
        return records
    }

    private fun youtubeId(value: String): String? = YouTubeLinks.videoId(value) ?: value.trim().takeIf { VIDEO_ID.matches(it) }

    private fun row(cells: List<String>): String = cells.joinToString(",") { quote(it) } + "\r\n"

    private fun quote(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** "3:45", "1:02:03", or a number of seconds (milliseconds, when it's too long to be seconds). */
    internal fun parseDuration(text: String): Long? {
        val t = text.trim()
        if (':' in t) {
            val parts = t.split(':').map { it.trim().toLongOrNull() ?: return null }
            if (parts.size > 3) return null
            return parts.fold(0L) { acc, n -> acc * 60 + n }.takeIf { it > 0 }?.times(1000)
        }
        val n = t.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return if (n > 36_000) n.toLong() else (n * 1000).toLong()
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    private val DELIMITERS = listOf(',', ';', '\t', '|')
    private const val SAMPLE_ROWS = 8

    /** Outweighs the raw count of separators, so a split that's the same every row always wins. */
    private const val CONSISTENCY_WEIGHT = 1000

    private val INDEX_HEADERS = setOf(
        "index", "#", "no", "no.", "number", "num", "track number", "track no",
        "track num", "track count", "track index", "track id", "position", "pos", "order",
        "disc", "disc number", "disc no", "disc num", "disc count", "side",
        "duration", "duration ms", "duration_ms", "time", "length", "bpm", "year", "date",
        "genre", "genres", "isrc", "key", "popularity", "bitrate", "added at", "added by",
    )
    private val PRIMARY_TITLE_HEADERS = setOf(
        "track name", "trackname", "song title", "songtitle", "track title", "tracktitle",
        "song name", "songname", "title", "titles", "video title", "item name", "audio title",
        "titre", "titres", "titulo", "titulos", "cancion", "canciones", "lied", "lieder", "titel",
    )
    private val SECONDARY_TITLE_HEADERS = setOf("song", "songs", "name", "music", "item", "headline")
    private val TITLE_HEADERS = PRIMARY_TITLE_HEADERS + SECONDARY_TITLE_HEADERS + setOf("track", "tracks")
    private val PRIMARY_ARTIST_HEADERS = setOf(
        "artist name s", "artist names", "artist name", "artistname", "track artist",
        "track artists", "lead artist", "primary artist", "main artist", "channel title",
        "channel name", "track artist name", "artiste", "artistes", "artista", "artistas",
        "interpret", "interpreten", "kuenstler", "künstler",
    )
    private val SECONDARY_ARTIST_HEADERS = setOf(
        "artist s", "artist", "artists", "channel", "uploader", "author", "creator",
        "performer", "performers", "singer", "singers", "band", "by",
    )
    private val ARTIST_HEADERS = PRIMARY_ARTIST_HEADERS + SECONDARY_ARTIST_HEADERS
    private val ALBUM_HEADERS = setOf(
        "album name", "albumname", "album", "albums", "release", "collection", "record",
        "album title", "album name s", "record title", "album_name", "album_title",
    )
    private val URL_HEADERS = setOf(
        "url", "uri", "track url", "track uri", "youtube url", "video id", "videoid",
        "link", "track link", "spotify uri", "spotify url", "youtube link", "video url",
        "spotify id", "spotify track id", "id", "yt url", "youtube", "soundcloud url", "youtube music",
    )
    private val DURATION_MS_HEADERS = setOf("duration ms", "track duration ms", "length ms")
    private val DURATION_HEADERS = setOf("duration", "length", "time", "track duration")
}
