// BitChord's AudioOutputPolicyTest (app/src/test/java/com/music/bitchord/playback/AudioOutputPolicyTest.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0), and AETHER's tests of the FLAC selector it feeds.
@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyTest {
    @Test
    fun floatRequiresPreferredUsbRouteAndAdvertisedSupport() {
        assertTrue(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, isPreferredUsbRoute = true, advertisesPcmFloat = true))
    }

    @Test
    fun floatFallsBackOnSpeakerEvenWhenRequested() {
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, isPreferredUsbRoute = false, advertisesPcmFloat = true))
    }

    @Test
    fun floatFallsBackWhenUsbDoesNotAdvertiseIt() {
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, isPreferredUsbRoute = true, advertisesPcmFloat = false))
    }

    @Test
    fun pcm16NeverRequestsFloat() {
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.PCM_16, isPreferredUsbRoute = true, advertisesPcmFloat = true))
    }

    @Test
    fun routeAwarePhoneIsAlwaysCappedAt16Bit() {
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.PHONE, advertisesPcmFloat = true))
    }

    @Test
    fun routeAwareUsbAllowsFloatWhenAdvertised() {
        assertTrue(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.USB, advertisesPcmFloat = true))
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.USB, advertisesPcmFloat = false))
    }

    @Test
    fun routeAwareBluetoothAllowsFloatOnlyWhenAdvertised() {
        assertTrue(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.BLUETOOTH, advertisesPcmFloat = true))
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.BLUETOOTH, advertisesPcmFloat = false))
    }

    @Test
    fun bluetoothHiResCodecEnablesFloatRegardlessOfRequestedMode() {
        assertTrue(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.PCM_16, routeKind = AudioRouting.Kind.BLUETOOTH, advertisesPcmFloat = false, bluetoothCodecBitDepth = 24))
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.PCM_16, routeKind = AudioRouting.Kind.BLUETOOTH, advertisesPcmFloat = false, bluetoothCodecBitDepth = 16))
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, routeKind = AudioRouting.Kind.PHONE, advertisesPcmFloat = true, bluetoothCodecBitDepth = 24))
    }

    @Test
    fun routeAwarePcm16AlwaysReturnsFalse() {
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.PCM_16, routeKind = AudioRouting.Kind.BLUETOOTH, advertisesPcmFloat = true))
        assertFalse(AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.PCM_16, routeKind = AudioRouting.Kind.USB, advertisesPcmFloat = true))
    }

    @Test
    fun samsungVendorFlacDecoderIsBlockedForFloatOutput() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.SEC.FLAC.Decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.android.flac.decoder"))
    }

    // --- The selector the players are built with (issues #15 and #16: 16-bit FLAC as loud, fast static) ---

    private fun codec(name: String, mime: String, software: Boolean, vendor: Boolean) =
        MediaCodecInfo.newInstance(name, mime, mime, null, !software, software, vendor, false, false)

    /** Media3's own list, in the order a Samsung phone gives it: its vendor decoder first. */
    private fun platform(vararg codecs: MediaCodecInfo) = MediaCodecSelector { _, _, _ -> codecs.toList() }

    @Test
    fun aSamsungPhoneDecodesFlacWithAndroidsOwnDecoder() {
        val samsung = platform(
            codec("c2.sec.flac.decoder", MimeTypes.AUDIO_FLAC, software = true, vendor = true),
            codec("c2.android.flac.decoder", MimeTypes.AUDIO_FLAC, software = true, vendor = false),
        )
        val chosen = flacSafeCodecs(samsung).getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false)
        assertEquals(listOf("c2.android.flac.decoder"), chosen.map { it.name })
    }

    @Test
    fun anOldSamsungDecoderIsRefusedToo() {
        val old = platform(
            codec("OMX.SEC.flac.dec", MimeTypes.AUDIO_FLAC, software = false, vendor = true),
            codec("OMX.google.flac.decoder", MimeTypes.AUDIO_FLAC, software = true, vendor = false),
        )
        assertEquals(listOf("OMX.google.flac.decoder"), flacSafeCodecs(old).getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false).map { it.name })
    }

    @Test
    fun anyVendorsFlacDecoderGoesAfterAndroidsOwn() {
        val qualcomm = platform(
            codec("c2.qti.flac.hw.decoder", MimeTypes.AUDIO_FLAC, software = false, vendor = true),
            codec("c2.qti.flac.sw.decoder", MimeTypes.AUDIO_FLAC, software = true, vendor = true),
            codec("c2.android.flac.decoder", MimeTypes.AUDIO_FLAC, software = true, vendor = false),
        )
        val chosen = flacSafeCodecs(qualcomm).getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false).map { it.name }
        assertEquals("c2.android.flac.decoder", chosen.first())
        // Still there to fall back on: only Samsung's is known to garble float.
        assertEquals(3, chosen.size)
    }

    @Test
    fun everyOtherKindOfAudioKeepsThePlatformsOrder() {
        val opus = platform(
            codec("c2.sec.opus.decoder", MimeTypes.AUDIO_OPUS, software = true, vendor = true),
            codec("c2.android.opus.decoder", MimeTypes.AUDIO_OPUS, software = true, vendor = false),
        )
        assertEquals(
            listOf("c2.sec.opus.decoder", "c2.android.opus.decoder"),
            flacSafeCodecs(opus).getDecoderInfos(MimeTypes.AUDIO_OPUS, false, false).map { it.name },
        )
    }
}
