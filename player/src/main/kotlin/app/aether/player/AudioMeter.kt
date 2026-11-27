// After PODIUM's AudioMeter (player/service, D-82, GPL-3.0): only its loudness of the whole signal,
// which is what lines a carried copy up with its song (SoundAlignment, D-96).
package app.aether.player

import app.aether.player.automix.AudioBlock
import app.aether.player.automix.FloatAudioProcessor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * How loud the music is, 10 ms at a time, stamped with where in the song each 10 ms is: the last step
 * of the sound path, which changes nothing and measures only while something [listen]s, which is only
 * while two copies of a song are being lined up.
 *
 * - Mixed to mono and measured as RMS over 10 ms blocks.
 * - Each block is stamped with its place in the song: the sink says where each stream starts
 *   ([onStreamStart], what Media3 hands its own processors as `positionOffsetUs`), and the blocks count
 *   on from there, so a reader asks by song position ([levelAt]) and the reading lines up with the sound.
 * - A flush (a seek, a new stream) starts the record afresh.
 * - Written on the playback thread, read on the main thread, without locks: blocks are published by the
 *   volatile counter, and a reading from before a flush is refused by its generation.
 */
internal class AudioMeter : FloatAudioProcessor {
    private val starts = LongArray(CAPACITY)
    private val levels = FloatArray(CAPACITY)
    @Volatile private var written = 0L
    @Volatile private var generation = 0
    @Volatile private var blockUs = BLOCK_MS * 1000L
    private val listeners = AtomicInteger(0)

    private var sampleRate = 0
    private var channels = 0
    private var blockFrames = 0
    private var offsetUs = 0L
    private var frames = 0L
    private var inBlock = 0
    private var sum = 0.0

    /** Measures for as long as this is open. */
    fun listen(): AutoCloseable {
        listeners.incrementAndGet()
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                listeners.decrementAndGet()
            }
        }
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        forget()
        this.sampleRate = sampleRate
        channels = channelCount
        blockFrames = (sampleRate * BLOCK_MS / 1000).coerceAtLeast(1)
        blockUs = blockFrames * 1_000_000L / sampleRate.coerceAtLeast(1)
    }

    /** The stream starts afresh at [positionOffsetUs] in the song: a seek, or a new song. */
    fun onStreamStart(positionOffsetUs: Long) {
        forget()
        offsetUs = positionOffsetUs
    }

    override fun process(block: AudioBlock) {
        val count = block.frameCount
        if (count == 0 || channels <= 0 || sampleRate <= 0) return
        if (listeners.get() > 0) {
            measure(block)
        } else {
            // Nobody listening: only the song position counts on, so listening can start mid-stream.
            frames += count
            inBlock = 0
            sum = 0.0
        }
    }

    private fun measure(block: AudioBlock) {
        val samples = block.samples
        val count = block.channelCount
        var i = 0
        for (frame in 0 until block.frameCount) {
            var mono = 0f
            for (ch in 0 until count) mono += samples[i++]
            mono /= count
            sum += (mono * mono).toDouble()
            frames++
            if (++inBlock == blockFrames) {
                publish(offsetUs + (frames - blockFrames) * 1_000_000L / sampleRate, sqrt(sum / blockFrames).toFloat())
                inBlock = 0
                sum = 0.0
            }
        }
    }

    private fun publish(startUs: Long, level: Float) {
        val n = written
        val i = (n % CAPACITY).toInt()
        starts[i] = startUs
        levels[i] = level
        written = n + 1
    }

    override fun flush() = forget()

    override fun reset() {
        forget()
        sampleRate = 0
        channels = 0
    }

    private fun forget() {
        generation++
        written = 0
        offsetUs = 0
        frames = 0
        inBlock = 0
        sum = 0.0
    }

    /** The ring slot of the block covering [positionMs], or -1. */
    private fun slotAt(positionMs: Long): Int {
        val n = written
        if (n == 0L) return -1
        val us = positionMs * 1000
        var lo = maxOf(0L, n - CAPACITY + 1)
        var hi = n - 1
        if (us < starts[(lo % CAPACITY).toInt()] || us >= starts[(hi % CAPACITY).toInt()] + blockUs) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[(mid % CAPACITY).toInt()] <= us) lo = mid else hi = mid - 1
        }
        return (lo % CAPACITY).toInt()
    }

    /** The loudness at [positionMs] in the song, or -1 when it hasn't been measured (or is gone). */
    fun levelAt(positionMs: Long): Float {
        val gen = generation
        val i = slotAt(positionMs)
        if (i < 0) return -1f
        val level = levels[i]
        return if (generation == gen) level else -1f
    }

    companion object {
        const val BLOCK_MS = 10

        /** Five seconds of blocks: far more than the player ever decodes ahead. */
        const val CAPACITY = 512
    }
}
