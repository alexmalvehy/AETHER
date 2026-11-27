package app.aether.core.designsystem.component

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.PictureState
import app.aether.core.designsystem.art.SoftBlur
import app.aether.core.designsystem.art.arrival
import app.aether.core.designsystem.art.rememberPictureState
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Artwork
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect

/** One of a folder's "files": a song peeking out of it. */
data class FolderFile(val label: String, val artwork: Artwork)

/**
 * How a folder stands under the finger: leaning toward it, its songs rising. A folder that flies
 * away hands this on, so it leaves the list exactly as it was leaning there.
 */
@Stable
class FolderPose {
    val press = Animatable(0f)
    val leanX = Animatable(0f)
    val leanY = Animatable(0f)
}

/*
 * A folder's pictures are asked for at these sizes wherever it's drawn, in a list or held open at
 * the head of its page, so they're the same pictures in memory: the folder that flies to its page
 * arrives exactly as it left, on the first frame, every time.
 */
private const val FilePx = 360
private const val SoftPx = 40

/**
 * A playlist as a grey glass folder (the folder recording): its songs peek out as small cards, in
 * their own colour, and the front is frosted glass with the title. The folder itself is always
 * neutral: frosted from a cover's colours it went to mud, so all it takes from the cover is its light
 * and shade. It leans toward the finger; pressed, its songs rise; tapped, it opens: the front tips away and the
 * songs lift out, before [onOpen] runs. [opened] draws it held open, as it stands at the head of its
 * own page. Only presentation: the playlist itself lives elsewhere.
 *
 * In flight ([flying] 0 to 1) it's drawn at its full [width] by whoever scales it down to where it
 * is, and it opens as it goes. At 0 it's exactly the folder of [fromWidth] it left: everything on it
 * measured in points (its words, and how far its files are set in) holds its size against the
 * scale, because those are the same size on the small folder and the large.
 *
 * Drawn light: the back and the files are one drawing, the front another, each picture painted
 * straight into its outline (no clipping layers), from pictures kept in memory.
 */
@Composable
fun FolderCard(
    title: String,
    detail: String?,
    artwork: Artwork,
    files: List<FolderFile>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 168.dp,
    opened: Boolean = false,
    pose: FolderPose = remember { FolderPose() },
    flying: (() -> Float)? = null,
    fromWidth: Dp = width,
) {
    val height = width * 0.86f
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val press = pose.press
    val leanX = pose.leanX
    val leanY = pose.leanY
    val resting = if (opened) 1f else 0f
    val open: () -> Float = flying ?: { resting }
    // In flight: the scale it's being drawn at, and what undoes it for anything that holds its size.
    val small = if (flying != null && width > 0.dp) fromWidth / width else 1f
    val scaled: () -> Float = if (flying != null) ({ small + (1f - small) * flying() }) else ({ 1f })
    // The back and the frosted front are pictures, painted once (FolderPanels): flat, they lean and tilt
    // as pictures do. Painting the outlines under a 3D tilt made the GPU work out each one's coverage by
    // hand, a tenth of a second or more for a large folder; painting them into layers of their own as
    // each row came into view was most of what a first scroll through the Library cost.
    val screen = LocalDensity.current
    val frontW = with(screen) { width.roundToPx() }
    val frontH = with(screen) { (height * 0.7f).roundToPx() }

    // A folder the source named no songs for still holds its own cover.
    val cards = remember(files, artwork) { files.ifEmpty { if (artwork.isEmpty) emptyList() else listOf(FolderFile("", artwork)) } }
    val soft = rememberPictureState(artwork.url(SoftPx), SoftPx, software = true, transformation = SoftBlur)
    val f0 = rememberPictureState(cards.getOrNull(0)?.artwork?.url(FilePx), FilePx)
    val f1 = rememberPictureState(cards.getOrNull(1)?.artwork?.url(FilePx), FilePx)
    val f2 = rememberPictureState(cards.getOrNull(2)?.artwork?.url(FilePx), FilePx)
    val softPaint = rememberPicturePaint(soft.bitmap)
    val filePaints = listOf(rememberPicturePaint(f0.bitmap), rememberPicturePaint(f1.bitmap), rememberPicturePaint(f2.bitmap))
    val softShown = soft.arrival()
    val fileShown = listOf(f0.arrival(), f1.arrival(), f2.arrival())
    // The frost for this folder at this size, painted off the main thread once its picture is here, and
    // kept, so a row scrolled away and back has it at once. Until then it's painted live, as it looks.
    val softKey = artwork.url(SoftPx) ?: ""
    var frost by remember(softKey, frontW, frontH) { mutableStateOf(FolderPanels.frostIfPainted(softKey, frontW, frontH)) }
    LaunchedEffect(soft.bitmap, softKey, frontW, frontH) {
        val picture = soft.bitmap ?: return@LaunchedEffect
        if (frost != null || frontW <= 0 || frontH <= 0) return@LaunchedEffect
        frost = withContext(Dispatchers.Default) { FolderPanels.frost(softKey, frontW, frontH, picture, FrostStrength, MonoValues, screen) }
    }

    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, height)
                .semantics {
                    contentDescription = listOfNotNull(title, detail).joinToString(", ")
                    if (!opened) {
                        role = Role.Button
                        onClick { onOpen(); true }
                    }
                }
                .then(if (opened) Modifier else Modifier.pointerInput(onOpen) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun lean(p: Offset) {
                            val nx = (p.x / size.width - 0.5f).coerceIn(-0.5f, 0.5f)
                            val ny = (p.y / size.height - 0.5f).coerceIn(-0.5f, 0.5f)
                            scope.launch { leanY.animateTo(nx * 14f, spring(0.7f, 600f)) }
                            scope.launch { leanX.animateTo(-ny * 12f, spring(0.7f, 600f)) }
                        }
                        lean(down.position)
                        scope.launch { press.animateTo(1f, spring(0.7f, 700f)) }
                        var travelled = 0f
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) { released = true; break }
                            travelled += c.positionChange().getDistance()
                            // A drag belongs to the list it scrolls in, not to the folder.
                            if (travelled > viewConfiguration.touchSlop) break
                            lean(c.position)
                        }
                        scope.launch { leanX.animateTo(0f, spring(0.45f, 300f)) }
                        scope.launch { leanY.animateTo(0f, spring(0.45f, 300f)) }
                        scope.launch { press.animateTo(0f, spring(0.5f, 400f)) }
                        if (released && travelled <= viewConfiguration.touchSlop) {
                            haptics.tick()
                            // The page opens at once; the folder flies to it, opening as it goes.
                            onOpen()
                        }
                    }
                })
                .graphicsLayer {
                    rotationX = leanX.value + open() * 6f
                    rotationY = leanY.value
                    cameraDistance = 12f * density
                },
        ) {
            // The back panel: its outline alone, painted once and kept. With the songs in the same
            // drawing, every frame they rose in flight painted that large curved outline again, worked
            // out by the processor and sent to the GPU each time.
            Spacer(
                Modifier.fillMaxSize().drawWithCache {
                    val back = FolderPanels.back(size.width.roundToInt(), size.height.roundToInt(), this)
                    onDrawBehind { drawImage(back) }
                },
            )
            // The playlist's songs peeking out of the back and rising: plain rounded cards, which the
            // GPU draws as they move, each cover as it is (nothing is written on them, nothing darkens them).
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    val opening = open()
                    // A point on the folder in flight, as many of its own points as make one on screen.
                    val pt = 1f / scaled().coerceAtLeast(0.01f)
                    val lift = (press.value * 7f * pt + opening * 22f).dp.toPx()
                    for (i in 0 until minOf(3, cards.size)) {
                        val fw = size.width * (0.62f - i * 0.06f)
                        val fh = size.height * 0.4f
                        val left = size.width - (10 + i * 12).dp.toPx() * pt - fw
                        val top = size.height * 0.14f + (i * 4).dp.toPx() * pt - lift * (1f - i * 0.25f)
                        val angle = (i - 1) * 2.2f * (1f + opening)
                        translate(left, top) {
                            rotate(angle, pivot = Offset(fw / 2f, fh / 2f)) {
                                val card = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, fw, fh, CornerRadius(5.dp.toPx() * pt))) }
                                drawPath(card, Ink.Coal)
                                filePaints[i]?.draw(this, card, Rect0(Size(fw, fh)), fileShown[i]())
                            }
                        }
                    }
                },
            )
            // Front panel: grey frosted glass with the cover's light and shade in it, rim-lit, with the
            // title. It tilts as a whole; its painting inside is kept flat, painted once (painted inside the
            // tilt, it was painted again in perspective each time the tilt moved, and on every visit
            // the glass made to the page), and its words stand over it.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(height * 0.7f)
                    .graphicsLayer {
                        rotationX = -open() * 18f
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        cameraDistance = 12f * density
                    },
            ) {
                Spacer(
                    Modifier.matchParentSize().drawWithCache {
                        val w = size.width.roundToInt()
                        val h = size.height.roundToInt()
                        val glass = FolderPanels.frontGlass(w, h, this)
                        // The rim (half a point of light inside the panel's edge) and the light along its top.
                        val light = FolderPanels.frontLight(w, h, this)
                        val frontPath = outlinePath(FolderFrontShape, size, layoutDirection, this)
                        // The frost overscans the panel by a third, so its soft edges never show.
                        val over = Rect0(size).inflate(0.35f)
                        onDrawBehind {
                            drawImage(glass)
                            val painted = frost?.takeIf { it.width == w && it.height == h }
                            if (painted != null) drawImage(painted, alpha = softShown().coerceIn(0f, 1f))
                            else softPaint?.draw(this, frontPath, over, softShown() * FrostStrength, Mono)
                            drawImage(light)
                        }
                    },
                )
                if (flying == null) {
                    FolderWords(title, detail, Modifier.align(Alignment.TopEnd).padding(top = height * 0.17f, end = WordsInset, start = WordsInset))
                } else {
                    // The words as they're set on the small folder give way to those on the large one
                    // (a long name wraps differently), and neither scales with the folder.
                    fun Modifier.heldSize(alpha: () -> Float) = graphicsLayer {
                        val pt = 1f / scaled().coerceAtLeast(0.01f)
                        scaleX = pt
                        scaleY = pt
                        transformOrigin = TransformOrigin(1f, 0f)
                        translationX = -WordsInset.toPx() * (pt - 1f)
                        this.alpha = alpha()
                    }
                    FolderWords(
                        title,
                        detail,
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = height * 0.17f, end = WordsInset)
                            .widthIn(max = fromWidth - WordsInset * 2)
                            .heldSize { 1f - wordsArrive(flying()) },
                    )
                    FolderWords(
                        title,
                        detail,
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = height * 0.17f, end = WordsInset, start = WordsInset)
                            .heldSize { wordsArrive(flying()) },
                    )
                }
            }
        }
    }
}

private val WordsInset = 12.dp

/** The folder's greys: the same for every folder, whatever is in it. */
internal val FolderBackTop = Color(0xFF3A3A3D)
internal val FolderBackFoot = Color(0xFF2A2A2C)
internal val FolderFrontTop = Color(0xFF58585C)
internal val FolderFrontFoot = Color(0xFF3B3B3E)

/** How much of the cover's light and shade shows in the frost. */
private const val FrostStrength = 0.4f

/** Over which part of a flight the words of the small folder give way to those of the large. */
private fun wordsArrive(t: Float): Float = ((t - 0.2f) / 0.45f).coerceIn(0f, 1f)

@Composable
private fun FolderWords(title: String, detail: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End) {
        Txt(title, Type.TitleSmall, maxLines = 2, align = TextAlign.End)
        if (detail != null) Txt(detail, Type.Label, color = Color.White.copy(alpha = 0.72f), maxLines = 1, upper = true)
    }
}

/** A rectangle by its corners, for where a picture is laid. */
private class Rect0(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    constructor(size: Size) : this(0f, 0f, size.width, size.height)
    val width get() = right - left
    val height get() = bottom - top
    fun same(o: Rect0) = left == o.left && top == o.top && right == o.right && bottom == o.bottom
    fun inflate(fraction: Float) = Rect0(left - width * fraction / 2f, top - height * fraction / 2f, right + width * fraction / 2f, bottom + height * fraction / 2f)
}

/** Grey with a little contrast: what keeps a cover's colours out of the frost. */
private val MonoValues = floatArrayOf(
    0.2126f * 1.12f, 0.7152f * 1.12f, 0.0722f * 1.12f, 0f, -15.4f,
    0.2126f * 1.12f, 0.7152f * 1.12f, 0.0722f * 1.12f, 0f, -15.4f,
    0.2126f * 1.12f, 0.7152f * 1.12f, 0.0722f * 1.12f, 0f, -15.4f,
    0f, 0f, 0f, 1f, 0f,
)
private val Mono = ColorFilter.colorMatrix(ColorMatrix(MonoValues))

/**
 * A picture ready to paint into an outline: its shader, brush and placement made once for the
 * picture, so pressing or opening a folder allocates nothing per frame.
 */
private class PicturePaint(bitmap: ImageBitmap) {
    private val android = bitmap.asAndroidBitmap()
    private val shader = BitmapShader(android, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val brush = ShaderBrush(shader)
    private val matrix = Matrix()
    private var placedFor: Rect0? = null

    /** Cropped to fill [area] and painted straight into [path]: no clip, no layer. */
    fun draw(scope: DrawScope, path: Path, area: Rect0, alpha: Float, filter: ColorFilter? = null) {
        if (alpha <= 0.004f) return
        if (placedFor?.same(area) != true) {
            val scale = max(area.width / android.width, area.height / android.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(area.left + (area.width - android.width * scale) / 2f, area.top + (area.height - android.height * scale) / 2f)
            shader.setLocalMatrix(matrix)
            placedFor = area
        }
        scope.drawPath(path, brush, alpha = alpha, colorFilter = filter)
    }
}

@Composable
private fun rememberPicturePaint(bitmap: ImageBitmap?): PicturePaint? = remember(bitmap) { bitmap?.let(::PicturePaint) }

private fun outlinePath(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): Path =
    when (val outline = shape.createOutline(size, layoutDirection, density)) {
        is Outline.Generic -> outline.path
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    }

/** The folder's back: a rounded panel with a tab rising on its right. */
internal object FolderBackShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.width * 0.06f
        val tabTop = 0f
        val bodyTop = size.height * 0.12f
        val tabStart = size.width * 0.52f
        val path = Path().apply {
            moveTo(0f, bodyTop + r)
            quadraticTo(0f, bodyTop, r, bodyTop)
            lineTo(tabStart - r * 1.6f, bodyTop)
            cubicTo(tabStart - r * 0.6f, bodyTop, tabStart - r * 0.4f, tabTop, tabStart + r * 0.8f, tabTop)
            lineTo(size.width - r, tabTop)
            quadraticTo(size.width, tabTop, size.width, tabTop + r)
            lineTo(size.width, size.height - r)
            quadraticTo(size.width, size.height, size.width - r, size.height)
            lineTo(r, size.height)
            quadraticTo(0f, size.height, 0f, size.height - r)
            close()
        }
        return Outline.Generic(path)
    }
}

/** The folder's front: lower on the left, rising to the right in one soft diagonal. */
internal object FolderFrontShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.width * 0.06f
        val low = size.height * 0.16f
        val notch = size.width * 0.36f
        val path = Path().apply {
            moveTo(0f, low + r)
            quadraticTo(0f, low, r, low)
            lineTo(notch - r, low)
            cubicTo(notch + r * 0.2f, low, notch + r * 0.6f, 0f, notch + r * 2.2f, 0f)
            lineTo(size.width - r, 0f)
            quadraticTo(size.width, 0f, size.width, r)
            lineTo(size.width, size.height - r)
            quadraticTo(size.width, size.height, size.width - r, size.height)
            lineTo(r, size.height)
            quadraticTo(0f, size.height, 0f, size.height - r)
            close()
        }
        return Outline.Generic(path)
    }
}
