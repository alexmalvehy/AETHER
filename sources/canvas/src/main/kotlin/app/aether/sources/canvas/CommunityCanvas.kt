// After LastWave-Native's CommunityCanvas (app/.../data/canvas/CommunityCanvas.kt) and BitChord's
// (app/.../data/canvas/CommunityCanvas.kt), GPL-3.0: the same index, held the same way. Matched by
// AETHER's own rules ([SameSong]) instead of their contains-either-way test.
package app.aether.sources.canvas

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A GET as the app makes it: the body of a 2xx, or null for anything else. Never throws. */
fun interface CanvasHttp {
    suspend fun get(url: String): String?
}

/**
 * The community's index of looping covers: `song + artist -> a short film`, one file kept by the
 * ViMusic community and asked by BitChord and LastWave too. It covers what the catalogues' motion
 * artwork doesn't (the services only have it where a label paid for some), thinly: a couple of
 * hundred songs (measured 2026-11-19).
 *
 * One file for the whole index, fetched whole and matched on the phone: nothing about what's playing
 * is ever sent. It's held for [KEEP_MS]; a fetch that fails is tried again after [RETRY_MS].
 */
class CommunityCanvas(private val http: CanvasHttp, private val now: () -> Long = System::currentTimeMillis) {

    internal data class Entry(val song: String, val artist: String, val album: String, val url: String)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var askAgainAt = 0L
    private val lock = Mutex()

    /** The film for [title] by [artist] (on [album], where both say), or null when the index has none. */
    suspend fun find(title: String, artist: String, album: String?): String? =
        index().firstOrNull { SameSong.matches(title, artist, album, it.song, it.artist, it.album) }?.url

    private suspend fun index(): List<Entry> = lock.withLock {
        val t = now()
        if (t < askAgainAt) return@withLock entries
        val parsed = http.get(INDEX)?.let(::parse).orEmpty()
        if (parsed.isNotEmpty()) entries = parsed
        askAgainAt = t + if (parsed.isNotEmpty()) KEEP_MS else RETRY_MS
        entries
    }

    internal companion object {
        const val INDEX = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
        private const val KEEP_MS = 30L * 60 * 1000
        private const val RETRY_MS = 2L * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Every entry that names a song, its artist and an https film; anything else is passed over. */
        fun parse(body: String): List<Entry> = runCatching {
            (json.parseToJsonElement(body) as? JsonObject)?.get("items")?.let { it as? JsonArray }.orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                fun text(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val url = text("url").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val song = text("song").ifEmpty { return@mapNotNull null }
                val artist = text("artist").ifEmpty { return@mapNotNull null }
                Entry(song, artist, text("album"), url)
            }
        }.getOrDefault(emptyList())
    }
}
