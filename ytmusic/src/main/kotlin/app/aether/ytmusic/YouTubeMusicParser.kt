package app.aether.ytmusic

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns the catalogue's answers into [Item]s and pages. Ported from PODIUM's parser (written against
 * the music web client's answers as of 2026-10) and extended to every guest surface verified live on
 * 2026-11-03: Home's chips and background, Explore, Charts and their countries, Moods & genres,
 * new releases, podcasts, artists' audience figures (docs/ytmusic-verification.md).
 *
 * Tolerant by design: items are recognised by what they link to and how they play rather than by
 * where they sit, both the older and the newer page layouts are read, and anything unrecognisable is
 * skipped, never guessed. English labels ("Single", "EP") are only ever a fallback.
 */
internal object YouTubeMusicParser {

    /** What a list holds when its rows don't say (a typed search, an album's tracks). */
    enum class Hint { NONE, SONG, VIDEO, ALBUM, ARTIST, PLAYLIST, EPISODE }

    // --- Single items --------------------------------------------------------------------------------

    /** Any list, grid or shelf entry, whatever its renderer. */
    fun item(entry: JsonElement, hint: Hint = Hint.NONE): Item? {
        val (name, r) = entry.renderer() ?: return null
        return when (name) {
            "musicResponsiveListItemRenderer" -> responsiveItem(r, hint)
            "musicTwoRowItemRenderer" -> twoRowItem(r)
            "musicMultiRowListItemRenderer" -> multiRowItem(r)
            "musicNavigationButtonRenderer" -> link(r)
            "playlistPanelVideoRenderer" -> panelItem(r)
            "playlistPanelVideoWrapperRenderer" -> r.at("primaryRenderer")?.let { item(it, hint) }
            else -> null
        }
    }

    fun responsiveItem(r: JsonObject, hint: Hint = Hint.NONE): Item? {
        val columns = r.at("flexColumns").arr.map { it.at("musicResponsiveListItemFlexColumnRenderer", "text") }
        val titleRuns = columns.getOrNull(0).runs()
        val title = titleRuns.joinToString("") { it.runText() }.trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = columns.drop(1).filter { it.runs().isNotEmpty() }
            .flatMapIndexed { i, c -> (if (i > 0) listOf(SEPARATOR_RUN) else emptyList()) + c.runs() }
        val fixed = r.at("fixedColumns").arr.mapNotNull { it.at("musicResponsiveListItemFixedColumnRenderer", "text").text() }
        val thumbs = thumbnails(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val play = r.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
        val videoId = r.at("playlistItemData", "videoId").str
            ?: play.at("videoId").str
            ?: titleRuns.firstNotNullOfOrNull { it.at("navigationEndpoint", "watchEndpoint", "videoId").str }
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val explicit = isExplicit(r)
        val browse = r.at("navigationEndpoint", "browseEndpoint") ?: titleRuns.firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint") }
        val page = browse?.let(::pageTypeOf)
        // A row that opens a page (an album, a playlist, a show) can still carry a play button: it's the page, not a song.
        if (videoId != null && (browse == null || page == NON_MUSIC_TRACK_PAGE || page == EPISODE_PAGE)) {
            val kind = kindOf(r.findFirst("musicVideoType").str, label, hint)
            val durationText = fixed.firstOrNull { DURATION.matches(it.trim()) } ?: detail.firstOrNull { DURATION.matches(it.runText().trim()) }?.runText()
            val (artists, album) = songPages(r, detail, label)
            return Song(
                videoId = videoId,
                title = title,
                artists = artists,
                album = album,
                durationMs = durationText?.let(::parseDuration),
                thumbnails = thumbs,
                explicit = explicit,
                kind = kind,
                subtitle = line(detail),
                plays = playsIn(detail),
                playlistId = play.at("playlistId").str,
                playParams = play.at("params").str,
                setVideoId = r.at("playlistItemData", "playlistSetVideoId").str,
                playable = r["musicItemRendererDisplayPolicy"].str != GREYED_OUT,
                trackNumber = r.at("index").text()?.trim()?.toIntOrNull(),
            )
        }
        val browseId = browse.at("browseId").str ?: return null
        return pageItem(browseId, page, title, detail, label, thumbs, explicit, hint)
    }

    fun twoRowItem(r: JsonObject): Item? {
        val titleRuns = r.at("title").runs()
        val title = titleRuns.joinToString("") { it.runText() }.trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = r.at("subtitle").runs()
        val thumbs = thumbnails(r.at("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val endpoint = r.at("navigationEndpoint") ?: titleRuns.firstOrNull()?.at("navigationEndpoint")
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val explicit = isExplicit(r)
        val browse = endpoint.at("browseEndpoint")
        val page = browse?.let(::pageTypeOf)
        // A video or episode can open its own page (MPED…) yet play from its thumbnail's button.
        val watch = endpoint.at("watchEndpoint")
            ?: if (page == NON_MUSIC_TRACK_PAGE || page == EPISODE_PAGE) {
                r.at("thumbnailOverlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
            } else {
                null
            }
        watch.at("videoId").str?.let { videoId ->
            val (artists, album) = songPages(r, detail, label)
            return Song(
                videoId = videoId,
                title = title,
                artists = artists,
                album = album,
                thumbnails = thumbs,
                explicit = explicit,
                kind = kindOf(watch.findFirst("musicVideoType").str, label, if (page == NON_MUSIC_TRACK_PAGE) Hint.VIDEO else Hint.NONE),
                subtitle = line(detail),
                plays = playsIn(detail),
                playlistId = watch.at("playlistId").str,
                playParams = watch.at("params").str,
            )
        }
        val browseId = browse.at("browseId").str ?: return null
        return pageItem(browseId, page, title, detail, label, thumbs, explicit, Hint.NONE)
    }

    /** A podcast episode's row: title, show, description, and a play button. */
    fun multiRowItem(r: JsonObject): Song? {
        val watch = r.at("onTap", "watchEndpoint")
            ?: r.at("overlay", "musicItemThumbnailOverlayRenderer", "content", "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
        val videoId = watch.at("videoId").str ?: return null
        val title = r.at("title").text()?.trim() ?: return null
        val detail = r.at("subtitle").runs()
        val show = r.at("secondTitle").runs().firstOrNull()
        val duration = r.at("playbackProgress", "musicPlaybackProgressRenderer", "durationText").runs()
            .map { it.runText().trim() }.firstOrNull { it.isNotEmpty() && it != SEPARATOR.trim() }
        return Song(
            videoId = videoId,
            title = title,
            artists = listOfNotNull(show?.let { Ref(it.runText().trim(), it.at("navigationEndpoint", "browseEndpoint", "browseId").str) }),
            thumbnails = thumbnails(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")),
            kind = MediaKind.EPISODE,
            subtitle = listOfNotNull(line(detail), duration).joinToString(SEPARATOR).takeIf { it.isNotBlank() },
            plays = playsIn(detail),
        )
    }

    /** A tile: a mood or genre, or one of Explore's entries. */
    fun link(r: JsonObject): Link? {
        val title = r.at("buttonText").text()?.trim() ?: return null
        val browse = r.at("clickCommand", "browseEndpoint") ?: return null
        val browseId = browse.at("browseId").str ?: return null
        return Link(
            title = title,
            destination = Destination(browseId, browse.at("params").str),
            color = r.at("solid", "leftStripeColor").long,
            icon = r.at("iconStyle", "icon", "iconType").str,
        )
    }

    /** A row of the watch-next list (a radio, a queue). */
    fun panelItem(r: JsonObject): Song? {
        val videoId = r["videoId"].str ?: r.at("navigationEndpoint", "watchEndpoint", "videoId").str ?: return null
        val title = r.at("title").text()?.trim() ?: return null
        val byline = r.at("longBylineText").runs().ifEmpty { r.at("shortBylineText").runs() }
        val (artists, album) = songPages(r, byline, null)
        return Song(
            videoId = videoId,
            title = title,
            artists = artists,
            album = album,
            durationMs = r.at("lengthText").text()?.let(::parseDuration),
            thumbnails = thumbnails(r.at("thumbnail", "thumbnails")),
            explicit = isExplicit(r),
            kind = kindOf(r.findFirst("musicVideoType").str, null, Hint.NONE),
            subtitle = line(byline),
            playlistId = r.at("navigationEndpoint", "watchEndpoint", "playlistId").str,
            year = byline.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() },
        )
    }

    private fun pageItem(
        browseId: String,
        page: String?,
        title: String,
        detail: List<JsonObject>,
        label: String?,
        thumbs: List<Thumbnail>,
        explicit: Boolean,
        hint: Hint,
    ): Item? {
        val kind = when {
            page == ALBUM_PAGE -> Hint.ALBUM
            page == ARTIST_PAGE || page == LIBRARY_ARTIST_PAGE -> Hint.ARTIST
            page == PLAYLIST_PAGE -> Hint.PLAYLIST
            page == PODCAST_PAGE -> return Podcast(browseId, title, plainParts(detail, label).firstOrNull(), thumbs, line(detail))
            page != null -> return null // profiles, episodes' own pages: nothing to browse here
            browseId.startsWith("MPRE") -> Hint.ALBUM
            browseId.startsWith("UC") || browseId.startsWith("MPLAUC") -> Hint.ARTIST
            browseId.startsWith("VL") -> Hint.PLAYLIST
            else -> hint
        }
        return when (kind) {
            Hint.ALBUM -> Album(
                browseId = browseId,
                title = title,
                artists = artistsIn(detail, label),
                year = detail.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() },
                thumbnails = thumbs,
                explicit = explicit,
                type = label?.takeIf { it in ALBUM_LABELS },
                subtitle = line(detail),
            )
            Hint.ARTIST -> Artist(browseId.removePrefix("MPLA"), title, thumbs, line(detail))
            Hint.PLAYLIST -> Playlist(
                playlistId = browseId.removePrefix("VL"),
                title = title,
                author = plainParts(detail, label).firstOrNull { COUNT.find(it) == null && !it.endsWith(" views") },
                trackCount = detail.firstNotNullOfOrNull { run -> COUNT.find(run.runText())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() },
                thumbnails = thumbs,
                subtitle = line(detail),
            )
            else -> null
        }
    }

    // --- Shelves -------------------------------------------------------------------------------------------

    /** A page's blocks as shelves, in order; blocks that hold nothing the app can show are left out. */
    fun shelves(blocks: List<JsonElement>): List<Shelf> = blocks.mapNotNull(::shelf).filter { it.items.isNotEmpty() }

    fun shelf(block: JsonElement): Shelf? {
        val (name, r) = block.renderer() ?: return null
        val header: JsonElement?
        val title: String
        val entries: List<JsonElement>
        val more: Destination?
        val style: ShelfStyle
        when (name) {
            "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer" -> {
                header = r.at("header").renderer()?.second
                title = header.at("title").text() ?: return null
                entries = r.at("contents").arr
                more = destination(header.at("moreContentButton", "buttonRenderer", "navigationEndpoint", "browseEndpoint"))
                    ?: header.at("title").runs().firstNotNullOfOrNull { destination(it.at("navigationEndpoint", "browseEndpoint")) }
                val rows = entries.firstOrNull().renderer()?.first == "musicResponsiveListItemRenderer"
                style = if (rows) ShelfStyle.ROWS else ShelfStyle.CAROUSEL
            }
            "musicShelfRenderer" -> {
                header = null
                title = r.at("title").text() ?: return null
                entries = r.at("contents").arr
                more = destination(r.at("bottomEndpoint", "browseEndpoint"))
                    ?: r.at("title").runs().firstNotNullOfOrNull { destination(it.at("navigationEndpoint", "browseEndpoint")) }
                style = ShelfStyle.LIST
            }
            "gridRenderer" -> {
                header = null
                title = r.at("header", "gridHeaderRenderer", "title").text() ?: ""
                entries = r.at("items").arr
                more = null
                style = ShelfStyle.GRID
            }
            else -> return null
        }
        val hint = hintForTitle(title)
        val wide = entries.firstNotNullOfOrNull { it.at("musicTwoRowItemRenderer", "aspectRatio").str }?.contains("16_9") == true
        return Shelf(
            title = title.trim(),
            items = entries.mapNotNull { item(it, hint) }.distinctBy(::identity),
            strapline = header.at("strapline").text()?.trim(),
            style = style,
            wide = wide,
            more = more,
        )
    }

    // --- Home ------------------------------------------------------------------------------------------

    fun home(json: JsonObject): HomePage {
        val (blocks, continuation, section) = sectionBlocks(json)
        val chips = section.at("header", "chipCloudRenderer", "chips").arr.mapNotNull { chip ->
            val c = chip.at("chipCloudChipRenderer") ?: return@mapNotNull null
            val title = c.at("text").text()?.trim() ?: return@mapNotNull null
            val browse = c.at("navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
            destination(browse)?.let { Chip(title, it) }
        }
        val background = thumbnails(json.at("background", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        return HomePage(shelves(blocks), chips, background, continuation)
    }

    // --- Explore, charts, moods and other pages of shelves ---------------------------------------------

    fun explore(json: JsonObject): ExplorePage {
        val (blocks, _, _) = sectionBlocks(json)
        val links = ArrayList<Link>()
        val shelves = ArrayList<Shelf>()
        for (block in blocks) {
            val s = shelf(block) ?: continue
            // Explore's entries are an untitled grid of tiles; everything else is a shelf.
            if (s.style == ShelfStyle.GRID && s.title.isEmpty() && s.items.all { it is Link }) links += s.items.filterIsInstance<Link>()
            else if (s.items.isNotEmpty()) shelves += s
        }
        return ExplorePage(links, shelves)
    }

    /**
     * The charts page. Its countries' codes live in the answer's entity store (each option's
     * `formItemEntityKey` names the entity holding its code, as `opaqueToken`), its names in the
     * country menu; the selected one is the menu's own title.
     */
    fun charts(json: JsonObject): ChartsPage {
        val (blocks, _, _) = sectionBlocks(json)
        val codes = HashMap<String, String>()
        var selectedCode: String? = null
        for (m in json.at("frameworkUpdates", "entityBatchUpdate", "mutations").arr) {
            val choice = m.at("payload", "musicFormBooleanChoice") ?: continue
            val code = choice.at("opaqueToken").str ?: continue
            m.at("entityKey").str?.let { codes[it] = code }
            choice.at("id").str?.let { codes[it] = code }
            if (choice.at("selected").bool == true) selectedCode = code
        }
        val menu = json.findFirst("musicMultiSelectMenuRenderer")
        val countries = menu.at("options").arr.mapNotNull { o ->
            val r = o.at("musicMultiSelectMenuItemRenderer") ?: return@mapNotNull null
            val name = r.at("title").text()?.trim() ?: return@mapNotNull null
            val code = r.at("formItemEntityKey").str?.let(codes::get) ?: return@mapNotNull null
            Country(code, name)
        }
        val button = json.findFirst("musicSortFilterButtonRenderer").at("title").text()?.trim()
        val country = countries.firstOrNull { it.code == selectedCode } ?: countries.firstOrNull { it.name == button }
        return ChartsPage(country, countries, shelves(blocks))
    }

    fun moods(json: JsonObject): List<MoodGroup> {
        val (blocks, _, _) = sectionBlocks(json)
        return blocks.mapNotNull { block ->
            val grid = block.at("gridRenderer") ?: return@mapNotNull null
            val title = grid.at("header", "gridHeaderRenderer", "title").text()?.trim() ?: return@mapNotNull null
            val moods = grid.at("items").arr.mapNotNull { (item(it) as? Link) }
            MoodGroup(title, moods).takeIf { moods.isNotEmpty() }
        }
    }

    fun browsePage(json: JsonObject): BrowsePage {
        val (blocks, continuation, _) = sectionBlocks(json)
        val title = (json.at("header").renderer()?.second ?: json.findFirst("musicResponsiveHeaderRenderer")).at("title").text()?.trim()
        return BrowsePage(title, shelves(blocks), continuation)
    }

    // --- Search ------------------------------------------------------------------------------------------

    fun search(json: JsonObject): SearchPage {
        val tabs = json.at("contents", "tabbedSearchResultsRenderer", "tabs").arr
        val section = tabs.firstNotNullOfOrNull { it.at("tabRenderer", "content", "sectionListRenderer") }
            ?: json.at("contents", "sectionListRenderer")
        val filters = section.at("header", "chipCloudRenderer", "chips").arr.mapNotNull { chip ->
            val c = chip.at("chipCloudChipRenderer") ?: return@mapNotNull null
            val params = c.at("navigationEndpoint", "searchEndpoint", "params").str ?: return@mapNotNull null
            val label = c.at("text").text()?.trim() ?: return@mapNotNull null
            SearchFilter(label, params)
        }
        val items = ArrayList<Item>()
        var continuation: Continuation? = null
        var noResults = false
        for (block in section.at("contents").arr) {
            val (name, r) = block.renderer() ?: continue
            when (name) {
                "musicCardShelfRenderer" -> {
                    val card = cardItem(r)
                    card?.let(items::add)
                    // A top result's songs are the card's own ("Song • 2:49") and don't name who made
                    // them: an artist's or an album's card lends them its artists, by its page, not its words.
                    val by = when (card) {
                        is Artist -> listOf(Ref(card.title, card.browseId))
                        is Album -> card.artists
                        else -> emptyList()
                    }
                    r.at("contents").arr.mapNotNullTo(items) { entry ->
                        val found = item(entry)
                        if (found is Song && found.artists.isEmpty() && by.isNotEmpty()) found.copy(artists = by) else found
                    }
                }
                "musicShelfRenderer" -> {
                    val hint = hintForTitle(r.at("title").text())
                    r.at("contents").arr.mapNotNullTo(items) { item(it, hint) }
                    continuation = continuation ?: continuationOf(r)
                }
                // Since 2026 the unfiltered results come as one flat list: a section per result.
                "itemSectionRenderer" -> {
                    val contents = r.at("contents").arr
                    if (contents.any { it.renderer()?.first == "messageRenderer" }) noResults = true
                    contents.mapNotNullTo(items) { item(it) }
                    continuation = continuation ?: continuationOf(r)
                }
            }
        }
        return SearchPage(items.distinctBy(::identity), filters, continuation, noResults && items.isEmpty())
    }

    fun searchContinuation(json: JsonObject, hint: Hint = Hint.NONE): Page<Item> {
        val (entries, continuation) = continuationEntries(json)
        return Page(entries.mapNotNull { item(it, hint) }, continuation)
    }

    /**
     * The length of every song in a queue's answer ([YouTubeMusicClient.queue]), by its id: a song given
     * with its other version (the audio beside the video) has both read.
     */
    fun lengths(json: JsonObject): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (data in json.at("queueDatas").arr) {
            val content = data.at("content") ?: continue
            val wrapper = content.at("playlistPanelVideoWrapperRenderer")
            val panels = listOfNotNull(content.at("playlistPanelVideoRenderer"), wrapper.at("primaryRenderer", "playlistPanelVideoRenderer")) +
                wrapper.at("counterpart").arr.mapNotNull { it.at("counterpartRenderer", "playlistPanelVideoRenderer") }
            for (panel in panels) {
                val id = panel.at("videoId").str ?: continue
                val ms = panel.at("lengthText").text()?.trim()?.let(::parseDuration) ?: continue
                out.putIfAbsent(id, ms)
            }
        }
        return out
    }

    fun suggestions(json: JsonObject): Suggestions {
        val queries = ArrayList<String>()
        val items = ArrayList<Item>()
        for (entry in json.at("contents").arr.flatMap { it.at("searchSuggestionsSectionRenderer", "contents").arr }) {
            val (name, r) = entry.renderer() ?: continue
            when (name) {
                "searchSuggestionRenderer" -> r.at("suggestion").text()?.trim()?.let(queries::add)
                else -> item(entry)?.let(items::add)
            }
        }
        return Suggestions(queries.distinct(), items.distinctBy(::identity))
    }

    /** The top result card: an artist, album, song or playlist with its own layout. */
    private fun cardItem(r: JsonObject): Item? {
        val titleRun = r.at("title").runs().firstOrNull() ?: return null
        val title = titleRun.runText().trim().takeIf { it.isNotEmpty() } ?: return null
        val detail = r.at("subtitle").runs()
        val label = detail.firstOrNull()?.runText()?.trim()?.takeIf { it in TYPE_LABELS }
        val thumbs = thumbnails(r.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
        val endpoint = titleRun.at("navigationEndpoint") ?: r.at("onTap")
        endpoint.at("watchEndpoint", "videoId").str?.let { videoId ->
            val (artists, album) = songPages(r, detail, label)
            return Song(
                videoId = videoId,
                title = title,
                artists = artists,
                album = album,
                durationMs = detail.firstOrNull { DURATION.matches(it.runText().trim()) }?.runText()?.let(::parseDuration),
                thumbnails = thumbs,
                explicit = isExplicit(r),
                kind = kindOf(endpoint.findFirst("musicVideoType").str, label, Hint.NONE),
                subtitle = line(detail),
            )
        }
        val browse = endpoint.at("browseEndpoint") ?: return null
        val browseId = browse.at("browseId").str ?: return null
        return pageItem(browseId, pageTypeOf(browse), title, detail, label, thumbs, isExplicit(r), Hint.NONE)
    }

    // --- Albums and playlists ---------------------------------------------------------------------------

    fun collection(json: JsonObject, albumPage: Boolean): Collection? {
        val header = json.findFirst("musicResponsiveHeaderRenderer")
            ?: json.findFirst("musicDetailHeaderRenderer")
            ?: json.at("header").renderer()?.second
        val title = header.at("title").text()?.trim()
        val shelf = json.findFirst("musicPlaylistShelfRenderer") ?: json.findFirst("musicShelfRenderer")
        if (title == null && shelf == null) return null
        val subtitle = header.at("subtitle").runs()
        val strapline = header.at("straplineTextOne").runs()
        val linkedArtists = (strapline + subtitle).mapNotNull { pageRef(it, ARTIST_PAGE) ?: pageRef(it, USER_CHANNEL_PAGE) }
        val artists = linkedArtists.ifEmpty {
            strapline.map { it.runText().trim() }.filter { it.isNotEmpty() && it != SEPARATOR.trim() }.map { Ref(it) }
        }
        val year = subtitle.firstNotNullOfOrNull { YEAR.matchEntire(it.runText().trim())?.value?.toIntOrNull() }
        val thumbs = thumbnails(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
            .ifEmpty { thumbnails(header.at("thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails")) }
        val second = header.at("secondSubtitle").runs()
        val counts = (second + subtitle).firstNotNullOfOrNull { run ->
            COUNT.find(run.runText())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
        }
        val hint = if (albumPage) Hint.SONG else Hint.NONE
        val tracks = shelf.at("contents").arr.mapNotNull { item(it, hint) as? Song }
        val playlistId = shelf.at("playlistId").str
            ?: json.findAll("watchEndpoint", 40).firstNotNullOfOrNull { it.at("playlistId").str }
            ?: json.findAll("watchPlaylistEndpoint", 10).firstNotNullOfOrNull { it.at("playlistId").str }
            ?: json.at("microformat", "microformatDataRenderer", "urlCanonical").str?.substringAfter("list=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() }
        val description = header.at("description", "musicDescriptionShelfRenderer", "description").text()?.trim()
            ?: header.at("description").text()?.trim()
        return Collection(
            title = title ?: "",
            subtitle = line(subtitle),
            secondSubtitle = line(second),
            description = description,
            artists = artists,
            year = year,
            thumbnails = thumbs,
            tracks = tracks,
            playlistId = playlistId,
            trackCount = counts,
            continuation = shelf?.let { continuationOf(it) },
            isAlbum = albumPage,
            // Only the listener's own playlists come wrapped in the header that offers to edit them.
            editable = !albumPage && json.findFirst("musicEditablePlaylistDetailHeaderRenderer") != null,
        )
    }

    /** The id of a playlist just made in the account; null when the answer has none. */
    fun createdPlaylist(json: JsonObject): String? = json.at("playlistId").str?.takeIf { it.isNotBlank() }

    /** Whether the account took a playlist's changes. */
    fun edited(json: JsonObject): Boolean = json.at("status").str == "STATUS_SUCCEEDED"

    fun songsContinuation(json: JsonObject): Page<Song> {
        val (entries, continuation) = continuationEntries(json)
        return Page(entries.mapNotNull { item(it, Hint.SONG) as? Song }, continuation)
    }

    // --- Artists ------------------------------------------------------------------------------------------

    fun artist(json: JsonObject): ArtistPage? {
        val header = json.findFirst("musicImmersiveHeaderRenderer")
            ?: json.findFirst("musicVisualHeaderRenderer")
            ?: json.findFirst("musicResponsiveHeaderRenderer")
            ?: json.at("header").renderer()?.second
        val name = header.at("title").text()?.trim() ?: return null
        val thumbs = thumbnails(header.at("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails"))
            .ifEmpty { thumbnails(header.at("foregroundThumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")) }
        val (blocks, _, _) = sectionBlocks(json)
        val shelves = shelves(blocks)
        var songs: List<Song> = emptyList()
        var songsPlaylist: String? = null
        val albums = ArrayList<Album>()
        val singles = ArrayList<Album>()
        val videos = ArrayList<Song>()
        val playlists = ArrayList<Playlist>()
        val related = ArrayList<Artist>()
        val carouselSongs = ArrayList<Song>()
        for (block in blocks) {
            val (kind, r) = block.renderer() ?: continue
            if (kind == "musicShelfRenderer" && songs.isEmpty()) {
                songs = r.at("contents").arr.mapNotNull { item(it, Hint.SONG) as? Song }
                songsPlaylist = (r.at("bottomEndpoint", "browseEndpoint", "browseId").str
                    ?: r.at("title").runs().firstNotNullOfOrNull { it.at("navigationEndpoint", "browseEndpoint", "browseId").str })
                    ?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
                continue
            }
            val s = shelf(block) ?: continue
            val lower = s.title.lowercase()
            for (i in s.items) when (i) {
                is Album -> if ("single" in lower || i.type == "Single" || i.type == "EP") singles += i else albums += i
                is Song -> if (i.kind == MediaKind.SONG) carouselSongs += i else if (i.kind != MediaKind.EPISODE) videos += i
                is Artist -> related += i
                is Playlist -> playlists += i
                else -> Unit
            }
        }
        if (songs.isEmpty()) songs = carouselSongs
        return ArtistPage(
            name = name,
            description = header.at("description").text()?.trim(),
            subscribers = header.at("subscriptionButton", "subscribeButtonRenderer", "subscriberCountText").text()?.trim(),
            monthlyAudience = header.at("monthlyListenerCount").text()?.trim(),
            thumbnails = thumbs,
            shelves = shelves,
            songs = songs,
            songsPlaylistId = songsPlaylist,
            albums = albums,
            singles = singles,
            videos = videos,
            playlists = playlists,
            related = related,
            // The buttons have pointed at a playlist (watchPlaylistEndpoint) and, since 2026, at a
            // starting song within it (watchEndpoint): either is read.
            radio = watchTarget(header.at("startRadioButton", "buttonRenderer", "navigationEndpoint")),
            shuffle = watchTarget(header.at("playButton", "buttonRenderer", "navigationEndpoint")),
        )
    }

    // --- The watch-next list ---------------------------------------------------------------------------

    fun upNext(json: JsonObject): UpNext {
        val panel = json.findFirst("playlistPanelRenderer")
        val songs = panel.at("contents").arr.mapNotNull { item(it) as? Song }
        // The tabs beside the queue: Up next, Lyrics, Related. Lyrics and Related are pages of their own.
        val tabs = json.findFirst("watchNextTabbedResultsRenderer").at("tabs").arr.mapNotNull { tab ->
            tab.at("tabRenderer", "endpoint", "browseEndpoint")?.let { destination(it) }
        }
        return UpNext(
            songs = songs,
            continuation = panel?.let(::continuationOf),
            lyrics = tabs.firstOrNull { it.browseId.startsWith("MPLY") },
            related = tabs.firstOrNull { it.browseId.startsWith("MPTR") },
        )
    }

    /**
     * The song each music video in a queue's answer stands for, where YouTube Music pairs them (its own
     * Song/Video switch, which it offers signed in only): the video's id to the song. BitChord's
     * `parseWatchQueue(preferAudio)`, as a map.
     */
    fun songVersions(json: JsonObject): Map<String, Song> {
        val out = LinkedHashMap<String, Song>()
        for (wrapper in json.findAll("playlistPanelVideoWrapperRenderer")) {
            val halves = listOfNotNull(wrapper.at("primaryRenderer")) + wrapper.at("counterpart").arr.mapNotNull { it.at("counterpartRenderer") }
            val songs = halves.mapNotNull { item(it) as? Song }
            val song = songs.firstOrNull { it.kind == MediaKind.SONG } ?: continue
            songs.filter { it.kind == MediaKind.MUSIC_VIDEO || it.kind == MediaKind.VIDEO }.forEach { out.putIfAbsent(it.videoId, song) }
        }
        return out
    }

    fun upNextContinuation(json: JsonObject): Page<Song> {
        val (entries, continuation) = continuationEntries(json)
        return Page(entries.mapNotNull { item(it) as? Song }, continuation)
    }

    fun lyrics(json: JsonObject): Lyrics? {
        val shelf = json.findFirst("musicDescriptionShelfRenderer") ?: return null
        val text = shelf.at("description").text()?.trim() ?: return null
        return Lyrics(text, shelf.at("footer").text()?.trim())
    }

    // --- The account (signed in) ---------------------------------------------------------------------------

    /** A library tab: a grid of playlists or albums, or a list of artists or songs. */
    fun library(json: JsonObject): Page<Item> {
        val grid = json.findFirst("gridRenderer")
        if (grid != null) return Page(grid.at("items").arr.mapNotNull { item(it) }, continuationOf(grid))
        val shelf = json.findFirst("musicShelfRenderer") ?: json.findFirst("musicPlaylistShelfRenderer")
        return Page(shelf.at("contents").arr.mapNotNull { item(it) }, shelf?.let(::continuationOf))
    }

    fun libraryContinuation(json: JsonObject): Page<Item> {
        val (entries, continuation) = continuationEntries(json)
        return Page(entries.mapNotNull { item(it) }, continuation)
    }

    fun history(json: JsonObject): List<HistoryItem> {
        val (blocks, _, _) = sectionBlocks(json)
        return blocks.flatMap { block ->
            val (name, r) = block.renderer() ?: return@flatMap emptyList()
            if (name != "musicShelfRenderer") return@flatMap emptyList()
            val period = r.at("title").text()?.trim()
            r.at("contents").arr.mapNotNull { (item(it, Hint.SONG) as? Song)?.let { s -> HistoryItem(s, period) } }
        }
    }

    fun account(json: JsonObject): Account? {
        val header = json.findFirst("activeAccountHeaderRenderer") ?: return null
        val name = header.at("accountName").text()?.trim() ?: return null
        return Account(name, header.at("channelHandle").text()?.trim(), thumbnails(header.at("accountPhoto", "thumbnails")))
    }

    // --- Shared pieces ----------------------------------------------------------------------------------

    /** The blocks of a page's main section list, how to load more of them, and the list itself (for its header). */
    private fun sectionBlocks(json: JsonObject): Triple<List<JsonElement>, Continuation?, JsonElement?> {
        json.at("continuationContents", "sectionListContinuation")?.let { c ->
            return Triple(c.at("contents").arr, continuationOf(c), c)
        }
        val section = json.at("contents", "singleColumnBrowseResultsRenderer", "tabs").arr
            .firstNotNullOfOrNull { it.at("tabRenderer", "content", "sectionListRenderer") }
            ?: json.at("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
            ?: json.findFirst("sectionListRenderer")
        return Triple(section.at("contents").arr, section?.let(::continuationOf), section)
    }

    /** The entries of a continuation answer, in either of the catalogue's two styles. */
    private fun continuationEntries(json: JsonObject): Pair<List<JsonElement>, Continuation?> {
        json.at("continuationContents")?.renderer()?.second?.let { c ->
            val entries = c.at("contents").arr.ifEmpty { c.at("items").arr }
            return entries to continuationOf(c)
        }
        val appended = json.at("onResponseReceivedActions").arr.flatMap { it.at("appendContinuationItemsAction", "continuationItems").arr }
        return appended to trailingContinuation(appended)
    }

    /** How to continue a list: the older `continuations` field, or a trailing continuation item. */
    fun continuationOf(list: JsonElement): Continuation? {
        list.at("continuations").arr.firstNotNullOfOrNull { c ->
            (c.at("nextContinuationData") ?: c.at("nextRadioContinuationData"))?.at("continuation").str
        }?.let { return Continuation(it, inBody = false) }
        return trailingContinuation(list.at("contents").arr.ifEmpty { list.at("items").arr })
    }

    private fun trailingContinuation(entries: List<JsonElement>): Continuation? =
        entries.lastOrNull()?.at("continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token").str
            ?.let { Continuation(it, inBody = true) }

    fun thumbnails(e: JsonElement?): List<Thumbnail> =
        e.arr.mapNotNull { t ->
            val url = t.at("url").str?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            Thumbnail(url, t.at("width").long?.toInt() ?: 0, t.at("height").long?.toInt() ?: 0)
        }.sortedBy { it.width }

    private fun destination(browse: JsonElement?): Destination? {
        val id = browse.at("browseId").str ?: return null
        return Destination(id, browse.at("params").str)
    }

    private fun watchTarget(endpoint: JsonElement?): WatchTarget? {
        val watch = endpoint.at("watchEndpoint") ?: endpoint.at("watchPlaylistEndpoint") ?: return null
        val target = WatchTarget(watch.at("videoId").str, watch.at("playlistId").str, watch.at("params").str)
        return target.takeIf { it.videoId != null || it.playlistId != null }
    }

    /** The detail line as the service writes it. */
    private fun line(runs: List<JsonObject>): String? = runs.joinToString("") { it.runText() }.trim().takeIf { it.isNotEmpty() }

    private fun playsIn(runs: List<JsonObject>): String? =
        runs.map { it.runText().trim() }.firstOrNull { it.endsWith(" plays") || it.endsWith(" views") }

    /** The artists named in a detail line: linked artist runs, else the plain words before the album. */
    private fun artistsIn(runs: List<JsonObject>, label: String?): List<Ref> {
        val linked = runs.mapNotNull { pageRef(it, ARTIST_PAGE) ?: pageRef(it, USER_CHANNEL_PAGE) }
        if (linked.isNotEmpty()) return linked
        return plainParts(runs, label).firstOrNull()
            ?.takeUnless(::isNotAName)
            ?.let { listOf(Ref(it)) }
            .orEmpty()
    }

    /** The detail line split at its separators, without the type label. */
    private fun plainParts(runs: List<JsonObject>, label: String?): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        for (run in runs) {
            val text = run.runText()
            if (text.trim() == SEPARATOR.trim()) {
                if (current.isNotBlank()) parts += current.toString().trim()
                current.clear()
            } else {
                current.append(text)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return if (label != null && parts.firstOrNull() == label) parts.drop(1) else parts
    }

    private fun pageRef(run: JsonObject, pageType: String): Ref? {
        val browse = run.at("navigationEndpoint", "browseEndpoint") ?: return null
        if (pageTypeOf(browse) != pageType) return null
        val name = run.runText().trim().takeIf { it.isNotEmpty() } ?: return null
        return Ref(name, browse.at("browseId").str)
    }

    /** The kind of page a link opens: as the link says, or, when it doesn't, as its id shows. */
    private fun pageTypeOf(browse: JsonElement): String? {
        browse.at("browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType").str?.let { return it }
        val id = browse.at("browseId").str ?: return null
        return when {
            id.startsWith("MPRE") -> ALBUM_PAGE
            id.startsWith("UC") || id.startsWith("MPLAUC") -> ARTIST_PAGE
            else -> null
        }
    }

    /**
     * A song's artists and album: linked in its detail line, else from the row's own menu (Go to
     * album, Go to artist), which links them when the line only names them. The menu's artist is
     * taken only for a line naming one artist, so a page is never put on the wrong name.
     */
    private fun songPages(r: JsonObject, runs: List<JsonObject>, label: String?): Pair<List<Ref>, Ref?> {
        val artists = artistsIn(runs, label)
        val album = runs.firstNotNullOfOrNull { pageRef(it, ALBUM_PAGE) }
        if (album != null && artists.all { it.browseId != null }) return artists to album
        val menu = r.at("menu", "menuRenderer", "items").arr.mapNotNull { item ->
            val browse = item.at("menuNavigationItemRenderer", "navigationEndpoint", "browseEndpoint") ?: return@mapNotNull null
            val id = browse.at("browseId").str ?: return@mapNotNull null
            pageTypeOf(browse)?.let { it to id }
        }
        val menuAlbum = menu.firstOrNull { it.first == ALBUM_PAGE }?.second
        val albumName = plainParts(runs, label).getOrNull(1)?.takeUnless(::isNotAName)
        val menuArtists = menu.filter { it.first == ARTIST_PAGE }.map { it.second }.distinct()
        val single = artists.singleOrNull()?.takeIf { it.browseId == null && !namesSeveral(it.name) }
        return Pair(
            if (single != null && menuArtists.size == 1) listOf(single.copy(browseId = menuArtists[0])) else artists,
            album ?: if (menuAlbum != null && albumName != null) Ref(albumName, menuAlbum) else null,
        )
    }

    private fun isNotAName(text: String) =
        DURATION.matches(text) || YEAR.matches(text) || text.endsWith(" views") || text.endsWith(" plays")

    /** "A & B", "A, B", "A x B", "A feat. B": a line naming more than one artist. */
    private fun namesSeveral(text: String) = SEVERAL.containsMatchIn(text)

    private fun isExplicit(r: JsonObject): Boolean =
        (r.at("badges").arr + r.at("subtitleBadges").arr + r.at("subtitleBadge").arr).any {
            it.at("musicInlineBadgeRenderer", "icon", "iconType").str == "MUSIC_EXPLICIT_BADGE"
        }

    fun kindOf(videoType: String?, label: String?, hint: Hint): MediaKind = when (videoType) {
        "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" -> MediaKind.SONG
        "MUSIC_VIDEO_TYPE_OMV", "MUSIC_VIDEO_TYPE_OFFICIAL_SOURCE_MUSIC" -> MediaKind.MUSIC_VIDEO
        "MUSIC_VIDEO_TYPE_UGC" -> MediaKind.VIDEO
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> MediaKind.EPISODE
        else -> when (label) {
            "Song" -> MediaKind.SONG
            "Video" -> MediaKind.VIDEO
            "Episode" -> MediaKind.EPISODE
            else -> when (hint) {
                Hint.VIDEO -> MediaKind.VIDEO
                Hint.EPISODE -> MediaKind.EPISODE
                else -> MediaKind.SONG
            }
        }
    }

    private fun hintForTitle(title: String?): Hint {
        val t = title?.lowercase() ?: return Hint.NONE
        return when {
            "song" in t -> Hint.SONG
            "video" in t -> Hint.VIDEO
            "album" in t || "single" in t -> Hint.ALBUM
            "artist" in t -> Hint.ARTIST
            "playlist" in t -> Hint.PLAYLIST
            "episode" in t -> Hint.EPISODE
            else -> Hint.NONE
        }
    }

    fun identity(item: Item): String = when (item) {
        is Song -> "s:${item.videoId}"
        is Album -> "a:${item.browseId}"
        is Artist -> "r:${item.browseId}"
        is Playlist -> "p:${item.playlistId}"
        is Podcast -> "c:${item.browseId}"
        is Link -> "l:${item.destination.browseId}:${item.destination.params}"
    }

    /** "3:45", "1:02:03" → milliseconds. */
    fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return numbers.fold(0L) { acc, n -> acc * 60 + n } * 1000
    }

    private const val SEPARATOR = " • "
    private val SEPARATOR_RUN = JsonObject(mapOf("text" to JsonPrimitive(SEPARATOR)))
    private val DURATION = Regex("\\d{1,2}(:\\d{2}){1,2}")
    private val YEAR = Regex("(19|20)\\d{2}")
    private val SEVERAL = Regex("( & |, | x | and | feat\\.? | ft\\.? | with )", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("([\\d,]+)\\s+(songs?|tracks?|episodes?)")
    private const val GREYED_OUT = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
    const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
    const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"
    const val PLAYLIST_PAGE = "MUSIC_PAGE_TYPE_PLAYLIST"
    private const val PODCAST_PAGE = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
    private const val NON_MUSIC_TRACK_PAGE = "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
    private const val EPISODE_PAGE = "MUSIC_PAGE_TYPE_PODCAST_EPISODE_PAGE"
    private const val USER_CHANNEL_PAGE = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val LIBRARY_ARTIST_PAGE = "MUSIC_PAGE_TYPE_LIBRARY_ARTIST"
    private val ALBUM_LABELS = setOf("Album", "Single", "EP")
    private val TYPE_LABELS = setOf("Song", "Video", "Episode", "Album", "Single", "EP", "Artist", "Playlist", "Podcast", "Profile", "Station")
}
