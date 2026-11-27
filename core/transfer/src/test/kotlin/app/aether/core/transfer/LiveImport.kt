package app.aether.core.transfer

import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.Track
import app.aether.sources.youtube.YouTubeSource
import app.aether.sources.youtube.YouTubeStreamResolver
import app.aether.ytmusic.YouTubeMusic
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.system.exitProcess

/**
 * A real playlist page, saved as AETHER fetched it, read and every song looked for on YouTube Music
 * live, as an import does (`./gradlew :core:transfer:liveImport -Ppage=<file> -Pkind=spotify|apple`).
 * Says what each song became, so a wrong match shows at a glance, and how many were found.
 */
fun main(args: Array<String>) = runBlocking {
    val page = args.getOrNull(0)?.let(::File)?.takeIf { it.exists() } ?: run { println("a saved page, please"); exitProcess(2) }
    val read = when (args.getOrNull(1)) {
        "apple" -> AppleMusicLink.read(page.readText(), "pl.live")
        "file" -> page.inputStream().use { Imports.read(it, page.name, File.createTempFile("live", ".zip")) }
        else -> SpotifyLink.read(page.readText(), "live")
    }
    val playlist = (read as? ImportRead.Ok)?.library?.playlists?.single() ?: run { println("✗ ${(read as ImportRead.Failed).reason}"); exitProcess(1) }
    val yt = YouTubeSource(YouTubeMusic(language = { "en" }, region = { "US" }), YouTubeStreamResolver(OkHttpClient()))
    println("${playlist.name}: ${playlist.items.size} songs${if (playlist.partial) " (part)" else ""}")
    var found = 0
    var asked = 0
    for (item in playlist.items) {
        var hit: Track? = null
        // As the Finder does: a link looked up and checked first.
        item.youtubeId?.let { id ->
            asked++
            val next = (yt.upNext(Track(MediaId("ytm:$id"), item.title.ifBlank { id }, emptyList())) as? Outcome.Ok)?.value
            hit = next?.tracks?.firstOrNull { it.id.key == id }?.takeIf { Matcher.confirms(item, it) }
        }
        // Then each reading of a guessed line, the first asked in full, the others briefly.
        if (hit == null) readings@ for ((i, reading) in Matcher.readings(item).withIndex()) {
            for (query in Matcher.queries(reading).take(if (i == 0) Int.MAX_VALUE else 2)) {
                asked++
                val songs = (yt.searchSongs(query) as? Outcome.Ok)?.value?.items.orEmpty()
                hit = Matcher.pick(reading, songs)
                if (hit != null) break@readings
            }
        }
        if (hit != null) found++
        println((if (hit != null) "✓ " else "✗ ") + item.label + (hit?.let { "  →  ${it.artistLine} – ${it.title}${if (it.explicit) " [E]" else ""}" } ?: ""))
    }
    println("\n$found of ${playlist.items.size} found, $asked searches")
}
