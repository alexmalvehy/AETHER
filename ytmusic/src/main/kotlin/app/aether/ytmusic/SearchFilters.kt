package app.aether.ytmusic

import java.util.Base64

/**
 * What a search filter selects, read from its parameters rather than its label, which comes in the
 * listener's language ("Songs", "Canciones", "गाने"). A filter's parameters are a small protobuf,
 * base64 as the page sends it: `{ 2: { 17: { <kind>: 1 } } }`, then ranking hints. The kind is 1 for
 * songs, 2 videos, 3 albums, 4 artists, 7 and 8 playlists, 9 episodes, 10 podcasts, 11 profiles, as
 * the answer's own chips show (fixture `search.json.gz`).
 *
 * The encoding follows PODIUM's (sources/youtubemusic SearchFilters.kt, GPL-3.0); reading a chip's
 * kind from its bytes, never its label, is AETHER's.
 */
object SearchFilters {
    const val SONGS = 1

    /** The kind [params] select, or null when they aren't a filter's (or can't be read). Never throws. */
    fun kindOf(params: String): Int? {
        val bytes = runCatching { Base64.getDecoder().decode(percentDecoded(params)) }.getOrNull() ?: return null
        val filter = messageField(bytes, 2) ?: return null
        val kinds = messageField(filter, 17) ?: return null
        return varintFields(kinds).firstOrNull { it.second == 1L }?.first
    }

    /** The parameters selecting [kind] alone, without the ranking hints a page adds after. */
    fun encoded(kind: Int): String {
        val inner = byteArrayOf((kind shl 3).toByte(), 1)
        val wrapped = byteArrayOf(0x8a.toByte(), 0x01, inner.size.toByte()) + inner
        val message = byteArrayOf(0x12, wrapped.size.toByte()) + wrapped
        return Base64.getEncoder().encodeToString(message).replace("=", "%3D")
    }

    /** The Songs filter among [filters], else the Songs filter as AETHER writes it. */
    fun songs(filters: List<SearchFilter>): String =
        filters.firstOrNull { kindOf(it.params) == SONGS }?.params ?: encoded(SONGS)

    /** `%3D` and its like back to their characters; nothing else touched (`+` is base64's own). */
    private fun percentDecoded(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val code = if (c == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (code != null) {
                out.append(code.toChar())
                i += 3
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** The bytes of length-delimited field [number] in [message], the first time it appears. */
    private fun messageField(message: ByteArray, number: Int): ByteArray? {
        var i = 0
        while (i < message.size) {
            val (key, afterKey) = varint(message, i) ?: return null
            val field = (key ushr 3).toInt()
            i = afterKey
            when ((key and 7).toInt()) {
                0 -> i = (varint(message, i) ?: return null).second
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val (length, start) = varint(message, i) ?: return null
                    if (length < 0 || length > message.size - start) return null
                    val end = start + length.toInt()
                    if (field == number) return message.copyOfRange(start, end)
                    i = end
                }
                else -> return null
            }
        }
        return null
    }

    /** Every varint field in [message], as (number, value). */
    private fun varintFields(message: ByteArray): List<Pair<Int, Long>> {
        val out = ArrayList<Pair<Int, Long>>()
        var i = 0
        while (i < message.size) {
            val (key, afterKey) = varint(message, i) ?: break
            i = afterKey
            when ((key and 7).toInt()) {
                0 -> {
                    val (value, next) = varint(message, i) ?: break
                    out += (key ushr 3).toInt() to value
                    i = next
                }
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val (length, start) = varint(message, i) ?: break
                    if (length < 0 || length > message.size - start) break
                    i = start + length.toInt()
                }
                else -> break
            }
        }
        return out
    }

    /** A varint at [at]: its value and where the next thing starts; null past the end. */
    private fun varint(bytes: ByteArray, at: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = at
        while (i < bytes.size && shift < 64) {
            val b = bytes[i].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return value to i
            shift += 7
        }
        return null
    }
}
