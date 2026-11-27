package app.aether.transfer

import app.aether.core.model.ArtistRef
import app.aether.core.model.Capability
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.Page
import app.aether.core.model.PageToken
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Track
import app.aether.core.model.UpNext
import app.aether.core.transfer.ImportItem
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How an import finds each song: what it asks, in what order, and how patiently. */
class FinderTest {
    private fun song(id: String, title: String, artist: String, source: String = "ytm") = Track(MediaId("$source:$id"), title, listOf(ArtistRef(artist)))

    private class Catalogue : MusicSource {
        override val id = "ytm"
        override val name = "YouTube Music"
        override val capabilities = emptySet<Capability>()
        val asked = mutableListOf<String>()
        val lookedUp = mutableListOf<String>()
        var answer: (String) -> Outcome<Page<Track>> = { Outcome.Ok(Page(emptyList(), null)) }
        var watch: (Track) -> Outcome<UpNext> = { Outcome.Err(SourceError.Unsupported) }
        override suspend fun searchSongs(query: String, token: PageToken?): Outcome<Page<Track>> { asked += query; return answer(query) }
        override suspend fun upNext(track: Track): Outcome<UpNext> { lookedUp += track.id.key; return watch(track) }
    }

    private class Phone(val songs: List<Track>) : MusicSource {
        override val id = "local"
        override val name = "This phone"
        override val capabilities = setOf(Capability.SEARCH)
        override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> =
            Outcome.Ok(SearchResults(songs.filter { it.title.contains(query, ignoreCase = true) }, emptyList(), null))
    }

    private var clock = 0L
    private val waits = mutableListOf<Long>()
    private var connected = true

    private fun finder(catalogue: Catalogue, phone: MusicSource? = null) = Finder(
        catalogue = catalogue,
        phone = phone,
        onPhone = { null },
        sourceHere = { it == "addon-here" },
        online = { connected },
        sleep = { waits += it; clock += it },
        now = { clock },
    )

    private fun found(vararg tracks: Track): Outcome<Page<Track>> = Outcome.Ok(Page(tracks.toList(), null))

    @Test fun `an AETHER file's own songs are taken as they are, without asking anything`() = runBlocking {
        val catalogue = Catalogue()
        val own = song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
        assertEquals(own, finder(catalogue).find(ImportItem(own.title, listOf("Rick Astley"), track = own)))
        val fromAddon = song("x1", "Lossless", "Someone", source = "addon-here")
        assertEquals(fromAddon, finder(catalogue).find(ImportItem(fromAddon.title, listOf("Someone"), track = fromAddon)))
        assertTrue(catalogue.asked.isEmpty() && catalogue.lookedUp.isEmpty())
    }

    @Test fun `a source that isn't here has its song found again by its words`() = runBlocking {
        val catalogue = Catalogue().apply { answer = { found(song("yt1", "Lossless", "Someone")) } }
        val gone = song("x1", "Lossless", "Someone", source = "addon-gone")
        assertEquals("yt1", finder(catalogue).find(ImportItem(gone.title, listOf("Someone"), track = gone))?.id?.key)
    }

    @Test fun `a link is looked up and taken when it's the song the file names`() = runBlocking {
        val right = song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
        val catalogue = Catalogue().apply { watch = { Outcome.Ok(UpNext(listOf(right, song("other", "Next", "Someone")))) } }
        assertEquals(right, finder(catalogue).find(ImportItem("Never Gonna Give You Up", listOf("Rick Astley"), youtubeId = "dQw4w9WgXcQ")))
        assertTrue(catalogue.asked.isEmpty(), "no search when the link holds")
    }

    @Test fun `a stale link, leading to another song, is passed over for the words beside it`() = runBlocking {
        val catalogue = Catalogue().apply {
            watch = { Outcome.Ok(UpNext(listOf(song("dQw4w9WgXcQ", "Bohemian Rhapsody", "Queen")))) }
            answer = { found(song("right", "Never Gonna Give You Up", "Rick Astley")) }
        }
        assertEquals("right", finder(catalogue).find(ImportItem("Never Gonna Give You Up", listOf("Rick Astley"), youtubeId = "dQw4w9WgXcQ"))?.id?.key)
    }

    @Test fun `turned away for asking too fast, it waits as long as the service says, then asks again`() = runBlocking {
        var calls = 0
        val catalogue = Catalogue().apply {
            answer = { if (calls++ == 0) Outcome.Err(SourceError.RateLimited(5_000)) else found(song("ok", "Song", "Artist")) }
        }
        assertEquals("ok", finder(catalogue).find(ImportItem("Song", listOf("Artist")))?.id?.key)
        assertEquals(listOf(5_000L), waits)
    }

    @Test fun `a lost answer is asked again, longer each time, and a service that never answers stops the import`() {
        val catalogue = Catalogue().apply { answer = { Outcome.Err(SourceError.Network) } }
        val stop = assertFailsWith<NoConnection> { runBlocking { finder(catalogue).find(ImportItem("Song", listOf("Artist"))) } }
        assertTrue(stop.unanswered)
        // Three waits between four tries, and no wait after the last.
        assertEquals(listOf(800L, 1_600L, 3_200L), waits)
    }

    @Test fun `a question the service refuses as such is just that question unanswered`() = runBlocking {
        val catalogue = Catalogue().apply { answer = { q -> if (q == "Song Artist") Outcome.Err(SourceError.Server(400)) else found(song("ok", "Song", "Artist")) } }
        assertEquals("ok", finder(catalogue).find(ImportItem("Song", listOf("Artist")))?.id?.key)
        assertTrue(waits.isEmpty())
    }

    @Test fun `no connection stops the import rather than finding nothing for every song`() {
        val offline = Catalogue().apply { answer = { Outcome.Err(SourceError.Offline) } }
        assertFailsWith<NoConnection> { runBlocking { finder(offline).find(ImportItem("Song", listOf("Artist"))) } }
        connected = false
        val dropped = Catalogue().apply { answer = { Outcome.Err(SourceError.Network) } }
        assertFailsWith<NoConnection> { runBlocking { finder(dropped).find(ImportItem("Song", listOf("Artist"))) } }
    }

    @Test fun `a song in several playlists is looked for once, found or not`() = runBlocking {
        val catalogue = Catalogue().apply { answer = { q -> if ("Here" in q) found(song("h", "Here", "Artist")) else found() } }
        val f = finder(catalogue)
        f.find(ImportItem("Here", listOf("Artist")))
        f.find(ImportItem("Missing", listOf("Artist")))
        val before = catalogue.asked.size
        assertEquals("h", f.find(ImportItem(" here ", listOf("artist")))?.id?.key)
        assertNull(f.find(ImportItem("Missing", listOf("Artist"))))
        assertEquals(before, catalogue.asked.size)
    }

    @Test fun `a line whose split was a guess is read the other way round when the first reading finds nothing`() = runBlocking {
        val catalogue = Catalogue().apply {
            answer = { q -> if (q.startsWith("Stand by Me")) found(song("sbm", "Stand by Me", "Ben E. King")) else found() }
        }
        // As a list's line reads it: "Artist - Title" first, so a song called "Ben E. King".
        val item = ImportItem("Ben E. King", listOf("Stand by Me"), reference = "Stand by Me - Ben E. King", loose = true)
        assertEquals("sbm", finder(catalogue).find(item)?.id?.key)
    }

    @Test fun `a file of the listener's own is looked for on the phone first`() = runBlocking {
        val catalogue = Catalogue()
        val mine = song("42", "Teardrop", "Massive Attack", source = "local")
        val f = finder(catalogue, Phone(listOf(mine, song("43", "Teardrop", "Someone Else", source = "local"))))
        assertEquals(mine, f.find(ImportItem("Teardrop", listOf("Massive Attack"), local = true)))
        assertTrue(catalogue.asked.isEmpty())
    }

    @Test fun `nothing is taken for want of a better`() = runBlocking {
        val catalogue = Catalogue().apply { answer = { found(song("a", "Hello", "Lionel Richie"), song("b", "Hello", "Evanescence")) } }
        assertNull(finder(catalogue).find(ImportItem("Hello", listOf("Adele"))))
    }
}
