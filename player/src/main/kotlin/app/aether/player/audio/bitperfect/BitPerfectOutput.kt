/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

// AETHER: BitChord's BitPerfectOutput (app/src/main/java/com/music/bitchord/playback/audio/bitperfect/
// BitPerfectOutput.kt, github.com/kushagrasinghx/BitChord), for one of its modes: Android 14's bit-perfect
// mixer to a USB DAC (BitChord's USB_ANDROID), on while the listener's setting is ([enabled]). Its direct
// USB and AAudio exclusive drivers are native code AETHER doesn't carry, and its "convert for a limited
// DAC" isn't bit-perfect, so neither is ported; the rest (one owner, failures remembered per device and
// format, the status) is BitChord's.
package app.aether.player.audio.bitperfect

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What a [BitPerfectAudioSink] asks of [BitPerfectOutput], kept narrow so a test can stand in for it. */
internal interface BitPerfectOutputs {
    fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean

    fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): BitPerfectTransport

    fun release(requester: Any)

    fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String)

    fun publish(status: BitPerfectOutput.Status)

    fun publishIdle()

    /** See [BitPerfectOutput.normalOutputPending]. */
    fun normalOutputPending(): Boolean
}

/**
 * Process-wide owner of the one bit-perfect output.
 *
 * Both of AETHER's players build a [BitPerfectAudioSink], but a DAC opened
 * bit-perfect has room for a single stream: Android's bit-perfect mixer
 * mutes everything else. Whichever sink opens first holds it until it
 * releases; the other is told no and stays on the ordinary path.
 *
 * A route that refused to open is remembered until the attached device or the
 * setting changes, so a track that keeps failing is not retried on every buffer.
 */
internal object BitPerfectOutput {

    private const val TAG = "BitPerfectOutput"
    private const val NO_DEVICE = -1

    data class Status(
        val active: Boolean = false,
        val driver: String? = null,
        val deviceName: String? = null,
        val sampleRateHz: Int = 0,
        val outputBits: Int = 0,
        val sourceBits: Int = 0,
        val sourceRateHz: Int = 0,
        /** False once any sample of the current track had to be rounded. */
        val exact: Boolean = true,
        /** Always false in AETHER: a DAC that can't take a track as it is gets the ordinary path. */
        val dacLimited: Boolean = false,
        /** Why the last attempt fell back to normal output, if it did. */
        val fallbackReason: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _ready = MutableStateFlow(false)

    /**
     * Whether the setting is on and a USB DAC is attached, so lossless tracks
     * will be going to the bit-perfect output. What blends stand down for
     * follows this, not the bare setting: with it on and nothing plugged in,
     * everything plays as normal and should look it.
     */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** AETHER: the listener's setting (Settings ▸ Sound ▸ Bit-perfect to a USB DAC), BitChord's USB_ANDROID mode. */
    @Volatile var enabled: () -> Boolean = { false }

    private var watching = false

    /** Starts following the attached devices; [refreshReady] covers the setting. */
    @Synchronized
    fun watch(context: Context) {
        if (watching) return
        watching = true
        val app = context.applicationContext
        app.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(
            object : android.media.AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = refreshReady(app)
                override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = refreshReady(app)
            },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        refreshReady(app)
    }

    fun refreshReady(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        attached = enabled() && Build.VERSION.SDK_INT >= 34 && audio?.let(MixerTransport::usbDevice) != null
        recomputeReady()
    }

    @Volatile private var attached = false

    /**
     * Re-derived whenever any of its inputs moves — the setting, the attached
     * device, or the output opening and closing — rather than only on device
     * events.
     */
    private fun recomputeReady() {
        _ready.value = enabled() && (attached || _status.value.active)
    }

    fun forContext(context: Context): BitPerfectOutputs {
        val app = context.applicationContext
        return object : BitPerfectOutputs {
            override fun canOpen(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                canOpen(app, sampleRate, channels, sourceBits, requester)

            override fun open(sampleRate: Int, channels: Int, sourceBits: Int, requester: Any) =
                open(app, sampleRate, channels, sourceBits, requester)

            override fun release(requester: Any) = this@BitPerfectOutput.release(requester)

            override fun markFailed(sampleRate: Int, channels: Int, sourceBits: Int, reason: String) =
                markFailed(app, sampleRate, channels, sourceBits, reason)

            override fun publish(status: Status) = this@BitPerfectOutput.publish(status)

            override fun publishIdle() = this@BitPerfectOutput.publishIdle()

            // BitChord waits here after its direct USB driver hands a DAC back; Android's mixer never takes it.
            override fun normalOutputPending() = false
        }
    }

    private var owner: Any? = null
    private var active: BitPerfectTransport? = null

    private val failed = HashSet<String>()
    private var failedFor: String? = null

    /** Whether a sink asking for this stream now could get the bit-perfect output. */
    @Synchronized
    fun canOpen(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, requester: Any): Boolean {
        if (owner != null && owner !== requester) return false
        if (!enabled()) return false
        if (Build.VERSION.SDK_INT < 34) return false
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val device = runCatching { MixerTransport.usbDevice(manager) }.getOrNull() ?: return false
        if (key(device.id, sampleRate, channels, sourceBits) in currentFailures(device.id)) return false
        return MixerTransport.bestFormat(manager, device, sampleRate, channels, sourceBits) != null
    }

    /** Opens the bit-perfect output for [requester], or throws with the reason. */
    @Synchronized
    fun open(
        context: Context,
        sampleRate: Int,
        channels: Int,
        sourceBits: Int,
        requester: Any,
    ): BitPerfectTransport {
        if (owner != null && owner !== requester) throw BitPerfectTransportException("busy")
        val manager = context.getSystemService(AudioManager::class.java)
            ?: throw BitPerfectTransportException("no_audio_manager")
        if (!enabled()) throw BitPerfectTransportException("off")
        if (Build.VERSION.SDK_INT < 34) throw BitPerfectTransportException("android_version")
        val transport = MixerTransport.open(manager, sampleRate, channels, sourceBits)
        owner = requester
        active = transport
        return transport
    }

    @Synchronized
    fun release(requester: Any) {
        if (owner !== requester) return
        owner = null
        active = null
    }

    /** Records that this stream could not be opened on the attached device. */
    @Synchronized
    fun markFailed(context: Context, sampleRate: Int, channels: Int, sourceBits: Int, reason: String) {
        val deviceId = if (Build.VERSION.SDK_INT >= 34) {
            context.getSystemService(AudioManager::class.java)?.let { runCatching { MixerTransport.usbDevice(it) }.getOrNull() }?.id ?: NO_DEVICE
        } else {
            NO_DEVICE
        }
        currentFailures(deviceId).add(key(deviceId, sampleRate, channels, sourceBits))
        Log.i(TAG, "Bit-perfect output unavailable ($reason) for $sampleRate Hz/$channels ch/$sourceBits-bit")
        _status.value = Status(fallbackReason = reason)
        recomputeReady()
    }

    fun publish(status: Status) {
        _status.value = status
        recomputeReady()
    }

    /** Clears an active status, leaving a fallback reason in place. */
    fun publishIdle() {
        if (_status.value.active) _status.value = Status()
        recomputeReady()
    }

    private fun currentFailures(deviceId: Int): MutableSet<String> {
        val scope = "$deviceId/${enabled()}"
        if (failedFor != scope) {
            failed.clear()
            failedFor = scope
        }
        return failed
    }

    private fun key(deviceId: Int, rate: Int, channels: Int, bits: Int) =
        "$deviceId/$rate/$channels/$bits"
}
