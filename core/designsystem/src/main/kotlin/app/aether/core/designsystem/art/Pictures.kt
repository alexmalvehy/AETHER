package app.aether.core.designsystem.art

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.graphicsLayer
import app.aether.core.designsystem.theme.Motion
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.Transformation

/**
 * Pictures that never load twice. Every picture is asked for at a size and kept in the image
 * memory cache under its own key (address, size, how it's decoded), and looked up there while the
 * screen is being composed: anything seen before is drawn on the very first frame, with no blank
 * and no fade. A folder flying to its page, a card scrolled back into view, Now Playing reopened:
 * each shows exactly what it showed before. Only a picture never seen goes to the network or disk.
 */
object Pictures {
    fun key(url: String, px: Int, software: Boolean, tag: String?): String =
        buildString {
            append(url); append('#'); append(px)
            if (software) append("#sw")
            if (tag != null) { append('#'); append(tag) }
        }

    /** The picture if it's already in memory: no waiting, no request. */
    fun peek(context: android.content.Context, key: String): ImageBitmap? =
        context.imageLoader.memoryCache?.get(MemoryCache.Key(key))?.image?.toBitmap()?.asImageBitmap()

    fun request(context: android.content.Context, url: String, px: Int, software: Boolean, transformation: Transformation?, key: String): ImageRequest =
        ImageRequest.Builder(context)
            .data(url)
            .size(px)
            .memoryCacheKey(key)
            .allowHardware(!software)
            .apply { if (transformation != null) transformations(transformation) }
            .build()

    /** Fetches [url] into memory ahead of need (a folder's pictures before its page opens). */
    fun prefetch(context: android.content.Context, url: String?, px: Int, software: Boolean = false, transformation: Transformation? = null, tag: String? = null) {
        url ?: return
        val k = key(url, px, software, tag ?: transformation?.cacheKey)
        if (context.imageLoader.memoryCache?.get(MemoryCache.Key(k)) != null) return
        context.imageLoader.enqueue(request(context, url, px, software, transformation, k))
    }

    /**
     * The picture's own pixels, for what's made from them (a morph's dots): from memory when it's
     * there, otherwise loaded once. Null when it can't be had.
     */
    suspend fun bitmap(context: android.content.Context, url: String?, px: Int): Bitmap? {
        url ?: return null
        val k = key(url, px, true, null)
        val image = context.imageLoader.memoryCache?.get(MemoryCache.Key(k))?.image
            ?: (context.imageLoader.execute(request(context, url, px, true, null, k)) as? SuccessResult)?.image
            ?: return null
        val bitmap = image.toBitmap()
        return if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
    }

    /** Waits until [url] is in memory: for a picture that shouldn't be shown before it's there. */
    suspend fun fetch(context: android.content.Context, url: String?, px: Int, software: Boolean = false) {
        url ?: return
        val k = key(url, px, software, null)
        if (context.imageLoader.memoryCache?.get(MemoryCache.Key(k)) != null) return
        context.imageLoader.execute(request(context, url, px, software, null, k))
    }
}

/** A picture on its way or here, and whether it was already in memory when the screen asked for it. */
@Stable
class PictureState internal constructor(initial: ImageBitmap?) {
    var bitmap by mutableStateOf(initial)
        internal set

    /** It was in memory: drawn on the first frame, nothing to reveal. */
    val fromMemory: Boolean = initial != null
}

/**
 * The picture at [url] at [px], from memory at once when it's there, otherwise loaded once.
 * [software] when a shader samples it; [transformation] to keep a treated copy (the soft blur).
 */
@Composable
fun rememberPictureState(url: String?, px: Int, software: Boolean = false, transformation: Transformation? = null): PictureState {
    val context = LocalContext.current
    val key = remember(url, px, software, transformation) { url?.let { Pictures.key(it, px, software, transformation?.cacheKey) } }
    val state = remember(key) { PictureState(key?.let { Pictures.peek(context, it) }) }
    if (state.bitmap == null && url != null && key != null) {
        LaunchedEffect(key) {
            val result = context.imageLoader.execute(Pictures.request(context, url, px, software, transformation, key))
            val image = (result as? SuccessResult)?.image?.toBitmap() ?: return@LaunchedEffect
            state.bitmap = (if (software && image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image).asImageBitmap()
        }
    }
    return state
}

@Composable
fun rememberPicture(url: String?, px: Int, software: Boolean = false, transformation: Transformation? = null): ImageBitmap? =
    rememberPictureState(url, px, software, transformation).bitmap

/**
 * How visible a picture is: 1 at once when it came from memory; otherwise it rises from 0 as it
 * arrives, on a spring with no bounce, the way a print comes up in the developer.
 */
@Composable
fun PictureState.arrival(): () -> Float {
    val alpha = remember(this) { Animatable(if (fromMemory) 1f else 0f) }
    val here = bitmap != null
    if (!fromMemory) LaunchedEffect(this, here) { if (here) alpha.animateTo(1f, Motion.fluid(0.42f, 1f)) }
    return { alpha.value }
}

/** A picture cropped to fill: drawn the frame it's composed when it's in memory, faded in when it isn't. */
@Composable
fun Picture(
    url: String?,
    px: Int,
    modifier: Modifier = Modifier,
    colorFilter: ColorFilter? = null,
    transformation: Transformation? = null,
    software: Boolean = false,
    filterQuality: FilterQuality = FilterQuality.Medium,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val state = rememberPictureState(url, px, software, transformation)
    val shown = state.arrival()
    val bitmap = state.bitmap ?: return
    Image(
        bitmap,
        contentDescription,
        modifier.graphicsLayer { alpha = shown() },
        contentScale = contentScale,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}
