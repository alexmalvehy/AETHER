@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.player

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
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
import app.aether.core.designsystem.art.SoftArt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
fun NowPlaying(player: DragState, onClose: () -> Unit, menu: (@Composable () -> Unit)? = null) {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val state by container.playback.state.collectAsStateWithLifecycle()
    val track = state.current ?: return
    val treatmentIndex by container.settings.artwork.collectAsStateWithLifecycle()
    val treatment = ArtTreatment.Cycle[treatmentIndex.mod(ArtTreatment.Cycle.size)]
    val queueSheet = rememberDragState()
    val lyricsPane = rememberDragState(give = Pull.Give, flick = Pull.Flick)
    val songVideo by container.settings.songVideo.collectAsStateWithLifecycle()
    val films = remember { mutableStateListOf(FilmSlot(track)) }
    // One key, kept: on, a song has its cover and then the cover's film, and a music video plays as
    // itself (its cover's film only if the video can't be had); off, just the cover.
    val videos = remember { mutableStateListOf(VideoSlot(track)) }
    LaunchedEffect(track.id) {
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
    val lyrics = rememberLyrics(track)
    val insets = WindowInsets.systemBarsIgnoringVisibility.asPaddingValues()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding()
        val stage = remember(maxWidth, maxHeight, top, bottom) { Stage(maxWidth, maxHeight, top, bottom) }
        val screen = with(LocalDensity.current) { maxHeight.toPx() }
        SideEffect { lyricsPane.travelPx = screen }
        val lyricsScroll = rememberSheetScroll(lyricsPane)
        BackHandler(enabled = lyricsPane.visible) { lyricsPane.hide() }
        // Under a sheet or the lyrics, the print stops its grain (it's hidden, and every grain frame
        // would have every piece of glass over it drawn again); under the lyrics, which cover the
        // screen, Now Playing isn't drawn at all.
        var scrubbing by remember { mutableStateOf(false) }
        val lyricsUp by remember { derivedStateOf { lyricsPane.value >= 0.999f } }
        // While it's being pulled too: the print's grain would have all its glass drawn again under the finger.
        val moving by remember { derivedStateOf { player.value < 0.999f } }
        val still = queueSheet.visible || lyricsPane.visible || menu != null || scrubbing || moving
        val hidden = { lyricsPane.value >= 0.999f && !lyricsPane.holding }
        GlassHost(
            Modifier.fillMaxSize(),
            hideContent = hidden,
            content = {
                // The cover's light, far behind everything. A few dozen
                // pixels of the cover, blurred once and cached, stretched over the screen.
                SoftArt(track.artwork, Modifier.fillMaxSize().alpha(0.34f), px = 32)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.2f), 0.5f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black)))
                // The cover as it is can move; a print of it stays a print. A music video plays as
                // itself instead, and only gives way to its film if it can't play.
                if (treatment == ArtTreatment.Original) films.toList().forEach { film ->
                    val video = videos.lastOrNull { it.track.id == film.track.id }
                    if (!film.track.isVideo || video?.failed == true) key(film) {
                        SongFilm(
                            film,
                            playing = state.isPlaying && !still && !lyricsUp,
                            onGone = { films.remove(film) },
                            fade = stage.filmFade,
                            modifier = Modifier.fillMaxWidth().height(stage.filmFoot).graphicsLayer { alpha = 1f - (videos.maxOfOrNull { it.up.value } ?: 0f).coerceIn(0f, 1f) },
                        )
                    }
                }
                // A video, as itself, where the print stands: under a sheet or the lyrics it holds its picture.
                videos.toList().forEach { slot ->
                    key(slot) {
                        MusicVideo(
                            slot,
                            playing = state.isPlaying && !lyricsUp && !queueSheet.visible,
                            onGone = { videos.remove(slot) },
                            onFailed = { actions.keep.note("This video won't play here, so its cover stays") },
                            modifier = Modifier.at(stage.screen),
                        )
                    }
                }
                Column(Modifier.at(stage.heading), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top) {
                    Txt("Now playing", Type.Label, upper = true, modifier = Modifier.padding(top = 18.dp))
                    if (state.queue.size > 1) Txt("${state.index + 1} of ${state.queue.size}", Type.Meta, color = Ink.Grey)
                }
                Print(
                    track = track,
                    grain = !still,
                    filmUp = {
                        val film = if (treatment == ArtTreatment.Original) films.filter { f -> !f.track.isVideo || videos.any { it.track.id == f.track.id && it.failed } }.maxOfOrNull { it.up.value } ?: 0f else 0f
                        maxOf(film, videos.maxOfOrNull { it.up.value } ?: 0f)
                    },
                    index = state.index,
                    treatment = treatment,
                    microtype = microtype(track, state, lyrics),
                    onSwipe = { dir -> haptics.strong(); if (dir > 0) actions.playback.previous() else actions.playback.next() },
                    modifier = Modifier.at(stage.print).onPlaced { actions.keep.cover = it },
                )
                Song(
                    track = track,
                    state = state,
                    onArtist = { track.artists.firstOrNull { it.id != null }?.id?.let { actions.go(Route.Artist(it)) } },
                    onAlbum = { track.album?.id?.let { actions.go(Route.Album(it)) } },
                    modifier = Modifier.at(stage.song),
                )
            },
            overlay = {
              Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (hidden()) 0f else 1f }) {
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
                    Modifier.at(stage.close).graphicsLayer { alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                    backdrop = null,
                )
                val context = LocalContext.current
                val isCasting by container.cast.isCasting.collectAsStateWithLifecycle()
                GlassCircle(
                    Glyph.Cast,
                    if (isCasting) "Casting" else "Cast",
                    {
                        haptics.tick()
                        app.aether.player.CastManager.openOutputSwitcher(context)
                    },
                    Modifier.at(stage.cast).graphicsLayer { alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                    lit = isCasting,
                    backdrop = null,
                )
                // One key for the moving picture, kept: a song's film, or a music video itself.
                run {
                    // A music video needs nothing else; a song's film needs the art from Apple's pages.
                    val lit = songVideo && (track.isVideo || artAllowed)
                    GlassCircle(
                        Glyph.Video,
                        if (lit) "Video on" else "Video off",
                        {
                            haptics.tick()
                            if (!songVideo && !track.isVideo && !artAllowed) actions.keep.note("Turn on Artist and album art in Settings")
                            else container.settings.setSongVideo(!songVideo)
                        },
                        Modifier.at(stage.film).graphicsLayer { alpha = ((player.value - 0.86f) / 0.14f).coerceIn(0f, 1f) },
                        lit = lit,
                        backdrop = null,
                    )
                }
                ClockDial(
                    position = container.playback.position.collectAsStateWithLifecycle(),
                    durationMs = state.durationMs,
                    playing = state.isPlaying,
                    onSeek = { actions.playback.seekTo(it) },
                    onScrubbing = { scrubbing = it },
                    running = !lyricsUp,
                    modifier = Modifier.at(stage.dial),
                )
                // Over the dial, so its keys always take the touch where the two meet.
                RingControls(
                    stage = stage,
                    state = state,
                    liked = liked,
                    hasLyrics = lyrics is Load.Ready && lyrics.value != null,
                    onLike = { scope.launch { container.library.setLiked(track, !liked) } },
                    onLyrics = { lyricsPane.show() },
                    onQueue = { queueSheet.show() },
                    onMore = { actions.openMenu(track) },
                    pullUp = Modifier.dragsOpen(queueSheet),
                )
                GlassCircle(Glyph.Previous, "Previous", { haptics.strong(); actions.playback.previous() }, Modifier.at(stage.previous), size = stage.key, iconSize = 19.dp, backdrop = null)
                GlassCircle(Glyph.Next, "Next", { haptics.strong(); actions.playback.next() }, Modifier.at(stage.next), size = stage.key, iconSize = 19.dp, backdrop = null)
                DragSheet(queueSheet) { QueueSheet(state) }
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
                    ) { LyricsStage(track, lyricsPane) }
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
    val cast = Spot(w - edge - 44.dp - 8.dp - 44.dp, top + 6.dp, 44.dp, 44.dp)
    val film = Spot(w - edge - 44.dp, top + 6.dp, 44.dp, 44.dp)
    val heading = Spot(edge + 56.dp, top + 2.dp, w - (edge + 56.dp) * 2, 52.dp)
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

    init {
        val areaTop = close.y + close.h + 16.dp
        val areaH = (song.y - 8.dp - areaTop).coerceAtLeast(120.dp)
        val pw = minOf(w - 60.dp, areaH * 0.8f)
        val ph = pw * 1.25f
        print = Spot((w - pw) / 2, areaTop + (areaH - ph) / 2, pw, ph)
        screen = Spot(0.dp, print.y, w, ph)
    }
}

/**
 * The song's name as every name in AETHER is set (the grotesk's capitals, as on Listen and in the
 * mini player), on one line; what qualifies it ("From GTA VI: Original Score", "feat. King
 * Princess") in the serif, small and grey, beneath; who made it (a way to them); and what the
 * decoder measured. A line too long for the screen drifts slowly sideways, fading at its edges.
 */
@Composable
private fun Song(track: Track, state: PlaybackState, onArtist: () -> Unit, onAlbum: () -> Unit, modifier: Modifier) {
    val (name, aside) = remember(track.title) { splitTitle(track.title) }
    // Playing from one of the listener's own sources, an exact copy: said beside what the decoder measured.
    val served by LocalContainer.current.sources.served.collectAsStateWithLifecycle()
    val from = served[track.id]?.takeIf { it.isNotBlank() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Drift(Modifier.then(if (track.album?.id != null) Modifier.quietClick(onAlbum) else Modifier)) {
            Txt(name, SongTitle, maxLines = 1)
        }
        if (aside != null) {
            Spacer(Modifier.height(2.dp))
            Drift { Txt(aside, Type.Meta, color = Ink.Grey, maxLines = 1) }
        }
        Spacer(Modifier.height(5.dp))
        Drift(Modifier.quietClick(onArtist)) { Txt(track.artistLine, Type.Body, color = Ink.Grey, maxLines = 1) }
        Spacer(Modifier.height(7.dp))
        when (state.status) {
            PlaybackStatus.LOADING, PlaybackStatus.BUFFERING -> GridDots(size = 12.dp, grid = 3)
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
 * fades at the edges as it goes; a line that fits sits still and centred. Still with reduced motion.
 */
@Composable
private fun Drift(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
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
        Canvas(Modifier.fillMaxSize()) {
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

/** The format the decoder measured, never what a source claimed. Nothing when unknown. */
private fun quality(heard: HeardFormat?): String? {
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
        if (reduce) {
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
                // It gives way to the song's film, and comes back when that goes.
                alpha = 1f - filmUp().coerceIn(0f, 1f)
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

/** The song's words, from its source, kept for the session. Null when it has none. */
@Composable
fun rememberLyrics(track: Track): Load<Lyrics?> {
    val container = LocalContainer.current
    val (load, _) = rememberLoad("lyrics:${track.id}") {
        val source = container.sources.of(track.id) ?: return@rememberLoad Outcome.Ok(null)
        when (val next = source.upNext(track)) {
            is Outcome.Ok -> next.value.lyrics?.let { source.lyrics(it) } ?: Outcome.Ok(null)
            is Outcome.Err -> Outcome.Ok(null)
        }
    }
    return load
}

@Composable
private fun QueueSheet(state: PlaybackState) {
    val actions = LocalActions.current
    val haptics = LocalHaptics.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val first = state.index
    val songs = remember(state.queue, first) { state.queue.drop(first.coerceAtLeast(0)) }
    // A stable name for each queued song (its id, and which repeat of it this is), so a song keeps
    // its row while it's carried: keyed by position, the row was a new row after one step, and the
    // drag was lost.
    val keys = remember(songs) {
        val seen = HashMap<String, Int>()
        songs.map { t -> val n = seen.merge(t.id.value, 1, Int::plus)!!; "${t.id.value}#$n" }
    }
    // The song being carried: where it was (in the queue), how far the finger has taken it, how far
    // it has lifted out of the list; and, once let go, the move waiting for the queue to take it.
    var carried by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableIntStateOf(-1) }
    val offset = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }
    var landing by remember { mutableStateOf(false) }
    val rowH = with(density) { 62.dp.toPx() }
    fun target(): Int = if (from < 0) -1 else (from + kotlin.math.round(offset.value / rowH).toInt()).coerceIn(first + 1, state.queue.lastIndex)
    var aimed by remember { mutableIntStateOf(-1) }

    // Once the queue has taken the move, the carried song is where it was dropped: it lets go there.
    LaunchedEffect(keys) {
        if (landing) {
            offset.snapTo(0f)
            carried = null
            from = -1
            landing = false
            lift.animateTo(0f, Motion.snappy())
        }
    }
    // Carried near the list's top or foot, the list scrolls on under it.
    val carrying = carried != null && !landing
    LaunchedEffect(carrying) {
        if (!carrying) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        var then = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - then) / 1e9f).coerceIn(0f, 0.05f)
            then = now
            val info = list.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == carried } ?: continue
            val top = item.offset + offset.value
            val bottom = top + item.size
            val speed = with(density) { 520.dp.toPx() } * dt
            val push = when {
                top < edge -> -speed * ((edge - top) / edge).coerceIn(0f, 1f)
                bottom > info.viewportEndOffset - edge -> speed * ((bottom - (info.viewportEndOffset - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (push != 0f) {
                val moved = list.scrollBy(push)
                offset.snapTo(offset.value + moved)
            }
            val t = target()
            if (t != aimed) { aimed = t; haptics.tick() }
        }
    }

    fun pickUp(key: String, index: Int) {
        if (landing) return
        carried = key
        from = index
        aimed = index
        haptics.strong()
        scope.launch { offset.snapTo(0f) }
        scope.launch { lift.animateTo(1f, Motion.bouncy()) }
    }
    fun carry(dy: Float) {
        if (carried == null || landing) return
        scope.launch { offset.snapTo(offset.value + dy) }
        val t = target()
        if (t != aimed) { aimed = t; haptics.tick() }
    }
    fun drop() {
        if (carried == null || landing) return
        val to = target()
        val start = from
        haptics.light()
        scope.launch {
            // It settles into the gap it was aimed at, then the queue moves it there for real.
            offset.animateTo((to - start) * rowH, Motion.snappy())
            if (to != start) {
                landing = true
                actions.playback.move(start, to)
                // If the queue doesn't answer, it goes back where it was.
                kotlinx.coroutines.delay(1200)
                if (landing) { landing = false; offset.animateTo(0f, Motion.snappy()); carried = null; from = -1; lift.animateTo(0f, Motion.snappy()) }
            } else {
                carried = null
                from = -1
                lift.animateTo(0f, Motion.snappy())
            }
        }
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
                itemsIndexed(songs, key = { i, _ -> keys[i] }) { i, t ->
                    val index = first + i
                    val key = keys[i]
                    val isCarried = key == carried
                    QueueRow(
                        track = t,
                        current = i == 0,
                        carried = isCarried,
                        offset = { if (isCarried) offset.value else 0f },
                        lift = { if (isCarried) lift.value else 0f },
                        onPlay = { actions.playback.skipTo(index) },
                        onRemove = { actions.playback.remove(index) },
                        onPickUp = { pickUp(key, index) },
                        onCarry = ::carry,
                        onDrop = ::drop,
                        modifier = Modifier
                            .zIndex(if (isCarried) 1f else 0f)
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = if (isCarried) null else Motion.fluid(0.4f, 0.9f, IntOffset.VisibilityThreshold)),
                    )
                }
            }
            // Where it will land: a line of light in the gap, gliding from gap to gap.
            LandingLine(list = list, carried = { carried }, from = { from }, target = ::target, keys = keys, first = first, lift = { lift.value })
        }
    }
}

/**
 * The line showing where a carried song will land: a hairline of light across the gap, brightest at
 * its start, where a small point of light sits. It glides between gaps on a spring and shows only
 * while a song is carried away from its own place.
 */
@Composable
private fun LandingLine(
    list: androidx.compose.foundation.lazy.LazyListState,
    carried: () -> String?,
    from: () -> Int,
    target: () -> Int,
    keys: List<String>,
    first: Int,
    lift: () -> Float,
) {
    val y = remember { Animatable(0f) }
    val shown = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            val c = carried() ?: return@snapshotFlow null
            val t = target()
            val f = from()
            if (t < 0 || t == f) return@snapshotFlow -1f
            val info = list.layoutInfo
            val near = info.visibleItemsInfo.firstOrNull { it.key == keys.getOrNull(t - first) } ?: return@snapshotFlow null
            if (c.isEmpty()) null else if (t > f) (near.offset + near.size).toFloat() else near.offset.toFloat()
        }.collect { gap ->
            when {
                gap == null || gap < 0f -> launch { shown.animateTo(0f, Motion.fluid(0.25f, 1f)) }
                else -> {
                    if (!placed || shown.value < 0.02f) { y.snapTo(gap); placed = true } else launch { y.animateTo(gap, Motion.fluid(0.3f, 0.86f)) }
                    launch { shown.animateTo(1f, Motion.fluid(0.25f, 1f)) }
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize().padding(horizontal = Space.edge)) {
        val a = shown.value * lift().coerceIn(0f, 1f)
        if (a <= 0.01f) return@Canvas
        val at = y.value
        drawLine(
            Brush.horizontalGradient(0f to Color.White.copy(alpha = 0.95f * a), 0.7f to Color.White.copy(alpha = 0.5f * a), 1f to Color.Transparent),
            Offset(0f, at), Offset(size.width, at), strokeWidth = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(Color.White.copy(alpha = 0.12f * a), Offset(0f, at), Offset(size.width * 0.8f, at), strokeWidth = 6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(Color.White.copy(alpha = a), radius = 3.dp.toPx(), center = Offset(0f, at))
        drawCircle(Color.White.copy(alpha = 0.25f * a), radius = 8.dp.toPx(), center = Offset(0f, at))
    }
}

/**
 * A queued song. Its handle (or a long press anywhere on it) picks it up: it lifts a little out of
 * the list, larger, onto the selector's lit glass with a soft shadow beneath, and follows the finger;
 * let go and it settles into the gap the line shows. The cross takes it out of the queue.
 */
@Composable
private fun QueueRow(
    track: Track,
    current: Boolean,
    carried: Boolean,
    offset: () -> Float,
    lift: () -> Float,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onPickUp: () -> Unit,
    onCarry: (Float) -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickUp by rememberUpdatedState(onPickUp)
    val carry by rememberUpdatedState(onCarry)
    val drop by rememberUpdatedState(onDrop)
    // The row moves with the finger, so the finger is measured on the window, not on the row: measured
    // on the moving row, each step was partly cancelled by the row's own move, and it lagged behind.
    var rowAt by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var handleAt by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowAt = it }
            .graphicsLayer {
                val l = lift()
                translationY = offset()
                val k = 1f + 0.035f * l
                scaleX = k
                scaleY = k
                shadowElevation = 16.dp.toPx() * l
                shape = Corners.Medium
                clip = false
            }
            // Lifted, it's a card of its own: dark beneath, so the songs it passes over don't show
            // through, and lit at its rim as the selector is.
            .drawWithCache {
                val outline = Corners.Medium.createOutline(size, layoutDirection, this)
                onDrawBehind {
                    val l = lift().coerceIn(0f, 1f)
                    if (l > 0.004f) drawOutline(outline, Color(0xFF151515).copy(alpha = 0.96f * l))
                }
            }
            .litDrop(Corners.Medium) { lift() * 0.9f }
            .then(
                if (current) Modifier else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        pickUp()
                        var lastY = rowAt?.localToWindow(held.position)?.y ?: held.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            val y = rowAt?.localToWindow(change.position)?.y ?: change.position.y
                            carry(y - lastY)
                            lastY = y
                        }
                        drop()
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(start = 6.dp)) {
            TrackRow(track, onClick = onPlay, playing = current, trailing = {})
        }
        if (!current) {
            IconAction(Glyph.Close, "Remove from queue", onRemove, size = 14.dp)
            // The handle: three hairlines, as iOS draws its own. A drag here starts at once.
            Box(
                Modifier
                    .size(44.dp)
                    .onGloballyPositioned { handleAt = it }
                    .semantics { contentDescription = "Move ${track.title}" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            pickUp()
                            var lastY = handleAt?.localToWindow(down.position)?.y ?: down.position.y
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                val y = handleAt?.localToWindow(change.position)?.y ?: change.position.y
                                carry(y - lastY)
                                lastY = y
                            }
                            drop()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp, 12.dp)) {
                    val c = Ink.White.copy(alpha = if (carried) 0.95f else 0.45f)
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

