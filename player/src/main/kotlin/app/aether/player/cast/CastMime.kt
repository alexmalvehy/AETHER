package app.aether.player.cast

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap

/**
 * What a song is, for a TV that's told rather than left to guess (BitChord's `mimeTypeOf`): the
 * source's own word for it, the stream's `mime` parameter (YouTube's addresses say), the file's type, or
 * its extension; a song nothing names is taken to be MP4 audio, as nearly every unnamed stream is.
 */
object CastMime {
    fun ofStream(context: Context, url: String, given: String?): String {
        given?.substringBefore(';')?.trim()?.takeIf { it.contains('/') }?.let { return it }
        val uri = Uri.parse(url)
        if (uri.scheme == "content" || uri.scheme == "file") return ofFile(context, uri)
        runCatching { uri.getQueryParameter("mime") }.getOrNull()?.takeIf { it.contains('/') }?.let { return it }
        return byExtension(uri.lastPathSegment) ?: "audio/mp4"
    }

    fun ofFile(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
            ?: byExtension(uri.lastPathSegment)
            ?: "audio/mp4"

    /** A DASH or HLS manifest: many pieces, which can't be handed on as one song. */
    fun isManifest(url: String, mime: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        if ("mpegurl" in m || "dash+xml" in m) return true
        // The address's path alone: what follows a ? or a # says nothing of what it is.
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    internal fun byExtension(name: String?): String? {
        val ext = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (ext) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "ogg", "oga", "opus" -> "audio/ogg"
            "webm", "weba" -> "audio/webm"
            "wav" -> "audio/wav"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
    }
}
