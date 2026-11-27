package app.aether.core.designsystem.component

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a page's name is sized. Widths here are what a name measures at full size; [room] is a phone's
 * width less the page's margins (1080 px at 420 dpi, about 371 dp).
 */
class NameFitTest {
    private val room = 371f

    /** Lines at a scale, as a line breaker breaking only at spaces would give them. */
    private fun lines(words: List<Float>, space: Float): (Float) -> Int = { k ->
        var count = 1
        var used = 0f
        for (w in words) {
            val width = w * k
            val needed = if (used == 0f) width else used + space * k + width
            if (needed > room && used > 0f) { count++; used = width } else used = needed
        }
        count
    }

    @Test fun `a name that fits stands at full size`() {
        assertEquals(1f, NameFit.scale(oneLine = 300f, widestWord = 180f, room = room, maxLines = 3) { 1 })
    }

    @Test fun `one long word gets smaller until it fits whole, never broken`() {
        // PinkPantheress at 74 sp is wider than the phone.
        val k = NameFit.scale(oneLine = 460f, widestWord = 460f, room = room, maxLines = 3) { 1 }
        assertTrue(k < 1f)
        assertTrue(460f * k <= room, "the word fits at $k")
    }

    @Test fun `a name a little too wide is set a little smaller to keep to one line`() {
        // The Neighbourhood
        val k = NameFit.scale(oneLine = 520f, widestWord = 380f, room = room, maxLines = 3, linesAt = lines(listOf(120f, 380f), 20f))
        assertTrue(520f * k <= room, "one line at $k")
        assertTrue(k >= NameFit.ONE_LINE_LEAST)
    }

    @Test fun `a name over several lines is set smaller than on one, and breaks only at spaces`() {
        // Rage Against the Machine: far too long for one line.
        val words = listOf(150f, 260f, 110f, 280f)
        val k = NameFit.scale(oneLine = 860f, widestWord = 280f, room = room, maxLines = 3, linesAt = lines(words, 20f))
        assertTrue(k <= NameFit.SEVERAL_LINES_MOST, "several lines are set smaller: $k")
        assertTrue(280f * k <= room, "its widest word fits whole at $k")
        assertTrue(lines(words, 20f)(k) <= 3)
    }

    @Test fun `a name needing more lines than it may have gets smaller until it fits them`() {
        val words = List(9) { 200f }
        val fit = lines(words, 20f)
        val k = NameFit.scale(oneLine = 1960f, widestWord = 200f, room = room, maxLines = 2, linesAt = fit)
        assertTrue(fit(k) <= 2 || k <= 0.42f, "two lines, or as small as a name gets: $k gives ${fit(k)}")
    }

    @Test fun `a word wider than the smallest name allows still isn't broken`() {
        val k = NameFit.scale(oneLine = 1200f, widestWord = 1200f, room = room, maxLines = 3) { 1 }
        assertTrue(1200f * k <= room, "the word fits at $k, below the floor")
    }

    @Test fun `a one-line place shrinks the name to fit rather than wrapping it`() {
        val k = NameFit.scale(oneLine = 860f, widestWord = 280f, room = room, maxLines = 1) { 3 }
        assertTrue(860f * k <= room)
    }
}
