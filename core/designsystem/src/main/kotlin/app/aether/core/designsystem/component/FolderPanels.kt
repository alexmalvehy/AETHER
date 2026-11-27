package app.aether.core.designsystem.component

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A folder's two panels, painted once into pictures for every folder of a size, and each folder's frost
 * once for that folder: drawn, a folder is a few pictures laid down. It used to be its outlines worked
 * out and painted into two layers of its own each time a row came into view, and again each time it
 * came back (the list lets rows go as they leave): measured on the A059, the first scroll through the
 * Library spent up to 63 ms of a frame issuing that drawing, and a row coming in cost the UI thread up
 * to 33 ms; with the panels left out, 11 and 3.5 ms at the 90th percentile.
 *
 * Painted exactly as they were drawn: the same outlines, gradients, rim, sheen and frost, anti-aliased,
 * at the folder's own size in pixels, so nothing is scaled.
 */
internal object FolderPanels {
    private const val SHARED = 12
    private const val FROSTS = 32

    private val shared = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > SHARED
    }
    private val frosts = object : LinkedHashMap<String, ImageBitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > FROSTS
    }

    /** The back: a rounded panel with a tab rising on its right, in the folder's greys. */
    fun back(w: Int, h: Int, density: Density): ImageBitmap = sharedOf("back:$w:$h") {
        paint(w, h) { canvas ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = vertical(h.toFloat(), FolderBackTop, FolderBackFoot) }
            canvas.drawPath(outline(FolderBackShape, w, h, density), p)
        }
    }

    /** The front's glass, under its frost. */
    fun frontGlass(w: Int, h: Int, density: Density): ImageBitmap = sharedOf("glass:$w:$h") {
        paint(w, h) { canvas ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = vertical(h.toFloat(), FolderFrontTop, FolderFrontFoot) }
            canvas.drawPath(outline(FolderFrontShape, w, h, density), p)
        }
    }

    /** The front's rim, half a point of light inside its edge, and the light it catches along its top: over its frost. */
    fun frontLight(w: Int, h: Int, density: Density): ImageBitmap = sharedOf("light:$w:$h:${density.density}") {
        paint(w, h) { canvas ->
            val path = outline(FolderFrontShape, w, h, density)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f * density.density
                shader = vertical(h.toFloat(), Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f))
            }
            canvas.drawPath(path, rim)
            val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = vertical(h * 0.3f, Color.White.copy(alpha = 0.10f), Color.Transparent) }
            canvas.drawPath(path, sheen)
        }
    }

    /** A folder's frost, if it's been painted for this size. */
    fun frostIfPainted(key: String, w: Int, h: Int): ImageBitmap? = synchronized(frosts) { frosts["$key:$w:$h"] }

    /**
     * The cover's light and shade in the front's glass: [soft] (a software picture) cropped to fill the
     * panel overscanned by a third, grey, at [strength], within the outline. Painted off the main thread.
     */
    fun frost(key: String, w: Int, h: Int, soft: ImageBitmap, strength: Float, mono: FloatArray, density: Density): ImageBitmap {
        frostIfPainted(key, w, h)?.let { return it }
        val image = paint(w, h) { canvas ->
            val bitmap = soft.asAndroidBitmap()
            val left = -w * 0.35f / 2f
            val top = -h * 0.35f / 2f
            val areaW = w * 1.35f
            val areaH = h * 1.35f
            val scale = max(areaW / bitmap.width, areaH / bitmap.height)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(left + (areaW - bitmap.width * scale) / 2f, top + (areaH - bitmap.height * scale) / 2f)
            }
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
                colorFilter = ColorMatrixColorFilter(mono)
                alpha = (strength * 255f).roundToInt().coerceIn(0, 255)
            }
            canvas.drawPath(outline(FolderFrontShape, w, h, density), p)
        }
        synchronized(frosts) { frosts["$key:$w:$h"] = image }
        return image
    }

    private inline fun sharedOf(key: String, make: () -> ImageBitmap): ImageBitmap {
        synchronized(shared) { shared[key]?.let { return it } }
        val image = make()
        synchronized(shared) { shared[key] = image }
        return image
    }

    /**
     * Painted on the processor, then handed to the GPU once and kept there. A picture left as a bitmap
     * that can still be painted on is sent again every time it's drawn (measured: each folder's three
     * pictures uploaded again on every frame of a scroll).
     */
    private inline fun paint(w: Int, h: Int, draw: (Canvas) -> Unit): ImageBitmap {
        val bitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap))
        val kept = bitmap.copy(Bitmap.Config.HARDWARE, false) ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (kept != null) {
            bitmap.recycle()
            return kept.asImageBitmap()
        }
        bitmap.prepareToDraw()
        return bitmap.asImageBitmap()
    }

    private fun vertical(endY: Float, top: Color, foot: Color) =
        LinearGradient(0f, 0f, 0f, endY.coerceAtLeast(1f), top.toArgb(), foot.toArgb(), Shader.TileMode.CLAMP)

    private fun outline(shape: Shape, w: Int, h: Int, density: Density): android.graphics.Path =
        when (val o = shape.createOutline(Size(w.toFloat(), h.toFloat()), LayoutDirection.Ltr, density)) {
            is Outline.Generic -> o.path
            is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
            is Outline.Rectangle -> Path().apply { addRect(o.rect) }
        }.asAndroidPath()
}
