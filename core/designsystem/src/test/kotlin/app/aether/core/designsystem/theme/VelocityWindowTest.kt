package app.aether.core.designsystem.theme

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A held thing's speed, from Apple's rolling window of its last ~90 ms. */
class VelocityWindowTest {
    private val ms = 1_000_000L

    @Test fun `steady movement reads as its speed`() {
        val w = VelocityWindow()
        // 0.1 a frame at 90 Hz: 9 a second.
        for (i in 0..8) w.add(i * 11 * ms, i * 0.1f)
        assertTrue(kotlin.math.abs(w.velocity() - 9.09f) < 0.05f, "was ${w.velocity()}")
    }

    @Test fun `a finger that stopped before letting go reads as stopped`() {
        val w = VelocityWindow()
        for (i in 0..8) w.add(i * 11 * ms, i * 0.1f)
        // Then held still for 120 ms: the window only sees the stillness.
        for (i in 1..12) w.add((88 + i * 10) * ms, 0.8f)
        assertEquals(0f, w.velocity())
    }

    @Test fun `one late sample doesn't spike it`() {
        val w = VelocityWindow()
        for (i in 0..7) w.add(i * 11 * ms, i * 0.1f)
        // A sample that arrived 2 ms after the one before, a frame's movement on: a two-point estimate reads 50 a second.
        w.add(79 * ms, 0.8f)
        val v = w.velocity()
        assertTrue(v in 8f..14f, "was $v")
    }

    @Test fun `fewer than two samples is no speed`() {
        val w = VelocityWindow()
        assertEquals(0f, w.velocity())
        w.add(0, 1f)
        assertEquals(0f, w.velocity())
    }

    @Test fun `reset forgets the last gesture`() {
        val w = VelocityWindow()
        for (i in 0..8) w.add(i * 11 * ms, i * 0.1f)
        w.reset()
        w.add(200 * ms, 0.5f)
        assertEquals(0f, w.velocity())
    }
}
