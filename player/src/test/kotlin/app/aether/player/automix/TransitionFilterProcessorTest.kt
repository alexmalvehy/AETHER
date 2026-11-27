package app.aether.player.automix

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/** Automix's filter as AETHER's sink runs it: the audio itself in, what comes out measured. */
@OptIn(UnstableApi::class)
class TransitionFilterProcessorTest {
    private val rate = 48_000

    private fun processor(encoding: Int, channels: Int = 2, startUs: Long = 0L) = TransitionFilterProcessor().apply {
        configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        flush(AudioProcessor.StreamMetadata(startUs))
    }

    private fun stereoSine(hz: Double, amplitude: Float, frames: Int, from: Int = 0) =
        FloatArray(frames * 2) { amplitude * sin(2 * PI * hz * (from + it / 2) / rate).toFloat() }

    private fun runFloat(p: TransitionFilterProcessor, samples: FloatArray): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { input.putFloat(it) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        return FloatArray(out.remaining()).also { out.get(it) }
    }

    private fun run16(p: TransitionFilterProcessor, samples: ShortArray): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun betweenMixesEveryByteOf16BitPassesAsItCame() {
        val p = processor(C.ENCODING_PCM_16BIT)
        val bytes = ByteArray(8_000) { (it * 37 % 251).toByte() }
        val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertContentEquals(bytes, ByteArray(out.remaining()).also { out.get(it) })
    }

    @Test
    fun betweenMixesEverySampleOfFloatPassesAsItCame() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        val samples = stereoSine(440.0, 0.9f, 4_800)
        assertContentEquals(samples, runFloat(p, samples))
    }

    @Test
    fun itKnowsWhereInTheSongWhatItProcessedIs() {
        val p = processor(C.ENCODING_PCM_FLOAT, startUs = 1_000_000L)
        assertEquals(C.TIME_UNSET, p.processedUs)
        runFloat(p, stereoSine(440.0, 0.5f, 4_800))
        assertEquals(1_100_000L, p.processedUs)
    }

    @Test
    fun aLowPassTakesTheHighsAway() {
        val p = processor(C.ENCODING_PCM_FLOAT)
        p.setCutoffs(lowPassHz = 300f, highPassHz = TransitionFilterProcessor.OFF_HZ)
        val out = runFloat(p, stereoSine(6_000.0, 0.8f, 48_000))
        val tail = out.drop(out.size / 2).maxOf { abs(it) }
        assertTrue(tail < 0.02f, "6 kHz through a 300 Hz low-pass came out at $tail")
    }

    /**
     * Media3's sink flushes every step when it renegotiates mid-song. That isn't a seek: a flush that
     * carries straight on from where the audio ended keeps the filter's state, so the sound doesn't
     * click. Measured against the same audio through a filter never flushed.
     */
    @Test
    fun aFlushThatCarriesStraightOnKeepsTheFilterWhereItWas() {
        val steady = processor(C.ENCODING_PCM_FLOAT)
        val flushed = processor(C.ENCODING_PCM_FLOAT)
        listOf(steady, flushed).forEach { it.setCutoffs(lowPassHz = 800f, highPassHz = TransitionFilterProcessor.OFF_HZ) }
        val first = stereoSine(220.0, 0.7f, 9_600)
        val second = stereoSine(220.0, 0.7f, 9_600, from = 9_600)
        runFloat(steady, first)
        runFloat(flushed, first)
        flushed.flush(AudioProcessor.StreamMetadata(flushed.processedUs))
        assertContentEquals(runFloat(steady, second), runFloat(flushed, second))
    }

    @Test
    fun aSeekStartsTheFilterAfresh() {
        val steady = processor(C.ENCODING_PCM_FLOAT)
        val sought = processor(C.ENCODING_PCM_FLOAT)
        listOf(steady, sought).forEach { it.setCutoffs(lowPassHz = 800f, highPassHz = TransitionFilterProcessor.OFF_HZ) }
        val first = stereoSine(220.0, 0.7f, 9_600)
        val second = stereoSine(220.0, 0.7f, 9_600, from = 9_600)
        runFloat(steady, first)
        runFloat(sought, first)
        sought.flush(AudioProcessor.StreamMetadata(60_000_000L))
        val a = runFloat(steady, second)
        val b = runFloat(sought, second)
        assertTrue(a.indices.any { abs(a[it] - b[it]) > 1e-4f }, "a seek kept the filter's state")
    }

    /**
     * Two songs overlapping: with the faders at `in` and `out`, the trim is `1 / sqrt(in + out)` and
     * each song's peaks are held to the trim before it's applied, so whatever the two masters do the
     * sum the mixer gets never passes full scale (BitChord's trim alone let it reach 1.19).
     */
    @Test
    fun midBlendTwoFullScaleSongsCantSumPastFullScale() {
        val rise = 0.70710677f
        val fall = 0.70710677f
        val trim = 1f / kotlin.math.sqrt(rise + fall)
        val a = processor(C.ENCODING_PCM_16BIT)
        val b = processor(C.ENCODING_PCM_16BIT)
        a.setBlendTrim(trim)
        b.setBlendTrim(trim)
        // Square waves at full scale, in phase: two kicks landing together, every sample.
        val square = ShortArray(48_000 * 2) { if ((it / 2 / 120) % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        val outA = run16(a, square)
        val outB = run16(b, square)
        val settled = outA.size / 2
        var worst = 0f
        for (i in settled until outA.size) {
            val sum = rise * outA[i] / 32768f + fall * outB[i] / 32768f
            worst = maxOf(worst, abs(sum))
        }
        assertTrue(worst <= 1.0005f, "the two songs summed to $worst")
        assertTrue(worst > 0.95f, "the peak catcher took more than it needed: $worst")
    }

    @Test
    fun afterTheBlendTheTrimLetsGoAndTheBytesPassAgain() {
        val p = processor(C.ENCODING_PCM_16BIT)
        p.setBlendTrim(0.84f)
        run16(p, ShortArray(48_000) { (it * 7 % 2000).toShort() })
        p.setBlendTrim(1f)
        run16(p, ShortArray(48_000) { (it * 7 % 2000).toShort() })
        val bytes = ByteArray(4_000) { (it * 13 % 251).toByte() }
        val input = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
        input.flip()
        p.queueInput(input)
        val out = p.output
        assertContentEquals(bytes, ByteArray(out.remaining()).also { out.get(it) })
    }
}
