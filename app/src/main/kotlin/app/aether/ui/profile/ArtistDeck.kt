package app.aether.ui.profile

import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.Stable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.LocalHaptics
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.theme.Corners
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Motion
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Artwork
import app.aether.core.model.Outcome
import app.aether.core.model.Artist
import app.aether.core.model.SearchResults
import app.aether.core.model.withoutEmoji
import app.aether.Container
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How an artist looks: a picture, and their own lettering where they have one. */
class Face(val id: app.aether.core.model.MediaId?, val picture: Artwork, val logo: String?, val logoAspect: Float)

/**
 * Finds artists' pictures. A play knows an artist's name and not always their page, so a name with
 * no page is looked up. A song's cover stands in for nobody: failing all else, the card has no picture.
 */
class Faces(private val container: Container) {
    private val known = HashMap<String, Face>()

    fun cached(a: ArtistStat): Face? = synchronized(known) { known[a.name.lowercase()] }

    suspend fun of(a: ArtistStat): Face {
        cached(a)?.let { return it }
        val face = try {
            look(a)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: Face(a.id, Artwork.None, null, 1f)
        if (!face.picture.isEmpty) synchronized(known) { known[a.name.lowercase()] = face }
        return face
    }

    private suspend fun look(a: ArtistStat): Face? {
        val primary = container.sources.primary
        val id = a.id ?: (primary.search(a.name, null) as? Outcome.Ok<SearchResults>)?.value?.items
            ?.filterIsInstance<Artist>()?.firstOrNull { it.title.equals(a.name, ignoreCase = true) }?.id
            ?: return null
        val source = container.sources.of(id) ?: return null
        val page = (source.artist(id) as? Outcome.Ok)?.value ?: return null
        val art = if (container.settings.artistArt.value) container.artistArt.find(id.value, page.artist.name, page.topTracks.map { it.title }) else null
        return Face(id, art?.picture ?: page.artist.artwork, art?.logo, art?.logoAspect ?: 1f)
    }
}

private const val Peek = 0.05f

/** How many stand in the stack; the rest wait behind the last of them. */
private const val Deepest = 2f

private val Thrown = Motion.fluid(0.3f, 1f, 2f)
private val Back = Motion.fluid<Float>(0.46f, 0.72f)
private val Tuck = Motion.fluid<Float>(0.5f, 0.92f)
private val Step = Motion.fluid<Float>(0.46f, 0.86f)

@Stable
private class DeckCard(place: Int) {
    val x = Animatable(0f)
    val depth = Animatable(place.toFloat())

    /** On its way round to the back. */
    var out by mutableStateOf(false)

    /** Still in front of the others, on its way out. */
    var leaving by mutableStateOf(false)
}

/**
 * The listener's most played artists as a stack of cards, one to a person: a big picture, the name
 * across its foot, and what they've meant lately. A card thrown either way goes out to that side and
 * comes round to the back of the stack, and the one behind it comes up; touched, it opens the artist.
 */
@Composable
fun ArtistDeck(artists: List<ArtistStat>, faces: Faces, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val actions = LocalActions.current
    val count = artists.size
    // Front to back.
    val order = remember(count) { mutableStateListOf<Int>().apply { addAll(0 until count) } }
    val cards = remember(count) { List(count) { DeckCard(it) } }
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = Space.edge)) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        val tracker = remember { VelocityTracker() }

        fun throwFront(side: Float, velocity: Float) {
            val index = order.first()
            val card = cards[index]
            // The ones behind have come forward with the finger: they carry on from exactly there.
            val carried = (abs(card.x.value) / width).coerceIn(0f, 1f)
            haptics.tick()
            card.leaving = true
            card.out = true
            order.removeAt(0)
            order.add(index)
            order.forEachIndexed { i, c ->
                if (c == index) return@forEachIndexed
                scope.launch {
                    cards[c].depth.snapTo((cards[c].depth.value - carried).coerceAtLeast(0f))
                    cards[c].depth.animateTo(i.toFloat(), Step)
                }
            }
            scope.launch {
                card.x.animateTo(side * width * 1.12f, Thrown, initialVelocity = velocity)
                // Clear of the stack: it goes behind everything, and slides in under them.
                card.leaving = false
                card.depth.snapTo((count - 1).toFloat())
                card.x.animateTo(0f, Tuck)
                card.out = false
            }
        }

        Box(Modifier.fillMaxWidth().aspectRatio(0.8f)) {
            val front = order.take(3)
            (front + cards.indices.filter { cards[it].out && it !in front }).forEach { index ->
                val card = cards[index]
                key(index) {
                    Box(
                        Modifier
                            .zIndex(if (card.leaving) 1f else -order.indexOf(index).toFloat())
                            .fillMaxSize()
                            .graphicsLayer {
                                // Everything behind the front card comes forward as it's pulled away.
                                val top = cards[order.first()]
                                val carried = if (card === top || card.leaving) 0f else (abs(top.x.value) / width).coerceIn(0f, 1f)
                                val place = (card.depth.value - carried).coerceIn(0f, Deepest)
                                val s = 1f - Peek * place
                                val pivot = size.height * 1.2f
                                scaleX = s
                                scaleY = s
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1.2f)
                                // Scaled about a point below it, so its top edge is put back where the stack wants it.
                                translationY = -place * 16.dp.toPx() - pivot * (1f - s)
                                translationX = card.x.value
                                rotationZ = card.x.value / width * 9f
                            },
                    ) { ArtistFace(artists[index], index, count, faces) }
                }
            }
            // One surface over the stack, so a card can be taken the moment the last has gone.
            Box(
                Modifier
                    .zIndex(2f)
                    .matchParentSize()
                    .pointerInput(count) {
                        var held = 0
                        detectHorizontalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                held = order.first()
                            },
                            onDragEnd = {
                                val x = cards[held].x
                                val v = tracker.calculateVelocity().x
                                val fast = abs(v) > 1100f
                                if (count > 1 && order.first() == held && (fast || abs(x.value) > width * 0.26f)) {
                                    throwFront(if (if (fast) v > 0f else x.value > 0f) 1f else -1f, v)
                                } else scope.launch { x.animateTo(0f, Back, initialVelocity = v) }
                            },
                            onDragCancel = { scope.launch { cards[held].x.animateTo(0f, Back) } },
                        ) { change, dx ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val x = cards[held].x
                            scope.launch { x.snapTo(x.value + dx) }
                        }
                    }
                    .pointerInput(count) {
                        detectTapGestures {
                            haptics.select()
                            val a = artists[order.first()]
                            (faces.cached(a)?.id ?: a.id)?.let { actions.nav.open(Route.Artist(it)) }
                        }
                    },
            )
        }
    }
}

@Composable
private fun ArtistFace(a: ArtistStat, rank: Int, of: Int, faces: Faces) {
    val face by produceState(faces.cached(a), a.name) { value = faces.of(a) }
    Box(Modifier.fillMaxSize().clip(Corners.Large).background(Ink.Coal)) {
        val picture = face?.picture ?: Artwork.None
        if (!picture.isEmpty) TreatedArtwork(picture, ArtTreatment.Monochrome(contrast = 1.14f, grain = 0f), Modifier.fillMaxSize(), sizeHintPx = 1080)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.34f), 0.2f to Color.Transparent, 0.52f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.94f))))
        Strapline("Nº ${rank + 1} of $of", Modifier.align(Alignment.TopStart).padding(18.dp), color = Color.White)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            val logo = face?.logo
            if (logo != null) {
                Picture(
                    logo,
                    1000,
                    Modifier.widthIn(max = 220.dp).heightIn(max = 76.dp).aspectRatio(face?.logoAspect ?: 1f),
                    colorFilter = ColorFilter.tint(Color.White),
                    contentScale = ContentScale.Fit,
                )
            } else {
                app.aether.core.designsystem.component.FittedName(a.name, Type.name(a.name, Type.Headline.copy(fontSize = Type.Headline.fontSize * 1.2f)), maxLines = 2, align = TextAlign.Start)
            }
            Spacer(Modifier.height(10.dp))
            Strapline("${a.plays} ${if (a.plays == 1) "play" else "plays"}  ·  ${listening(a.minutes.toLong())}", color = Color.White)
            Spacer(Modifier.height(6.dp))
            Txt("On repeat: ${a.topSong.title.withoutEmoji()}", Type.Aside, color = Color.White.copy(alpha = 0.82f), maxLines = 1)
        }
    }
}

/** "48 min", "6 hr". */
internal fun listening(minutes: Long): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 600 -> "${minutes / 60} hr ${minutes % 60} min"
    else -> "${minutes / 60} hr"
}
