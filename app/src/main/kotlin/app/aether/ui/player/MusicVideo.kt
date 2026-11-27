package app.aether.ui.player

import android.content.Context
import android.os.SystemClock
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import app.aether.core.designsystem.theme.Motion
import app.aether.core.model.MediaKind
import app.aether.core.model.Outcome
import app.aether.core.model.Track
import app.aether.core.model.VideoStream
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Whether [track] is a video (an official music video, or an upload) rather than a song. */
val Track.isVideo: Boolean get() = kind == MediaKind.MUSIC_VIDEO || kind == MediaKind.VIDEO

/**
 * One video's place on Now Playing: how far up it is, which the print gives way to, and whether it's on
 * its way out. The pictures, and the player showing them, are kept with it for as long as it's there,
 * so turning the video off and on again only pauses it, as LastWave keeps its player.
 */
@Stable
class VideoSlot(val track: Track) {
    val up = Animatable(0f, visibilityThreshold = 0.002f)
    var leaving by mutableStateOf(false)

    internal var stream by mutableStateOf<VideoStream?>(null)
    internal var player by mutableStateOf<ExoPlayer?>(null)
    internal var framed by mutableStateOf(false)
    internal var aspect by mutableFloatStateOf(16f / 9f)
    internal var failed by mutableStateOf(false)

    /** How long the last jump took to show a picture: the next one aims that far ahead of the music. */
    internal var seekLeadMs = FirstSeekLeadMs

    @OptIn(UnstableApi::class)
    internal fun playerFor(context: Context, video: VideoStream, positionMs: Long): ExoPlayer = player ?: run {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(video.headers)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
        ExoPlayer.Builder(context).build().apply {
            // Pictures only: the sound is the engine's.
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            volume = 0f
            setMediaSource(ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(video.url)), positionMs + seekLeadMs)
            prepare()
        }.also { player = it }
    }

    internal fun release() {
        player?.release()
        player = null
        framed = false
    }
}

private val Rising = Motion.fluid(0.6f, 1f, 0.002f)
private val Going = Motion.fluid(0.32f, 1f, 0.002f)

/** A video with no picture by now isn't going to be worth waiting for: the cover stays. */
private const val GiveUpMs = 12_000L

/**
 * How tall a picture to ask for. The place it plays in is the width of a phone, where 1080 lines can't
 * be told from 720: 720 always (the music's bandwidth and the phone's battery are worth more), 480 on
 * the data saver.
 */
private fun tallest(saver: Boolean): Int = if (saver) 480 else 720

/**
 * A video playing as video on Now Playing, in place of its cover: its own pictures, in colour, the full
 * width of the screen where the print stands, in step with its sound. The sound is the song's, played
 * by the engine as always (its float output and its measured labels untouched); this plays only the
 * pictures, silent, and follows it: where the music is, the pictures are, nudged a little faster or
 * slower to stay with it, and set straight when they fall far behind or the music jumps. The cover is
 * there first and gives way once the first picture is in; a video that can't be had leaves the cover.
 */
@OptIn(UnstableApi::class)
@Composable
fun MusicVideo(slot: VideoSlot, playing: Boolean, onGone: () -> Unit, modifier: Modifier = Modifier, onFailed: () -> Unit = {}, allowed: Boolean = true) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val track = slot.track
    val saver by container.settings.efficient.collectAsStateWithLifecycle()
    val wanted by container.settings.songVideo.collectAsStateWithLifecycle()
    // Not [allowed] while Now Playing is closed: no video is fetched for a song nobody is looking at,
    // and one that was playing lets go of its player after a while, as when the key turns it off.
    val on = wanted && track.isVideo && !slot.leaving && allowed
    // The player goes when the slot does.
    DisposableEffect(slot) { onDispose { slot.release() } }
    LaunchedEffect(on) {
        if (!on || slot.stream != null) return@LaunchedEffect
        val source = container.sources.of(track.id) ?: return@LaunchedEffect
        when (val r = source.video(track.id, tallest(saver))) {
            is Outcome.Ok -> slot.stream = r.value
            is Outcome.Err -> slot.failed = true
        }
    }
    // Off for a while, it lets go of the player and what it had loaded; on again, it starts afresh.
    LaunchedEffect(on) {
        if (on) return@LaunchedEffect
        delay(LetGoAfterMs)
        slot.release()
    }
    LaunchedEffect(slot.stream, on, slot.framed) {
        if (slot.stream == null || !on || slot.framed) return@LaunchedEffect
        delay(GiveUpMs)
        if (!slot.framed) slot.failed = true
    }
    LaunchedEffect(slot.failed) { if (slot.failed && on) onFailed() }
    LaunchedEffect(slot.framed, on, slot.failed, slot.leaving) {
        slot.up.animateTo(if (slot.framed && on && !slot.failed) 1f else 0f, if (on && !slot.failed) Rising else Going)
        if (slot.leaving) onGone()
    }
    val showing by remember { derivedStateOf { slot.up.value > 0.002f } }
    val video = slot.stream ?: return
    if (slot.failed && !showing) return
    if (!on && !showing) return

    val player = slot.playerFor(context, video, container.playback.positionNow())
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { slot.framed = true }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) slot.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { slot.failed = true }
        }
        player.addListener(listener)
        onDispose {
            // Off the screen: paused where it is, kept for the key turning it back on. Its next picture
            // is on a new surface, so the cover stays until that's in.
            player.removeListener(listener)
            player.playWhenReady = false
            player.setPlaybackParameters(PlaybackParameters.DEFAULT)
            slot.framed = false
        }
    }
    // In step with the music: the engine's clock leads, the pictures follow it (LastWave's way). Only
    // a player that's ready is corrected, and a jump at most every so often, so that a jump still
    // loading is never jumped again; each aims ahead by what the last one took to land.
    val running = playing && resumed && on
    LaunchedEffect(player, running) {
        if (!running) {
            player.playWhenReady = false
            player.setPlaybackParameters(PlaybackParameters.DEFAULT)
            player.seekTo(container.playback.positionNow())
            return@LaunchedEffect
        }
        player.playWhenReady = true
        var jumpedAt = Long.MIN_VALUE / 2
        var landing = false
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            if (player.playbackState == Player.STATE_READY) {
                if (landing) {
                    landing = false
                    slot.seekLeadMs = ((slot.seekLeadMs + (now - jumpedAt)) / 2).coerceIn(0L, MaxSeekLeadMs)
                }
                val target = container.playback.positionNow()
                val drift = player.currentPosition - target
                val speed = when {
                    abs(drift) > JumpMs -> {
                        if (now - jumpedAt > JumpCooldownMs) {
                            player.seekTo(target + slot.seekLeadMs)
                            jumpedAt = now
                            landing = true
                        }
                        1f
                    }
                    abs(drift) > CatchUpMs -> if (drift < 0) 1.15f else 0.85f
                    abs(drift) > NudgeMs -> if (drift < 0) 1.05f else 0.95f
                    else -> 1f
                }
                if (player.playbackParameters.speed != speed) player.setPlaybackParameters(PlaybackParameters(speed))
            }
            delay(StepMs)
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // As wide as the screen, as tall as its shape asks, and never taller than the print's place.
        val aspect = slot.aspect
        val fitsWidth = aspect >= maxWidth / maxHeight
        val w = if (fitsWidth) maxWidth else maxHeight * aspect
        val h = if (fitsWidth) maxWidth / aspect else maxHeight
        Box(
            Modifier
                .size(w, h)
                .clipToBounds()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = slot.up.value
                }
                .drawWithCache {
                    // Its top and foot go to the black the screen is, by a hair, so it sits in it.
                    val feather = 10.dp.toPx() / size.height
                    val edges = Brush.verticalGradient(0f to Color.Transparent, feather to Color.Black, 1f - feather to Color.Black, 1f to Color.Transparent)
                    onDrawWithContent {
                        drawContent()
                        drawRect(edges, blendMode = BlendMode.DstIn)
                    }
                },
        ) {
            // A surface of its own for each player: set once, never swapped under a picture.
            key(player) {
                AndroidView(
                    factory = { c -> TextureView(c).also { player.setVideoTextureView(it) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** How often the pictures are compared with the music, and how far apart they may drift before each correction. */
private const val StepMs = 50L
private const val NudgeMs = 60L
private const val CatchUpMs = 200L
private const val JumpMs = 600L

/** A jump has this long to land before another is made. */
private const val JumpCooldownMs = 1_200L

/** What a first jump aims ahead by, and the most any does. */
private const val FirstSeekLeadMs = 300L
private const val MaxSeekLeadMs = 1_500L

/** Turned off this long, the video lets go of its player. */
private const val LetGoAfterMs = 30_000L
