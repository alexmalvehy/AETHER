// Ported from BitChord (app/.../playback/PlaybackService.kt, its SessionPlayer; kushagrasinghx/BitChord,
// GPL-3.0): the part that answers for a Cast receiver. Its party, DAC and loop-mix parts aren't AETHER's.
package app.aether.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.aether.player.cast.CastPlayback
import app.aether.player.cast.CastSink
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The player the media session sees: the engine's, or, while a TV is the speaker, the TV's. While the
 * TV plays, the phone's player is held paused and its clock is stale, so everything that describes
 * playback (playing or not, where, how long, how loud) is the TV's; the queue, the songs and the
 * commands that change them stay the phone's, and reach the TV as the phone's player moves.
 *
 * Built again at every Automix handoff, around whichever player has the session then.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(player: Player, private val cast: CastPlayback) : ForwardingPlayer(player), CastSink {

    // The listeners registered through this wrapper, kept so the TV's changes can be told to them: the
    // phone's own player never tells them, being paused, and knowing of nothing happening.
    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var reportedPlayWhenReady = player.playWhenReady
    private var reportedPlaybackState = player.playbackState
    private var reportedIsPlaying = player.isPlaying

    init {
        cast.sink = this
    }

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    override fun getPlayWhenReady() = if (cast.active) cast.playWhenReady else super.getPlayWhenReady()
    override fun isPlaying() = if (cast.active) cast.isPlaying else super.isPlaying()
    override fun getPlaybackState() = if (cast.active) cast.playbackState else super.getPlaybackState()
    override fun getCurrentPosition() = if (cast.active) cast.positionMs else super.getCurrentPosition()
    override fun getContentPosition() = if (cast.active) cast.positionMs else super.getContentPosition()
    override fun getBufferedPosition() = if (cast.active) cast.positionMs else super.getBufferedPosition()
    override fun getContentBufferedPosition() = if (cast.active) cast.positionMs else super.getContentBufferedPosition()
    override fun getDuration(): Long = if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getDuration()
    override fun getContentDuration(): Long = if (cast.active && cast.durationMs != C.TIME_UNSET) cast.durationMs else super.getContentDuration()

    // The TV is a remote output with a volume of its own: the phone's volume keys move it.
    override fun getDeviceInfo(): DeviceInfo = if (cast.active) TvDevice else super.getDeviceInfo()
    override fun getDeviceVolume(): Int = if (cast.active) cast.deviceVolume else super.getDeviceVolume()
    override fun isDeviceMuted(): Boolean = if (cast.active) false else super.isDeviceMuted()

    override fun setDeviceVolume(volume: Int, flags: Int) = if (cast.active) cast.setVolume(volume / 100f) else super.setDeviceVolume(volume, flags)
    @Deprecated("Deprecated in Java")
    override fun setDeviceVolume(volume: Int) = if (cast.active) cast.setVolume(volume / 100f) else @Suppress("DEPRECATION") super.setDeviceVolume(volume)
    override fun increaseDeviceVolume(flags: Int) = if (cast.active) nudge(+VolumeStep) else super.increaseDeviceVolume(flags)
    @Deprecated("Deprecated in Java")
    override fun increaseDeviceVolume() = if (cast.active) nudge(+VolumeStep) else @Suppress("DEPRECATION") super.increaseDeviceVolume()
    override fun decreaseDeviceVolume(flags: Int) = if (cast.active) nudge(-VolumeStep) else super.decreaseDeviceVolume(flags)
    @Deprecated("Deprecated in Java")
    override fun decreaseDeviceVolume() = if (cast.active) nudge(-VolumeStep) else @Suppress("DEPRECATION") super.decreaseDeviceVolume()
    override fun setDeviceMuted(muted: Boolean, flags: Int) { if (!cast.active) super.setDeviceMuted(muted, flags) }
    @Deprecated("Deprecated in Java")
    override fun setDeviceMuted(muted: Boolean) { if (!cast.active) @Suppress("DEPRECATION") super.setDeviceMuted(muted) }

    private fun nudge(delta: Float) = cast.setVolume(cast.deviceVolume / 100f + delta)

    override fun play() = if (cast.active) cast.play() else super.play()
    override fun pause() = if (cast.active) cast.pause() else super.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (cast.active) {
            if (playWhenReady) cast.play() else cast.pause()
            return
        }
        super.setPlayWhenReady(playWhenReady)
    }

    override fun seekTo(positionMs: Long) = if (cast.active) cast.seekTo(positionMs) else super.seekTo(positionMs)
    override fun seekBack() = if (cast.active) cast.seekTo(cast.positionMs - seekBackIncrement) else super.seekBack()
    override fun seekForward() = if (cast.active) cast.seekTo(cast.positionMs + seekForwardIncrement) else super.seekForward()
    override fun seekToDefaultPosition() = if (cast.active) cast.seekTo(0L) else super.seekToDefaultPosition()

    /** Back, on a TV: to the song's start past its first seconds, else the song before. */
    override fun seekToPrevious() {
        if (!cast.active) return super.seekToPrevious()
        if (cast.positionMs > wrappedPlayer.maxSeekToPreviousPosition || !wrappedPlayer.hasPreviousMediaItem()) cast.seekTo(0L)
        else wrappedPlayer.seekToPreviousMediaItem()
    }

    override fun onRemotePlaybackChanged() {
        val flags = FlagSet.Builder()
        val playWhenReady = getPlayWhenReady()
        val state = getPlaybackState()
        val playing = isPlaying()
        if (playWhenReady != reportedPlayWhenReady) {
            reportedPlayWhenReady = playWhenReady
            flags.add(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE) }
        }
        if (state != reportedPlaybackState) {
            reportedPlaybackState = state
            flags.add(Player.EVENT_PLAYBACK_STATE_CHANGED)
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
        if (playing != reportedIsPlaying) {
            reportedIsPlaying = playing
            flags.add(Player.EVENT_IS_PLAYING_CHANGED)
            listeners.forEach { it.onIsPlayingChanged(playing) }
        }
        // Always: a controller carries the position on from the last one it was given, and has to be
        // set again whenever the TV has been sought, stalled or resumed.
        flags.add(Player.EVENT_POSITION_DISCONTINUITY)
        val position = Player.PositionInfo(null, currentMediaItemIndex, currentMediaItem, null, currentPeriodIndex, currentPosition, contentPosition, C.INDEX_UNSET, C.INDEX_UNSET)
        listeners.forEach { it.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_INTERNAL) }
        val events = Player.Events(flags.build())
        listeners.forEach { it.onEvents(this, events) }
    }

    override fun onRemoteRouteChanged() {
        val info = getDeviceInfo()
        listeners.forEach { it.onDeviceInfoChanged(info) }
        listeners.forEach { it.onEvents(this, Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_INFO_CHANGED).build())) }
        onRemoteVolumeChanged()
    }

    override fun onRemoteVolumeChanged() {
        val volume = getDeviceVolume()
        val muted = isDeviceMuted()
        listeners.forEach { it.onDeviceVolumeChanged(volume, muted) }
        listeners.forEach { it.onEvents(this, Player.Events(FlagSet.Builder().add(Player.EVENT_DEVICE_VOLUME_CHANGED).build())) }
    }

    private companion object {
        val TvDevice: DeviceInfo = DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build()

        /** A press of a volume key, as a share of the TV's range. */
        const val VolumeStep = 0.05f
    }
}
