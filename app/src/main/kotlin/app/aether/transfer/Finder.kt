package app.aether.transfer

import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Track
import app.aether.core.transfer.ImportItem
import app.aether.core.transfer.Matcher
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * The connection went mid-import, or the service stopped answering ([unanswered]): the import stops,
 * keeping what's done, rather than finding nothing for every song.
 */
class NoConnection(val unanswered: Boolean = false) : Exception("No connection")

/**
 * Finds, for each song a file or link describes, the song that plays here, by LastWave's road
 * (CsvPlaylistImporter.matchRow, GPL-3.0) and the [Matcher]'s rules:
 *
 * 1. The song itself, from an AETHER file, when its source is here: no asking at all.
 * 2. A YouTube link the file gives, looked up and checked against the words beside it (a link is a
 *    claim: a stale one leads to another video), else found by those words.
 * 3. A file of the listener's own (Spotify's local files, an M3U's paths): the phone's music first.
 * 4. The catalogue's songs, the most precise question first, the first song that's truly it.
 *
 * Asking is patient: a refusal for asking too fast pauses every search, not just one; a dropped answer
 * is asked again, longer each time; no connection at all stops the import ([NoConnection]). What's
 * been found, or found to be missing, is remembered for the import, so a song in several playlists is
 * looked for once.
 */
class Finder(
    private val catalogue: MusicSource,
    private val phone: MusicSource?,
    /** The phone's song by its id, when it's on this phone. */
    private val onPhone: suspend (MediaId) -> Track?,
    /** Whether a source with this id is here (one of the listener's own). */
    private val sourceHere: (String) -> Boolean,
    private val online: () -> Boolean,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val known = ConcurrentHashMap<String, Known>()

    @Volatile private var resumeAt = 0L

    private class Known(val track: Track?)

    suspend fun find(item: ImportItem): Track? {
        for ((i, reading) in Matcher.readings(item).withIndex()) {
            own(reading)?.let { return it }
            val key = key(reading)
            val before = known[key]
            if (before != null) {
                // Found before: that song. Looked for before and missing: on to the next reading.
                if (before.track != null) return before.track
                continue
            }
            // A guessed reading other than the first is asked about briefly.
            val found = linked(reading) ?: phoneSong(reading) ?: catalogued(reading, if (i == 0) Int.MAX_VALUE else 2)
            known[key] = Known(found)
            if (found != null) return found
        }
        return null
    }

    /** The song itself, as an AETHER file carries it, when what plays it is here. */
    private suspend fun own(reading: ImportItem): Track? {
        val t = reading.track ?: return null
        return when (t.id.source) {
            catalogue.id -> t
            phone?.id -> onPhone(t.id)
            else -> t.takeIf { sourceHere(t.id.source) }
        }
    }

    /** The song a YouTube link leads to, if it's the song the file names beside it. */
    private suspend fun linked(reading: ImportItem): Track? {
        val id = reading.youtubeId ?: return null
        val probe = Track(MediaId.of(catalogue.id, id), reading.title.ifBlank { id }, emptyList())
        val next = patiently { catalogue.upNext(probe) } ?: return null
        val track = next.tracks.firstOrNull { it.id.key == id } ?: return null
        return track.takeIf { Matcher.confirms(reading, it) }
    }

    /** A file of the listener's own, among the phone's music, by its title (the phone matches words in titles). */
    private suspend fun phoneSong(reading: ImportItem): Track? {
        val phone = phone ?: return null
        if (!reading.local && reading.track?.id?.source != phone.id) return null
        if (reading.title.isBlank()) return null
        // The phone's answer fails soft: without leave to read the music, it's no answer.
        val songs = runCatching { (phone.search(reading.title, null) as? Outcome.Ok)?.value?.items?.filterIsInstance<Track>() }.getOrNull().orEmpty()
        return Matcher.pick(reading, songs)
    }

    /** The catalogue's songs, asked [questions] at most of [Matcher.queries]. */
    private suspend fun catalogued(reading: ImportItem, questions: Int): Track? {
        for (query in Matcher.queries(reading).take(questions)) {
            val songs = patiently { catalogue.searchSongs(query) }?.items
                ?: patiently { catalogue.search(query) }?.items?.filterIsInstance<Track>()
                ?: continue
            Matcher.pick(reading, songs)?.let { return it }
        }
        return null
    }

    /**
     * [call], asked again when its answer was lost: a wait between, doubling. Refused for asking too
     * fast, everyone waits as long as the service says. No connection, or a service that answers none
     * of [ATTEMPTS] tries, stops the import, keeping what's done: going on, every song would wait out
     * its own tries and come back missing. A request the service refuses as such (a 4xx) is just this
     * question unanswered.
     */
    private suspend fun <T> patiently(call: suspend () -> Outcome<T>): T? {
        var wait = FIRST_WAIT_MS
        for (attempt in 1..ATTEMPTS) {
            val paused = resumeAt - now()
            if (paused > 0) sleep(paused)
            when (val r = call()) {
                is Outcome.Ok -> return r.value
                is Outcome.Err -> when (val e = r.error) {
                    is SourceError.RateLimited -> resumeAt = maxOf(resumeAt, now() + (e.retryAfterMs ?: wait))
                    SourceError.Offline -> throw NoConnection()
                    SourceError.Network -> {
                        if (!online()) throw NoConnection()
                        if (attempt == ATTEMPTS) throw NoConnection(unanswered = true)
                        sleep(wait)
                    }
                    is SourceError.Server -> {
                        if (e.code in 400..499 || attempt == ATTEMPTS) return null
                        sleep(wait)
                    }
                    else -> return null
                }
            }
            wait *= 2
        }
        return null
    }

    /** One reading's question, the same however a file spaced or cased it. */
    private fun key(r: ImportItem): String =
        r.youtubeId?.let { "yt:$it" }
            ?: listOf(r.title, r.artists.joinToString(","), r.album.orEmpty(), (r.durationMs?.div(5_000)).toString(), r.explicit.toString())
                .joinToString("|") { it.trim().lowercase().replace(SPACES, " ") }

    private companion object {
        const val ATTEMPTS = 4
        const val FIRST_WAIT_MS = 800L
        val SPACES = Regex("""\s+""")
    }
}
