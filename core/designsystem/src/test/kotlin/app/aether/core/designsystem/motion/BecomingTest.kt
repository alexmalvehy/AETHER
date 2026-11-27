package app.aether.core.designsystem.motion

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared transform's rules: a 1080 x 2392 screen, a 441 x 379 folder in a list. */
class BecomingTest {
    private val screen = Rect(0f, 0f, 1080f, 2392f)
    private val folder = Rect(53f, 1402f, 494f, 1781f)

    private fun near(expected: Float, actual: Float, what: String) =
        assertTrue(kotlin.math.abs(expected - actual) < 0.01f, "$what: expected $expected, was $actual")

    @Test fun `both ends of a flight are exactly its two places`() {
        assertEquals(folder, Becoming.lerp(folder, screen, 0f))
        assertEquals(screen, Becoming.lerp(folder, screen, 1f))
    }

    @Test fun `past an end it carries on along the same line, so a spring's overshoot is continuous`() {
        val over = Becoming.lerp(folder, screen, 1.05f)
        assertTrue(over.left < 0f && over.top < 0f && over.right > 1080f && over.bottom > 2392f)
    }

    @Test fun `the window at the start is the folder, the page scaled to its width and cut at its height`() {
        val w = Becoming.window(folder, Size(1080f, 2392f), radius = 26f)
        near(441f / 1080f, w.scale, "scale")
        near(53f, w.x, "x")
        near(1402f, w.y, "y")
        // 379 px of screen is 928 px of page at that scale.
        near(379f / (441f / 1080f), w.clipHeight, "clip")
        near(26f / (441f / 1080f), w.radius, "radius in page units")
    }

    @Test fun `the window at the end is the page itself`() {
        val w = Becoming.window(screen, Size(1080f, 2392f), radius = 100f)
        near(1f, w.scale, "scale")
        near(0f, w.x, "x")
        near(0f, w.y, "y")
        near(2392f, w.clipHeight, "clip")
    }

    @Test fun `a place on screen is landed in exactly, and shown`() {
        val l = Becoming.landing(live = folder, lastSeen = null, screen = screen, from = screen)
        assertEquals(folder, l.rect)
        assertEquals(Landing.Kind.Slot, l.kind)
    }

    @Test fun `a place partly off the top is still landed in exactly`() {
        val partly = folder.translate(0f, -1500f) // top at -98
        val l = Becoming.landing(live = partly, lastSeen = null, screen = screen, from = screen)
        assertEquals(partly, l.rect)
        assertEquals(Landing.Kind.Slot, l.kind)
    }

    @Test fun `a place scrolled far above the screen is landed in just past the top edge`() {
        val above = folder.translate(0f, -4000f)
        val l = Becoming.landing(live = above, lastSeen = null, screen = screen, from = screen)
        assertEquals(Landing.Kind.Edge, l.kind)
        assertTrue(l.rect.bottom <= 0f, "it ends off the screen: ${l.rect}")
        assertTrue(l.rect.bottom > -200f, "but only just: ${l.rect}")
        near(above.left, l.rect.left, "same side")
        near(above.height, l.rect.height, "same size")
    }

    @Test fun `a place scrolled far below the screen is landed in just past the foot`() {
        val below = folder.translate(0f, 5000f)
        val l = Becoming.landing(live = below, lastSeen = null, screen = screen, from = screen)
        assertEquals(Landing.Kind.Edge, l.kind)
        assertTrue(l.rect.top >= 2392f && l.rect.top < 2392f + 200f, "${l.rect}")
    }

    @Test fun `a place that's gone is landed where it was last seen, by its edge`() {
        val l = Becoming.landing(live = null, lastSeen = folder, screen = screen, from = screen)
        assertEquals(folder, l.rect)
        assertEquals(Landing.Kind.Edge, l.kind) // nothing there to show
    }

    @Test fun `a place never seen shrinks and fades where the flight is`() {
        val from = Rect(300f, 400f, 700f, 800f)
        val l = Becoming.landing(live = null, lastSeen = null, screen = screen, from = from)
        assertEquals(Landing.Kind.Vanish, l.kind)
        assertEquals(from.center, l.rect.center)
        near(from.width / 2f, l.rect.width, "half the size")
    }

    @Test fun `the ramp is an even S from 0 to 1`() {
        assertEquals(0f, Becoming.ramp(-1f, 0f, 1f))
        assertEquals(1f, Becoming.ramp(2f, 0f, 1f))
        near(0.5f, Becoming.ramp(0.5f, 0f, 1f), "middle")
    }
}
