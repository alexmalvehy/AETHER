package app.aether.sources.youtube

import app.aether.ytmusic.HttpResponse
import app.aether.ytmusic.HttpTransport
import app.aether.ytmusic.MediaKind
import app.aether.ytmusic.Ref
import app.aether.ytmusic.Song
import app.aether.ytmusic.YouTubeMusic
import app.aether.ytmusic.getOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
 * An album filed by its music videos plays as its songs. KR$NA's "Yours Truly" as YouTube Music answered
 * on 2026-11-19: 14 of its 15 rows are official videos, and one search of its Songs shelf for the album
 * gives the songs of 11 of them.
 */
class SongVersionsTest {
    private fun bytes(name: String) =
        GZIPInputStream(checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json.gz"))).use { it.readBytes() }

    /** YouTube Music as a guest: the album, its Songs shelf, and nothing found for any other search. */
    private inner class Service(val searchesFail: Boolean = false) : HttpTransport {
        val searched = mutableListOf<String>()
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            if (method == "GET") return HttpResponse(200, """{"INNERTUBE_CLIENT_VERSION":"1.20261111.01.00","INNERTUBE_CONTEXT_GL":"IN"}""".toByteArray())
            val asked = Json.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
            return when {
                "/browse" in url -> HttpResponse(200, bytes("album_yours_truly"))
                "/search" in url -> {
                    val query = asked["query"]!!.jsonPrimitive.content
                    searched += query
                    if (searchesFail) throw IOException("SocketTimeoutException")
                    HttpResponse(200, if (query == "Yours Truly KR\$NA") bytes("songs_yours_truly") else "{}".toByteArray())
                }
                else -> HttpResponse(404, "{}".toByteArray())
            }
        }
    }

    private fun music(service: Service) = YouTubeMusic(language = { "en" }, region = { "IN" }, transport = service)

    private val asSongs = mapOf(
        "kB-7ZB8hqEM" to "9t9B_j5LIHM", // Nothing to Prove
        "Yo7q7rdRs7U" to "BvqqY8_vPXE", // Knock Knock
        "uJmLJAdP00c" to "RSVLgzDqQ5Y", // Sensitive
        "Y_1NX7csC_w" to "66mHAF4DnIY", // Never Enough
        "156_7w_W5j0" to "LVlTuTvvFv0", // Buss Down
        "ELkfkoMiLxo" to "jPM_K1I4qak", // KKBN
        "NsF9S-n5sdI" to "KbJXAChagDM", // Talk My Shit/Guarantee
        "VTKiSD0FAvo" to "sRO7jpggzvE", // Hello
        "io866mRzR7c" to "BTff_oBREW4", // Vibrate
        "we3Tj42dlhk" to "5b6ePt5SMVA", // Who You Are
        "Prs3qcC91CY" to "nujrbxBYdkc", // Yours Truly
    )

    @Test fun `an album filed by its videos plays as its songs, where its songs are found`() = runBlocking {
        val service = Service()
        val yt = music(service)
        val filed = assertNotNull(yt.album("MPREb_rCFkGykwhLD").getOrNull())
        assertEquals(14, filed.tracks.count { it.kind == MediaKind.MUSIC_VIDEO })
        val album = SongVersions(yt).songsOf(filed)

        assertEquals(filed.tracks.size, album.tracks.size)
        filed.tracks.zip(album.tracks).forEach { (was, now) ->
            val song = asSongs[was.videoId]
            if (song != null) {
                assertEquals(song, now.videoId, "${was.title} plays as its song")
                assertEquals(MediaKind.SONG, now.kind)
                assertEquals(was.trackNumber, now.trackNumber, "in its place on the album")
                assertEquals(was.playlistId, now.playlistId, "and plays on in the album")
            } else {
                assertEquals(was, now, "${was.title} stays as it was")
            }
        }
        assertEquals(album.tracks.size, album.tracks.map { it.videoId }.distinct().size, "no song twice")
        // The skits weren't on the album's shelf: each asked for alone, by its title and artist, then its title.
        assertEquals("Yours Truly KR\$NA", service.searched.first())
        assertTrue(service.searched.containsAll(listOf("VOTP SKIT 1 KR\$NA", "VOTP SKIT 1", "VOTP SKIT 4 KR\$NA")))
    }

    @Test fun `asked again, it asks nothing`() = runBlocking {
        val service = Service()
        val yt = music(service)
        val versions = SongVersions(yt)
        val filed = assertNotNull(yt.album("MPREb_rCFkGykwhLD").getOrNull())
        val first = versions.songsOf(filed)
        service.searched.clear()
        assertEquals(first, versions.songsOf(filed))
        assertTrue(service.searched.isEmpty(), "asked: ${service.searched}")
    }

    @Test fun `searches that don't answer leave the album as it was, and are asked again`() = runBlocking {
        val service = Service(searchesFail = true)
        val yt = music(service)
        val versions = SongVersions(yt)
        val filed = assertNotNull(yt.album("MPREb_rCFkGykwhLD").getOrNull())
        assertEquals(filed, versions.songsOf(filed))
        val asked = service.searched.size
        assertTrue(asked > 0)
        versions.songsOf(filed)
        assertTrue(service.searched.size > asked, "nothing was remembered from searches that didn't answer")
    }

    // --- What counts as the song --------------------------------------------------------------------

    private val krsna = listOf(Ref("KR\$NA", null))

    private fun song(id: String, title: String, artists: String, album: String?, seconds: Int, explicit: Boolean = false) =
        Song(id, title, artists.split(" & ").map { Ref(it, null) }, album?.let { Ref(it, null) }, seconds * 1000L, explicit = explicit, kind = MediaKind.SONG)

    @Test fun `the song it is, with or without its featured artists, wherever it was released`() {
        val found = SongVersions.songFor("Roll Up", krsna, 250_000, false, listOf(song("a", "Roll Up (feat. Badshah)", "KR\$NA", "Roll Up", 196)), "Still Here")
        assertEquals("a", found?.videoId)
    }

    @Test fun `never a remix, a live take, or another artist's song of the name`() {
        val candidates = listOf(
            song("remix", "Nothing to Prove (Remix)", "KR\$NA", "Yours Truly (Remixes)", 200),
            song("live", "Nothing to Prove (Live)", "KR\$NA", null, 210),
            song("other", "Nothing to Prove", "Someone Else", "Elsewhere", 201),
        )
        assertNull(SongVersions.songFor("Nothing to Prove (Official Video)", krsna, 230_000, false, candidates, "Yours Truly"))
    }

    @Test fun `the album's own before a single of the same song`() {
        val candidates = listOf(song("single", "Knock Knock", "KR\$NA", "Knock Knock", 207), song("album", "Knock Knock", "KR\$NA & Phenom", "Yours Truly", 207))
        assertEquals("album", SongVersions.songFor("Knock Knock", krsna, 231_000, false, candidates, "Yours Truly")?.videoId)
    }

    @Test fun `an intro only from its own album`() {
        val elsewhere = listOf(song("x", "Intro", "KR\$NA", "Sellout", 90))
        assertNull(SongVersions.songFor("Intro", krsna, 95_000, false, elsewhere, "Still Here"))
        val here = listOf(song("y", "Intro", "KR\$NA", "Still Here", 93))
        assertEquals("y", SongVersions.songFor("Intro", krsna, 95_000, false, here, "Still Here")?.videoId)
    }

    @Test fun `as explicit as the video, then as long`() {
        val candidates = listOf(song("clean", "Hello", "KR\$NA", "Yours Truly", 145), song("explicit", "Hello", "KR\$NA", "Yours Truly", 146, explicit = true))
        assertEquals("explicit", SongVersions.songFor("Hello", krsna, 146_000, true, candidates, "Yours Truly")?.videoId)
        assertEquals("clean", SongVersions.songFor("Hello", krsna, 146_000, false, candidates, "Yours Truly")?.videoId)
    }
}
