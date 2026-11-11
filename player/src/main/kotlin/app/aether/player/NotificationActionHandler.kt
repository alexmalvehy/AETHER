package app.aether.player

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import app.aether.core.model.Track
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Exposes and handles the "Like" (`ACTION_TOGGLE_LIKE`) and "Start Radio" (`ACTION_START_RADIO`)
 * custom actions on Android's system media notification and lock screen player card.
 */
@OptIn(UnstableApi::class)
class NotificationActionHandler(
    private val scope: CoroutineScope,
    private val env: PlaybackEnvironment,
    private val playerProvider: () -> Player?,
    private val sessionProvider: () -> MediaSession?,
) {

    fun buildCustomLayout(isLiked: Boolean): List<CommandButton> {
        val likeButton = CommandButton.Builder()
            .setDisplayName(if (isLiked) "Liked" else "Like")
            .setIconResId(if (isLiked) R.drawable.aether_heart_filled else R.drawable.aether_heart)
            .setSessionCommand(COMMAND_TOGGLE_LIKE)
            .build()
        val radioButton = CommandButton.Builder()
            .setDisplayName("Start Radio")
            .setIconResId(R.drawable.aether_radio)
            .setSessionCommand(COMMAND_START_RADIO)
            .build()
        return listOf(likeButton, radioButton)
    }

    fun availableSessionCommands(): SessionCommands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(COMMAND_TOGGLE_LIKE)
            .add(COMMAND_START_RADIO)
            .add(SessionCommand(CastManager.ACTION_OPEN_OUTPUT_SWITCHER, Bundle.EMPTY))
            .build()

    fun refreshCustomLayout() {
        val session = sessionProvider() ?: return
        val currentTrack = playerProvider()?.currentMediaItem?.toTrack()
        scope.launch(Dispatchers.Main) {
            val liked = currentTrack?.let { runCatching { env.isLiked(it.id) }.getOrDefault(false) } ?: false
            session.setCustomLayout(buildCustomLayout(liked))
        }
    }

    fun onCustomCommand(
        session: MediaSession,
        customCommand: SessionCommand,
        @Suppress("UNUSED_PARAMETER") args: Bundle,
    ): ListenableFuture<SessionResult>? {
        return when (customCommand.customAction) {
            ACTION_TOGGLE_LIKE -> {
                val track = playerProvider()?.currentMediaItem?.toTrack()
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                scope.launch(Dispatchers.Main) {
                    val nowLiked = runCatching { env.toggleLike(track) }.getOrDefault(false)
                    session.setCustomLayout(buildCustomLayout(nowLiked))
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            ACTION_START_RADIO -> {
                val player = playerProvider()
                val track = player?.currentMediaItem?.toTrack()
                    ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                scope.launch(Dispatchers.Main) {
                    val radioTracks = runCatching { env.startRadio(track) }.getOrDefault(emptyList())
                        .filterNot { it.id == track.id }
                    if (radioTracks.isNotEmpty() && player.mediaItemCount > 0) {
                        val currentIndex = player.currentMediaItemIndex
                        if (currentIndex + 1 < player.mediaItemCount) {
                            player.removeMediaItems(currentIndex + 1, player.mediaItemCount)
                        }
                        player.addMediaItems(radioTracks.map { it.toMediaItem() })
                    }
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> null
        }
    }

    companion object {
        const val ACTION_TOGGLE_LIKE = "app.aether.player.ACTION_TOGGLE_LIKE"
        const val ACTION_START_RADIO = "app.aether.player.ACTION_START_RADIO"

        val COMMAND_TOGGLE_LIKE = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
        val COMMAND_START_RADIO = SessionCommand(ACTION_START_RADIO, Bundle.EMPTY)
    }
}
