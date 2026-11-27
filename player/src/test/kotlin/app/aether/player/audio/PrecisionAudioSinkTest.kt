// BitChord's PrecisionAudioSinkTest (app/src/test/java/com/music/bitchord/playback/audio/PrecisionAudioSinkTest.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0), on AETHER's chain. Where BitChord read its pipeline readout
// (AudioOutputStatus, which AETHER doesn't have), these read the samples and encodings themselves; the
// tests that switched BitChord's own equaliser on use AETHER's equaliser or ReplayGain. The last few are AETHER's.
@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.aether.core.model.EqFilter
import app.aether.core.model.EqKind
import app.aether.core.model.EqProfile
import app.aether.core.model.EqShape
import app.aether.player.AudioMeter
import app.aether.player.EqualiserProcessor
import app.aether.player.automix.PcmEncoding
import app.aether.player.automix.TransitionFilterProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class PrecisionAudioSinkTest {

    private class FakeAudioSink : AudioSink {
        var configuredConfig: AudioSink.AudioSinkConfig? = null
        private var sinkListener: AudioSink.Listener? = null
        var flushed: Boolean = false
        var resetCount: Int = 0
        var discontinuityHandled: Boolean = false
        var playedToEndOfStream: Boolean = false
        var isEndedReturn: Boolean = false
        var hasPendingDataReturn: Boolean = false

        var bytesToConsumePerCall: Int = Int.MAX_VALUE
        var lastHandledBuffer: ByteBuffer? = null
        var handleBufferCallCount: Int = 0
        var lastPresentationTimeUs: Long = C.TIME_UNSET
        var lastEncodedAccessUnitCount: Int = 0

        var formatSupportReturn: Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        var supportsFormatReturn: Boolean = true
        var supportsFormatPredicate: ((Format) -> Boolean)? = null

        /**
         * Per-format support level, for delegates that accept some encodings
         * natively and not others. This, rather than [supportsFormatPredicate],
         * is what the sink reads when choosing an output encoding — only
         * SUPPORTED_DIRECTLY means the delegate will really open that track.
         */
        var formatSupportPredicate: ((Format) -> Int)? = null

        override fun setListener(listener: AudioSink.Listener) {
            this.sinkListener = listener
        }

        override fun supportsFormat(format: Format): Boolean {
            return supportsFormatPredicate?.invoke(format) ?: supportsFormatReturn
        }

        override fun getFormatSupport(format: Format): Int =
            formatSupportPredicate?.invoke(format) ?: formatSupportReturn

        /** Where the fake device says the music is. */
        var positionUs: Long = 0L

        override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUs

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            this.configuredConfig = audioSinkConfig
        }

        override fun play() {}

        override fun handleDiscontinuity() {
            discontinuityHandled = true
        }

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int,
        ): Boolean {
            handleBufferCallCount++
            lastHandledBuffer = buffer
            lastPresentationTimeUs = presentationTimeUs
            lastEncodedAccessUnitCount = encodedAccessUnitCount

            val remaining = buffer.remaining()
            val consume = minOf(remaining, bytesToConsumePerCall)
            buffer.position(buffer.position() + consume)

            return !buffer.hasRemaining()
        }

        override fun playToEndOfStream() {
            playedToEndOfStream = true
        }

        override fun isEnded(): Boolean = isEndedReturn

        override fun hasPendingData(): Boolean = hasPendingDataReturn

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

        override fun getSkipSilenceEnabled(): Boolean = false

        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}

        override fun getAudioAttributes(): AudioAttributes? = null

        override fun setAudioSessionId(audioSessionId: Int) {}

        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

        override fun getAudioTrackBufferSizeUs(): Long = 0L

        override fun enableTunnelingV21() {}

        override fun disableTunneling() {}

        override fun setVolume(volume: Float) {}

        override fun pause() {}

        override fun flush() {
            flushed = true
        }

        override fun reset() {
            resetCount++
            configuredConfig = null
        }

        override fun release() {}
    }

    private fun createSink(
        delegate: AudioSink,
        enableFloatOutput: Boolean = true,
        dspChain: DspChain = idleDspChain(),
    ): PrecisionAudioSink {
        return PrecisionAudioSink(
            delegate = delegate,
            dspChain = dspChain,
            enableFloatOutput = { enableFloatOutput },
        )
    }

    private fun rawFormat(pcmEncoding: Int, channelCount: Int = 2): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(pcmEncoding)
        .setChannelCount(channelCount)
        .setSampleRate(48000)
        .build()

    // ---- Bit-exactness -----------------------------------------------------
    //
    // These assert on the *samples handed to the delegate*, not on flags. The
    // claim being checked is that with nothing switched on, the numbers
    // arriving at AudioTrack are the numbers the decoder produced.

    /** Every stage present but switched off, which is the shipping default. */
    private fun idleDspChain(
        equaliser: EqualiserProcessor = EqualiserProcessor(),
        replayGain: ReplayGainProcessor = ReplayGainProcessor(),
        meter: AudioMeter = AudioMeter(),
    ): DspChain = DspChain(equaliser, TransitionFilterProcessor(), replayGain, meter)

    /**
     * 16-bit in, 16-bit out, every sample identical.
     *
     * [app.aether.player.automix.PcmBoundary] scales by 32768 — a power of two — so the trip out to
     * float and back recovers every original integer, both extremes included.
     */
    @Test
    fun `16-bit round trip is exact with an idle chain`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)

        // An even count: these are stereo frames, and a trailing half-frame is
        // deliberately dropped by the sink rather than padded.
        val samples = shortArrayOf(
            0, 1, -1, 1000, -1000, 32767, -32768, 12345, -12345, 255, -256, 4096,
        )
        val input = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach(input::putShort)
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        samples.forEachIndexed { i, expected ->
            assertEquals("sample $i altered", expected, handed.getShort())
        }
    }

    /**
     * 24-bit in, float out, and the float carries the original integer exactly.
     *
     * Float is the only container Media3 will open an AudioTrack with that holds 24 bits —
     * `DefaultAudioSink` has just two linear-PCM output encodings, float and 16-bit. int24 fits
     * inside float32's 24-bit significand, and `PcmBoundary` scales by 8388608, a power of two,
     * so the value survives exactly and can be read straight back out.
     */
    @Test
    fun `24-bit goes to float and survives exactly`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        assertEquals(PcmEncoding.PCM_24BIT_PACKED, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig!!.format.pcmEncoding)

        // Including the extremes and values whose low byte would vanish under
        // any sloppier conversion.
        val samples = intArrayOf(0, 1, -1, 8388607, -8388608, 0x0000FF, 0x7FFFFF, -0x7FFFFF)
        val input = ByteBuffer.allocate(samples.size * 3).order(ByteOrder.nativeOrder())
        samples.forEach {
            input.put((it and 0xFF).toByte())
            input.put(((it shr 8) and 0xFF).toByte())
            input.put(((it shr 16) and 0xFF).toByte())
        }
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        samples.forEachIndexed { i, expected ->
            // Back to the integer domain the sample started in.
            val recovered = Math.round(handed.getFloat() * 8388608.0f)
            assertEquals("sample $i altered", expected, recovered)
        }
    }

    /** On a route that will not open a float track, a 24-bit source has to come down to 16-bit. */
    @Test
    fun `a 24-bit source comes down to 16-bit when the route refuses float`() {
        val fakeDelegate = FakeAudioSink()
        // What DefaultAudioSink answers with float output off: it rewrites the
        // float format to 16-bit and reports transcoding rather than refusing.
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
    }

    /**
     * `supportsFormat` is not a strong enough gate to pick float on: with float output off,
     * `DefaultAudioSink.getFormatSupport` rewrites a float format to 16-bit and still answers
     * SUPPORTED_WITH_TRANSCODING. Only SUPPORTED_DIRECTLY means "yes, natively".
     */
    @Test
    fun `float is taken only when the delegate supports it directly`() {
        val transcoding = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            supportsFormatReturn = true
        }
        val direct = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }

        createSink(transcoding).also {
            it.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
            assertEquals(PcmEncoding.PCM_16BIT, it.targetOutputEncoding)
        }
        createSink(direct).also {
            it.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
            assertEquals(PcmEncoding.PCM_FLOAT, it.targetOutputEncoding)
        }
    }

    /**
     * With float output off, the sink must not tell Media3 that a float format is supported *directly*:
     * `MediaCodecAudioRenderer.getMediaFormat` reads exactly that answer to decide whether to ask the
     * decoder for float output. This is what keeps a decoder from being asked for float on the phone's
     * own speaker (issues #15 and #16: Samsung's FLAC decoder garbled 16-bit FLAC when asked).
     */
    @Test
    fun `float input is not claimed as direct when float output is off`() {
        val floatOff = createSink(FakeAudioSink(), enableFloatOutput = false)
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
            floatOff.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)),
        )
        // Still playable, just not natively.
        assertTrue(floatOff.supportsFormat(rawFormat(C.ENCODING_PCM_FLOAT)))

        val floatOn = createSink(FakeAudioSink(), enableFloatOutput = true)
        assertEquals(
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
            floatOn.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)),
        )
    }

    /** 32-bit integer PCM goes out as float, which carries 24 bits of it: the most Media3 can open. */
    @Test
    fun `32-bit PCM goes out as float`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_32BIT)).build())

        assertEquals(PcmEncoding.PCM_32BIT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
    }

    /** A multichannel stream still gets the DSP chain: nothing in it is stereo-specific. */
    @Test
    fun `precision stays active for a 5 point 1 stream`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(
            AudioSink.AudioSinkConfig.Builder(
                rawFormat(C.ENCODING_PCM_16BIT, channelCount = 6),
            ).build(),
        )

        assertTrue(sink.isPrecisionActive)
        assertEquals(6, fakeDelegate.configuredConfig!!.format.channelCount)
    }

    /** A bitstream the sink cannot decode has no DSP at all. */
    @Test
    fun `a passthrough bitstream is passed by`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate)
        val eac3 = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3_JOC)
            .setChannelCount(6)
            .setSampleRate(48000)
            .build()

        sink.configure(AudioSink.AudioSinkConfig.Builder(eac3).build())

        assertFalse(sink.isPrecisionActive)
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, fakeDelegate.configuredConfig!!.format.sampleMimeType)
    }

    /**
     * A decoder buffer bigger than one block is split, and each piece carries its own timestamp:
     * `DefaultAudioSink` resyncs its clock once a buffer's time is 200 ms from where it expects.
     */
    @Test
    fun `split sub-blocks advance the presentation timestamp`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        // Two full blocks of stereo 16-bit frames.
        val frames = PrecisionAudioSink.DEFAULT_CAPACITY_FRAMES * 2
        val input = ByteBuffer.allocate(frames * 2 * 2).order(ByteOrder.nativeOrder())
        repeat(frames * 2) { input.putShort(0) }
        input.flip()

        sink.handleBuffer(input, 1_000_000L, 1)

        val expected = 1_000_000L + Util.sampleCountToDurationUs(
            PrecisionAudioSink.DEFAULT_CAPACITY_FRAMES.toLong(),
            48000,
        )
        assertEquals(2, fakeDelegate.handleBufferCallCount)
        assertEquals(expected, fakeDelegate.lastPresentationTimeUs)
    }

    /**
     * Switched on, the chain is reached and does change the audio: here ReplayGain, with the levels
     * written into the song's own tags (a FLAC's Vorbis comments, as the decoder hands them over).
     */
    @Test
    fun `the DSP chain is reached when it is switched on`() {
        val replayGain = ReplayGainProcessor().apply { setMode(ReplayGainUtil.Mode.Track) }
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false, dspChain = idleDspChain(replayGain = replayGain))
        val tagged = rawFormat(C.ENCODING_PCM_16BIT).buildUpon()
            .setMetadata(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.02 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.5")))
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(tagged).build())

        val input = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        shortArrayOf(8000, 8000, 8000, 8000).forEach(input::putShort)
        input.flip()

        sink.handleBuffer(input, 0L, 1)

        val handed = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.nativeOrder())
        handed.rewind()
        // −6.02 dB is half. If this ever matches the input, the chain has stopped being reached and
        // the exactness tests above are no longer proving anything.
        assertEquals(4000f, handed.getShort().toFloat(), 2f)
    }

    @Test
    fun `configure activates precision mode with Float32 output when float output enabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(48000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_16BIT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(inputFormat, sink.activeFormat)

        // Delegate must receive Float32 format
        assertNotNull(fakeDelegate.configuredConfig)
        val delegateFormat = fakeDelegate.configuredConfig!!.format
        assertEquals(C.ENCODING_PCM_FLOAT, delegateFormat.pcmEncoding)
        assertEquals(2, delegateFormat.channelCount)
        assertEquals(48000, delegateFormat.sampleRate)
    }

    @Test
    fun `configure activates precision mode with PCM16 output when float output disabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        // Internal Float32 DSP remains active even when output is PCM16
        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_16BIT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
    }

    @Test
    fun `configure activates precision mode with PCM16 output when delegate rejects float`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportPredicate = { format ->
            if (format.pcmEncoding == C.ENCODING_PCM_16BIT) {
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            } else {
                AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            }
        }

        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setChannelCount(2)
            .setSampleRate(96000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_24BIT_PACKED, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
    }

    @Test
    fun `configure falls back for non-raw audio mime types`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertEquals(MimeTypes.AUDIO_AAC, fakeDelegate.configuredConfig!!.format.sampleMimeType)
    }

    @Test
    fun `configure falls back beyond the channel ceiling`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        val inputFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(PrecisionAudioSink.MAX_CHANNELS + 1)
            .setSampleRate(48000)
            .build()

        val config = AudioSink.AudioSinkConfig.Builder(inputFormat).build()
        sink.configure(config)

        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertEquals(
            PrecisionAudioSink.MAX_CHANNELS + 1,
            fakeDelegate.configuredConfig!!.format.channelCount,
        )
    }

    @Test
    fun `configure activates for PCM 16bit, 24bit, 32bit, and Float32`() {
        val encodings = listOf(
            C.ENCODING_PCM_16BIT to PcmEncoding.PCM_16BIT,
            C.ENCODING_PCM_24BIT to PcmEncoding.PCM_24BIT_PACKED,
            C.ENCODING_PCM_32BIT to PcmEncoding.PCM_32BIT,
            C.ENCODING_PCM_FLOAT to PcmEncoding.PCM_FLOAT,
        )

        for ((pcmEncoding, expectedEncoding) in encodings) {
            val fakeDelegate = FakeAudioSink()
            val sink = createSink(fakeDelegate, enableFloatOutput = true)
            val format = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(pcmEncoding)
                .setChannelCount(2)
                .setSampleRate(96000)
                .build()

            sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
            assertTrue("Expected precision active for encoding $pcmEncoding", sink.isPrecisionActive)
            assertEquals(expectedEncoding, sink.inputPcmEncoding)
            assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
            assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig!!.format.pcmEncoding)
        }
    }

    @Test
    fun `handleBuffer converts PCM16 to Float32 and passes to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            // Half scale = 16384 (approx 0.5f)
            inputBuffer.putShort(16384.toShort())
            inputBuffer.putShort((-16384).toShort())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 123456L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        assertEquals(123456L, fakeDelegate.lastPresentationTimeUs)
        assertEquals(1, fakeDelegate.lastEncodedAccessUnitCount)

        // Every frame accounted for in the encoding the delegate was configured with: 4 bytes per
        // sample, so a 16-bit song can never again be read as twice as many float samples as it has.
        assertEquals(frameCount * 2 * 4, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        val leftSample = delegateBuffer.getFloat()
        val rightSample = delegateBuffer.getFloat()
        assertEquals(0.5f, leftSample, 0.01f)
        assertEquals(-0.5f, rightSample, 0.01f)
    }

    @Test
    fun `handleBuffer converts PCM16 in to Float32 DSP to PCM16 out when float disabled`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val frameCount = 128
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putShort(16384.toShort())
            inputBuffer.putShort((-16384).toShort())
        }
        inputBuffer.flip()

        val handled = sink.handleBuffer(inputBuffer, 555L, 1)
        assertTrue(handled)
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer
        assertNotNull(delegateBuffer)
        // Output must be PCM16 (2 bytes per sample * 2 channels * 128 frames = 512 bytes)
        assertEquals(frameCount * 2 * 2, delegateBuffer!!.limit())
        delegateBuffer.position(0)
        assertEquals(16384, delegateBuffer.getShort().toInt())
        assertEquals(-16384, delegateBuffer.getShort().toInt())
    }

    @Test
    fun `handleBuffer converts PCM24 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 3).order(ByteOrder.LITTLE_ENDIAN)
        // 4194304 = 0x00400000 -> approx 0.5f in 24-bit
        for (i in 0 until frameCount) {
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x40.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0x00.toByte())
            inputBuffer.put(0xC0.toByte())
        }
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 100L, 1))
        assertFalse(inputBuffer.hasRemaining())

        val delegateBuffer = fakeDelegate.lastHandledBuffer!!
        assertEquals(frameCount * 2 * 2, delegateBuffer.limit())
        delegateBuffer.position(0)
        assertEquals(16384, delegateBuffer.getShort().toInt())
        assertEquals(-16384, delegateBuffer.getShort().toInt())
    }

    @Test
    fun `handleBuffer converts PCM32 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_32BIT)).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putInt(1073741824)
            inputBuffer.putInt(-1073741824)
        }
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 200L, 1))

        val delegateBuffer = fakeDelegate.lastHandledBuffer!!
        assertEquals(frameCount * 2 * 2, delegateBuffer.limit())
        delegateBuffer.position(0)
        assertEquals(16384, delegateBuffer.getShort().toInt())
        assertEquals(-16384, delegateBuffer.getShort().toInt())
    }

    @Test
    fun `handleBuffer converts Float32 in to Float32 DSP to PCM16 out`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_FLOAT)).build())

        val frameCount = 64
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount) {
            inputBuffer.putFloat(0.5f)
            inputBuffer.putFloat(-0.5f)
        }
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 300L, 1))

        val delegateBuffer = fakeDelegate.lastHandledBuffer!!
        assertEquals(frameCount * 2 * 2, delegateBuffer.limit())
        delegateBuffer.position(0)
        assertEquals(16384, delegateBuffer.getShort().toInt())
        assertEquals(-16384, delegateBuffer.getShort().toInt())
    }

    @Test
    fun `handleBuffer respects backpressure and reuses identical buffer instance with Float32 output`() {
        val fakeDelegate = FakeAudioSink()
        // Simulate delegate consuming only part of the buffer on first pass
        fakeDelegate.bytesToConsumePerCall = 256

        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val frameCount = 128 // 128 frames -> 1024 bytes float
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putShort(8000.toShort())
        }
        inputBuffer.flip()

        val result1 = sink.handleBuffer(inputBuffer, 1000L, 1)
        assertFalse("Must return false when delegate has backpressure", result1)
        val firstBufferRef = fakeDelegate.lastHandledBuffer
        assertNotNull(firstBufferRef)
        assertEquals(256, firstBufferRef!!.position())
        assertEquals(1024, firstBufferRef.limit())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        val result2 = sink.handleBuffer(inputBuffer, 1000L, 1)
        assertTrue("Must return true once delegate fully consumes", result2)
        val secondBufferRef = fakeDelegate.lastHandledBuffer

        // CRITICAL CONTRACT: Must be the EXACT same ByteBuffer reference to satisfy DefaultAudioSink checkArgument
        assertSame("Must reuse exact same ByteBuffer instance across backpressure ticks", firstBufferRef, secondBufferRef)
        assertEquals(1024, secondBufferRef!!.position())
    }

    @Test
    fun `handleBuffer respects backpressure and reuses identical buffer instance with PCM16 output`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.bytesToConsumePerCall = 100

        val sink = createSink(fakeDelegate, enableFloatOutput = false)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        val frameCount = 128 // 128 frames -> 512 bytes PCM16
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frameCount * 2) {
            inputBuffer.putShort(4000.toShort())
        }
        inputBuffer.flip()

        assertFalse(sink.handleBuffer(inputBuffer, 2000L, 1))
        val firstBufferRef = fakeDelegate.lastHandledBuffer
        assertEquals(100, firstBufferRef!!.position())
        assertEquals(512, firstBufferRef.limit())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        assertTrue(sink.handleBuffer(inputBuffer, 2000L, 1))
        val secondBufferRef = fakeDelegate.lastHandledBuffer

        assertSame(firstBufferRef, secondBufferRef)
        assertEquals(512, secondBufferRef!!.position())
    }

    @Test
    fun `handleBuffer in fallback mode forwards untouched to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44100)
            .build()
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())

        val inputBuffer = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.putInt(0x12345678)
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 5000L, 0))
        assertSame("Fallback mode forwards inputBuffer directly without copy", inputBuffer, fakeDelegate.lastHandledBuffer)
    }

    @Test
    fun `flush and reset clear state and propagate to delegate`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        sink.flush()
        assertTrue(fakeDelegate.flushed)
        assertTrue(sink.isPrecisionActive) // flush maintains configured format

        sink.reset()
        assertEquals(1, fakeDelegate.resetCount)
        assertFalse(sink.isPrecisionActive)
        assertNull(sink.inputPcmEncoding)
        assertNull(sink.targetOutputEncoding)
        assertNull(sink.activeFormat)
    }

    @Test
    fun `handleDiscontinuity forwards to delegate without resetting precision state`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        sink.handleDiscontinuity()
        assertTrue(fakeDelegate.discontinuityHandled)
        assertTrue(sink.isPrecisionActive)
    }

    @Test
    fun `getFormatSupport reports SINK_FORMAT_SUPPORTED_DIRECTLY for all linear PCM encodings with float output enabled`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val sink = createSink(fakeDelegate, enableFloatOutput = true)

        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) {
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(rawFormat(encoding)))
        }
    }

    /**
     * With float output off, every *integer* PCM input is still consumed natively, but a float input is
     * not, because the track it ends up on will be 16-bit — and `MediaCodecAudioRenderer` asks this exact
     * question to decide whether a decoder is told to emit float.
     */
    @Test
    fun `getFormatSupport with float output disabled reports integer PCM directly and float as transcoding`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)))
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT)) {
            assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(rawFormat(encoding)))
        }
    }

    @Test
    fun `supportsFormat returns true when precision path can transcode to supported output`() {
        val fakeDelegate = FakeAudioSink()
        // Delegate only supports PCM16
        fakeDelegate.supportsFormatPredicate = { format -> format.pcmEncoding == C.ENCODING_PCM_16BIT }
        val sink = createSink(fakeDelegate, enableFloatOutput = false)

        assertTrue(sink.supportsFormat(rawFormat(C.ENCODING_PCM_24BIT)))
        assertTrue(sink.supportsFormat(rawFormat(C.ENCODING_PCM_FLOAT)))
    }

    @Test
    fun `large input buffer processed in chunks`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        // 8192 frames > DEFAULT_CAPACITY_FRAMES (4096)
        val totalFrames = 8192
        val inputBuffer = ByteBuffer.allocateDirect(totalFrames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalFrames * 2) {
            inputBuffer.putShort(1000.toShort())
        }
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 0L, 1))
        assertFalse(inputBuffer.hasRemaining())
        assertEquals(2, fakeDelegate.handleBufferCallCount)
    }

    /** The equaliser reaches float audio too, which Media3's own sink would have taken past it. */
    @Test
    fun `the equaliser shapes float audio`() {
        val equaliser = EqualiserProcessor().apply {
            // A peak well above DC: what's left at DC is the curve's headroom, 6 dB down.
            update(EqProfile("t", "t", EqKind.Tuning, listOf(EqFilter(EqShape.Peak, 1_000f, 6f, 1.41f))))
        }
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true, dspChain = idleDspChain(equaliser = equaliser))
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_FLOAT)).build())

        val frameCount = 4096
        val inputBuffer = ByteBuffer.allocateDirect(frameCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frameCount * 2) { inputBuffer.putFloat(0.25f) }
        inputBuffer.flip()

        sink.handleBuffer(inputBuffer, 0L, 1)

        val delegateBuffer = fakeDelegate.lastHandledBuffer!!.order(ByteOrder.LITTLE_ENDIAN)
        // Steady state, long after the 30 ms swap has finished.
        delegateBuffer.position((frameCount - 1) * 2 * 4)
        assertEquals(0.125f, delegateBuffer.getFloat(), 0.01f)
        assertEquals(0.125f, delegateBuffer.getFloat(), 0.01f)
    }

    @Test
    fun `playToEndOfStream, isEnded, and hasPendingData reflect pending buffer state`() {
        val fakeDelegate = FakeAudioSink()
        fakeDelegate.bytesToConsumePerCall = 100 // partial consume

        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_FLOAT)).build())

        val inputBuffer = ByteBuffer.allocateDirect(1000).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 250) inputBuffer.putFloat(0.1f)
        inputBuffer.flip()

        sink.handleBuffer(inputBuffer, 0L, 1)

        assertTrue("Sink must report pending data when output buffer has remaining bytes", sink.hasPendingData())
        assertFalse("Sink cannot report ended when output buffer has remaining bytes", sink.isEnded())

        fakeDelegate.bytesToConsumePerCall = Int.MAX_VALUE
        sink.playToEndOfStream()
        assertTrue(fakeDelegate.playedToEndOfStream)
    }

    @Test
    fun `trailing incomplete frame bytes are dropped without hanging`() {
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = true)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())

        // 1 full frame (4 bytes) + 2 extra bytes (incomplete frame) = 6 bytes
        val inputBuffer = ByteBuffer.allocateDirect(6).order(ByteOrder.LITTLE_ENDIAN)
        inputBuffer.putShort(100)
        inputBuffer.putShort(200)
        inputBuffer.put(0x12)
        inputBuffer.put(0x34)
        inputBuffer.flip()

        assertTrue(sink.handleBuffer(inputBuffer, 0L, 1))
        assertFalse("Trailing incomplete frame bytes must be consumed so buffer finishes", inputBuffer.hasRemaining())
    }

    /** A 24-bit preference is read as "more than 16 bits, please" and answered with float. */
    @Test
    fun `a 24-bit preference is honoured as float, since Media3 has no 24-bit track`() {
        val fakeDelegate = FakeAudioSink()
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = idleDspChain(),
            enableFloatOutput = { true },
            preferredOutputEncodingProvider = { PcmEncoding.PCM_24BIT_PACKED },
        )
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_FLOAT)).build())

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_FLOAT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }

    /** ...and falls back to 16-bit, not 24-bit, when the route has no float track. */
    @Test
    fun `a 24-bit preference falls back to 16-bit without a float track`() {
        val fakeDelegate = FakeAudioSink().apply {
            formatSupportReturn = AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = idleDspChain(),
            enableFloatOutput = { true },
            preferredOutputEncodingProvider = { PcmEncoding.PCM_24BIT_PACKED },
        )
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())

        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }

    @Test
    fun `preferredOutputEncodingProvider directs target encoding to 16-bit PCM on phone speaker`() {
        val fakeDelegate = FakeAudioSink()
        val sink = PrecisionAudioSink(
            delegate = fakeDelegate,
            dspChain = idleDspChain(),
            enableFloatOutput = { true },
            preferredOutputEncodingProvider = { PcmEncoding.PCM_16BIT },
        )
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_FLOAT)).build())

        assertTrue(sink.isPrecisionActive)
        assertEquals(PcmEncoding.PCM_FLOAT, sink.inputPcmEncoding)
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)
        assertEquals(C.ENCODING_PCM_16BIT, fakeDelegate.configuredConfig?.format?.pcmEncoding)
    }

    // ---- AETHER's ------------------------------------------------------------------------------

    /** The route is asked each time, not once: plugging a DAC in or out changes the next song's track. */
    @Test
    fun `the float decision follows the route as it is now`() {
        var floatRoute = false
        val fakeDelegate = FakeAudioSink()
        val sink = PrecisionAudioSink(fakeDelegate, idleDspChain(), enableFloatOutput = { floatRoute })
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING, sink.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)))
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
        assertEquals(PcmEncoding.PCM_16BIT, sink.targetOutputEncoding)

        floatRoute = true
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(rawFormat(C.ENCODING_PCM_FLOAT)))
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_24BIT)).build())
        assertEquals(PcmEncoding.PCM_FLOAT, sink.targetOutputEncoding)
    }

    /** The meter stamps what it hears with its place in the song, as Media3 told it in its own chain. */
    @Test
    fun `the meter hears the song at the song's own position`() {
        val meter = AudioMeter()
        val fakeDelegate = FakeAudioSink()
        val sink = createSink(fakeDelegate, enableFloatOutput = false, dspChain = idleDspChain(meter = meter))
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())
        // The second song of a gapless run: its stream starts 1 s into the renderer's timeline.
        sink.setOutputStreamOffsetUs(1_000_000)
        val listening = meter.listen()
        val frames = 4_800
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            input.putShort(16384.toShort())
            input.putShort(16384.toShort())
        }
        input.flip()
        // 5 s into the song.
        sink.handleBuffer(input, 6_000_000, 1)
        assertTrue(abs(meter.levelAt(5_050) - 0.5f) < 0.01f)
        assertEquals(-1f, meter.levelAt(6_050))
        listening.close()
    }

    /** With the sink in place, the loop mix (BitChord's, ported with Automix) has the side it needs. */
    @Test
    fun `a stereo song makes the loop mix possible`() {
        val chain = idleDspChain()
        val sink = createSink(FakeAudioSink(), dspChain = chain)
        assertFalse(chain.transition.loop.supportsLoopMix)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())
        assertTrue(chain.transition.loop.supportsLoopMix)
    }

    /** How far the chain runs ahead of what's heard: Automix's filters are timed by it. */
    @Test
    fun `the lead is what the chain has processed past the position`() {
        val chain = idleDspChain()
        val fakeDelegate = FakeAudioSink().apply { positionUs = 100_000L }
        val sink = createSink(fakeDelegate, enableFloatOutput = false, dspChain = chain)
        sink.configure(AudioSink.AudioSinkConfig.Builder(rawFormat(C.ENCODING_PCM_16BIT)).build())
        val frames = 4_800
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * 2) { input.putShort(0) }
        input.flip()
        sink.handleBuffer(input, 0L, 1)
        sink.getCurrentPositionUs(false)
        // 100 ms processed against 100 ms heard... and the next 100 ms is 100 ms ahead.
        assertEquals(0L, chain.transition.leadUs)
        input.clear()
        repeat(frames * 2) { input.putShort(0) }
        input.flip()
        sink.handleBuffer(input, 100_000L, 1)
        sink.getCurrentPositionUs(false)
        // Each sub-block is timed on its own (4096 frames, then 704), so the sum rounds down a microsecond.
        assertEquals(100_000.0, chain.transition.leadUs.toDouble(), 2.0)
    }
}
