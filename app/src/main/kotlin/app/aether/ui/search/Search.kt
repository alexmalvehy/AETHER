@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.aether.ui.search

import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.art.TreatedArtwork
import app.aether.core.designsystem.art.edgeRefraction
import app.aether.core.designsystem.component.GlassPanel
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.Strapline
import app.aether.core.designsystem.theme.Space
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import app.aether.core.designsystem.component.IconAction
import app.aether.core.designsystem.component.Loading
import app.aether.core.designsystem.component.Notice
import app.aether.core.designsystem.component.SearchField
import app.aether.core.designsystem.component.SectionHeading
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.glass.LocalReduceMotion
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.Album
import app.aether.core.model.Artist
import app.aether.core.model.Artwork
import app.aether.core.model.Explore
import app.aether.core.model.FeedSection
import app.aether.core.model.Link
import app.aether.core.model.MediaEntity
import app.aether.core.model.MediaKind
import app.aether.core.model.Playlist
import app.aether.core.model.Podcast
import app.aether.core.model.SearchResults
import app.aether.core.model.SectionStyle
import app.aether.core.model.Suggestions
import app.aether.core.model.Track
import app.aether.core.model.valueOrNull
import app.aether.ui.LocalActions
import app.aether.ui.common.CardRow
import app.aether.ui.common.EntityCard
import app.aether.ui.common.Load
import app.aether.ui.common.LocalContainer
import app.aether.ui.common.SectionBlock
import app.aether.ui.common.TrackList
import app.aether.ui.common.rememberLoad
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import app.aether.core.designsystem.component.Glyph
import app.aether.ui.Route
import app.aether.ui.common.sections
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first
import app.aether.ui.common.Colophon
import app.aether.ui.common.rememberPageListState
import app.aether.ui.common.DockClearance
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp
import app.aether.core.designsystem.glass.backdrop.backdrops.layerBackdrop
import app.aether.core.designsystem.glass.backdrop.backdrops.rememberLayerBackdrop
import app.aether.core.designsystem.theme.Motion

/** How Discover's results come and go: fading, and gliding to their places, on springs. */
private val ItemFade = Motion.fluid<Float>(0.4f, 1f)
private val ItemPlace = Motion.fluid(0.5f, 0.9f, IntOffset.VisibilityThreshold)

/** Discover: everything the catalogue holds, behind one field of glass. */
@Composable
fun SearchScreen() {
    val container = LocalContainer.current
    val actions = LocalActions.current
    val source = container.sources.primary
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var suggestions by remember { mutableStateOf<Suggestions?>(null) }
    var suggesting by remember { mutableStateOf(false) }
    val recent by container.library.searches().collectAsStateWithLifecycle(emptyList())
    val (explore, retryExplore) = rememberLoad("explore") { source.explore() }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.isBlank() || query == submitted) { suggestions = null; return@LaunchedEffect }
        delay(220)
        suggesting = true
        suggestions = source.suggestions(query).valueOrNull()
        suggesting = false
    }

    fun submit(q: String) {
        if (q.isBlank()) return
        query = q
        submitted = q
        filter = null
        focus.clearFocus()
        scope.launch { container.library.searched(q) }
    }

    val idle = query.isEmpty() && submitted == null
    var focused by remember { mutableStateOf(false) }
    // The field is open while it's held or holds words not yet searched for. A touch anywhere else
    // closes it: what was typed goes, and any search it had replaced comes back. So does Back.
    val open = focused || (query.isNotEmpty() && query != submitted)
    val openNow by rememberUpdatedState(open)
    val emptyNow by rememberUpdatedState(query.isEmpty())
    fun closeField() {
        focus.clearFocus()
        query = submitted ?: ""
    }
    val roomsHidden = app.aether.ui.LocalRoomsHidden.current
    // Leaving the room (or Now Playing rising over it) lets the field go: the keyboard goes with it.
    val elsewhere by remember { derivedStateOf { roomsHidden() } }
    LaunchedEffect(elsewhere) { if (elsewhere) focus.clearFocus() }
    BackHandler(enabled = !elsewhere && (open || submitted != null)) {
        if (open) closeField() else { query = ""; submitted = null; filter = null }
    }
    val page = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val field = remember { arrayOfNulls<LayoutCoordinates>(1) }
    // The field rises to the top the moment it's touched, on a spring, and "Discover" recedes as it
    // comes; it settles back only when the field is empty and let go. (It used to jump the instant a
    // first letter was typed.)
    val rise by animateFloatAsState(if (idle && !focused) 0f else 1f, Motion.fluid(0.52f, 0.88f), label = "rise")
    val coversBackdrop = rememberLayerBackdrop()
    val covers = (explore as? Load.Ready<Explore>)?.value?.sections?.flatMap { s -> s.items.filter { it is Album || (it is Track && it.kind == MediaKind.SONG) } }?.map { it.artwork }.orEmpty()

    val list = rememberPageListState()
    val density = LocalDensity.current
    // How far the page has left its top, 0 to 1 over the carousel's own depth.
    val away = {
        if (list.firstVisibleItemIndex > 0) 1f
        else (list.firstVisibleItemScrollOffset / with(density) { 300.dp.toPx() }).coerceIn(0f, 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { page[0] = it }
            .pointerInput(Unit) {
                // Watched on the way down to the page's contents, which still get their touches.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!openNow) return@awaitEachGesture
                    val inField = runCatching {
                        val p = page[0]
                        val f = field[0]
                        p != null && f != null && p.localBoundingBoxOf(f, clipBounds = false).contains(down.position)
                    }.getOrDefault(false)
                    if (inField) return@awaitEachGesture
                    var travelled = 0f
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            // With nothing typed there's nothing here to choose yet: the touch only
                            // closes the field, and doesn't also open what happened to be under it. With
                            // words typed, a suggestion touched is still chosen.
                            if (emptyNow) change.consume()
                            closeField()
                            break
                        }
                        travelled += change.positionChange().getDistance()
                        // A drag belongs to the page.
                        if (travelled > viewConfiguration.touchSlop) break
                    }
                }
            },
    ) {
        val coversShown = idle && covers.isNotEmpty()
        AnimatedVisibility(coversShown, enter = fadeIn(Motion.fluid(0.6f, 1f)), exit = fadeOut(Motion.fluid(0.35f, 1f))) {
            DriftingCovers(covers, away, Modifier.layerBackdrop(coversBackdrop))
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = if (idle) 0.dp else DockClearance)) {
            item(key = "head") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .layout { measurable, constraints ->
                            // The head's own height eases with the rise, so what's beneath moves with it.
                            val top = lerp(150.dp, 72.dp, rise).roundToPx()
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height + top) { placeable.place(0, top) }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Txt(
                        "Discover",
                        Type.Monument,
                        align = TextAlign.Center,
                        modifier = Modifier.graphicsLayer {
                            val k = 1f - 0.16f * rise
                            scaleX = k
                            scaleY = k
                            alpha = 1f - 0.45f * rise
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        },
                    )
                    Spacer(Modifier.height(30.dp))
                    SearchField(
                        query = query,
                        onQuery = { query = it; if (it.isEmpty()) submitted = null },
                        onSubmit = ::submit,
                        loading = suggesting,
                        focusRequester = fieldFocus,
                        onFocus = { focused = it },
                        // Over the drifting covers, the field refracts them, as the tab bar refracts the page.
                        backdrop = if (coversShown) coversBackdrop else null,
                        modifier = Modifier.padding(horizontal = Space.edge).onPlaced { field[0] = it },
                    )
                }
            }
            val q = submitted
            when {
                q != null -> {
                    item(key = "results:$q:$filter") { Box(Modifier.animateItem(ItemFade, ItemPlace, ItemFade)) { Results(q, filter, onFilter = { filter = it }) } }
                    item(key = "sources:$q") { Box(Modifier.animateItem(ItemFade, ItemPlace, ItemFade)) { FromSources(q) } }
                }
                query.isNotEmpty() -> item(key = "suggest") { Box(Modifier.animateItem(ItemFade, ItemPlace, ItemFade)) { SuggestionList(suggestions, onQuery = ::submit) } }
                else -> {
                    if (recent.isNotEmpty()) item(key = "recent") { RecentSearches(recent, onQuery = ::submit, onForget = { scope.launch { container.library.forgetSearch(it) } }) }
                    when (explore) {
                        Load.Loading -> item(key = "loading") { Loading() }
                        is Load.Failed -> item(key = "failed") { Notice(explore.title, explore.body, action = "Try again", onAction = retryExplore) }
                        is Load.Ready -> {
                            if (explore.value.links.isNotEmpty()) sections(listOf(FeedSection("Ways in", explore.value.links, strapline = "Explore", style = SectionStyle.GRID)), topSpace = 56.dp)
                            // A wall of fifty tiles is a list, not a page: the moods here, everything behind See all.
                            sections(explore.value.sections, tileLimit = 12, onAllTiles = { actions.go(Route.Moods) })
                            item(key = "colophon") { Colophon() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The background while the field is empty (the glass carousel recording): new covers drift past on
 * the inside of a curve, bending light as they reach the edges. Untouchable, dimmed under the
 * heading, so it never competes with the words. It belongs to the top of the page: as the page
 * scrolls ([away] 0 to 1) it rises a little slower than the page, goes out of focus and sinks into
 * the black under the status bar's frost, and once gone it stops drifting.
 */
@Composable
private fun DriftingCovers(covers: List<Artwork>, away: () -> Float, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val roomsHidden = app.aether.ui.LocalRoomsHidden.current
    // As the mood ribbon does: between the list's own occasional steps it slides as one picture, by
    // fractions of a pixel, which the list alone can't (it can only stand on whole ones).
    val slide = remember { mutableFloatStateOf(0f) }
    val slack = with(density) { 96.dp.toPx() }
    if (!reduce) LaunchedEffect(covers.size) {
        // The covers drift for as long as they can be seen; scrolled away, or with the room out of
        // sight, they ask for no frames at all.
        while (true) {
            snapshotFlow { away() < 1f && !roomsHidden() }.first { it }
            var pace = 0f
            var then = withFrameNanos { it }
            while (away() < 1f && !roomsHidden()) {
                val now = withFrameNanos { it }
                val dt = ((now - then) / 1e9f).coerceIn(0f, 0.05f)
                then = now
                pace += (1f - pace) * (1f - kotlin.math.exp(-dt / 0.6f))
                slide.floatValue += 0.55f * 60f * dt * pace
                if (slide.floatValue >= slack) {
                    val whole = slide.floatValue.toInt()
                    state.dispatchRawDelta(whole.toFloat())
                    slide.floatValue -= whole
                }
            }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(430.dp)
            .graphicsLayer {
                val a = away()
                alpha = 1f - a
                translationY = -a * with(density) { 120.dp.toPx() }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && a > 0.01f) {
                    val r = a * with(density) { 26.dp.toPx() }
                    renderEffect = BlurEffect(r, r, TileMode.Decal)
                }
            },
    ) {
        LazyRow(
            state = state,
            userScrollEnabled = false,
            modifier = Modifier.fillMaxWidth().padding(top = 70.dp).height(300.dp)
                .edgeRefraction(edgeFraction = 0.3f, pull = 0.7f, liftFraction = 0.32f, chromaDp = 6f, smearDp = 10f)
                .layout { measurable, constraints ->
                    val wide = constraints.maxWidth + slack.toInt() + 1
                    val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
                    layout(constraints.maxWidth, placeable.height) {
                        placeable.placeWithLayer(0, 0) { translationX = -slide.floatValue }
                    }
                },
            contentPadding = PaddingValues(vertical = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(Int.MAX_VALUE / 2) { i ->
                val art = covers[i % covers.size]
                TreatedArtwork(art, ArtTreatment.Cover, Modifier.width(150.dp).aspectRatio(1f).clip(RoundedCornerShape(3.dp)), sizeHintPx = 300)
            }
        }
        // The covers are dimmed here too, by the same black, and not by a layer of their own at 55%: a
        // whole carousel's worth less to draw on every step of its drift, for the same picture.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black, 0.3f to Color.Black.copy(alpha = 0.34f), 0.72f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black)))
    }
}

/** Recent searches as glass capsules: touch one to search again; hold one to forget it. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecentSearches(recent: List<String>, onQuery: (String) -> Unit, onForget: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp, start = Space.edge, end = Space.edge), horizontalAlignment = Alignment.CenterHorizontally) {
        Strapline("Recent")
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            recent.forEach { q -> GlassPill(q, trailingIcon = Glyph.Close, onClick = { onQuery(q) }, modifier = Modifier.pointerInput(q) { detectTapGestures(onLongPress = { onForget(q) }) }) }
        }
    }
}

@Composable
private fun SuggestionList(suggestions: Suggestions?, onQuery: (String) -> Unit) {
    val actions = LocalActions.current
    if (suggestions == null || (suggestions.queries.isEmpty() && suggestions.items.isEmpty())) return
    GlassPanel(Modifier.padding(top = 20.dp, start = Space.edge, end = Space.edge)) {
        suggestions.queries.forEach { s ->
            Row(Modifier.fillMaxWidth().quietClick { onQuery(s) }.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Txt(s, Type.TitleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                app.aether.core.designsystem.component.Icon(Glyph.Forward, Modifier.graphicsLayer { rotationZ = -45f }, size = 11.dp, color = Ink.Dim)
            }
        }
        suggestions.items.takeIf { it.isNotEmpty() }?.let { items ->
            Spacer(Modifier.height(16.dp))
            // Songs first here too, as in the results; otherwise in the catalogue's order.
            items.sortedBy { if (it is Track && it.kind == MediaKind.SONG) 0 else 1 }.forEach { item ->
                when (item) {
                    is Track -> app.aether.core.designsystem.component.TrackRow(item, onClick = { actions.play(listOf(item)) }, onLongClick = { actions.openMenu(item) })
                    else -> EntityLine(item, onClick = { actions.open(item) })
                }
            }
        }
    }
}

/** An album, artist or playlist as one line: a small picture, its name, and what it is. */
@Composable
private fun EntityLine(entity: MediaEntity, onClick: () -> Unit) {
    val (kind, round) = when (entity) {
        is Artist -> "Artist" to true
        is Album -> (entity.type ?: "Album") to false
        is Playlist -> "Playlist" to false
        is Podcast -> "Podcast" to false
        is Link -> "Browse" to false
        is Track -> "Song" to false
    }
    Row(Modifier.fillMaxWidth().quietClick(onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TreatedArtwork(
            entity.artwork,
            if (entity is Artist) ArtTreatment.Print else ArtTreatment.Cover,
            Modifier.width(46.dp).aspectRatio(1f).clip(if (round) RoundedCornerShape(50) else RoundedCornerShape(3.dp)),
            sizeHintPx = 160,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(entity.title, Type.TitleSmall, maxLines = 1)
            Txt(listOfNotNull(kind, entity.subtitle?.takeIf { !it.startsWith(kind) }).joinToString("  ·  "), Type.Meta, color = Ink.Grey, maxLines = 1)
        }
    }
}

/**
 * What a search found: the catalogue's results, and its songs alone, asked for at the same moment
 * because the mixed results hold only a few songs, and songs come first.
 */
private data class Found(val results: SearchResults, val songs: List<Track>, val moreSongs: app.aether.core.model.PageToken?)

/** Songs shown before "More songs": enough to choose from, few enough that what follows is near (PODIUM, D-80). */
private const val SongsAtFirst = 5

/**
 * Results under the catalogue's own filters: songs first, always (as in PODIUM and BitChord), then
 * artists, albums, videos, playlists and podcasts. The catalogue's top result leads its own kind
 * rather than standing above the songs.
 */
@Composable
private fun Results(query: String, filter: String?, onFilter: (String?) -> Unit) {
    val container = LocalContainer.current
    val source = container.sources.primary
    var filters by remember(query) { mutableStateOf(emptyList<app.aether.core.model.SearchFilter>()) }
    val chosen = filters.firstOrNull { it.label == filter }
    val (load, retry) = rememberLoad("search:$query:${chosen?.key}") {
        if (chosen != null) {
            when (val r = source.search(query, chosen)) {
                is app.aether.core.model.Outcome.Ok -> app.aether.core.model.Outcome.Ok(Found(r.value, emptyList(), null))
                is app.aether.core.model.Outcome.Err -> r
            }
        } else {
            kotlinx.coroutines.coroutineScope {
                val typed = async { source.searchSongs(query) }
                val mixed = source.search(query, null)
                val songs = (typed.await() as? app.aether.core.model.Outcome.Ok)?.value
                when (mixed) {
                    is app.aether.core.model.Outcome.Ok -> app.aether.core.model.Outcome.Ok(Found(mixed.value, songs?.items.orEmpty(), songs?.next))
                    // The songs alone still answer a search whose mixed half didn't.
                    is app.aether.core.model.Outcome.Err ->
                        if (songs != null && songs.items.isNotEmpty()) app.aether.core.model.Outcome.Ok(Found(SearchResults(emptyList(), emptyList(), null), songs.items, songs.next))
                        else mixed
                }
            }
        }
    }
    LaunchedEffect(load) { if (load is Load.Ready<Found> && filters.isEmpty()) filters = load.value.results.filters }

    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        if (filters.isNotEmpty()) {
            val chipState = rememberLazyListState()
            LazyRow(
                state = chipState,
                modifier = Modifier.fillMaxWidth().edgeRefraction(chipState, edgeFraction = 0.14f, liftFraction = 0.08f),
                contentPadding = PaddingValues(horizontal = Space.edge, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filters.distinctBy { it.label }, key = { it.label }) { f ->
                    val on = f.label == filter
                    GlassPill(f.label, onClick = { onFilter(if (on) null else f.label) }, lit = on, small = true)
                }
            }
        }
        when (load) {
            Load.Loading -> Loading()
            is Load.Failed -> Notice(load.title, load.body, action = "Try again", onAction = retry)
            is Load.Ready -> {
                val found = load.value
                val items = found.results.items
                // The mixed results' songs are the catalogue's best guesses and lead; the songs asked
                // for alone fill in after them, and fill in what the mixed results left out.
                val listed = remember(found) { listedSongs(items, found.songs) }
                var opened by remember(found) { mutableStateOf(chosen != null) }
                var further by remember(found) { mutableStateOf(emptyList<Track>()) }
                var next by remember(found) { mutableStateOf(found.moreSongs) }
                var asking by remember(found) { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                if (items.isEmpty() && listed.isEmpty()) {
                    Notice("Nothing by that name", "Try fewer words, or another spelling.")
                } else {
                    val songs = (listed + further).distinctBy { it.id }
                    val videos = items.filterIsInstance<Track>().filter { it.kind == MediaKind.MUSIC_VIDEO || it.kind == MediaKind.VIDEO }
                    val episodes = items.filterIsInstance<Track>().filter { it.kind == MediaKind.EPISODE }
                    val artists = items.filterIsInstance<Artist>()
                    val albums = items.filterIsInstance<Album>()
                    val playlists = items.filterIsInstance<Playlist>()
                    val podcasts = items.filterIsInstance<Podcast>()
                    // A song chosen from the results brings its own radio: what follows is what's like
                    // it, never the other songs that happen to share its name.
                    if (songs.isNotEmpty()) {
                        Group("Songs")
                        TrackList(if (opened) songs else songs.take(SongsAtFirst), alone = true)
                        // First the songs already found, then the catalogue's further pages of them.
                        val more = !opened && songs.size > SongsAtFirst || opened && next != null
                        if (more) {
                            Spacer(Modifier.height(14.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                GlassPill(if (asking) "Finding more" else "More songs", small = true, onClick = {
                                    if (!opened) {
                                        opened = true
                                    } else if (!asking) {
                                        val token = next ?: return@GlassPill
                                        asking = true
                                        scope.launch {
                                            (source.searchSongs(query, token) as? app.aether.core.model.Outcome.Ok)?.value?.let { page ->
                                                further = further + page.items.filter { it.kind == MediaKind.SONG }
                                                next = page.next
                                            }
                                            asking = false
                                        }
                                    }
                                })
                            }
                        }
                    }
                    if (artists.isNotEmpty()) { Group("Artists"); CardRow(artists) }
                    if (albums.isNotEmpty()) { Group("Albums"); CardRow(albums) }
                    if (videos.isNotEmpty()) { Group("Videos"); CardRow(videos, wide = true) }
                    if (playlists.isNotEmpty()) { Group("Playlists"); CardRow(playlists) }
                    if (podcasts.isNotEmpty() || episodes.isNotEmpty()) { Group("Podcasts"); CardRow(podcasts + episodes) }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(36.dp))
    SectionHeading(title)
    Spacer(Modifier.height(12.dp))
}

/** Songs from the sources the listener added, under the catalogue's own results. */
@Composable
private fun FromSources(query: String) {
    val container = LocalContainer.current
    val added by container.sources.added.collectAsStateWithLifecycle()
    val others = added.filter { it.id != container.sources.primary.id && it.id != "local" && app.aether.core.model.Capability.SEARCH in it.capabilities }
    if (others.isEmpty()) return
    val found by produceState(emptyList<Track>(), query, others) {
        value = others.flatMap { s -> (s.search(query) as? app.aether.core.model.Outcome.Ok)?.value?.items?.filterIsInstance<Track>().orEmpty() }
    }
    if (found.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 44.dp)) {
        SectionHeading("From your sources")
        Spacer(Modifier.height(16.dp))
        TrackList(found)
    }
}
