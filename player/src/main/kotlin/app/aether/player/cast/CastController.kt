// Ported from BitChord (app/.../playback/cast/CastController.kt, kushagrasinghx/BitChord; GPL-3.0).
// Unchanged in what it does; AETHER adds whether the phone is on a local network, and the notes the
// screens show (BitChord's toasts).
package app.aether.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Which TVs are out there, and which one the music is on.
 *
 * The half of casting the screens talk to. Choosing a TV is the Cast framework's own business
 * (selecting its route starts a session), so this only lists routes, asks for one to be selected, and
 * reports what the session does about it. What plays on the TV is [CastPlayback]'s, handed each
 * session as it begins.
 *
 * Everything here is main-thread, like the router it wraps.
 */
object CastController {
    private const val TAG = "AETHER cast"

    /** How long after a session ends the phone's route is put back: past the router's own fallback. */
    private const val RESTORE_AFTER_MS = 400L

    data class Device(
        val id: String,
        val name: String,
        /** What the TV says it is ("Chromecast", "Google TV", a speaker group). */
        val description: String?,
        val connecting: Boolean,
        val connected: Boolean,
    )

    data class State(
        /** False until the framework is up, and for good where Google Play services are missing. */
        val supported: Boolean = false,
        val devices: List<Device> = emptyList(),
        val connectedName: String? = null,
        val connecting: Boolean = false,
        /** The phone is on a Wi-Fi or wired network, where TVs can be found and reached. */
        val onLocalNetwork: Boolean = false,
    ) {
        val casting: Boolean get() = connectedName != null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The TV's own volume, 0..1. */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    /** Things the listener should be told, a line each ("The TV couldn't play this one"). */
    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    internal fun note(words: String) {
        _notes.tryEmit(words)
    }

    /**
     * The music has just left this phone for a TV the listener chose (not a cast picked up again after
     * the app was away): the moment the screen marks, as the music goes out through its top.
     */
    private val _handoffs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val handoffs: SharedFlow<Unit> = _handoffs.asSharedFlow()

    internal fun handedOver() {
        _handoffs.tryEmit(Unit)
    }

    /** Debug builds (`--es aether.preview handoff`): the handoff's wave on its own, with no TV, to be looked at. */
    fun previewHandoff() = handedOver()

    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var started = false
    private var playback: CastPlayback? = null

    /** The session whose volume is being followed, so it can be let go of. */
    private var watched: CastSession? = null

    /** Where the phone's own sound went before a TV took it (the headphones' route, say): it goes back there. */
    private var beforeCast: String? = null

    private val volumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            watched?.let { _volume.value = it.volume.toFloat().coerceIn(0f, 1f) }
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = update { it.copy(connecting = true) }

        override fun onSessionStarted(session: CastSession, sessionId: String) = began(session, resumed = false)

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session start failed: $error")
            update { it.copy(connecting = false) }
            playback?.onConnectFailed()
        }

        override fun onSessionEnding(session: CastSession) {
            playback?.onSessionEnding(session)
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            stopWatching()
            update { it.copy(connecting = false, connectedName = null) }
            playback?.onSessionEnded(error)
            publishDevices()
            // After the router's own choice of where sound goes now, which this has to come after.
            main.postDelayed(::restoreRoute, RESTORE_AFTER_MS)
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = update { it.copy(connecting = true) }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = began(session, resumed = true)

        override fun onSessionResumeFailed(session: CastSession, error: Int) = update { it.copy(connecting = false) }

        // A dropped connection the framework is still trying to win back. Nothing to do: the TV keeps
        // playing what it has meanwhile.
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /**
     * Brings the framework up, once. Safe to call from anywhere, any number of times; where Google Play
     * services are absent it settles on unsupported and the cast key never appears.
     */
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        watchNetwork(app)
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            Log.i(TAG, "Google Play services unavailable; casting off")
            return
        }
        try {
            // The task form, so the framework's start-up isn't on the main thread of whichever screen
            // asked first.
            CastContext.getSharedInstance(app, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ctx -> onReady(app, ctx) }
                .addOnFailureListener { Log.w(TAG, "Cast framework unavailable", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Cast framework unavailable", e)
        }
    }

    private fun onReady(app: Context, ctx: CastContext) {
        castContext = ctx
        router = MediaRouter.getInstance(app)
        selector = ctx.mergedSelector
        ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        update { it.copy(supported = true) }
        // A session that outlived the process: the framework restores it and reports it through the
        // listener, but one already live by the time this runs would be reported to nobody.
        ctx.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let { began(it, resumed = true) }
    }

    /** The session up now, when there is one: the app speaks to AETHER's own receiver over it. */
    fun session(): CastSession? = castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected }

    /** Binds the service's playback half; it's handed any session already live. */
    fun attach(target: CastPlayback) {
        playback = target
        castContext?.sessionManager?.currentCastSession
            ?.takeIf { it.isConnected }
            ?.let { target.onSessionBegan(it, resumed = true) }
    }

    fun detach(target: CastPlayback) {
        if (playback === target) playback = null
    }

    /**
     * Keeps [State.devices] current until the returned function is called. [active] asks the router to
     * scan rather than merely listen, which finds a TV in a couple of seconds instead of a minute:
     * worth it only while somebody is looking at the list.
     */
    fun discover(active: Boolean): () -> Unit {
        val router = router ?: return {}
        val selector = selector ?: return {}
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishDevices()
            override fun onRouteSelected(router: MediaRouter, selected: MediaRouter.RouteInfo, reason: Int) = publishDevices()
            override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = publishDevices()
        }
        val flags = MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or if (active) MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN else 0
        router.addCallback(selector, callback, flags)
        publishDevices()
        return { router.removeCallback(callback) }
    }

    fun connect(deviceId: String) {
        val router = router ?: return
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        update { it.copy(connecting = true) }
        // The phone's own route, before the TV's is chosen: Bluetooth headphones, the speaker, a wired pair.
        router.selectedRoute.takeIf { it.isSystemRoute }?.let { beforeCast = it.id }
        router.selectRoute(route)
    }

    /**
     * Puts the phone's own sound back where it was before the TV. Ending a session, the router falls
     * back to a route of the phone's own, and on newer Android that's the phone itself even with
     * Bluetooth headphones connected: the music came back on the speaker, and Android held media there,
     * so headphones switched off and on again stuttered until the app was restarted. The route chosen
     * before is chosen again where it's still there; failing that, Bluetooth's, where there is one.
     */
    private fun restoreRoute() {
        val router = router ?: return
        val before = beforeCast
        beforeCast = null
        // A TV again by now (the listener chose another): nothing to put back.
        if (session() != null) return
        val selected = router.selectedRoute
        val wanted = before?.let { id -> router.routes.firstOrNull { it.id == id && it.isEnabled && it.isSystemRoute } }
            ?: router.bluetoothRoute?.takeIf { it.isEnabled && selected.isDeviceSpeaker }
            ?: return
        Log.i(TAG, "after the TV: ${selected.name} (bluetooth=${selected.isBluetooth}, speaker=${selected.isDeviceSpeaker}); back to ${wanted.name}")
        if (wanted.id != selected.id) router.selectRoute(wanted)
    }

    /**
     * Ends the session. [resumeHere] says the listener asked to bring the music back to this phone,
     * rather than the TV having gone away (see [CastPlayback.onSessionEnded]).
     */
    fun disconnect(resumeHere: Boolean) {
        playback?.resumeHereOnEnd = resumeHere
        castContext?.sessionManager?.endCurrentSession(true)
    }

    fun setVolume(level: Float) {
        val value = level.coerceIn(0f, 1f)
        _volume.value = value
        try {
            castContext?.sessionManager?.currentCastSession?.volume = value.toDouble()
        } catch (e: Exception) {
            Log.w(TAG, "could not set the TV's volume", e)
        }
    }

    private fun began(session: CastSession, resumed: Boolean) {
        watch(session)
        update { it.copy(connecting = false, connectedName = session.castDevice?.friendlyName ?: it.connectedName ?: "TV") }
        publishDevices()
        playback?.onSessionBegan(session, resumed)
    }

    private fun watch(session: CastSession) {
        stopWatching()
        watched = session
        session.addCastListener(volumeListener)
        _volume.value = session.volume.toFloat().coerceIn(0f, 1f)
    }

    private fun stopWatching() {
        watched?.removeCastListener(volumeListener)
        watched = null
    }

    private fun publishDevices() {
        val router = router
        val selector = selector
        val devices = if (router == null || selector == null) {
            emptyList()
        } else {
            router.routes
                .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
                .map {
                    Device(
                        id = it.id,
                        name = it.name,
                        description = it.description?.takeIf { d -> d.isNotBlank() },
                        connecting = it.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                        connected = it.isSelected,
                    )
                }
                .sortedBy { it.name.lowercase() }
        }
        update { it.copy(devices = devices) }
    }

    /** Whether the phone is on a Wi-Fi or wired network, followed as it changes. */
    private fun watchNetwork(app: Context) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return
        fun local(): Boolean = connectivity.allNetworks.any { n ->
            connectivity.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
        update { it.copy(onLocalNetwork = local()) }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = post { update { it.copy(onLocalNetwork = local()) } }
                override fun onLost(network: Network) = post { update { it.copy(onLocalNetwork = local()) } }
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = post { update { it.copy(onLocalNetwork = local()) } }
            })
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private inline fun update(change: (State) -> State) {
        _state.value = change(_state.value)
    }
}
