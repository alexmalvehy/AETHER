// AETHER: BitChord's Automix (app/src/main/java/com/music/bitchord/playback/TransitionFilterProcessor.kt, github.com/kushagrasinghx/BitChord),
// ported with its notices below; only its package and its ties to BitChord's app are changed.
package app.aether.player.automix

import app.aether.automix.*

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * The filter a track rides through a Automix transition: a low-pass that can
 * close over the outgoing track, and a high-pass that can lift the low end out
 * of one side of a blend.
 *
 * ## The filter
 *
 * A topology-preserving (trapezoidal-integrator) state-variable filter, two
 * second-order sections cascaded to a 24 dB/octave Butterworth response.
 */
@UnstableApi
class TransitionFilterProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetLowPassHz: Float = OPEN_HZ

    @Volatile
    private var targetHighPassHz: Float = OFF_HZ

    private var channelCount = 0
    private var sampleRate = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)

    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)

    /** Whether the integrators hold anything since they were last cleared. */
    private var filterStateDirty = false

    /**
     * How far ahead of the speaker this filter is running, in media
     * microseconds; written by the sink, 0 when it has nothing to say.
     *
     * The filter runs where audio is *written*, not where it is heard, and on a
     * float route the output buffer behind it holds about four seconds — Media3
     * sizes it for 8x playback once the AudioTrack is doing the speed. A cutoff
     * set now is therefore heard seconds from now, while a fader set now is
     * heard at once. [CrossfadeController] reads this to aim each filter at the
     * point in the blend its audio will actually be played.
     */
    @Volatile
    var leadUs: Long = 0L

    /**
     * Where in its song the audio this filter last processed is, in media microseconds, or
     * [C.TIME_UNSET] before it knows: Media3 tells each processor where its stream starts
     * ([onFlush]), and the frames since are counted. Read against what the player says is being
     * heard, the difference is [leadUs] (AETHER: BitChord's own sink wrote that; Media3's says
     * enough to work it out).
     */
    @Volatile
    var processedUs: Long = C.TIME_UNSET
        private set

    private var streamStartUs = C.TIME_UNSET
    private var framesSinceFlush = 0L

    /**
     * This sink's side of the Automix loop mix. Carried here, on the processor
     * that already trades roles with its player at every handoff, so the
     * controller reaches the right sink by the same route as the filters.
     */
    val loop = LoopMixPort()

    // ---- Echo out ------------------------------------------------------------
    //
    // A feedback delay after the filters, for Advanced Automix's echo out: the
    // outgoing track's last beat keeps repeating, darker and thinner each
    // time, after its dry signal is gone. Everything here is idle — not one
    // sample touched — until [setEcho] engages it, and the ring is only ever
    // allocated at the size a transition actually asks for.

    /** Written by the controller; read once per block on the audio thread. */
    @Volatile private var echoEngaged = false
    @Volatile private var echoTargetSend = 0f
    @Volatile private var echoTargetDry = 1f
    @Volatile private var echoSeconds = 0f

    /** Bumped for every new echo; the audio thread clears its ring when it sees a new one. */
    @Volatile private var echoGeneration = 0

    /** A ring allocated off the audio thread, waiting to be adopted by it. */
    @Volatile private var pendingEchoRing: FloatArray? = null

    /** The format as last configured, readable from the controller's thread for sizing the ring. */
    @Volatile private var echoFormatRate = 0
    @Volatile private var echoFormatChannels = 0

    private var echoRing = FloatArray(0)
    private var echoSeenGeneration = 0
    private var echoDelayFrames = 0
    private var echoPosition = 0
    private var echoSend = 0f
    private var echoDry = 1f
    private var echoLow = FloatArray(0)
    private var echoHigh = FloatArray(0)
    private var echoLowCoefficient = 0f
    private var echoHighCoefficient = 0f

    /**
     * Aims the filter. [lowPassHz] at or above [OPEN_HZ] and [highPassHz] at or
     * below [OFF_HZ] mean "not filtering", which is the state this returns to
     * between transitions.
     */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** Parks both filters. Glided, not snapped. */
    fun open() = setCutoffs(OPEN_HZ, OFF_HZ)

    @Volatile private var trimTarget = 1f
    private var currentTrim = 1f
    private var trimGlide = 1f

    /** The peak catcher's gain now (1 is letting everything by), and how fast it lets go. */
    private var reduction = 1f
    private var releaseCoefficient = 1f

    /**
     * Headroom taken off this track while it overlaps another: 1 between transitions, a little
     * lower during one (BitChord's `LoudnessProcessor.setBlendTrim`). Two songs faded equal-power
     * sum to more than either alone, up to 1.41 at the midpoint; a smooth `1 / sqrt(in + out)`
     * costs at most 1.5 dB there and changes no waveform's shape. Glided on the audio thread.
     *
     * AETHER adds a peak catcher under it. That trim still lets the two songs' peaks sum to 1.19, and
     * with the beats matched their kicks land together, every beat: past full scale, which the mixer
     * clips, heard as crackling. With the faders at `in` and `out` and this trim at `t = 1/sqrt(in + out)`,
     * each song's peaks are held to `t` before it's applied, so both together can never pass full
     * scale. Only the top 1.5 dB of a song's peaks are ever touched, and only mid-blend: instant to
     * catch a peak, letting go over 80 ms (BitChord's own peak catcher's times).
     */
    fun setBlendTrim(value: Float) {
        trimTarget = value.coerceIn(MIN_TRIM, 1f)
    }

    val isFiltering: Boolean
        get() = targetLowPassHz < OPEN_HZ || targetHighPassHz > OFF_HZ ||
            currentLowPassHz < OPEN_HZ - SETTLED_HZ || currentHighPassHz > OFF_HZ + SETTLED_HZ

    /**
     * Engages the echo out, or moves it: [delaySeconds] between repeats, [send]
     * how much of the signal is fed into the delay, [dry] how much of it is
     * passed through. Both glide across the next block rather than stepping.
     *
     * A new [delaySeconds] starts a new echo. The ring for it is allocated
     * here, on the caller's thread, and only when the one already held is too
     * small — so the audio thread never allocates, and a phone that never
     * hears an echo out never holds one.
     */
    fun setEcho(delaySeconds: Float, send: Float, dry: Float) {
        if (delaySeconds != echoSeconds || !echoEngaged) {
            val frames = (delaySeconds.coerceIn(0f, MAX_ECHO_SECONDS) * echoFormatRate).toInt()
            val needed = frames * echoFormatChannels
            if (needed > echoRing.size) pendingEchoRing = FloatArray(needed)
            echoSeconds = delaySeconds
            echoGeneration++
        }
        echoTargetSend = send.coerceIn(0f, 1f)
        echoTargetDry = dry.coerceIn(0f, 1f)
        echoEngaged = true
    }

    /**
     * Drops the echo and puts the dry signal back. For the end of a
     * transition, when the track it belonged to is gone or going: a tail
     * still ringing is cut, not faded.
     */
    fun parkEcho() {
        echoEngaged = false
        echoTargetSend = 0f
        echoTargetDry = 1f
    }

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        val requiredSize = channelCount * STAGES * 2
        if (lowState.size != requiredSize) {
            lowState = FloatArray(requiredSize)
            highState = FloatArray(requiredSize)
        }
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        if (echoLow.size != channelCount) {
            echoLow = FloatArray(channelCount)
            echoHigh = FloatArray(channelCount)
        }
        trimGlide = if (sampleRate <= 0) 1f else (1.0 - exp(-1.0 / (TRIM_GLIDE_SECONDS * sampleRate))).toFloat()
        releaseCoefficient = if (sampleRate <= 0) 1f else (1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))).toFloat()
        echoLowCoefficient = onePole(ECHO_LOW_PASS_HZ, sampleRate)
        echoHighCoefficient = onePole(ECHO_HIGH_PASS_HZ, sampleRate)
        // A new format invalidates the ring's length in frames.
        echoFormatRate = sampleRate
        echoFormatChannels = channelCount
        echoSeenGeneration = echoGeneration - 1
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: AudioBlock) {
        val frameCount = block.frameCount
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return
        filter(block, frameCount)
        if (echoEngaged) echo(block, frameCount)
    }

    private fun filter(block: AudioBlock, frameCount: Int) {
        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz
        val parked = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        if (parked) {
            clearStateIfNeeded()
            return
        }
        filterStateDirty = true

        var remaining = frameCount
        var frameOffset = 0
        while (remaining > 0) {
            val subBlock = min(remaining, GLIDE_FRAMES)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            val lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
            val highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
            if (lowOn) updateLowCoefficients()
            if (highOn) updateHighCoefficients()

            for (f in 0 until subBlock) {
                val baseIdx = (frameOffset + f) * channelCount
                for (channel in 0 until channelCount) {
                    var sample = block.samples[baseIdx + channel]
                    if (lowOn) sample = lowPass(channel, sample)
                    if (highOn) sample = highPass(channel, sample)
                    // Headroom preserved: no clamping to [-1.0f, +1.0f]
                    block.samples[baseIdx + channel] = sample
                }
            }
            frameOffset += subBlock
            remaining -= subBlock
        }
    }

    /**
     * 16-bit and float PCM, whichever the decoder gives (AETHER's sink takes both): BitChord ran this
     * path on 16-bit only and its echo only in its own float sink; here both encodings go through
     * [process] whole, filters and echo, through an [AudioBlock].
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if ((encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Transition filtering inactive: encoding=$encoding " +
                    "channels=${inputAudioFormat.channelCount} is neither 16-bit nor float PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        this.encoding = encoding
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        if (work.channelCount != inputAudioFormat.channelCount) work = AudioBlock(inputAudioFormat.channelCount, WORK_FRAMES)
        return inputAudioFormat
    }

    /** The encoding as configured, and the block a buffer is processed in. */
    private var encoding = C.ENCODING_INVALID
    private var work = AudioBlock(AudioBlock.DEFAULT_CHANNELS, WORK_FRAMES)

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val was = processedUs
        streamStartUs = streamMetadata.positionOffsetUs
        framesSinceFlush = 0L
        processedUs = C.TIME_UNSET
        // The stream carrying straight on from where it was (the sink restarting its steps mid-song)
        // isn't a seek: the filters, the echo and the trim stay where they are, or the music clicks.
        if (was != C.TIME_UNSET && abs(streamMetadata.positionOffsetUs - was) <= CONTIGUOUS_US) return
        onFlush()
    }

    override fun onFlush() {
        lowState.fill(0f)
        highState.fill(0f)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        // Whatever the ring holds is from before the seek; start it clean.
        echoSeenGeneration = echoGeneration - 1
    }

    override fun onReset() {
        parkEcho()
        echoSeenGeneration = echoGeneration - 1
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        lowState = FloatArray(0)
        highState = FloatArray(0)
        channelCount = 0
        sampleRate = 0
        encoding = C.ENCODING_INVALID
        streamStartUs = C.TIME_UNSET
        framesSinceFlush = 0L
        processedUs = C.TIME_UNSET
        currentTrim = trimTarget
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val float = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = (if (float) 4 else BYTES_PER_SAMPLE) * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)
        framesSinceFlush += frameCount
        if (streamStartUs != C.TIME_UNSET && sampleRate > 0) {
            processedUs = streamStartUs + framesSinceFlush * 1_000_000L / sampleRate
        }

        // Nothing audible to do: every byte passes through as it came, so a song between mixes is
        // untouched (and a route that reports bit-perfect stays truthful).
        val parked = targetLowPassHz >= OPEN_HZ && targetHighPassHz <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        val trimParked = trimTarget >= 1f && currentTrim >= 1f - TRIM_SETTLED && reduction >= PARK_REDUCTION
        if (parked && !echoEngaged && trimParked) {
            currentTrim = 1f
            reduction = 1f
            clearStateIfNeeded()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())
        val block = work
        val capacity = block.samples.size / channelCount
        var remaining = frameCount
        while (remaining > 0) {
            val frames = min(remaining, capacity)
            val samples = block.samples
            val count = frames * channelCount
            if (float) {
                for (i in 0 until count) samples[i] = inputBuffer.float
            } else {
                for (i in 0 until count) samples[i] = inputBuffer.short.toFloat() / 32768f
            }
            block.setFrameCount(frames)
            process(block)
            trim(samples, frames)
            if (float) {
                for (i in 0 until count) outputBuffer.putFloat(samples[i])
            } else {
                for (i in 0 until count) outputBuffer.putShort(PcmBoundary.clamp16FromFloat(samples[i]))
            }
            remaining -= frames
        }
        outputBuffer.flip()
    }

    /**
     * Forgets the integrators once the filter parks.
     *
     * Parking skips the filter without running it, so whatever the integrators
     * held when the last transition ended would otherwise sit there until the
     * next one — minutes later — and be the first thing that transition's
     * filter output: a step of stale signal at the head of the blend, heard as
     * a click. Zeroed state is a filter at rest, which is what an unused one is.
     */
    /**
     * The blend trim over [block], for the sink's float chain (AETHER's `DspChain`), which runs it after
     * [process] as [queueInput] does: in BitChord it is its loudness stage's, the last of the chain.
     */
    fun trim(block: AudioBlock) {
        if (block.frameCount == 0 || channelCount < 1) return
        trim(block.samples, block.frameCount)
    }

    /**
     * The blend trim over [frames] frames of [samples], glided frame by frame so it never zippers, with
     * the peak catcher under it (see [setBlendTrim]): a peak above the trim is brought down to it.
     */
    private fun trim(samples: FloatArray, frames: Int) {
        val target = trimTarget
        var trim = currentTrim
        var gr = reduction
        if (target >= 1f && trim >= 1f - TRIM_SETTLED && gr >= PARK_REDUCTION) {
            currentTrim = 1f
            reduction = 1f
            return
        }
        val channels = channelCount
        val release = releaseCoefficient
        var index = 0
        for (frame in 0 until frames) {
            trim += (target - trim) * trimGlide
            var peak = 0f
            for (channel in 0 until channels) {
                val magnitude = abs(samples[index + channel])
                if (magnitude > peak) peak = magnitude
            }
            val wanted = if (peak > trim) trim / peak else 1f
            gr = if (wanted < gr) wanted else gr + (wanted - gr) * release
            val scale = trim * gr
            for (channel in 0 until channels) samples[index + channel] *= scale
            index += channels
        }
        currentTrim = trim
        reduction = gr
    }

    private fun clearStateIfNeeded() {
        if (!filterStateDirty) return
        lowState.fill(0f)
        highState.fill(0f)
        filterStateDirty = false
    }

    // ---- Echo ----------------------------------------------------------------

    /**
     * Runs the echo over [block] in place: the input goes out at [echoDry] and
     * into the ring at [echoSend], and what comes back round is added at
     * [ECHO_WET] and fed in again at [ECHO_FEEDBACK] through a one-pole
     * low-pass and high-pass. Those two are what make each repeat darker and
     * thinner than the last — a tape echo, not a copy machine — and keep the
     * low end from piling up in the loop.
     *
     * One read, one write and two one-pole filters per sample, with the send
     * and dry gains ramped linearly across the block so a 30ms control tick is
     * never heard as a step.
     */
    private fun echo(block: AudioBlock, frameCount: Int) {
        if (echoSeenGeneration != echoGeneration) startEcho()
        val channels = channelCount
        val delay = echoDelayFrames
        val ring = echoRing
        val samples = block.samples
        val targetSend = echoTargetSend
        val targetDry = echoTargetDry
        val sendStep = (targetSend - echoSend) / frameCount
        val dryStep = (targetDry - echoDry) / frameCount
        var send = echoSend
        var dry = echoDry
        if (delay <= 0) {
            // No ring to run: honour the dry gain alone, so the track still leaves.
            for (f in 0 until frameCount) {
                dry += dryStep
                val base = f * channels
                for (c in 0 until channels) samples[base + c] *= dry
            }
            echoSend = targetSend
            echoDry = targetDry
            return
        }
        val lowCoefficient = echoLowCoefficient
        val highCoefficient = echoHighCoefficient
        val low = echoLow
        val high = echoHigh
        var position = echoPosition
        for (f in 0 until frameCount) {
            send += sendStep
            dry += dryStep
            val base = f * channels
            val ringBase = position * channels
            for (c in 0 until channels) {
                val input = samples[base + c]
                val delayed = ring[ringBase + c]
                val darker = low[c] + lowCoefficient * (delayed - low[c])
                low[c] = darker
                val rumble = high[c] + highCoefficient * (darker - high[c])
                high[c] = rumble
                ring[ringBase + c] = input * send + (darker - rumble) * ECHO_FEEDBACK
                samples[base + c] = input * dry + delayed * ECHO_WET
            }
            if (++position == delay) position = 0
        }
        echoPosition = position
        echoSend = targetSend
        echoDry = targetDry
    }

    /** A new echo, on the audio thread: adopt any ring waiting for it and clear what it will use. */
    private fun startEcho() {
        echoSeenGeneration = echoGeneration
        pendingEchoRing?.let {
            echoRing = it
            pendingEchoRing = null
        }
        val channels = channelCount.coerceAtLeast(1)
        echoDelayFrames = (echoSeconds * sampleRate).toInt().coerceIn(0, echoRing.size / channels)
        echoRing.fill(0f, 0, echoDelayFrames * channels)
        echoLow.fill(0f)
        echoHigh.fill(0f)
        echoPosition = 0
        echoSend = 0f
        echoDry = 1f
    }

    // ---- Filter ------------------------------------------------------------

    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun usableCutoff(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_CUTOFF_FRACTION)

    private fun updateLowCoefficients() {
        if (sampleRate <= 0) return
        val g = tan(Math.PI * usableCutoff(currentLowPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            lowA1[stage] = a1
            lowA2[stage] = g * a1
            lowA3[stage] = g * (g * a1)
        }
    }

    private fun updateHighCoefficients() {
        if (sampleRate <= 0) return
        val g = tan(Math.PI * usableCutoff(currentHighPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            highA1[stage] = a1
            highA2[stage] = g * a1
            highA3[stage] = g * (g * a1)
            highK[stage] = k
        }
    }

    private fun lowPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = lowState[i]
            val ic2 = lowState[i + 1]
            val v3 = value - ic2
            val v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            val v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2f * v1 - ic1
            lowState[i + 1] = 2f * v2 - ic2
            value = v2
        }
        return value
    }

    private fun highPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = highState[i]
            val ic2 = highState[i + 1]
            val v3 = value - ic2
            val v1 = highA1[stage] * ic1 + highA2[stage] * v3
            val v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2f * v1 - ic1
            highState[i + 1] = 2f * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    companion object {
        /** Frames processed at once, a buffer at a time. */
        private const val WORK_FRAMES = 4096

        /** Never let a trim take more than 6 dB off a track (BitChord's LoudnessProcessor). */
        private const val MIN_TRIM = 0.5f

        /** The trim's time constant: long enough not to zipper. */
        private const val TRIM_GLIDE_SECONDS = 0.04
        private const val TRIM_SETTLED = 1e-4f

        /** How quickly the peak catcher lets go after a peak (BitChord's LoudnessProcessor). */
        private const val RELEASE_SECONDS = 0.08

        /** Little enough reduction left (about 0.25 dB) to drop it outright when parking. */
        private const val PARK_REDUCTION = 0.97f

        /** A flush this close to where the last audio ended is the same stream carrying on. */
        private const val CONTIGUOUS_US = 50_000L

        private const val TAG = "AetherTransitionFilter"

        /** A low-pass at or above this is doing nothing audible, so it counts as off. */
        const val OPEN_HZ = 20_000f

        /** A high-pass at or below this is doing nothing audible, so it counts as off. */
        const val OFF_HZ = 20f

        /** Nothing musical wants the low end lifted above this, and a typo shouldn't be able to. */
        const val MAX_HIGH_PASS_HZ = 2_000f

        private const val MIN_HZ = 10f
        private const val BYTES_PER_SAMPLE = 2

        /** Two cascaded second-order sections: 24 dB/octave, the usual DJ-filter slope. */
        private const val STAGES = 2

        /** Section Qs for a maximally flat (Butterworth) fourth-order response. */
        private val BUTTERWORTH_Q = floatArrayOf(0.54120f, 1.30656f)

        /** Frames between coefficient updates. ~1.5 ms at 44.1 kHz. */
        private const val GLIDE_FRAMES = 64

        /** Per-sub-block glide fraction. ~30 ms time constant, just under one fade tick. */
        private const val GLIDE_RATE = 0.05f

        /** How close to a parked value counts as parked, so a glide terminates. */
        private const val SETTLED_HZ = 1f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_CUTOFF_FRACTION = 0.45f

        /**
         * Echo feedback: each repeat at 40% of the one before, so
         * [com.music.bitchord.playback.smart.ECHO_REPEATS] of them take it to
         * about -40 dB — the tail the planner reserves room for. Kept short on
         * purpose: a long run of repeats over the incoming song was heard as
         * the outgoing one glitching.
         */
        private const val ECHO_FEEDBACK = 0.4f

        /** How loud the repeats come back against the dry signal they echo. */
        private const val ECHO_WET = 0.5f

        /** Corners of the loop's one-pole filters: each repeat loses air above and rumble below. */
        private const val ECHO_LOW_PASS_HZ = 3_500f
        private const val ECHO_HIGH_PASS_HZ = 250f

        /** Mirrors [com.music.bitchord.playback.smart.MAX_ECHO_SECONDS]; bounds the ring. */
        private const val MAX_ECHO_SECONDS = 1f

        /** The coefficient of a one-pole smoother with its corner at [hz]. */
        private fun onePole(hz: Float, sampleRate: Int): Float =
            if (sampleRate <= 0) 0f else (1.0 - exp(-2.0 * Math.PI * hz / sampleRate)).toFloat()
    }
}

/**
 * The two filters a transition rides: one over the track arriving, one over the
 * track leaving.
 *
 * An interface rather than the processors themselves so [CrossfadeController]
 * stays testable without an audio sink, and so it never has to know that
 * "incoming" and "outgoing" are two different ExoPlayers whose roles swap at the
 * lap.
 */
interface TransitionFilters {
    /** The track fading up — the session player, once the lap has handed the queue over. */
    fun incoming(lowPassHz: Float, highPassHz: Float)

    /** The track fading out — the ghost player. */
    fun outgoing(lowPassHz: Float, highPassHz: Float)

    /**
     * How far ahead of the speaker each side's filter is running, in that
     * track's media milliseconds — see [TransitionFilterProcessor.leadUs].
     * Zero, the default, means "applied as it is heard".
     */
    fun incomingLeadMs(): Long = 0L
    fun outgoingLeadMs(): Long = 0L

    /** Echo out on each side's sink — see [TransitionFilterProcessor.setEcho]. */
    fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) = Unit
    fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) = Unit

    /** Drops any echo on either sink. Called whenever a transition ends, however it ended. */
    fun parkEchoes() = Unit

    /** Parks both. Called whenever a transition ends, however it ended. */
    fun open() {
        incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
    }

    /** For callers with no audio sink to filter — tests, and the default wiring. */
    object None : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
        override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
    }
}
