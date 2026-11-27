package app.aether.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.system.exitProcess

/**
 * Captures one search answer as a fixture, as a guest, with the visitor id and every tracking
 * parameter taken out (`./gradlew :ytmusic:captureSearch -Pquery="..." -Pname=... [-Psongs]`).
 * The Songs filter is AETHER's own ([SearchFilters.encoded]), as a search asks it before the mixed
 * answer has said what its filters are.
 */
fun main(args: Array<String>) = runBlocking {
    val (query, name) = args.getOrNull(0) to args.getOrNull(1)
    if (query == null || name == null) { println("query and name, please"); exitProcess(2) }
    val songs = args.getOrNull(2) == "songs"
    // With "queue", the query is video ids, comma-separated: the queue's answer for them.
    val queue = args.getOrNull(2) == "queue"
    val client = YouTubeMusicClient(UrlConnectionTransport(), { YouTubeMusic.DESKTOP_USER_AGENT }, { null }, { "en" }, { "US" })
    // With "browse", the query is a browse id (an artist's, an album's): its page.
    val browse = args.getOrNull(2) == "browse"
    // With "next", the query is a playlist id: the queue it starts.
    val next = args.getOrNull(2) == "next"
    val answer = when {
        queue -> client.queue(query.split(',').map(String::trim))
        browse -> client.browse(query)
        next -> client.next(null, query)
        else -> client.search(query, if (songs) SearchFilters.encoded(SearchFilters.SONGS) else null)
    }
    when (val r = answer) {
        is YtResult.Failure -> { println("✗ ${r.error}"); exitProcess(1) }
        is YtResult.Success -> {
            val out = File("src/test/resources/fixtures/$name.json.gz")
            GZIPOutputStream(out.outputStream()).use { it.write(stripped(r.value).toString().toByteArray()) }
            println("✓ wrote ${out.path}")
        }
    }
}

/** The answer without what identifies the visitor or this visit. */
private fun stripped(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.filterKeys { it !in Private }.mapValues { stripped(it.value) })
    is JsonArray -> JsonArray(e.map(::stripped))
    else -> e
}

private val Private = setOf("visitorData", "trackingParams", "clickTrackingParams", "serviceTrackingParams", "loggingContext", "adSignalsInfo", "mainAppWebResponseContext")
