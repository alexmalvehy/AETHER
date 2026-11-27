// Ported from BitChord (app/src/main/java/com/music/bitchord/widget/MediaWidget.kt, github.com/kushagrasinghx/BitChord,
// GPL-3.0): its two providers that are one widget, the layout chosen by measured width, drawing off the broadcast's
// thread within its window, and the words and keys sent before a slow cover. AETHER's face: the cover going out in
// rings round its keys (WidgetArt), Now Playing's type and keys. AETHER's pace: one worker, the newest ask first,
// the keys alone when only they change, and the next song's picture drawn while this one plays.
package app.aether.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.aether.MainActivity
import app.aether.R
import app.aether.player.WidgetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * The home-screen widget: the song playing, its cover going out to black round its keys as Now Playing's
 * film goes out round the dial, its name and artists set as Now Playing sets them, and Now Playing's keys.
 *
 * Two entries in the picker, [MediaWidgetSquare] (2×2) and [MediaWidgetWide] (4×2), but one widget: which
 * layout an instance draws is decided by its measured width ([WIDE_LAYOUT_MIN_DP]), so a square dragged out to
 * four cells sets its cover beside its words, and a wide one squeezed to two stacks them.
 */
abstract class MediaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The launcher asks for the whole widget (one just placed, or after a reboot): sent whole.
        for (id in ids) sent.remove(id)
        drawHeld(context)
    }

    /** Every resize is a new picture: it's drawn at the widget's own size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        sent.remove(id)
        drawHeld(context)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) sent.remove(id)
    }

    override fun onDisabled(context: Context) {
        WidgetArt.clear()
        sent.clear()
    }

    /** Drawn by the worker, the broadcast held open ([goAsync]) until it has been, within the broadcast's window. */
    private fun drawHeld(context: Context) {
        val pending = goAsync()
        val number = ask(context)
        scope.launch {
            try {
                withTimeoutOrNull(BROADCAST_BUDGET_MS) { drawn.first { it >= number } }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {
        /**
         * The measured width from which the cover goes beside the words (BitChord's: halfway between three
         * cells, 180 dp, and four, 250 dp, so cells a little off 70 dp still land on the right side of it).
         */
        const val WIDE_LAYOUT_MIN_DP = 215

        /** Draws every widget placed from what the service last said. Called by the service as that changes: hands off at once. */
        fun refresh(context: Context) {
            ask(context)
        }

        /** The views a widget of [widthDp] × [heightDp] would show now (the debug preview draws them as a launcher would). */
        internal suspend fun views(context: Context, widthDp: Int, heightDp: Int): RemoteViews {
            val state = WidgetState.load(context)
            val size = sizeOf(context, widthDp, heightDp)
            return views(context, state, size, WidgetArt.render(context, state.artwork, size))
        }

        /**
         * The play key turned to [playing] on every widget at once, before the player has answered: a tap is seen
         * to land. Only the key is sent. A tap that doesn't take is turned back by the next drawing, which goes by
         * what the player says.
         */
        fun showPlaying(context: Context, playing: Boolean) {
            val app = context.applicationContext
            val manager = runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return
            for ((id, was) in sent) {
                if (!was.state.hasTrack || was.state.playing == playing) continue
                val shows = was.state.copy(playing = playing)
                runCatching { manager.partiallyUpdateAppWidget(id, keys(app, shows, was.wide)) }
                sent[id] = was.copy(state = shows)
            }
        }

        /**
         * Every widget asked to be drawn again; the number of the ask, to wait on. One worker draws them, and a newer
         * ask replaces one it hasn't finished (the service says several things over one song change), so a widget is
         * never sent something older than what it was sent before.
         */
        private fun ask(context: Context): Long {
            val number = asked.incrementAndGet()
            asks.trySend(context.applicationContext)
            worker.start()
            return number
        }

        /** Every widget placed, as the service last said; then the next song's picture, begun while nothing waits on it. */
        private suspend fun drawAll(context: Context) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val state = WidgetState.load(context)
            val sizes = ArrayList<WidgetSize>()
            for ((provider, fallbackWidthDp) in PROVIDERS) {
                val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, provider)) }.getOrNull() ?: continue
                for (id in ids) {
                    val size = measure(context, manager, id, fallbackWidthDp)
                    sizes += size
                    try {
                        withTimeoutOrNull(DRAW_TIMEOUT_MS) { draw(context, manager, id, state, size) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A launcher gone, or a picture refused: the others are still drawn, and this one next time.
                    }
                }
            }
            // At the change, the next song's cover comes with its name.
            state.nextArtwork?.let { next -> for (size in sizes) picture(context, next, size) }
        }

        /**
         * One widget brought to [state]. The same song at the same size: its keys alone, or nothing if they're as
         * they were. Another song: its words and keys at once, over the picture the widget has (sent alone, they
         * leave it be), and the song's own picture when it's drawn, unless it was drawn ahead.
         */
        private suspend fun draw(context: Context, manager: AppWidgetManager, id: Int, state: WidgetState, size: WidgetSize) {
            val before = sent[id]
            val ready = WidgetArt.peek(state.artwork, size)
            if (before != null && before.size == size.key && before.state.sameSong(state)) {
                if (before.state != state) {
                    runCatching { manager.partiallyUpdateAppWidget(id, keys(context, state, size.wide)) }
                    sent[id] = before.copy(state = state)
                }
                if (before.whole) return
            } else if (ready == null) {
                runCatching { manager.partiallyUpdateAppWidget(id, views(context, state, size, art = null)) }
                sent[id] = Sent(state, size.key, size.wide, whole = false)
            }
            val art = ready ?: picture(context, state.artwork, size).await()
            manager.push(id, views(context, state, size, art))
            // Whole once the picture is the song's own: one whose cover didn't come is tried again next time.
            sent[id] = Sent(state, size.key, size.wide, whole = WidgetArt.peek(state.artwork, size) != null)
        }

        /**
         * A song's picture at a size, drawn once however many ask for it, on the process's scope: an ask replaced
         * while it's being drawn doesn't stop it, and the ask that replaced it waits on the same one.
         */
        private fun picture(context: Context, url: String?, size: WidgetSize): Deferred<Bitmap> {
            val key = "$url|${size.key}"
            val job = drawing.computeIfAbsent(key) {
                scope.async(start = CoroutineStart.LAZY) {
                    try {
                        WidgetArt.render(context, url, size)
                    } finally {
                        drawing.remove(key)
                    }
                }
            }
            job.start()
            return job
        }

        /** The same song, words and picture: only the keys can differ. */
        private fun WidgetState.sameSong(other: WidgetState) =
            id == other.id && title == other.title && artist == other.artist && artwork == other.artwork

        /** The keys alone, to be laid over what a widget already shows. */
        private fun keys(context: Context, state: WidgetState, wide: Boolean): RemoteViews =
            RemoteViews(context.packageName, if (wide) R.layout.widget_wide else R.layout.widget_square).apply {
                setImageViewResource(R.id.widget_toggle, if (state.playing) R.drawable.widget_pause else R.drawable.widget_play)
                setContentDescription(R.id.widget_toggle, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
                setImageAlpha(R.id.widget_next, if (!state.hasTrack || state.hasNext) ALPHA_ENABLED else ALPHA_DISABLED)
            }

        private fun views(context: Context, state: WidgetState, size: WidgetSize, art: Bitmap?): RemoteViews {
            val views = RemoteViews(context.packageName, if (size.wide) R.layout.widget_wide else R.layout.widget_square)
            art?.let { views.setImageViewBitmap(R.id.widget_art, it) }
            views.setTextViewText(R.id.widget_title, if (state.hasTrack) state.title else context.getString(R.string.widget_nothing))
            if (size.wide) {
                views.setTextViewText(R.id.widget_artist, state.artist)
                views.setViewVisibility(R.id.widget_artist, if (state.artist.isBlank()) View.GONE else View.VISIBLE)
                // The words and keys stand in the black, from where the cover ends.
                val edge = (12 * size.dp).roundToInt()
                views.setViewPadding(R.id.widget_panel, size.heightPx + (6 * size.dp).roundToInt(), edge, edge, edge)
            }
            views.setImageViewResource(R.id.widget_toggle, if (state.playing) R.drawable.widget_pause else R.drawable.widget_play)
            views.setContentDescription(R.id.widget_toggle, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))

            // Anywhere but a key opens Now Playing.
            val open = openNowPlaying(context)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            if (state.hasTrack) {
                views.setImageAlpha(R.id.widget_toggle, ALPHA_ENABLED)
                views.setImageAlpha(R.id.widget_previous, ALPHA_ENABLED)
                // Dimmed at the end of the queue, as the notification has it.
                views.setImageAlpha(R.id.widget_next, if (state.hasNext) ALPHA_ENABLED else ALPHA_DISABLED)
                views.setOnClickPendingIntent(R.id.widget_toggle, WidgetActions.pendingIntent(context, WidgetActions.ACTION_TOGGLE))
                views.setOnClickPendingIntent(R.id.widget_previous, WidgetActions.pendingIntent(context, WidgetActions.ACTION_PREVIOUS))
                views.setOnClickPendingIntent(R.id.widget_next, WidgetActions.pendingIntent(context, WidgetActions.ACTION_NEXT))
            } else {
                // Nothing has played: the keys open the app, which is more use than keys that do nothing.
                for (key in TRANSPORT) {
                    views.setImageAlpha(key, ALPHA_DISABLED)
                    views.setOnClickPendingIntent(key, open)
                }
            }
            return views
        }

        /** ImageView's setImageAlpha is remotable, though RemoteViews has no helper for it. */
        private fun RemoteViews.setImageAlpha(viewId: Int, alpha: Int) = setInt(viewId, "setImageAlpha", alpha)

        /** The app, opened on Now Playing. An explicit component and no action, so a running app gets it too. */
        private fun openNowPlaying(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_NOW_PLAYING, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** A launcher that has gone, or refuses the picture, isn't worth a crash in a broadcast. */
        private fun AppWidgetManager.push(id: Int, views: RemoteViews) {
            runCatching { updateAppWidget(id, views) }
        }

        /**
         * The size to draw this widget at, in the picture's pixels. The launcher reports a range: portrait wants
         * its least width and greatest height, landscape the other way about.
         */
        private fun measure(context: Context, manager: AppWidgetManager, id: Int, fallbackWidthDp: Int): WidgetSize {
            val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthDp = options?.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                ?.takeIf { it > 0 } ?: fallbackWidthDp
            val heightDp = options?.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                ?.takeIf { it > 0 } ?: FALLBACK_HEIGHT_DP
            return sizeOf(context, widthDp, heightDp)
        }

        private fun sizeOf(context: Context, widthDp: Int, heightDp: Int): WidgetSize {
            val density = context.resources.displayMetrics.density
            val rawWidth = widthDp * density
            val rawHeight = heightDp * density
            // Every update hands the picture to the launcher, which keeps it while it's on screen: bounded, so a
            // tablet's widget can't hand over something absurd. A phone's never reaches it.
            val longest = maxOf(rawWidth, rawHeight)
            val scale = if (longest > MAX_BITMAP_PX) MAX_BITMAP_PX / longest else 1f
            return WidgetSize(
                wide = widthDp >= WIDE_LAYOUT_MIN_DP,
                widthPx = (rawWidth * scale).roundToInt().coerceAtLeast(1),
                heightPx = (rawHeight * scale).roundToInt().coerceAtLeast(1),
                dp = density * scale,
                cornerPx = context.resources.getDimension(R.dimen.widget_radius) * scale,
            )
        }

        private val PROVIDERS = listOf(MediaWidgetSquare::class.java to SQUARE_WIDTH_DP, MediaWidgetWide::class.java to WIDE_WIDTH_DP)
        private val TRANSPORT = intArrayOf(R.id.widget_previous, R.id.widget_toggle, R.id.widget_next)

        /** What a widget was last sent: the state it shows, the size it was drawn at, and whether its picture is the song's own. */
        private data class Sent(val state: WidgetState, val size: String, val wide: Boolean, val whole: Boolean)

        /** By widget. Forgotten with the widget, and whenever the launcher asks for it whole. */
        private val sent = ConcurrentHashMap<Int, Sent>()

        /** Pictures being drawn, by song and size. */
        private val drawing = ConcurrentHashMap<String, Deferred<Bitmap>>()

        /** Drawing runs on the process's scope: a provider lives only for one broadcast, and [refresh] isn't one. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private val asked = AtomicLong()
        private val drawn = MutableStateFlow(0L)
        private val asks = Channel<Context>(Channel.CONFLATED)
        private val worker = scope.launch(start = CoroutineStart.LAZY) {
            asks.receiveAsFlow().collectLatest { app ->
                val number = asked.get()
                try {
                    drawAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The next ask draws again.
                }
                drawn.update { maxOf(it, number) }
            }
        }

        /** How long one widget may take, a cover's fetch included. */
        private const val DRAW_TIMEOUT_MS = 8_000L

        /** How long a broadcast is held open for its drawing: inside the system's window. */
        private const val BROADCAST_BUDGET_MS = 8_000L

        private const val MAX_BITMAP_PX = 1_200f
        private const val ALPHA_ENABLED = 255
        private const val ALPHA_DISABLED = 90
        private const val REQUEST_OPEN = 0
    }
}

/** Grid spans, by the launcher's own arithmetic: n cells are 70n − 30 dp. */
private const val SQUARE_WIDTH_DP = 110
private const val WIDE_WIDTH_DP = 250
private const val FALLBACK_HEIGHT_DP = 110

/** The 2×2 in the picker (res/xml/widget_square_info.xml). */
class MediaWidgetSquare : MediaWidget()

/** The 4×2 in the picker (res/xml/widget_wide_info.xml). */
class MediaWidgetWide : MediaWidget()
