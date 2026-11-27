package app.aether.sources.addon

import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Track
import kotlinx.coroutines.runBlocking
import okio.buffer
import okhttp3.ResponseBody.Companion.asResponseBody
import kotlinx.coroutines.asCoroutineDispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An addon as AETHER asks it, against a scripted one: what BitChord's host does, AETHER does. */
class AddonSourceTest {
    private class Addon(var manifest: String = MANIFEST) {
        val asked = mutableListOf<String>()
        var search: Pair<Int, String> = 200 to """{"tracks":[{"id":"t1","title":"Hello","artist":"Adele","duration":295,"artworkURL":"https://img/x.jpg","streamURL":"https://cdn/row.flac"}]}"""
        var stream: Pair<Int, String> = 200 to """{"url":"https://cdn/asked.flac","codec":"flac","sampleRate":44100,"bitDepth":16}"""
        var dropFirst = 0

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            asked += url.encodedPath
            if (dropFirst > 0) { dropFirst--; throw IOException("SocketTimeoutException") }
            val (code, body) = when {
                url.encodedPath.endsWith("/manifest.json") -> 200 to manifest
                url.encodedPath.endsWith("/search") -> search
                url.encodedPath.contains("/stream/") -> stream
                else -> 404 to "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    private fun source(addon: Addon, lossless: Boolean = true): AddonSource = runBlocking {
        (AddonSource.connect("https://addon.example/a/manifest.json", addon.http, losslessOutput = { lossless }) as Outcome.Ok).value
    }

    private fun AddonSource.found(): List<Track> = runBlocking { (search("Hello Adele", null) as Outcome.Ok).value.items.filterIsInstance<Track>() }

    @Test fun `a refusal is kept in the addon's own words, and repeated ones rest it`() = runBlocking {
        val addon = Addon().apply { search = 403 to """{"error":"Qobuz upstream returned a non-JSON response."}""" }
        val s = source(addon)
        assertIs<Outcome.Err>(s.search("Hello", null))
        assertEquals("Qobuz upstream returned a non-JSON response.", s.lastError)
        assertNull(s.trouble)
        s.search("Hello", null)
        assertEquals("Qobuz upstream returned a non-JSON response.", s.trouble)
        assertFalse(s.ready)
    }

    @Test fun `an answer clears the last error`() = runBlocking {
        val addon = Addon().apply { search = 500 to """{"message":"down for a minute"}""" }
        val s = source(addon)
        s.search("Hello", null)
        assertEquals("down for a minute", s.lastError)
        addon.search = 200 to """{"tracks":[]}"""
        s.search("Hello", null)
        assertNull(s.lastError)
    }

    @Test fun `stream is asked first, for what it says about the address`() = runBlocking {
        val addon = Addon()
        val s = source(addon)
        val row = s.found().single()
        val stream = (s.stream(row.id, false) as Outcome.Ok).value
        assertEquals("https://cdn/asked.flac", stream.url)
        assertEquals(44100, stream.sampleRateHz)
        assertTrue(addon.asked.any { it.contains("/stream/t1") })
    }

    @Test fun `the row's own address is the fallback when stream has nothing`() = runBlocking {
        val addon = Addon().apply { stream = 404 to "{}" }
        val s = source(addon)
        val row = s.found().single()
        assertEquals("https://cdn/row.flac", (s.stream(row.id, false) as Outcome.Ok).value.url)
    }

    @Test fun `an addon that only searches plays from its rows`() = runBlocking {
        val addon = Addon("""{"id":"x","name":"Rows","resources":["search"]}""")
        val s = source(addon)
        val row = s.found().single()
        assertEquals("https://cdn/row.flac", (s.stream(row.id, false) as Outcome.Ok).value.url)
        assertFalse(addon.asked.any { it.contains("/stream/") })
    }

    @Test fun `one that asks for a lossless output isn't asked without one`() = runBlocking {
        val addon = Addon("""{"id":"q","name":"Qobuz","resources":["search","stream"],"checkValidLossless":1}""")
        val waiting = source(addon, lossless = false)
        assertTrue(waiting.manifest.requiresLosslessOutput)
        assertFalse(waiting.ready)
        val miss = waiting.search("Hello", null)
        assertIs<Outcome.Err>(miss)
        assertIs<SourceError.NotPlayable>(miss.error)
        assertNull(waiting.exactCopy(Track(MediaId("ytm:x"), "Hello", emptyList())))
        assertFalse(addon.asked.any { it.endsWith("/search") })
        val connected = source(addon, lossless = true)
        assertTrue(connected.ready)
        assertEquals(1, connected.found().size)
    }

    @Test fun `a row's cover is kept`() {
        val row = source(Addon()).found().single()
        assertEquals("https://img/x.jpg", row.artwork.url(600))
    }

    @Test fun `adding is asked again when the network let it down`() = runBlocking {
        val addon = Addon().apply { dropFirst = 1 }
        val r = AddonSource.connect("https://addon.example/a/manifest.json", addon.http)
        assertIs<Outcome.Ok<AddonSource>>(r)
        assertEquals(2, addon.asked.count { it.endsWith("/manifest.json") })
    }

    @Test fun `answers are read and closed off the thread that asked, as Android needs of the main thread`() {
        val touched = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val text = when {
                path.endsWith("/manifest.json") -> MANIFEST
                path.endsWith("/search") -> """{"tracks":[{"id":"t1","title":"Hello","artist":"Adele","duration":295}]}"""
                else -> """{"url":"https://cdn/asked.flac"}"""
            }
            // A body that says which thread read it and which closed it.
            val source = object : okio.ForwardingSource(okio.Buffer().writeUtf8(text)) {
                override fun read(sink: okio.Buffer, byteCount: Long): Long { touched += Thread.currentThread().name; return super.read(sink, byteCount) }
                override fun close() { touched += Thread.currentThread().name; super.close() }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("x")
                .body(source.buffer().asResponseBody("application/json".toMediaType(), text.length.toLong())).build()
        }.build()
        val caller = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "caller") }
        try {
            runBlocking(caller.asCoroutineDispatcher()) {
                val s = (AddonSource.connect("https://addon.example/a/manifest.json", http) as Outcome.Ok).value
                val row = (s.search("Hello", null) as Outcome.Ok).value.items.filterIsInstance<Track>().single()
                assertIs<Outcome.Ok<*>>(s.stream(row.id, false))
            }
        } finally {
            caller.shutdown()
        }
        assertTrue(touched.isNotEmpty())
        // In tests a thread's name carries its coroutine ("caller @coroutine#11").
        assertFalse(touched.any { it.startsWith("caller") }, "read or closed on the asking thread: $touched")
    }

    @Test fun `a refusal's words are read from either spelling`() {
        assertEquals("upstream down", AddonProtocol.errorText("""{"error":"upstream  down"}""".toByteArray()))
        assertEquals("nested", AddonProtocol.errorText("""{"error":{"message":"nested"}}""".toByteArray()))
        assertNull(AddonProtocol.errorText("<html>no</html>".toByteArray()))
        assertNotNull(AddonProtocol.errorText("""{"detail":"why"}""".toByteArray()))
    }

    private companion object {
        const val MANIFEST = """{"id":"dev.test","name":"Test","resources":["search","stream"],"settings":[{"key":"quality","default":"LOSSLESS","options":[{"value":"LOSSLESS"},{"value":"HIGH"}]}]}"""
    }
}
