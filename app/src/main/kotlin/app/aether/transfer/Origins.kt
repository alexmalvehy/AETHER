package app.aether.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Where each of the listener's playlists came from: the Spotify playlist, the Apple Music one, the file
 * by its name, the AETHER file's lasting id. An import that finds its origin here adds to that playlist
 * rather than making another, even after it's been renamed. A playlist can have several origins (made
 * from a Spotify link, later exported as an AETHER file).
 *
 * Kept in a file of its own beside the library, a line an origin (`origin<TAB>playlist id`), so the
 * library's database needs no change for it; written whole to a new file and moved into place, so a
 * write cut short leaves the last good one.
 */
class Origins(private val file: File) {
    private val lock = Mutex()
    private var map: MutableMap<String, Long>? = null

    /** The playlist [origin] made, if it's on record. */
    suspend fun owner(origin: String): Long? = locked { it[origin] }

    suspend fun remember(origin: String, playlistId: Long) = locked { m ->
        if (m[origin] != playlistId) {
            m[origin] = playlistId
            save(m)
        }
    }

    /** Every origin on record for [playlistId]. */
    suspend fun of(playlistId: Long): List<String> = locked { m -> m.filterValues { it == playlistId }.keys.toList() }

    /**
     * [playlistId]'s lasting id for AETHER files: the one it came in with, else a new one, kept. It's
     * what lets the same playlist, exported and imported again here or on another phone, be added to
     * rather than doubled.
     */
    suspend fun aetherId(playlistId: Long): String = locked { m ->
        m.entries.firstOrNull { it.value == playlistId && it.key.startsWith(AETHER) }?.key?.removePrefix(AETHER)
            ?: UUID.randomUUID().toString().also { id ->
                m[AETHER + id] = playlistId
                save(m)
            }
    }

    /** Forgets the origins of playlists that are gone, so a new playlist can take their place. */
    suspend fun prune(alive: Set<Long>) = locked { m -> if (m.values.removeAll { it !in alive }) save(m) }

    private suspend fun <T> locked(block: (MutableMap<String, Long>) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) { block(map ?: load().also { map = it }) }
    }

    private fun load(): MutableMap<String, Long> {
        val out = LinkedHashMap<String, Long>()
        runCatching {
            if (file.exists()) file.readLines().forEach { line ->
                val origin = line.substringBeforeLast('\t', "")
                val id = line.substringAfterLast('\t', "").toLongOrNull()
                if (origin.isNotEmpty() && id != null) out[origin] = id
            }
        }
        return out
    }

    private fun save(m: Map<String, Long>) {
        runCatching {
            val next = File(file.parentFile, file.name + ".new")
            next.writeText(m.entries.joinToString("") { (origin, id) -> "${origin.replace('\t', ' ').replace('\n', ' ')}\t$id\n" })
            if (!next.renameTo(file)) {
                file.delete()
                next.renameTo(file)
            }
        }
    }

    private companion object {
        const val AETHER = "aether:"
    }
}
