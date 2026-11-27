@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package app.aether

import android.content.Intent
import android.util.Log
import app.aether.core.model.AccountPlaylist
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: playlist sync checked live against the signed-in account, step by step, through the
 * real [PlaylistSync] (`adb logcat -s AetherSyncCheck`). It uses five YouTube Music songs from the queue.
 *
 * - `--es aether.debug synccheck`: makes "AETHER sync test" here, syncs it (so it's made in the account,
 *   private), then adds, removes and renames on each side in turn, and turns its sync off. Left as it is
 *   to be looked at.
 * - `--es aether.debug syncclean`: deletes "AETHER sync test" from the account and from here.
 * - `--es aether.debug likecheck`: likes a song the account hasn't liked, then unlikes it. This phone's
 *   own Liked songs aren't touched.
 */
object SyncCheck {
    private const val TAG = "AetherSyncCheck"
    private const val NAME = "AETHER sync test"

    fun handle(intent: Intent?, container: Container, context: android.content.Context) {
        when (intent?.getStringExtra("aether.debug")) {
            "synccheck" -> container.scope.launch(Dispatchers.IO) { runCatching { playlists(container) }.onFailure { say("FAIL threw $it") } }
            "syncclean" -> container.scope.launch(Dispatchers.IO) { runCatching { clean(container) }.onFailure { say("FAIL threw $it") } }
            "likecheck" -> container.scope.launch(Dispatchers.IO) { runCatching { likes(container) }.onFailure { say("FAIL threw $it") } }
            "syncprobe" -> container.scope.launch(Dispatchers.IO) { runCatching { probe(container) }.onFailure { say("FAIL threw $it") } }
            "adoptcheck" -> container.scope.launch(Dispatchers.IO) { runCatching { adopt(container) }.onFailure { say("FAIL threw $it") } }
            // How fast the current song's stream comes, whole against in ranges (player/src/debug/StreamSpeedCheck).
            "dlspeed" -> container.scope.launch(Dispatchers.IO) {
                runCatching {
                    val id = container.playback.state.value.current?.id ?: return@runCatching say("FAIL nothing in the queue")
                    app.aether.player.StreamSpeedCheck.measure(context.applicationContext, id).forEach(::say)
                    say("DONE speed")
                }.onFailure { say("FAIL threw $it") }
            }
            "gonecheck" -> container.scope.launch(Dispatchers.IO) { runCatching { gone(container) }.onFailure { say("FAIL threw $it") } }
            "syncstate" -> container.scope.launch(Dispatchers.IO) {
                runCatching { state(container, intent.getStringExtra("aether.ids").orEmpty(), intent.getStringExtra("aether.song").orEmpty()) }
                    .onFailure { say("FAIL threw $it") }
            }
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
    }

    private fun check(what: String, ok: Boolean, detail: String = "") = say("${if (ok) "PASS" else "FAIL"} $what${if (detail.isNotEmpty()) " ($detail)" else ""}")

    private fun songs(container: Container): List<Track> {
        val source = container.sources.primary
        return container.playback.state.value.queue.filter { it.id.source == source.id }.distinctBy { it.id }
    }

    private suspend fun playlists(container: Container) {
        val sync = container.playlistSync
        val library = container.library
        val source = container.sources.primary
        val songs = songs(container).take(5)
        if (songs.size < 5) return say("FAIL needs five YouTube Music songs in the queue, has ${songs.size}")
        val (a, b, c, d, e) = songs
        say("songs: ${songs.joinToString { "${it.title} [${it.id.key}]" }}")

        suspend fun synced(key: String, change: suspend () -> Unit): Boolean {
            val before = sync.links.value[key]?.at ?: 0L
            change()
            return withTimeoutOrNull(60_000) { sync.links.first { (it[key]?.at ?: 0L) > before } } != null
        }
        suspend fun there(key: String): AccountPlaylist? =
            sync.links.value[key]?.remote?.let { (source.accountPlaylist(MediaId(it)) as? Outcome.Ok)?.value }
        suspend fun here(id: Long): List<String> = library.entries(id).first().map { it.track.id.key }
        fun ids(vararg t: Track) = t.map { it.id.key }

        // 1. Made here, synced: made in the account with its songs.
        val id = library.createPlaylist(NAME, tracks = listOf(a, b))
        val key = PlaylistSync.playlist(id)
        check("turned on, synced", synced(key) { sync.setOn(key, true) })
        val made = there(key)
        check("made in the account with its two songs", made?.entries?.map { it.track.id.key } == ids(a, b), "${made?.entries?.map { it.track.id.key }}")
        check("the account's copy is the listener's own", made?.editable == true)
        say("account copy: ${sync.links.value[key]?.remote}")

        // 2. Added here: added there.
        check("a song added here synced", synced(key) { library.add(id, listOf(c)) })
        check("...and is there", there(key)?.entries?.map { it.track.id.key } == ids(a, b, c), "${there(key)?.entries?.map { it.track.id.key }}")

        // 3. Taken out here: taken out there.
        check("a song taken out here synced", synced(key) { library.remove(id, library.entries(id).first().first { it.track.id == a.id }.entryId) })
        check("...and is gone from there", there(key)?.entries?.map { it.track.id.key } == ids(b, c), "${there(key)?.entries?.map { it.track.id.key }}")

        // 4. Added there: added here.
        val remoteId = MediaId(sync.links.value[key]!!.remote!!)
        check("a song added in the account", source.addToAccountPlaylist(remoteId, listOf(d)) is Outcome.Ok)
        check("...synced", synced(key) { sync.request(key) })
        check("...and is here", here(id) == ids(b, c, d), "${here(id)}")

        // 5. Taken out there: taken out here.
        val entry = there(key)?.entries?.firstOrNull { it.track.id == b.id }
        check("a song taken out in the account", entry != null && source.removeFromAccountPlaylist(remoteId, listOf(entry)) is Outcome.Ok)
        check("...synced", synced(key) { sync.request(key) })
        check("...and is gone from here", here(id) == ids(c, d), "${here(id)}")

        // 6. Renamed here: renamed there.
        check("renamed here, synced", synced(key) { library.editPlaylist(id, "$NAME 2", null) })
        check("...and named so there", there(key)?.playlist?.title == "$NAME 2", "${there(key)?.playlist?.title}")

        // 7. Off: a change here stays here.
        sync.setOn(key, false)
        library.add(id, listOf(e))
        delay(8_000)
        check("off: a song added here stays here", here(id) == ids(c, d, e), "${here(id)}")
        check("off: the account's copy is as it was", there(key)?.entries?.map { it.track.id.key } == ids(c, d), "${there(key)?.entries?.map { it.track.id.key }}")
        say("DONE playlist check; local playlist $id left for a look; syncclean removes both copies")
    }

    /**
     * One of the account's own playlists taken in (as its page's sync key does): a throwaway one, made in the
     * account first. [clean] removes it.
     */
    private suspend fun adopt(container: Container) {
        val sync = container.playlistSync
        val library = container.library
        val source = container.sources.primary
        val songs = songs(container).take(3)
        if (songs.size < 3) return say("FAIL needs three YouTube Music songs in the queue")
        val made = source.createAccountPlaylist("$NAME adopt", null, songs.take(2))
        val remote = (made as? Outcome.Ok)?.value ?: return say("FAIL couldn't make it in the account: $made")
        delay(2_000)
        val id = sync.adopt(app.aether.core.model.Playlist(remote, "$NAME adopt"))
        check("taken in as a playlist here", id != null)
        if (id == null) return
        val key = PlaylistSync.playlist(id)
        val here = library.entries(id).first().map { it.track.id.key }
        check("...with the account's two songs", here == songs.take(2).map { it.id.key }, "$here")
        check("...synced, tied to the account's copy", sync.links.value[key]?.let { it.on && it.remote == remote.value } == true)
        val before = sync.links.value[key]?.at ?: 0L
        library.add(id, listOf(songs[2]))
        val synced = withTimeoutOrNull(60_000) { sync.links.first { (it[key]?.at ?: 0L) > before } } != null
        val there = (source.accountPlaylist(remote) as? Outcome.Ok)?.value?.entries?.map { it.track.id.key }
        check("a song added here goes to the account", synced && there == songs.map { it.id.key }, "$there")
        say("DONE adopt check; syncclean removes it")
    }

    /** A synced playlist whose copy is deleted in the account: its sync turns off, and it stays here. */
    private suspend fun gone(container: Container) {
        val sync = container.playlistSync
        val library = container.library
        val source = container.sources.primary
        val songs = songs(container).take(2)
        if (songs.size < 2) return say("FAIL needs two YouTube Music songs in the queue")
        val id = library.createPlaylist("$NAME gone", tracks = songs)
        val key = PlaylistSync.playlist(id)
        val before = sync.links.value[key]?.at ?: 0L
        sync.setOn(key, true)
        val synced = withTimeoutOrNull(60_000) { sync.links.first { (it[key]?.at ?: 0L) > before } } != null
        check("synced, made in the account", synced)
        val remote = sync.links.value[key]?.remote?.let(::MediaId) ?: return say("FAIL no copy in the account")
        check("its copy deleted in the account", source.deleteAccountPlaylist(remote) is Outcome.Ok)
        delay(5_000)
        sync.request(key)
        val off = withTimeoutOrNull(60_000) { sync.links.first { it[key]?.on == false } } != null
        check("its sync turned itself off", off)
        check("...and it stays here with its songs", library.entries(id).first().size == 2)
        say("DONE gone check")
    }

    /** What the account says now of playlists [ids] (comma separated) and of one [song] among its liked. */
    private suspend fun state(container: Container, ids: String, song: String) {
        val source = container.sources.primary
        for (id in ids.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val r = source.accountPlaylist(MediaId.of(source.id, id))
            say("playlist $id reads: ${if (r is Outcome.Ok) "a playlist, ${r.value.entries.size} songs, own=${r.value.editable}" else r}")
        }
        if (song.isNotEmpty()) {
            val liked = (source.accountLiked() as? Outcome.Ok)?.value
            say("song $song is ${when { liked == null -> "unknown (liked songs unreadable)"; liked.any { it.id.key == song } -> "STILL LIKED"; else -> "not liked" }}")
        }
        say("DONE state")
    }

    /** How the account answers just after a playlist is made, and after a rename, over time. */
    private suspend fun probe(container: Container) {
        val sync = container.playlistSync
        val library = container.library
        val source = container.sources.primary
        val song = songs(container).firstOrNull() ?: return say("FAIL no song in the queue")
        // A rename of the test playlist's copy, read back over half a minute.
        val test = library.playlistsWithPreview().first().firstOrNull { it.title.startsWith(NAME) }
        val remote = test?.let { sync.links.value[PlaylistSync.playlist(it.id)]?.remote }?.let(::MediaId)
        if (remote != null) {
            val renamed = source.renameAccountPlaylist(remote, "$NAME 3")
            say("rename answered: $renamed")
            for (wait in listOf(0L, 3_000L, 10_000L, 20_000L)) {
                delay(wait)
                val t = (source.accountPlaylist(remote) as? Outcome.Ok)?.value?.playlist?.title
                say("after +${wait}ms its name there reads: $t")
            }
        }
        // A playlist made and read straight back.
        val made = source.createAccountPlaylist("$NAME probe", null, listOf(song))
        say("made: $made")
        val id = (made as? Outcome.Ok)?.value ?: return
        for (wait in listOf(0L, 1_000L, 2_000L, 4_000L)) {
            delay(wait)
            val r = source.accountPlaylist(id)
            say("read back after +${wait}ms: ${if (r is Outcome.Ok) "ok, ${r.value.entries.size} songs, own=${r.value.editable}" else r}")
        }
        say("deleted the probe: ${source.deleteAccountPlaylist(id)}")
        say("DONE probe")
    }

    private suspend fun clean(container: Container) {
        val sync = container.playlistSync
        val library = container.library
        val source = container.sources.primary
        val mine = library.playlistsWithPreview().first().filter { it.title.startsWith(NAME) }
        for (p in mine) {
            val key = PlaylistSync.playlist(p.id)
            val remote = sync.links.value[key]?.remote
            if (remote != null) {
                check("deleted from the account", source.deleteAccountPlaylist(MediaId(remote)) is Outcome.Ok)
                val after = source.accountPlaylist(MediaId(remote))
                say("reading it now gives: ${(after as? Outcome.Err)?.error ?: "a playlist still"}")
            }
            library.deletePlaylist(p.id)
            say("deleted here: ${p.title} (${p.id})")
        }
        if (mine.isEmpty()) say("nothing to clean")
        say("DONE clean")
    }

    private suspend fun likes(container: Container) {
        val source = container.sources.primary
        val liked = (source.accountLiked() as? Outcome.Ok)?.value?.map { it.id }?.toSet() ?: return say("FAIL couldn't read the account's liked songs")
        say("the account likes ${liked.size} songs")
        val song = songs(container).firstOrNull { it.id !in liked } ?: return say("FAIL every queued song is liked already")
        say("song: ${song.title} [${song.id.key}]")
        check("liked in the account", source.setAccountLiked(song, true) is Outcome.Ok)
        delay(1_500)
        check("...and listed among its liked songs", (source.accountLiked() as? Outcome.Ok)?.value?.any { it.id == song.id } == true)
        check("unliked in the account", source.setAccountLiked(song, false) is Outcome.Ok)
        delay(1_500)
        check("...and gone from its liked songs", (source.accountLiked() as? Outcome.Ok)?.value?.none { it.id == song.id } == true)
        say("DONE like check")
    }
}
