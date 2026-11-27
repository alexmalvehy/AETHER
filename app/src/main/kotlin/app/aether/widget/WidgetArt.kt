// After BitChord's widget artwork (app/src/main/java/com/music/bitchord/widget/MediaWidgetArt.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): its one composited picture per song and size, drawn off the
// main thread, its cover cropped from the centre, its cache and its rounded corners. Its blurred band is
// AETHER's rings instead: the cover going out round the keys as Now Playing's film goes out round the dial.
package app.aether.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.hypot

/** The size a widget is drawn at, in the picture's pixels. */
internal class WidgetSize(
    val wide: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    /** How many pixels a dp is in the picture (the screen's, scaled down with a very large widget). */
    val dp: Float,
    val cornerPx: Float,
) {
    /** The 4×2's cover is a square as tall as the widget; the words and keys stand in the black beside it. */
    val coverPx: Int get() = if (wide) heightPx else widthPx

    /** Which layout, at which size: a widget sent at another size is sent whole again. */
    val key: String get() = "${if (wide) "w" else "s"}${widthPx}x$heightPx"
}

/**
 * The one picture a widget shows behind its words and keys: the song's cover in its own colour, going out to
 * black in rings round where the keys are, as Now Playing's film goes out round the dial. Drawn once per song
 * and size, because a widget can't draw a gradient over a picture of its own, let alone one in rings.
 *
 * The 4×2 has its rings round the right edge, the middle of it (the dial stood on the screen's edge, as on
 * AETHER's TV screen), wide enough that the black takes the whole of the panel where the words go; the 2×2 has
 * them round the middle of its foot, as Now Playing has them round the dial's pivot, gone by the song's name.
 * Each fades along Now Playing's smoothstep, over half the widget's height, dithered so it has no bands.
 */
internal object WidgetArt {

    fun peek(url: String?, size: WidgetSize): Bitmap? = composites[key(url, size)]?.takeIf { !it.isRecycled }

    suspend fun render(context: Context, url: String?, size: WidgetSize): Bitmap {
        peek(url, size)?.let { return it }
        val w = size.widthPx
        val h = size.heightPx
        val cover = url?.let { load(context, it, size.coverPx) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val place = Rect(0, 0, size.coverPx, if (size.wide) h else h)
        if (cover != null) canvas.fillCentreCropped(cover, place)
        else canvas.drawRect(place, Paint().apply { color = PLACEHOLDER })
        canvas.rings(size)
        val rounded = out.withRoundedCorners(size.cornerPx)
        if (rounded !== out) out.recycle()
        // A cover asked for and not had isn't kept, so the next song change, or a connection come back, draws it.
        if (cover != null || url == null) composites.put(key(url, size), rounded)
        return rounded
    }

    fun clear() = composites.evictAll()

    /** Black over the cover in rings round the keys: none of the cover by them, all of it [FADE] further out. */
    private fun Canvas.rings(size: WidgetSize) {
        val w = size.widthPx.toFloat()
        val h = size.heightPx.toFloat()
        val (cx, cy, gone) = if (size.wide) {
            // Round the right edge's middle, wide enough to take the panel's corners into the black.
            Triple(w, h / 2f, hypot(w - h + PANEL_SPARE_DP * size.dp, h / 2f))
        } else {
            // Round the foot's middle, gone by the top of the name.
            Triple(w / 2f, h, SQUARE_BAND_DP * size.dp)
        }
        val full = gone + FADE * h
        val stops = FloatArray(STOPS + 2)
        val colors = IntArray(STOPS + 2)
        stops[0] = 0f
        colors[0] = Color.BLACK
        for (i in 0..STOPS) {
            val t = i / STOPS.toFloat()
            stops[i + 1] = (gone + t * (full - gone)) / full
            val shown = t * t * (3f - 2f * t)
            colors[i + 1] = Color.argb(((1f - shown) * 255f).toInt().coerceIn(0, 255), 0, 0, 0)
        }
        drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(cx, cy, full, colors, stops, Shader.TileMode.CLAMP)
            isDither = true
        })
    }

    /** Fills [place] with [src], cropped from its centre rather than squashed. */
    private fun Canvas.fillCentreCropped(src: Bitmap, place: Rect) {
        val scale = maxOf(place.width().toFloat() / src.width, place.height().toFloat() / src.height)
        val sampleW = (place.width() / scale).coerceAtMost(src.width.toFloat())
        val sampleH = (place.height() / scale).coerceAtMost(src.height.toFloat())
        val left = (src.width - sampleW) / 2f
        val top = (src.height - sampleH) / 2f
        drawBitmap(
            src,
            Rect(left.toInt(), top.toInt(), (left + sampleW).toInt(), (top + sampleH).toInt()),
            place,
            Paint().apply { isFilterBitmap = true },
        )
    }

    /** The same picture with its corners rounded off, through a shader, so the arcs are smooth. */
    private fun Bitmap.withRoundedCorners(radiusPx: Float): Bitmap {
        if (radiusPx <= 0f) return this
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawRoundRect(
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            radiusPx,
            radiusPx,
            Paint().apply {
                isAntiAlias = true
                shader = BitmapShader(this@withRoundedCorners, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            },
        )
        return out
    }

    private suspend fun load(context: Context, url: String, px: Int): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(px)
            // Its pixels are read to draw it into the picture.
            .allowHardware(false)
            .build()
        val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    private fun key(url: String?, size: WidgetSize) = "${url ?: "none"}|${size.widthPx}x${size.heightPx}|${size.wide}"

    /** What stands where a cover isn't: one that didn't load, or nothing ever played. */
    private const val PLACEHOLDER = 0xFF161616.toInt()

    /** How far past the panel's edge the 4×2's black reaches, so the words never sit on the cover. */
    private const val PANEL_SPARE_DP = 6f

    /** The 2×2's black, up from its foot: the name over the keys. Matches @dimen/widget_square_band. */
    private const val SQUARE_BAND_DP = 64f

    /** The rings' fade, from none of the cover to all of it, as a share of the widget's height. */
    private const val FADE = 0.5f

    /** Points along the smoothstep: enough that it reads as a curve, not as steps. */
    private const val STOPS = 32

    /** The pictures drawn, by song and size: the launchers hold them too, while they're on screen. */
    private val composites = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
}
