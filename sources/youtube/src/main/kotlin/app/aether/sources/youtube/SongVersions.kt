// After BitChord (kushagrasinghx/BitChord, GPL-3.0): shared/.../data/YtMusicRepository.kt (resolveAudio,
// officialVersion, playlistAsAudio) and shared/.../data/sources/TrackMatcher.kt (bestOfficialAudioForVideo),
// held to PODIUM's title rules (AETHER's core:lyrics matching) instead of BitChord's own matcher.
package app.aether.sources.youtube

import app.aether.core.lyrics.matching.ArtistNames
import app.aether.core.lyrics.matching.TrackNormalizer
import app.aether.ytmusic.Collection
import app.aether.ytmusic.MediaKind
import app.aether.ytmusic.Ref
import app.aether.ytmusic.Song
import app.aether.ytmusic.YouTubeMusic
import app.aether.ytmusic.getOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * An album's songs, as songs. YouTube Music files some albums by their music videos (KR$NA's "Yours
 * Truly", 14 of its 15 tracks; "Still Here", 6 of 9; measured 2026-11-19), and played as filed such an
 * album is its videos' sound: intros, scenes and all. Each video is asked for the song it is, BitChord's
 * way: first as YouTube Music pairs them (its own Song/Video switch, the whole album at once; signed in,
 * as a guest is paired nothing), then on its Songs shelf, the album's songs together and then each song
 * alone. Only the same recording is taken: the same title and the same version of it, by a shared artist;
 * its length only ranks (a video's runs longer than its song), as BitChord's `bestOfficialAudioForVideo`
 * has it. A video whose song isn't found stays as it is: its own sound beats a guess.
 */
internal class SongVersions(private val yt: YouTubeMusic) {

    /** What was found for each video, by its id; null where every search answered and none was its song. */
    private val known = object : LinkedHashMap<String, Song?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > REMEMBERED
    }

    private sealed interface Answer {
        data class Found(val song: Song) : Answer
        data object None : Answer
        data object Unknown : Answer
    }

    suspend fun songsOf(album: Collection): Collection {
        val videos = album.tracks.filter(::isVideo).distinctBy { it.videoId }
        if (videos.isEmpty()) return album
        val found = ConcurrentHashMap<String, Song>()
        val none = ConcurrentHashMap.newKeySet<String>()
        synchronized(known) {
            for (v in videos) if (known.containsKey(v.videoId)) known[v.videoId]?.let { found[v.videoId] = it } ?: none.add(v.videoId)
        }
        val byline = { v: Song -> v.artists.ifEmpty { album.artists } }
        withTimeoutOrNull(BUDGET_MS) {
            var open = videos.filter { !found.containsKey(it.videoId) && it.videoId !in none }
            // YouTube Music's own pairs: the album's queue, at once.
            val playlist = album.playlistId
            if (open.isNotEmpty() && playlist != null && yt.signedIn) {
                yt.songVersionsIn(playlist).getOrNull()?.let { pairs -> open.forEach { v -> pairs[v.videoId]?.let { found[v.videoId] = it } } }
                open = open.filter { !found.containsKey(it.videoId) }
            }
            // The album's own songs, asked for together.
            if (open.isNotEmpty()) {
                val query = listOfNotNull(album.title, album.artists.firstOrNull()?.name).joinToString(" ")
                yt.searchSongs(query).getOrNull()?.items?.filterIsInstance<Song>()?.let { shelf ->
                    open.forEach { v -> songFor(v.title, byline(v), v.durationMs, v.explicit, shelf, album.title)?.let { found[v.videoId] = it } }
                }
                open = open.filter { !found.containsKey(it.videoId) }
            }
            // Each one left, on its own, a few at a time.
            if (open.isNotEmpty()) {
                val gate = Semaphore(AT_ONCE)
                coroutineScope {
                    open.map { v ->
                        async {
                            when (val answer = gate.withPermit { alone(v, byline(v), album.title) }) {
                                is Answer.Found -> found[v.videoId] = answer.song
                                Answer.None -> none += v.videoId
                                Answer.Unknown -> Unit
                            }
                        }
                    }.awaitAll()
                }
            }
        }
        synchronized(known) {
            found.forEach { (video, song) -> known[video] = song }
            none.forEach { known[it] = null }
        }
        // A song once only: never one already on the album, and never one song for two of its videos.
        val taken = album.tracks.filterNot(::isVideo).mapTo(HashSet()) { it.videoId }
        return album.copy(
            tracks = album.tracks.map { row ->
                val song = found[row.videoId]?.takeIf { isVideo(row) && taken.add(it.videoId) } ?: return@map row
                asRow(song, row)
            },
        )
    }

    /** One video on its own, on the Songs shelf: by its title and first artist, then by its title alone. */
    private suspend fun alone(video: Song, credit: List<Ref>, albumTitle: String): Answer {
        val title = TrackNormalizer.analyze(video.title, names(credit)).baseTitle.trim()
        if (title.isEmpty()) return Answer.None
        var answered = true
        for (query in listOfNotNull(credit.firstOrNull()?.name?.let { "$title $it" }, title).distinct()) {
            val shelf = yt.searchSongs(query).getOrNull()?.items?.filterIsInstance<Song>()
            if (shelf == null) {
                answered = false
                continue
            }
            songFor(video.title, credit, video.durationMs, video.explicit, shelf, albumTitle)?.let { return Answer.Found(it) }
        }
        return if (answered) Answer.None else Answer.Unknown
    }

    /** The song filed in its own place: the album's number for it, the album's playlist to play on in. */
    private fun asRow(song: Song, row: Song) = song.copy(
        album = row.album,
        trackNumber = row.trackNumber ?: song.trackNumber,
        playlistId = row.playlistId,
        playParams = row.playParams,
        setVideoId = null,
        explicit = song.explicit || row.explicit,
    )

    internal companion object {
        /** Videos remembered: a few albums' worth. */
        private const val REMEMBERED = 500

        /** Searches out at once for one album. */
        private const val AT_ONCE = 4

        /** The longest an album waits for its songs; what isn't found by then stays a video, and is asked again next time. */
        private const val BUDGET_MS = 4_000L

        /** Titles every album has its own of: one of these is only ever taken from the same album. */
        private val GENERIC = setOf("intro", "outro", "interlude", "skit", "prelude", "reprise", "untitled", "bonus track", "hidden track")

        fun isVideo(song: Song) = song.kind == MediaKind.MUSIC_VIDEO || song.kind == MediaKind.VIDEO

        private fun names(credit: List<Ref>) = credit.joinToString(", ") { it.name }

        private fun sharesArtist(a: String, b: String): Boolean {
            val ours = ArtistNames.comparableForms(a)
            return ours.isNotEmpty() && ArtistNames.comparableForms(b).any { it in ours }
        }

        /**
         * The song that is the video titled [title] by [credit] among [candidates]: the same recording by
         * title and version, by a shared artist. Ranked by being on [albumTitle], then by matching the
         * video's explicitness, then by the closest length; never taken for its length alone.
         */
        fun songFor(title: String, credit: List<Ref>, lengthMs: Long?, explicit: Boolean, candidates: List<Song>, albumTitle: String?): Song? {
            val byline = names(credit)
            val wanted = TrackNormalizer.analyze(title, byline)
            val identity = wanted.version.identityKey
            if (identity.isEmpty() || byline.isBlank()) return null
            val sameAlbum = { c: Song -> albumTitle != null && c.album?.name?.trim().equals(albumTitle.trim(), ignoreCase = true) }
            return candidates
                .asSequence()
                .filter { it.kind == MediaKind.SONG && it.playable }
                .filter { c ->
                    val got = TrackNormalizer.analyze(c.title, names(c.artists))
                    got.version.identityKey == identity && got.version.tags == wanted.version.tags
                }
                .filter { c -> sharesArtist(byline, names(c.artists)) }
                .filter { c -> identity !in GENERIC || sameAlbum(c) }
                .minWithOrNull(
                    compareBy<Song> { if (sameAlbum(it)) 0 else 1 }
                        .thenBy { c -> if (c.explicit == explicit) 0 else 1 }
                        .thenBy { c -> c.durationMs?.let { d -> lengthMs?.let { abs(d - it) } } ?: Long.MAX_VALUE },
                )
        }
    }
}
