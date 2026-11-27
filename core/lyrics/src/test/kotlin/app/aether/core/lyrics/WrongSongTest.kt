package app.aether.core.lyrics

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Another song's words never stand in for this one's. KuGou, asked for two lesser songs that share a
 * famous title, answered other recordings' files (measured 2026-11-19): for Pennyy's "Espresso" its
 * lyrics search, asked for Pennyy's own recording, gave Tchaikovsky's "June"; for Kizo's, a remix by
 * someone else and "Spice Up Your Life". Its answers here are those, trimmed to what's read.
 */
class WrongSongTest {

    /** KuGou as it answered: each address part to its body; anything else a 404. */
    private class KuGouAnswers(private val routes: List<Pair<String, String>>) : LyricsHttp {
        val asked = mutableListOf<String>()
        override suspend fun get(url: String): Pair<Int, String> {
            asked += url
            return routes.firstOrNull { url.contains(it.first) }?.let { 200 to it.second } ?: (404 to "")
        }
    }

    private fun lrc(vararg lines: String) = """{"content":"${Base64.getEncoder().encodeToString(lines.joinToString("\n").toByteArray())}"}"""

    private fun song(hash: String, title: String, artist: String, seconds: Int) =
        """{"hash":"$hash","songname":"$title","singername":"$artist","album_name":"","duration":$seconds}"""

    private fun file(id: String, title: String, artist: String, ms: Long) =
        """{"id":"$id","accesskey":"k$id","song":"$title","singer":"$artist","duration":$ms}"""

    @Test fun `asked for Pennyy's Espresso, it never sings Tchaikovsky's June`() = runTest {
        val http = KuGouAnswers(
            listOf(
                "api/v3/search/song" to """{"data":{"info":[${song("pennyy", "Espresso", "Pennyy", 440)},${song("sabrina", "Espresso (Explicit)", "Sabrina Carpenter", 175)}]}}""",
                "search?ver=1&man=yes&client=pc&hash=pennyy" to """{"candidates":[${file("9", "The Seasons Op. 37b: June - Barcarole", "arkady sevidov", 320317)}]}""",
                "download" to lrc("[00:01.00]某一行", "[00:05.00]另一行"),
            ),
        )
        val answer = KuGou(http).lookup(LyricsRequest("p", "Espresso", "Pennyy", null, 440_000))
        assertEquals(ProviderAnswer.NotFound, answer)
        assertTrue(http.asked.none { "download" in it }, "nothing of another recording's was even fetched")
    }

    @Test fun `asked for Kizo's Espresso, neither the remix of the name nor Spice Up Your Life`() = runTest {
        val http = KuGouAnswers(
            listOf(
                "api/v3/search/song" to """{"data":{"info":[${song("kizo", "Espresso (Explicit)", "Kizo", 129)},${song("touont", "Espresso (Remix)", "Touont", 128)}]}}""",
                "hash=touont" to """{"candidates":[${file("2", "Espresso", "Touont", 128679)}]}""",
                "keyword=" to """{"candidates":[${file("3", "Spice Up Your Life", "Spice Girls", 130000)}]}""",
                "download" to lrc("[00:01.00]Colours of the world", "[00:03.00]Spice up your life"),
            ),
        )
        val answer = KuGou(http).lookup(LyricsRequest("k", "Espresso", "Kizo", null, 130_000))
        assertEquals(ProviderAnswer.NotFound, answer)
        assertTrue(http.asked.none { "hash=touont" in it }, "a remix by someone else isn't asked about")
    }

    @Test fun `this song's own file is still taken`() = runTest {
        val http = KuGouAnswers(
            listOf(
                "api/v3/search/song" to """{"data":{"info":[${song("kizo", "Espresso (Explicit)", "Kizo", 129)}]}}""",
                "hash=kizo" to """{"candidates":[${file("1", "Espresso", "Kizo", 129400)}]}""",
                "download" to lrc("[00:12.00]Codziennie mówię, że ostatni buszek", "[00:15.00]W studiu znów jakieś kawałki kleję", "[00:19.00]Trzecia linijka"),
            ),
        )
        val answer = KuGou(http).lookup(LyricsRequest("k", "Espresso", "Kizo", null, 130_000))
        val lyrics = assertIs<Lyrics.Synced>(assertIs<ProviderAnswer.Found>(answer).lyrics)
        assertEquals("Codziennie mówię, że ostatni buszek", lyrics.lines.first { it.text.isNotBlank() }.text)
    }

    /** KuGou finding [title] by [artist] (as it files them), with one file of its own for it. */
    private fun filed(title: String, artist: String, seconds: Int) = KuGouAnswers(
        listOf(
            "api/v3/search/song" to """{"data":{"info":[${song("h", title, artist, seconds)}]}}""",
            "hash=h" to """{"candidates":[${file("1", title, artist, seconds * 1000L)}]}""",
            "download" to lrc("[00:10.00]First line", "[00:14.00]Second line", "[00:18.00]Third line"),
        ),
    )

    @Test fun `a name filed with its other name in brackets is still that artist`() = runTest {
        val answer = KuGou(filed("봄날", "BTS (防弹少年团)", 274)).lookup(LyricsRequest("b", "봄날", "BTS", null, 274_000))
        assertIs<ProviderAnswer.Found>(answer)
    }

    @Test fun `a Chinese name written in the other script is still that artist`() = runTest {
        val answer = KuGou(filed("晴天", "周杰伦", 269)).lookup(LyricsRequest("j", "晴天", "周杰倫", null, 269_000))
        assertIs<ProviderAnswer.Found>(answer)
    }

    @Test fun `another Chinese artist's song of the same name is not`() = runTest {
        val answer = KuGou(filed("晴天", "孙燕姿", 269)).lookup(LyricsRequest("j", "晴天", "周杰倫", null, 269_000))
        assertEquals(ProviderAnswer.NotFound, answer)
    }

    @Test fun `a library that wants a key for this song hasn't it, rather than having failed`() = runTest {
        val keyed = BetterLyrics { 401 to """{"error":"API key required"}""" }
        assertEquals(ProviderAnswer.NotFound, keyed.lookup(LyricsRequest("i", "I Guess", "KR\$NA", null, 180_000)))
    }

    // --- Lines at a file's head that aren't sung ---

    private fun line(ms: Long, text: String, words: Boolean = false) =
        LyricsLine(ms, text, words = if (words) text.split(' ').mapIndexed { i, w -> LyricsWord(ms + i * 100L, w, ms + i * 100L + 90) } else emptyList())

    private fun shown(vararg lines: LyricsLine, title: String? = null) =
        assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(Lyrics.Synced(lines.toList()), 240_000, title)).lines

    @Test fun `the writers' names at the head are a pause, not a line`() {
        val lines = shown(
            line(2_494, "Mikky Ekko/Justin Parker/Elof Loelv", words = true),
            line(17_872, "All along it was a fever", words = true),
            line(21_000, "A cold sweat, hot-headed believer", words = true),
        )
        assertEquals("", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
        assertEquals(2_494, lines[0].startMs, "its time is kept, as a pause")
        assertEquals("All along it was a fever", lines[1].text)
    }

    @Test fun `the song's own name at the head is a pause`() {
        val lines = shown(
            line(0, "Tum Hi Ho - Arijit Singh (Aashiqui 2)"),
            line(14_000, "Hum tere bin ab reh nahi sakte"),
            line(18_000, "Tere bina kya wajood mera"),
            title = "Tum Hi Ho",
        )
        assertEquals("", lines[0].text)
        assertEquals("Hum tere bin ab reh nahi sakte", lines[1].text)
    }

    @Test fun `a credit's own label at the head is a pause`() {
        val lines = shown(line(500, "作词 : 方文山"), line(900, "Written by: Somebody"), line(15_000, "The first line"), line(18_000, "The second line"))
        assertEquals(listOf("", "", "The first line", "The second line"), lines.map { it.text })
    }

    @Test fun `words that only look like credits are sung`() {
        // Words with slashes, a line naming the song later on, a song that begins on its own name.
        val lines = shown(
            line(1_000, "Love/hate"),
            line(4_000, "Up/Down"),
            line(8_000, "Andrew Watt/John Ryan"),
            title = "Love/hate",
        )
        assertEquals(listOf("Love/hate", "Up/Down", "Andrew Watt/John Ryan"), lines.map { it.text })
        val named = shown(line(12_000, "Hello - it's me"), line(15_000, "I was wondering"), title = "Hello")
        assertEquals("Hello - it's me", named[0].text, "a line naming the song, sung after the start, is the song")
    }

    @Test fun `a file that is all heading keeps its lines rather than showing none`() {
        val lines = shown(line(0, "Song - Artist"), line(3_000, "Only line"), title = "Song")
        assertEquals(listOf("Song - Artist", "Only line"), lines.map { it.text })
    }
}
