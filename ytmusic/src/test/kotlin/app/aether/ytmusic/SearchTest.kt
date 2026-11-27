package app.aether.ytmusic

import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Search: filters known by what their parameters select, songs searched alone, and what a top result says. */
class SearchTest {

    private fun fixture(name: String) = checkNotNull(
        YouTubeMusicClient.parseJson(GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json.gz"))).use { it.readBytes() }),
    )

    private val chips by lazy { YouTubeMusicParser.search(fixture("search")).filters }

    @Test
    fun `every chip the service sends says what it selects in its parameters`() {
        val kinds = chips.associate { it.label to SearchFilters.kindOf(it.params) }
        assertEquals(
            mapOf(
                "Songs" to 1, "Videos" to 2, "Albums" to 3, "Artists" to 4, "Featured playlists" to 7,
                "Community playlists" to 8, "Episodes" to 9, "Podcasts" to 10, "Profiles" to 11,
            ),
            kinds,
        )
    }

    @Test
    fun `the Songs filter is found by its parameters in any language, never by its label`() {
        val spanish = chips.map {
            it.copy(label = when (it.label) { "Songs" -> "Canciones"; "Videos" -> "Vídeos"; "Albums" -> "Álbumes"; else -> it.label + " (es)" })
        }
        val songs = chips.single { it.label == "Songs" }.params
        assertEquals(songs, SearchFilters.songs(spanish))
    }

    @Test
    fun `without the answer's filters, AETHER writes the Songs filter itself`() {
        assertEquals("EgWKAQIIAQ%3D%3D", SearchFilters.songs(emptyList()))
        assertEquals(SearchFilters.SONGS, SearchFilters.kindOf(SearchFilters.encoded(SearchFilters.SONGS)))
    }

    @Test
    fun `parameters that aren't a filter's are no kind at all, and never throw`() {
        for (junk in listOf("", "!!!", "%ZZ", "%", "AAAA", "EgWK", "/////w%3D%3D", "Eg%3D%3D")) {
            assertNull(SearchFilters.kindOf(junk), "\"$junk\"")
        }
    }

    @Test
    fun `songs searched alone come as songs, explicit ones marked, with more to follow`() {
        // Captured live with AETHER's own Songs filter (captureSearch), so the filter it writes works.
        val page = YouTubeMusicParser.search(fixture("search_songs_explicit"))
        val songs = page.items.filterIsInstance<Song>()
        assertEquals(20, songs.size)
        assertTrue(songs.all { it.kind == MediaKind.SONG })
        val first = songs.first()
        assertEquals("Not Like Us", first.title)
        assertTrue(first.explicit, "Not Like Us carries the explicit badge")
        assertEquals(false, songs[1].explicit, "${songs[1].title} carries none")
        assertTrue(songs.count { it.explicit } >= 4)
        assertNotNull(page.continuation)
    }

    @Test
    fun `a top result's songs are credited to the artist the card is`() {
        // Its songs say only "Song • 2:49"; before, they reached the screen by nobody.
        val page = YouTubeMusicParser.search(fixture("search_mixed_pinkpantheress"))
        val card = page.items.first() as Artist
        assertEquals("PinkPantheress", card.title)
        val cardSongs = page.items.filterIsInstance<Song>().take(3)
        assertEquals(listOf("Stateside", "Stars", "Boy's a liar Pt. 2"), cardSongs.map { it.title })
        for (s in cardSongs) assertEquals(listOf(Ref("PinkPantheress", card.browseId)), s.artists, s.title)
        // The list's own rows name theirs already, and keep them.
        val row = page.items.filterIsInstance<Song>().first { it.title == "True romance" }
        assertEquals(listOf("PinkPantheress"), row.artists.map { it.name })
    }

    @Test
    fun `songs searched alone carry who made them, the album and the length`() {
        val songs = YouTubeMusicParser.search(fixture("search_songs_pinkpantheress")).items.filterIsInstance<Song>()
        val liar = songs.first { it.title == "Boy's a liar Pt. 2" }
        assertEquals(listOf("PinkPantheress", "Ice Spice"), liar.artists.map { it.name })
        assertEquals(132_000L, liar.durationMs)
        assertNotNull(liar.album)
    }
}
