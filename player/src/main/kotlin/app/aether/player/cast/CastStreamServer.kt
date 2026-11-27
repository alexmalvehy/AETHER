// Ported from LastWave-Native (app/.../playback/cast/CastStreamServer.kt, Clash-Projects/LastWave-Native;
// GPL-3.0). AETHER keeps several songs published at once (the TV holds the playing one and a few after
// it), opens them through its own sources, and keeps the range arithmetic where a test can reach it.
package app.aether.player.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import fi.iki.elonen.NanoHTTPD
import okhttp3.Call
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.util.UUID

/** What the TV is to be given: where the phone reads it, what it asks with, and what it is. */
class CastSource(val uri: Uri, val headers: Map<String, String>, val mimeType: String)

/**
 * The songs the TV plays, served from this phone over the local network, each at an address nobody
 * could guess. The TV can't read what the phone reads by itself: a kept song is a file here, a song
 * from the listener's own sources may sit on their network, and a stream is fetched the way the
 * phone's own app fetched it (with its own headers). So the phone reads it, exactly as it would to play
 * it, and hands the bytes on, a range at a time as the TV asks.
 *
 * While it runs the phone stays awake and on its Wi-Fi at full speed (the TV is listening to it). It's
 * for as long as a cast is.
 */
@OptIn(UnstableApi::class)
class CastStreamServer private constructor(
    private val context: Context,
    private val http: OkHttpClient,
    val address: String,
) : NanoHTTPD(address, 0) {

    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AETHER:Cast")
    @Suppress("DEPRECATION")
    private val wifiLock = context.getSystemService(WifiManager::class.java)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AETHER:Cast")

    /** What's published, by its path; the oldest let go once there are more than [KEPT]. */
    private val published = object : LinkedHashMap<String, CastSource>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CastSource>?) = size > KEPT
    }

    /** Publishes [source] and says where the TV finds it. */
    fun publish(source: CastSource): String {
        val path = "/${UUID.randomUUID()}"
        synchronized(published) { published[path] = source }
        return "http://$address:$listeningPort$path"
    }

    override fun stop() {
        synchronized(published) { published.clear() }
        try {
            super.stop()
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val source = synchronized(published) { published[session.uri] }
            ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (session.method == Method.OPTIONS) return cors(newFixedLengthResponse(""))
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
        // In bounded ranges, so the TV is sent the song at the line's speed (asked for whole, googlevideo
        // sends it at about playback speed).
        val factory = app.aether.player.ChunkedDataSource.Factory(
            DefaultDataSource.Factory(
                context,
                OkHttpDataSource.Factory(Call.Factory { request -> http.newCall(request) }).setDefaultRequestProperties(source.headers),
            ),
        )
        val spec = DataSpec.Builder().setUri(source.uri).build()
        var input: DataSourceInputStream? = null
        val probe = factory.createDataSource()
        try {
            val total = probe.open(spec).let { if (it == C.LENGTH_UNSET.toLong()) -1L else it }
            probe.close()
            if (total == 0L) return cors(newFixedLengthResponse(Response.Status.OK, source.mimeType, ""))
            val range = when (val asked = Ranges.of(session.headers["range"], total)) {
                Ranges.Answer.Whole -> null
                Ranges.Answer.Unsatisfiable -> return rangeError(total)
                is Ranges.Answer.Part -> asked
            }
            val start = range?.start ?: 0L
            val length = range?.let { it.end - it.start + 1 } ?: total
            input = DataSourceInputStream(
                factory.createDataSource(),
                spec.buildUpon().setPosition(start).setLength(if (length >= 0) length else C.LENGTH_UNSET.toLong()).build(),
            )
            val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
            // The same answer for a HEAD: NanoHTTPD sends it without its body, and the source is only
            // opened when it's first read.
            val response = if (length >= 0) {
                newFixedLengthResponse(status, source.mimeType, input, length)
            } else {
                newChunkedResponse(status, source.mimeType, input)
            }
            response.addHeader("Accept-Ranges", "bytes")
            if (range != null) response.addHeader("Content-Range", "bytes ${range.start}-${range.end}/$total")
            return cors(response)
        } catch (error: Exception) {
            input?.close()
            return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Stream unavailable"))
        } finally {
            runCatching { probe.close() }
        }
    }

    private fun rangeError(total: Long): Response =
        cors(newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "Invalid range"))
            .apply { if (total >= 0) addHeader("Content-Range", "bytes */$total") }

    private fun cors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Range")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges, Content-Length")
        // A TV's browser may ask before a page from the internet reaches an address on the home network.
        addHeader("Access-Control-Allow-Private-Network", "true")
    }

    companion object {
        /** Songs kept published: the one playing, the few after it, and the ones just played. */
        private const val KEPT = 8
        private const val SOCKET_READ_TIMEOUT = 15_000

        /**
         * A server on this phone's Wi-Fi (or wired) address, running. Null when the phone isn't on such
         * a network: a TV can only reach it from the same one.
         */
        fun start(context: Context, http: OkHttpClient): CastStreamServer? {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val address = manager.allNetworks.asSequence().filter { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress ?: return null
            val server = CastStreamServer(context.applicationContext, http, address)
            return try {
                server.start(SOCKET_READ_TIMEOUT, true)
                server.wakeLock.acquire(12 * 60 * 60 * 1000L)
                server.wifiLock.acquire()
                server
            } catch (error: Exception) {
                server.stop()
                null
            }
        }
    }
}

/** The byte range a request asks for, of a body [total] bytes long (-1 when that isn't known). */
internal object Ranges {
    sealed interface Answer {
        data object Whole : Answer
        data object Unsatisfiable : Answer
        data class Part(val start: Long, val end: Long) : Answer
    }

    private val BYTES = Regex("""bytes=(\d*)-(\d*)""")

    fun of(header: String?, total: Long): Answer {
        if (header == null) return Answer.Whole
        val match = BYTES.matchEntire(header.trim()) ?: return Answer.Unsatisfiable
        // A body of no known length can still be sent from its start (as a TV's first ask always is),
        // just not from anywhere else in it.
        if (total < 0) return if (match.groupValues[1] == "0" && match.groupValues[2].isEmpty()) Answer.Whole else Answer.Unsatisfiable
        if (total == 0L) return Answer.Unsatisfiable
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        val (start, end) = if (first.isEmpty()) {
            // "bytes=-500": the last 500.
            val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return Answer.Unsatisfiable
            (total - suffix).coerceAtLeast(0) to total - 1
        } else {
            val from = first.toLongOrNull() ?: return Answer.Unsatisfiable
            val to = if (last.isEmpty()) total - 1 else minOf(last.toLongOrNull() ?: return Answer.Unsatisfiable, total - 1)
            from to to
        }
        if (start >= total || end < start) return Answer.Unsatisfiable
        return Answer.Part(start, end)
    }
}
