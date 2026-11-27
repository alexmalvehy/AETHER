@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.player

import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.PullRipple
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.Haptics
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.FluidSpring
import app.aether.core.designsystem.theme.Glide
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import app.aether.core.model.songCount
import app.aether.player.Downloads
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val FlightPx = 1080
private const val SmallPx = 200

/** One disc stands at the foot, the newest; how many are coming down is the number on it. */
private const val MostDiscs = 1

/** How long a song sent down waits to hear that its download has begun. */
private const val AwaitMs = 15_000L

/** More waiting to go in than this, and they go in without a wave each: the last one still has its wave. */
private const val MostWaves = 3

private val LiftSpring = FluidSpring(0.62f, 0.84f)
private val SinkSpring = FluidSpring(0.3f, 1f)
private val RiseSpring = FluidSpring(0.46f, 0.82f)
private val PlaceSpring = FluidSpring(0.42f, 0.86f)
private val DrawIn = FluidSpring(0.2f, 1f)
private val LetGo = FluidSpring(0.55f, 0.62f)
private val Words = FluidSpring(0.4f, 1f)

private val DiscSize = 56.dp

/** From the middle of one disc to the middle of the next: they overlap a little, a stack in a row. */
private val DiscGap = 44.dp

/** Where the middle of a waiting disc is held: a little below the screen's foot, so it stays clear of the bars above it. */
private val DiscAbove = (-6).dp

/** How much further into the phone a disc goes as the song comes down. */
private val DiscSinks = 10.dp

/** What's left of a song on its way in: it goes under the screen's foot. */
private val DiscGone = 48.dp

/** One song on its way into the phone. [flies]: it lifted off a cover. [ripples]: its arrival moves the screen. */
@Stable
internal class Disc(val track: Track, val flies: Boolean, val ripples: Boolean, startX: Float) {
    val flying = Glide(if (flies) 0f else 1f, rest = 0.0008f)
    val sinking = Glide(if (flies) 0f else 1f)
    val place = Glide(startX)
    var from by mutableStateOf(Rect.Zero)
    var progress by mutableFloatStateOf(0f)
    var leaving by mutableStateOf(false)

    /** The download's been heard of. */
    var seen = false
}

/**
 * Songs going into the phone. Asked to keep one, its cover lifts off with a small ripple and is carried
 * to the foot of the screen, where it waits half inside the phone, a thin ring saying how much has come
 * down. Others join it side by side, three and then a number; touched, they open the list of what's
 * coming down. Each goes in only when it's really there: the screen is drawn down after it, a bright
 * mouth opens at the foot, and a wave runs up through everything, as AirDrop's does. Whatever else is
 * coming down (a whole album, say) waits with them, goes in quietly, and has one wave when it's all done.
 */
@Stable
class KeepFlight(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: Downloads,
    private val haptics: Haptics,
) {
    val ripple = PullRipple()

    /** Where Now Playing's cover stands, for a song to lift off. */
    var cover: LayoutCoordinates? = null

    internal var screen = Size.Zero
    internal var density = 1f
    internal var reduce = false

    /** What the discs open when they're touched. */
    var onOpenList: () -> Unit = {}

    internal val discs = mutableStateListOf<Disc>()
    internal var word by mutableStateOf<String?>(null)
        private set

    /** The words at the foot and the dark behind them. */
    internal val saying = Glide(0f)

    private val lifting = HashSet<MediaId>()
    private val arrivals = Channel<Disc>(Channel.UNLIMITED)
    private var waiting = 0
    private var quietlyKept = 0

    init {
        scope.launch { downloads.entries.collect(::reconcile) }
        scope.launch { for (disc in arrivals) goIn(disc) }
    }

    /** Where a piece of the screen is, if it's still there. */
    fun boundsOf(place: LayoutCoordinates?): Rect? = runCatching { place?.takeIf { it.isAttached }?.boundsInRoot() }.getOrNull()

    /** Sends [song] down from [start] (with nowhere to lift off, or motion reduced, it simply rises at the foot). */
    fun fly(song: Track, start: Rect?, reduce: Boolean) {
        if (song.id in lifting || discs.any { it.track.id == song.id && !it.leaving }) return
        lifting += song.id
        scope.launch {
            val flies = !reduce && start != null
            if (flies) withTimeoutOrNull(1_500) { Pictures.fetch(context, song.artwork.url(FlightPx), FlightPx) }
            val disc = Disc(song, flies, ripples = !reduce, slotX(activeCount(), activeCount() + 1))
            val size = DiscSize.value * density
            disc.from = start?.takeIf { flies } ?: Rect(Offset((screen.width - size) / 2f, screen.height - size), Size(size, size))
            discs += disc
            lifting -= song.id
            arrange()
            if (flies) {
                // What rises from where the cover stood: not over a song's arrival, which is the larger.
                if (!ripple.waving) launch { ripple.wave(disc.from.center, 13f * density, 17f, 6f, screen.height * 1.5f, 0.14f, 0.8f) }
                launch { disc.flying.to(1f, LiftSpring) }
            } else launch { disc.sinking.to(0f, RiseSpring) }
            delay(AwaitMs)
            if (!disc.seen && !disc.leaving) sendAway(disc)
        }
    }

    private fun activeCount() = discs.count { !it.leaving }

    /** Every disc stands in the one place, the middle of the foot. */
    @Suppress("UNUSED_PARAMETER")
    private fun slotX(index: Int, of: Int): Float = 0f

    private fun arrange() {
        val active = discs.filterNot { it.leaving }
        active.take(MostDiscs).forEachIndexed { i, d -> scope.launch { d.place.to(slotX(i, active.size), PlaceSpring) } }
    }

    private fun reconcile(entries: Map<MediaId, Downloads.Entry>) {
        discs.toList().forEach { d ->
            if (d.leaving) return@forEach
            val e = entries[d.track.id]
            // A failure left from an earlier try says nothing about this one.
            if (e != null && e.status != Downloads.Status.Failed) {
                d.seen = true
                d.progress = e.progress
            }
            when {
                e == null && d.seen -> sendAway(d)
                e?.status == Downloads.Status.Kept -> arrived(d)
                e?.status == Downloads.Status.Failed && d.seen -> {
                    say("Couldn't keep this song")
                    sendAway(d)
                }
            }
        }
        // What's coming down that no cover was lifted for: a playlist's songs, an album's, new ones picked up.
        entries.values.filter { it.status == Downloads.Status.Queued || it.status == Downloads.Status.Keeping }.forEach { e ->
            if (e.track.id in lifting || discs.any { it.track.id == e.track.id }) return@forEach
            val disc = Disc(e.track, flies = false, ripples = false, slotX(activeCount(), activeCount() + 1))
            disc.seen = true
            disc.progress = e.progress
            val size = DiscSize.value * density
            disc.from = Rect(Offset((screen.width - size) / 2f, screen.height - size), Size(size, size))
            discs += disc
            scope.launch { disc.sinking.to(0f, RiseSpring) }
        }
        arrange()
    }

    private fun arrived(d: Disc) {
        d.leaving = true
        waiting++
        arrivals.trySend(d)
    }

    /** It isn't coming: stopped, failed, or never began. It only goes back down. */
    private fun sendAway(d: Disc) {
        d.leaving = true
        scope.launch {
            d.sinking.to(1f, SinkSpring)
            discs.remove(d)
            arrange()
        }
    }

    /** One at a time, so that no wave is cut short by the next. */
    private suspend fun goIn(d: Disc) {
        try {
            when {
                reduce -> {
                    d.sinking.to(1f, SinkSpring)
                    say("Kept on this phone")
                }
                !d.ripples -> {
                    d.sinking.to(1f, SinkSpring)
                    quietlyKept++
                }
                waiting > MostWaves -> d.sinking.to(1f, SinkSpring)
                else -> wave(d.place.value, d, quick = waiting > 1)
            }
        } finally {
            waiting--
            discs.remove(d)
            arrange()
        }
        if (quietlyKept > 0 && waiting == 0 && activeCount() == 0) {
            val kept = quietlyKept
            quietlyKept = 0
            if (!reduce) wave(0f, null, quick = false)
            say("${songCount(kept)} kept on this phone")
        }
    }

    private suspend fun wave(atX: Float, d: Disc?, quick: Boolean) = coroutineScope {
        val mouth = Offset(screen.width / 2f + atX, screen.height)
        ripple.aim(mouth)
        if (d != null) launch { d.sinking.to(1f, SinkSpring) }
        delay(110)
        haptics.strong()
        // The screen goes after it, and springs back.
        launch {
            ripple.pull.to(1f, DrawIn)
            ripple.pull.to(0f, LetGo)
        }
        launch {
            ripple.glow.to(1f, DrawIn)
            ripple.glow.to(0f, FluidSpring(0.9f, 1f))
        }
        launch { ripple.wave(mouth, 30f * density, 14f, 2.9f, screen.height * 1.05f, 0.6f, if (quick) 1.05f else 1.7f) }
        if (d != null) say("Kept on this phone")
    }

    /** A few words at the foot of the screen. */
    fun note(words: String) = say(words)

    private fun say(words: String) {
        scope.launch {
            word = words
            saying.to(1f, Words)
            delay(1500)
            saying.to(0f, Words)
            word = null
        }
    }
}

/** The songs in the air, the discs at the foot and the words there, over everything. Only the discs take a touch. */
@Composable
fun KeepOverlay(flight: KeepFlight) {
    val pxPerDp = LocalDensity.current.density
    val reduce = LocalReduceMotion.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        SideEffect {
            flight.screen = Size(width, height)
            flight.density = pxPerDp
            flight.reduce = reduce
        }
        flight.word?.let { FootWords(it, flight) }
        val active = flight.discs.filterNot { it.leaving }
        flight.discs.forEach { d ->
            if (d.leaving || active.indexOf(d) in 0 until MostDiscs) key(d) {
                DiscView(d, width, height, pxPerDp, count = if (!d.leaving && active.indexOf(d) == 0 && active.size > 1) active.size else 0)
            }
        }
        if (active.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(DiscSize, 40.dp)
                    .semantics {
                        role = Role.Button
                        contentDescription = "${songCount(active.size)} coming down. Show them"
                    }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { flight.onOpenList() },
            )
        }
    }
}

@Composable
private fun DiscView(d: Disc, width: Float, height: Float, pxPerDp: Float, count: Int = 0) {
    val disc = DiscSize.value * pxPerDp
    val above = DiscAbove.value * pxPerDp
    val sinks = DiscSinks.value * pxPerDp
    val gone = DiscGone.value * pxPerDp
    // How far down the screen its middle is, once it has come down.
    fun restY() = height - above + d.progress * sinks + d.sinking.value * gone
    val from = d.from
    val density = LocalDensity.current
    Box(
        Modifier
            .size(with(density) { from.width.toDp() }, with(density) { from.height.toDp() })
            .graphicsLayer {
                val f = d.flying.value
                val toX = width / 2f + d.place.value
                scaleX = 1f + (disc / from.width - 1f) * f
                scaleY = 1f + (disc / from.height - 1f) * f
                translationX = from.left + (toX - from.width / 2f - from.left) * f
                translationY = from.top + (restY() - from.height / 2f - from.top) * f
                shape = RoundedCornerShape(percent = (6 + 44 * f.coerceIn(0f, 1f)).toInt())
                clip = true
            },
    ) {
        val px = if (d.flies) FlightPx else SmallPx
        Picture(d.track.artwork.url(px), px, Modifier.fillMaxSize())
        // How many songs are coming down, on the cover itself, once the disc has come to rest: up to
        // 99, then "99+".
        if (count > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ((d.flying.value - 0.92f) / 0.08f).coerceIn(0f, 1f) }
                    .background(Ink.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) { Txt(if (count > 99) "99+" else "$count", Type.TitleSmall, maxLines = 1) }
        }
    }
    Canvas(
        Modifier
            .size(DiscSize + 8.dp)
            .graphicsLayer {
                val settled = ((d.flying.value - 0.92f) / 0.08f).coerceIn(0f, 1f)
                alpha = settled * (1f - d.sinking.value)
                translationX = width / 2f + d.place.value - size.width / 2f
                translationY = restY() - size.height / 2f
            },
    ) {
        val line = 1.5.dp.toPx()
        drawCircle(Ink.White.copy(alpha = 0.18f), style = Stroke(line))
        drawArc(Ink.White, -90f, 360f * d.progress, useCenter = false, style = Stroke(line))
    }
}

@Composable
private fun BoxScope.FootWords(words: String, flight: KeepFlight) {
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(190.dp)
            .graphicsLayer { alpha = flight.saying.value }
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black.copy(alpha = 0.72f), 1f to Color.Black)),
    ) {
        Strapline(
            words,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(bottom = 26.dp)
                .graphicsLayer { translationY = (1f - flight.saying.value) * 12f * density },
            color = Color.White,
        )
    }
}

/** Everything coming down: what's under way first, how far along, and a way to stop each. It closes when they're done. */
@Composable
fun KeepList(close: () -> Unit) {
    val container = LocalContainer.current
    val entries by container.downloads.entries.collectAsStateWithLifecycle()
    val going = remember(entries) {
        entries.values.filter { it.status == Downloads.Status.Queued || it.status == Downloads.Status.Keeping }.sortedBy { it.status != Downloads.Status.Keeping }
    }
    LaunchedEffect(going.isEmpty()) {
        if (going.isEmpty()) {
            delay(600)
            close()
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt("Coming down", Type.Display)
            Spacer(Modifier.height(4.dp))
            Strapline(songCount(going.size))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Space.edge)) {
            items(going, key = { it.track.id.value }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TreatedArtwork(e.track.artwork, ArtTreatment.Cover, Modifier.size(48.dp).clip(Corners.Small), sizeHintPx = 160)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        app.aether.core.designsystem.component.MarkedLine(e.track.title, e.track.explicit, Type.TitleSmall)
                        Txt(if (e.status == Downloads.Status.Keeping) "${(e.progress * 100).toInt()}%" else "Waiting", Type.Meta, color = Ink.Grey, maxLines = 1)
                        Spacer(Modifier.height(6.dp))
                        Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                            val round = CornerRadius(size.height)
                            drawRoundRect(Ink.White.copy(alpha = 0.12f), cornerRadius = round)
                            drawRoundRect(Ink.White, size = Size(size.width * e.progress.coerceIn(0f, 1f), size.height), cornerRadius = round)
                        }
                    }
                    IconAction(Glyph.Close, "Stop ${e.track.title}", { container.downloads.forget(e.track.id) }, size = 14.dp, tint = Ink.Grey)
                }
            }
        }
    }
}
