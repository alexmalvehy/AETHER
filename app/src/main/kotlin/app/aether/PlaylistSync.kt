package app.aether

import android.content.Context
import app.aether.core.data.Account
import app.aether.core.data.Library
import app.aether.core.data.SourceManager
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.Playlist
import app.aether.core.model.SourceError
import app.aether.core.model.SyncPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The listener's playlists, and their Liked songs, kept in step with their YouTube Music account, each
 * for as long as its sync key is on.
 *
 * On, a playlist made here is made there too (private), and from then on what either side gains or lets go
 * of goes to the other ([SyncPlan]), and so does its name: when it's turned on, when this phone comes
 * online signed in, when its page is opened, and a moment after it's changed here. Liked songs are the
 * account's liked songs. Off, it stays as it last was, and changes stay where they're made; on again, it
 * catches up from where both last agreed. Deleting a playlist here only ends its sync: its copy in the
 * account stays.
 *
 * Every change to the account is read back before it's counted, so a song the account wouldn't take is
 * kept here only (and not offered again), and a step that didn't take is made again next time. A list is
 * never changed from a part of the other: reading the account's copy fails whole or answers whole.
 * What ties each list to the account is kept on the phone (playlist-sync.json), nowhere else.
 */
class PlaylistSync(
    context: Context,
    private val scope: CoroutineScope,
    private val account: Account,
    private val sources: SourceManager,
    private val library: Library,
    private val online: Online,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One list's tie to the account. */
    data class Link(
        /** The account's copy ("ytm:PL…"; "ytm:LM", its liked songs); null until it's been made there. */
        val remote: String?,
        val on: Boolean,
        /** What both held when they last agreed: songs' ids. */
        val base: Set<String> = emptySet(),
        /** The name both had then. */
        val name: String? = null,
        /** The name it had before this phone last renamed it there: read back, it's the account lagging, not a rename. */
        val renamedFrom: String? = null,
        /** Songs the account wouldn't take (offered twice and not kept): kept here only. */
        val refused: Set<String> = emptySet(),
        /** Offered once and not there when read back: offered again, and refused if they miss again. */
        val missed: Set<String> = emptySet(),
        /** When they last agreed; 0, never. */
        val at: Long = 0,
    )

    private val file = File(context.filesDir, "playlist-sync.json")
    private val _links = MutableStateFlow(read())

    /** Every list's tie, by its key ([playlist] or [LIKED]). */
    val links: StateFlow<Map<String, Link>> = _links.asStateFlow()

    private val _syncing = MutableStateFlow<Set<String>>(emptySet())

    /** The lists being brought into step right now. */
    val syncing: StateFlow<Set<String>> = _syncing.asStateFlow()

    val signedIn: StateFlow<Boolean> = account.cookies.map { it != null }.stateIn(scope, SharingStarted.Eagerly, account.cookies.value != null)

    private val _notes = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** What the listener should be told: a list that can no longer be kept in step, and why. */
    val notes: SharedFlow<String> = _notes.asSharedFlow()

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val waiting = ConcurrentHashMap.newKeySet<String>()

    /** Lists turned on by the listener a moment ago, whose first sync should say if it can't be done. */
    private val asked = ConcurrentHashMap.newKeySet<String>()

    /** What each list was left as by its last sync, so the sync's own changes don't start it again. */
    private val leftAs = ConcurrentHashMap<String, Any>()

    /** Told once a session: signing in again is the listener's to do. */
    @Volatile private var toldSignIn = false

    fun start() {
        // Requests are worked through one at a time, so the account is never asked two things at once.
        scope.launch(Dispatchers.IO) {
            for (key in queue) {
                waiting.remove(key)
                run(key)
            }
        }
        // Signed in and online: every list that's on, brought up to date (and again each time the phone is back).
        scope.launch {
            combine(account.cookies, online.now) { cookies, up -> cookies != null && up }
                .distinctUntilChanged()
                .collect { ready ->
                    if (!ready) return@collect
                    delay(START_DELAY_MS)
                    links.value.filterValues { it.on }.keys.forEach(::request)
                }
        }
        // A change made here to a list that's on goes there once it has settled.
        scope.launch {
            links.map { all -> all.filterValues { it.on }.keys }.distinctUntilChanged().collectLatest { keys ->
                coroutineScope { keys.forEach { key -> launch { watch(key) } } }
            }
        }
        // A playlist deleted here takes its tie with it; its copy in the account stays.
        scope.launch {
            library.playlistsWithPreview().collect { lists ->
                val here = lists.map { playlist(it.id) }.toSet()
                val gone = links.value.keys.filter { it.startsWith(LOCAL) && it !in here }
                if (gone.isNotEmpty()) change { it - gone.toSet() }
            }
        }
    }

    /** Asks for [key] to be brought into step, if it's on; [now] or not, it waits its turn. */
    fun request(key: String) {
        if (links.value[key]?.on != true) return
        if (waiting.add(key)) queue.trySend(key)
    }

    /** A list's page opened: brought up to date, if it's on and hasn't been for a minute. */
    fun refresh(key: String) {
        val link = links.value[key] ?: return
        if (link.on && now() - link.at > FRESH_MS) request(key)
    }

    /** Turns [key]'s sync on (and makes its copy in the account, if it has none) or off. */
    fun setOn(key: String, on: Boolean) {
        if (on) {
            change { all ->
                val link = all[key] ?: Link(remote = if (key == LIKED) likedRemote() else null, on = true)
                all + (key to link.copy(on = true))
            }
            asked += key
            request(key)
            // The account's Liked Music is these same songs now: not listed beside them.
            if (key == LIKED) scope.launch(Dispatchers.IO) {
                library.savedPlaylists.first().filter { it.id.value == likedRemote() }.forEach { library.save(it, keep = false) }
            }
        } else {
            change { all -> all[key]?.let { all + (key to it.copy(on = false)) } ?: all }
        }
    }

    /**
     * One of the account's own playlists made one of this phone's, kept in step with it: its songs as the
     * account has them, its place in the Library taken by the copy. The copy's id, or null if the account
     * couldn't be asked or the playlist isn't the listener's to change.
     */
    suspend fun adopt(playlist: Playlist): Long? = withContext(Dispatchers.IO) {
        val source = sources.of(playlist.id) ?: return@withContext null
        val there = source.accountPlaylist(playlist.id)
        val remote = (there as? Outcome.Ok)?.value
        if (remote == null) {
            note((there as Outcome.Err).error, playlist.title, asked = true)
            return@withContext null
        }
        if (!remote.editable) {
            _notes.tryEmit("“${playlist.title}” isn't yours to change on YouTube Music")
            return@withContext null
        }
        val id = library.createPlaylist(remote.playlist.title, tracks = remote.entries.map { it.track })
        val key = playlist(id)
        leftAs[key] = remote.entries.map { it.track.id.value } to remote.playlist.title
        change { it + (key to Link(remote = playlist.id.value, on = true, base = remote.entries.map { e -> e.track.id.value }.toSet(), name = remote.playlist.title, at = now())) }
        library.save(playlist, keep = false)
        id
    }

    /**
     * The account's playlists this phone keeps a copy of (not to be listed twice), and its liked songs while
     * they're kept in step here.
     */
    fun linkedRemotes(): Set<String> =
        links.value.filter { (key, link) -> link.remote != null && (link.on || key != LIKED) }.values.mapNotNull { it.remote }.toSet()

    // --- One list ---------------------------------------------------------------------------------------

    private suspend fun run(key: String) {
        val link = links.value[key] ?: return
        if (!link.on || account.cookies.value == null) return
        val source = sources.primary
        _syncing.update { it + key }
        try {
            if (key == LIKED) liked(source, link) else playlist(key, source, link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing is lost: what both last agreed on stands, and the next sync starts from there.
            android.util.Log.w(TAG, "sync stopped: $e")
        } finally {
            asked -= key
            _syncing.update { it - key }
        }
    }

    private suspend fun playlist(key: String, source: MusicSource, start: Link) {
        val id = key.removePrefix(LOCAL).toLongOrNull() ?: return
        val local = library.playlist(id).first() ?: return
        var link = start
        // Not in the account yet: made there with what it holds now. Its id is kept at once, so it's
        // never made twice.
        if (link.remote == null) {
            val made = source.createAccountPlaylist(local.title, local.description, library.entries(id).first().map { it.track })
            val remoteId = (made as? Outcome.Ok)?.value ?: return note((made as Outcome.Err).error, local.title, key in asked)
            link = link.copy(remote = remoteId.value)
            change { all -> all[key]?.let { all + (key to it.copy(remote = remoteId.value)) } ?: all }
        }
        val remoteId = MediaId(link.remote!!)
        // A playlist just made can't be read back for a moment (measured: not at once, whole a second later).
        var there = source.accountPlaylist(remoteId)
        if (start.remote == null) for (wait in MADE_WAITS_MS) {
            if (there is Outcome.Ok) break
            delay(wait)
            there = source.accountPlaylist(remoteId)
        }
        val remote = (there as? Outcome.Ok)?.value ?: return gone(key, (there as Outcome.Err).error, local.title, link, source)
        if (!remote.editable) {
            // Another account's, now: left as it is here.
            change { all -> all[key]?.let { all + (key to it.copy(on = false)) } ?: all }
            _notes.tryEmit("“${local.title}” can't be changed in this YouTube Music account, so it's no longer synced")
            return
        }
        val entries = library.entries(id).first()
        val here = entries.map { it.track }.filter { it.id.source == source.id }
        val theirs = remote.entries.associateBy { it.track.id.value }
        val plan = SyncPlan.of(link.base, here.map { it.id.value }, remote.entries.map { it.track.id.value }, link.refused)

        // Its name. The account can answer with the name it had a moment before a rename (seen once on
        // the A059): that's this phone's rename not shown yet, so it's sent again rather than taken back.
        var name = link.name ?: local.title
        var renamedFrom = link.renamedFrom
        val step = SyncPlan.name(link.name, local.title, remote.playlist.title).let {
            if (it is SyncPlan.Name.Pull && it.name == link.renamedFrom) SyncPlan.Name.Push(local.title) else it
        }
        when (step) {
            SyncPlan.Name.Same -> { name = local.title; renamedFrom = null }
            is SyncPlan.Name.Push -> when (val r = source.renameAccountPlaylist(remoteId, step.name)) {
                is Outcome.Ok -> { renamedFrom = remote.playlist.title.takeIf { it != step.name }; name = step.name }
                is Outcome.Err -> android.util.Log.i(TAG, "couldn't rename: ${r.error}")
            }
            is SyncPlan.Name.Pull -> { library.editPlaylist(id, step.name, local.description); name = step.name; renamedFrom = null }
        }

        // There.
        val mine = here.associateBy { it.id.value }
        val added = plan.push.isNotEmpty() && source.addToAccountPlaylist(remoteId, plan.push.mapNotNull { mine[it] }) is Outcome.Ok
        val taken = plan.unpush.isNotEmpty() && source.removeFromAccountPlaylist(remoteId, plan.unpush.mapNotNull { theirs[it] }) is Outcome.Ok

        // Here: what came there goes at the end, in the account's order.
        if (plan.pull.isNotEmpty()) library.add(id, plan.pull.mapNotNull { theirs[it]?.track })
        if (plan.unpull.isNotEmpty()) {
            val drop = plan.unpull.toSet()
            entries.filter { it.track.id.value in drop }.forEach { library.remove(id, it.entryId) }
        }

        // What the account holds now, read back if it was changed.
        var pushed = if (added) plan.push.toSet() else emptySet()
        var unpushed = if (taken) plan.unpush.toSet() else emptySet()
        var refused = link.refused - theirs.keys
        var missed = emptySet<String>()
        if (added || taken) {
            val after = (source.accountPlaylist(remoteId) as? Outcome.Ok)?.value?.entries?.map { it.track.id.value }?.toSet()
            if (after == null) {
                // Not known: both steps are made again next time (neither does anything twice).
                pushed = emptySet()
                unpushed = emptySet()
            } else {
                val notKept = pushed - after
                refused = refused + (notKept intersect link.missed)
                missed = notKept - refused
                pushed = pushed intersect after
                unpushed = unpushed - after
            }
        }
        val base = plan.base(pushed = pushed, unpushed = unpushed)
        // What this sync left it as, from what it was and its own changes (read back from the database, it
        // would count a change the listener made meanwhile as the sync's, and never send it).
        val dropped = plan.unpull.toSet()
        leftAs[key] = (entries.map { it.track.id.value }.filter { it !in dropped } + plan.pull.filter { it in theirs }) to
            (if (step is SyncPlan.Name.Pull) step.name else local.title)
        change { all -> all[key]?.let { all + (key to it.copy(base = base, name = name, renamedFrom = renamedFrom, refused = refused, missed = missed, at = now())) } ?: all }
    }

    private suspend fun liked(source: MusicSource, link: Link) {
        val there = source.accountLiked()
        val remote = (there as? Outcome.Ok)?.value ?: return note((there as Outcome.Err).error, "Liked songs", LIKED in asked)
        val likedBefore = library.likedIds.first()
        val here = library.liked.first().filter { it.id.source == source.id }
        val plan = SyncPlan.of(link.base, here.map { it.id.value }, remote.map { it.id.value }, link.refused)
        val mine = here.associateBy { it.id.value }
        val theirs = remote.associateBy { it.id.value }

        // There, a song at a time, as liking one is; a refusal of the whole account stops it for now.
        val pushed = mutableSetOf<String>()
        val unpushed = mutableSetOf<String>()
        for ((ids, liked, done) in listOf(Triple(plan.push, true, pushed), Triple(plan.unpush, false, unpushed))) {
            for (tid in ids) {
                val track = (if (liked) mine[tid] else theirs[tid]) ?: continue
                when (val r = source.setAccountLiked(track, liked)) {
                    is Outcome.Ok -> done += tid
                    is Outcome.Err -> if (r.error.stops()) break
                }
                delay(LIKE_GAP_MS)
            }
        }

        // Here: the account's in its order, the most recently liked first.
        if (plan.pull.isNotEmpty()) library.likeAll(plan.pull.mapNotNull { theirs[it] })
        plan.unpull.forEach { tid -> mine[tid]?.let { library.setLiked(it, false) } }

        var refused = link.refused - theirs.keys
        var missed = emptySet<String>()
        var confirmedPush: Set<String> = pushed
        var confirmedUnpush: Set<String> = unpushed
        if (pushed.isNotEmpty() || unpushed.isNotEmpty()) {
            val after = (source.accountLiked() as? Outcome.Ok)?.value?.map { it.id.value }?.toSet()
            if (after == null) {
                confirmedPush = emptySet()
                confirmedUnpush = emptySet()
            } else {
                // A video the account likes but doesn't list among its songs, twice: kept here, not liked again.
                val notKept = pushed - after
                refused = refused + (notKept intersect link.missed)
                missed = notKept - refused
                confirmedPush = pushed intersect after
                confirmedUnpush = unpushed - after
            }
        }
        val base = plan.base(pushed = confirmedPush, unpushed = confirmedUnpush)
        leftAs[LIKED] = likedBefore - plan.unpull.toSet() + plan.pull.filter { it in theirs }
        change { all -> all[LIKED]?.let { all + (LIKED to it.copy(base = base, refused = refused, missed = missed, at = now())) } ?: all }
    }

    // --- Watching for changes made here -----------------------------------------------------------------

    /**
     * Each list as it stands, against what its last sync left it as: a change made before this began
     * watching (a song added the moment it was turned on, or while the app was closed) counts as well.
     */
    @OptIn(FlowPreview::class)
    private suspend fun watch(key: String) {
        changesOf(key).distinctUntilChanged().debounce(SETTLE_MS).filter { it != leftAs[key] }.collect { request(key) }
    }

    private fun changesOf(key: String): Flow<Any> =
        if (key == LIKED) library.likedIds.map { it }
        else {
            val id = key.removePrefix(LOCAL).toLong()
            combine(library.entries(id).map { e -> e.map { it.track.id.value } }, library.playlist(id).map { it?.title }) { ids, title -> ids to title }
        }

    // --- When it can't be done ----------------------------------------------------------------------------

    /**
     * The account's copy couldn't be read: gone, or not now. A playlist deleted in the account doesn't read
     * as missing (measured: its page reads as unreadable, as a moment's trouble does), so one that has been
     * in step before is looked for in the account's library: not listed there, it's gone. One never yet in
     * step isn't judged so, since a playlist just made can't be read for a moment either.
     */
    private suspend fun gone(key: String, error: SourceError, title: String, link: Link, source: MusicSource) {
        val missing = error is SourceError.NotFound ||
            (error is SourceError.Server && link.at > 0 && link.remote != null && !listed(source, link.remote))
        if (missing) {
            change { all -> all[key]?.let { all + (key to it.copy(on = false, remote = null, base = emptySet(), name = null, refused = emptySet(), missed = emptySet())) } ?: all }
            _notes.tryEmit("“$title” is gone from YouTube Music. It stays here, no longer synced")
        } else note(error, title, key in asked)
    }

    /** Whether the account's library lists [remote]; when the library can't be read, it's taken to. */
    private suspend fun listed(source: MusicSource, remote: String): Boolean {
        val all = (source.accountPlaylists() as? Outcome.Ok)?.value?.takeIf { it.isNotEmpty() } ?: return true
        return all.any { it.id.value == remote }
    }

    private fun note(error: SourceError, title: String, asked: Boolean) {
        android.util.Log.i(TAG, "couldn't sync: $error")
        when {
            error is SourceError.AuthRequired || error is SourceError.AuthExpired -> {
                if (!toldSignIn) _notes.tryEmit("Sign in to YouTube Music again to sync your playlists")
                toldSignIn = true
            }
            // Turned on a moment ago: the listener should know it hasn't happened yet.
            asked -> _notes.tryEmit("Couldn't reach YouTube Music. “$title” will sync when it can")
        }
    }

    private fun SourceError.stops() = this is SourceError.AuthRequired || this is SourceError.AuthExpired || this is SourceError.RateLimited || this is SourceError.Offline

    private fun likedRemote(): String = MediaId.of(sources.primary.id, LIKED_PLAYLIST).value

    // --- Kept on the phone ----------------------------------------------------------------------------

    private fun change(transform: (Map<String, Link>) -> Map<String, Link>) {
        val next = synchronized(this) {
            val after = transform(_links.value)
            _links.value = after
            after
        }
        scope.launch(Dispatchers.IO) { write(next) }
    }

    @Synchronized
    private fun write(all: Map<String, Link>) {
        if (all != _links.value) return
        val json = JSONObject()
        all.forEach { (key, l) ->
            json.put(key, JSONObject().apply {
                put("remote", l.remote ?: JSONObject.NULL)
                put("on", l.on)
                put("base", JSONArray(l.base.toList()))
                put("name", l.name ?: JSONObject.NULL)
                put("renamedFrom", l.renamedFrom ?: JSONObject.NULL)
                put("refused", JSONArray(l.refused.toList()))
                put("missed", JSONArray(l.missed.toList()))
                put("at", l.at)
            })
        }
        val temp = File(file.parentFile, file.name + ".new")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
    }

    private fun read(): Map<String, Link> = runCatching {
        if (!file.exists()) return emptyMap()
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { key ->
            val o = json.getJSONObject(key)
            Link(
                remote = o.optString("remote").takeIf { !o.isNull("remote") && it.isNotEmpty() },
                on = o.optBoolean("on"),
                base = o.optJSONArray("base").strings(),
                name = o.optString("name").takeIf { !o.isNull("name") },
                renamedFrom = o.optString("renamedFrom").takeIf { !o.isNull("renamedFrom") && it.isNotEmpty() },
                refused = o.optJSONArray("refused").strings(),
                missed = o.optJSONArray("missed").strings(),
                at = o.optLong("at"),
            )
        }
    }.getOrDefault(emptyMap())

    private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()

    companion object {
        const val LIKED = "liked"
        private const val TAG = "AetherSync"
        private const val LOCAL = "local:"
        private const val LIKED_PLAYLIST = "LM"

        fun playlist(id: Long) = "$LOCAL$id"

        /** After the app opens: its first screens come first. */
        private const val START_DELAY_MS = 6_000L

        /** A change here is sent once nothing more has changed for this long. */
        private const val SETTLE_MS = 2_000L

        /** A list synced this recently isn't asked again for its page being opened. */
        private const val FRESH_MS = 60_000L

        /** Waits before reading back a playlist just made in the account. */
        private val MADE_WAITS_MS = listOf(1_000L, 2_000L, 4_000L)

        /** Between one like and the next. */
        private const val LIKE_GAP_MS = 150L
    }
}
