// After LastWave-Native's import matching (app/.../data/playlist/CsvPlaylistImporter.kt: matchRow,
// linkMatchesRow, queryVariants, verifiedSearch; GPL-3.0), held to PODIUM's rules for what's the same
// recording (sources/api matching, GPL-3.0; AETHER's ExactCopy keeps them too). Where LastWave's looser
// pass would take a studio "Song" for a "Song (Live)" it couldn't find, this takes nothing: the song is
// listed as not found rather than played wrong.
package app.aether.core.transfer

import app.aether.core.lyrics.matching.ArtistNames
import app.aether.core.lyrics.matching.Explicitness
import app.aether.core.lyrics.matching.TrackNormalizer
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import kotlin.math.abs

/**
 * Which song that plays here is the song a file or service describes. The same song is the same
 * words once packaging ("Remastered", "Official Audio", "feat. …") is set aside, the same version of
 * it (live, remix, acoustic, sped up: both ways, and by the same hands), by a shared artist, of the
 * same length give or take [LENGTH_TOLERANCE_MS] where both lengths are known, and never a clean edition
 * for an explicit one where the titles say so. A song named without an artist must carry its title
 * exactly. Nothing is taken for want of a better: no match, and the song is listed as not found.
 */
object Matcher {
    /** Lengths further apart than this are two recordings, where both are known. */
    const val LENGTH_TOLERANCE_MS = 12_000L

    /** A song named by its title alone must be this close in length, where it's known. */
    const val TITLE_ONLY_TOLERANCE_MS = 4_000L

    private const val MAX_QUERIES = 5

    /**
     * What to search for, most precise first (LastWave's order): the title and the first artist, the
     * title and every artist, the title without its packaging and the first artist, the artists and the
     * title, the title, the title without its packaging.
     */
    fun queries(item: ImportItem): List<String> {
        val title = item.title.trim()
        if (title.isEmpty()) return emptyList()
        val artist = item.artists.joinToString(", ").trim()
        val primary = item.artists.firstOrNull()?.let { ArtistNames.split(it).firstOrNull() } ?: artist
        val base = TrackNormalizer.analyze(title, artist).baseTitle.trim().ifEmpty { title }
        return buildList {
            if (artist.isNotEmpty()) {
                add("$title $primary")
                if (artist != primary) add("$title $artist")
                if (base != title) add("$base $primary")
                add("$artist $title")
            }
            add(title)
            if (base != title) add(base)
        }.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_QUERIES)
    }

    /**
     * The ways to read [item]: as it was read, then, when that was a guess from one line of text
     * ("Stand by Me - Ben E. King" read as by "Stand by Me"), the line's other readings, the whole
     * line as a title last.
     */
    fun readings(item: ImportItem): List<ImportItem> {
        if (!item.loose) return listOf(item)
        val line = item.reference ?: return listOf(item.copy(loose = false))
        val others = Lines.readings(line).map { (title, artist) -> item.copy(title = title, artists = Lines.credits(artist), loose = false) }
        return (listOf(item.copy(loose = false)) + others).distinctBy { it.title to it.artists }
    }

    /**
     * Whether [track], the song a link in the file leads to, is the song the file names beside it. A
     * link is a claim, not proof: links go stale, and a stale one leads to some other video.
     */
    fun confirms(item: ImportItem, track: Track): Boolean {
        if (item.title.isBlank()) return true
        val titleOk = sameTitle(item.title, track.title) || sameSong(item, track)
        if (item.artists.isEmpty()) return titleOk
        return titleOk && shareArtist(item, track)
    }

    /** The song among [candidates] that is [item], the best of several, or null. */
    fun pick(item: ImportItem, candidates: List<Track>): Track? =
        candidates.withIndex()
            .filter { (_, c) -> accepts(item, c) }
            .sortedWith(
                compareBy<IndexedValue<Track>> { (_, c) -> if (sameTitle(item.title, c.title)) 0 else 1 }
                    .thenBy { (_, c) -> if (c.kind == MediaKind.SONG) 0 else 1 }
                    .thenBy { (_, c) -> if (sameAlbum(item, c)) 0 else 1 }
                    .thenBy { (_, c) -> if (item.explicit != null && item.explicit != c.explicit) 1 else 0 }
                    .thenBy { (_, c) -> lengthGap(item, c) ?: Long.MAX_VALUE / 2 }
                    .thenBy { it.index },
            )
            .firstOrNull()?.value

    /** Whether [candidate] is [item]: the rules above. */
    fun accepts(item: ImportItem, candidate: Track): Boolean {
        if (!candidate.available) return false
        if (item.title.isBlank()) return false
        if (candidate.kind == MediaKind.EPISODE) return false
        if (!sameSong(item, candidate)) return false
        val gap = lengthGap(item, candidate)
        if (item.artists.isEmpty()) {
            // Known by its title alone: the title exactly, the length close.
            if (!sameTitle(item.title, candidate.title)) return false
            return gap == null || gap <= TITLE_ONLY_TOLERANCE_MS
        }
        if (!shareArtist(item, candidate)) return false
        return gap == null || gap <= LENGTH_TOLERANCE_MS
    }

    /** The same words and the same version of them, explicit and clean kept apart where the titles say. */
    private fun sameSong(item: ImportItem, candidate: Track): Boolean {
        val want = TrackNormalizer.analyze(item.title, item.artists.joinToString(", "))
        val have = TrackNormalizer.analyze(candidate.title, candidate.artistLine)
        val a = want.version
        val b = have.version
        if (a.identityKey.isEmpty() || a.identityKey != b.identityKey) return false
        if (a.tags != b.tags) return false
        if (a.tags.isNotEmpty() && a.versionDetail != b.versionDetail) return false
        val wantHint = want.explicitnessHint ?: Explicitness.UNKNOWN
        val haveHint = have.explicitnessHint ?: Explicitness.UNKNOWN
        return !wantHint.conflictsWith(haveHint)
    }

    /** One name in common, the artists credited and those featured in the title, on both sides. */
    private fun shareArtist(item: ImportItem, candidate: Track): Boolean {
        val want = names(item.artists, TrackNormalizer.analyze(item.title).featuredArtists)
        val have = names(candidate.artists.map { channel(it.name) } + channel(candidate.artistLine), TrackNormalizer.analyze(candidate.title).featuredArtists)
        return want.isNotEmpty() && have.isNotEmpty() && (want intersect have).isNotEmpty()
    }

    private fun names(credits: List<String>, featured: List<String>): Set<String> =
        (credits + featured).filter { it.isNotBlank() }.flatMap { ArtistNames.comparableForms(it) }.toSet()

    /** A channel's name as the artist's ("Artist - Topic", "ArtistVEVO"). */
    private fun channel(name: String): String = name.removeSuffix(" - Topic").let { if (it.endsWith("VEVO") && it.length > 4) it.removeSuffix("VEVO") else it }

    /** The same title, word for word once case, signs and a video's own label ("Official Video") are set aside. */
    private fun sameTitle(a: String, b: String): Boolean {
        val x = Lines.normalize(a.replace(VIDEO_LABEL, ""))
        val y = Lines.normalize(b.replace(VIDEO_LABEL, ""))
        return x.isNotEmpty() && x == y
    }

    private fun sameAlbum(item: ImportItem, candidate: Track): Boolean {
        val a = item.album?.let { TrackNormalizer.analyze(it).version.identityKey }?.takeIf { it.isNotEmpty() } ?: return false
        val b = candidate.album?.title?.let { TrackNormalizer.analyze(it).version.identityKey }?.takeIf { it.isNotEmpty() } ?: return false
        return a == b
    }

    private fun lengthGap(item: ImportItem, candidate: Track): Long? {
        val a = item.durationMs ?: return null
        val b = candidate.durationMs ?: return null
        return abs(a - b)
    }

    /** A video's own label in brackets, never part of a song's name (LastWave's SAFE_VIDEO_LABEL_REGEX). */
    private val VIDEO_LABEL = Regex(
        """(?i)\s*[\[(](?:official\s+)?(?:music\s+video|lyric\s+video|audio\s+video|visualizer|audio|video|track|hd|hq|4k)\b[^\])]*[\])]""",
    )
}
