// Ported from BitChord (app/src/main/java/com/music/bitchord/widget/MediaWidgetActions.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): playback reached by binding the session, never by starting it,
// the binding held a moment after the command, a backstop for a session that never comes. Without its like and
// shuffle; with a wait for the queue a cold start restores.
package app.aether.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.aether.player.PlaybackService
import app.aether.player.WidgetState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The widget's keys. A tap on a home-screen widget is the background, where starting a service is refused;
 * binding one isn't, and a MediaController binds (BitChord's way). Binding also brings [PlaybackService] up
 * when the app is closed; it restores the queue where it was, and the command waits for that.
 */
class WidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        val app = context.applicationContext
        // Seen to land at once: the key turns before the player has been reached.
        if (action == ACTION_TOGGLE) MediaWidget.showPlaying(app, !WidgetState.load(app).playing)
        // Held open across the connection: a controller arriving after the broadcast returned arrives in a
        // process that may already be gone.
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        val done = AtomicBoolean(false)
        fun release() {
            if (!done.compareAndSet(false, true)) return
            MediaController.releaseFuture(future)
            // Whatever the player made of it, the widgets show that: a tap that didn't take turns back.
            MediaWidget.refresh(app)
            runCatching { pending.finish() }
        }
        future.addListener(
            {
                runCatching { future.get() }.getOrNull()?.let { controller ->
                    whenQueued(controller) { runCatching { controller.execute(action) } }
                }
                // Not let go at once: the service still has a stream to resolve before it can hold itself
                // up, and losing its last binding in that moment stops it rather than starting the music.
                handler.postDelayed(::release, SETTLE_MS)
            },
            ContextCompat.getMainExecutor(app),
        )
        // A session that never comes would leave the broadcast open until the system calls it unresponsive.
        handler.postDelayed(::release, GIVE_UP_MS)
    }

    /** [block] once the player has a queue: at once, or when a cold start has restored it (or never). */
    private fun whenQueued(controller: MediaController, block: () -> Unit) {
        if (controller.mediaItemCount > 0) return block()
        controller.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (controller.mediaItemCount == 0) return
                controller.removeListener(this)
                block()
            }
        })
    }

    private fun MediaController.execute(action: String) {
        when (action) {
            ACTION_TOGGLE -> if (playWhenReady) {
                pause()
            } else {
                // At the end of the queue, play() alone would wait at the end.
                if (playbackState == Player.STATE_ENDED) seekTo(0L)
                prepareIfIdle()
                play()
            }
            // Skipping doesn't start what wasn't playing: paused, it stays paused, as everywhere else.
            ACTION_NEXT -> {
                seekToNextMediaItem()
                prepareIfIdle()
            }
            // As Now Playing's key: back to the start of the song, or to the one before it near its start.
            ACTION_PREVIOUS -> {
                seekToPrevious()
                prepareIfIdle()
            }
        }
    }

    private fun MediaController.prepareIfIdle() {
        if (playbackState == Player.STATE_IDLE) prepare()
    }

    companion object {
        const val ACTION_TOGGLE = "app.aether.widget.TOGGLE"
        const val ACTION_NEXT = "app.aether.widget.NEXT"
        const val ACTION_PREVIOUS = "app.aether.widget.PREVIOUS"

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            // One per action: extras aren't compared, so only the code and the action tell them apart.
            REQUEST_BASE + ACTIONS.indexOf(action),
            Intent(context, WidgetActions::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private val ACTIONS = listOf(ACTION_TOGGLE, ACTION_NEXT, ACTION_PREVIOUS)
        private const val REQUEST_BASE = 100

        /** How long the binding is kept after the command, so the service can settle. */
        private const val SETTLE_MS = 2_000L

        /** The backstop for a session that never connects: inside a broadcast's window. */
        private const val GIVE_UP_MS = 7_000L
    }
}
