package app.aether.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.layout.LayoutCoordinates
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.model.Destination
import app.aether.core.model.MediaId
import kotlin.math.abs

/** Every place in AETHER. Keys keep each place's scroll and state while it's in a stack. */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data object Search : Route { override val key = "search" }
    data object Library : Route { override val key = "library" }
    data object Profile : Route { override val key = "profile" }
    data class Album(val id: MediaId) : Route { override val key = "album:$id" }
    data class Artist(val id: MediaId) : Route { override val key = "artist:$id" }
    data class Playlist(val id: MediaId) : Route { override val key = "playlist:$id" }
    data class LocalPlaylist(val id: Long) : Route { override val key = "local:$id" }
    data object Liked : Route { override val key = "liked" }
    data object History : Route { override val key = "history" }
    data class Browse(val destination: Destination, val title: String?) : Route { override val key = "browse:${destination.key}:${destination.params}" }
    data object Charts : Route { override val key = "charts" }
    data object Moods : Route { override val key = "moods" }
    data object Settings : Route { override val key = "settings" }
    data object Equaliser : Route { override val key = "equaliser" }

    /** Offline music as a playlist; with a [path], one of the phone's folders inside it. */
    data class OnThisPhone(val path: String = "") : Route { override val key = "phone:$path" }
    data object Kept : Route { override val key = "kept" }

    /** An album of the phone's songs, or of songs kept from the catalogue. */
    data class OfflineAlbum(val album: String) : Route { override val key = "offline-album:$album" }

    /** Choosing the phone's music folders, a level at a time. */
    data class MusicFolders(val path: String = "") : Route { override val key = "music-folders:$path" }
}

/** Four rooms, few enough to hold in one hand. */
enum class Tab(val label: String, val root: Route) {
    Listen("Listen", Route.Home),
    Discover("Discover", Route.Search),
    Library("Library", Route.Library),
    Profile("Profile", Route.Profile),
}

/** A page further off than this (of the screen's width) is still arriving. */
private const val ArrivingFrom = 0.1f

/** Where a page is in its life on a stack. */
enum class PagePhase {
    /** Made and waiting, out of sight, for its first opening or its next. */
    Kept,

    /** On a stack: arriving, or in its place. */
    Open,

    /** Off the stack, on its way out. */
    Leaving,
}

/**
 * One page on a room's stack. It stays made for as long as it's on the stack (a page you come back
 * to is the same page, not one built again), and it's drawn only while it can be seen.
 */
@Stable
class PageEntry internal constructor(val route: Route, val id: Long, phase: PagePhase, inPlace: Boolean = false) {
    /** How far off to the right it is: 0 in its place, 1 entirely off (arriving, or on its way out). */
    val slide = Glide(if (inPlace) 0f else 1f)

    var phase by mutableStateOf(phase)
        internal set

    /**
     * Where it's drawn among its room's pages: in the order they were opened, the latest on top. A
     * page still on its way out when another is opened stays under the new one, its folder with it,
     * and finishes going there, as one app closing into its icon goes on under the next one opening.
     */
    internal var order by mutableLongStateOf(id)

    /** The folder it was opened from, as it was touched: its head stands open before the page has loaded. */
    internal var preview by mutableStateOf<FolderPreview?>(null)

    /** It has come to rest in its place. A page holds back its long lists until then. */
    val landed: Boolean by derivedStateOf { phase == PagePhase.Open && slide.value <= 0.001f }

    /** Still coming in: on the stack, and not yet most of the way. Read when asked, not followed. */
    internal val arriving: Boolean get() = phase == PagePhase.Open && slide.value > ArrivingFrom

    /** The page's own frame, once it's been laid out: where things on it are measured from. */
    internal var frame: LayoutCoordinates? = null

    /** The folder held open at the head of this page, when it has one, and the trip that brought it. */
    internal var head: LayoutCoordinates? = null

    /** A page opened from a folder grows out of it, as an app grows out of its icon. */
    internal var trip by mutableStateOf<FolderTrip?>(null)

    /** The page's own list, so a drag on a page that's leaving can scroll the page coming back under it. */
    internal var list: LazyListState? = null

    /** A finger on it while it leaves: it goes once the finger does, so a drag it hands on never stops part way. */
    internal var held by mutableStateOf(false)
}

/**
 * The rail the rooms ride on, side by side. The screen moves along it, so going to the room on your
 * right brings it in from the right and sends this one out to the left, and coming back reverses
 * that. A room you jump to is brought up beside what's showing rather than flown past those between:
 * only ever the room you left and the room you're going to. One spring carries it all, so changing
 * your mind half way, however many times, just carries on from where it is at the speed it had.
 */
@Stable
class RoomRail(first: Tab) {
    /** Where the screen is along the rail, in screens. */
    val at = Glide(0f)
    private val slots = Tab.entries.map { mutableFloatStateOf(if (it == first) 0f else Off) }
    private var heading = first

    /** Whether the rail is moving: a room in motion is a picture, sliding, not a page being drawn. */
    val moving: Boolean get() = at.isRunning

    /**
     * How far [tab] is from the screen, in screens: 0 on show, negative off to the left, positive
     * off to the right, and 1 for a room that isn't on the rail at all, which is waiting off to the right.
     */
    fun offset(tab: Tab): Float {
        val slot = slots[tab.ordinal].floatValue
        return if (slot == Off) 1f else slot - at.value
    }

    /** Whether any of [tab] can be seen. */
    fun showing(tab: Tab): Boolean = abs(offset(tab)) < 1f

    /** Takes the screen to [to]; or, with [animate] off, simply puts it there. */
    suspend fun go(to: Tab, animate: Boolean) {
        val from = heading
        heading = to
        val here = at.value
        // What can't be seen any more comes off the rail.
        slots.forEach { if (it.floatValue != Off && abs(it.floatValue - here) >= 1f) it.floatValue = Off }
        val slot = slots[to.ordinal]
        if (slot.floatValue == Off) {
            val direction = if (to.ordinal > from.ordinal) 1f else -1f
            // Beside the last of what's showing on that side.
            val on = slots.filter { it.floatValue != Off }.map { it.floatValue }
            val edge = if (direction > 0f) on.maxOrNull() else on.minOrNull()
            slot.floatValue = (edge ?: here) + direction
        }
        if (animate) at.to(slot.floatValue, Spring) else at.snap(slot.floatValue)
        slots.forEachIndexed { i, s -> if (i != to.ordinal) s.floatValue = Off }
    }

    private companion object {
        const val Off = Float.MAX_VALUE
        val Spring = FluidSpring(0.44f, 1f)
    }
}

/** One stack per room: leaving a room and coming back finds it as it was. */
@Stable
class Navigator {
    var tab by mutableStateOf(Tab.Listen)
        private set

    val rail = RoomRail(tab)

    private var nextId = 0L
    private val stacks = Tab.entries.associateWith { mutableStateListOf(PageEntry(it.root, nextId++, PagePhase.Open, inPlace = true)) }

    /** Pages on their way out of each room: off the stack, still sliding. */
    private val leaving = Tab.entries.associateWith { mutableStateListOf<PageEntry>() }

    /**
     * Settings is a place you go to and come back from, always the same: it's made once, shortly
     * after the rooms, and waits out of sight, so opening it is only sliding it in.
     */
    private val settings = PageEntry(Route.Settings, nextId++, PagePhase.Kept)
    private val settingsRoom = Tab.Library

    /** Once true, what's kept is made. */
    var keepsMade by mutableStateOf(false)
        internal set

    private val stack: SnapshotStateList<PageEntry> get() = stacks.getValue(tab)

    val current: Route get() = stack.last().route
    val top: PageEntry get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1 || tab != Tab.Listen

    /** How deep the current room is. */
    val depth: Int get() = stack.size

    /** How deep [tab] is. */
    fun depthOf(tab: Tab): Int = stacks.getValue(tab).size

    /**
     * A room's pages as they're drawn, bottom first: what's kept, then its stack and any on their way
     * out, in the order they were opened. A page left goes out over the one it uncovers; one opened while
     * another is still going comes in over it.
     */
    fun pagesOf(tab: Tab): List<PageEntry> {
        val kept = if (tab == settingsRoom && keepsMade && settings.phase == PagePhase.Kept) listOf(settings) else emptyList()
        return kept + (stacks.getValue(tab) + leaving.getValue(tab)).sortedBy { it.order }
    }

    /** The page on top of [tab]'s own stack. */
    fun topOf(tab: Tab): PageEntry = stacks.getValue(tab).last()

    fun open(route: Route) {
        if (route == settings.route) {
            if (tab != settingsRoom) tab = settingsRoom
            if (settings.phase != PagePhase.Open) {
                leaving.getValue(settingsRoom).remove(settings)
                settings.phase = PagePhase.Open
                settings.order = nextId++
                stacks.getValue(settingsRoom) += settings
            }
            return
        }
        if (stack.last().route == route) return
        // One page arriving at a time. Another asked for while the top one is still coming in (two
        // folders touched at once, a second card under another finger) is ignored, as iOS ignores
        // touches while it pushes a page: two at once flew two folders onto one another.
        if (stack.last().arriving) return
        // A page on its way out, opened again (a folder touched as it flies home): the same page
        // comes back from where it is, at the speed it had, with its list where it was. Only from the
        // top: one already gone under a newer page would jump out over it, so a new one comes instead.
        val going = leaving.getValue(tab).lastOrNull { it.route == route }
        if (going != null && (stack + leaving.getValue(tab)).all { it === going || it.order < going.order }) {
            leaving.getValue(tab).remove(going)
            going.phase = PagePhase.Open
            going.order = nextId++
            stack += going
            return
        }
        stack += PageEntry(route, nextId++, PagePhase.Open)
    }

    /** The page under [page] in its room: the one it covers. */
    fun beneath(page: PageEntry): PageEntry? {
        for (t in Tab.entries) {
            val pages = pagesOf(t)
            val i = pages.indexOf(page)
            if (i > 0) return pages.subList(0, i).lastOrNull { it.phase != PagePhase.Kept && it.phase != PagePhase.Leaving }
        }
        return null
    }

    fun back(): Boolean = when {
        stack.size > 1 -> { leave(stack.removeAt(stack.lastIndex)); true }
        tab != Tab.Listen -> { tab = Tab.Listen; true }
        else -> false
    }

    /** Choosing the room you're in returns to its top: the page on top slides away, those under it simply go. */
    fun select(target: Tab) {
        if (target != tab) {
            tab = target
            return
        }
        val stack = stack
        if (stack.size > 1) {
            leave(stack.removeAt(stack.lastIndex))
            while (stack.size > 1) stack.removeAt(stack.lastIndex).let { if (it === settings) it.phase = PagePhase.Kept }
        }
    }

    private fun leave(page: PageEntry) {
        page.phase = PagePhase.Leaving
        leaving.getValue(tab) += page
    }

    /** A page has finished sliding away: it's gone, or, if it's one that's kept, it waits again. */
    internal fun finished(page: PageEntry) {
        leaving.values.forEach { it.remove(page) }
        if (page === settings) page.phase = PagePhase.Kept
    }
}
