package app.aether.core.transfer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Any file a listener hands AETHER, read for its playlists: AETHER's own, M3U and M3U8, CSV and TSV
 * (Exportify's, Soundiiz's, TuneMyMusic's, a spreadsheet's), a list of songs in a .txt, Spotify's data
 * download as its zip or one of its files. What it is is told from what it holds, not its name alone.
 * Never throws; whatever goes wrong is said in words.
 */
object Imports {
    /** A text file larger than this is no playlist. */
    const val MAX_TEXT_BYTES = 32 * 1024 * 1024

    /** A zip larger than this is no data download of Spotify's. */
    const val MAX_ZIP_BYTES = 512L * 1024 * 1024

    /**
     * Reads [input], which the caller closes. A zip is copied to [scratch] first, to be opened by its
     * directory; [scratch] is the caller's to delete.
     */
    fun read(input: InputStream, fileName: String?, scratch: File): ImportRead = runCatching {
        val stream = BufferedInputStream(input)
        stream.mark(8)
        val head = ByteArray(4)
        // Not readNBytes: Android has it only from 13, and AETHER runs from 10.
        var got = 0
        while (got < head.size) {
            val n = stream.read(head, got, head.size - got)
            if (n < 0) break
            got += n
        }
        stream.reset()
        if (got == 4 && SpotifyData.isZip(head)) {
            if (!copyCapped(stream, scratch, MAX_ZIP_BYTES)) return ImportRead.Failed("This zip is larger than Spotify's data download.")
            return SpotifyData.readZip(scratch)
        }
        val bytes = readCapped(stream, MAX_TEXT_BYTES) ?: return ImportRead.Failed("This file is larger than any playlist.")
        if (bytes.isEmpty()) return ImportRead.Failed("This file is empty.")
        if (looksBinary(bytes)) return ImportRead.Failed("AETHER can't read this kind of file. It reads its own, M3U, CSV, text lists and Spotify's data download.")
        readText(Text.decode(bytes), fileName)
    }.getOrElse { ImportRead.Failed("This file couldn't be read.") }

    /** A file's text, told by what it holds. */
    fun readText(text: String, fileName: String?): ImportRead {
        val start = text.trimStart()
        return when {
            AetherFile.looksLike(text) -> AetherFile.read(text)
            SpotifyData.looksLike(text) -> SpotifyData.readJson(text)
            M3u.looksLike(text, fileName) -> M3u.read(text, fileName)
            start.startsWith("{") || start.startsWith("[") || start.startsWith("<") ->
                ImportRead.Failed("AETHER doesn't know this kind of file. It reads its own, M3U, CSV, text lists and Spotify's data download.")
            else -> Csv.read(text, fileName)
        }
    }

    /** A picture, an audio file or anything else that isn't text: a NUL early on, as text never has. */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.copyOf(minOf(bytes.size, 4096))
        // UTF-16 has NULs, and says so with its mark.
        if (sample.size >= 2 && ((sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) || (sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()))) return false
        return sample.any { it == 0.toByte() }
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun copyCapped(input: InputStream, to: File, max: Long): Boolean {
        var total = 0L
        to.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) return false
                out.write(buffer, 0, n)
            }
        }
        return true
    }
}
