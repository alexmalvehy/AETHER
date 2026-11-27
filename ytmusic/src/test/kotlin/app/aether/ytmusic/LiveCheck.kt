package app.aether.ytmusic

import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Asks the real service for every guest surface, as the app will, and says what came back
 * (`./gradlew :ytmusic:liveCheck [-Pcountry=US]`). A surface that fails or parses to nothing fails
 * the run: that's the moment to recapture fixtures and look at what changed.
 */
fun main(args: Array<String>) = runBlocking {
    val country = args.firstOrNull()
    val yt = YouTubeMusic(language = { "en" })
    val problems = ArrayList<String>()

    suspend fun <T> check(name: String, call: suspend () -> YtResult<T>, describe: (T) -> String, empty: (T) -> Boolean) {
        when (val r = call()) {
            is YtResult.Failure -> problems += "$name: ${r.error}".also { println("✗ $it") }
            is YtResult.Success -> if (empty(r.value)) problems += "$name: parsed to nothing".also { println("✗ $it") }
            else println("✓ $name: ${describe(r.value)}")
        }
    }

    fun shelves(list: List<Shelf>) = list.joinToString(" | ") { "${it.title.ifEmpty { "(tiles)" }} (${it.items.size})" }

    var home: HomePage? = null
    check("home", { yt.home().also { home = it.getOrNull() } }, { "${it.chips.size} chips, ${shelves(it.shelves)}" }, { it.shelves.isEmpty() })
    home?.continuation?.let { c -> check("home, more", { yt.homeMore(c) }, { shelves(it.shelves) }, { it.shelves.isEmpty() }) }
    home?.chips?.firstOrNull { it.title != "Podcasts" }?.let { chip ->
        check("home, chip ${chip.title}", { yt.home(chip) }, { shelves(it.shelves) }, { it.shelves.isEmpty() })
    }
    var explore: ExplorePage? = null
    check("explore", { yt.explore().also { explore = it.getOrNull() } }, { "${it.links.map(Link::title)}, ${shelves(it.shelves)}" }, { it.shelves.isEmpty() })
    var charts: ChartsPage? = null
    check("charts", { yt.charts(country).also { charts = it.getOrNull() } }, { "${it.country} of ${it.countries.size}, ${shelves(it.shelves)}" }, { it.shelves.isEmpty() || it.countries.isEmpty() })
    var moods: List<MoodGroup>? = null
    check("moods", { yt.moods().also { moods = it.getOrNull() } }, { g -> g.joinToString { "${it.title} (${it.moods.size})" } }, { it.isEmpty() })
    moods?.forEach { g -> println("  mood titles, ${g.title}: ${g.moods.joinToString { it.title }}") }
    moods?.firstOrNull()?.moods?.firstOrNull()?.let { mood ->
        check("mood ${mood.title}", { yt.browse(mood.destination) }, { shelves(it.shelves) }, { it.shelves.isEmpty() })
    }
    check("new releases", { yt.newReleases() }, { shelves(it.shelves) }, { it.shelves.isEmpty() })
    check("new albums", { yt.newAlbums() }, { shelves(it.shelves) }, { it.shelves.isEmpty() })
    check("podcasts", { yt.podcasts() }, { shelves(it.shelves) }, { it.shelves.isEmpty() })

    val chart = charts?.shelves?.firstOrNull()?.items?.filterIsInstance<Playlist>()?.firstOrNull()
    chart?.let { c -> check("chart ${c.title}", { yt.playlist(c.playlistId) }, { "${it.tracks.size} tracks, ${it.secondSubtitle}" }, { it.tracks.isEmpty() }) }
    val album = explore?.shelves?.flatMap { it.items }?.filterIsInstance<Album>()?.firstOrNull()
    album?.let { a -> check("album ${a.title}", { yt.album(a.browseId) }, { "${it.tracks.size} tracks, ${it.subtitle}" }, { it.tracks.isEmpty() }) }
    val artist = charts?.shelves?.flatMap { it.items }?.filterIsInstance<Artist>()?.firstOrNull()
    artist?.let { a ->
        check("artist ${a.title}", { yt.artist(a.browseId) }, { "${it.monthlyAudience}, ${shelves(it.shelves)}" }, { it.shelves.isEmpty() })
    }
    check("search", { yt.search("arijit singh") }, { "${it.items.size} results, filters ${it.filters.map(SearchFilter::label)}" }, { it.items.isEmpty() })
    check("suggestions", { yt.suggestions("arijit") }, { "${it.queries.size} queries, ${it.items.size} items" }, { it.queries.isEmpty() })
    // Songs alone, as search asks for them beside the mixed answer, with the filter AETHER writes itself.
    check("songs alone", { yt.searchSongs("not like us kendrick lamar") }, { p ->
        val songs = p.items.filterIsInstance<Song>()
        "${songs.size} songs, ${songs.count { it.explicit }} marked explicit, ${songs.count { it.durationMs != null }} with a length, more: ${p.continuation != null}"
    }, { p -> p.items.filterIsInstance<Song>().let { s -> s.size < 5 || s.none { it.explicit } } })
    // A song found by searching is followed by its radio: what's like it, not what shares its name.
    val found = yt.search("tum hi ho").getOrNull()?.items?.filterIsInstance<Song>()
    found?.firstOrNull()?.let { s ->
        println("  searching gives: ${found.take(6).joinToString { "${it.title} / ${it.subtitle}" }}")
        println("  its own play target: ${s.watchTarget}")
        check("radio of ${s.title}", { yt.upNext(WatchTarget.radio(s.videoId)) }, { r ->
            val same = r.songs.drop(1).count { it.title.equals(s.title, ignoreCase = true) }
            "${r.songs.size} songs, $same share its name: ${r.songs.take(8).joinToString { it.title }}"
        }, { it.songs.size < 2 })
    }

    val song = home?.shelves?.firstOrNull()?.items?.filterIsInstance<Song>()?.firstOrNull()
    song?.let { s ->
        var next: UpNext? = null
        check("up next ${s.title}", { yt.upNext(s.watchTarget).also { next = it.getOrNull() } }, { "${it.songs.size} songs, lyrics ${it.lyrics != null}, related ${it.related != null}" }, { it.songs.size < 2 })
        next?.lyrics?.let { d -> check("lyrics", { yt.lyrics(d) }, { l -> l?.let { "${it.text.lines().size} lines, ${it.source}" } ?: "none for this song" }, { false }) }
        next?.related?.let { d -> check("related", { yt.related(d) }, { shelves(it) }, { it.isEmpty() }) }
    }

    println(if (problems.isEmpty()) "\nEvery surface answered and parsed." else "\n${problems.size} problem(s): $problems")
    if (problems.isNotEmpty()) exitProcess(1)
}
