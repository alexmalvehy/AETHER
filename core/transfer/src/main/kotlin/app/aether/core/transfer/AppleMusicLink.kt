// Reading follows LastWave-Native's AppleMusicPlaylistImporter (app/.../data/playlist/, GPL-3.0): the
// page's own data (`serialized-server-data`) first, the `schema:music-playlist` ld+json after it, the
// scripts found however their ids are quoted. AETHER asks for the page as itself.
package app.aether.core.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI

/**
 * A public Apple Music playlist by its link (`music.apple.com/{country}/playlist/{name}/pl.{id}`), read
 * from its public page. The page's data gives each song's name, its artists one by one, the album, the
 * length and whether it's explicit; the ld+json fallback gives names and lengths.
 */
object AppleMusicLink {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The playlist a pasted link names (`pl.…`), or null. */
    fun playlistId(input: String?): String? {
        val uri = runCatching { URI(input?.trim().orEmpty()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "music.apple.com" && host != "itunes.apple.com") return null
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if ("playlist" !in segments) return null
        return segments.lastOrNull { PLAYLIST_ID.matches(it) }
    }

    fun isAppleMusic(input: String?): Boolean =
        runCatching { URI(input?.trim().orEmpty()).host?.lowercase() }.getOrNull().let { it == "music.apple.com" || it == "itunes.apple.com" }

    fun read(html: String, playlistId: String): ImportRead {
        val data = script(html, SERVER_DATA)?.let(::parse)
        var name: String? = null
        var count: Int? = null
        val rows = ArrayList<ImportItem>()
        val seen = HashSet<String>()
        if (data != null) {
            walk(data) { o ->
                val id = o.text("id") ?: return@walk
                when {
                    // The page's own kinds of thing, by their ids: its head, and a song's row.
                    id.startsWith("playlist-detail-header") && name == null -> {
                        name = Text.clean(o.text("title"))
                        count = (o["trackCount"] as? JsonPrimitive)?.intOrNull
                    }
                    id.startsWith("track-lockup") -> {
                        if (!seen.add(id)) return@walk
                        val title = Text.clean(o.text("title")) ?: return@walk
                        val links = (o["subtitleLinks"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("title")?.let(Text::clean) }
                        rows += ImportItem(
                            title = title,
                            artists = links.ifEmpty { Lines.credits(o.text("artistName")) },
                            album = (o["tertiaryLinks"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.text("title") }?.let(::release),
                            durationMs = (o["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 },
                            explicit = (o["showExplicitBadge"] as? JsonPrimitive)?.booleanOrNull,
                            reference = id,
                        )
                    }
                }
            }
        }
        if (rows.isEmpty()) {
            // The ld+json: names and lengths, no artists.
            val ld = script(html, LD_JSON)?.let(::parse) as? JsonObject
            name = name ?: Text.clean(ld?.text("name"))
            count = count ?: (ld?.get("numTracks") as? JsonPrimitive)?.intOrNull
            (ld?.get("track") as? JsonArray).orEmpty().forEach { t ->
                val o = t as? JsonObject ?: return@forEach
                val title = Text.clean(o.text("name")) ?: return@forEach
                rows += ImportItem(title, durationMs = o.text("duration")?.let(::isoDuration), reference = o.text("url"))
            }
        }
        if (rows.isEmpty()) return ImportRead.Failed("Apple Music didn't share this playlist. Is it public?")
        val playlist = ImportedPlaylist(
            name = name ?: "Apple Music playlist",
            items = rows,
            origin = "apple:$playlistId",
            partial = count != null && rows.size < count!!,
        )
        return ImportRead.Ok(ImportedLibrary(ImportFormat.APPLE_LINK, listOf(playlist)))
    }

    /** "Housewife - Single", "Ride - EP": the release's name, without what Apple adds for its kind. */
    private fun release(title: String): String? = Text.clean(title.removeSuffix(" - Single").removeSuffix(" - EP"))

    /** ISO 8601 lengths as the ld+json writes them: "PT3M28S", "PT1H2M3S". */
    internal fun isoDuration(text: String): Long? {
        val m = ISO.matchEntire(text.trim()) ?: return null
        val (h, min, s) = m.destructured
        val total = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (s.toDoubleOrNull() ?: 0.0).toLong()
        return total.takeIf { it > 0 }?.times(1000)
    }

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

    private fun script(html: String, pattern: Regex): String? = pattern.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun parse(content: String): JsonElement? = runCatching { json.parseToJsonElement(content) }.getOrNull()

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private val PLAYLIST_ID = Regex("""pl\.[A-Za-z0-9-]+""")
    private val ISO = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?""")

    /** The ids are written quoted or bare (`id=schema:music-playlist`), as Apple's pages have had them. */
    private val SERVER_DATA = Regex("""<script[^>]*\bid\s*=\s*["']?serialized-server-data["']?[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val LD_JSON = Regex("""<script[^>]*\bid\s*=\s*["']?schema:music-playlist["']?[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
}
