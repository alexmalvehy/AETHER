package app.aether.core.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * Spotify's own copy of your data (spotify.com, Account, Privacy settings, "Download your data"):
 * every playlist in `Playlist1.json`, `Playlist2.json`…, the songs you liked in `YourLibrary.json`.
 * All of them, however long, straight from Spotify. Read from the zip as Spotify sends it, or from one
 * of its files taken out.
 *
 * The zip is opened by its directory, so only those files are ever unpacked, each to at most
 * [MAX_ENTRY_BYTES] whatever it claims: a zip made to unpack into gigabytes can't fill the phone.
 */
object SpotifyData {
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    fun readZip(file: File): ImportRead {
        val zip = runCatching { ZipFile(file) }.getOrNull() ?: return ImportRead.Failed("This zip couldn't be opened.")
        zip.use { z ->
            val wanted = runCatching {
                z.entries().asSequence().take(MAX_ENTRIES).filter { !it.isDirectory && wanted(it.name.substringAfterLast('/')) }.toList()
            }.getOrNull() ?: return ImportRead.Failed("This zip couldn't be opened.")
            if (wanted.isEmpty()) return ImportRead.Failed("This zip isn't Spotify's data download: it has no Playlist or YourLibrary file.")
            val playlists = ArrayList<ImportedPlaylist>()
            val liked = ArrayList<ImportItem>()
            // Playlist1, Playlist2 … Playlist10 in their own order, then the liked songs.
            for (entry in wanted.sortedBy { order(it.name.substringAfterLast('/')) }) {
                val bytes = runCatching { z.getInputStream(entry).use(::readCapped) }.getOrNull() ?: continue
                when (val r = readJson(Text.decode(bytes))) {
                    is ImportRead.Ok -> {
                        playlists += r.library.playlists
                        liked += r.library.liked
                    }
                    is ImportRead.Failed -> Unit
                }
            }
            if (playlists.isEmpty() && liked.isEmpty()) return ImportRead.Failed("Spotify's data download had no playlists or liked songs in it.")
            return ImportRead.Ok(ImportedLibrary(ImportFormat.SPOTIFY_DATA, playlists, liked))
        }
    }

    /** Whether [text] is one of the data download's files. */
    fun looksLike(text: String): Boolean {
        val head = text.take(4_000)
        if (!head.trimStart().startsWith("{")) return false
        val playlists = "\"playlists\"" in head && ("\"trackName\"" in text.take(40_000) || "\"items\"" in text.take(40_000))
        val library = "\"tracks\"" in head && "\"artist\"" in text.take(40_000) && "\"track\"" in text.take(40_000)
        return playlists || library
    }

    /** One of the download's files: `Playlist1.json` (playlists) or `YourLibrary.json` (liked songs). */
    fun readJson(text: String): ImportRead {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return ImportRead.Failed("This doesn't read as Spotify's data.")
        val playlists = (root["playlists"] as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val name = Text.clean(o["name"].str()) ?: return@mapNotNull null
            val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { item(it as? JsonObject) }
            if (items.isEmpty()) return@mapNotNull null
            ImportedPlaylist(name, items, origin = origin(name), description = Text.clean(o["description"].str(), 1_000))
        }
        // YourLibrary.json: the songs you liked, as artist, album and track.
        val liked = (root["tracks"] as? JsonArray).orEmpty().mapNotNull { t ->
            val o = t as? JsonObject ?: return@mapNotNull null
            val title = Text.clean(o["track"].str()) ?: return@mapNotNull null
            ImportItem(title, Lines.credits(o["artist"].str()), album = Text.clean(o["album"].str()), reference = Text.clean(o["uri"].str()))
        }
        if (playlists.isEmpty() && liked.isEmpty()) return ImportRead.Failed("Spotify's data had no playlists or liked songs in it.")
        return ImportRead.Ok(ImportedLibrary(ImportFormat.SPOTIFY_DATA, playlists, liked))
    }

    /** A playlist from the download, which names its playlists but gives them no id. */
    fun origin(name: String): String = "spotify-data:" + Lines.normalize(name)

    /** A playlist's entry: a song (`track`), or a file from your computer (`localTrack`); episodes are left out. */
    private fun item(o: JsonObject?): ImportItem? {
        o ?: return null
        (o["track"] as? JsonObject)?.let { t ->
            val title = Text.clean(t["trackName"].str()) ?: return null
            return ImportItem(title, Lines.credits(t["artistName"].str()), album = Text.clean(t["albumName"].str()), reference = Text.clean(t["trackUri"].str()))
        }
        (o["localTrack"] as? JsonObject)?.let { l -> return local(l["uri"].str()) }
        return null
    }

    /** `spotify:local:Artist:Album:Title:Seconds`, `+` for spaces and the rest percent-encoded. */
    internal fun local(uri: String?): ImportItem? {
        val parts = uri?.removePrefix("spotify:local:")?.split(':') ?: return null
        if (parts.size < 4) return null
        fun part(s: String) = Text.clean(runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s))
        val title = part(parts[2]) ?: return null
        return ImportItem(
            title,
            Lines.credits(part(parts[0])),
            album = part(parts[1]),
            durationMs = parts[3].toLongOrNull()?.takeIf { it > 0 }?.times(1000),
            reference = uri,
            local = true,
        )
    }

    private fun wanted(name: String) = PLAYLIST_FILE.matches(name) || name.equals("YourLibrary.json", ignoreCase = true)

    private fun order(name: String): Int = PLAYLIST_FILE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: if (name.startsWith("Playlist", true)) 0 else Int.MAX_VALUE

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull
    private val PLAYLIST_FILE = Regex("""Playlist(\d*)\.json""", RegexOption.IGNORE_CASE)
}
