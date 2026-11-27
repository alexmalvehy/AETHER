package app.aether.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.IOException
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A search's mixed answer gives a length only to its top result's own songs; every other song row carries
 * its plays instead (measured 2026-11-19, "espresso": 13 songs, 3 with a length). The rest are asked of a
 * queue, which answers each song's length, and matched by id.
 */
class SearchLengthsTest {
    private fun bytes(name: String) =
        GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json.gz"))).use { it.readBytes() }

    private fun fixture(name: String) = checkNotNull(YouTubeMusicClient.parseJson(bytes(name)))

    /** The service, answering the search and the queue from what it said on 2026-11-19. */
    private class Service(val search: ByteArray, val queue: ByteArray?) : HttpTransport {
        val queued = mutableListOf<List<String>>()

        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (method == "GET") return HttpResponse(200, """{"INNERTUBE_CLIENT_VERSION":"1.20261111.01.00","INNERTUBE_CONTEXT_GL":"US"}""".toByteArray())
            return when {
                "/music/get_queue" in url -> {
                    val ids = Json.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject["videoIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                    queued += ids
                    queue?.let { HttpResponse(200, it) } ?: throw IOException("SocketTimeoutException")
                }
                "/search" in url -> HttpResponse(200, search)
                else -> HttpResponse(404, "{}".toByteArray())
            }
        }
    }

    @Test
    fun `the mixed answer leaves most of its songs without a length, and a queue has every one`() {
        val songs = YouTubeMusicParser.search(fixture("search_mixed_espresso")).items.filterIsInstance<Song>()
        val untimed = songs.filter { it.durationMs == null }
        assertTrue(untimed.size >= 8, "only ${untimed.size} of ${songs.size} without a length")
        val lengths = YouTubeMusicParser.lengths(fixture("queue_espresso"))
        for (s in untimed) assertNotNull(lengths[s.videoId], "${s.title} (${s.videoId})")
        // As the Songs filter gives them: Kizo's 2:10, the video's 3:21.
        assertEquals(130_000L, lengths["O9dm7oje6i8"])
        assertEquals(201_000L, lengths["eVli-tstM5E"])
    }

    @Test
    fun `a search gives every song its length, asking the queue only for those without one`() = runBlocking {
        val service = Service(bytes("search_mixed_espresso"), bytes("queue_espresso"))
        val page = YouTubeMusic(language = { "en" }, region = { "US" }, transport = service).search("espresso").getOrNull()
        val songs = assertNotNull(page).items.filterIsInstance<Song>()
        assertTrue(songs.isNotEmpty())
        for (s in songs) assertNotNull(s.durationMs, "${s.title} (${s.videoId}) has no length")
        // One queue, for exactly the songs the answer left without a length.
        val untimed = YouTubeMusicParser.search(fixture("search_mixed_espresso")).items.filterIsInstance<Song>().filter { it.durationMs == null }.map { it.videoId }
        assertEquals(listOf(untimed.distinct()), service.queued)
        // A length the answer gave is kept as it was given.
        val given = YouTubeMusicParser.search(fixture("search_mixed_espresso")).items.filterIsInstance<Song>().filter { it.durationMs != null }
        for (g in given) assertEquals(g.durationMs, songs.first { it.videoId == g.videoId }.durationMs)
    }

    @Test
    fun `a queue that doesn't answer leaves the search as it was`() = runBlocking {
        val service = Service(bytes("search_mixed_espresso"), queue = null)
        val page = YouTubeMusic(language = { "en" }, region = { "US" }, transport = service).search("espresso").getOrNull()
        val expected = YouTubeMusicParser.search(fixture("search_mixed_espresso"))
        assertEquals(expected.items, assertNotNull(page).items)
        assertEquals(1, service.queued.size)
        assertNull(page.items.filterIsInstance<Song>().first { it.videoId == "O9dm7oje6i8" }.durationMs)
    }

    @Test
    fun `a search whose songs all have their length asks no queue`() = runBlocking {
        val service = Service(bytes("search_songs_espresso"), bytes("queue_espresso"))
        YouTubeMusic(language = { "en" }, region = { "US" }, transport = service).search("espresso")
        assertTrue(service.queued.isEmpty())
    }
}
