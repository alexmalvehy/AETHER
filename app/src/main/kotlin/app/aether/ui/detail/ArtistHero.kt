package app.aether.ui.detail

import androidx.media3.exoplayer.source.ProgressiveMediaSource
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.ConnectivityManager
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Album
import app.aether.core.model.ArtistArt
import app.aether.core.model.ArtistPage
import app.aether.core.model.Track
import app.aether.ui.FilmCache
import app.aether.ui.LocalRoomsHidden
import app.aether.ui.common.LocalContainer
import app.aether.ui.pageLanded
import app.aether.ui.placedWhile

/** A little taller than it's wide: a portrait fills it without being cropped into. */
internal const val HeroAspect = 0.94f

/** Black and white, with a little contrast about the middle: the film as the page's pictures are printed. */
internal val FilmPrint = ArtTreatment.Monochrome(contrast = 1.2f, grain = 0.6f)

/**
 * The head of an artist's page. The source's picture is there first, always, on any connection. If
 * there's a portrait it comes up over that, and if there's a film, that comes up over the portrait
 * from its first frame, however long the connection took to bring it. The artist's name is set in
 * the script until their own lettering, for those who have one, comes up in its place, in white.
 */
@Composable
fun ArtistHero(page: ArtistPage) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val consent by container.settings.artistArt.collectAsStateWithLifecycle()
    val saver by container.settings.efficient.collectAsStateWithLifecycle()
    val id = page.artist.id.value
    val art by produceState<ArtistArt?>(container.artistArt.cached(id).takeIf { consent }, id, consent) {
        if (!consent) {
            value = null
            return@produceState
        }
        val own = page.sections.flatMap { it.items }.filter { it is Album || it is Track }.map { it.title }
        value = container.artistArt.find(id, page.artist.name, page.topTracks.map { it.title } + own)
    }
    val portrait = art?.picture
    val logo = art?.logo
    // No film on the data saver.
    val film = art?.film?.takeIf { !saver }

    val hidden = LocalRoomsHidden.current
    val elsewhere by remember { derivedStateOf { hidden() } }
    val landed = pageLanded()

    // Each comes up over what's beneath only once it's really here, and what it has covered stops
    // being drawn at all.
    val portraitUp = remember { Animatable(0f, visibilityThreshold = 0.002f) }
    val filmUp = remember { Animatable(0f, visibilityThreshold = 0.002f) }
    val logoUp = remember { Animatable(0f, visibilityThreshold = 0.002f) }
    var filmFramed by remember { mutableStateOf(false) }
    val portraitUrl = portrait?.url(PortraitPx)
    LaunchedEffect(portraitUrl) {
        if (portraitUrl == null) return@LaunchedEffect
        Pictures.fetch(context, portraitUrl, PortraitPx)
        portraitUp.animateTo(1f, Rising)
    }
    LaunchedEffect(logo) {
        if (logo == null) return@LaunchedEffect
        Pictures.fetch(context, logo, LogoPx)
        logoUp.animateTo(1f, Rising)
    }
    LaunchedEffect(filmFramed) { if (filmFramed) filmUp.animateTo(1f, Rising) }

    val portraitCovers by remember { derivedStateOf { portraitUp.value >= 0.999f } }
    val filmCovers by remember { derivedStateOf { filmUp.value >= 0.999f } }

    Box(Modifier.fillMaxWidth().aspectRatio(HeroAspect).clipToBounds()) {
        // The source's picture is a wide banner: blown up to fill this box it was only a pair of eyes.
        // It's only a little larger than it is, below the status bar's frost so that no face is under
        // it, and falls away to black above and below.
        val banner = page.artist.artwork
        val wide = banner.aspect > 1.15f
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.padding(top = BannerTop).aspectRatio((banner.aspect / BannerZoom).coerceAtLeast(HeroAspect)) else Modifier.fillMaxSize())
                .placedWhile { !portraitCovers && !filmCovers },
        ) {
            TreatedArtwork(banner, FilmPrint, Modifier.fillMaxSize(), sizeHintPx = 1440)
            if (wide) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black, 0.22f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black)))
        }
        if (portrait != null) {
            Box(Modifier.fillMaxSize().placedWhile { !filmCovers }.graphicsLayer { alpha = portraitUp.value }) {
                TreatedArtwork(portrait, FilmPrint, Modifier.fillMaxSize(), sizeHintPx = PortraitPx)
            }
        }
        // Started once the page has arrived, so that making a player never touches its slide.
        if (film != null && landed) {
            HeroFilm(
                url = film,
                playing = !elsewhere,
                onFirstFrame = { filmFramed = true },
                // A square film in a box a little taller than wide: as wide as it needs to keep its shape.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1f / HeroAspect
                    alpha = filmUp.value
                },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.5f), 0.35f to Color.Transparent, 1f to Color.Black)))
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.BottomCenter) {
                // As large as the screen allows, whole words only: "PinkPantheress" shrinks to fit rather
                // than leaving "ss" alone on a line, and a name of several words is set smaller.
                app.aether.core.designsystem.component.FittedName(
                    page.artist.name,
                    Type.name(page.artist.name),
                    modifier = Modifier.padding(horizontal = Space.edge).graphicsLayer { alpha = 1f - logoUp.value },
                )
                if (logo != null) {
                    Picture(
                        logo,
                        LogoPx,
                        // As wide as it may be and no taller than the name's place: a square-ish mark like
                        // BTS's fits by its height, and the page's lines below keep theirs.
                        Modifier
                            .padding(bottom = 4.dp)
                            .widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.68f)
                            .heightIn(max = LogoTallest)
                            .aspectRatio(art?.logoAspect ?: 1f)
                            .graphicsLayer { alpha = logoUp.value },
                        colorFilter = ColorFilter.tint(Color.White),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            page.monthlyAudience?.let {
                Spacer(Modifier.height(12.dp))
                Strapline(it, color = Color.White.copy(alpha = 0.78f))
            }
        }
    }
}

/** How much larger than the screen's width a wide banner stands, so that it isn't a thin strip. */
private const val BannerZoom = 1.5f

/** Where a wide banner starts: below the status bar's frost. */
private val BannerTop = 64.dp

/** The tallest an artist's lettering stands. */
private val LogoTallest = 108.dp

private const val PortraitPx = 1400
internal const val LogoPx = 1000
internal val Rising = Motion.fluid(0.7f, 1f, 0.002f)

/**
 * A short film, looping and silent, in black and white unless told otherwise. It never touches the music: it
 * has no sound, asks for no audio focus, and plays at the best quality that fits the screen's width (half
 * of it on a metered connection), from its first frame. What's been fetched is kept, so it starts at once the next time. It rests when it's
 * out of sight or the app is, and is let go when the page is.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun HeroFilm(url: String, playing: Boolean, onFirstFrame: () -> Unit, modifier: Modifier, mono: Boolean = true, finest: Boolean = false, onVideoAspect: (Float) -> Unit = {}, playlist: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(url, finest, playlist) {
        val metered = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.isActiveNetworkMetered == true
        val side = if (metered) 540 else 1080
        val data = CacheDataSource.Factory()
            .setCache(FilmCache.of(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                // As wide as the screen, and as tall as a portrait film needs (a square cap took a tall
                // film's 1080 x 1920 down to its 608 x 1080 copy).
                .setMaxVideoSize(side, side * 2)
                // A song's own film on Now Playing, on any connection: the finest copy there is
                // (Apple's run to 2160 square and 2048 x 2732), drawn down to the screen, sharper than
                // a screen-sized copy drawn up; only what this phone can decode is ever chosen.
                .apply { if (finest) clearVideoSizeConstraints() }
                // The best that fits, from the first frame: a film only plays on a fast connection, and a
                // short loop never ran long enough for the player to climb from where it guessed low.
                .setForceHighestSupportedBitrate(true)
                .build()
            // Apple's films are HLS playlists; a song's cover from the community's index is a video file.
            setMediaSource(
                if (playlist) {
                    HlsMediaSource.Factory(data).createMediaSource(
                        MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build(),
                    )
                } else {
                    ProgressiveMediaSource.Factory(data).createMediaSource(MediaItem.fromUri(url))
                },
            )
            // The player's own pick lands mid-ladder on Apple's playlists (a 4.9 Mbps copy of a film
            // that has 7, 10 and 20): once the copies are known, the film is set to the richest one
            // this phone can decode within the size allowed, trick-play copies aside.
            val wide = if (finest) 8192 else side
            addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    if (trackSelectionParameters.overrides.isNotEmpty()) return
                    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return
                    val best = (0 until group.length)
                        .filter { group.isTrackSupported(it) }
                        .map { it to group.getTrackFormat(it) }
                        .filter { (_, f) -> f.roleFlags and C.ROLE_FLAG_TRICK_PLAY == 0 && f.width in 1..wide && f.height <= wide * 2 }
                        .maxByOrNull { (_, f) -> f.bitrate }?.first ?: return
                    trackSelectionParameters = trackSelectionParameters.buildUpon()
                        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, best))
                        .build()
                }
            })
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = onFirstFrame()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) onVideoAspect(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    DisposableEffect(lifecycle, playing, player) {
        player.playWhenReady = playing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> player.playWhenReady = playing
                Lifecycle.Event.ON_STOP -> player.playWhenReady = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { c ->
            TextureView(c).also { view ->
                if (mono) view.setLayerPaint(Paint().apply { colorFilter = ColorMatrixColorFilter(filmMatrix()) })
                player.setVideoTextureView(view)
            }
        },
        modifier = modifier,
    )
}

/** No colour, and the print's contrast about the middle. */
private fun filmMatrix(): ColorMatrix {
    val contrast = 1.2f
    val offset = (1f - contrast) * 128f
    val r = 0.2126f * contrast
    val g = 0.7152f * contrast
    val b = 0.0722f * contrast
    return ColorMatrix(floatArrayOf(r, g, b, 0f, offset, r, g, b, 0f, offset, r, g, b, 0f, offset, 0f, 0f, 0f, 1f, 0f))
}
