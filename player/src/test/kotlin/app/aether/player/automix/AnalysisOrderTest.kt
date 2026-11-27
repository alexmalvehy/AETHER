package app.aether.player.automix

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which song is measured next: the one a mix is waiting on, then the one asked for first. */
class AnalysisOrderTest {
    private class Ask(val id: String, val urgent: Boolean, val ticket: Long)

    private fun next(vararg asks: Ask) = mostPressing(asks.toList(), { it.urgent }, { it.ticket })?.id

    @Test fun `with nothing waiting on it, first come first served`() {
        assertEquals("a", next(Ask("a", false, 1), Ask("b", false, 2), Ask("c", false, 3)))
    }

    @Test fun `a song moved to next is measured ahead of the songs that were next before it`() {
        // "a" was next and is still waiting; the listener moved "c" into its place.
        assertEquals("c", next(Ask("a", false, 1), Ask("b", false, 2), Ask("c", true, 3)))
    }

    @Test fun `among songs a mix waits on, the earlier asked goes first`() {
        assertEquals("b", next(Ask("a", false, 1), Ask("b", true, 2), Ask("c", true, 3)))
    }

    @Test fun `nothing waiting, nothing to run`() {
        assertNull(next())
    }
}
