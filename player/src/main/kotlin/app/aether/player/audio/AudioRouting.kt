// Ported from BitChord (app/src/main/java/com/music/bitchord/playback/AudioRouting.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): only the half that says where the music is going
// (the kinds of output and Android's own order of preference). Its route picker isn't AETHER's.
package app.aether.player.audio

import android.media.AudioDeviceInfo

/** Which output the music is coming out of. */
object AudioRouting {

    /** Output kinds worth telling apart, in the order they are listed. */
    enum class Kind { PHONE, WIRED, USB, BLUETOOTH, HDMI, OTHER }

    /**
     * Which device the audio is actually reaching.
     *
     * The chosen one when it is still connected. Otherwise Android's own order
     * of preference, reproduced here rather than asked for, because there is no
     * API that answers it for a sink this app has not opened yet — and because a
     * pure function of the connected-device list is steady, where reading the
     * system's current route is a value that flickers while a Bluetooth profile
     * is negotiating.
     */
    fun activeOf(infos: Array<AudioDeviceInfo>, chosenId: Int?): AudioDeviceInfo? {
        chosenId?.let { id -> infos.firstOrNull { it.id == id } }?.let { return it }
        return systemDefault(infos)
    }

    private fun systemDefault(infos: Array<AudioDeviceInfo>): AudioDeviceInfo? =
        infos.firstOrNull { kindOf(it.type) == Kind.BLUETOOTH }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.WIRED }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.USB }
            ?: infos.firstOrNull { kindOf(it.type) == Kind.HDMI }
            ?: infos.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /**
     * Null for the sinks that are not somewhere music is listened to — the
     * telephony earpiece, a remote submix, the virtual sink a screen recorder
     * opens. Offering those as a choice is offering silence.
     */
    internal fun kindOf(type: Int): Kind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_AUX_LINE,
        -> Kind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> Kind.USB
        // Compile-time constants, so naming the newer ones costs nothing on an
        // older device; the sinks they name simply never appear there.
        // TYPE_BLUETOOTH_SCO is deliberately absent: it is the telephony
        // profile, it cannot play music.
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        -> Kind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        -> Kind.HDMI
        else -> null
    }
}
