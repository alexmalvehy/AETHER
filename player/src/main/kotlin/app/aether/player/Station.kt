// AETHER: BitChord's station rules (shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/QueueBuilder.kt,
// github.com/kushagrasinghx/BitChord, Copyright (C) 2026 Kushagra Singh, GPL-3.0), over AETHER's tracks.
package app.aether.player

import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import java.util.Locale

/**
 * Builds what plays on after the songs the listener chose: the songs a catalogue suggests after the
 * one playing, kept from turning into what was already heard or into one artist's discography.
 *
 * Two problems this exists to solve (BitChord's words):
 *
 *  - YouTube's watch queues routinely carry the same recording twice: the official audio and the
 *    music video are separate ids with near identical titles, so de-duplicating on id alone lets a
 *    song play, then play again as its video. Songs are matched on a normalised title plus a shared
 *    artist instead.
 *  - A radio leans hard on the seed's own artist. Capping how many songs any one artist contributes
 *    keeps a station from turning into a single artist's discography.
 */
internal object Station {

    /** No more than this many songs by one artist in a single batch. */
    const val PER_ARTIST_LIMIT = 2

    /** The artist the station was seeded on gets more room, but not the run of it. */
    const val SEED_ARTIST_LIMIT = 4

    /** Fewer videos than this in a radio and a video's station plays on with its songs too. */
    const val MIN_VIDEO_STATION = 3

    /**
     * What of [candidates] is worth adding after [existing] (the queue, its last song the seed's place):
     * nothing already queued, nothing heard twice, at most [limit]. Catalogue songs over videos, as a
     * music app wants (and Automix never mixes a video); [preferVideos] for a station playing on from a
     * music video, where the radio has enough of them to be one.
     */
    fun extend(existing: List<Track>, candidates: List<Track>, limit: Int, seed: Track? = existing.lastOrNull(), preferVideos: Boolean = false): List<Track> {
        if (limit <= 0) return emptyList()
        val taken = existing.toMutableList()
        val perArtist = mutableMapOf<String, Int>()
        val seedArtists = seed?.let(::artistSet).orEmpty()
        val out = mutableListOf<Track>()

        val songsOnly = candidates.filterNot { it.isVideoKind }
        val videosOnly = candidates.filter { it.isVideoKind }
        val ordered = when {
            preferVideos && videosOnly.size >= MIN_VIDEO_STATION -> videosOnly
            preferVideos -> candidates
            else -> songsOnly.ifEmpty { candidates }
        }

        for (candidate in ordered) {
            if (out.size >= limit) break
            if (!candidate.available) continue
            if (taken.any { sameRecording(it, candidate) }) continue

            val artists = artistSet(candidate)
            val key = artists.minOrNull()
            if (key != null) {
                val cap = if (artists.any { it in seedArtists }) SEED_ARTIST_LIMIT else PER_ARTIST_LIMIT
                val played = perArtist[key] ?: 0
                if (played >= cap) continue
                perArtist[key] = played + 1
            }
            taken += candidate
            out += candidate
        }
        return out
    }

    /** Whether two songs are the same recording: ids differ between a song's audio and its video. */
    fun sameRecording(a: Track, b: Track): Boolean {
        if (a.id == b.id) return true
        val title = normalisedTitle(a.title)
        if (title.isEmpty() || title != normalisedTitle(b.title)) return false
        // One shared name is enough: the two cuts of a song are billed in whatever order the upload used.
        val left = artistSet(a)
        val right = artistSet(b)
        return left.isEmpty() || right.isEmpty() || left.any { it in right }
    }

    /**
     * `Kesariya (From "Brahmastra") | Official Video` and `Kesariya` are one recording as far as a
     * queue is concerned. Remix and cover markers are deliberately left in: those really are other songs.
     */
    fun normalisedTitle(raw: String): String = raw.lowercase(Locale.ROOT)
        .substringBefore(" | ")
        .replace(NOISE, " ")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()

    /** Everyone credited on [track], split out so billing order stops mattering. */
    fun artistSet(track: Track): Set<String> = track.artists.flatMap { artistSet(it.name) }.toSet()

    fun artistSet(raw: String): Set<String> = raw.lowercase(Locale.ROOT)
        .replace(TOPIC, " ")
        .split(",", "&", "·", "•", ";", " feat", " ft.", " ft ", " x ", " with ")
        .map { it.replace(PUNCTUATION, " ").replace(SPACES, " ").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val Track.isVideoKind: Boolean get() = kind == MediaKind.MUSIC_VIDEO || kind == MediaKind.VIDEO

    private val NOISE = Regex(
        """\((?:official|lyric|lyrics|lyrical|audio|video|visuali[sz]er|full song|hd|4k)[^)]*\)""" +
            """|\(from[^)]*\)""" +
            """|\[[^]]*]""" +
            """|\b(?:official (?:video|audio|music video)|lyrical video|full video|4k video)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic\b""", RegexOption.IGNORE_CASE)
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
}
