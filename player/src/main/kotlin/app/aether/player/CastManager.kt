package app.aether.player

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouterParams
import app.aether.core.model.Outcome
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Provides the Google Cast receiver configuration for AETHER using the Default Media Receiver
 * (`CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`) and enables Android 13+
 * System Output Switcher (Seamless Media Transfer) discovery.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Manages Google Cast sessions, `MediaRouter` Output Switcher configuration (Seamless Media Transfer),
 * and playback handoff between the local [Player] and [CastPlayer].
 */
@OptIn(UnstableApi::class)
class CastManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val env: PlaybackEnvironment? = null,
    private val localPlayerProvider: () -> Player? = { null },
) : SessionAvailabilityListener {

    private val _isCasting = MutableStateFlow(false)
    val isCasting: StateFlow<Boolean> = _isCasting.asStateFlow()

    private val _castDeviceName = MutableStateFlow<String?>(null)
    val castDeviceName: StateFlow<String?> = _castDeviceName.asStateFlow()

    private val _routesAvailable = MutableStateFlow(false)
    val routesAvailable: StateFlow<Boolean> = _routesAvailable.asStateFlow()

    private var mediaRouter: MediaRouter? = null
    private var routeSelector: MediaRouteSelector? = null
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateRouteState(router)
        }

        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateRouteState(router)
        }

        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) {
            updateRouteState(router)
        }

        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            updateRouteState(router)
            if (!route.isDefault && route.matchesSelector( requireRouteSelector())) {
                _castDeviceName.value = route.name
            }
        }

        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) {
            updateRouteState(router)
            if (reason == MediaRouter.UNSELECT_REASON_STOPPED || reason == MediaRouter.UNSELECT_REASON_DISCONNECTED) {
                localPlayerProvider()?.pause()
            }
        }
    }

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _castDeviceName.value = session.castDevice?.friendlyName
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            _castDeviceName.value = session.castDevice?.friendlyName
            _isCasting.value = true
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _isCasting.value = false
            _castDeviceName.value = null
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            _isCasting.value = false
            _castDeviceName.value = null
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _castDeviceName.value = session.castDevice?.friendlyName
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _castDeviceName.value = session.castDevice?.friendlyName
            _isCasting.value = true
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _isCasting.value = false
            _castDeviceName.value = null
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /**
     * Initializes `MediaRouter` with `CastMediaControlIntent` selector and `MediaRouterParams`
     * enabling Android 13+ System Output Switcher and Seamless Media Transfer, and binds `CastPlayer`.
     */
    fun initialize() {
        runCatching {
            val router = MediaRouter.getInstance(context)
            val selector = MediaRouteSelector.Builder()
                .addControlCategory(
                    CastMediaControlIntent.categoryForCast(
                        CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                    ),
                )
                .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                .build()
            val params = MediaRouterParams.Builder()
                .setOutputSwitcherEnabled(true)
                .setTransferToLocalEnabled(true)
                .setMediaTransferRestrictedToSelfProviders(false)
                .build()
            router.routerParams = params
            router.addCallback(
                selector,
                routerCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            mediaRouter = router
            routeSelector = selector
            updateRouteState(router)
        }

        runCatching {
            val executor = Executors.newSingleThreadExecutor()
            CastContext.getSharedInstance(context, executor).addOnSuccessListener { shared ->
                runCatching {
                    castContext = shared
                    shared.sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
                    val cp = CastPlayer(shared)
                    cp.setSessionAvailabilityListener(this)
                    castPlayer = cp
                    if (cp.isCastSessionAvailable) {
                        onCastSessionAvailable()
                    }
                }
            }
        }
    }

    override fun onCastSessionAvailable() {
        _isCasting.value = true
        _castDeviceName.value = runCatching {
            castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        }.getOrNull()
        val local = localPlayerProvider() ?: return
        val currentItem = local.currentMediaItem ?: return
        val positionMs = local.currentPosition.coerceAtLeast(0L)
        val playWhenReady = local.playWhenReady
        local.pause()
        transferToCast(currentItem, positionMs, playWhenReady)
    }

    override fun onCastSessionUnavailable() {
        val cp = castPlayer
        val positionMs = cp?.currentPosition?.coerceAtLeast(0L) ?: 0L
        _isCasting.value = false
        _castDeviceName.value = null
        val local = localPlayerProvider() ?: return
        if (positionMs > 0L && local.currentMediaItem != null) {
            local.seekTo(positionMs)
        }
    }

    /**
     * Resolves a direct stream URL for [item] and sends it to the connected [CastPlayer] so the
     * Cast Default Media Receiver can stream the audio alongside track metadata and artwork.
     */
    fun transferToCast(item: MediaItem, positionMs: Long = 0L, playWhenReady: Boolean = true) {
        val cp = castPlayer ?: return
        if (!cp.isCastSessionAvailable) return
        val track = item.toTrack()
        scope.launch(Dispatchers.Main) {
            runCatching {
                val resolvedUrl = if (track != null && env != null) {
                    when (val res = env.stream(track.id, StreamPurpose.LISTEN, track)) {
                        is Outcome.Ok -> res.value.url to res.value.mimeType
                        is Outcome.Err -> null
                    }
                } else {
                    item.localConfiguration?.uri?.toString()?.let { it to item.localConfiguration?.mimeType }
                } ?: return@launch

                val (url, mime) = resolvedUrl
                val metadata = MediaMetadata.Builder()
                    .setTitle(track?.title ?: item.mediaMetadata.title)
                    .setArtist(track?.artistLine ?: item.mediaMetadata.artist)
                    .setAlbumTitle(track?.album?.title ?: item.mediaMetadata.albumTitle)
                    .setArtworkUri(track?.artwork?.url(1200)?.let { Uri.parse(it) } ?: item.mediaMetadata.artworkUri)
                    .build()
                val castItem = MediaItem.Builder()
                    .setMediaId(item.mediaId)
                    .setUri(url)
                    .setMimeType(mime ?: MimeTypes.AUDIO_MPEG)
                    .setMediaMetadata(metadata)
                    .build()
                cp.setMediaItem(castItem, positionMs)
                cp.playWhenReady = playWhenReady
                cp.prepare()
            }
        }
    }

    /**
     * Handles `MediaSession.Callback` routing/transfer commands for the System Output Switcher.
     */
    fun onCustomCommand(
        @Suppress("UNUSED_PARAMETER") session: MediaSession,
        customCommand: SessionCommand,
        @Suppress("UNUSED_PARAMETER") args: Bundle,
    ): SessionResult? {
        if (customCommand.customAction == ACTION_OPEN_OUTPUT_SWITCHER) {
            openOutputSwitcher(context)
            return SessionResult(SessionResult.RESULT_SUCCESS)
        }
        return null
    }

    fun release() {
        runCatching {
            mediaRouter?.removeCallback(routerCallback)
            castContext?.sessionManager?.removeSessionManagerListener(sessionManagerListener, CastSession::class.java)
            castPlayer?.setSessionAvailabilityListener(null)
            castPlayer?.release()
        }
        castPlayer = null
    }

    private fun requireRouteSelector(): MediaRouteSelector =
        routeSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()

    private fun updateRouteState(router: MediaRouter) {
        val selector = requireRouteSelector()
        val hasRoutes = router.routes.any { !it.isDefault && it.matchesSelector(selector) }
        _routesAvailable.value = hasRoutes
        val selected = router.selectedRoute
        if (!selected.isDefault && selected.matchesSelector(selector)) {
            _castDeviceName.value = selected.name
        }
    }

    companion object {
        const val ACTION_OPEN_OUTPUT_SWITCHER = "app.aether.player.ACTION_OPEN_OUTPUT_SWITCHER"

        /**
         * Launches the Android System Media Output Switcher sheet (or falls back to a
         * MediaRouter Cast route selector dialog when invoked from an [Activity]).
         */
        fun openOutputSwitcher(context: Context) {
            if (tryLaunchSystemOutputSwitcher(context)) return
            if (context is Activity && !context.isFinishing) {
                runCatching {
                    val router = MediaRouter.getInstance(context)
                    val selector = MediaRouteSelector.Builder()
                        .addControlCategory(
                            CastMediaControlIntent.categoryForCast(
                                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                            ),
                        )
                        .addControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO)
                        .addControlCategory(MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
                        .build()
                    val routes = router.routes.filter { it.isDefault || it.matchesSelector(selector) }
                    val names = routes.map { it.name }.toTypedArray()
                    val checked = routes.indexOfFirst { it.id == router.selectedRoute.id }.coerceAtLeast(0)
                    AlertDialog.Builder(context)
                        .setTitle("Audio output")
                        .setSingleChoiceItems(names, checked) { dialog, which ->
                            routes.getOrNull(which)?.select()
                            dialog.dismiss()
                        }
                        .show()
                }
            }
        }

        private fun tryLaunchSystemOutputSwitcher(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val pkg = context.packageName
            val intents = listOf(
                Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", pkg),
                Intent("android.settings.MEDIA_OUTPUT")
                    .putExtra("android.provider.extra.PACKAGE_NAME", pkg)
                    .putExtra("package_name", pkg),
            )
            for (intent in intents) {
                if (context !is Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val started = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (started) return true
            }
            return false
        }
    }
}
