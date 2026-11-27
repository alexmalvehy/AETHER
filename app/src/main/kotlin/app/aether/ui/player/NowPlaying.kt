@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.player

import app.aether.player.cast.CastController
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.DisposableEffect
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import app.aether.core.designsystem.component.Pull
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import app.aether.core.designsystem.art.SoftArt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.layout.onPlaced
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.DragSheet
import app.aether.core.designsystem.component.GlassCircle
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.TrackRow
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.clock
import app.aether.core.designsystem.component.dragsOpen
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.component.rememberDragState
import app.aether.core.designsystem.glass.GlassHost
import app.aether.core.designsystem.glass.GlassWeight
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.glass.liquidGlass
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Sans
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.designsystem.theme.retained
import app.aether.core.model.songCount
import app.aether.core.model.HeardFormat
import app.aether.core.model.Lyrics
import app.aether.core.model.Outcome
import app.aether.core.model.PlaybackState
import app.aether.core.model.PlaybackStatus
import app.aether.core.model.RepeatMode
import app.aether.core.model.Track
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.Load
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.rememberLoad
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import app.aether.core.designsystem.art.Pictures
import app.aether.ui.PrintPx
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import app.aether.core.designsystem.component.ClockDial
import app.aether.core.designsystem.component.Icon
import app.aether.core.designsystem.glass.liquidTouch
import app.aether.core.designsystem.glass.rememberLiquidState
import app.aether.core.designsystem.glass.translucent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import app.aether.ui.lyrics.LyricsStage
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.VisibilityThreshold
import app.aether.core.designsystem.component.litDrop
import app.aether.core.designsystem.component.DragState
import app.aether.core.designsystem.component.Grabber
import app.aether.core.designsystem.component.rememberSheetScroll
import app.aether.core.designsystem.theme.FluidSpring
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * Now Playing, drawn around the cover (the sketch): the way out in the top corner and where you
 * are in the queue beside it; the print; the song's name; one capsule holding every control but
 * the transport; and at the foot, a half dial whose red hand is the song's progress, with the
 * previous and next keys standing on it either side of the time. Everything a thumb needs is in
 * the lower half. It follows the finger: pull it down to leave, pull the capsule up for what's next.
 */
@Composable
fun NowPlaying(player: DragState, onClose: () -> Unit, menu: (@Composable () -> Unit)? = null, window: PlayerWindow? = null) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val state by container.playback.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    // Now Playing stays made while it's closed: building it was most of its first frame (~55 ms), and
    // letting it all go most of its last (~50 ms). What moves by itself in it rests while it's closed,
    // and what it fetches (a film, a video, the words) waits until it's been opened, as before.
    val shown by remember { derivedStateOf { player.visible } }
    // Closed, the dial holds where it was: the song's place moves four times a second, and Now Playing
    // stays in the picture while it's closed, so a dial following it would be drawn again unseen.
    val position = container.playback.position.collectAsStateWithLifecycle()
    val dialPosition = remember(position) { HeldWhile(position) { player.visible } }
    val treatmentIndex by container.settings.artwork.collectAsStateWithLifecycle()
    val treatment = ArtTreatment.Cycle[treatmentIndex.mod(ArtTreatment.Cycle.size)]
    // The words laid across the cover (its tattoo): the listener can have the cover alone.
    val coverWords by container.settings.coverWords.collectAsStateWithLifecycle()
    // The cover's light behind it, or black with the cover's faint glow, as it was.
    val coverLight by container.settings.coverLight.collectAsStateWithLifecycle()
    val queueSheet = rememberDragState()
    // Where the music plays: this phone, or a TV on its Wi-Fi (the cast key, beside the video's).
    val castSheet = rememberDragState()
    val cast by CastController.state.collectAsStateWithLifecycle()
    val lyricsPane = rememberDragState(give = Pull.Give, flick = Pull.Flick)
    val songVideo by container.settings.songVideo.collectAsStateWithLifecycle()
    val films = remember { mutableStateListOf(FilmSlot(track)) }
    // One key, kept: on, a song has its cover and then the cover's film, and a music video plays as
    // itself (its cover's film only if the video can't be had); off, just the cover.
    val videos = remember { mutableStateListOf(VideoSlot(track)) }
    val morph = remember { CoverMorphState() }
    LaunchedEffect(track.id, morph.carrying) {
        // A change of song under Automix's dots: its film comes once they've landed.
        if (morph.carrying) return@LaunchedEffect
        if (films.lastOrNull()?.track?.id != track.id) {
            films.lastOrNull()?.leaving = true
            films += FilmSlot(track)
        }
        if (videos.lastOrNull()?.track?.id != track.id) {
            videos.lastOrNull()?.leaving = true
            videos += VideoSlot(track)
        }
    }
    val artAllowed by container.settings.artistArt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val liked by container.library.isLiked(track.id).collectAsStateWithLifecycle(false)
    // A video plays without lyrics: its sound runs to its own clock (an intro, a scene, a longer cut),
    // so words timed to the song drift from it, and a video's title finds other songs' words. Nothing
    // is asked for one; the key says so, and the words close if one comes on.
    val playingVideo = track.isVideo
    val lyrics = rememberLyrics(track, fetch = shown && !playingVideo)
    LaunchedEffect(playingVideo) { if (playingVideo && lyricsPane.visible) lyricsPane.hide() }
    // Closed, it comes back as it always has: no words and no Up next left open under it.
    LaunchedEffect(shown) {
        if (!shown) {
            lyricsPane.dismiss()
            queueSheet.dismiss()
            castSheet.dismiss()
        }
    }
    val insets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()
    // Automix: where it will mix (on the dial), and its change of song (on the cover).
    val automixWindow = container.playback.automixWindow.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding()
        val stage = remember(maxWidth, maxHeight, top, bottom) { Stage(maxWidth, maxHeight, top, bottom) }
        val screen = with(LocalDensity.current) { maxHeight.toPx() }
        SideEffect { lyricsPane.travelPx = screen }
        // While the words' page follows the song, a pull down anywhere on it takes the lyrics away;
        // once it's been turned by hand, the page is read back first.
        val lyricsFollowing = remember { mutableStateOf(true) }
        val lyricsScroll = rememberSheetScroll(lyricsPane, pullsFirst = { lyricsFollowing.value })
        BackHandler(enabled = lyricsPane.visible) { lyricsPane.hide() }
        // Under a sheet or the lyrics, the print stops its grain (it's hidden, and every grain frame
        // would have every piece of glass over it drawn again); under the lyrics, which cover the
        // screen, Now Playing isn't drawn at all.
        var scrubbing by remember { mutableStateOf(false) }
        val lyricsUp by remember { derivedStateOf { lyricsPane.value >= 0.999f } }
        // While it's being pulled too: the print's grain would have all its glass drawn again under the finger.
        val moving by remember { derivedStateOf { player.value < 0.999f } }
        val still = queueSheet.visible || castSheet.visible || lyricsPane.visible || menu != null || scrubbing || moving
        val hidden = { lyricsPane.value >= 0.999f && !lyricsPane.holding }
        // Growing out of the mini player ([window]): the window opens and shows what's in it, everything
        // standing where it stands at rest ([Growth]). Read while drawing, so nothing is composed again.
        val grown: (part: (Float) -> Float) -> Float = { part -> if (window == null) 1f else part(player.value) }
        val density = LocalDensity.current
        val printDrawing = rememberGraphicsLayer()
        val printFlying = { window != null && abs(player.value - 1f) > 0.0005f }
        // The cover as itself is square and whole; a print is 4:5.
        val printSpot = if (treatment == ArtTreatment.Original) stage.cover else stage.print
        GlassHost(
            Modifier.fillMaxSize(),
            // Its own black is drawn below (it fades in as the window grows): the plane itself is clear.
            ground = Color.Transparent,
            hideContent = hidden,
            content = {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = grown(Growth::ground) }.background(Color.Black))
                // The cover's light, far behind everything: BitChord's mesh of its colours (Apple Music's
                // backdrop) across the top, orbiting while the song plays, going out round the dial in the
                // film's own rings.
                // A song's film is the cover's light itself, in the same rings: the light gives way to it as
                // it comes up, and isn't drawn at all under one that's fully up.
                val filmCover = {
                    if (treatment != ArtTreatment.Original) 0f else {
                        val videoUp = (videos.maxOfOrNull { it.up.value } ?: 0f).coerceIn(0f, 1f)
                        val filmUp = films.maxOfOrNull { film ->
                            val video = videos.lastOrNull { it.track.id == film.track.id }
                            if (!film.track.isVideo || video?.failed == true) film.up.value else 0f
                        } ?: 0f
                        (filmUp * (1f - videoUp) * morph.veil.floatValue * grown(Growth::film)).coerceIn(0f, 1f)
                    }
                }
                if (coverLight) AmbientLight(
                    track.artwork,
                    fade = stage.filmFade,
                    frame = stage.filmFoot,
                    upcoming = state.queue.getOrNull(state.index + 1)?.artwork,
                    running = shown && state.isPlaying && !still && !lyricsUp,
                    // Under a music video the video is the motion: the light holds still, and the screen is
                    // drawn only as the video's frames come (measured: the video at its full rate, the
                    // light costing what no light would).
                    rests = { filmCover() >= 0.999f || videos.any { it.up.value > 0.002f } },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = grown(Growth::ground) * (1f - filmCover()) },
                ) else {
                    // Black, as it was: a few dozen pixels of the cover, blurred once and cached, stretched
                    // over the screen, and gone to black down it.
                    SoftArt(track.artwork, Modifier.fillMaxSize().graphicsLayer { alpha = 0.34f * grown(Growth::ground) }, px = 32)
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = grown(Growth::ground) }
                            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.2f), 0.5f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black)),
                    )
                }
                // The cover as it is can move; a print of it stays a print. A music video plays as
                // itself instead, and only gives way to its film if it can't play.
                if (treatment == ArtTreatment.Original) films.toList().forEach { film ->
                    val video = videos.lastOrNull { it.track.id == film.track.id }
                    if (!film.track.isVideo || video?.failed == true) key(film) {
                        SongFilm(
                            film,
                            playing = state.isPlaying && !still && !lyricsUp && !morph.carrying,
                            ready = shown,
                            onGone = { films.remove(film) },
                            fade = stage.filmFade,
                            modifier = Modifier.fillMaxWidth().height(stage.filmFoot).graphicsLayer { alpha = (1f - (videos.maxOfOrNull { it.up.value } ?: 0f).coerceIn(0f, 1f)) * morph.veil.floatValue * grown(Growth::film) },
                        )
                    }
                }
                // A video, as itself, where the print stands: under a sheet or the lyrics it holds its picture.
                videos.toList().forEach { slot ->
                    key(slot) {
                        MusicVideo(
                            slot,
                            playing = state.isPlaying && !lyricsUp && !queueSheet.visible && shown,
                            allowed = shown,
                            onGone = { videos.remove(slot) },
                            onFailed = { actions.keep.note("This video won't play here, so its cover stays") },
                            modifier = Modifier.at(stage.screen).graphicsLayer { alpha = grown(Growth::film) },
                        )
                    }
                }
                Column(
                    Modifier.at(stage.heading).bloom(window, player),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top,
                ) {
                    Txt("Now playing", Type.Label, upper = true, modifier = Modifier.padding(top = 18.dp))
                    // On a TV, where it's playing says more than its place in the queue.
                    val on = cast.connectedName
                    if (on != null) Txt("On $on", Type.Meta, color = Ink.Grey, maxLines = 1)
                    else if (state.queue.size > 1) Txt("${state.index + 1} of ${state.queue.size}", Type.Meta, color = Ink.Grey)
                }
                Print(
                    track = track,
                    grain = !still,
                    filmUp = {
                        val film = if (treatment == ArtTreatment.Original) films.filter { f -> !f.track.isVideo || videos.any { it.track.id == f.track.id && it.failed } }.maxOfOrNull { it.up.value } ?: 0f else 0f
                        val up = maxOf(film, videos.maxOfOrNull { it.up.value } ?: 0f)
                        // In flight the print is itself, whatever plays over it at rest: it gives way to the song's
                        // film or video over the last of the way in, as they come up ([Growth.film]), and comes
                        // back from under them over the first of the way out.
                        if (window != null) up * Growth.film(player.value) else up
                    },
                    index = state.index,
                    treatment = treatment,
                    microtype = if (coverWords) microtype(track, state, lyrics) else emptyList(),
                    onSwipe = { dir -> haptics.strong(); if (dir > 0) actions.playback.previous() else actions.playback.next() },
                    veil = { morph.veil.floatValue },
                    instant = { morph.carrying },
                    modifier = Modifier
                        .at(printSpot)
                        .onPlaced { actions.keep.cover = it }
                        // Its drawing is kept: while the window grows or shrinks it's drawn flying, over
                        // everything else, and here only at rest. The same drawing both ways, so it lands as itself.
                        .then(if (window != null) Modifier.drawWithContent {
                            printDrawing.record { this@drawWithContent.drawContent() }
                            if (!printFlying()) drawLayer(printDrawing)
                        }.graphicsLayer {
                            // In flight it's one texture, drawn once at its own size and then only moved:
                            // drawn at a new size every frame, its shader, its picture and its words were
                            // all made again each frame (a print-sized upload, frames of 30-45 ms on the GPU).
                            compositingStrategy = if (printFlying()) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                        } else Modifier),
                )
                // Only while Now Playing is up: its dots are made ahead and follow the music frame by frame.
                if (shown) CoverMorph(
                    blends = container.playback.automixBlend,
                    planned = container.playback.automixPlanned,
                    queue = state.queue,
                    currentId = track.id.value,
                    treatment = treatment,
                    state = morph,
                    modifier = Modifier.at(printSpot).graphicsLayer { alpha = grown(Growth::landed) },
                )
                Song(
                    track = track,
                    state = state,
                    moving = shown,
                    onArtist = { track.artists.firstOrNull { it.id != null }?.id?.let { actions.go(Route.Artist(it)) } },
                    onAlbum = { track.album?.id?.let { actions.go(Route.Album(it)) } },
                    modifier = Modifier.at(stage.song).bloom(window, player),
                )
            },
            overlay = {
              Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden()) 0f else 1f }) {
               // Its top, where it stands: it comes in once the island has room for it.
               Box(Modifier.fillMaxSize().bloom(window, player)) {
                // The grabber: Now Playing is pulled down to go, or touched here.
                Grabber(player, Modifier.at(stage.grabber), touchHeight = stage.grabber.h)
                // It takes the place of the room's back key, whose arrow turned to point down as this
                // rose: it shows as it arrives, and leaves the same way.
                // The keys stand over black, where live glass would have nothing to bend: they're the
                // same glass drawn as on a page (its surface, its lit rim, the lens's edge), and cost
                // nothing on each frame the screen draws.
                GlassCircle(
                    Glyph.Down,
                    "Close",
                    onClose,
                    Modifier.at(stage.close).graphicsLayer { if (window == null) alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                    backdrop = null,
                )
                // One key for the moving picture, kept: a song's film, or a music video itself.
                run {
                    // A music video needs nothing else, nor does a cover from the community's index; Apple's
                    // films need the art from Apple's pages, and are said to when it's off.
                    GlassCircle(
                        Glyph.Video,
                        if (songVideo) "Video on" else "Video off",
                        {
                            haptics.tick()
                            container.settings.setSongVideo(!songVideo)
                            if (!songVideo && !track.isVideo && !artAllowed) actions.keep.note("Apple Music's films need Artist and album art in Settings")
                        },
                        Modifier.at(stage.film).graphicsLayer { if (window == null) alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                        lit = songVideo,
                        backdrop = null,
                    )
                }
                // Cast: beside the video's key, lit while a TV plays (where Google Cast is on this phone at all).
                if (cast.supported) GlassCircle(
                    Glyph.Cast,
                    cast.connectedName?.let { "Playing on $it" } ?: "Play on a TV",
                    {
                        haptics.tick()
                        castSheet.show()
                    },
                    Modifier.at(stage.cast).graphicsLayer { if (window == null) alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                    lit = cast.casting,
                    backdrop = null,
                )
               }
               // Its foot (the dial, the ring and the keys either side), where it stands, the same way.
               Box(Modifier.fillMaxSize().bloom(window, player)) {
                ClockDial(
                    position = dialPosition,
                    durationMs = state.durationMs,
                    playing = state.isPlaying,
                    onSeek = { actions.playback.seekTo(it) },
                    onScrubbing = { scrubbing = it },
                    running = !lyricsUp && shown,
                    window = { automixWindow.value?.let { it.start to it.end } },
                    modifier = Modifier.at(stage.dial),
                )
                // Over the dial, so its keys always take the touch where the two meet.
                RingControls(
                    stage = stage,
                    state = state,
                    liked = liked,
                    hasLyrics = !playingVideo && lyrics is Load.Ready && lyrics.value != null,
                    onLike = { scope.launch { container.library.setLiked(track, !liked) } },
                    onLyrics = { if (playingVideo) actions.keep.note("Videos play without lyrics") else lyricsPane.show() },
                    onQueue = { queueSheet.show() },
                    onMore = { actions.openMenu(track) },
                    pullUp = Modifier.dragsOpen(queueSheet),
                )
                GlassCircle(Glyph.Previous, "Previous", { haptics.strong(); actions.playback.previous() }, Modifier.at(stage.previous), size = stage.key, iconSize = 19.dp, backdrop = null)
                GlassCircle(Glyph.Next, "Next", { haptics.strong(); actions.playback.next() }, Modifier.at(stage.next), size = stage.key, iconSize = 19.dp, backdrop = null)
               }
               if (window != null) {
                   val place = with(density) { Rect(printSpot.x.toPx(), printSpot.y.toPx(), (printSpot.x + printSpot.w).toPx(), (printSpot.y + printSpot.h).toPx()) }
                   val corner = with(density) { PrintCorner.toPx() }
                   // The print in flight, over everything it passes (the song's lines, the ring), as the
                   // flying thing always is; then the pill's own cover over it, for the first moment.
                   FlyingPrint(window, printDrawing, place, corner, Modifier.fillMaxSize())
                   PillCover(track, window, print = place, corner = corner, modifier = Modifier.fillMaxSize())
               }
                DragSheet(queueSheet) { QueueSheet(state) }
                DragSheet(castSheet, height = 460.dp) { CastPicker(castSheet) }
                menu?.invoke()
              }
                // The words, full screen over everything; they follow the finger down to go.
                if (lyricsPane.visible) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationY = (1f - lyricsPane.value.coerceIn(0f, 1f)) * screen }
                            .nestedScroll(lyricsScroll)
                            .dragsOpen(lyricsPane),
                    ) { LyricsStage(track, lyricsPane, lyricsFollowing) }
                }
            },
        )
    }
}

/** A place on the screen: its top-left corner and its size. */
private class Spot(val x: Dp, val y: Dp, val w: Dp, val h: Dp)

private fun Modifier.at(spot: Spot): Modifier = this.offset(spot.x, spot.y).size(spot.w, spot.h)

/**
 * Where everything on Now Playing sits, worked out once from the screen: both planes (what glass
 * refracts, and the glass) are laid out from it, so they always agree. From the foot up: the dial,
 * the capsule, the song; the print takes what's left, at 4:5.
 */
private class Stage(w: Dp, h: Dp, top: Dp, bottom: Dp) {
    private val edge = Space.edge
    val close = Spot(edge, top + 6.dp, 44.dp, 44.dp)
    val film = Spot(w - edge - 44.dp, top + 6.dp, 44.dp, 44.dp)

    /** The cast key, beside the video's. */
    val cast = Spot(w - edge - 44.dp - 10.dp - 44.dp, top + 6.dp, 44.dp, 44.dp)

    /** Centred between the keys either side: as far in on the left as the two keys reach on the right. */
    val heading = Spot(edge + 108.dp, top + 2.dp, w - (edge + 108.dp) * 2, 52.dp)
    val grabber = Spot(w / 2 - 36.dp, top + 2.dp, 72.dp, 16.dp)
    private val radius = minOf((w - edge * 2) / 2, h * 0.2f)
    val dial = Spot(w / 2 - radius - 14.dp, h - bottom - radius - 20.dp, (radius + 14.dp) * 2, radius + 20.dp)
    private val pivotY = h - bottom - 6.dp
    val key = 52.dp
    val previous = Spot(w / 2 - radius * 0.47f - key / 2, pivotY - 36.dp - key / 2, key, key)
    val next = Spot(w / 2 + radius * 0.47f - key / 2, pivotY - 36.dp - key / 2, key, key)

    /** The housing round the dial: a band just outside its marks, as far round as the screen allows. */
    val ringInner = radius + 18.dp
    val ringOuter = radius + 66.dp

    /** Where the band starts above the horizontal, either side (radians): its ends stay 10 dp in from the screen's. */
    val ringReach = acos(((w / 2 - 10.dp) / ringOuter).coerceIn(0f, 0.97f)).coerceAtLeast(0.3f)

    /** The housing's box: the screen's width, from just above the band down to the dial's pivot. */
    val ring = Spot(0.dp, pivotY - ringOuter - 10.dp, w, ringOuter + 10.dp)
    val song = Spot(edge, ring.y + 10.dp - 8.dp - 96.dp, w - edge * 2, 96.dp)

    /** A song's film goes in rings round the dial's own centre: gone by the song's name, whole 200 dp further out. */
    private val filmGone = pivotY - song.y - 8.dp
    val filmFade = FilmFade(pivotY, filmGone, filmGone + 200.dp)

    /** Far enough down for the last of those rings, at the screen's edges where it falls lowest. */
    val filmFoot = pivotY - sqrt((filmGone.value * filmGone.value - (w.value / 2f) * (w.value / 2f)).coerceAtLeast(0f)).dp
    val print: Spot

    /** Where a video plays: the screen's full width across the print's place, the picture fitted inside it. */
    val screen: Spot

    /**
     * The cover shown as itself ([ArtTreatment.Original]): square, the whole of it, as wide as the print and
     * where the print's middle is. Only the prints (mono, halftone, letters, dots) are cut to 4:5.
     */
    val cover: Spot

    init {
        val areaTop = close.y + close.h + 16.dp
        val areaH = (song.y - 8.dp - areaTop).coerceAtLeast(120.dp)
        val pw = minOf(w - 60.dp, areaH * 0.8f)
        val ph = pw * 1.25f
        print = Spot((w - pw) / 2, areaTop + (areaH - ph) / 2, pw, ph)
        screen = Spot(0.dp, print.y, w, ph)
        cover = Spot(print.x, print.y + (ph - pw) / 2, pw, pw)
    }
}

/**
 * The song's name as every name in AETHER is set (the grotesk's capitals, as on Listen and in the
 * mini player), on one line; what qualifies it ("From GTA VI: Original Score", "feat. King
 * Princess") in the serif, small and grey, beneath; who made it (a way to them); and what the
 * decoder measured. A line too long for the screen drifts slowly sideways, fading at its edges.
 */
@Composable
private fun Song(track: Track, state: PlaybackState, moving: Boolean, onArtist: () -> Unit, onAlbum: () -> Unit, modifier: Modifier) {
    val (name, aside) = remember(track.title) { splitTitle(track.title) }
    // Playing from one of the listener's own sources, an exact copy: said beside what the decoder measured.
    val served by LocalContainer.current.sources.served.collectAsStateWithLifecycle()
    val from = served[track.id]?.takeIf { it.isNotBlank() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Drift(Modifier.then(if (track.album?.id != null) Modifier.quietClick(onAlbum) else Modifier), moving) {
            app.aether.core.designsystem.component.MarkedLine(name, track.explicit, SongTitle)
        }
        if (aside != null) {
            Spacer(Modifier.height(2.dp))
            Drift(moving = moving) { Txt(aside, Type.Meta, color = Ink.Grey, maxLines = 1) }
        }
        Spacer(Modifier.height(5.dp))
        Drift(Modifier.quietClick(onArtist), moving) { Txt(track.artistLine, Type.Body, color = Ink.Grey, maxLines = 1) }
        Spacer(Modifier.height(7.dp))
        when (state.status) {
            // The loader's dots only while they can be seen: closed, it would draw for no one.
            PlaybackStatus.LOADING, PlaybackStatus.BUFFERING -> if (moving) GridDots(size = 12.dp, grid = 3) else Spacer(Modifier.size(12.dp))
            PlaybackStatus.ERROR -> Txt(state.error ?: "Couldn't play", Type.Meta, color = Ink.Grey, align = TextAlign.Center, maxLines = 1)
            else -> {
                val measured = quality(state.heard)
                val route = if (state.bitPerfect) "Bit-perfect to ${state.outputDevice ?: "USB DAC"}" else null
                val source = from?.let { "From $it" }
                listOfNotNull(measured, route, source).joinToString("   ·   ").takeIf { it.isNotEmpty() }?.let { Txt(it, Type.Label, color = if (state.bitPerfect) Ink.White else Ink.Dim, upper = true, maxLines = 1) }
            }
        }
    }
}

/** The song's name on Now Playing: the caption capitals, a size up. */
private val SongTitle = Type.TitleSmall.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = 0.06.em)

/**
 * A title's own name, and what qualifies it: trailing brackets ("(feat. ...)", "[From ...]",
 * "- Remastered 2011") become a line of their own. Nothing is dropped; a title that is only
 * brackets stays whole.
 */
internal fun splitTitle(title: String): Pair<String, String?> {
    var name = title.trim()
    val asides = ArrayList<String>()
    while (true) {
        val m = Regex("""\s*[(\[]([^()\[\]]+)[)\]]\s*$""").find(name) ?: break
        val rest = name.substring(0, m.range.first).trim()
        if (rest.isEmpty()) break
        asides.add(0, m.groupValues[1].trim())
        name = rest
    }
    val dash = Regex("""\s+-\s+((?:\d{4}\s+)?(?:Remaster(?:ed)?|Live|Radio Edit|Acoustic|Mono|Stereo)[^-]*)$""", RegexOption.IGNORE_CASE).find(name)
    if (dash != null && dash.range.first > 0) {
        asides.add(0, dash.groupValues[1].trim())
        name = name.substring(0, dash.range.first).trim()
    }
    return name to asides.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

/**
 * One line that drifts sideways when it's too long to show, slowly, pausing at the start, and
 * fades at the edges as it goes; a line that fits sits still and centred. Still with reduced motion,
 * and while Now Playing is closed ([moving] false): a marquee asks for every frame.
 */
@Composable
private fun Drift(modifier: Modifier = Modifier, moving: Boolean = true, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current || !moving
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 18.dp.toPx().coerceAtMost(size.width / 4f)
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, fade / size.width to Color.Black, 1f - fade / size.width to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            if (reduce) Modifier.padding(horizontal = 18.dp)
            else Modifier.padding(horizontal = 18.dp).basicMarquee(
                iterations = Int.MAX_VALUE,
                initialDelayMillis = 1600,
                repeatDelayMillis = 2600,
                spacing = MarqueeSpacing(56.dp),
                velocity = 28.dp,
            ),
        ) { content() }
    }
}

/** One key on the housing. */
private class RingKey(val glyph: Glyph, val label: String, val on: Boolean = false, val dim: Boolean = false, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * Every control but the transport, set round the dial on a housing like its own: two hairline arcs
 * just outside the marks, a hairline between each key as the dial has between its marks, and no
 * glass. Shuffle, like, the words, play, what's next, repeat, more, left to right over the top;
 * play at the crown in a faintly lit segment. A key that's on is white with a point of light
 * beneath it. Pull up on any key for what's next.
 */
@Composable
private fun RingControls(
    stage: Stage,
    state: PlaybackState,
    liked: Boolean,
    hasLyrics: Boolean,
    onLike: () -> Unit,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    onMore: () -> Unit,
    pullUp: Modifier,
) {
    val actions = LocalActions.current
    val playing = state.isPlaying || state.status == PlaybackStatus.BUFFERING || state.status == PlaybackStatus.LOADING
    val keys = listOf(
        RingKey(Glyph.Shuffle, if (state.shuffle) "Shuffle on" else "Shuffle off", on = state.shuffle) { actions.playback.setShuffle(!state.shuffle) },
        RingKey(if (liked) Glyph.HeartFilled else Glyph.Heart, if (liked) "Liked" else "Like", on = liked, onClick = onLike),
        RingKey(Glyph.Lyrics, "Lyrics", dim = !hasLyrics, onClick = onLyrics),
        RingKey(if (playing) Glyph.Pause else Glyph.Play, if (playing) "Pause" else "Play", primary = true) { actions.playback.toggle() },
        RingKey(Glyph.Queue, "Up next", onClick = onQueue),
        RingKey(
            if (state.repeat == RepeatMode.ONE) Glyph.RepeatOne else Glyph.Repeat,
            when (state.repeat) { RepeatMode.OFF -> "Repeat off"; RepeatMode.ALL -> "Repeat all"; RepeatMode.ONE -> "Repeat one" },
            on = state.repeat != RepeatMode.OFF,
        ) { actions.playback.cycleRepeat() },
        RingKey(Glyph.More, "More", onClick = onMore),
    )
    val reach = stage.ringReach
    val slot = (PI.toFloat() - 2f * reach) / keys.size
    // Angles are measured from the right, upward, as on a dial; key i is centred at this one.
    fun angleOf(i: Int) = PI.toFloat() - reach - (i + 0.5f) * slot
    Box(Modifier.at(stage.ring)) {
        // The housing never moves: a picture of itself, kept.
        Canvas(Modifier.fillMaxSize().retained()) {
            val c = Offset(size.width / 2f, size.height)
            val ri = stage.ringInner.toPx()
            val ro = stage.ringOuter.toPx()
            val startDeg = -(180f - reach * 180f / PI.toFloat())
            val sweepDeg = 180f - 2f * reach * 180f / PI.toFloat()
            // The crown's segment, faintly lit, under play.
            val p = keys.indexOfFirst { it.primary }
            if (p >= 0) {
                val a0 = PI.toFloat() - reach - p * slot
                val a1 = a0 - slot
                val segment = Path().apply {
                    arcTo(Rect(c, ro), -a0 * 180f / PI.toFloat(), (a0 - a1) * 180f / PI.toFloat(), forceMoveTo = true)
                    arcTo(Rect(c, ri), -a1 * 180f / PI.toFloat(), -(a0 - a1) * 180f / PI.toFloat(), forceMoveTo = false)
                    close()
                }
                drawPath(segment, Ink.White.copy(alpha = 0.06f))
                drawArc(Ink.White.copy(alpha = 0.5f), -a0 * 180f / PI.toFloat(), (a0 - a1) * 180f / PI.toFloat(), false, Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2), style = Stroke(1.4.dp.toPx()))
            }
            drawArc(Ink.White.copy(alpha = 0.16f), startDeg, sweepDeg, false, Offset(c.x - ro, c.y - ro), Size(ro * 2, ro * 2), style = Stroke(0.8.dp.toPx()))
            drawArc(Ink.White.copy(alpha = 0.11f), startDeg, sweepDeg, false, Offset(c.x - ri, c.y - ri), Size(ri * 2, ri * 2), style = Stroke(0.8.dp.toPx()))
            // A hairline between each key, and at either end, like the dial's marks.
            for (b in 0..keys.size) {
                val a = PI.toFloat() - reach - b * slot
                val dir = Offset(cos(a), -sin(a))
                val end = b == 0 || b == keys.size
                val inset = if (end) 0f else 7.dp.toPx()
                drawLine(Ink.White.copy(alpha = if (end) 0.24f else 0.17f), c + dir * (ri + inset), c + dir * (ro - inset), strokeWidth = 0.8.dp.toPx())
            }
            // A point of light under each key that's on.
            keys.forEachIndexed { i, k ->
                if (k.on) {
                    val a = angleOf(i)
                    drawCircle(Ink.White, radius = 1.6.dp.toPx(), center = c + Offset(cos(a), -sin(a)) * (ri + 7.dp.toPx()))
                }
            }
        }
        val mid = (stage.ringInner + stage.ringOuter) / 2
        keys.forEachIndexed { i, k ->
            val a = angleOf(i)
            Box(
                Modifier
                    .offset(
                        x = stage.ring.w / 2 + mid * cos(a) - 24.dp,
                        y = stage.ring.h - mid * sin(a) - 24.dp,
                    )
                    .size(48.dp)
                    .semantics { role = Role.Button; contentDescription = k.label }
                    .clip(CircleShape)
                    .then(pullUp)
                    .quietClick(k.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    k.glyph,
                    size = if (k.primary) 24.dp else 19.dp,
                    color = when {
                        k.primary || k.on -> Ink.White
                        k.dim -> Ink.Dim
                        else -> Ink.White.copy(alpha = 0.78f)
                    },
                )
            }
        }
    }
}

/**
 * The format the decoder measured, never what a source claimed, as Now Playing says it (and a TV
 * showing AETHER, which is sent the same words). Nothing when unknown.
 */
internal fun quality(heard: HeardFormat?): String? {
    heard ?: return null
    val parts = buildList {
        heard.codec?.let { add(it.replace("MP4A-LATM", "AAC").replace("MP4A", "AAC")) }
        if (heard.lossless && heard.bitDepth != null) add("${heard.bitDepth}-bit")
        heard.sampleRateHz?.let { add("%.1f kHz".format(it / 1000f).replace(".0 ", " ")) }
        if (!heard.lossless) heard.bitrateKbps?.let { add("$it kbps") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("   ")
}

/**
 * A print on the stack: its picture wiped in over those beneath, developing into its tones. Each
 * runs to its end by itself, so a second skip mid-way lays the next print over this one, wherever it
 * had got to, rather than jumping back.
 */
private class PrintSheet(val track: Track, arriving: Boolean, val fromAbove: Boolean) {
    val wipe = Animatable(if (arriving) 0f else 1f, visibilityThreshold = 0.0008f)
    val develop = Animatable(if (arriving) 1f else 0f)
    val developing: () -> Float = { develop.value }
}

/** How long a next print is waited for before the wipe starts without it (ms). */
private const val PrintWaitMs = 1800L

/** The print's corners (Corners.Medium's radius), where the pill's cover lands. */
private val PrintCorner = 16.dp

/**
 * A part of Now Playing coming into the island as it grows ([Growth.bloom]): fading up from a little
 * smaller than it stands, about its own middle, once there's room for it. At rest, as it is.
 */
private fun Modifier.bloom(window: PlayerWindow?, player: DragState): Modifier =
    if (window == null) this else graphicsLayer {
        val p = player.value
        if (p >= 0.9995f && p <= 1.0005f) return@graphicsLayer
        alpha = Growth.bloom(p)
        val k = Growth.bloomScale(p)
        scaleX = k
        scaleY = k
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }

/** [live]'s value while [on] says so; otherwise the last value read, without asking [live] (so nothing follows it). */
private class HeldWhile<T>(private val live: State<T>, private val on: () -> Boolean) : State<T> {
    private var last = live.value
    override val value: T get() = if (on()) live.value.also { last = it } else last
}

/**
 * The print flying between the pill's cover and its place as the window grows and shrinks
 * ([PrintFlight]): its own drawing ([drawing], kept where it stands), scaled and moved, and cut to the
 * shape it has on the way, the pill's circle at one end and its own corners at the other. Drawn over
 * everything it passes; at rest it's drawn where it stands instead, and this draws nothing.
 */
@Composable
private fun FlyingPrint(window: PlayerWindow, drawing: GraphicsLayer, place: Rect, corner: Float, modifier: Modifier) {
    val path = remember { Path() }
    Spacer(
        modifier.drawBehind {
            val f = window.frame() ?: return@drawBehind
            if (abs(f.p - 1f) <= 0.0005f) return@drawBehind
            val art = f.art ?: return@drawBehind
            val flight = PrintFlight.at(art, place, f.p, corner)
            path.reset()
            path.addRoundRect(androidx.compose.ui.geometry.RoundRect(flight.clip, androidx.compose.ui.geometry.CornerRadius(flight.radius)))
            translate(place.left + flight.dx, place.top + flight.dy) {
                scale(flight.scale, flight.scale, pivot = Offset(place.width / 2f, place.height / 2f)) {
                    clipPath(path) { drawLayer(drawing) }
                }
            }
        },
    )
}

/**
 * The pill's own cover, over the print as the print leaves the pill and as it comes back into it
 * ([Growth.pillCover]): the same picture the pill shows, whole, in the same circle, at the place the
 * print is flying from (read where it is, every frame). It gives way to the print in the first moment,
 * while both are small and moving, so a print in mono, halftone or dither takes over where it can't be
 * told apart, and never at the end, where the print lands as itself.
 */
@Composable
private fun PillCover(track: Track, window: PlayerWindow, print: Rect, corner: Float, modifier: Modifier) {
    val large = app.aether.core.designsystem.art.rememberPicture(track.artwork.url(PrintPx), PrintPx)
    val small = app.aether.core.designsystem.art.rememberPicture(track.artwork.url(PillCoverPx), PillCoverPx)
    val path = remember { Path() }
    Spacer(
        modifier.drawBehind {
            val f = window.frame() ?: return@drawBehind
            if (f.p >= 0.9995f) return@drawBehind
            val alpha = Growth.pillCover(f.p)
            if (alpha <= 0.002f) return@drawBehind
            val art = f.art ?: return@drawBehind
            // The pill's own picture first, so the first frames are the pill's cover exactly.
            val picture = small ?: large ?: return@drawBehind
            // Past open while the spring settles, it goes a little past the print and comes back: its cushion.
            val r = app.aether.core.designsystem.motion.Becoming.lerp(art, print, f.p)
            if (r.width < 1f || r.height < 1f) return@drawBehind
            val radius = app.aether.core.designsystem.motion.Becoming.lerp(art.height / 2f, corner, f.p.coerceIn(0f, 1f))
            // The picture's middle, as wide or as tall as this frame's shape needs: the circle's square
            // crop at the start, the print's 4:5 at the end.
            val pw = picture.width.toFloat()
            val ph = picture.height.toFloat()
            val scale = kotlin.math.max(r.width / pw, r.height / ph)
            val sw = (r.width / scale).coerceAtMost(pw)
            val sh = (r.height / scale).coerceAtMost(ph)
            path.reset()
            path.addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(radius)))
            clipPath(path) {
                drawImage(
                    picture,
                    srcOffset = IntOffset(((pw - sw) / 2f).roundToInt(), ((ph - sh) / 2f).roundToInt()),
                    srcSize = androidx.compose.ui.unit.IntSize(sw.roundToInt(), sh.roundToInt()),
                    dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    alpha = alpha,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
        },
    )
}

/** The mini player's cover is asked for at this size: the same picture in memory as the pill's own. */
private const val PillCoverPx = 160

/**
 * The print. The next cover rises from below, blown out like paper just out of the bath, and
 * develops into its tones (the Now Playing recording). It starts once its picture is in hand: a wipe
 * over a picture that hadn't arrived was a wipe of nothing, the transition that failed to show.
 * Nothing is written on it: the cover is the cover.
 */
@Composable
private fun Print(
    track: Track,
    grain: Boolean,
    filmUp: () -> Float,
    index: Int,
    treatment: ArtTreatment,
    microtype: List<String>,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** How much of it shows: Automix's dots stand in for it while they carry a change of song. */
    veil: () -> Float = { 1f },
    /** A change of song made under Automix's dots: the new cover is simply there. */
    instant: () -> Boolean = { false },
) {
    val reduce = LocalReduceMotion.current
    val context = LocalContext.current
    val sheets = remember { mutableStateListOf(PrintSheet(track, arriving = false, fromAbove = false)) }
    var lastIndex by remember { mutableIntStateOf(index) }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(track.id) {
        if (sheets.last().track.id == track.id) return@LaunchedEffect
        // Going on, the next print rises from below; going back, the earlier one comes down from above.
        val fromAbove = index < lastIndex
        lastIndex = index
        if (reduce || instant()) {
            sheets.clear()
            sheets += PrintSheet(track, arriving = false, fromAbove = false)
            return@LaunchedEffect
        }
        withTimeoutOrNull(PrintWaitMs) { Pictures.fetch(context, track.artwork.url(PrintPx), PrintPx) }
        val sheet = PrintSheet(track, arriving = true, fromAbove = fromAbove)
        sheets += sheet
        while (sheets.size > 3) sheets.removeAt(0)
        // In a scope of its own: the next skip doesn't stop this one.
        scope.launch {
            launch {
                sheet.wipe.animateTo(1f, Motion.fluid(0.9f, 1f, 0.0008f))
                // All of it is over those beneath: they're gone.
                val at = sheets.indexOf(sheet)
                if (at > 0) sheets.removeRange(0, at)
            }
            // Tone, not movement: it comes up at the darkroom's own pace.
            sheet.develop.animateTo(0f, tween(1900, delayMillis = 450, easing = Motion.Editorial))
        }
    }

    BoxWithConstraints(
        modifier
            .graphicsLayer {
                // The finger's pull, resisted as Apple's rubber band resists, so the print leans
                // rather than slides off.
                val pull = FluidSpring.rubberBand(drag.value, size.width.coerceAtLeast(1f))
                translationX = pull * 0.6f
                rotationZ = pull / 600f
                // It gives way to the song's film, and comes back when that goes; and to Automix's dots.
                alpha = (1f - filmUp().coerceIn(0f, 1f)) * veil()
                clip = true
                shape = Corners.Medium
            }
            .pointerInput(Unit) {
                // Sideways only: a vertical pull belongs to Now Playing itself.
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                var raw = 0f
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); raw = drag.value },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        // Where the flick would carry it decides, not just how far it was pulled.
                        val landing = FluidSpring.project(raw, v)
                        if (abs(landing) > size.width * 0.35f) onSwipe(if (landing > 0) 1 else -1)
                        scope.launch { drag.animateTo(0f, Motion.snappy(), initialVelocity = v) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f, Motion.snappy()) } },
                ) { change, dx ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    raw += dx
                    scope.launch { drag.snapTo(raw) }
                }
            },
    ) {
        sheets.forEach { sheet ->
            key(sheet) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1f + 0.035f * (1f - sheet.wipe.value)
                            transformOrigin = TransformOrigin(0.5f, if (sheet.fromAbove) 0f else 1f)
                        }
                        .drawWithContent {
                            val w = sheet.wipe.value
                            when {
                                w >= 0.999f -> drawContent()
                                sheet.fromAbove -> clipRect(bottom = size.height * w) { this@drawWithContent.drawContent() }
                                else -> clipRect(top = size.height * (1f - w)) { this@drawWithContent.drawContent() }
                            }
                        },
                ) {
                    // Only the top sheet's grain lives; a plain picture has none to keep alive.
                    val alive = grain && sheet === sheets.last() && treatment != ArtTreatment.Original
                    TreatedArtwork(sheet.track.artwork, treatment, Modifier.fillMaxSize(), sizeHintPx = PrintPx, develop = sheet.developing, animateGrain = alive)
                }
            }
        }
        Microtype(microtype, Modifier.fillMaxSize())
    }
}

private val MicroStyle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.W600, fontSize = 6.5.sp, lineHeight = 8.sp, letterSpacing = 0.08.em)

/** The print's tattoo: capitals the size of a caption, laid across it in a loose grid. */
@Composable
private fun Microtype(lines: List<String>, modifier: Modifier) {
    if (lines.isEmpty()) return
    val color = Ink.White.copy(alpha = 0.86f)
    Box(modifier.padding(10.dp)) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween) {
            lines.take(4).forEach { Txt(it, MicroStyle, color = color, upper = true, maxLines = 1, modifier = Modifier.width(72.dp)) }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.Center).offset(y = 56.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            lines.drop(4).take(9).chunked(3).forEach { col ->
                Column(Modifier.width(84.dp)) { col.forEach { Txt(it, MicroStyle, color = color, upper = true, maxLines = 2) } }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter), horizontalArrangement = Arrangement.SpaceBetween) {
            lines.takeLast(3).forEach { Txt(it, MicroStyle, color = color, upper = true, maxLines = 1, modifier = Modifier.width(96.dp)) }
        }
    }
}

/** What the tattoo says: the house and the song's place, its words where it has them, its name. */
private fun microtype(track: Track, state: PlaybackState, lyrics: Load<Lyrics?>): List<String> {
    val head = listOf("AETHER", "N\u00ba ${state.index + 1}", track.year?.toString() ?: (track.album?.title ?: ""), quality(state.heard)?.substringBefore("   ") ?: "")
    val words = (lyrics as? Load.Ready)?.value?.text?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() && it.length < 40 }?.distinct()?.take(9)
    val middle = words ?: listOfNotNull(track.artistLine, track.album?.title, track.durationMs?.let { clock(it) }, track.plays, if (track.explicit) "Explicit" else null)
    val tail = listOf(track.title, track.artists.firstOrNull()?.name ?: "", track.durationMs?.let { clock(it) } ?: "")
    return head + middle.take(9).let { it + List((9 - it.size).coerceAtLeast(0)) { "" } } + tail
}

/** The song's words, from its source, kept for the session. Null when it has none. Asked for only when [fetch]. */
@Composable
fun rememberLyrics(track: Track, fetch: Boolean = true): Load<Lyrics?> {
    val container = LocalContainer.current
    val (load, _) = rememberLoad("lyrics:${track.id}", enabled = fetch) {
        val source = container.sources.of(track.id) ?: return@rememberLoad Outcome.Ok(null)
        when (val next = source.upNext(track)) {
            is Outcome.Ok -> next.value.lyrics?.let { source.lyrics(it) } ?: Outcome.Ok(null)
            is Outcome.Err -> Outcome.Ok(null)
        }
    }
    return load
}

/** A song in Up next as the list holds it: the song, its place in the queue, and a key that stays with it. */
private class QueueEntry(val track: Track, val index: Int, val key: String)

/**
 * Up next. Moving a song is BitChord's (its queue, `PlayerQueue.kt`): the song in hand is drawn over the
 * list where the finger holds it, placed from the finger alone, so nothing the list does underneath (a
 * swap, a relayout, a scroll) can move it. Its own row stays in the list unseen and trades places with
 * its neighbours the moment it's carried halfway over one, sent to the queue then and there, and they
 * slide aside to show where it will be. Held near the list's top or foot, the list scrolls on under it.
 */
@Composable
private fun QueueSheet(state: PlaybackState) {
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val container = LocalContainer.current
    val list = rememberLazyListState()
    val first = state.index.coerceAtLeast(0)
    // A stable name for each queued song (its id, and which repeat of it this is), so a song keeps its
    // row while it's carried and while the queue moves round it.
    val entries = remember(state.queue, first) {
        val seen = HashMap<String, Int>()
        state.queue.drop(first).mapIndexed { i, t ->
            val n = seen.merge(t.id.value, 1, Int::plus)!!
            QueueEntry(t, first + i, "${t.id.value}#$n")
        }
    }
    // The song Automix is bringing in is already playing: it stays next, and nothing goes in front of it.
    val mixingIn by container.playback.automixIncoming.collectAsStateWithLifecycle()
    val heldNext = mixingIn != null && entries.getOrNull(1)?.track?.id?.value == mixingIn
    val movable = remember(entries, heldNext) { entries.drop(if (heldNext) 2 else 1) }
    val drag = rememberQueueDrag(list, movable) { from, to ->
        haptics.tick()
        actions.playback.move(from, to)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.edge, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt("Up next", Type.Display)
                Spacer(Modifier.height(6.dp))
                Strapline(songCount(state.upcoming.size))
            }
            if (state.upcoming.isNotEmpty()) GlassPill("Clear", icon = Glyph.Trash, onClick = { actions.playback.clearUpcoming() }, small = true)
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = Space.edge), state = list) {
                itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
                    val isHeld = drag.held?.key == e.key
                    // Scrolled far enough to be let go of, the row takes its gesture with it: the move ends here.
                    if (isHeld) DisposableEffect(Unit) { onDispose { drag.end(e.key, settle = false) } }
                    val locked = i == 1 && heldNext
                    QueueRow(
                        track = e.track,
                        current = i == 0,
                        mixingIn = locked,
                        movable = i > 0 && !locked,
                        onPlay = { actions.playback.skipTo(e.index) },
                        onRemove = { actions.playback.remove(e.index) },
                        onDragStart = { if (drag.start(e.key)) haptics.strong() },
                        onDrag = drag::drag,
                        onDragEnd = { drag.end(e.key) },
                        modifier = if (isHeld) {
                            Modifier.alpha(0f)
                        } else {
                            Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = Motion.fluid(0.35f, 0.9f, IntOffset.VisibilityThreshold))
                        },
                    )
                }
            }
            // The song in hand, over the list where the finger holds it.
            drag.held?.let { held ->
                Box(Modifier.matchParentSize().padding(horizontal = Space.edge)) {
                    QueueRow(
                        track = held.track,
                        current = false,
                        mixingIn = false,
                        movable = true,
                        lifted = { drag.lift.value },
                        interactive = false,
                        onPlay = {},
                        onRemove = {},
                        modifier = Modifier.offset { IntOffset(0, drag.heldTop.roundToInt()) },
                    )
                }
            }
        }
    }
}

/**
 * How far in from either end of the list a carried song starts scrolling it, and how fast it scrolls
 * once it's right at the edge: about six rows a second (BitChord's numbers).
 */
private val QueueEdgeZone = 40.dp
private val QueueEdgeSpeed = 340.dp

/**
 * The pace, in pixels a second, to scroll the list at while a song spanning [top] to [bottom] is held
 * in a list showing [viewportStart] to [viewportEnd]: negative towards its start, positive towards its
 * end, nothing while it's clear of both. From a fifth of [speed] as it enters the [zone], so the list
 * visibly moves at once, up to all of it at the edge.
 */
internal fun edgeScrollSpeed(top: Float, bottom: Float, viewportStart: Int, viewportEnd: Int, zone: Float, speed: Float): Float {
    if (zone <= 0f) return 0f
    val intoStart = (viewportStart + zone) - top
    val intoEnd = bottom - (viewportEnd - zone)
    val reach = when {
        intoStart > 0f && intoEnd <= 0f -> -intoStart
        intoEnd > 0f && intoStart <= 0f -> intoEnd
        else -> return 0f
    }
    val ramp = speed * (0.2f + 0.8f * (abs(reach) / zone).coerceAtMost(1f))
    return if (reach < 0f) -ramp else ramp
}

@Composable
private fun rememberQueueDrag(list: androidx.compose.foundation.lazy.LazyListState, rows: List<QueueEntry>, onMove: (Int, Int) -> Unit): QueueDrag {
    val scope = rememberCoroutineScope()
    val drag = remember(list) { QueueDrag(list, scope) }
    drag.rows = rows
    drag.onMove = onMove
    with(LocalDensity.current) {
        drag.edgeZone = QueueEdgeZone.toPx()
        drag.edgeSpeed = QueueEdgeSpeed.toPx()
    }
    // Held near either edge, the song scrolls the list under itself. The finger stays put, so each
    // frame's scroll is settled as if the list had moved and the song hadn't: it trades places with
    // the rows going past on the same terms.
    val direction = drag.autoScrollDir
    LaunchedEffect(drag, direction) {
        if (direction == 0) return@LaunchedEffect
        list.scroll {
            var previous = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A dropped frame paid back in full lands as a lurch, so it isn't.
                val seconds = ((now - previous) / 1_000_000_000f).coerceAtMost(1f / 30f)
                previous = now
                if (scrollBy(drag.autoScrollSpeed * seconds) == 0f) break
                drag.scrolled()
            }
        }
    }
    return drag
}

/**
 * The one move Up next can have at a time (BitChord's `QueueDrag`). Everything turns on where the finger
 * holds the song's middle in the list's view; only the finger moves it. Swaps are read off the live
 * layout against it, each sent to the queue the moment the song crosses a neighbour, and the song itself
 * is drawn at [heldTop] over the list, never from its own row, which jumps a whole row the frame a swap
 * lands. Let go, it settles into its row on a spring.
 */
@androidx.compose.runtime.Stable
private class QueueDrag(private val list: androidx.compose.foundation.lazy.LazyListState, private val scope: kotlinx.coroutines.CoroutineScope) {
    var rows: List<QueueEntry> = emptyList()
    var onMove: (Int, Int) -> Unit = { _, _ -> }
    var edgeZone = 0f
    var edgeSpeed = 0f

    /** The song in hand; null at rest. */
    var held by mutableStateOf<QueueEntry?>(null)
        private set

    /** Where the song in hand is drawn: its top in the list's view, kept on screen. */
    var heldTop by mutableFloatStateOf(0f)
        private set

    /** How far it has lifted out of the list. */
    val lift = Animatable(0f)

    /** -1, 0 or 1: state, as it starts and stops the scrolling. */
    var autoScrollDir by mutableIntStateOf(0)
        private set

    /** Signed, pixels a second. Not state: only the scrolling reads it, once a frame. */
    var autoScrollSpeed = 0f
        private set

    private var heldCenter = 0f
    private var heldSize = 0

    /** Where the last swap sent will put the song, until the list shows it there. */
    private var awaiting: Int? = null
    private var landing: kotlinx.coroutines.Job? = null

    fun start(key: String): Boolean {
        if (held != null) return false
        val entry = rows.firstOrNull { it.key == key } ?: return false
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return false
        heldSize = item.size
        heldCenter = item.offset + item.size / 2f
        heldTop = item.offset.toFloat()
        awaiting = null
        held = entry
        scope.launch { lift.animateTo(1f, Motion.bouncy()) }
        return true
    }

    fun drag(deltaY: Float) = settle(deltaY)

    fun scrolled() = settle(0f)

    /** Let go: into its row on a spring, then the row shows again. [settle] false: gone at once. */
    fun end(key: String, settle: Boolean = true) {
        if (held?.key != key || landing?.isActive == true) return
        awaiting = null
        setAutoScroll(0f)
        val slot = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (!settle || slot == null) {
            held = null
            scope.launch { lift.snapTo(0f) }
            return
        }
        landing = scope.launch {
            kotlinx.coroutines.coroutineScope {
                launch {
                    // Into its row as the row is now, every frame: a list still moving under it (a fling,
                    // a song arriving above) mustn't leave it landing where its row was when it was let go.
                    val glide = app.aether.core.designsystem.theme.FluidValue(heldTop, FluidSpring.Snappy)
                    var target = slot.offset.toFloat()
                    var last = withFrameNanos { it }
                    while (true) {
                        val now = withFrameNanos { it }
                        list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { target = it.offset.toFloat() }
                        glide.retarget(target)
                        heldTop = glide.step(((now - last) / 1e9f).coerceIn(0f, 1f / 30f))
                        last = now
                        if (glide.resting) break
                    }
                }
                launch { lift.animateTo(0f, Motion.snappy()) }
            }
            held = null
        }
    }

    private fun settle(deltaY: Float) {
        val key = held?.key ?: return
        if (landing?.isActive == true) return
        val rows = rows
        val info = list.layoutInfo
        val items = info.visibleItemsInfo
        val half = heldSize / 2f
        // Stopped at the ends of the songs it can trade with, where those are on screen.
        var center = heldCenter + deltaY
        rows.firstOrNull()?.let { f -> items.firstOrNull { it.key == f.key }?.let { center = center.coerceAtLeast(it.offset + half) } }
        rows.lastOrNull()?.let { l -> items.firstOrNull { it.key == l.key }?.let { center = center.coerceAtMost(it.offset + it.size - half) } }
        heldCenter = center
        val top = center - half
        val minTop = info.viewportStartOffset.toFloat()
        heldTop = top.coerceIn(minTop, (info.viewportEndOffset - heldSize).toFloat().coerceAtLeast(minTop))

        // Its row is out of view: nothing to swap against until the list brings it back.
        val dragged = items.firstOrNull { it.key == key } ?: return setAutoScroll(0f)
        aimAutoScroll(top, key)
        // A swap already sent but not laid out yet: deciding another off the old layout would send it twice.
        awaiting?.let {
            if (dragged.index != it) return
            awaiting = null
        }
        val target = items
            .filter { item -> item.key != key && rows.any { it.key == item.key } }
            .minByOrNull { abs(it.offset + it.size / 2f - center) }
            ?: return
        // Half a row of overlap a swap, so a song isn't traded back and forth over a pixel of travel.
        if (abs(center - (target.offset + target.size / 2f)) > target.size / 2f) return
        // The list keeps its place by its first visible row: move that one while there's list above it
        // and the whole list follows by a row. Wait for the scroll to bring the next one in.
        if (target.index == list.firstVisibleItemIndex && list.canScrollBackward) return
        val from = rows.firstOrNull { it.key == key }?.index ?: return
        val to = rows.firstOrNull { it.key == target.key }?.index ?: return
        onMove(from, to)
        awaiting = target.index
    }

    /** Only while there's a song that way to trade with, and list left to scroll. */
    private fun aimAutoScroll(top: Float, key: String) {
        val info = list.layoutInfo
        val speed = edgeScrollSpeed(top, top + heldSize, info.viewportStartOffset, info.viewportEndOffset, edgeZone, edgeSpeed)
        val blocked = when {
            speed < 0f -> rows.firstOrNull()?.key == key || !list.canScrollBackward
            speed > 0f -> rows.lastOrNull()?.key == key || !list.canScrollForward
            else -> true
        }
        setAutoScroll(if (blocked) 0f else speed)
    }

    private fun setAutoScroll(speed: Float) {
        autoScrollSpeed = speed
        val direction = when {
            speed > 0f -> 1
            speed < 0f -> -1
            else -> 0
        }
        if (autoScrollDir != direction) autoScrollDir = direction
    }
}

/**
 * One song in Up next: touched, it plays; its handle (three hairlines, as iOS draws its own) moves it,
 * and so does a long press anywhere on it. [lifted] is the copy drawn under the finger while it's carried.
 */
@Composable
private fun QueueRow(
    track: Track,
    current: Boolean,
    /** Automix is bringing this song in: it can't be moved or taken out until it plays. */
    mixingIn: Boolean,
    movable: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    lifted: () -> Float = { 0f },
    /** False for the copy under the finger: the touch stays with the row in the list. */
    interactive: Boolean = true,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val dragging = movable && interactive
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val l = lifted()
                val k = 1f + 0.035f * l
                scaleX = k
                scaleY = k
                shadowElevation = 16.dp.toPx() * l
                shape = Corners.Medium
                clip = false
                alpha = if (mixingIn) 0.55f else 1f
            }
            // Lifted, it's a card of its own: dark beneath, so the songs it passes over don't show
            // through, and lit at its rim as the selector is.
            .drawWithCache {
                val outline = Corners.Medium.createOutline(size, layoutDirection, this)
                onDrawBehind {
                    val l = lifted().coerceIn(0f, 1f)
                    if (l > 0.004f) drawOutline(outline, Color(0xFF151515).copy(alpha = 0.96f * l))
                }
            }
            .litDrop(Corners.Medium) { lifted() * 0.9f }
            .then(
                if (!dragging) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start() },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    ) { change, amount ->
                        change.consume()
                        move(amount.y)
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            TrackRow(track, onClick = if (interactive) onPlay else ({}), playing = current, trailing = {})
        }
        if (mixingIn) {
            // Already playing, rising under this song: it stays where it is until it's on.
            Strapline("Mixing in", Modifier.padding(end = 12.dp))
        } else if (!current) {
            IconAction(Glyph.Close, "Remove from queue", if (interactive) onRemove else ({}), size = 14.dp)
            Box(
                Modifier
                    .size(44.dp)
                    .semantics { contentDescription = "Move ${track.title}" }
                    .then(
                        if (!dragging) Modifier else Modifier.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { start() },
                                onDragEnd = { end() },
                                onDragCancel = { end() },
                            ) { change, amount ->
                                change.consume()
                                move(amount.y)
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp, 12.dp)) {
                    val c = Ink.White.copy(alpha = if (lifted() > 0.5f) 0.95f else 0.45f)
                    val w = 1.2.dp.toPx()
                    for (k in 0..2) {
                        val yy = size.height * k / 2f
                        drawLine(c, Offset(0f, yy), Offset(size.width, yy), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
            }
        }
    }
}

