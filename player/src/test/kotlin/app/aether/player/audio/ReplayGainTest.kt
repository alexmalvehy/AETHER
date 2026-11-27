@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.aether.player.automix.AudioBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gramophone's ReplayGain as AETHER runs it: the tags each kind of file carries, and the gain they give. */
class ReplayGainTest {

    private fun song(vararg entries: Metadata.Entry): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setMetadata(Metadata(*entries))
        .build()

    private fun db(gain: Float) = ReplayGainUtil.amplToDb(gain)

    @Test
    fun aFlacsVorbisCommentsAreRead() {
        val tags = ReplayGainUtil.parse(
            song(
                VorbisComment("REPLAYGAIN_TRACK_GAIN", "-7.89 dB"),
                VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.988"),
                VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-6.50 dB"),
                VorbisComment("REPLAYGAIN_ALBUM_PEAK", "1.000"),
            ),
        )
        assertEquals(-7.89f, tags.trackGain!!, 1e-4f)
        assertEquals(0.988f, tags.trackPeak!!, 1e-4f)
        assertEquals(-6.5f, tags.albumGain!!, 1e-4f)
        assertEquals(1f, tags.albumPeak!!, 1e-4f)
    }

    @Test
    fun anOpusFilesR128GainIsBroughtToReplayGainsReference() {
        // R128 is Q7.8 dB against −23 LUFS; ReplayGain's reference is 5 dB louder.
        val tags = ReplayGainUtil.parse(song(VorbisComment("R128_TRACK_GAIN", "-1024")))
        assertEquals(1f, tags.trackGain!!, 1e-4f)
    }

    @Test
    fun anMp3sId3TagsAndAnM4asItunesTagsAreRead() {
        val mp3 = ReplayGainUtil.parse(song(TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("-3.20 dB"))))
        assertEquals(-3.2f, mp3.trackGain!!, 1e-4f)
        val m4a = ReplayGainUtil.parse(song(InternalFrame("com.apple.iTunes", "REPLAYGAIN_ALBUM_GAIN", "-5.00 dB")))
        assertEquals(-5f, m4a.albumGain!!, 1e-4f)
    }

    @Test
    fun aSongWithoutTagsHasNone() {
        val tags = ReplayGainUtil.parse(Format.Builder().build())
        assertNull(tags.trackGain)
        assertNull(tags.albumGain)
    }

    @Test
    fun theGainNeverTakesASongPastItsPeak() {
        // +6 dB asked for, but the song already peaks at 0.8: only up to full scale.
        val (gain, knee) = ReplayGainUtil.calculateGain(ReplayGainUtil.ReplayGainInfo(6f, 0.8f, null, null), ReplayGainUtil.Mode.Track, 0, true, ReplayGainUtil.RATIO)!!
        assertEquals(1.25f, gain, 1e-4f)
        assertNull(knee)
    }

    @Test
    fun aGainHeldToThePeakNeverThrows() {
        // Gramophone's own check tripped on 0.9 × (1 / 0.9) = 1.0000001: on the audio thread that would stop the music.
        val (gain, _) = ReplayGainUtil.calculateGain(ReplayGainUtil.ReplayGainInfo(1f, 0.9f, null, null), ReplayGainUtil.Mode.Track, 0, true, ReplayGainUtil.RATIO)!!
        assertEquals(1f / 0.9f, gain, 1e-4f)
    }

    @Test
    fun byAlbumFallsBackToTheSongsOwnLevel() {
        val tags = ReplayGainUtil.ReplayGainInfo(-4f, 1f, null, null)
        val (gain, _) = ReplayGainUtil.calculateGain(tags, ReplayGainUtil.Mode.Album, 0, true, ReplayGainUtil.RATIO)!!
        assertEquals(-4f, db(gain), 1e-3f)
    }

    // --- The processor on the sink's float blocks --------------------------------------------------

    private fun block(value: Float, frames: Int = 16) = AudioBlock(2, frames).apply {
        reset(frames)
        samples.fill(value, 0, frames * 2)
    }

    @Test
    fun offItTouchesNothing() {
        val rg = ReplayGainProcessor()
        rg.setFormat(song(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.02 dB")))
        val b = block(0.3f)
        rg.process(b)
        b.samples.forEach { assertEquals(0.3f, it, 0f) }
    }

    @Test
    fun bySongItLevelsBlocksByTheSongsTags() {
        val rg = ReplayGainProcessor()
        rg.setMode(ReplayGainUtil.Mode.Track)
        rg.setFormat(song(VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6.02 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "1.0")))
        val b = block(0.5f)
        rg.process(b)
        b.samples.forEach { assertEquals(0.25f, it, 1e-3f) }
    }

    @Test
    fun aMalformedTagLeavesTheSongAsItIs() {
        val rg = ReplayGainProcessor()
        rg.setMode(ReplayGainUtil.Mode.Track)
        // An RVA2 frame with an identification and no channels: Gramophone's reading of it throws.
        rg.setFormat(song(BinaryFrame("RVA2", "track\u0000".toByteArray(Charsets.ISO_8859_1))))
        assertEquals(1f, rg.currentGain, 0f)
    }

    @Test
    fun noTagRaisesASongTensOfDecibels() {
        val rg = ReplayGainProcessor()
        rg.setMode(ReplayGainUtil.Mode.Track)
        // A peak written far too low would let a +40 dB gain through untouched.
        rg.setFormat(song(VorbisComment("REPLAYGAIN_TRACK_GAIN", "+40 dB"), VorbisComment("REPLAYGAIN_TRACK_PEAK", "0.0001")))
        assertEquals(15f, db(rg.currentGain), 1e-3f)
    }
}
