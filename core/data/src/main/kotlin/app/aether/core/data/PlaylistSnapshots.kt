package app.aether.core.data

import android.content.Context
import app.aether.core.model.MediaId
import app.aether.core.model.Playlist
import app.aether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What a playlist from the catalogue holds, as last seen online, kept on the phone so that its kept
 * songs can be found without a connection. [keptFrom] marks a playlist some of whose songs were kept
 * from its page: it belongs in the Library as much as one that was saved.
 */
class PlaylistSnapshots(context: Context) {
    /** [keptAt]: when songs were first kept from it, which is when it came into the library (0 if before that was noted). */
    data class Snapshot(val playlist: Playlist, val tracks: List<Track>, val keptFrom: Boolean, val keptAt: Long = 0)

    private val file = File(context.filesDir, "playlist-snapshots.json")
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _all = MutableStateFlow(read())
    val all: StateFlow<Map<MediaId, Snapshot>> = _all.asStateFlow()

    /** [playlist] as just loaded: its songs so far (a long one adds pages as they come). */
    fun seen(playlist: Playlist, tracks: List<Track>) {
        val before = _all.value[playlist.id]
        if (before != null && before.tracks.map { it.id } == tracks.map { it.id } && before.playlist == playlist) return
        update(playlist.id, Snapshot(playlist, tracks, before?.keptFrom ?: false, before?.keptAt ?: 0))
    }

    /** Songs of [id] were kept from its page. */
    fun keptFrom(id: MediaId) {
        val before = _all.value[id] ?: return
        if (!before.keptFrom) update(id, before.copy(keptFrom = true, keptAt = System.currentTimeMillis()))
    }

    private fun update(id: MediaId, snapshot: Snapshot) {
        _all.value = _all.value + (id to snapshot)
        val copy = _all.value
        io.launch { runCatching { write(copy) } }
    }

    @Synchronized
    private fun write(all: Map<MediaId, Snapshot>) {
        val out = JSONArray()
        all.values.forEach { s ->
            out.put(JSONObject().apply {
                put("playlist", s.playlist.toSavedJson())
                put("tracks", JSONArray().apply { s.tracks.forEach { put(it.toJson()) } })
                put("keptFrom", s.keptFrom)
                put("keptAt", s.keptAt)
            })
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(out.toString())
        tmp.renameTo(file)
    }

    private fun read(): Map<MediaId, Snapshot> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val playlist = playlistFromJson(o.getString("playlist")) ?: return@mapNotNull null
            val list = o.getJSONArray("tracks")
            val tracks = (0 until list.length()).mapNotNull { trackFromJson(list.getString(it)) }
            playlist.id to Snapshot(playlist, tracks, o.optBoolean("keptFrom", false), o.optLong("keptAt", 0))
        }.toMap()
    }.getOrDefault(emptyMap())
}
