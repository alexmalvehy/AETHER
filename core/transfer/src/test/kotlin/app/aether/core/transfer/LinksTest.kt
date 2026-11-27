package app.aether.core.transfer

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Public playlists by their links, read from the pages Spotify and Apple Music serve to anyone (the
 * shapes as fetched by AETHER, as itself, on 2026-11-16).
 */
class LinksTest {
    private fun ok(r: ImportRead): ImportedPlaylist = assertIs<ImportRead.Ok>(r, (r as? ImportRead.Failed)?.reason).library.playlists.single()
    private val nbsp = Char(0x00A0)

    @Test fun `a Spotify playlist is found in every shape of link people paste`() {
        val id = "37i9dQZF1DXcBWIGoYBM5M"
        for (link in listOf(
            "https://open.spotify.com/playlist/$id?si=abc123",
            "https://open.spotify.com/intl-de/playlist/$id",
            "https://open.spotify.com/embed/playlist/$id",
            "spotify:playlist:$id",
            id,
            "  https://open.spotify.com/playlist/$id  ",
        )) assertEquals(id, SpotifyLink.playlistId(link), link)
        assertNull(SpotifyLink.playlistId("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(SpotifyLink.playlistId("https://evil.example/playlist/$id"))
        assertTrue(SpotifyLink.isShortLink("https://spotify.link/AbCdEf"))
    }

    private fun embed(rows: List<String>, escaped: Boolean = false): String {
        val data = """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","name":"Today’s Top Hits","title":"Today’s Top Hits","subtitle":"Spotify","trackList":[${rows.joinToString(",")}]}}}}}}"""
        val body = if (escaped) data.replace("\"", "&quot;") else data
        return """<html><head><script id="__NEXT_DATA__" type="application/json" nonce="x">$body</script></head></html>"""
    }

    private fun row(id: Int, title: String, subtitle: String, explicit: Boolean = false, ms: Long = 200_000) =
        """{"uri":"spotify:track:${id.toString().padStart(22, 'a')}","title":"$title","subtitle":"$subtitle","isExplicit":$explicit,"duration":$ms,"entityType":"track"}"""

    @Test fun `a Spotify playlist's songs, in the playlist's order, each once`() {
        val p = ok(SpotifyLink.read(embed(listOf(
            row(1, "BbY WOW", "KAROL G,${nbsp}Judeline,${nbsp}rusowsky", ms = 225_834),
            row(2, "Housewife", "Tate McRae", explicit = true),
            row(3, "Tyler's", "Tyler, The Creator"),
            row(1, "BbY WOW", "KAROL G"),
        )), "37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("Today’s Top Hits", p.name)
        // LastWave's walk turned a playlist round; this one keeps its order.
        assertEquals(listOf("BbY WOW", "Housewife", "Tyler's"), p.items.map { it.title })
        assertEquals(listOf("KAROL G", "Judeline", "rusowsky"), p.items[0].artists)
        assertEquals(listOf("Tyler, The Creator"), p.items[2].artists, "a name with a plain comma in it stays whole")
        assertEquals(225_834L, p.items[0].durationMs)
        assertEquals(true, p.items[1].explicit)
        assertEquals("spotify:37i9dQZF1DXcBWIGoYBM5M", p.origin)
        assertFalse(p.partial)
    }

    @Test fun `a page whose data is HTML-escaped still reads, and the first hundred are said to be part`() {
        val p = ok(SpotifyLink.read(embed((1..100).map { row(it, "Song $it", "Artist") }, escaped = true), "37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals(100, p.items.size)
        assertTrue(p.partial, "the embed page stops at a hundred")
    }

    @Test fun `a private or missing playlist says so`() {
        assertIs<ImportRead.Failed>(SpotifyLink.read("<html>nothing</html>", "x"))
        assertIs<ImportRead.Failed>(SpotifyLink.read(embed(emptyList()), "x"))
    }

    @Test fun `an Apple Music playlist by its link`() {
        assertEquals("pl.f4d106fed2bd41149aaacabb233eb5eb", AppleMusicLink.playlistId("https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb"))
        assertEquals("pl.u-AkAmP4Xs8VMdQ", AppleMusicLink.playlistId("https://music.apple.com/in/playlist/pl.u-AkAmP4Xs8VMdQ?l=en"))
        assertNull(AppleMusicLink.playlistId("https://music.apple.com/us/album/housewife/6822497770"))
    }

    private fun lockup(n: Int, title: String, artists: List<String>, album: String, explicit: Boolean, ms: Long) =
        """{"id":"track-lockup - pl.x - $n","title":"$title","artistName":"${artists.joinToString(" & ")}","duration":$ms,"showExplicitBadge":$explicit,""" +
            """"subtitleLinks":[${artists.joinToString(",") { """{"title":"$it"}""" }}],"tertiaryLinks":[{"title":"$album"}]}"""

    @Test fun `Apple Music's page gives each song its artists one by one, the album and whether it's explicit`() {
        val data = """{"data":[{"data":{"sections":[{"id":"playlist-detail-header-section - pl.x","items":[{"id":"playlist-detail-header - pl.x","title":"Today’s Hits","trackCount":3}]},""" +
            """{"id":"track-list - pl.x","items":[${lockup(1, "Housewife", listOf("Tate McRae"), "Housewife - Single", false, 208_810)},${lockup(2, "Together", listOf("A", "B"), "Both - EP", true, 190_000)}]}]}}]}"""
        val html = """<script type="application/json" id=serialized-server-data>$data</script>"""
        val p = ok(AppleMusicLink.read(html, "pl.x"))
        assertEquals("Today’s Hits", p.name)
        assertEquals(listOf("Housewife", "Together"), p.items.map { it.title })
        assertEquals(listOf("A", "B"), p.items[1].artists)
        assertEquals("Housewife", p.items[0].album, "\"- Single\" is Apple's word for the kind, not the album's name")
        assertEquals(true, p.items[1].explicit)
        assertEquals(208_810L, p.items[0].durationMs)
        assertTrue(p.partial, "three songs said, two given")
        assertEquals("apple:pl.x", p.origin)
    }

    @Test fun `without the page's data, Apple's ld+json gives names and lengths`() {
        val html = """<script id="schema:music-playlist" type="application/ld+json">{"@type":"MusicPlaylist","name":"Mix","numTracks":1,"track":[{"@type":"MusicRecording","name":"Housewife","duration":"PT3M28S"}]}</script>"""
        val p = ok(AppleMusicLink.read(html, "pl.y"))
        assertEquals("Housewife", p.items.single().title)
        assertEquals(208_000L, p.items.single().durationMs)
        assertEquals(3_723_000L, AppleMusicLink.isoDuration("PT1H2M3S"))
    }
}
