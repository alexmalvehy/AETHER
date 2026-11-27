package app.aether.ui

import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import app.aether.core.designsystem.component.FolderCard
import app.aether.core.designsystem.component.FolderPose
import app.aether.core.designsystem.component.FolderFile
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.motion.Anchors
import app.aether.core.designsystem.motion.Becoming
import app.aether.core.designsystem.motion.Landing
import app.aether.core.designsystem.motion.anchor
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Ink
import app.aether.core.model.Artwork
import app.aether.ui.detail.AlbumScreen
import app.aether.ui.detail.ArtistScreen
import app.aether.ui.detail.BrowseScreen
import app.aether.ui.detail.ChartsScreen
import app.aether.ui.detail.FolderHeadWidth
import app.aether.ui.detail.MoodsScreen
import app.aether.ui.detail.PlaylistScreen
import app.aether.ui.detail.SleeveActions
import app.aether.ui.detail.SleeveHead
import app.aether.ui.home.HomeScreen
import app.aether.ui.library.HistoryScreen
import app.aether.ui.library.LibraryScreen
import app.aether.ui.library.LikedScreen
import app.aether.ui.library.LocalPlaylistScreen
import app.aether.ui.search.SearchScreen
import app.aether.ui.settings.SettingsScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * The rooms and their pages. Every room stays made, and every page on a room's stack stays made
 * beneath the one on top: changing rooms or coming back is showing what's already there, never
 * building it again.
 *
 * What makes the movement light is what moves: a room or a page is one drawing, moved by a property
 * of its layer, with nothing in it composed or measured again. What's costly inside it (a print's
 * shader, a folder's outlines) is a small texture of its own, kept. What can't be seen isn't placed:
 * it stays made, its lists where they were, but its textures go back to Android, which otherwise held
 * nearly all of its graphics budget for rooms nobody could see.
 */

/** The page being composed: what it's measured from, and whether it has arrived. */
val LocalPage = staticCompositionLocalOf<PageEntry?> { null }

/**
 * Whether what moves by itself on this page (the mood ribbon, the drifting covers) should rest: it
 * isn't the page on show, or something is over it. A page moving where no one can see it had the
 * whole screen drawn again for every step, and a blur over it made again.
 */
val LocalRoomsHidden = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * Whether this page is on show and at rest: nothing over it, the rooms' rail still, and no page of its
 * room sliding in or away. What changes by itself on a page (Listen's figures) waits for this, so that
 * it never asks for work while the screen is moving.
 */
val LocalPageSettled = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Stays composed and measured, and is placed (so drawn, and touchable) only while [show] says so. */
fun Modifier.placedWhile(show: () -> Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { if (show()) placeable.place(0, 0) }
}

/** The rooms, each made once, with the room you're in on show and any folder in flight just over its page. */
@Composable
fun Pages(nav: Navigator, flight: FolderFlight, watcher: NestedScrollConnection, paused: () -> Boolean, modifier: Modifier = Modifier) {
    val holder = rememberSaveableStateHolder()
    val arrival = LocalArrival.current
    val reduce = LocalReduceMotion.current
    var made by remember { mutableStateOf(setOf(nav.tab)) }
    var warmed by remember { mutableStateOf(false) }
    val lastScroll = remember { longArrayOf(0L) }
    SideEffect { if (nav.tab !in made) made = made + nav.tab }
    LaunchedEffect(nav.tab) { nav.rail.go(nav.tab, animate = !reduce) }
    // The other rooms are made shortly after the first has landed, one at a time and never under a
    // moving finger, so the first time you go to one it's simply there.
    LaunchedEffect(Unit) {
        snapshotFlow { arrival.cornerAlpha >= 1f }.first { it }
        for (tab in Tab.entries) {
            if (tab in made) continue
            delay(RoomGapMs)
            while (SystemClock.uptimeMillis() - lastScroll[0] < StillMs) delay(200)
            made = made + tab
        }
        delay(RoomGapMs)
        while (SystemClock.uptimeMillis() - lastScroll[0] < StillMs) delay(200)
        nav.keepsMade = true
    }
    Box(modifier.fillMaxSize()) {
        Tab.entries.forEach { tab ->
            if (tab in made || tab == nav.tab) key(tab) {
                Room(tab, nav, holder, flight, watcher, paused) { lastScroll[0] = SystemClock.uptimeMillis() }
            }
        }
        // A folder's page made once, out of sight, so its code is loaded before the first folder is
        // opened (that first opening is what stumbled).
        if (!warmed && made.size == Tab.entries.size) {
            Box(Modifier.placedWhile { false }) {
                SleeveHead("", null, Artwork.None, emptyList()) { SleeveActions(emptyList(), saved = null, onSave = null) }
                FolderCard("", null, Artwork.None, emptyList(), onOpen = {}, width = FolderHeadWidth, opened = true, flying = { 0f })
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                warmed = true
            }
        }
    }
}

/** How long to wait before making the next room, and how long the page must have been still. */
private const val RoomGapMs = 900L
private const val StillMs = 500L

@Composable
private fun Room(
    tab: Tab,
    nav: Navigator,
    holder: SaveableStateHolder,
    flight: FolderFlight,
    watcher: NestedScrollConnection,
    paused: () -> Boolean,
    onScrolled: () -> Unit,
) {
    val rail = nav.rail
    val onShow = nav.tab == tab
    val showing by remember(tab) { derivedStateOf { rail.showing(tab) } }
    // A drag handed on by a page leaving flings its list from here, where it outlives that page.
    val keep = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    // A room left mid-fling coasts to rest out of sight; it doesn't fold the dock of the one you're in.
    val connection = remember(tab, watcher) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                onScrolled()
                return if (nav.tab == tab) watcher.onPostScroll(consumed, available, source) else Offset.Zero
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .placedWhile { showing }
            .graphicsLayer {
                // Along the rail, in screens. Clipped to itself: a page waiting beside it, off its left
                // edge, mustn't be touchable through another room.
                val offset = rail.offset(tab)
                translationX = offset * size.width
                clip = true
            }
            .then(if (onShow) Modifier else Modifier.clearAndSetSemantics { })
            .nestedScroll(connection),
    ) {
        val pages = nav.pagesOf(tab)
        pages.forEachIndexed { i, page ->
            key(page.id) {
                // Everything over it, nearest first: what it drifts and dims under, and what hides it.
                val over = pages.subList(i + 1, pages.size)
                Page(page, over, tab, nav, holder, flight, paused, keep, fling)
                // Its folder in the air flies just over it: over the page it leaves and the one it lands
                // in, and under any page opened since, which comes in over it as it goes on home.
                page.trip?.let { trip -> key(trip) { FlyingFolder(trip, flight, over) } }
            }
        }
    }
}

/**
 * A page: opaque, so one sliding over another covers it, with a soft shadow along its leading edge.
 * Pages move as they do on iOS: one you open slides in from the right over the one you were on,
 * which drifts left and dims; going back slides it away and the one beneath returns. A page opened
 * from a folder slides in the same way, carrying the folder up to its head, and going back the folder
 * flies home to wherever its place is now. On a spring, so a page caught mid-way by a second touch
 * carries on from where it is, at the speed it had; and under the system's back gesture it follows
 * the thumb.
 * Beneath a page at rest, it waits off to the left, a picture, ready for the way back.
 */
@Composable
private fun Page(
    page: PageEntry,
    over: List<PageEntry>,
    tab: Tab,
    nav: Navigator,
    holder: SaveableStateHolder,
    flight: FolderFlight,
    paused: () -> Boolean,
    keep: CoroutineScope,
    fling: FlingBehavior,
) {
    val reduce = LocalReduceMotion.current
    val root = page.route == tab.root
    val scope = rememberCoroutineScope()
    // Every page slides, a folder's too: the folder's flight rides this same spring (its progress is
    // the page's slide), so the two can never disagree.
    LaunchedEffect(page, page.phase) {
        when (page.phase) {
            PagePhase.Kept -> page.slide.snap(1f)
            PagePhase.Open -> page.slide.to(0f, if (reduce) PageFade else PageSlide)
            PagePhase.Leaving -> {
                page.slide.to(1f, if (reduce) PageFade else PageSlide)
                flight.ended(page)
                // A finger still on it is scrolling the page under it: it goes when the finger does.
                snapshotFlow { page.held }.first { !it }
                nav.finished(page)
            }
        }
    }
    DisposableEffect(page) {
        onDispose {
            if (!root) holder.removeState(page.id)
            flight.ended(page)
        }
    }
    // The system's back gesture, on a page above its room's first: AETHER's own transition follows the
    // thumb, as iOS's swipe back does (the page slides away, the one beneath shows through), and lets go
    // either way at the thumb's speed. Android's own back animation never runs over it; at a room's first
    // page there's nothing here to go back to, and the gesture is Android's, leaving the app. Before the
    // page's own content, so a field or a pane on it is closed first.
    val onTop = !root && over.isEmpty() && page.phase == PagePhase.Open && nav.tab == tab && !reduce
    val width = LocalView.current.width.toFloat()
    PredictiveBackHandler(enabled = PredictiveBack && onTop && !paused()) { progress ->
        try {
            var startX = Float.NaN
            var from = 0f
            progress.collect { e ->
                if (startX.isNaN()) {
                    startX = e.touchX
                    // Caught still arriving, it goes from where it is: never back to its place first.
                    from = page.slide.value.coerceIn(0f, 1f)
                }
                page.slide.follow(backTravel(e.progress, e.touchX - startX, width, e.swipeEdge == BackEventCompat.EDGE_LEFT, from))
            }
            if (nav.topOf(tab) === page) nav.back()
        } catch (e: CancellationException) {
            // Let go without going: back to its place, from where the thumb left it.
            scope.launch { page.slide.to(0f, PageSlide) }
            throw e
        }
    }
    val hidden = remember(page, tab) { { paused() || nav.tab != tab || nav.topOf(tab) !== page } }
    val settled = remember(page, tab) { { !hidden() && !nav.rail.moving && nav.pagesOf(tab).none { it.slide.isRunning } } }
    // Wholly covered by a page that has landed over it, it waits out of sight.
    val waitingNow by remember(page, over) { derivedStateOf { page.phase == PagePhase.Kept || anyLanded(over) } }
    // The shadow along its leading edge, drawn beside the page rather than in it, so that the page's
    // picture holds only the page. There only while it's on its way.
    if (!root) Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val away = page.slide.value
                // Where the page is, drift and all: covered while it's still going, it drifts under the
                // page that came, and its shadow with it.
                translationX = (away - coverBy(over) / 3f) * size.width
                alpha = if (!reduce && away > 0.001f && away < 0.999f) 1f else 0f
            }
            .drawBehind {
                val w = 26.dp.toPx()
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)), startX = -w, endX = 0f),
                    topLeft = Offset(-w, 0f),
                    size = Size(w, size.height),
                )
            },
    )
    Box(
        Modifier
            .fillMaxSize()
            .placedWhile { !waitingNow }
            .graphicsLayer {
                if (reduce) alpha = 1f - page.slide.value
                else translationX = (page.slide.value - coverBy(over) / 3f) * size.width
                // While it slides, or the page over it does, it's one picture, drawn once and moved: the
                // rooms' plane (which the glass reads) is then drawn each frame from two pictures, not
                // from every row, cover and line on both pages. At rest it's drawn as it always was.
                val moving = page.slide.isRunning || anyRunning(over)
                compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            // Inside the layer: what's measured from here is where things will be once it has arrived.
            .onPlaced { page.frame = it }
            .then(if (page.phase == PagePhase.Leaving) Modifier.handsOver(page, { nav.beneath(page)?.list }, keep, fling) else Modifier)
            .then(if (over.isEmpty() && page.phase != PagePhase.Kept) Modifier else Modifier.clearAndSetSemantics { })
            .background(Ink.Black),
    ) {
        CompositionLocalProvider(LocalPage provides page, LocalRoomsHidden provides hidden, LocalPageSettled provides settled) {
            holder.SaveableStateProvider(page.id) { Screen(page.route) }
        }
    }
    // Dimmed as it's covered, by a sheet of black over it, not by fading all of it: one rectangle,
    // with no copy of the page to fade.
    if (over.isNotEmpty() && !reduce) Spacer(
        Modifier
            .fillMaxSize()
            .placedWhile { !waitingNow }
            .graphicsLayer {
                val covered = coverBy(over)
                translationX = (page.slide.value - covered / 3f) * size.width
                alpha = CoveredDim * covered
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .background(Color.Black),
    )
}

private val PageSlide = FluidSpring(0.4f, 1f)
private val PageFade = FluidSpring(0.3f, 1f)

/** How dark a page is once one has come all the way over it. */
private const val CoveredDim = 0.6f

/**
 * How covered a page is by [over], the pages above it: 0 bare, 1 hidden. Each covers as much as it has
 * come in (1 less its slide); together they cover as one, 1 less the product of their slides. So a page
 * opened while another is still going takes over from it with no jump and no kink: the page beneath
 * goes on drifting and dimming as it was, and turns round smoothly as the new one comes. Read each
 * frame, while drawing.
 */
internal fun coverBy(over: List<PageEntry>): Float {
    var bare = 1f
    for (i in over.indices) bare *= over[i].slide.value.coerceIn(0f, 1f)
    return 1f - bare
}

/** Whether any of [pages] is moving. */
private fun anyRunning(pages: List<PageEntry>): Boolean {
    for (i in pages.indices) if (pages[i].slide.isRunning) return true
    return false
}

/** Whether any of [pages] has come to rest in its place, covering all beneath it. */
private fun anyLanded(pages: List<PageEntry>): Boolean {
    for (i in pages.indices) if (pages[i].landed) return true
    return false
}

/** Under this, a page's slide is at rest. */
private const val Rest = 0.0005f

/**
 * Whether Android's back gesture drives AETHER's own transitions as the thumb moves. Off, the gesture
 * runs the same transitions when it's let go (and Android still plays nothing of its own over them).
 */
private const val PredictiveBack = true

/**
 * How far a page has gone under the thumb of the system's back gesture, from where it was when the
 * thumb took it ([from]: 0 in its place, more if it was still arriving). From the left edge it slides
 * exactly as far as the thumb has come ([dragged] of [width]), as iOS's does; from the right edge,
 * where following the thumb would pull it the wrong way, by the gesture's own [progress].
 */
internal fun backTravel(progress: Float, dragged: Float, width: Float, fromLeft: Boolean, from: Float = 0f): Float = when {
    fromLeft && width > 0f -> (from + dragged / width).coerceIn(0f, 1f)
    else -> (from + (1f - from) * progress).coerceIn(0f, 1f)
}

/**
 * A page on its way out isn't there to be touched any more: a finger on it belongs to the page coming
 * back under it. A drag scrolls that page's list (so a folder flying home can be scrolled after, and
 * keeps finding its place), and flings it as it's let go; a tap does nothing. While the finger is on
 * it, the page stays (off screen, once it's got there), so the drag never stops part way.
 */
private fun Modifier.handsOver(page: PageEntry, to: () -> LazyListState?, keep: CoroutineScope, fling: FlingBehavior): Modifier =
    pointerInput(page) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            page.held = true
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dragging = false
            var travelled = 0f
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id }
                    e.changes.forEach { it.consume() }
                    if (c == null) break
                    // The page only moves sideways, so up and down here are the screen's.
                    val dy = c.position.y - c.previousPosition.y
                    tracker.addPosition(c.uptimeMillis, c.position)
                    travelled += abs(dy)
                    if (!dragging && travelled > viewConfiguration.touchSlop) dragging = true
                    if (dragging && dy != 0f) to()?.dispatchRawDelta(-dy)
                    if (!c.pressed) break
                }
            } finally {
                page.held = false
            }
            if (dragging) {
                val list = to() ?: return@awaitEachGesture
                val v = tracker.calculateVelocity().y
                // In the room's scope: the page this came from may be gone before the fling is.
                keep.launch { list.scroll { with(fling) { performFling(-v) } } }
            }
        }
    }

/** The screen's own corner radius, in pixels: what a window grows into. */
internal fun displayCornerRadius(view: View): Float {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0f
    return view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
}


@Composable
private fun Screen(route: Route) {
    when (route) {
        Route.Home -> HomeScreen()
        Route.Search -> SearchScreen()
        Route.Library -> LibraryScreen()
        Route.Profile -> app.aether.ui.profile.ProfileScreen()
        is Route.Album -> AlbumScreen(route.id)
        is Route.Artist -> ArtistScreen(route.id)
        is Route.Playlist -> PlaylistScreen(route.id)
        is Route.LocalPlaylist -> LocalPlaylistScreen(route.id)
        Route.Liked -> LikedScreen()
        Route.History -> HistoryScreen()
        is Route.Browse -> BrowseScreen(route.destination, route.title)
        Route.Charts -> ChartsScreen()
        Route.Moods -> MoodsScreen()
        Route.Settings -> SettingsScreen()
        Route.Equaliser -> app.aether.ui.settings.EqualiserScreen()
        is Route.OnThisPhone -> app.aether.ui.library.OnThisPhoneScreen(route.path)
        Route.Kept -> app.aether.ui.library.KeptScreen()
        is Route.OfflineAlbum -> app.aether.ui.library.OfflineAlbumScreen(route.album)
        is Route.MusicFolders -> app.aether.ui.settings.MusicFoldersScreen(route.path)
    }
}

/**
 * Whether the page has slid into its place. A page holds back what it can until then, so that the
 * slide, and a folder's flight, run alone and smooth.
 */
@Composable
fun pageLanded(): Boolean {
    val page = LocalPage.current ?: return true
    return page.landed
}

/**
 * How many rows of a folder page's list may be made by now. Making the whole visible list in one frame
 * was a frame of 70 to 120 ms; instead the rows are made a few a frame from the page's second frame,
 * shown as they're made: the screen's worth is there within four frames, while the page is still
 * mostly off to the right, and all of it long before it lands. Read it inside the list's content, so
 * only the list is asked again.
 */
@Composable
fun rememberStagedRows(): State<Int> {
    val page = LocalPage.current
    val staged = remember(page) { mutableIntStateOf(if (page == null || page.landed) Int.MAX_VALUE else 0) }
    if (page != null) LaunchedEffect(page) {
        if (staged.intValue == Int.MAX_VALUE) return@LaunchedEffect
        withFrameNanos { }
        while (!page.landed && staged.intValue < MostStaged) {
            staged.intValue += RowsPerFrame
            withFrameNanos { }
        }
        staged.intValue = Int.MAX_VALUE
    }
    return staged
}

private const val RowsPerFrame = 3
private const val MostStaged = 24

/** A folder as it was touched: enough to draw it open on its page before the page has loaded. */
data class FolderPreview(val key: String, val title: String, val detail: String?, val artwork: Artwork, val files: List<FolderFile>)

/** Where a folder stands in a list: the page it's on and its name there. Found again by these, every frame. */
data class SlotKey(val page: Long, val key: String)

/** One frame of a folder's journey: where the folder is, and how much of it shows. */
internal class TripFrame(val folder: Rect, val folderAlpha: Float)

/**
 * A folder's journey between its place in a list and the head of the page it opens. It has no clock
 * of its own: it's as far along as its page is ([PageEntry.slide]), so the page, the folder and the
 * back gesture can never disagree, and a page caught on its way turns round with the speed it had.
 */
@Stable
class FolderTrip internal constructor(
    val preview: FolderPreview,
    internal val pose: FolderPose,
    internal val slot: SlotKey,
    internal val slotWidth: Dp,
    internal val page: PageEntry,
    /** The page whose list the folder's place is in. */
    internal val origin: PageEntry? = null,
) {
    /** 0 in its place in the list, 1 open at the head of its page (a little past while it settles). */
    val progress: Float get() = 1f - page.slide.value

    /** In the air: its page isn't at rest. Both its places are hidden while it is. */
    val inFlight: Boolean get() = abs(page.slide.value) > Rest

    /** What it's drawn in, and measured against: its room, at the room's own origin. */
    internal var drawnIn: LayoutCoordinates? = null

    /** Where its place and its page's head were last seen, for when they're out of sight. */
    internal var lastSlot: Rect? = null
    internal var lastHead: Rect? = null

    /** Where the folder stands at the head of its page, as the page stands at rest (it's the page that moves). */
    internal fun headAtRest(): Rect? {
        val head = page.head?.takeIf { it.isAttached } ?: return null
        val frame = page.frame?.takeIf { it.isAttached } ?: return null
        return runCatching { frame.localBoundingBoxOf(head, clipBounds = false) }.getOrNull()
    }
}

/**
 * Folders in the air. A folder touched in a list flies to the head of the page it opens while the page
 * slides in, and flies back into wherever its place is now when the page is left: both ends are
 * found again every frame ([Anchors]), so a list scrolled, a page resized or the bars coming and going
 * mid-flight never leave it landing somewhere else. Its place is hidden while it's in the air and shown
 * in the frame it lands.
 */
@Stable
class FolderFlight {
    /** Where every folder in a list is, by [SlotKey]. */
    val anchors = Anchors()

    /** Every page opened from a folder that's still on a stack. */
    internal val trips = mutableStateListOf<FolderTrip>()

    /** [page] has just been opened from the folder in [slot]: it will grow out of it. */
    internal fun depart(preview: FolderPreview, pose: FolderPose, slot: SlotKey, slotWidth: Dp, page: PageEntry, origin: PageEntry? = null) {
        // Opened again on its way home: the trip it had simply turns round.
        if (page.trip != null) return
        // Only a page arriving now carries a folder up; one already in place stays as it is.
        if (page.phase != PagePhase.Open || page.slide.value < 0.999f) return
        val trip = FolderTrip(preview, pose, slot, slotWidth, page, origin)
        page.trip = trip
        trips += trip
    }

    /** [page] has gone: its flight with it. */
    internal fun ended(page: PageEntry) {
        val trip = page.trip ?: return
        trips.remove(trip)
        page.trip = null
    }

    /** Whether the folder in [slot] is in the air, and so hidden in its place. Read while drawing. */
    fun hides(slot: SlotKey): Boolean {
        for (t in trips) if (t.slot == slot && t.inFlight) return true
        return false
    }

    /** Whether [page]'s own folder is in the air, and so hidden at its head. */
    internal fun headHidden(page: PageEntry): Boolean = page.trip?.inFlight == true

    /**
     * Where [trip] is this frame. Its place is read now, wherever the list has taken it; one gone out of
     * sight is landed by the edge it left by ([Becoming.landing]).
     */
    internal fun frame(trip: FolderTrip): TripFrame? {
        // In its room's own terms, as its page and its place are: a room sliding along the rail as you
        // change rooms mid-flight carries all three together.
        val host = trip.drawnIn?.takeIf { it.isAttached } ?: return null
        val screen = Rect(Offset.Zero, host.size.toSize())
        val live = anchors.bounds(trip.slot, host)
        if (live != null) trip.lastSlot = live
        val head = trip.headAtRest()?.also { trip.lastHead = it } ?: trip.lastHead
        val landing = Becoming.landing(live, trip.lastSlot, screen, head ?: screen)
        val place = landing.rect
        val p = trip.progress
        return TripFrame(
            folder = Becoming.lerp(place, head ?: place, p),
            // A place never seen: the folder shrinks and fades where it is.
            folderAlpha = if (landing.kind == Landing.Kind.Vanish) Becoming.ramp(p, 0f, 0.35f) else 1f,
        )
    }
}

/**
 * Opens [route] from the folder [preview], touched in [slot] on the page on top ([go] opens it, after
 * whatever has to step aside first). Only a page that really opened takes the folder: one still arriving
 * keeps a second touch from opening another (two folders touched at once, a card and a folder), and
 * then neither the folder nor its flight reaches the page that is arriving.
 */
internal fun openFolderPage(
    nav: Navigator,
    flight: FolderFlight,
    preview: FolderPreview,
    route: Route,
    pose: FolderPose,
    slot: SlotKey?,
    slotWidth: Dp,
    go: (Route) -> Unit = nav::open,
) {
    val from = nav.top
    go(route)
    val opened = nav.top
    if (opened === from || opened.route != route) return
    if (opened.preview == null) opened.preview = preview
    if (slot != null) flight.depart(preview, pose, slot, slotWidth, opened, origin = from)
}

/**
 * The folder itself in the air: the large one, scaled to where it is. It leaves exactly as the small
 * one lay and lands exactly as the large one stands; the words and small things on it hold their
 * size on the way, as they're the same size in both places. Made for as long as its page is on a
 * stack, so turning round never builds it again.
 */
@Composable
private fun FlyingFolder(trip: FolderTrip, flight: FolderFlight, over: List<PageEntry>) {
    val p = trip.preview
    val layer = rememberGraphicsLayer()
    Box(Modifier.fillMaxSize().onPlaced { trip.drawnIn = it }.wrapContentSize(Alignment.TopStart, unbounded = true)) {
        FolderCard(
            title = p.title,
            detail = p.detail,
            artwork = p.artwork,
            files = p.files,
            onOpen = {},
            width = FolderHeadWidth,
            opened = true,
            pose = trip.pose,
            flying = { trip.progress.coerceIn(0f, 1.1f) },
            fromWidth = trip.slotWidth,
            modifier = Modifier.drawWithContent {
                // Where it is is found here, as it's drawn, after this frame's layout, when a list
                // scrolled under it this frame has already moved its place. Found any earlier (in a
                // layer's own block, which runs before layout), its place was a frame old: the folder
                // trailed a scroll, shaking, and caught up when the scroll stopped.
                // Once a page opened since has come all the way over it, there's nothing of it to see.
                val f = if (trip.inFlight && !anyLanded(over)) flight.frame(trip) else null
                if (f == null) return@drawWithContent
                // Read so that the list its place is in, scrolled, draws it again.
                trip.origin?.list?.let { it.firstVisibleItemIndex; it.firstVisibleItemScrollOffset }
                val k = f.folder.width / size.width
                layer.record { this@drawWithContent.drawContent() }
                layer.pivotOffset = Offset.Zero
                layer.scaleX = k
                layer.scaleY = k
                layer.translationX = f.folder.left
                layer.translationY = f.folder.top
                layer.alpha = f.folderAlpha
                // Under a page opened since, it darkens as the pages it's between do, so it lands in
                // its place exactly as dark as the place is.
                val dim = CoveredDim * coverBy(over)
                layer.colorFilter = if (dim > 0.001f) ColorFilter.tint(Color.Black.copy(alpha = dim), BlendMode.SrcAtop) else null
                drawLayer(layer)
            },
        )
    }
}

/**
 * A folder in a list that opens a page: touched, its page slides in and it flies up to the head of
 * that page, and it flies back into its place when the page is left.
 */
@Composable
fun FolderLink(preview: FolderPreview, route: Route, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val flight = actions.flight
    val page = LocalPage.current
    val reduce = LocalReduceMotion.current
    val pose = remember { FolderPose() }
    // This folder, not just this playlist: one shown twice on a page (in two sections of Listen, say)
    // is two places, and the one touched is the one that flies. Saved, so a row scrolled away and back
    // is still the same place.
    val copy = rememberSaveable { nextFolderCopy() }
    val slot = remember(page, preview.key, copy) { page?.let { SlotKey(it.id, "${preview.key}#$copy") } }
    FolderCard(
        title = preview.title,
        detail = preview.detail,
        artwork = preview.artwork,
        files = preview.files,
        onOpen = { actions.openFolder(preview, route, pose, slot.takeIf { !reduce }, FolderLinkWidth) },
        pose = pose,
        width = FolderLinkWidth,
        modifier = modifier
            .then(if (slot != null) Modifier.anchor(flight.anchors, slot) else Modifier)
            .graphicsLayer { alpha = if (slot != null && flight.hides(slot)) 0f else 1f },
    )
}

/** How wide a folder stands in a list. */
private val FolderLinkWidth = 168.dp

private var folderCopies = 0L

/** A name for one folder on screen, unique for the app's life. */
private fun nextFolderCopy(): Long = ++folderCopies

/** The folder this page was opened from, as it was touched, if it's the one named [key]. */
@Composable
fun folderPreview(key: String): FolderPreview? = LocalPage.current?.preview?.takeIf { it.key == key }

/** Where a folder lands: the one held open at the head of its page. */
@Composable
fun Modifier.folderHead(): Modifier {
    val page = LocalPage.current ?: return this
    val flight = LocalActions.current.flight
    return this
        .onPlaced { page.head = it }
        .graphicsLayer { alpha = if (flight.headHidden(page)) 0f else 1f }
}

