// After BitChord's DspChain (app/src/main/java/com/music/bitchord/playback/audio/DspChain.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): its order and its contract, with AETHER's own stages.
@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import app.aether.player.AudioMeter
import app.aether.player.EqualiserProcessor
import app.aether.player.automix.AudioBlock
import app.aether.player.automix.FloatAudioProcessor
import app.aether.player.automix.TransitionFilterProcessor

/**
 * AETHER's processors in their sequence, on [PrecisionAudioSink]'s float blocks:
 *
 * AudioBlock(Float32) -> equaliser -> Automix's filters, echo and blend trim -> ReplayGain -> meter
 *
 * BitChord's order: the listener's equaliser before the transition, which belongs to one handoff and
 * has the last word on it; levelling after both, per sink on purpose, so each side of a blend is
 * levelled for its own song. The meter last, so it hears what's heard.
 *
 * ## Staying out of the way
 *
 * Every stage self-bypasses when it has nothing to do, returning before it reads a sample rather than
 * multiplying through by unity. With all of them idle the block leaves this class as it arrived,
 * which is what lets [app.aether.player.automix.PcmBoundary]'s power-of-two scaling round-trip 16- and
 * 24-bit integers unchanged.
 */
internal class DspChain(
    val equaliser: EqualiserProcessor,
    val transition: TransitionFilterProcessor,
    val replayGain: ReplayGainProcessor,
    val meter: AudioMeter,
) : FloatAudioProcessor {

    /** The song about to play, as the decoder hands it over: what ReplayGain reads its tags from. */
    fun onFormat(format: Format) = replayGain.setFormat(format)

    override fun configure(sampleRate: Int, channelCount: Int) {
        equaliser.configure(sampleRate, channelCount)
        transition.configure(sampleRate, channelCount)
        replayGain.configure(sampleRate, channelCount)
        meter.configure(sampleRate, channelCount)
    }

    /**
     * A new song has begun gaplessly on this sink. BitChord's loudness stage switches songs here; AETHER's
     * ReplayGain has already switched, at [onFormat], which the renderer calls before the song's first sample.
     */
    fun onStreamBoundary() = Unit

    /** The stream starts afresh at [positionOffsetUs] in the song (a seek, a new song): for the meter's stamps. */
    fun onStreamStart(positionOffsetUs: Long) = meter.onStreamStart(positionOffsetUs)

    override fun process(block: AudioBlock) {
        if (block.frameCount == 0) return
        equaliser.process(block)
        transition.process(block)
        transition.trim(block)
        replayGain.process(block)
        meter.process(block)
    }

    @Suppress("DEPRECATION")
    override fun flush() {
        equaliser.flush()
        transition.flush()
        replayGain.flush()
        meter.flush()
    }

    override fun reset() {
        equaliser.reset()
        transition.reset()
        replayGain.reset()
        meter.reset()
    }
}
