package app.aether.ui.detail

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.Picture
import app.aether.core.designsystem.art.Pictures
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.AlbumArt
import app.aether.core.model.AlbumPage
import app.aether.ui.LocalRoomsHidden
import app.aether.ui.common.LocalContainer
import app.aether.ui.pageLanded
import app.aether.ui.placedWhile

/**
 * The head of an album's page, made as an artist's is: the cover the full width of the screen, printed
 * in black and white, its film coming up over it from its first frame for the albums that have one.
 * The colour is for the cards that show the album. Albums aren't lettered, their makers are: where the one who made it has their own lettering, it stands in
 * for their name, in white. The cover is all there, square; the page's black starts where it ends.
 */
@Composable
fun AlbumHero(page: AlbumPage, onArtist: (() -> Unit)?) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val consent by container.settings.artistArt.collectAsStateWithLifecycle()
    val saver by container.settings.efficient.collectAsStateWithLifecycle()
    val album = page.album
    val id = album.id.value
    val artist = album.artists.firstOrNull()
    val art by produceState<AlbumArt?>(container.albumArt.cachedAlbum(id).takeIf { consent }, id, consent) {
        if (!consent || artist == null) {
            value = null
            return@produceState
        }
        value = container.albumArt.findAlbum(id, album.title, artist.name, artist.id?.value)
    }
    // One person's lettering can't stand for several names.
    val logo = art?.logo?.takeIf { album.artists.size == 1 }
    // No film on the data saver.
    val film = art?.film?.takeIf { !saver }

    val hidden = LocalRoomsHidden.current
    val elsewhere by remember { derivedStateOf { hidden() } }
    val landed = pageLanded()

    val filmUp = remember { Animatable(0f, visibilityThreshold = 0.002f) }
    val logoUp = remember { Animatable(0f, visibilityThreshold = 0.002f) }
    var filmFramed by remember { mutableStateOf(false) }
    LaunchedEffect(logo) {
        if (logo == null) return@LaunchedEffect
        Pictures.fetch(context, logo, LogoPx)
        logoUp.animateTo(1f, Rising)
    }
    LaunchedEffect(filmFramed) { if (filmFramed) filmUp.animateTo(1f, Rising) }
    val filmCovers by remember { derivedStateOf { filmUp.value >= 0.999f } }

    Box(Modifier.fillMaxWidth().aspectRatio(HeroAspect).clipToBounds()) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Box(Modifier.fillMaxSize().placedWhile { !filmCovers }) {
                TreatedArtwork(album.artwork, FilmPrint, Modifier.fillMaxSize(), sizeHintPx = CoverPx)
            }
            // Started once the page has arrived, so that making a player never touches its slide.
            if (film != null && landed) {
                HeroFilm(
                    url = film,
                    playing = !elsewhere,
                    onFirstFrame = { filmFramed = true },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = filmUp.value },
                )
            }
            Box(Modifier.fillMaxSize().background(CoverFade))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // An album's name is already set smaller than a room's: over several lines it gives up less.
            app.aether.core.designsystem.component.FittedName(
                album.title,
                Type.name(album.title, Type.Headline),
                maxLines = 3,
                oneLineLeast = 0.8f,
                severalLinesMost = 0.86f,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            val names = album.artists.joinToString(", ") { it.name }
            if (names.isNotEmpty()) {
                // The same height with or without lettering, so nothing above it moves when it arrives.
                Box(
                    Modifier.height(LetteringSlot).then(if (onArtist != null) Modifier.quietClick(onArtist) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Txt(
                        names,
                        Type.Body,
                        color = Color.White.copy(alpha = 0.82f),
                        align = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 28.dp).graphicsLayer { alpha = 1f - logoUp.value },
                    )
                    if (logo != null) {
                        Picture(
                            logo,
                            LogoPx,
                            Modifier
                                .widthIn(max = LocalConfiguration.current.screenWidthDp.dp * 0.5f)
                                .heightIn(max = LetteringTallest)
                                .aspectRatio(art?.logoAspect ?: 1f)
                                .graphicsLayer { alpha = logoUp.value },
                            colorFilter = ColorFilter.tint(Color.White),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
            }
            val details = listOfNotNull(album.subtitle, page.details).joinToString("   ")
            if (details.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Strapline(details, Modifier.padding(horizontal = 20.dp), color = Color.White.copy(alpha = 0.78f))
            }
        }
    }
}

/** Sharp at the full width of any phone. */
private const val CoverPx = 1440

/** Where the maker's name stands, under the album's. */
private val LetteringSlot = 46.dp
private val LetteringTallest = 40.dp

/** Dark enough at the top for the back key, and falling away to the page's black under the words. */
private val CoverFade = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.4f),
    0.22f to Color.Transparent,
    0.44f to Color.Transparent,
    0.64f to Color.Black.copy(alpha = 0.55f),
    0.82f to Color.Black.copy(alpha = 0.86f),
    1f to Color.Black,
)
