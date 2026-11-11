package app.aether.sources.youtube

import app.aether.ytmusic.WebSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Extracted InnerTube `playbackTracking` payload from a `/youtubei/v1/player` response.
 */
data class PlaybackTracking(
    val videostatsPlaybackUrl: String,
    val videostatsWatchtimeUrl: String?,
    val cpn: String,
    val docid: String,
    val playbackTokens: Map<String, String> = emptyMap(),
)

/**
 * Reports continuous playback upstream to YouTube Music (`videostatsPlaybackUrl` and
 * `videostatsWatchtimeUrl`) so tracks appear in the listener's official YouTube Music history
 * and register for the Year-End Recap when opt-in history sync is enabled.
 */
class YouTubeHistoryTracker(
    http: OkHttpClient,
    private val cookies: () -> String?,
    private val region: () -> String = { "US" },
    private val resolver: YouTubeStreamResolver? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val client = http.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun reportPlayback(videoId: String, positionMs: Long = 12_000L) = withContext(Dispatchers.IO) {
        try {
            val rawCookies = cookies()?.takeIf { it.isNotBlank() } ?: return@withContext
            val session = WebSession.parse(rawCookies) ?: return@withContext
            val cookieHeader = buildCookieHeader(rawCookies, session)
            val cpn = generateCpn()
            val tracking = fetchAuthenticatedTracking(videoId, cpn, session, cookieHeader)
                ?: resolver?.playbackTracking(videoId)?.copy(cpn = cpn)
                ?: return@withContext

            val playedSec = (positionMs.coerceAtLeast(10_000L) / 1000.0)
            val cmt = String.format(Locale.US, "%.3f", playedSec)

            val playbackPingUrl = buildTrackingUrl(
                baseUrl = tracking.videostatsPlaybackUrl,
                cpn = tracking.cpn,
                docid = tracking.docid,
                extraParams = mapOf(
                    "ver" to "2",
                    "c" to CLIENT_NAME,
                    "cmt" to cmt,
                ),
            )
            if (playbackPingUrl != null) {
                dispatchTrackingPing(playbackPingUrl, session, cookieHeader)
            }

            val watchtimeBase = tracking.videostatsWatchtimeUrl
            if (!watchtimeBase.isNullOrBlank()) {
                val watchtimePingUrl = buildTrackingUrl(
                    baseUrl = watchtimeBase,
                    cpn = tracking.cpn,
                    docid = tracking.docid,
                    extraParams = mapOf(
                        "ver" to "2",
                        "c" to CLIENT_NAME,
                        "cmt" to cmt,
                        "st" to "0.000",
                        "et" to cmt,
                    ),
                )
                if (watchtimePingUrl != null) {
                    dispatchTrackingPing(watchtimePingUrl, session, cookieHeader)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Silent fallback: network timeouts or API blocks must never interrupt local playback.
        }
    }

    private fun fetchAuthenticatedTracking(
        videoId: String,
        cpn: String,
        session: WebSession,
        cookieHeader: String,
    ): PlaybackTracking? = try {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", region().takeIf { it.length == 2 }?.uppercase() ?: "US")
                }
                putJsonObject("user") { put("lockedSafetyMode", false) }
            }
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("signatureTimestamp", 20073)
                }
            }
        }
        val request = Request.Builder()
            .url("$ORIGIN/youtubei/v1/player?prettyPrint=false")
            .headers(authenticatedHeaders(session, cookieHeader))
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = lenientJson.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject ?: return null
            extractPlaybackTracking(json, videoId, cpn)
        }
    } catch (_: Throwable) {
        null
    }

    private fun dispatchTrackingPing(url: String, session: WebSession, cookieHeader: String) {
        val request = Request.Builder()
            .url(url)
            .headers(authenticatedHeaders(session, cookieHeader))
            .get()
            .build()
        client.newCall(request).execute().close()
    }

    private fun authenticatedHeaders(session: WebSession, cookieHeader: String): okhttp3.Headers {
        val nowSeconds = nowMs() / 1000L
        return okhttp3.Headers.Builder().apply {
            add("User-Agent", USER_AGENT)
            add("Origin", ORIGIN)
            add("Referer", "$ORIGIN/")
            add("X-Origin", ORIGIN)
            add("X-Goog-AuthUser", "0")
            add("X-Youtube-Client-Name", CLIENT_ID)
            add("X-Youtube-Client-Version", CLIENT_VERSION)
            add("Cookie", cookieHeader)
            session.authorization(nowSeconds, ORIGIN)?.let { add("Authorization", it) }
        }.build()
    }

    /**
     * Ensures VISITOR_INFO1_LIVE, HSID, SSID, SAPISID, and other session cookies from the raw
     * cookie store are preserved on the outbound tracking request.
     */
    private fun buildCookieHeader(rawCookies: String, session: WebSession): String {
        val map = LinkedHashMap<String, String>()
        rawCookies.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq > 0) {
                val k = part.substring(0, eq).trim()
                val v = part.substring(eq + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) map[k] = v
            }
        }
        for (priority in listOf("VISITOR_INFO1_LIVE", "HSID", "SSID", "APISID", "SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID")) {
            val v = map[priority]
            if (v != null) {
                map.remove(priority)
                map[priority] = v
            }
        }
        return if (map.isNotEmpty()) {
            map.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            session.cookieHeader
        }
    }

    private fun buildTrackingUrl(
        baseUrl: String,
        cpn: String,
        docid: String,
        extraParams: Map<String, String>,
    ): String? {
        val normalized = baseUrl.replace("https://s.youtube.com/", "$ORIGIN/")
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        val builder = parsed.newBuilder()
        if (parsed.queryParameter("docid") == null) {
            builder.setQueryParameter("docid", docid)
        }
        builder.setQueryParameter("cpn", cpn)
        for ((k, v) in extraParams) {
            builder.setQueryParameter(k, v)
        }
        return builder.build().toString()
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"
        private const val CLIENT_VERSION = "1.20260218.01.00"
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
        private val random = SecureRandom()

        fun generateCpn(): String = buildString(16) {
            repeat(16) { append(CPN_ALPHABET[random.nextInt(CPN_ALPHABET.length)]) }
        }

        /**
         * Extracts [PlaybackTracking] (`videostatsPlaybackUrl.baseUrl`, `videostatsWatchtimeUrl.baseUrl`,
         * `cpn`, `docid`, and playback tokens) from an InnerTube `player` JSON response.
         */
        fun extractPlaybackTracking(
            json: JsonObject,
            fallbackVideoId: String,
            fallbackCpn: String? = null,
        ): PlaybackTracking? {
            val trackingObj = json["playbackTracking"] as? JsonObject ?: return null
            val playbackUrl = (trackingObj["videostatsPlaybackUrl"] as? JsonObject)
                ?.let { (it["baseUrl"] as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val watchtimeUrl = (trackingObj["videostatsWatchtimeUrl"] as? JsonObject)
                ?.let { (it["baseUrl"] as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotBlank() }
            val atrUrl = (trackingObj["atrUrl"] as? JsonObject)
                ?.let { (it["baseUrl"] as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotBlank() }

            val parsedUrl = playbackUrl.toHttpUrlOrNull()
            val videoDetailsId = (json["videoDetails"] as? JsonObject)
                ?.let { (it["videoId"] as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotBlank() }
            val docid = videoDetailsId
                ?: parsedUrl?.queryParameter("docid")?.takeIf { it.isNotBlank() }
                ?: fallbackVideoId
            val cpn = parsedUrl?.queryParameter("cpn")?.takeIf { it.isNotBlank() }
                ?: fallbackCpn
                ?: generateCpn()

            val tokens = LinkedHashMap<String, String>()
            for (tokenKey in listOf("vm", "ptk", "plid", "ei", "of", "cl", "fexp")) {
                parsedUrl?.queryParameter(tokenKey)?.takeIf { it.isNotBlank() }?.let { tokens[tokenKey] = it }
            }
            atrUrl?.let { tokens["atrUrl"] = it }

            return PlaybackTracking(
                videostatsPlaybackUrl = playbackUrl,
                videostatsWatchtimeUrl = watchtimeUrl,
                cpn = cpn,
                docid = docid,
                playbackTokens = tokens,
            )
        }
    }
}
