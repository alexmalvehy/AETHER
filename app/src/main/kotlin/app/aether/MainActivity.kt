package app.aether

import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.aether.ui.AetherRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var updateCheck: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as AetherApp).container
        container.playback.connect()
        // The boot film plays on a cold start only: not when the activity is recreated.
        val coldStart = savedInstanceState == null && !booted
        booted = true
        val systemReducesMotion = SystemSettings.Global.getFloat(contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        // Debug builds only: `--es aether.preview welcome` shows the welcome again without touching
        // the listener's settings, so it can be looked at on a phone that has been through it;
        // `--es aether.preview update` looks for an update as if this were an old version.
        val debug = packageName.endsWith(".debug")
        val preview = intent?.getStringExtra("aether.preview")
        val previewWelcome = debug && preview == "welcome"
        if (debug && preview == "update") container.updates.checkNow(pretend = "0.0.1")
        if (debug && preview == "morph") previewMorph()
        if (debug) listeningCheck(intent)
        // From the home-screen widget: the app opens on Now Playing.
        if (intent?.getBooleanExtra(EXTRA_NOW_PLAYING, false) == true) NowPlayingAsked.value = true
        setContent { AetherRoot(container, playBoot = coldStart, systemReducesMotion = systemReducesMotion, previewWelcome = previewWelcome) }
        // Full screen (PODIUM's D-85): Android's bars hidden while the setting is on; a swipe from the
        // edge shows them for a moment. The pages keep the room the bars take, so nothing moves under
        // the camera or jumps when they come and go.
        lifecycleScope.launch { container.settings.fullScreen.collect(::applyFullScreen) }
        // Every screen paints its own black from its first frame; the window's black beneath it was
        // one more full screen of paint on every frame. It stays for the splash, then goes.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    // Some phones bring the bars back when the window has its focus again (a dialog, the shade): hidden again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullScreen((application as AetherApp).container.settings.fullScreen.value)
    }

    private fun applyFullScreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = WindowInsetsCompat.Type.systemBars()
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else {
            controller.show(bars)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_NOW_PLAYING, false)) NowPlayingAsked.value = true
        // Debug builds: the update preview works on a running app too.
        if (packageName.endsWith(".debug") && intent.getStringExtra("aether.preview") == "update") {
            (application as AetherApp).container.updates.checkNow(pretend = "0.0.1")
        }
        if (packageName.endsWith(".debug") && intent.getStringExtra("aether.preview") == "morph") {
            setIntent(intent)
            previewMorph()
        }
        // Debug builds: playlist sync checked live against the signed-in account (src/debug/SyncCheck.kt).
        if (packageName.endsWith(".debug")) SyncCheck.handle(intent, (application as AetherApp).container, this)
        // Debug builds: Now Playing's light changed to other colours, as a new song would change it, and back to the cover's own the next time, with no song changed.
        if (packageName.endsWith(".debug") && intent.getStringExtra("aether.preview") == "light") {
            app.aether.ui.player.AmbientPreview.turn.intValue++
        }
        // Debug builds: the wave the screen makes as the music goes out to a TV, with no TV.
        if (packageName.endsWith(".debug") && intent.getStringExtra("aether.preview") == "handoff") {
            app.aether.player.cast.CastController.previewHandoff()
        }
        if (packageName.endsWith(".debug")) listeningCheck(intent)
    }

    /**
     * Debug builds: playback set up for a listening check from adb, on the running app. `--es aether.repeat
     * off|all|one`, `--el aether.seek <ms>` (counted from the song's end when negative), `--es aether.play
     * on|off`, applied in that order.
     */
    private fun listeningCheck(intent: android.content.Intent?) {
        if (intent == null) return
        val playback = (application as AetherApp).container.playback
        intent.getStringExtra("aether.repeat")?.let { want ->
            playback.setRepeat(
                when (want) {
                    "one" -> app.aether.core.model.RepeatMode.ONE
                    "all" -> app.aether.core.model.RepeatMode.ALL
                    else -> app.aether.core.model.RepeatMode.OFF
                },
            )
        }
        if (intent.hasExtra("aether.seek")) {
            val at = intent.getLongExtra("aether.seek", 0L)
            val duration = playback.state.value.durationMs
            playback.seekTo(if (at < 0 && duration > 0) (duration + at).coerceAtLeast(0) else at.coerceAtLeast(0))
        }
        when (intent.getStringExtra("aether.play")) {
            "on" -> if (!playback.state.value.isPlaying) playback.toggle()
            "off" -> if (playback.state.value.isPlaying) playback.toggle()
        }
    }

    /** Debug builds: Automix's change of cover from the playing song to the next, over ten seconds. */
    private fun previewMorph() {
        val state = (application as AetherApp).container.playback.state.value
        val from = state.current ?: return
        val to = state.queue.getOrNull(state.index + 1) ?: return
        app.aether.player.AutomixState.preview(lifecycleScope, from.id.value, to.id.value, 10f)
    }

    override fun onStart() {
        super.onStart()
        // A quiet look for a newer AETHER, once the opening has played and the room has settled.
        val updates = (application as AetherApp).container.updates
        updateCheck?.cancel()
        updateCheck = lifecycleScope.launch {
            delay(UpdateCheckAfterMs)
            updates.autoCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's page that lets AETHER install updates: carry on with the one waiting.
        (application as AetherApp).container.updates.resumed()
    }

    override fun onStop() {
        updateCheck?.cancel()
        super.onStop()
    }

    companion object {
        /** An intent's ask to open on Now Playing (the home-screen widget's). */
        const val EXTRA_NOW_PLAYING = "aether.nowplaying"

        /** Asked to open on Now Playing, and not yet: the Shell opens it once there's a song to show. */
        val NowPlayingAsked = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** Per process: coming back to a running app never replays the boot. */
        private var booted = false

        /** Long enough for the boot film and the first room to have the screen to themselves. */
        private const val UpdateCheckAfterMs = 6_000L
    }
}
