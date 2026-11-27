@file:OptIn(UnstableApi::class)

package app.aether.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.aether.core.model.EqMath
import app.aether.core.model.EqProfile
import app.aether.player.audio.DspChain
import app.aether.player.audio.OutputRoute
import app.aether.player.audio.PrecisionAudioSink
import app.aether.player.audio.bitperfect.BitPerfectAudioSink
import app.aether.player.audio.bitperfect.BitPerfectOutputs
import app.aether.player.audio.bitperfect.BitPerfectRoutingSink
import app.aether.player.audio.flacSafeCodecs
import app.aether.player.automix.AudioBlock
import app.aether.player.automix.FloatAudioProcessor
import org.nift4.alacdecoder.AlacRenderer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

/** A change of tuning is crossfaded over this long, so that it never clicks. */
private const val SwapSeconds = 0.03

/**
 * The standard renderers, with AETHER's sound path in their sink:
 *
 * - FLAC is decoded by a software decoder, Android's own first, and never by Samsung's, which garbles
 *   16-bit FLAC when asked for float (BitChord's selector, [flacSafeCodecs]).
 * - Apple Lossless by Gramophone's decoder ([AlacRenderer]), ahead of any the phone has: Samsung's
 *   phones have none, and BitChord found Qualcomm's unreliable.
 * - Every decoded format goes through [dsp] in float (BitChord's [PrecisionAudioSink]): the equaliser,
 *   Automix's filters, ReplayGain and the meter run on 24-bit and float audio too, where Media3's own
 *   sink would skip them. What leaves is float where [output] says the route takes it, 16-bit where it
 *   doesn't (the phone's own speaker, BitChord's rule).
 * - With [bitPerfect], a lossless song a USB DAC can take as it is goes to it untouched, at its own
 *   rate and depth, past all of the above (BitChord's bit-perfect output, the listener's choice).
 */
internal class ShapedRenderers(
    context: Context,
    private val dsp: DspChain,
    private val output: OutputRoute,
    private val bitPerfect: BitPerfectOutputs? = null,
) : DefaultRenderersFactory(context) {
    init {
        setMediaCodecSelector(flacSafeCodecs())
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // First, so an ALAC track is never handed to a platform decoder (Gramophone's order).
        out.add(AlacRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
        val media3 = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            // Speed is the AudioTrack's own (BitChord's choice too). Done by Media3's time-stretcher
            // instead, every change of speed drains and restarts it: a click each time, and Automix
            // changes the speed many times a mix (matching tempi, holding the beats in phase). On a float
            // route Media3's stretcher isn't in the path at all, so only this way does the speed change there.
            .setEnableAudioOutputPlaybackParameters(true)
            // Sized for the speeds AETHER plays at, not Media3's 8x: the buffer stays short, so what the
            // equaliser and Automix do is heard soon after it's done.
            .setAudioTrackBufferSizeProvider(SpeedSizedBuffer)
            .build()
        val precision = PrecisionAudioSink(
            delegate = media3,
            dspChain = dsp,
            enableFloatOutput = { enableFloatOutput && output.floatOutput() },
            preferredOutputEncodingProvider = output::preferredEncoding,
        )
        return bitPerfect?.let { BitPerfectRoutingSink(precision, BitPerfectAudioSink(it)) } ?: precision
    }
}

/** The AudioTrack's buffer as Media3 sizes it, for speeds up to [MaxSpeed] rather than its 8x. */
@OptIn(UnstableApi::class)
private object SpeedSizedBuffer : DefaultAudioSink.AudioTrackBufferSizeProvider {
    private val base = androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder().build()

    /** Automix's fastest (a quarter up, and its phase lock's nudge), and crossfade's catching up. */
    private const val MaxSpeed = 1.5

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int = base.getBufferSizeInBytes(minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, minOf(maxAudioTrackPlaybackSpeed, MaxSpeed))
}

/**
 * The equaliser, in the decoded audio before it reaches Android's mixer (after PODIUM's
 * `EqualizerProcessor`, GPL-3.0): the same on every phone, local or online, and on every format,
 * since it works on [PrecisionAudioSink]'s float blocks.
 *
 * - With nothing to do, the audio passes through untouched, sample for sample.
 * - Everything is lowered first by the curve's highest point, so a boost can't clip.
 * - Any file's filters can be asked for; each is an ordinary stable biquad.
 */
internal class EqualiserProcessor : FloatAudioProcessor {
    private class Swap(val rate: Int, val channels: Int, val to: Filters?)

    @Volatile private var profile: EqProfile? = null
    @Volatile private var rate = 0
    @Volatile private var channels = 0
    private val pending = AtomicReference<Swap?>(null)
    private var live: Filters? = null
    private var leaving: Filters? = null
    private var fadeLeft = 0
    private var fadeFrames = 1

    /** Whether the samples are being changed. */
    val shaping: Boolean get() = profile != null

    /** From any thread; the next block starts going over to [next]. */
    fun update(next: EqProfile?) {
        val audible = next?.takeIf { it.shaping }
        if (audible == profile) return
        profile = audible
        val r = rate
        val ch = channels
        if (r > 0) pending.set(Swap(r, ch, audible?.let { Filters(it, r, ch) }))
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        // The sink configures every song, gapless ones too: the same format keeps its filters and
        // their memory, so an album's songs run into each other without a seam.
        if (sampleRate == rate && channelCount == channels) return
        rate = sampleRate
        channels = channelCount
        fadeFrames = (sampleRate * SwapSeconds).toInt().coerceAtLeast(1)
        pending.set(null)
        live = profile?.let { Filters(it, sampleRate, channelCount) }
        leaving = null
        fadeLeft = 0
    }

    override fun process(block: AudioBlock) {
        val frames = block.frameCount
        if (frames == 0) return
        pending.getAndSet(null)?.let { swap ->
            if (swap.rate == rate && swap.channels == channels) {
                leaving = live
                live = swap.to
                fadeLeft = fadeFrames
            }
        }
        val now = live
        val old = leaving
        if (now == null && old == null) return
        val samples = block.samples
        val count = block.channelCount
        var i = 0
        for (frame in 0 until frames) {
            val fading = fadeLeft > 0
            val mix = if (fading) 1f - fadeLeft.toFloat() / fadeFrames else 1f
            for (ch in 0 until count) {
                val x = samples[i]
                val shaped = now?.process(ch, x) ?: x
                samples[i] = if (fading) {
                    val was = old?.process(ch, x) ?: x
                    was + (shaped - was) * mix
                } else shaped
                i++
            }
            if (fading && --fadeLeft == 0) leaving = null
        }
    }

    override fun flush() {
        live?.clear()
        leaving = null
        fadeLeft = 0
    }

    override fun reset() {
        pending.set(null)
        live = null
        leaving = null
        fadeLeft = 0
        rate = 0
        channels = 0
    }

    /** One profile's filters at one sample rate, with each channel's memory (transposed direct form II). */
    private class Filters(profile: EqProfile, rate: Int, channels: Int) {
        private val b0: DoubleArray
        private val b1: DoubleArray
        private val b2: DoubleArray
        private val a1: DoubleArray
        private val a2: DoubleArray
        private val headroom: Float
        private val bands: Int
        private val z1: DoubleArray
        private val z2: DoubleArray

        init {
            val fs = rate.toDouble()
            val audible = profile.filters.filter { it.hz < fs * 0.45 }
            val c = audible.map { EqMath.coefficients(it, fs) }
            bands = c.size
            b0 = DoubleArray(bands) { c[it][0] }
            b1 = DoubleArray(bands) { c[it][1] }
            b2 = DoubleArray(bands) { c[it][2] }
            a1 = DoubleArray(bands) { c[it][3] }
            a2 = DoubleArray(bands) { c[it][4] }
            headroom = 10.0.pow(-EqMath.peakDb(audible, fs).coerceAtLeast(0.0) / 20).toFloat()
            z1 = DoubleArray(bands * channels)
            z2 = DoubleArray(bands * channels)
        }

        fun process(channel: Int, sample: Float): Float {
            var x = (sample * headroom).toDouble()
            val base = channel * bands
            for (b in 0 until bands) {
                val i = base + b
                val y = b0[b] * x + z1[i]
                z1[i] = b1[b] * x - a1[b] * y + z2[i]
                z2[i] = b2[b] * x - a2[b] * y
                x = y
            }
            return x.toFloat()
        }

        fun clear() {
            z1.fill(0.0)
            z2.fill(0.0)
        }
    }
}
