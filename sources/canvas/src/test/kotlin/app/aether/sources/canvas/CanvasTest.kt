package app.aether.sources.canvas

import app.aether.core.model.CanvasSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasTest {

    // --- Which song a cover is of (LastWave's CanvasMatchingTest, held to AETHER's rules) ---

    @Test fun `the same song, packaged differently, is the same song`() {
        assertTrue(SameSong.matches("Crazy in Love", "Beyonce", "Dangerously In Love", "Crazy In Love", "Beyoncé ft. Jay-Z", "Dangerously in Love"))
        assertTrue(SameSong.matches("Crazy In Love (Remastered 2011)", "Beyonce", null, "Crazy In Love", "Beyoncé", null))
        assertTrue(SameSong.matches("Crazy In Love (feat. Jay-Z)", "Beyoncé", null, "Crazy In Love", "Beyoncé", null))
    }

    @Test fun `another song, another artist, another take or another album is not`() {
        assertFalse(SameSong.matches("Halo", "Beyonce", null, "Crazy In Love", "Beyoncé", null))
        assertFalse(SameSong.matches("Crazy In Love", "Adele", null, "Crazy In Love", "Beyoncé", null))
        assertFalse(SameSong.matches("Love Story", "Someone", null, "Love", "Someone", null))
        assertFalse(SameSong.matches("Dracula (Live)", "Tame Impala", null, "Dracula", "Tame Impala", null))
        assertFalse(SameSong.matches("Dracula", "Tame Impala", "Something Else", "Dracula", "Tame Impala", "Deadbeat"))
    }

    @Test fun `titles in another script keep it, and two of them aren't one song for having no Latin letters`() {
        // BitChord's and LastWave's matching kept only a to z: both of these came out empty, and matched.
        assertFalse(SameSong.matches("봄날", "BTS", null, "피 땀 눈물", "BTS", null))
        assertFalse(SameSong.matches("तुम ही हो", "Arijit Singh", null, "चन्ना मेरेया", "Arijit Singh", null))
        assertTrue(SameSong.matches("봄날", "BTS", null, "봄날", "BTS", null))
    }

    // --- The community's index ---

    private val index = """{"items":[
        {"song":"Dracula","artist":"Tame Impala","album":"Deadbeat","url":"https://canvas.example/Song/1.mp4"},
        {"song":"No film","artist":"Someone","url":"http://canvas.example/plain.mp4"},
        {"song":"","artist":"Nobody","url":"https://canvas.example/2.mp4"},
        {"song":"Espresso","artist":"Sabrina Carpenter","album":"","url":"https://canvas.example/Song/3.mp4"}
    ]}"""

    private class Clock(var t: Long = 1_000_000L)

    private class Index(var body: String?) : CanvasHttp {
        var asked = 0
        override suspend fun get(url: String): String? {
            asked++
            return body
        }
    }

    @Test fun `entries without a song, an artist or an https film are passed over`() {
        val entries = CommunityCanvas.parse(index)
        assertEquals(listOf("Dracula", "Espresso"), entries.map { it.song })
        assertEquals(emptyList(), CommunityCanvas.parse("not json"))
    }

    @Test fun `the index is fetched once, held, and matched on the phone`() = runTest {
        val http = Index(index)
        val clock = Clock()
        val community = CommunityCanvas(http) { clock.t }
        assertEquals("https://canvas.example/Song/1.mp4", community.find("Dracula", "Tame Impala", "Deadbeat"))
        assertEquals("https://canvas.example/Song/3.mp4", community.find("Espresso", "Sabrina Carpenter", "Short n' Sweet"))
        assertNull(community.find("Espresso", "Pennyy", null))
        assertEquals(1, http.asked)
        clock.t += 31 * 60 * 1000L
        community.find("Dracula", "Tame Impala", null)
        assertEquals(2, http.asked, "fetched again after half an hour")
    }

    @Test fun `an index that didn't come is asked again soon, and what was had is kept meanwhile`() = runTest {
        val http = Index(null)
        val clock = Clock()
        val community = CommunityCanvas(http) { clock.t }
        assertNull(community.find("Dracula", "Tame Impala", null))
        community.find("Dracula", "Tame Impala", null)
        assertEquals(1, http.asked, "not asked again at once")
        clock.t += 3 * 60 * 1000L
        http.body = index
        assertEquals("https://canvas.example/Song/1.mp4", community.find("Dracula", "Tame Impala", null))
    }

    // --- The chain ---

    private class Apple(var film: String? = null, var fails: Boolean = false) {
        val asked = mutableListOf<String>()
        suspend fun find(id: String, title: String, artist: String, album: String?): String? {
            asked += id
            if (fails) throw IllegalStateException("no answer")
            return film
        }
    }

    private fun chain(apple: Apple, community: CommunityCanvas, clock: Clock = Clock()) =
        CanvasChain({ null }, apple::find, community) { clock.t }

    @Test fun `Apple's own first, the index's where Apple has none, and Apple only with consent`() = runTest {
        val community = CommunityCanvas(Index(index))
        val withFilm = chain(Apple("https://apple.example/film.m3u8"), community)
        assertEquals(CanvasSource.APPLE, withFilm.find("a", "Dracula", "Tame Impala", "Deadbeat", apple = true)?.from)
        val without = Apple(null)
        val chain = chain(without, community)
        assertEquals(CanvasSource.COMMUNITY, chain.find("a", "Dracula", "Tame Impala", "Deadbeat", apple = true)?.from)
        val noConsent = Apple("https://apple.example/film.m3u8")
        assertEquals(CanvasSource.COMMUNITY, chain(noConsent, community).find("a", "Dracula", "Tame Impala", "Deadbeat", apple = false)?.from)
        assertTrue(noConsent.asked.isEmpty(), "Apple isn't asked without consent")
    }

    @Test fun `a source that fails is nothing found, and a miss is asked again later`() = runTest {
        val apple = Apple(fails = true)
        val clock = Clock()
        val chain = chain(apple, CommunityCanvas(Index(index)), clock)
        assertNull(chain.find("x", "Unknown Song", "Nobody", null, apple = true))
        chain.find("x", "Unknown Song", "Nobody", null, apple = true)
        assertEquals(1, apple.asked.size, "the miss is remembered")
        clock.t += 11 * 60 * 1000L
        chain.find("x", "Unknown Song", "Nobody", null, apple = true)
        assertEquals(2, apple.asked.size, "and asked again after a while")
    }

    @Test fun `a miss without the album is asked again once the album is known`() = runTest {
        val apple = Apple(null)
        val chain = chain(apple, CommunityCanvas(Index(index)))
        chain.find("y", "Song", "Someone", null, apple = true)
        chain.find("y", "Song", "Someone", "Album", apple = true)
        assertEquals(2, apple.asked.size)
    }

    @Test fun `a lookup cancelled stays cancelled`() = runTest {
        val chain = CanvasChain({ null }, { _, _, _, _ -> throw CancellationException("gone") }, CommunityCanvas(Index(index)))
        assertFailsWith<CancellationException> { chain.find("z", "Dracula", "Tame Impala", null, apple = true) }
    }
}
