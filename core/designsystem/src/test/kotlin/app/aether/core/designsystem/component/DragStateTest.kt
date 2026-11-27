package app.aether.core.designsystem.component

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import app.aether.core.designsystem.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A sheet's "is it showing", frame by frame at the A059's 120 Hz, on Now Playing's own springs
 * (Shell.kt's PlayerOpens and PlayerCloses): its close lands in the pill with a bounce.
 */
class DragStateTest {
    private val clock = BroadcastFrameClock()
    private val scope = CoroutineScope(clock + Dispatchers.Unconfined + Job())
    private var now = 0L
    private val sheet = DragState(
        scope, give = 0.2f, flick = 0.3f,
        openSpring = Motion.fluid(0.5f, 0.78f, 0.0008f),
        closeSpring = Motion.fluid(0.46f, 0.72f, 0.0008f),
    ).apply { travelPx = 2392f }

    @After fun stop() = scope.cancel()

    /** One frame: the spring steps, and what it changed is applied, as the end of a frame applies it. */
    private fun frame() {
        now += 8_333_333L
        clock.sendFrame(now)
        Snapshot.sendApplyNotifications()
    }

    /** Frames until nothing is moving, looking after each. */
    private fun runOut(look: () -> Unit = {}) {
        var frames = 0
        while (clock.hasAwaiters) {
            frame()
            look()
            check(++frames < 1000) { "never came to rest" }
        }
    }

    private fun open() {
        sheet.show()
        runOut()
    }

    /** Closes it and stops in the bounce, just past closed. */
    private fun intoTheBounce() {
        sheet.hide()
        var frames = 0
        while (sheet.value >= 0f) {
            frame()
            check(++frames < 200) { "closed without a bounce" }
        }
    }

    @Test fun `closing, it goes the frame it first goes under the line and stays gone through the bounce`() {
        open()
        val frames = mutableListOf<Pair<Float, Boolean>>()
        sheet.hide()
        runOut { frames += sheet.value to sheet.visible }

        val gone = frames.indexOfFirst { !it.second }
        assertTrue(gone > 0, "it went")
        // Never early: every frame before is over the line, and the frame it goes is under it.
        assertTrue(frames.take(gone).all { it.first > DragState.ShownFrom }, "went early")
        assertTrue(frames[gone].first <= DragState.ShownFrom)
        // The bounce really does come back over the line (what showed it again for a moment)...
        val under = frames.indexOfFirst { it.first < 0f }
        assertTrue(under >= gone && frames.drop(under).any { it.first > DragState.ShownFrom }, "the spring comes back over the line")
        // ...and it stays gone through it, to rest.
        assertTrue(frames.drop(gone).none { it.second }, "showed again in the bounce")
        assertEquals(0f, sheet.value)
    }

    @Test fun `opened again in the bounce, it shows again`() {
        open()
        intoTheBounce()
        assertFalse(sheet.visible)
        open()
        assertTrue(sheet.visible)
        assertEquals(1f, sheet.value)
    }

    @Test fun `caught by a finger in the bounce, it shows with the finger`() {
        open()
        intoTheBounce()
        sheet.dragBy(-240f)
        assertTrue(sheet.visible)
        assertTrue(sheet.value > DragState.ShownFrom)
    }

    @Test fun `put away at once and opened, it shows`() {
        open()
        intoTheBounce()
        sheet.dismiss()
        runOut()
        assertFalse(sheet.visible)
        open()
        assertTrue(sheet.visible)
    }
}
