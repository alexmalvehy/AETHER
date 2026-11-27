@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.nift4.alacdecoder.AlacDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gramophone's Apple Lossless decoder, as AETHER plays ALAC with it: a frame of known samples, written
 * the way an encoder writes an uncompressed frame, comes back as exactly those samples.
 */
class AlacDecoderTest {

    /** Writes bits most significant first, as ALAC reads them. */
    private class Bits {
        private val bytes = java.io.ByteArrayOutputStream()
        private var current = 0
        private var used = 0

        fun write(value: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                current = (current shl 1) or ((value shr i) and 1L).toInt()
                if (++used == 8) {
                    bytes.write(current)
                    current = 0
                    used = 0
                }
            }
        }

        fun bytes(): ByteArray {
            if (used > 0) write(0, 8 - used)
            return bytes.toByteArray()
        }
    }

    /** The decoder's magic cookie (ALACSpecificConfig), as MP4's 'alac' box carries it. */
    private fun cookie(samplesPerFrame: Int, bitDepth: Int, channels: Int): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(samplesPerFrame)
            .put(0) // compatible version
            .put(bitDepth.toByte())
            .put(40).put(10).put(14) // rice history mult, initial history, k modifier: Apple's defaults
            .put(channels.toByte())
            .putShort(255) // max run
            .putInt(0) // max frame bytes: unknown
            .putInt(0) // average bit rate
            .putInt(44100)
            .array()

    /** One mono frame, stored uncompressed: what an encoder writes when compressing would make it bigger. */
    private fun monoFrame(samples: ShortArray): ByteArray = Bits().apply {
        write(0, 3) // a single channel element
        write(0, 4) // its tag
        write(0, 12) // unused
        write(1, 1) // the frame says how many samples it has
        write(0, 2) // no bytes shifted out
        write(1, 1) // not compressed
        write(samples.size.toLong(), 32)
        samples.forEach { write(it.toLong() and 0xFFFF, 16) }
        write(7, 3) // the end of the frame
    }.bytes()

    @Test
    fun anUncompressedFrameDecodesToItsOwnSamples() {
        val samples = shortArrayOf(0, 1000, -1000, 32767, -32768, 12345, -1, 7)
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_ALAC)
            .setChannelCount(1)
            .setSampleRate(44100)
            .setInitializationData(listOf(cookie(samples.size, 16, 1)))
            .build()
        val decoder = AlacDecoder(format, 2, 2)
        try {
            val frame = monoFrame(samples)
            val input: DecoderInputBuffer = decoder.dequeueInputBuffer()!!
            input.ensureSpaceForWrite(frame.size)
            input.data!!.put(frame)
            input.timeUs = 0
            input.flip()
            decoder.queueInputBuffer(input)

            var output: SimpleDecoderOutputBuffer? = null
            val deadline = System.currentTimeMillis() + 5_000
            while (output == null && System.currentTimeMillis() < deadline) {
                output = decoder.dequeueOutputBuffer()
                if (output == null) Thread.sleep(5)
            }
            assertNotNull("the decoder answered", output)
            val pcm = output!!.data!!.order(ByteOrder.nativeOrder())
            assertEquals(samples.size * 2, pcm.remaining())
            samples.forEachIndexed { i, expected -> assertEquals("sample $i", expected, pcm.getShort()) }
            output.release()
        } finally {
            decoder.release()
        }
    }
}
