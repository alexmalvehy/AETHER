package app.aether

import android.content.Context
import app.aether.core.data.Account
import app.aether.core.data.Library
import app.aether.core.data.SourceManager
import app.aether.core.model.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The playlists already in the listener's YouTube Music library, kept in AETHER's Library while they're
 * signed in: there at sign-in, brought up to date at each start and on coming back online, and gone
 * again at sign-out. A playlist the listener saved here themselves is never taken away by this; only
 * what the sync itself added is (its ids are kept on the phone, nowhere else).
 */
class AccountPlaylists(
    context: Context,
    private val scope: CoroutineScope,
    private val account: Account,
    private val sources: SourceManager,
    private val library: Library,
    private val online: Online,
) {
    private val prefs = context.getSharedPreferences("account-playlists", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private var added: Set<String>
        get() = prefs.getStringSet(ADDED, emptySet()).orEmpty().toSet()
        set(value) { prefs.edit().putStringSet(ADDED, value).apply() }

    fun start() {
        scope.launch(Dispatchers.IO) {
            combine(account.cookies, online.now) { cookies, up -> (cookies != null) to up }
                .distinctUntilChanged()
                .collect { (signedIn, up) ->
                    if (!signedIn) forget() else if (up) sync()
                }
        }
    }

    /** Fetches the account's playlists and brings the Library to match. */
    suspend fun sync() = lock.withLock {
        val raw = (sources.primary.accountPlaylists() as? Outcome.Ok)?.value ?: return@withLock
        val found = raw.filterNot { app.aether.sources.youtube.YouTubeSource.isLikedMusicPlaylist(it.id) }
        val mine = added
        val before = library.savedPlaylists.first().map { it.id.value }.toSet()
        val now = found.map { it.id.value }.toSet()
        // New to the Library: added, and remembered as the sync's own.
        found.forEach { p -> if (p.id.value !in before || p.id.value in mine) library.save(p, keep = true) }
        val fresh = mine + found.map { it.id.value }.filter { it !in before }
        // The sync's own that the account no longer has: taken away again.
        val gone = fresh.filter { it !in now }
        library.savedPlaylists.first().filter { it.id.value in gone }.forEach { library.save(it, keep = false) }
        added = fresh - gone.toSet()
    }

    /** Signed out: what the sync added leaves with the account. */
    private suspend fun forget() = lock.withLock {
        val mine = added
        if (mine.isEmpty()) return@withLock
        library.savedPlaylists.first().filter { it.id.value in mine }.forEach { library.save(it, keep = false) }
        added = emptySet()
    }

    private companion object {
        const val ADDED = "added"
    }
}
