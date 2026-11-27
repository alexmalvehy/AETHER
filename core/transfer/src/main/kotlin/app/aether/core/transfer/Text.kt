package app.aether.core.transfer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A file's bytes as text, whatever wrote it: UTF-8 (with or without its mark), UTF-16 by its mark, and
 * otherwise Windows-1252, which is what an `.m3u` from an older Windows player usually is. Never
 * throws: bytes that are none of these come out as Windows-1252, which reads every byte.
 */
internal object Text {
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return strictUtf8(bytes) ?: String(bytes, Charset.forName("windows-1252"))
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Lines, whatever ends them (\n, \r\n, \r). */
    fun lines(text: String): List<String> = text.split("\r\n", "\n", "\r")

    /** One line of text a file gives, safe to show and store: no control characters, trimmed, of sane length. */
    fun clean(s: String?, max: Int = 300): String? = s
        ?.replace(CONTROL, " ")
        ?.replace(SPACES, " ")
        ?.trim()
        ?.take(max)
        ?.takeIf { it.isNotEmpty() }

    private val CONTROL = Regex("""[\u0000-\u001F\u007F]""")
    private val SPACES = Regex("""\s+""")
}
