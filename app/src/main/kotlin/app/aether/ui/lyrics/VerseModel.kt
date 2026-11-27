// Ported from BitChord's lyric line model (shared/data/lyrics/LyricLine.kt, GPL-3.0): the arithmetic of
// the sweep, the lift and the letter-by-letter swell, unchanged; built from AETHER's own lyrics.
package app.aether.ui.lyrics

import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.LyricsWord

/** One timed piece of a word: its characters [startChar] until [endChar], sung from [startMs] to [endMs]. */
internal class VSyllable(val startMs: Long, val endMs: Long, val startChar: Int, val endChar: Int)

/** One word of a line, with the stretch of the song it's sung over, and its syllables where they were timed. */
internal class VWord(val startMs: Long, val endMs: Long, val text: String, val syllables: List<VSyllable>) {
    /** How many of [text]'s characters have been sung at [positionMs]: each syllable fills its own over its own time. */
    fun charsSungAt(positionMs: Long): Float {
        if (positionMs <= startMs) return 0f
        if (positionMs >= endMs) return text.length.toFloat()
        if (syllables.isEmpty()) return (positionMs - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1L) * text.length
        for (s in syllables) {
            if (positionMs < s.startMs) return s.startChar.toFloat()
            if (positionMs < s.endMs) return s.startChar + (positionMs - s.startMs).toFloat() / (s.endMs - s.startMs).coerceAtLeast(1L) * (s.endChar - s.startChar)
        }
        return text.length.toFloat()
    }
}

/**
 * A line as Verse draws it (BitChord's LyricLine): sung from [timeMs]; an empty [text] is a break.
 * [words] only where the library timed them: a line timed whole lights whole, as BitChord's do.
 * [origin] is which of the song's lines it is (-1 for a break Verse marks itself); a line drawn under
 * another, its Latin letters, [follows] that one and lights as it does.
 */
internal class VLine(
    val timeMs: Long,
    val text: String,
    val words: List<VWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: VLine? = null,
    val alignEnd: Boolean = false,
    val origin: Int = -1,
    val follows: VLine? = null,
) {
    val isGap: Boolean get() = text.isEmpty()
    val isWordSynced: Boolean get() = words.isNotEmpty() || follows?.isWordSynced == true

    /** Where each word sits in [text], walked rather than searched, so a repeated word lines up with itself. */
    val wordSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        var offset = 0
        words.map { w ->
            val start = text.indexOf(w.text, offset).takeIf { it >= 0 } ?: offset
            val end = start + w.text.length
            offset = end
            start until end
        }
    }

    /** Each separately timed piece of the line: a word's syllables where it was split, the word itself where not. */
    val sweepSpans: List<IntRange> by lazy(LazyThreadSafetyMode.NONE) {
        words.flatMapIndexed { i, w ->
            val span = wordSpans[i]
            if (w.syllables.isEmpty()) listOf(span) else w.syllables.map { (span.first + it.startChar) until (span.first + it.endChar) }
        }
    }

    /** Whether anything on this line is off the floor at [positionMs]. */
    fun isLifted(positionMs: Long): Boolean {
        val first = words.firstOrNull() ?: return false
        if (positionMs <= first.startMs) return false
        return positionMs < words.last().endMs + RISE_MS || isGrowing(positionMs)
    }

    /** From when, and until when, anything drawn for this line moves: outside it, it's drawn the same on every frame. */
    val animatesFromMs: Long by lazy(LazyThreadSafetyMode.NONE) {
        follows?.animatesFromMs ?: minOf(timeMs, words.firstOrNull()?.startMs ?: timeMs)
    }
    val animatesUntilMs: Long by lazy(LazyThreadSafetyMode.NONE) {
        follows?.animatesUntilMs ?: run {
            val settled = words.lastOrNull()?.let { it.endMs + RISE_MS.toLong() } ?: endMs
            val grown = growingWords.maxOfOrNull { it.restsAtMs } ?: Long.MIN_VALUE
            maxOf(endMs, settled, grown)
        }
    }

    /** How far word [index] has lifted, 0..1: a held word higher, a quick one barely, rising over its own length. */
    fun wordLift(index: Int, positionMs: Long): Float {
        val w = words.getOrNull(index) ?: return 0f
        val riseMs = (w.endMs - w.startMs).toFloat().coerceIn(RISE_MIN_MS, RISE_MS)
        val rising = ((positionMs - w.startMs) / riseMs).coerceIn(0f, 1f)
        val falling = (1f - (positionMs - w.endMs) / RISE_MS).coerceIn(0f, 1f)
        return liftHeight(w) * smooth(minOf(rising, falling))
    }

    /** Whether the line itself says when its singing stops (the gap to the next line's start isn't evidence). */
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null || follows?.hasKnownEnd == true

    /** When the last word finishes (the answering vocal counted), or the line's own end, or its start. */
    val endMs: Long
        get() {
            follows?.let { return it.endMs }
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }

    /**
     * How far through the line the singing has got, as a fractional index into [text]: within a word
     * across its own span (a held note draws slowly, a quick one snaps); the space after a word fills
     * over the pause before the next rather than jumping ahead of its first letter.
     */
    fun revealedChars(positionMs: Long): Float {
        follows?.let { source ->
            if (source.text.isEmpty()) return 0f
            return (source.revealedChars(positionMs) / source.text.length).coerceIn(0f, 1f) * text.length
        }
        if (words.isEmpty()) return if (positionMs >= timeMs) text.length.toFloat() else 0f
        val spans = wordSpans
        for (index in words.indices) {
            val w = words[index]
            val span = spans[index]
            if (positionMs < w.startMs) return span.first.toFloat()
            if (positionMs < w.endMs) return span.first + w.charsSungAt(positionMs)
            val next = words.getOrNull(index + 1)
            if (next != null && positionMs < next.startMs) {
                val after = span.last + 1
                val pause = (next.startMs - w.endMs).coerceAtLeast(1L)
                return after + (positionMs - w.endMs).toFloat() / pause * (spans[index + 1].first - after)
            }
        }
        return text.length.toFloat()
    }

    /** How much of word [index]'s lift is left at [positionMs]: all of it while sung, easing to nothing after. */
    fun wordFall(index: Int, positionMs: Long): Float {
        val w = words.getOrNull(index) ?: return 0f
        val falling = (1f - (positionMs - w.endMs) / RISE_MS).coerceIn(0f, 1f)
        return liftHeight(w) * smooth(falling)
    }

    /** The words held long enough to swell a letter at a time. */
    val growingWords: List<GrowingWord> by lazy(LazyThreadSafetyMode.NONE) {
        words.mapIndexedNotNull { i, w -> if (w.canGrow()) GrowingWord(i, w) else null }
    }

    fun growingAt(index: Int): GrowingWord? = growingWords.firstOrNull { it.index == index }

    fun isGrowing(positionMs: Long): Boolean = growingWords.any { positionMs >= it.startMs && positionMs <= it.restsAtMs }

    companion object {
        /**
         * AETHER's lines as Verse draws them, with BitChord's breaks: a break the words mark themselves
         * (a bare stamp) is kept where it's at least [MIN_GAP_MS] long, and one is marked wherever a
         * line that says when it ends is followed by that much silence, and before a long intro.
         */
        fun of(lines: List<LyricsLine>): List<VLine> {
            val sung = ArrayList<VLine>(lines.size + 4)
            lines.forEachIndexed { i, line ->
                val next = lines.getOrNull(i + 1)?.startMs
                if (line.text.isBlank() && line.words.isEmpty()) {
                    // The last stamp closes the last line off: that's the outro.
                    if (next == null || next - line.startMs >= MIN_GAP_MS) sung += VLine(line.startMs, "", origin = i)
                } else {
                    sung += line(line, i, lines.drop(i + 1).firstOrNull { it.text.isNotBlank() }?.startMs)
                }
            }
            if (sung.isEmpty()) return sung
            val out = ArrayList<VLine>(sung.size + 4)
            if (!sung.first().isGap && sung.first().timeMs >= MIN_GAP_MS) out += VLine(0L, "")
            sung.forEachIndexed { i, line ->
                out += line
                val next = sung.getOrNull(i + 1) ?: return@forEachIndexed
                if (line.isGap || next.isGap || !line.hasKnownEnd) return@forEachIndexed
                // A break sharing its line's start could never be reached: the line would never light.
                if (next.timeMs - line.endMs >= MIN_GAP_MS && line.endMs > line.timeMs) out += VLine(line.endMs, "")
            }
            return out
        }

        /** The Latin letters of [line], drawn under it and lit as it is (or, Latin first, its own words under its Latin letters). */
        fun gloss(text: String, line: VLine): VLine = VLine(line.timeMs, text, alignEnd = line.alignEnd, follows = line)

        /**
         * [line] sung in Latin letters: each of its timed words given in Latin letters ([romanise]: null for
         * a word with nothing to change) and sung over that word's own time, so the Latin letters light, lift
         * and swell word by word as its own words do. A word's syllables are its own script's and don't
         * carry over. A line timed whole is [whole], the line's Latin letters lit whole. The answering vocal
         * goes the same way.
         */
        fun latin(line: VLine, whole: String, romanise: (String) -> String?): VLine {
            val words = line.words.map { w -> VWord(w.startMs, w.endMs, romanise(w.text) ?: w.text, emptyList()) }
            return VLine(
                timeMs = line.timeMs,
                text = if (words.isEmpty()) whole else joined(words),
                words = words,
                sungUntilMs = line.sungUntilMs,
                background = line.background?.let { b -> latin(b, romanise(b.text) ?: b.text, romanise) },
                alignEnd = line.alignEnd,
                origin = line.origin,
            )
        }

        /** Latin words with a space between them, and none before a mark that closes onto the word before. */
        private fun joined(words: List<VWord>): String = buildString {
            words.forEachIndexed { i, w ->
                if (i > 0 && w.text.first() !in CLOSING) append(' ')
                append(w.text)
            }
        }

        private const val CLOSING = ",.!?;:)]}…、。，！？"

        /**
         * One line: its own text (the spacing and punctuation between the words are the line's, and
         * Chinese and Japanese have none), the words where the library timed them, each ending where
         * it says or where the next begins.
         */
        private fun line(line: LyricsLine, origin: Int, next: Long?): VLine {
            val words = line.words.mapIndexedNotNull { k, w ->
                val text = w.text.trim()
                if (text.isEmpty()) return@mapIndexedNotNull null
                val lead = w.text.length - w.text.trimStart().length
                val following = line.words.getOrNull(k + 1)?.startMs
                val end = w.endMs ?: following ?: line.endMs ?: next ?: (w.startMs + LAST_WORD_MS)
                VWord(
                    w.startMs, maxOf(w.startMs + 1, end), text,
                    w.syllables.mapNotNull { s ->
                        val a = (s.startChar - lead).coerceIn(0, text.length)
                        val b = (s.endChar - lead).coerceIn(0, text.length)
                        if (b > a) VSyllable(s.startMs, maxOf(s.startMs + 1, s.endMs), a, b) else null
                    },
                )
            }
            val text = when {
                words.isEmpty() -> line.text.trim()
                inOrder(line.text, words) -> line.text
                else -> words.joinToString(if (' ' in line.text) " " else "") { it.text }
            }
            return VLine(
                timeMs = line.startMs,
                text = text,
                words = words,
                sungUntilMs = line.endMs,
                background = line.background?.let(::backing),
                alignEnd = line.opposite,
                origin = origin,
            )
        }

        /** Whether every word is found in [text], one after another. */
        private fun inOrder(text: String, words: List<VWord>): Boolean {
            var offset = 0
            for (w in words) {
                val at = text.indexOf(w.text, offset)
                if (at < 0) return false
                offset = at + w.text.length
            }
            return true
        }

        /**
         * The answering vocal without the brackets a library wraps it in: it has a line of its own,
         * which is what they marked. Each syllable keeps its own letters.
         */
        private fun backing(line: LyricsLine): VLine? {
            val words = line.words.mapNotNull { it.bare() }
            val text = line.text.filter { it != '(' && it != ')' }.trim()
            if (text.isEmpty() && words.isEmpty()) return null
            return line(line.copy(text = text, words = words, background = null), -1, null)
        }

        private fun LyricsWord.bare(): LyricsWord? {
            if ('(' !in text && ')' !in text) return this
            val place = IntArray(text.length + 1)
            var kept = 0
            for (i in text.indices) {
                place[i] = kept
                if (text[i] != '(' && text[i] != ')') kept++
            }
            place[text.length] = kept
            val bare = text.filter { it != '(' && it != ')' }
            if (bare.isBlank()) return null
            return copy(
                text = bare,
                syllables = syllables.mapNotNull { s ->
                    val a = place[s.startChar.coerceIn(0, text.length)]
                    val b = place[s.endChar.coerceIn(0, text.length)]
                    if (b > a) s.copy(startChar = a, endChar = b) else null
                },
            )
        }
    }
}

/** Shorter instrumental breaks aren't worth interrupting the page for. */
internal const val MIN_GAP_MS = 4_000L

/** The last word of a line nothing says the end of is held this long. */
private const val LAST_WORD_MS = 600L

/** The rows being sung at [positionMs]: the latest to start, and any earlier one still sounding (a duet overlaps). */
internal fun activeRows(lines: List<VLine>, positionMs: Long): List<Int> {
    val latest = lines.indexOfLast { it.timeMs <= positionMs }
    if (latest < 0) return emptyList()
    return (0..latest).filter { i ->
        val l = lines[i]
        i == latest || (!l.isGap && (l.hasKnownEnd || l.background?.hasKnownEnd == true) && l.timeMs <= positionMs && positionMs < l.endMs)
    }
}

/**
 * One word held long enough to swell a letter at a time: each letter runs the same move (swell up and
 * forward, hold, settle back to the small lift every sung word has), each a little after the one before,
 * so the movement travels along the word. Later letters of a long word, and every letter of a word only
 * just held long enough, move and light less.
 */
internal class GrowingWord(val index: Int, word: VWord) {
    val startMs: Long = word.startMs
    val endMs: Long = word.endMs
    private val chars = word.text.length
    private val scalePeak = FloatArray(chars)
    private val shiftPeak = FloatArray(chars)
    private val risePeak = FloatArray(chars)
    private val bloomPeak = FloatArray(chars)
    val restsAtMs: Long

    init {
        val held = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val earned = ((held - GROW_RAMP_MIN_MS) / (GROW_RAMP_MAX_MS - GROW_RAMP_MIN_MS)).coerceIn(0f, 1f).let { it * it * it }
        val decay = decayRate(chars, held)
        val bloomPace = minOf(GROW_BLOOM_PACE_MAX, held / GROW_BLOOM_PACE_MS)
        val bloomSpread = when {
            chars <= 3 -> GROW_BLOOM_SHORT
            chars >= 6 -> GROW_BLOOM_LONG
            else -> 1f
        }
        val base = if (chars <= 3) GROW_BASE_SHORT else GROW_BASE_LONG
        val liftPace = (held / GROW_LIFT_PACE_MS).coerceIn(GROW_LIFT_FLOOR, 1f)
        for (i in 0 until chars) {
            val place = if (chars > 1) i.toFloat() / (chars - 1) else 0f
            val reach = earned * (1f - place * decay)
            val scale = 1f + base + reach * GROW_SCALE_RANGE
            scalePeak[i] = scale * GROW_SCALE_TRIM
            bloomPeak[i] = (GROW_BLOOM_FLOOR + reach * GROW_BLOOM_RANGE) * bloomPace * bloomSpread
            risePeak[i] = ((scale - 1f) / GROW_SCALE_CEILING) * liftPace
            val centre = (i + 0.5f) / chars
            shiftPeak[i] = (centre - 0.5f) * 2f * (scale - 1f) * GROW_SHIFT_EM * GROW_SCALE_TRIM
        }
        val last = (chars - 1).coerceAtLeast(0) * GROW_STAGGER + GROW_SPAN
        restsAtMs = startMs + (held * last).toLong()
    }

    /** Where letter [charIndex] has got to at [positionMs], written into [into] (no allocation per letter per frame). */
    fun sampleInto(charIndex: Int, positionMs: Long, into: CharGrowth) {
        val span = (endMs - startMs).coerceAtLeast(1L).toFloat()
        val elapsed = positionMs - startMs - charIndex * span * GROW_STAGGER
        val phase = (elapsed / (span * GROW_SPAN)).coerceIn(0f, 1f)
        val peak = scalePeak[charIndex]
        when {
            phase < GROW_IN -> {
                val t = smooth(phase / GROW_IN)
                into.scale = 1f + (peak - 1f) * t
                into.shift = shiftPeak[charIndex] * t
                into.rise = risePeak[charIndex] * t
                into.bloom = bloomPeak[charIndex] * t
            }
            phase < GROW_HOLD -> {
                into.scale = peak
                into.shift = shiftPeak[charIndex]
                into.rise = risePeak[charIndex]
                into.bloom = bloomPeak[charIndex]
            }
            phase < GROW_OUT -> {
                val t = smooth((phase - GROW_HOLD) / (GROW_OUT - GROW_HOLD))
                into.scale = peak + (1f - peak) * t
                into.shift = shiftPeak[charIndex] * (1f - t)
                into.rise = risePeak[charIndex] + (GROW_REST - risePeak[charIndex]) * t
                into.bloom = bloomPeak[charIndex] * (1f - t)
            }
            else -> {
                into.scale = 1f
                into.shift = 0f
                into.rise = GROW_REST
                into.bloom = 0f
            }
        }
    }
}

/** Where one letter of a [GrowingWord] is: its swell, lean (in ems), lift (in sung-word lifts) and bloom. */
internal class CharGrowth {
    var scale = 1f
    var shift = 0f
    var rise = 0f
    var bloom = 0f
}

private const val RISE_MS = 700f
private const val RISE_MIN_MS = 250f

private fun liftHeight(w: VWord): Float {
    val held = (w.endMs - w.startMs).toFloat()
    val through = ((held - LIFT_QUICK_MS) / (LIFT_HELD_MS - LIFT_QUICK_MS)).coerceIn(0f, 1f)
    return LIFT_FLOOR + (1f - LIFT_FLOOR) * smooth(through)
}

private const val LIFT_QUICK_MS = 150f
private const val LIFT_HELD_MS = 900f
private const val LIFT_FLOOR = 0.25f

internal fun smooth(x: Float) = x * x * (3f - 2f * x)

/** Held long enough, and short enough, to swell a letter at a time; never in scripts whose letters join or stand as blocks. */
private fun VWord.canGrow(): Boolean {
    val length = text.length
    if (length == 0 || length > GROW_MAX_CHARS) return false
    if ('-' in text || text.any { it.blockScript() || it.joinedScript() }) return false
    val held = endMs - startMs
    return when {
        length == 1 -> held >= GROW_MIN_SOLO_MS
        length <= 3 -> held >= GROW_MIN_SHORT_MS + (length - 2) * GROW_SHORT_STEP_MS
        length == 4 -> held >= GROW_MIN_FOUR_MS
        else -> held >= GROW_MIN_LONG_MS && held >= length * GROW_MS_PER_CHAR
    }
}

private fun decayRate(length: Int, heldMs: Float): Float {
    val long = length > GROW_DECAY_LONG_CHARS
    val quick = heldMs < GROW_DECAY_QUICK_MS
    if (!long && !quick) return 0f
    var strength = 0f
    if (long) strength += minOf((length - GROW_DECAY_LONG_CHARS) / 5f, 1f) * GROW_DECAY_LONG
    if (quick) {
        val short = maxOf(0f, 1f - (heldMs - GROW_DECAY_QUICK_FLOOR_MS) / 400f)
        strength += short * if (length > 3) GROW_DECAY_QUICK else GROW_DECAY_QUICK_TINY
    }
    return minOf(strength, GROW_DECAY_MAX)
}

private fun Char.blockScript(): Boolean = this in '一'..'鿿' || this in '぀'..'ゟ' || this in '゠'..'ヿ' || this in '가'..'힯'
private fun Char.joinedScript(): Boolean = this in '֐'..'ࣿ'

private const val GROW_MAX_CHARS = 7
private const val GROW_MIN_SOLO_MS = 1_100L
private const val GROW_MIN_SHORT_MS = 1_360L
private const val GROW_SHORT_STEP_MS = 140L
private const val GROW_MIN_FOUR_MS = 1_050L
private const val GROW_MIN_LONG_MS = 900L
private const val GROW_MS_PER_CHAR = 200L
private const val GROW_DECAY_LONG_CHARS = 5
private const val GROW_DECAY_QUICK_MS = 1_200f
private const val GROW_DECAY_QUICK_FLOOR_MS = 800f
private const val GROW_DECAY_LONG = 0.4f
private const val GROW_DECAY_QUICK = 0.3f
private const val GROW_DECAY_QUICK_TINY = 0.1f
private const val GROW_DECAY_MAX = 0.7f
private const val GROW_STAGGER = 0.09f
private const val GROW_SPAN = 1.5f
private const val GROW_IN = 0.25f
private const val GROW_HOLD = 0.30f
private const val GROW_OUT = 0.75f
private const val GROW_REST = 1f
private const val GROW_RAMP_MIN_MS = 400f
private const val GROW_RAMP_MAX_MS = 3_000f
private const val GROW_BASE_SHORT = 0.05f
private const val GROW_BASE_LONG = 0.04f
private const val GROW_SCALE_RANGE = 0.08f
private const val GROW_SCALE_CEILING = 0.1f
private const val GROW_SCALE_TRIM = 0.98f
private const val GROW_SHIFT_EM = 25f / 34f
private const val GROW_BLOOM_FLOOR = 0.35f
private const val GROW_BLOOM_RANGE = 0.45f
private const val GROW_BLOOM_PACE_MS = 1_500f
private const val GROW_BLOOM_PACE_MAX = 1.1f
private const val GROW_BLOOM_SHORT = 0.85f
private const val GROW_BLOOM_LONG = 1.1f
private const val GROW_LIFT_PACE_MS = 2_000f
private const val GROW_LIFT_FLOOR = 0.3f
