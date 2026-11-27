// Reading the embed page follows LastWave-Native's parseEmbedPage (app/.../data/playlist/
// SpotifyPlaylistImporter.kt, GPL-3.0): the script found however its attributes are written, the HTML
// entities undone, every object with a uri and a title visited, rows kept once by their uri, Spotify's
// non-breaking spaces cleaned. AETHER visits them in the page's order (LastWave's walk took a stack from
// its end, which turned a playlist round), asks for the page as itself, and never uses Spotify's private
// API with a listener's login.
package app.aether.core.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * A public Spotify playlist by its link, read from the page Spotify offers for embedding it
 * (`open.spotify.com/embed/playlist/{id}`). That page lists a playlist's first hundred songs; a longer
 * one comes whole in Spotify's own data download ([SpotifyData]).
 */
object SpotifyLink {
    /** The embed page lists at most this many songs. */
    const val EMBED_LIMIT = 100

    private val PLAYLIST_ID = Regex("""[A-Za-z0-9]{22}""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The playlist a pasted link, `spotify:playlist:` uri or bare id names, or null when it names none. */
    fun playlistId(input: String?): String? {
        val s = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (PLAYLIST_ID.matches(s)) return s
        if (s.startsWith("spotify:playlist:")) return s.removePrefix("spotify:playlist:").substringBefore('?').substringBefore('/').takeIf { PLAYLIST_ID.matches(it) }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "spotify.com" && !host.endsWith(".spotify.com")) return null
        // open.spotify.com/playlist/{id}, /intl-de/playlist/{id}, /embed/playlist/{id}
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val at = segments.indexOf("playlist")
        return segments.getOrNull(at + 1)?.takeIf { at >= 0 && PLAYLIST_ID.matches(it) }
    }

    /** A short link (`spotify.link/…`, `spoti.fi/…`) that names its playlist only once followed. */
    fun isShortLink(input: String?): Boolean {
        val host = runCatching { URI(input?.trim().orEmpty()).host?.lowercase() }.getOrNull() ?: return false
        return host == "spotify.link" || host.endsWith(".spotify.link") || host == "spoti.fi"
    }

    /** Whether [input] is a Spotify address at all, for saying which kind of link it is. */
    fun isSpotify(input: String?): Boolean {
        val s = input?.trim().orEmpty()
        return s.startsWith("spotify:") || isShortLink(s) || runCatching { URI(s).host?.lowercase()?.let { it == "spotify.com" || it.endsWith(".spotify.com") } }.getOrNull() == true
    }

    fun embedUrl(playlistId: String): String = "https://open.spotify.com/embed/playlist/$playlistId"

    /** The playlist on the embed page's own data (`__NEXT_DATA__`), or why there's none. */
    fun read(html: String, playlistId: String): ImportRead {
        val payload = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: return ImportRead.Failed("Spotify didn't share this playlist. Is it public?")
        val root = parse(payload) ?: parse(unescapeHtml(payload))
            ?: return ImportRead.Failed("Spotify's answer couldn't be read.")
        var title: String? = null
        var description: String? = null
        val rows = ArrayList<ImportItem>()
        val seen = HashSet<String>()
        walk(root) { o ->
            val uri = o.text("uri") ?: return@walk
            val name = o.text("title") ?: o.text("name") ?: return@walk
            when {
                uri.startsWith("spotify:playlist:") -> if (title == null) {
                    title = clean(name)
                    description = clean(o.text("description"))
                }
                uri.startsWith("spotify:track:") -> {
                    val song = clean(name) ?: return@walk
                    if (!seen.add(uri)) return@walk
                    rows += ImportItem(
                        title = song,
                        artists = artists(o.text("subtitle")),
                        durationMs = (o["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                        explicit = (o["isExplicit"] as? JsonPrimitive)?.booleanOrNull,
                        reference = uri,
                    )
                }
            }
        }
        if (rows.isEmpty()) return ImportRead.Failed("Spotify didn't list any songs for this playlist. Is it public?")
        val playlist = ImportedPlaylist(
            name = title ?: "Spotify playlist",
            items = rows,
            origin = "spotify:$playlistId",
            description = description,
            partial = rows.size >= EMBED_LIMIT,
        )
        return ImportRead.Ok(ImportedLibrary(ImportFormat.SPOTIFY_LINK, listOf(playlist)))
    }

    /**
     * A song's artists as the page's line names them: Spotify joins them with a comma and a
     * non-breaking space, so a name with a plain one in it ("Tyler, The Creator") stays whole.
     */
    internal fun artists(line: String?): List<String> {
        val s = line?.takeIf { it.isNotBlank() } ?: return emptyList()
        val joiner = ",$NBSP"
        val parts = if (joiner in s) s.split(joiner) else listOf(s)
        return parts.mapNotNull(::clean)
    }

    /** Every object in [root], in the page's own order. */
    private fun walk(root: JsonElement, visit: (JsonObject) -> Unit) {
        when (root) {
            is JsonObject -> {
                visit(root)
                root.values.forEach { walk(it, visit) }
            }
            is JsonArray -> root.forEach { walk(it, visit) }
            else -> Unit
        }
    }

    private fun parse(payload: String): JsonElement? = runCatching { json.parseToJsonElement(payload) }.getOrNull()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    /** Spotify pads its lists with non-breaking spaces. */
    private fun clean(s: String?): String? = Text.clean(s?.replace(NBSP, ' '))

    /** The page's data is HTML-escaped inside its script on some pages: the common entities undone. */
    private fun unescapeHtml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private val NBSP = Char(0x00A0)
    private val NEXT_DATA = Regex("""<script[^>]*\bid=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
}
