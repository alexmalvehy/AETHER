package app.aether.core.transfer

import java.net.URI

/**
 * The YouTube video a link names, from any of the shapes people paste: music.youtube.com and
 * youtube.com `watch?v=`, `youtu.be/`, `shorts/` and `embed/`, and AETHER's own `ytm:` ids. A video id
 * is eleven characters of letters, digits, `-` and `_`; anything else is no id at all.
 */
object YouTubeLinks {
    private val ID = Regex("""[A-Za-z0-9_-]{11}""")

    fun videoId(text: String?): String? {
        val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (s.startsWith("ytm:")) return s.removePrefix("ytm:").takeIf { ID.matches(it) }
        val uri = runCatching { URI(s) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.rawPath.orEmpty()
        val id = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> when {
                path == "/watch" -> query(uri.rawQuery, "v")
                path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/") -> path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** A YouTube Music link to [videoId], as exports write it. */
    fun link(videoId: String): String = "https://music.youtube.com/watch?v=$videoId"

    private fun query(raw: String?, key: String): String? = raw?.split('&')
        ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
        ?.firstOrNull { it.first == key }
        ?.second
}
