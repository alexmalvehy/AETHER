// After BitChord's and LastWave-Native's CanvasRepository (app/.../data/canvas/CanvasRepository.kt,
// GPL-3.0): their order, their remembering of misses, and their rule that a miss reached without the
// album is asked again once the album is known. Their Tidal and Spotify sources aren't here (both need a
// token AETHER isn't given: docs/architecture.md).
package app.aether.sources.canvas

import app.aether.core.model.Canvas
import app.aether.core.model.CanvasProvider
import app.aether.core.model.CanvasSource
import kotlinx.coroutines.CancellationException

/**
 * A song's moving cover from the first that has one: Apple Music's motion artwork (the richest, asked
 * with the listener's consent through [apple], AETHER's own lookup on Apple's public pages), then the
 * community's index ([community]). Every answer is the song's own: Apple's is matched by its lookup, the
 * index's by [SameSong].
 *
 * Answers are remembered: found ones for the session; misses for [MISS_MS], as a lookup that failed
 * looks the same as one that found nothing, and is worth asking again later.
 */
class CanvasChain(
    private val appleCached: (id: String) -> String?,
    private val apple: suspend (id: String, title: String, artist: String, album: String?) -> String?,
    private val community: CommunityCanvas,
    private val now: () -> Long = System::currentTimeMillis,
) : CanvasProvider {

    private class Answer(val canvas: Canvas?, val withAlbum: Boolean, val at: Long)

    private val memory = object : LinkedHashMap<String, Answer>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Answer>?) = size > REMEMBERED
    }

    override fun cached(id: String, apple: Boolean): Canvas? =
        synchronized(memory) { memory[key(id, apple)]?.canvas }
            ?: if (apple) appleCached(id)?.let { Canvas(it, CanvasSource.APPLE) } else null

    override suspend fun find(id: String, title: String, artist: String, album: String?, apple: Boolean): Canvas? {
        val key = key(id, apple)
        synchronized(memory) { memory[key] }?.let { kept -> if (kept.stands(album != null)) return kept.canvas }
        val found = (if (apple) quietly { this.apple(id, title, artist, album) }?.let { Canvas(it, CanvasSource.APPLE) } else null)
            ?: quietly { community.find(title, artist, album) }?.let { Canvas(it, CanvasSource.COMMUNITY) }
        synchronized(memory) { memory[key] = Answer(found, album != null, now()) }
        return found
    }

    /**
     * Whether a kept answer still answers a lookup that does (or doesn't) know the album: a cover found
     * stands; a miss stands for [MISS_MS], unless it was reached without the album and the album is known now.
     */
    private fun Answer.stands(knowsAlbum: Boolean): Boolean =
        canvas != null || ((withAlbum || !knowsAlbum) && now() - at < MISS_MS)

    /** Asked with consent and without are different questions: neither answer stands for the other. */
    private fun key(id: String, apple: Boolean) = "$id|$apple"

    /** A source that throws found nothing (none of these hosts are ours); a cancelled lookup stays cancelled. */
    private suspend fun <T> quietly(ask: suspend () -> T?): T? = try {
        ask()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val REMEMBERED = 256
        const val MISS_MS = 10L * 60 * 1000
    }
}
