package app.aether.player

import androidx.media3.common.C
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeTest {
    private val song = CrossfadeRules.Song(album = "ytm:MPREb_a", trackNumber = 3, durationMs = 200_000)

    @Test
    fun `songs crossfade only when it's on, there's a next one, and it isn't the same song again`() {
        val other = CrossfadeRules.Song("ytm:MPREb_b", 1, 180_000)
        assertTrue(CrossfadeRules.applies(6, song, other, repeatOne = false))
        assertFalse(CrossfadeRules.applies(0, song, other, repeatOne = false))
        assertFalse(CrossfadeRules.applies(6, song, null, repeatOne = false))
        assertFalse(CrossfadeRules.applies(6, song, other, repeatOne = true))
    }

    @Test
    fun `an album's songs that run into each other never crossfade`() {
        assertFalse(CrossfadeRules.applies(6, song, song.copy(trackNumber = 4), repeatOne = false))
        assertTrue(CrossfadeRules.applies(6, song, song.copy(trackNumber = 7), repeatOne = false), "a jump within the album fades")
        assertTrue(CrossfadeRules.applies(6, song.copy(album = null), song.copy(album = null, trackNumber = 4), repeatOne = false))
    }

    @Test
    fun `songs too short for the overlap don't crossfade`() {
        val short = CrossfadeRules.Song("x", 1, 20_000)
        assertFalse(CrossfadeRules.applies(8, song, short, repeatOne = false))
        assertFalse(CrossfadeRules.applies(8, short, song, repeatOne = false))
        assertFalse(CrossfadeRules.applies(8, song, CrossfadeRules.Song("x", 1, null), repeatOne = false), "unknown length")
    }

    @Test
    fun `the fades keep the loudness steady and the catching up gentle`() {
        for (i in 0..10) {
            val p = i / 10f
            val power = CrossfadeRules.fadeOut(p) * CrossfadeRules.fadeOut(p) + CrossfadeRules.fadeIn(p) * CrossfadeRules.fadeIn(p)
            assertEquals(1f, power, 1e-5f)
        }
        assertEquals(1f, CrossfadeRules.fadeOut(0f))
        assertEquals(1f, CrossfadeRules.fadeIn(1f), 1e-6f)
        assertEquals(0.9f, CrossfadeRules.catchUpSpeed(100), 1e-6f)
        assertEquals(1.15f, CrossfadeRules.catchUpSpeed(-2_000), 1e-6f)
    }

    @Test
    fun `two copies a little apart are lined up by where their sound jumps`() {
        // A beat every 230 ms or so, unevenly, so no other offset fits as well.
        val beats = listOf(3, 26, 47, 71, 93, 118, 139, 162, 186, 207, 231, 254, 276, 299, 322, 344, 367, 391)
        fun levels(size: Int, shift: Int) = FloatArray(size) { i -> if (beats.any { b -> i - shift in b until b + 4 }) 0.8f else 0.05f }
        val song = levels(200, 0)
        // The copy's window starts 50 steps earlier (the margin), and its sound comes 12 steps (120 ms) later.
        val copy = levels(300, 50 + 12)
        val result = SoundAlignment.offset(song, copy)
        assertEquals(SoundAlignment.Result.Offset(120), result)
    }

    @Test
    fun `a passage with nothing to go by says so rather than guess`() {
        val flat = FloatArray(200) { 0.3f }
        assertEquals(SoundAlignment.Result.Unsure, SoundAlignment.offset(flat, FloatArray(300) { 0.3f }))
    }

    @Test
    fun `the meter stamps its readings with the song's position and changes nothing`() {
        val meter = AudioMeter()
        meter.configure(48_000, 1)
        // Where the sink says the stream starts in the song (what Media3 tells its own processors).
        meter.onStreamStart(5_000_000)
        val listening = meter.listen()
        // 100 ms of a 1 kHz tone at half scale, in the sink's float block.
        val frames = 4_800
        val block = app.aether.player.automix.AudioBlock(1, frames)
        block.reset(frames)
        for (i in 0 until frames) block.samples[i] = (sin(2 * PI * 1_000 * i / 48_000) * 0.5).toFloat()
        val sent = block.samples.copyOf()
        meter.process(block)
        assertTrue(sent.contentEquals(block.samples), "every sample passes as it came")
        // A sine at half scale has an RMS of 0.5/√2.
        assertTrue(abs(meter.levelAt(5_050) - 0.3536f) < 0.01f)
        assertEquals(-1f, meter.levelAt(4_000))
        listening.close()
        // A seek starts the record afresh.
        meter.onStreamStart(60_000_000)
        assertEquals(-1f, meter.levelAt(5_050))
    }
}
