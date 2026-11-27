package app.aether.player

import kotlin.test.assertEquals
import org.junit.Test

/** What the cover's morph reads each frame: where the blend is, between the engine's reports. */
class BlendTest {
    @Test
    fun itMovesOnAtItsOwnRateBetweenReports() {
        val blend = Blend("a", "b", progress = 0.25f, atNanos = 1_000_000_000L, perSecond = 0.1f)
        assertEquals(0.25f, blend.progressAt(1_000_000_000L), 1e-6f)
        assertEquals(0.35f, blend.progressAt(2_000_000_000L), 1e-6f)
    }

    @Test
    fun pausedItHoldsStill() {
        val blend = Blend("a", "b", progress = 0.6f, atNanos = 0L, perSecond = 0f)
        assertEquals(0.6f, blend.progressAt(5_000_000_000L), 1e-6f)
    }

    @Test
    fun itNeverRunsPastEitherEnd() {
        val blend = Blend("a", "b", progress = 0.95f, atNanos = 0L, perSecond = 0.5f)
        assertEquals(1f, blend.progressAt(10_000_000_000L), 1e-6f)
        assertEquals(0.95f, blend.progressAt(-5_000_000_000L), 1e-6f)
    }
}
