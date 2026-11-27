package app.aether.ui.player

import androidx.compose.ui.geometry.Rect
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pill growing into Now Playing as the Dynamic Island grows: the island's own surface and rim,
 * the pill's face gone in the first moment, Now Playing's parts coming in only once there's room, the
 * print flying as itself, and Now Playing at rest exactly as it was.
 */
class GrowthTest {
    private val comingIn: List<Pair<String, (Float) -> Float>> = listOf(
        "ground" to Growth::ground, "bloom" to Growth::bloom, "bloomScale" to Growth::bloomScale, "film" to Growth::film, "landed" to Growth::landed,
    )
    private val goingOut: List<Pair<String, (Float) -> Float>> = listOf(
        "face" to Growth::face, "rim" to Growth::rim, "pillCover" to Growth::pillCover, "dock" to Growth::dock,
    )

    @Test fun `at rest, Now Playing is all there and the pill's face and rim are gone`() {
        comingIn.forEach { (name, f) -> assertEquals(1f, f(1f), name) }
        goingOut.forEach { (name, f) -> assertEquals(0f, f(1f), name) }
    }

    @Test fun `at the pill, the island is the pill, its tint, its rim and its face, with nothing of Now Playing`() {
        assertEquals(Growth.PillTint, Growth.ground(0f))
        assertEquals(0f, Growth.bloom(0f))
        assertEquals(0f, Growth.film(0f))
        goingOut.forEach { (name, f) -> assertEquals(1f, f(0f), name) }
    }

    @Test fun `the island leads, and what's inside comes once there's room for it`() {
        assertEquals(0f, Growth.face(0.12f), "the pill's words are gone by 12%")
        assertEquals(1f, Growth.ground(0.3f), "the surface is Now Playing's own by 30%")
        assertEquals(0f, Growth.bloom(0.3f), "nothing of Now Playing before 30%")
        assertTrue(Growth.rim(0.5f) == 1f && Growth.rim(0.92f) == 0f, "the rim goes as the island becomes the screen")
        assertEquals(0.94f, Growth.bloomScale(0f), 1e-6f)
    }

    @Test fun `every part moves one way only, so the way back is the same path`() {
        val steps = (0..200).map { it / 200f }
        comingIn.forEach { (name, f) -> steps.zipWithNext().forEach { (a, b) -> assertTrue(f(b) >= f(a), "$name goes back on itself at $a") } }
        goingOut.forEach { (name, f) -> steps.zipWithNext().forEach { (a, b) -> assertTrue(f(b) <= f(a), "$name comes back at $a") } }
    }

    @Test fun `past open with the spring's give, nothing goes past its place`() {
        comingIn.forEach { (name, f) -> assertEquals(1f, f(1.04f), name) }
        goingOut.forEach { (name, f) -> assertEquals(0f, f(1.04f), name) }
    }

    @Test fun `closing past the pill, the pill takes it a little smaller and settles`() {
        assertEquals(1f, Growth.squash(0f))
        assertEquals(1f, Growth.squash(0.5f))
        val squashed = Growth.squash(-0.03f)
        assertTrue(squashed < 1f && squashed > 0.95f, "a few percent: $squashed")
    }

    // The A059 laid out: the pill's cover (44 dp) and the print (4:5).
    private val art = Rect(78f, 1979f, 193.5f, 2094.5f)
    private val print = Rect(113f, 300f, 967f, 1367.5f)
    private val corner = 42f

    @Test fun `at rest the print stands exactly as it is`() {
        val f = PrintFlight.at(art, print, 1f, corner)
        assertEquals(1f, f.scale, 1e-4f)
        assertEquals(0f, f.dx, 1e-3f)
        assertEquals(0f, f.dy, 1e-3f)
        assertEquals(Rect(0f, 0f, print.width, print.height), f.clip)
        assertEquals(corner, f.radius, 1e-3f)
    }

    @Test fun `at the pill the print is a circle of its middle, exactly over the pill's cover`() {
        val f = PrintFlight.at(art, print, 0f, corner)
        val onScreen = { x: Float, y: Float ->
            Pair(print.center.x + (x - print.width / 2f) * f.scale + f.dx, print.center.y + (y - print.height / 2f) * f.scale + f.dy)
        }
        val (l, t) = onScreen(f.clip.left, f.clip.top)
        val (r, b) = onScreen(f.clip.right, f.clip.bottom)
        assertEquals(art.left, l, 0.01f)
        assertEquals(art.top, t, 0.01f)
        assertEquals(art.right, r, 0.01f)
        assertEquals(art.bottom, b, 0.01f)
        assertEquals(f.clip.width / 2f, f.radius, 0.01f)
    }

    @Test fun `on the way, what shows of the print fills the flying shape and never more than the print has`() {
        (1..19).map { it / 20f }.forEach { p ->
            val f = PrintFlight.at(art, print, p, corner)
            assertTrue(f.clip.left >= -1e-3f && f.clip.right <= print.width + 1e-3f, "too wide at $p: ${f.clip}")
            assertTrue(f.clip.top >= -1e-3f && f.clip.bottom <= print.height + 1e-3f, "too tall at $p: ${f.clip}")
        }
    }
}
