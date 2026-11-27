package app.aether.cast

import android.content.Context
import android.util.Log
import app.aether.Container
import app.aether.core.model.CanvasSource
import app.aether.core.model.Outcome
import app.aether.core.model.PlaybackState
import app.aether.core.model.Track
import app.aether.player.cast.CastController
import app.aether.ui.lyrics.Words
import app.aether.ui.lyrics.wordsFor
import app.aether.ui.player.isVideo
import app.aether.ui.player.quality
import com.google.android.gms.cast.framework.CastSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * What AETHER's own receiver shows beside the music (site/cast/): the song's name and who made it, its
 * cover at its largest, its film (as Now Playing would show it, on the same consents), its words (as
 * the lyrics view finds them, credit and all), what the decoder measured (as Now Playing says it), and
 * whether the listener wants the words up on the TV (Settings: Lyrics on the TV). Sent over the cast's
 * own channel as each song comes on, the name at once and the film and words as they come, again
 * whenever that choice changes, and whenever the TV asks (it may start after the phone has said its
 * piece). Google's standard receiver is sent none of it.
 */
class CastStage(private val context: Context, private val container: Container) {

    /** The last stage sent, sent again when the TV asks. */
    private var last: JSONObject? = null
    private var listening: CastSession? = null

    fun start(scope: CoroutineScope) {
        if (!CastOptions.ownReceiver(context)) return
        val song = container.playback.state.map { Song.of(it) }.distinctUntilChanged()
        val casting = CastController.state.map { it.casting }.distinctUntilChanged()
        scope.launch {
            combine(song, casting) { s, on -> s to on }.collectLatest { (s, on) ->
                if (!on || s == null) {
                    last = null
                    return@collectLatest
                }
                listen()
                // The name at once; the film and the words as each comes.
                val stage = stageOf(s)
                send(stage)
                val film = async { filmOf(s.track) }
                val words = async { wordsOf(s.track) }
                film.await()?.let { stage.put("film", it) }
                words.await()?.let { stage.put("words", it) }
                if (stage.has("film") || stage.has("words")) send(stage)
            }
        }
        // The listener's choice, as soon as it's made: the TV opens or closes the words with it.
        scope.launch {
            container.settings.castLyrics.collect { on ->
                val stage = last ?: return@collect
                if (stage.optBoolean("lyricsOnTv") != on) send(stage.put("lyricsOnTv", on))
            }
        }
    }

    /** What's playing, as far as the stage is concerned. */
    private data class Song(val track: Track, val index: Int, val count: Int, val format: String?) {
        companion object {
            fun of(state: PlaybackState): Song? {
                val track = state.current ?: return null
                return Song(track, state.index, state.queue.size, quality(state.heard))
            }
        }
    }

    private fun stageOf(s: Song): JSONObject = JSONObject().apply {
        val t = s.track
        put("type", "stage")
        put("id", t.id.value)
        put("title", t.title)
        put("artist", t.artistLine)
        t.album?.title?.let { put("album", it) }
        t.year?.let { put("year", it) }
        put("index", s.index + 1)
        put("count", s.count)
        s.format?.let { put("format", it) }
        put("explicit", t.explicit)
        t.artwork.url(1600)?.takeIf { it.startsWith("https://") }?.let { put("art", it) }
        t.durationMs?.let { put("duration", it) }
        put("lyricsOnTv", container.settings.castLyrics.value)
    }

    /** The film Now Playing would show: none for a video, a song on the phone, or with Song video off. */
    private suspend fun filmOf(t: Track): JSONObject? {
        if (!container.settings.songVideo.value) return null
        if (t.isVideo || t.id.source == container.local.id) return null
        val artist = t.artists.firstOrNull()?.name ?: return null
        val canvas = runCatching { container.canvases.find(t.id.value, t.title, artist, t.album?.title, apple = container.settings.artistArt.value) }.getOrNull() ?: return null
        return JSONObject().put("url", canvas.url).put("hls", canvas.from != CanvasSource.COMMUNITY)
    }

    /** The song's words, found as the lyrics view finds them; none for a video, or for a song without. */
    private suspend fun wordsOf(t: Track): JSONObject? {
        val own = runCatching { serviceWordsOf(t) }.getOrNull()
        val words = runCatching { wordsFor(container, t, own, ownPending = false) }.getOrNull() ?: return null
        val lines = JSONArray()
        var size = 0
        fun add(startMs: Long, text: String): Boolean {
            size += text.length + 16
            if (size > MaxWords) return false
            lines.put(JSONArray().put(startMs).put(text))
            return true
        }
        return when (words) {
            is Words.Timed -> {
                for (line in words.lines) if (!add(line.startMs, line.text)) break
                JSONObject().put("timed", true).put("estimated", words.estimated).put("lines", lines).put("by", words.credit)
            }
            is Words.Untimed -> {
                for (line in words.lines) if (!add(-1, line)) break
                JSONObject().put("timed", false).put("lines", lines).put("by", words.credit)
            }
            Words.Instrumental -> JSONObject().put("instrumental", true)
            else -> null
        }
    }

    /** The music service's own words for [t], as Now Playing asks its source for them. */
    private suspend fun serviceWordsOf(t: Track): app.aether.core.model.Lyrics? {
        if (t.isVideo) return null
        val source = container.sources.of(t.id) ?: return null
        val next = source.upNext(t) as? Outcome.Ok ?: return null
        val ref = next.value.lyrics ?: return null
        return (source.lyrics(ref) as? Outcome.Ok)?.value
    }

    /** Hears the TV's "I'm here" (it came up after the stage was sent), once a session. */
    private fun listen() {
        val session = CastController.session() ?: return
        if (session === listening) return
        listening = session
        runCatching {
            session.setMessageReceivedCallbacks(Namespace) { _, _, message ->
                if (message.contains("\"hello\"")) last?.let { send(it) }
            }
        }.onFailure { Log.w("AETHER cast", "couldn't listen to the TV", it) }
    }

    private fun send(stage: JSONObject) {
        last = stage
        val session = CastController.session() ?: return
        runCatching { session.sendMessage(Namespace, stage.toString()) }.onFailure { Log.w("AETHER cast", "couldn't reach the TV's stage", it) }
    }

    private companion object {
        /** The channel AETHER's receiver listens on (site/cast/cast.js says the same). */
        const val Namespace = "urn:x-cast:app.aether.stage"

        /** Words beyond this much aren't sent: a cast message holds 64 KB, with room left for the rest. */
        const val MaxWords = 48_000
    }
}
