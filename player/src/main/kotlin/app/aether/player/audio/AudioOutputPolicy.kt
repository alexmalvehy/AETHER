// Ported from BitChord (app/src/main/java/com/music/bitchord/playback/AudioOutputPolicy.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0). Its rules and its decoder list are BitChord's; AETHER adds
// [flacSafeCodecs], BitChord's FLAC selector from its PlaybackService, as one function a test can reach.
@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/** How many bits the listener asked the output for (BitChord's `OutputPcmMode`; AETHER always asks for float). */
enum class OutputPcmMode { PCM_16, FLOAT_32 }

/**
 * Decides whether Media3 may open a PCM-float AudioTrack.
 *
 * Float output is not a quality switch that is safe on every Android route.
 * Some OEM speaker paths accept the AudioTrack and then convert it to PCM16 in
 * AudioFlinger; on affected Samsung FLAC decoders that combination can also
 * produce timestamp discontinuities and severely distorted output. Only an
 * explicitly selected external route that advertises PCM float is allowed.
 */
internal object AudioOutputPolicy {
    /** Backwards-compatible USB-specific check. */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        isPreferredUsbRoute: Boolean,
        advertisesPcmFloat: Boolean,
    ): Boolean = requestedMode == OutputPcmMode.FLOAT_32 &&
        isPreferredUsbRoute &&
        advertisesPcmFloat

    /**
     * Route-aware float output decision.
     *
     * - Built-in phone speaker (PHONE) must remain capped at 16-bit to prevent OEM mixer
     *   issues and distortion on affected device paths.
     * - External routes (USB, Bluetooth, Wired, HDMI) are allowed to use Float32 output
     *   if requested and actively advertised by the route's AudioDeviceInfo encodings.
     * - A Bluetooth route whose negotiated A2DP codec carries more than 16 bits
     *   (LDAC / aptX HD at 24 or 32 bits) uses float regardless of the requested mode.
     *   A2DP devices almost never list PCM_FLOAT in their encodings, and without a
     *   float track Media3 truncates a hi-res source to 16 bits before the codec
     *   ever sees it. [bluetoothCodecBitDepth] must come from authoritative telemetry.
     */
    fun shouldUseFloatOutput(
        requestedMode: OutputPcmMode,
        routeKind: AudioRouting.Kind,
        advertisesPcmFloat: Boolean,
        bluetoothCodecBitDepth: Int? = null,
    ): Boolean {
        if (routeKind == AudioRouting.Kind.PHONE) return false
        if (routeKind == AudioRouting.Kind.BLUETOOTH && (bluetoothCodecBitDepth ?: 0) > 16) return true
        if (requestedMode != OutputPcmMode.FLOAT_32) return false
        return advertisesPcmFloat
    }

    /** Samsung's vendor FLAC decoder emits invalid timestamps with PCM float. */
    fun isUnsafeFloatFlacDecoder(name: String): Boolean {
        val normalized = name.lowercase()
        return normalized == "c2.sec.flac.decoder" ||
            (normalized.startsWith("omx.sec.") && normalized.contains("flac"))
    }
}

/**
 * [base] for every kind of audio but FLAC, which goes to a software decoder first and never to one in
 * [AudioOutputPolicy.isUnsafeFloatFlacDecoder] (BitChord's selector: "c2.sec.flac.decoder produces one
 * extra 232.2 ms timestamp advance per decoded buffer when Media3 requests float PCM. Decode FLAC
 * through the platform software codec on affected Samsung devices"). Media3's software ordering puts
 * Android's own decoder (software, not a vendor's) ahead of everything else, so a FLAC plays through
 * `c2.android.flac.decoder` wherever the phone has it. With [base] Media3's own, this is exactly
 * BitChord's `MediaCodecSelector.PREFER_SOFTWARE` for FLAC.
 */
internal fun flacSafeCodecs(base: MediaCodecSelector = MediaCodecSelector.DEFAULT): MediaCodecSelector =
    MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        val candidates = base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
        if (mimeType == MimeTypes.AUDIO_FLAC) {
            MediaCodecUtil.getDecoderInfosSortedBySoftwareOnly(candidates)
                .filterNot { AudioOutputPolicy.isUnsafeFloatFlacDecoder(it.name) }
        } else {
            candidates
        }
    }
