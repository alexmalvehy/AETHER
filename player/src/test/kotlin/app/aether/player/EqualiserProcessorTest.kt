package app.aether.player

import app.aether.core.model.EqFilter
import app.aether.core.model.EqKind
import app.aether.core.model.EqProfile
import app.aether.core.model.EqShape
import app.aether.player.automix.AudioBlock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** The audio itself, through the real processor: a sine in, what comes out measured. */
class EqualiserProcessorTest {
    private val rate = 48_000

    private fun peak(hz: Float, db: Float) = EqProfile("t", "t", EqKind.Tuning, listOf(EqFilter(EqShape.Peak, hz, db, 1.41f)))

    private fun processor(): EqualiserProcessor = EqualiserProcessor().apply { configure(rate, 1) }

    private fun sine(hz: Double, amplitude: Float, frames: Int) = FloatArray(frames) { amplitude * sin(2 * PI * hz * it / rate).toFloat() }

    /** [samples] through [p] in the sink's blocks, as it hears them. */
    private fun run(p: EqualiserProcessor, samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        val block = AudioBlock(1, 4096)
        var at = 0
        while (at < samples.size) {
            val n = minOf(block.capacityFrames, samples.size - at)
            block.reset(n)
            samples.copyInto(block.samples, 0, at, at + n)
            p.process(block)
            block.samples.copyInto(out, at, 0, n)
            at += n
        }
        return out
    }

    /** The loudest of the last half: long after the filter has settled. */
    private fun level(samples: FloatArray) = samples.drop(samples.size / 2).maxOf { abs(it) }

    @Test
    fun withNothingChosenTheSamplesAreUntouched() {
        val p = processor()
        val noise = FloatArray(4_000) { ((it * 37 % 251) - 125) / 128f }
        assertContentEquals(noise, run(p, noise))
    }

    @Test
    fun aPeakShapesTheFrequenciesItIsFor() {
        val p = processor()
        p.update(peak(1_000f, 6f))
        // A swap crossfades in: let it finish.
        run(p, sine(1_000.0, 0.5f, 4_800))
        // Everything comes down 6 dB for headroom, and 1 kHz goes back up by the filter's 6.
        assertEquals(0.5f, level(run(p, sine(1_000.0, 0.5f, 24_000))), 0.02f)
        assertEquals(0.25f, level(run(p, sine(100.0, 0.5f, 48_000))), 0.02f)
    }

    @Test
    fun aBoostNeverClips() {
        val p = processor()
        p.update(peak(1_000f, 12f))
        run(p, sine(1_000.0, 1f, 4_800))
        assertTrue(level(run(p, sine(1_000.0, 1f, 24_000))) <= 1.001f)
    }

    @Test
    fun turnedOffItGoesBackToTheFile() {
        val p = processor()
        p.update(peak(1_000f, 6f))
        run(p, sine(1_000.0, 0.5f, 4_800))
        p.update(null)
        run(p, sine(1_000.0, 0.5f, 4_800))
        val clean = sine(440.0, 0.5f, 4_800)
        assertContentEquals(clean, run(p, clean))
    }

    @Test
    fun theNextSongInTheSameFormatKeepsTheFiltersRunning() {
        // The sink configures every song, gapless ones too: the same format mustn't reset the filters,
        // or an album's songs would meet with a step in the sound.
        val p = processor()
        p.update(peak(1_000f, 6f))
        run(p, sine(1_000.0, 0.5f, 4_800))
        val continuous = processor().apply { update(peak(1_000f, 6f)) }
        run(continuous, sine(1_000.0, 0.5f, 4_800))
        p.configure(rate, 1)
        val tone = sine(1_000.0, 0.5f, 2_400)
        assertContentEquals(run(continuous, tone), run(p, tone))
    }
}
