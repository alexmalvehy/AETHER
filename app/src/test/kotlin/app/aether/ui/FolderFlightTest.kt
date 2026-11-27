package app.aether.ui

import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.component.FolderPose
import app.aether.core.model.Artwork
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A folder's page and its flight: one progress for both (the page's slide), a page opened again on its
 * way home coming back as the same page with the same flight, and the back gesture's travel.
 */
class FolderFlightTest {
    private val key = "folder:local:1"
    private fun preview() = FolderPreview(key, "Viral Hits", "46 songs", Artwork.None, emptyList())

    /** Library's first page, a folder on it, and its page opened from it. */
    private fun opened(): Triple<Navigator, FolderFlight, SlotKey> {
        val nav = Navigator()
        val flight = FolderFlight()
        nav.select(Tab.Library)
        val slot = SlotKey(nav.top.id, key)
        nav.open(Route.LocalPlaylist(1))
        flight.depart(preview(), FolderPose(), slot, 168.dp, nav.top)
        return Triple(nav, flight, slot)
    }

    @Test fun `a page opened from a folder grows out of it, and the folder is hidden in its place`() {
        val (nav, flight, slot) = opened()
        assertNotNull(nav.top.trip)
        assertEquals(1, flight.trips.size)
        // Just opened, its slide is at 1: the folder is in the air.
        assertTrue(flight.hides(slot))
        assertTrue(flight.headHidden(nav.top))
    }

    @Test fun `opened again on its way home, the same page comes back with the same flight`() {
        val (nav, flight, slot) = opened()
        val page = nav.top
        val trip = page.trip
        runBlocking { page.slide.snap(0.4f) } // part of the way there
        nav.back()
        assertEquals(PagePhase.Leaving, page.phase)
        assertTrue(flight.hides(slot), "on its way home the folder is still in the air")
        nav.open(Route.LocalPlaylist(1))
        assertSame(page, nav.top, "the same page, not a new one")
        assertEquals(PagePhase.Open, page.phase)
        // The touch that brought it back departs again; the flight it had is kept, turned round.
        flight.depart(preview(), FolderPose(), slot, 168.dp, nav.top)
        assertSame(trip, page.trip)
        assertEquals(1, flight.trips.size)
        assertEquals(0.4f, page.slide.value, "it carries on from where it was")
    }

    @Test fun `fast open and back again and again never makes a second flight`() {
        val (nav, flight, slot) = opened()
        val page = nav.top
        repeat(5) {
            nav.back()
            nav.open(Route.LocalPlaylist(1))
            flight.depart(preview(), FolderPose(), slot, 168.dp, nav.top)
            assertSame(page, nav.top)
        }
        assertEquals(1, flight.trips.size)
    }

    @Test fun `a page already in place doesn't grow out of a folder`() {
        val nav = Navigator()
        val flight = FolderFlight()
        nav.select(Tab.Library)
        val slot = SlotKey(nav.top.id, key)
        nav.open(Route.LocalPlaylist(1))
        runBlocking { nav.top.slide.snap(0f) }
        flight.depart(preview(), FolderPose(), slot, 168.dp, nav.top)
        assertNull(nav.top.trip)
        assertTrue(flight.trips.isEmpty())
    }

    @Test fun `a landed page hides neither the folder in its list nor its head`() {
        val (nav, flight, slot) = opened()
        runBlocking { nav.top.slide.snap(0f) }
        assertFalse(flight.hides(slot))
        assertFalse(flight.headHidden(nav.top))
    }

    @Test fun `only the folder that was touched is hidden, on the page it was touched on`() {
        val (_, flight, slot) = opened()
        assertFalse(flight.hides(SlotKey(slot.page, "folder:local:2")))
        assertFalse(flight.hides(SlotKey(slot.page + 1, key)))
    }

    @Test fun `a page that's gone takes its flight with it`() {
        val (nav, flight, slot) = opened()
        val page = nav.top
        flight.ended(page)
        assertNull(page.trip)
        assertTrue(flight.trips.isEmpty())
        assertFalse(flight.hides(slot))
    }

    @Test fun `a second page asked for while one is still arriving is ignored`() {
        val nav = Navigator()
        nav.select(Tab.Library)
        nav.open(Route.LocalPlaylist(1))
        val first = nav.top
        // Two folders touched at once: the second arrives while the first is still coming in.
        nav.open(Route.LocalPlaylist(2))
        assertSame(first, nav.top, "only the first opens")
        runBlocking { first.slide.snap(0f) }
        nav.open(Route.LocalPlaylist(2))
        assertTrue(nav.top !== first && nav.top.route == Route.LocalPlaylist(2), "once it has landed, the next opens")
    }

    @Test fun `a page flying home can be brought back while nothing is arriving`() {
        val (nav, _, _) = opened()
        val page = nav.top
        runBlocking { page.slide.snap(0f) }
        nav.back()
        nav.open(Route.LocalPlaylist(1))
        assertSame(page, nav.top)
    }

    @Test fun `the page under a page is the one it covers`() {
        val nav = Navigator()
        nav.select(Tab.Library)
        val first = nav.top
        nav.open(Route.LocalPlaylist(1))
        val second = nav.top
        assertSame(first, nav.beneath(second))
        nav.back()
        // On its way out it still covers the page it came from.
        assertSame(first, nav.beneath(second))
    }

    // --- One page opened while another is still on its way home (parallel, as one app closing into its
    // icon goes on under the next one opening) ---

    private fun previewOf(n: Int) = FolderPreview("folder:local:$n", "Playlist $n", "$n songs", Artwork.None, emptyList())

    /** Library's first page and the folders 1 and 2 on it; the page of 1 opened, landed, and left part way. */
    private fun goingHome(): Pair<Navigator, FolderFlight> {
        val nav = Navigator()
        val flight = FolderFlight()
        nav.select(Tab.Library)
        val root = nav.top
        openFolderPage(nav, flight, previewOf(1), Route.LocalPlaylist(1), FolderPose(), SlotKey(root.id, "folder:local:1#1"), 168.dp)
        val first = nav.top
        runBlocking { first.slide.snap(0f) }
        nav.back()
        runBlocking { first.slide.snap(0.6f) }
        return nav to flight
    }

    @Test fun `a page opened while another goes home comes in over it and over its folder`() {
        val (nav, flight) = goingHome()
        val (root, first) = nav.pagesOf(Tab.Library)
        openFolderPage(nav, flight, previewOf(2), Route.LocalPlaylist(2), FolderPose(), SlotKey(root.id, "folder:local:2#2"), 168.dp)
        val second = nav.top
        assertEquals(listOf(root, first, second), nav.pagesOf(Tab.Library), "the new page is drawn over the one going")
        // Both folders are in the air: the first still going home under the second, the second coming up.
        assertEquals(2, flight.trips.size)
        assertTrue(flight.hides(SlotKey(root.id, "folder:local:1#1")))
        assertTrue(flight.hides(SlotKey(root.id, "folder:local:2#2")))
        assertEquals(PagePhase.Leaving, first.phase)
        assertEquals("folder:local:2", second.preview?.key)
        assertEquals("folder:local:1", first.preview?.key, "the page going keeps its own folder")
    }

    @Test fun `the page beneath goes on as it was when another page comes over the one going`() {
        val (nav, flight) = goingHome()
        val covered = { coverBy(nav.pagesOf(Tab.Library).drop(1)) }
        assertEquals(0.4f, covered(), 1e-6f)
        val root = nav.pagesOf(Tab.Library).first()
        openFolderPage(nav, flight, previewOf(2), Route.LocalPlaylist(2), FolderPose(), SlotKey(root.id, "folder:local:2#2"), 168.dp)
        // Just opened, the new page is all the way off: nothing jumps.
        assertEquals(0.4f, covered(), 1e-6f)
        val (_, first, second) = nav.pagesOf(Tab.Library)
        // Half way each, it's more covered than by either alone, and never less than the one coming.
        runBlocking { first.slide.snap(0.8f); second.slide.snap(0.5f) }
        assertEquals(0.6f, covered(), 1e-6f)
        // The first home and the second in place: wholly covered.
        runBlocking { first.slide.snap(1f); second.slide.snap(0f) }
        assertEquals(1f, covered(), 1e-6f)
    }

    @Test fun `a spring a hair past either end covers no more than all and no less than nothing`() {
        val (nav, _) = goingHome()
        val first = nav.pagesOf(Tab.Library)[1]
        runBlocking { first.slide.snap(1.02f) }
        assertEquals(0f, coverBy(listOf(first)), 1e-6f)
        runBlocking { first.slide.snap(-0.03f) }
        assertEquals(1f, coverBy(listOf(first)), 1e-6f)
        assertEquals(0f, coverBy(emptyList()))
    }

    @Test fun `a page gone under a newer one is never brought back out over it`() {
        val (nav, flight) = goingHome()
        val (root, first) = nav.pagesOf(Tab.Library)
        openFolderPage(nav, flight, previewOf(2), Route.LocalPlaylist(2), FolderPose(), SlotKey(root.id, "folder:local:2#2"), 168.dp)
        val second = nav.top
        runBlocking { second.slide.snap(0f) }
        // From the second page, the first's playlist again: a new page comes, over everything.
        nav.open(Route.LocalPlaylist(1))
        val again = nav.top
        assertTrue(again !== first && again.route == Route.LocalPlaylist(1))
        assertEquals(listOf(root, first, second, again), nav.pagesOf(Tab.Library))
        assertEquals(PagePhase.Leaving, first.phase, "the old one goes on home under them")
    }

    @Test fun `a page brought back from its way home comes back on top`() {
        val (nav, flight) = goingHome()
        val (root, first) = nav.pagesOf(Tab.Library)
        openFolderPage(nav, flight, previewOf(1), Route.LocalPlaylist(1), FolderPose(), SlotKey(root.id, "folder:local:1#1"), 168.dp)
        assertSame(first, nav.top)
        assertEquals(listOf(root, first), nav.pagesOf(Tab.Library))
        assertEquals(1, flight.trips.size, "its own flight, turned round")
    }

    @Test fun `a folder touched with a card's page still arriving neither opens nor flies onto that page`() {
        val nav = Navigator()
        val flight = FolderFlight()
        nav.select(Tab.Library)
        val slot = SlotKey(nav.top.id, "folder:local:1#1")
        nav.open(Route.Charts)
        val card = nav.top
        openFolderPage(nav, flight, previewOf(1), Route.LocalPlaylist(1), FolderPose(), slot, 168.dp)
        assertSame(card, nav.top)
        assertNull(card.trip)
        assertNull(card.preview)
        assertTrue(flight.trips.isEmpty())
        assertFalse(flight.hides(slot), "the folder touched stays in its place")
    }

    @Test fun `two folders touched at once open only the first, which keeps its own folder`() {
        val nav = Navigator()
        val flight = FolderFlight()
        nav.select(Tab.Library)
        val root = nav.top
        openFolderPage(nav, flight, previewOf(1), Route.LocalPlaylist(1), FolderPose(), SlotKey(root.id, "folder:local:1#1"), 168.dp)
        val first = nav.top
        openFolderPage(nav, flight, previewOf(2), Route.LocalPlaylist(2), FolderPose(), SlotKey(root.id, "folder:local:2#2"), 168.dp)
        assertSame(first, nav.top)
        assertEquals("folder:local:1", first.preview?.key, "a page still loading never shows the other folder, or that it's gone")
        assertEquals(1, flight.trips.size)
        assertFalse(flight.hides(SlotKey(root.id, "folder:local:2#2")))
    }

    @Test fun `from the left edge a page follows the thumb exactly`() {
        assertEquals(0.5f, backTravel(progress = 0.2f, dragged = 540f, width = 1080f, fromLeft = true))
        assertEquals(0f, backTravel(progress = 0.1f, dragged = -30f, width = 1080f, fromLeft = true))
    }

    @Test fun `from the right edge a page goes by the gesture's own progress`() {
        assertEquals(0.3f, backTravel(progress = 0.3f, dragged = -500f, width = 1080f, fromLeft = false))
    }

    @Test fun `a page caught still arriving goes from where it is, never jumping to its place first`() {
        // Still 40% short of its place when the thumb takes it: the thumb hasn't moved, so neither does it.
        assertEquals(0.4f, backTravel(progress = 0f, dragged = 0f, width = 1080f, fromLeft = true, from = 0.4f))
        assertEquals(0.5f, backTravel(progress = 0.1f, dragged = 108f, width = 1080f, fromLeft = true, from = 0.4f), 1e-6f)
        assertEquals(0.4f, backTravel(progress = 0f, dragged = 0f, width = 1080f, fromLeft = false, from = 0.4f))
        assertEquals(0.7f, backTravel(progress = 0.5f, dragged = 0f, width = 1080f, fromLeft = false, from = 0.4f), 1e-6f)
    }
}
