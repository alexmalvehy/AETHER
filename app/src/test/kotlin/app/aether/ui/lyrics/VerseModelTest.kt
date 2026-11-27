package app.aether.ui.lyrics

import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.LyricsSyllable
import app.aether.core.lyrics.LyricsWord
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerseModelTest {
    private fun word(start: Long, text: String, end: Long? = null, syllables: List<LyricsSyllable> = emptyList()) = LyricsWord(start, text, end, syllables)

    /** A stand-in for the phone's Latin letters: each script word to its reading, anything else left alone. */
    private val reading = mapOf("사랑해" to "saranghae", "너를" to "neoreul", "我" to "wǒ", "爱" to "ài", "你" to "nǐ", "오" to "o")
    private val romanise: (String) -> String? = { reading[it] }

    @Test
    fun `Latin first, each word is sung in Latin letters over its own time`() {
        val line = LyricsLine(1_000, "너를 사랑해", words = listOf(word(1_000, "너를", 1_400), word(1_500, "사랑해", 2_400)))
        val v = VLine.of(listOf(line)).single()
        val latin = VLine.latin(v, "neoreul saranghae", romanise)
        assertEquals("neoreul saranghae", latin.text)
        assertEquals(listOf("neoreul", "saranghae"), latin.words.map { it.text })
        assertEquals(listOf(1_000L to 1_400L, 1_500L to 2_400L), latin.words.map { it.startMs to it.endMs })
        assertTrue(latin.isWordSynced)
        // Half way through the second word, half of "saranghae" is lit, after all of "neoreul " .
        assertEquals("neoreul ".length + "saranghae".length / 2f, latin.revealedChars(1_950), 0.01f)
        assertEquals(v.timeMs, latin.timeMs)
        assertEquals(v.endMs, latin.endMs)
    }

    @Test
    fun `Latin first, the words in their own script go beneath, lit as the Latin letters are`() {
        val line = LyricsLine(0, "我爱你", words = listOf(word(0, "我", 300), word(300, "爱", 600), word(600, "你", 900)))
        val latin = VLine.latin(VLine.of(listOf(line)).single(), "wǒ ài nǐ", romanise)
        assertEquals("wǒ ài nǐ", latin.text)
        val beneath = VLine.gloss("我爱你", latin)
        // When "ài" is done, two of the three characters beneath are lit.
        val done = latin.revealedChars(600) / latin.text.length
        assertEquals(done * 3, beneath.revealedChars(600), 0.01f)
    }

    @Test
    fun `Latin first, a word with nothing to change keeps its own letters, and punctuation keeps to its word`() {
        val line = LyricsLine(0, "오 baby ,", words = listOf(word(0, "오", 300), word(300, "baby", 600), word(600, ",", 700)))
        val latin = VLine.latin(VLine.of(listOf(line)).single(), "o baby,", romanise)
        assertEquals("o baby,", latin.text)
        assertEquals(listOf("o", "baby", ","), latin.words.map { it.text })
        assertEquals(listOf(0 until 1, 2 until 6, 6 until 7), latin.wordSpans)
    }

    @Test
    fun `Latin first, a line timed whole is the line's Latin letters, lit whole`() {
        val line = LyricsLine(1_000, "사랑해")
        val latin = VLine.latin(VLine.of(listOf(line)).single(), "saranghae", romanise)
        assertEquals("saranghae", latin.text)
        assertTrue(latin.words.isEmpty())
        assertEquals("saranghae".length.toFloat(), latin.revealedChars(1_000))
    }

    @Test
    fun `Latin first, the answering vocal is given in Latin letters too`() {
        val line = LyricsLine(0, "너를", words = listOf(word(0, "너를", 500)), background = LyricsLine(200, "(사랑해)", words = listOf(word(200, "(사랑해)", 800))))
        val latin = VLine.latin(VLine.of(listOf(line)).single(), "neoreul", romanise)
        assertEquals("saranghae", latin.background?.text)
        assertEquals(listOf(200L to 800L), latin.background?.words?.map { it.startMs to it.endMs })
    }

    @Test
    fun `Chinese sung a character at a time keeps its own text, with no spaces put in`() {
        val line = LyricsLine(1_000, "我爱你", words = listOf(word(1_000, "我", 1_300), word(1_300, "爱", 1_600), word(1_600, "你", 2_000)))
        val v = VLine.of(listOf(line)).single()
        assertEquals("我爱你", v.text)
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), v.wordSpans)
        assertTrue(v.isWordSynced)
    }

    @Test
    fun `the sweep crosses a word over its own time and the space after it over the pause`() {
        val line = LyricsLine(0, "hold on", words = listOf(word(0, "hold", 400), word(600, "on", 1_000)))
        val v = VLine.of(listOf(line)).single()
        assertEquals(0f, v.revealedChars(0))
        assertEquals(2f, v.revealedChars(200))
        assertEquals(4.5f, v.revealedChars(500))
        assertEquals(5f, v.revealedChars(600))
        assertEquals(7f, v.revealedChars(1_000))
    }

    @Test
    fun `a syllable fills its own letters over its own time`() {
        val line = LyricsLine(0, "golden", words = listOf(word(0, "golden", 1_000, listOf(LyricsSyllable(0, 800, 0, 3), LyricsSyllable(800, 1_000, 3, 6)))))
        val v = VLine.of(listOf(line)).single()
        assertEquals(1.5f, v.revealedChars(400))
        assertEquals(4.5f, v.revealedChars(900))
        assertEquals(listOf(0 until 3, 3 until 6), v.sweepSpans)
    }

    @Test
    fun `a long silence after a line that says when it ends is a break, and so is a long intro`() {
        val lines = listOf(
            LyricsLine(5_000, "first", words = listOf(word(5_000, "first", 6_000))),
            LyricsLine(7_000, "second", words = listOf(word(7_000, "second", 8_000))),
            LyricsLine(20_000, "third", words = listOf(word(20_000, "third", 21_000))),
        )
        val rows = VLine.of(lines)
        assertEquals(listOf("", "first", "second", "", "third"), rows.map { it.text })
        assertEquals(0L, rows[0].timeMs)
        // The break starts as the singing stops, not when the next line is due.
        assertEquals(8_000L, rows[3].timeMs)
        assertEquals(listOf(-1, 0, 1, -1, 2), rows.map { it.origin })
    }

    @Test
    fun `a bare stamp is a break only where it lasts`() {
        val lines = listOf(
            LyricsLine(0, "one"),
            LyricsLine(2_000, ""),
            LyricsLine(3_000, "two"),
            LyricsLine(4_000, ""),
            LyricsLine(12_000, "three"),
        )
        val rows = VLine.of(lines)
        assertEquals(listOf("one", "two", "", "three"), rows.map { it.text })
        // Timed by the line alone: nothing says when "one" ends, so no break is made after it.
        assertTrue(rows.none { it.isGap && it.origin == -1 })
    }

    @Test
    fun `the answering vocal loses its brackets and keeps its timing`() {
        val backing = LyricsLine(1_000, "(oh yeah)", words = listOf(word(1_000, "(oh", 1_400, listOf(LyricsSyllable(1_000, 1_400, 1, 3))), word(1_400, "yeah)", 2_000)))
        val line = LyricsLine(0, "say it", words = listOf(word(0, "say", 400), word(400, "it", 900)), background = backing)
        val v = VLine.of(listOf(line)).single()
        val b = v.background!!
        assertEquals("oh yeah", b.text)
        assertEquals(listOf("oh", "yeah"), b.words.map { it.text })
        // "(oh" timed "oh" as chars 1..3; without the bracket that's 0..2.
        assertEquals(0, b.words[0].syllables.single().startChar)
        assertEquals(2, b.words[0].syllables.single().endChar)
        // The row lasts as long as its answering voice.
        assertEquals(2_000L, v.endMs)
    }

    @Test
    fun `Latin letters under a line light as far through as the line has got`() {
        val line = VLine.of(listOf(LyricsLine(0, "사랑해", words = listOf(word(0, "사랑해", 900))))).single()
        val gloss = VLine.gloss("saranghae", line)
        assertEquals(0f, gloss.revealedChars(0))
        assertEquals(3f, gloss.revealedChars(300))
        assertEquals(9f, gloss.revealedChars(900))
        assertTrue(gloss.isWordSynced)
    }

    @Test
    fun `lines sung over each other are both being sung`() {
        val rows = VLine.of(
            listOf(
                LyricsLine(0, "lead", words = listOf(word(0, "lead", 3_000))),
                LyricsLine(1_000, "answer", words = listOf(word(1_000, "answer", 2_000)), opposite = true),
            ),
        )
        assertEquals(listOf(0, 1), activeRows(rows, 1_500))
        assertEquals(listOf(0), activeRows(rows, 500))
        assertEquals(listOf(1), activeRows(rows, 3_500))
    }

    @Test
    fun `only a word held long enough swells, and never one in a script whose letters stand as blocks`() {
        val held = VLine.of(listOf(LyricsLine(0, "oh", words = listOf(word(0, "oh", 2_000))))).single()
        val quick = VLine.of(listOf(LyricsLine(0, "oh", words = listOf(word(0, "oh", 300))))).single()
        val korean = VLine.of(listOf(LyricsLine(0, "사랑", words = listOf(word(0, "사랑", 3_000))))).single()
        assertEquals(1, held.growingWords.size)
        assertTrue(quick.growingWords.isEmpty())
        assertTrue(korean.growingWords.isEmpty())
    }
}
