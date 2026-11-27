// After BitChord's lyrics providers (GPL-3.0): lrc.red, BiniLyrics, BetterLyrics, LyricsPlus, Unison, KuGou.
package app.aether.core.lyrics

import app.aether.core.lyrics.matching.ArtistNames
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Base64
import kotlin.math.abs

/*
 * The libraries AETHER asks for timed lyrics besides LRCLIB, as BitChord and LastWave ask them. Each is
 * asked by the song's details (its title, artist, album and length) and nothing else; none needs an
 * account, a key or another app's token. Their answers are untrusted: read leniently, matched against
 * the song where they say what they found, and never thrown at the screen.
 */

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.let { p -> p.longOrNull?.toDouble() ?: p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() }
private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/**
 * A library asked over HTTP: a 200's body; a 404 as "it hasn't the song", and so a 401 or 403 (a library
 * that wants a key for what it hasn't kept, as BetterLyrics now does, hasn't this song for us); anything
 * else a failure.
 */
abstract class LyricsLibrary(private val http: LyricsHttp) : LyricsProvider {
    /** Whether it can time words, not only lines: what it's asked first for. */
    abstract val timesWords: Boolean

    /** The body, or null for a 404; throws for a failure (the network's, or the library's). */
    protected suspend fun fetch(url: String): String? {
        val reply = http.reply(url)
        return when (reply.code) {
            in 200..299 -> reply.body
            404, 204, 401, 403 -> null
            429 -> throw RateLimit(reply.retryAfterSeconds?.times(1000) ?: 60_000L)
            else -> throw IOException("HTTP ${reply.code}")
        }
    }

    final override suspend fun lookup(request: LyricsRequest): ProviderAnswer = try {
        if (request.title.isBlank() || request.artist.isBlank()) ProviderAnswer.NotFound
        else find(request)?.let { ProviderAnswer.Found(it, attribution) } ?: ProviderAnswer.NotFound
    } catch (e: CancellationException) {
        throw e
    } catch (e: UnknownHostException) {
        ProviderAnswer.Offline
    } catch (e: RateLimit) {
        ProviderAnswer.RateLimited(e.waitMs)
    } catch (e: Exception) {
        ProviderAnswer.Failed
    }

    protected abstract suspend fun find(request: LyricsRequest): Lyrics?

    class RateLimit(val waitMs: Long) : IOException("rate limited")

    protected fun seconds(request: LyricsRequest): Long? = request.durationMs?.takeIf { it > 0 }?.let { (it + 500) / 1000 }
}

/** lrc.red: Apple Music's TTML filed by ISRC, a document per recording; searched by name otherwise. */
class LrcRed(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "lrcred"
    override val attribution = LyricsAttribution("lrc.red", "https://lrc.red")
    override val timesWords = true

    override suspend fun find(request: LyricsRequest): Lyrics? {
        request.isrc?.let(::document)?.let { url -> fetch(url)?.let(Ttml::parse)?.takeIf { it.isNotEmpty() }?.let { return Lyrics.Synced(it) } }
        val body = fetch("https://lrc.red/search.json?q=${enc("${request.title} ${request.artist}")}") ?: return null
        val hits = (json.parseToJsonElement(body)["hits"] as? JsonArray).orEmpty()
        val hit = LyricsMatcher.best(request, hits.filter { document(it["isrc"].str()) != null }) {
            LyricsMatcher.Candidate(it["title"].str().orEmpty(), it["artist"].str().orEmpty(), it["album"].str(), it["duration"].num()?.let { s -> (s * 1000).toLong() })
        } ?: return null
        val url = document(hit["isrc"].str()) ?: return null
        return fetch(url)?.let(Ttml::parse)?.takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it) }
    }

    private fun document(isrc: String?): String? = isrc?.trim()?.uppercase()?.takeIf { ISRC.matches(it) }?.let { "https://lrc.red/s/$it.ttml" }

    private companion object {
        val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")
    }
}

/** BiniLyrics: the same Apple timings, found by the recording; its search names the document. */
class BiniLyrics(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "bini"
    override val attribution = LyricsAttribution("BiniLyrics", "https://github.com/binimum")
    override val timesWords = true

    override suspend fun find(request: LyricsRequest): Lyrics? {
        val query = request.isrc?.let { "isrc=${enc(it)}" } ?: buildString {
            append("track=").append(enc(request.title)).append("&artist=").append(enc(request.artist))
            request.album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(enc(it)) }
            seconds(request)?.let { append("&duration=").append(it) }
        }
        val body = fetch("https://lyrics-api.binimum.org/?$query") ?: return null
        val results = (json.parseToJsonElement(body)["results"] as? JsonArray).orEmpty()
        val hit = if (request.isrc != null) results.firstOrNull() else LyricsMatcher.best(request, results) {
            LyricsMatcher.Candidate(it["track_name"].str().orEmpty(), it["artist_name"].str().orEmpty(), it["album_name"].str(), it["duration"].num()?.let { s -> (s * 1000).toLong() })
        }
        val url = hit?.get("lyricsUrl").str()?.takeIf { it.startsWith("https://") } ?: return null
        return fetch(url)?.let(Ttml::parse)?.takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it) }
    }
}

/**
 * BetterLyrics: the backend of the YouTube Music extension of that name; Apple's TTML for a song's
 * title, artist and length. (Asked with an album too, it refuses: measured 2026-11-10.)
 */
class BetterLyrics(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "betterlyrics"
    override val attribution = LyricsAttribution("BetterLyrics", "https://github.com/better-lyrics/better-lyrics")
    override val timesWords = true

    override suspend fun find(request: LyricsRequest): Lyrics? {
        val url = buildString {
            append("https://lyrics-api.boidu.dev/getLyrics?s=").append(enc(request.title)).append("&a=").append(enc(request.artist))
            seconds(request)?.let { append("&d=").append(it) }
        }
        return fetch(url)?.let(ProviderText::parse)
    }
}

/** LyricsPlus (YouLyPlus): syllable timings from several catalogues, on community mirrors; the first that has the song. */
class LyricsPlus(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "lyricsplus"
    override val attribution = LyricsAttribution("LyricsPlus", "https://github.com/ibratabian17/YouLyPlus")
    override val timesWords = true

    @Volatile private var lastGood: String? = null

    override suspend fun find(request: LyricsRequest): Lyrics? {
        val hosts = lastGood?.let { listOf(it) + (MIRRORS - it) } ?: MIRRORS
        var failure: Exception? = null
        for (host in hosts.take(3)) {
            val url = buildString {
                append(host).append("/v2/lyrics/get?title=").append(enc(request.title)).append("&artist=").append(enc(request.artist))
                seconds(request)?.let { append("&duration=").append(it) }
                request.album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(enc(it)) }
                request.isrc?.let { append("&isrc=").append(enc(it)) }
            }
            val body = try {
                fetch(url) ?: continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                continue
            }
            val lines = parse(json.parseToJsonElement(body))
            if (lines.isNotEmpty()) {
                lastGood = host
                return Lyrics.Synced(lines)
            }
        }
        failure?.let { throw it }
        return null
    }

    internal fun parse(root: JsonElement): List<LyricsLine> {
        val agents = HashMap<String, String>()
        (root["metadata"]["agents"] as? JsonObject)?.forEach { (id, agent) ->
            agent["type"].str()?.let { type -> agents[agent["alias"].str() ?: id] = type }
        }
        val sung = (root["lyrics"] as? JsonArray).orEmpty().mapNotNull { line ->
            val start = line["time"].num()?.toLong() ?: return@mapNotNull null
            val runs = (line["syllabus"] as? JsonArray).orEmpty().mapNotNull { s ->
                val text = (s["text"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val t = s["time"].num()?.toLong() ?: return@mapNotNull null
                Run(t, t + (s["duration"].num()?.toLong() ?: 0L), text)
            }
            val words = Runs.words(runs, spacingIsExplicit = true)
            val built = when {
                words.isNotEmpty() -> LyricsLine(minOf(start, words.first().startMs), words.joinToString(" ") { it.text }, endMs = words.last().endMs, words = words)
                !line["text"].str().isNullOrBlank() -> LyricsLine(start, line["text"].str()!!, endMs = line["duration"].num()?.toLong()?.takeIf { it > 0 }?.let { start + it })
                else -> null
            }
            built?.let { it to line["element"]["singer"].str() }
        }.sortedBy { it.first.startMs }
        val sides = Voices.sides(sung.map { it.second }, agents)
        return sung.mapIndexed { i, (line, _) -> line.copy(opposite = sides[i]) }.withGaps()
    }

    private companion object {
        val MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.workers.dev",
        )
    }
}

/** Unison: lyrics contributed by listeners, word-timed, line-timed or plain, voted on. */
class Unison(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "unison"
    override val attribution = LyricsAttribution("Unison", "https://unison.boidu.dev")
    override val timesWords = true

    override suspend fun find(request: LyricsRequest): Lyrics? {
        val url = buildString {
            append("https://unison.boidu.dev/lyrics?song=").append(enc(request.title)).append("&artist=").append(enc(request.artist))
            request.album?.takeIf { it.isNotBlank() }?.let { append("&album=").append(enc(it)) }
            seconds(request)?.let { append("&duration=").append(it) }
        }
        val root = json.parseToJsonElement(fetch(url) ?: return null)
        if ((root["success"] as? JsonPrimitive)?.contentOrNull != "true") return null
        val data = root["data"]
        // Where it says what it matched, it must be the song.
        val song = data["song"].str()
        val artist = data["artist"].str()
        if (song != null && artist != null && !LyricsMatcher.matches(request, LyricsMatcher.Candidate(song, artist, data["album"].str(), null))) return null
        val text = data["lyrics"].str() ?: return null
        return when {
            data["format"].str().equals("ttml", true) -> Ttml.parse(text).takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it) }
            data["syncType"].str().equals("plain", true) -> LrcParser.plain(text)
            else -> LrcParser.synced(text)
        }
    }
}

/**
 * KuGou: lines in time, strongest outside the English-speaking catalogue (Chinese, Korean, Japanese).
 * Every answer it gives is held to the song: its song search answers other songs of the same name, and
 * its lyrics search, asked for one recording, answers files made for others (asked for Pennyy's
 * "Espresso" it gave Tchaikovsky's "June", for Kizo's a remix by someone else and "Spice Up Your Life";
 * measured 2026-11-19). A song, or a file, that isn't this song by its own title and artist is passed over.
 */
class KuGou(http: LyricsHttp) : LyricsLibrary(http) {
    override val id = "kugou"
    override val attribution = LyricsAttribution("KuGou", "https://www.kugou.com")
    override val timesWords = false

    override suspend fun find(request: LyricsRequest): Lyrics? {
        val keyword = enc("${request.title.unbracketed()} - ${request.artist.unbracketed()}")
        val seconds = seconds(request)?.toInt() ?: -1
        val songs = fetch("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=$keyword")
        // Only this song, in cuts of about the same length: otherwise the first result for a common title is
        // as likely a cover, a remix, or another song of the name.
        val hashes = songs?.let { body ->
            (json.parseToJsonElement(body)["data"]["info"] as? JsonArray).orEmpty().mapNotNull { info ->
                val hash = info["hash"].str() ?: return@mapNotNull null
                val length = info["duration"].num()?.toInt() ?: -1
                if (seconds > 0 && abs(length - seconds) > TOLERANCE_S) return@mapNotNull null
                if (!isThisSong(request, info["songname"].str(), info["singername"].str(), info["album_name"].str())) return@mapNotNull null
                hash to abs(length - seconds)
            }.sortedBy { it.second }.map { it.first }
        }.orEmpty()
        val candidate = hashes.take(3).firstNotNullOfOrNull { hash -> candidates(request, "hash=${enc(hash)}", seconds).firstOrNull() }
            ?: candidates(request, "keyword=$keyword" + if (seconds > 0) "&duration=${seconds * 1000}" else "", seconds).firstOrNull()
            ?: return null
        val body = fetch("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=${enc(candidate.first)}&accesskey=${enc(candidate.second)}") ?: return null
        val content = json.parseToJsonElement(body)["content"].str() ?: return null
        val lrc = runCatching { Base64.getDecoder().decode(content).toString(Charsets.UTF_8) }.getOrNull() ?: return null
        if (instrumentalNotice(lrc)) return Lyrics.Instrumental
        return LrcParser.synced(withoutCredits(lrc))
    }

    /** The lyric files it offers for [query] that are this song's, by their own title, artist and length. */
    private suspend fun candidates(request: LyricsRequest, query: String, seconds: Int): List<Pair<String, String>> {
        val body = fetch("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&$query") ?: return emptyList()
        return (json.parseToJsonElement(body)["candidates"] as? JsonArray).orEmpty().mapNotNull { c ->
            val id = c["id"].str() ?: return@mapNotNull null
            val key = c["accesskey"].str() ?: return@mapNotNull null
            val length = c["duration"].num()?.toLong()?.let { (it + 500) / 1000 }?.toInt()
            if (seconds > 0 && length != null && length > 0 && abs(length - seconds) > TOLERANCE_S) return@mapNotNull null
            if (!isThisSong(request, c["song"].str(), c["singer"].str(), null)) return@mapNotNull null
            id to key
        }
    }

    /**
     * Whether KuGou's [title] by [artist] is the song asked for: the same title and version, by a shared
     * artist (PODIUM's rules, as every library is held to). Its own length check is KuGou's, and looser:
     * its cuts run a few seconds off the catalogue's.
     */
    private fun isThisSong(request: LyricsRequest, title: String?, artist: String?, album: String?): Boolean {
        if (title.isNullOrBlank() || artist.isNullOrBlank()) return false
        val asked = request.copy(durationMs = null)
        if (LyricsMatcher.matches(asked, LyricsMatcher.Candidate(title, artist, album, null))) return true
        // The same artist written in the other Chinese script (周杰倫 asked, 周杰伦 filed): then the title
        // and its version must still be the song's.
        return sameHanName(request.artist, artist) && LyricsMatcher.matches(asked, LyricsMatcher.Candidate(title, request.artist, album, null))
    }

    /**
     * Whether two credits name someone the same in Chinese characters, one written traditional and one
     * simplified: names of the same length, agreeing in at least half their characters, place by place.
     */
    private fun sameHanName(a: String, b: String): Boolean {
        val ours = ArtistNames.split(a).map { it.trim() }.filter(::allHan)
        val theirs = ArtistNames.split(b).flatMap { it.split('(', '（', ')', '）') }.map { it.trim() }.filter(::allHan)
        return ours.any { x -> theirs.any { y -> x.length == y.length && x.length >= 2 && x.indices.count { x[it] == y[it] } * 2 >= x.length } }
    }

    private fun allHan(name: String): Boolean = name.isNotEmpty() && name.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    private fun String.unbracketed(): String = replace(Regex("""[(（].*?[)）]"""), "").trim().ifBlank { this }

    /** KuGou's files open and close with credits (lyricist, composer) on real stamps: cut, from the ends only. */
    private fun withoutCredits(lrc: String): String {
        val lines = lrc.lineSequence().filter { STAMPED.matches(it) }.toList()
        if (lines.isEmpty()) return ""
        val head = (minOf(30, lines.lastIndex) downTo 0).firstOrNull { CREDIT.matches(lines[it]) }?.let { it + 1 } ?: 0
        val body = lines.drop(head)
        val tail = (0..minOf(30, body.lastIndex)).firstOrNull { body.isNotEmpty() && CREDIT.matches(body[body.lastIndex - it]) }?.let { it + 1 } ?: 0
        return body.dropLast(tail).joinToString("\n")
    }

    /** "纯音乐，请欣赏" ("pure music, please enjoy"): its way of saying there are no words. */
    private fun instrumentalNotice(lrc: String): Boolean {
        val lines = lrc.lineSequence().map { it.replace(STAMP, "").trim() }.filter { it.isNotEmpty() }.toList()
        return lines.isNotEmpty() && lines.all { INSTRUMENTAL.containsMatchIn(it) }
    }

    private companion object {
        const val TOLERANCE_S = 8
        val STAMP = Regex("""\[\d{2}:\d{2}\.\d{2,3}]""")
        val STAMPED = Regex("""\[\d{2}:\d{2}\.\d{2,3}].*""")
        val CREDIT = Regex(""".+][^\[]+[:：].+""")
        val INSTRUMENTAL = Regex("""纯音乐.*欣赏""")
    }
}
