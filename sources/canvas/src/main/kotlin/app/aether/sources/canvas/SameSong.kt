package app.aether.sources.canvas

import app.aether.core.lyrics.matching.ArtistNames
import app.aether.core.lyrics.matching.TrackNormalizer

/**
 * Whether a moving cover filed for one song is the song playing: by AETHER's own rules (PODIUM's
 * normaliser, as the lyrics and an album's songs are matched), not BitChord's and LastWave's. Theirs
 * keep only the letters a to z and the digits: a Korean, Hindi or Japanese title came out empty, and
 * two empty titles matched (BTS's 봄날 would take any of their songs' covers whose names aren't in Latin
 * letters). Here a title keeps its own script.
 *
 * The same title and the same version of it ("Song (Live)" isn't "Song", nor the other way round), by a
 * shared artist, and on the same album where both say which.
 */
internal object SameSong {
    fun matches(wantTitle: String, wantArtist: String, wantAlbum: String?, gotTitle: String, gotArtist: String, gotAlbum: String?): Boolean {
        val want = TrackNormalizer.analyze(wantTitle, wantArtist)
        val got = TrackNormalizer.analyze(gotTitle, gotArtist)
        val identity = want.version.identityKey
        if (identity.isEmpty() || identity != got.version.identityKey) return false
        if (want.version.tags != got.version.tags) return false
        val ours = ArtistNames.comparableForms(wantArtist)
        if (ours.isEmpty() || ArtistNames.comparableForms(gotArtist).none { it in ours }) return false
        if (!wantAlbum.isNullOrBlank() && !gotAlbum.isNullOrBlank()) {
            val a = TrackNormalizer.analyze(wantAlbum).version.identityKey
            val b = TrackNormalizer.analyze(gotAlbum).version.identityKey
            if (a.isNotEmpty() && b.isNotEmpty() && a != b) return false
        }
        return true
    }
}
