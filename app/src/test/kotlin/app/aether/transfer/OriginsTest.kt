package app.aether.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where playlists came from, kept across AETHER's starts. */
class OriginsTest {
    private val dir: File = kotlin.io.path.createTempDirectory().toFile().also { it.deleteOnExit() }
    private fun store() = Origins(File(dir, "playlist-origins.txt"))

    @Test fun `an origin leads back to its playlist after AETHER starts again`() = runBlocking {
        store().remember("spotify:37i9dQZF1DXcBWIGoYBM5M", 12)
        store().remember("file:chill vibes", 15)
        val again = store()
        assertEquals(12L, again.owner("spotify:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(15L, again.owner("file:chill vibes"))
        assertNull(again.owner("spotify:other"))
    }

    @Test fun `a playlist's lasting id for AETHER files is made once and kept`() = runBlocking {
        val first = store().aetherId(7)
        assertEquals(first, store().aetherId(7))
        assertNotEquals(first, store().aetherId(8))
        assertEquals(7L, store().owner("aether:$first"), "its own file, read here again, leads back to it")
    }

    @Test fun `a playlist that came in an AETHER file keeps that file's id`() = runBlocking {
        val s = store()
        s.remember("aether:from-another-phone", 3)
        assertEquals("from-another-phone", s.aetherId(3))
    }

    @Test fun `the origins of playlists that are gone are forgotten`() = runBlocking {
        val s = store()
        s.remember("spotify:a", 1)
        s.remember("spotify:b", 2)
        s.prune(setOf(2))
        assertNull(store().owner("spotify:a"))
        assertEquals(2L, store().owner("spotify:b"))
        assertTrue(store().of(2).contains("spotify:b"))
    }
}
