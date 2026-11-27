// After BitChord's PlaybackService (`shouldEnableFloatOutput`, `resolvePreferredOutputEncoding`) and its
// OutputNegotiator (github.com/kushagrasinghx/BitChord, GPL-3.0): the same decisions, for the one setting
// AETHER has, which is always "float where it's real" (BitChord's FLOAT_32). Two changes, both because
// AETHER's two players and its TV aren't rebuilt for a new route:
// - BitChord builds the players again when float output changes; here the route is asked again whenever
//   a decoder or a song's sink is set up, which is where the answer is used.
// - BitChord's Bluetooth codec telemetry (LDAC at 24 bits) isn't ported: Bluetooth gets float only where
//   the headset says it takes it, as in BitChord when that telemetry is missing.
@file:OptIn(UnstableApi::class)

package app.aether.player.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import app.aether.player.automix.PcmEncoding

/**
 * Where the music is going, and so whether a float track is real there:
 *
 * - The phone's own speaker: 16-bit, always (BitChord's rule: some phones' speaker paths take a float
 *   track and distort it, and nothing is gained there).
 * - Anything else: float when Android offers a direct float path at the song's rate, or the device says
 *   it takes float; otherwise 16-bit.
 *
 * Kept up to date by Android's device callbacks on the main thread; read from the playback thread.
 */
internal class OutputRoute(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    @Volatile private var device: AudioDeviceInfo? = null
    @Volatile private var kind = AudioRouting.Kind.PHONE
    @Volatile private var advertisesFloat = false
    @Volatile private var directFloat = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    fun start() {
        audio?.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun stop() {
        audio?.unregisterAudioDeviceCallback(devices)
    }

    private fun refresh() {
        val infos = runCatching { audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: emptyArray()
        val active = AudioRouting.activeOf(infos, null)
        device = active
        kind = active?.let { AudioRouting.kindOf(it.type) } ?: AudioRouting.Kind.PHONE
        advertisesFloat = active?.encodings?.contains(AudioFormat.ENCODING_PCM_FLOAT) == true
        // BitChord asks at 48 kHz stereo: what the decoders are told before a song's own rate is known.
        directFloat = DirectAudioProbe.probeDirectSupport(audio, 48_000, 2, active).supportsFloat
    }

    /** BitChord's `shouldEnableFloatOutput`: whether a decoder may hand over float, and a float track open. */
    fun floatOutput(): Boolean =
        AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, kind, advertisesFloat || directFloat)

    /**
     * BitChord's `resolvePreferredOutputEncoding` for the ordinary sink: 16-bit on the phone; float where
     * the route offers it directly at [format]'s own rate, or says it takes float; otherwise 16-bit. Null
     * while nothing is known of the rate, which leaves the choice to the sink's own fallback.
     */
    fun preferredEncoding(format: Format): PcmEncoding? {
        val route = kind
        if (route == AudioRouting.Kind.PHONE) return PcmEncoding.PCM_16BIT
        val rate = format.sampleRate.takeIf { it > 0 } ?: return null
        val channels = format.channelCount.takeIf { it > 0 } ?: 2
        val direct = DirectAudioProbe.probeDirectSupport(audio, rate, channels, device)
        return if (direct.supportsFloat || AudioOutputPolicy.shouldUseFloatOutput(OutputPcmMode.FLOAT_32, route, advertisesFloat)) {
            PcmEncoding.PCM_FLOAT
        } else {
            PcmEncoding.PCM_16BIT
        }
    }
}
