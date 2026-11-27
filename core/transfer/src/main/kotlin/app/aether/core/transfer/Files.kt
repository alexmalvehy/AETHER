package app.aether.core.transfer

import java.net.URLDecoder

/** Names and origins for playlists that come in files. */
internal object Files {
    /**
     * A playlist's name from its file's: no folder, no extension, `%20`s and underscores as spaces
     * (LastWave turned hyphens to spaces too; AETHER keeps them, for "Lo-Fi" and "K-Pop").
     */
    fun playlistName(fileName: String?): String = stem(fileName) ?: "Imported playlist"

    /**
     * Where a playlist that came in a file came from: its name, whatever kind of file carried it, so
     * "Chill.csv" read again, or "Chill.m3u8", adds to the playlist the first one made.
     */
    fun origin(name: String): String = "file:" + Lines.normalize(name)

    /** A path's or address's last part without its extension, decoded and with underscores as spaces. */
    fun stem(path: String?): String? {
        val last = path?.replace('\\', '/')?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/') ?: return null
        val decoded = runCatching { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last)
        val stem = decoded.substringBeforeLast('.', decoded).replace('_', ' ')
        return Text.clean(stem)
    }
}
