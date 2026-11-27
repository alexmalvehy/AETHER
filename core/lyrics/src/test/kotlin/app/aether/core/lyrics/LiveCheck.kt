package app.aether.core.lyrics

import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI

/**
 * Asks every lyrics library live for a few songs and says what each answered: `./gradlew
 * :core:lyrics:liveCheck`. Not a test: the services' answers change, and they're asked for real.
 */
fun main(args: Array<String>) = runBlocking {
    val http = object : LyricsHttp {
        override suspend fun get(url: String): Pair<Int, String> = reply(url).let { it.code to it.body }
        override suspend fun reply(url: String): LyricsHttp.Reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", "AETHER/1.1 (Android music player; live check)")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            LyricsHttp.Reply(code, body, c.getHeaderField("Retry-After")?.toLongOrNull())
        }
    }
    if (System.getProperty("debug") != null) {
        val body = http.reply("https://lyrics-api.boidu.dev/getLyrics?s=Starboy&a=The%20Weeknd&d=230").body
        println("body ${body.length}")
        runCatching { ProviderText.parse(body) }.onFailure { it.printStackTrace() }.onSuccess { println("parsed: ${(it as? Lyrics.Synced)?.lines?.size}") }
        return@runBlocking
    }
    val libraries = listOf(LrcRed(http), BiniLyrics(http), BetterLyrics(http), LyricsPlus(http), Unison(http), LrclibProvider(http), KuGou(http))
    // Songs of your own: -Psongs="Title|Artist|seconds;Title|Artist|seconds"
    val asked = args.firstOrNull()?.split(';')?.mapIndexedNotNull { i, s ->
        val parts = s.split('|').map(String::trim)
        if (parts.size < 2) null else LyricsRequest("asked$i", parts[0], parts[1], null, parts.getOrNull(2)?.toLongOrNull()?.times(1000))
    }
    val songs = asked ?: listOf(
        LyricsRequest("1", "Starboy", "The Weeknd, Daft Punk", "Starboy", 230_000),
        LyricsRequest("2", "Dynamite", "BTS", "Dynamite (DayTime Version)", 199_000),
        LyricsRequest("3", "봄날", "BTS", "YOU NEVER WALK ALONE", 274_000),
        LyricsRequest("4", "晴天", "周杰倫", "叶惠美", 269_000),
    )
    for (song in songs) {
        println("\n${song.title} — ${song.artist}")
        for (library in libraries) {
            val started = System.currentTimeMillis()
            val answer = runCatching { library.lookup(song) }.getOrElse { ProviderAnswer.Failed }
            val ms = System.currentTimeMillis() - started
            val what = when (answer) {
                is ProviderAnswer.Found -> when (val l = answer.lyrics) {
                    is Lyrics.Synced -> "${l.lines.count { it.text.isNotBlank() }} lines" + (if (l.lines.any { it.words.isNotEmpty() }) ", word by word" else ", by the line") +
                        "  “${l.lines.filter { it.text.isNotBlank() }.take(2).joinToString(" / ") { it.text.take(36) }}”"
                    is Lyrics.Plain -> "${l.lines.size} plain lines"
                    Lyrics.Instrumental -> "instrumental"
                }
                else -> answer::class.simpleName
            }
            println("  %-12s %5d ms  %s".format(library.attribution.name, ms, what))
        }
        val chain = LyricsChain({ libraries })
        val started = System.currentTimeMillis()
        val best = chain.lookup(song)
        println("  → chosen in ${System.currentTimeMillis() - started} ms: ${(best as? ProviderAnswer.Found)?.credit?.name ?: best::class.simpleName}")
    }
}
