package app.aether.ui.search

import app.aether.core.model.AlbumRef
import app.aether.core.model.Artist
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The songs a search lists, from its two answers (as recorded for "pinkpantheress", 2026-11-16). */
class SearchSongsTest {
    private val pink = ArtistRef("PinkPantheress", MediaId("ytm:UCpink"))
    private val ice = ArtistRef("Ice Spice", MediaId("ytm:UCice"))

    private fun song(id: String, title: String, artists: List<ArtistRef>, ms: Long? = null, album: String? = null, explicit: Boolean = false, kind: MediaKind = MediaKind.SONG) =
        Track(MediaId("ytm:$id"), title, artists, album?.let { AlbumRef(it) }, durationMs = ms, explicit = explicit, kind = kind)

    // The mixed answer: the artist's card, its songs naming nobody until the parser lends them the
    // card's artist, then the list's rows, which give no length.
    private val mixed = listOf(
        Artist(MediaId("ytm:UCpink"), "PinkPantheress"),
        song("stateside", "Stateside", listOf(pink), ms = 169_000),
        song("liar", "Boy's a liar Pt. 2", listOf(pink), ms = 132_000),
        song("romance", "True romance", listOf(pink)),
        song("video", "Illegal (Official Video)", listOf(pink), kind = MediaKind.MUSIC_VIDEO),
    )

    // The songs alone: every row whole.
    private val alone = listOf(
        song("zara", "Stateside + Zara Larsson", listOf(pink), ms = 185_000, album = "Fancy Some More?"),
        song("liar", "Boy's a liar Pt. 2", listOf(pink, ice), ms = 132_000, album = "Boy's a liar Pt. 2", explicit = true),
        song("romance", "True romance", listOf(pink), ms = 151_000, album = "to hell with it"),
    )

    @Test
    fun `the mixed results' songs lead, the songs searched alone follow, each once`() {
        assertEquals(listOf("stateside", "liar", "romance", "zara"), listedSongs(mixed, alone).map { it.id.key })
    }

    @Test
    fun `a song in both answers is listed whole`() {
        val listed = listedSongs(mixed, alone).associateBy { it.id.key }
        assertEquals(151_000L, listed.getValue("romance").durationMs, "the length the list row left out")
        assertEquals("to hell with it", listed.getValue("romance").album?.title)
        assertEquals(listOf("PinkPantheress", "Ice Spice"), listed.getValue("liar").artists.map { it.name }, "the fuller credit")
        assertTrue(listed.getValue("liar").explicit, "explicit when either copy says so")
    }

    @Test
    fun `videos aren't songs`() {
        assertTrue(listedSongs(mixed, alone).none { it.kind != MediaKind.SONG })
    }

    @Test
    fun `without the songs alone, the mixed results' songs stand as they are`() {
        assertEquals(listOf("stateside", "liar", "romance"), listedSongs(mixed, emptyList()).map { it.id.key })
    }
}
