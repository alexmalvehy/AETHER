package app.aether

import android.content.Context
import app.aether.core.data.Account
import app.aether.core.data.Library
import app.aether.core.model.MediaId
import app.aether.core.model.Outcome
import app.aether.core.model.Track
import app.aether.sources.youtube.YouTubeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Seamless two-way synchronization between the listener's remote YouTube Music "Liked Music"
 * (`LM` / `VLLM`) collection and AETHER's native local "Liked Songs" database ([Library]).
 *
 * - Ingests remote Liked Music tracks directly into [Library] so every remotely liked song shows
 *   an active heart throughout Now Playing, Search, Track menus, Notifications, and Liked Songs.
 * - Binds local like/unlike actions (`Library.setLiked`) to asynchronously dispatch InnerTube
 *   `like/like` or `like/removelike` requests when signed in.
 * - Removes any redundant external `ytm:LM` / `ytm:VLLM` playlist entry from saved playlists.
 */
class YouTubeFavoritesSync(
    context: Context,
    private val scope: CoroutineScope,
    private val account: Account,
    private val online: Online,
    private val youtube: YouTubeSource,
    private val library: Library,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    fun start() {
        library.onLikeChanged = { track, liked ->
            onLocalLikeToggled(track, liked)
        }
        scope.launch(Dispatchers.IO) {
            removeRedundantLikedPlaylists()
            var lastSession: String? = null
            combine(account.cookies, online.now) { cookies, isOnline -> cookies to isOnline }
                .collect { (cookies, isOnline) ->
                    if (cookies == null) {
                        lastSession = null
                        return@collect
                    }
                    if (!isOnline) return@collect
                    if (cookies != lastSession) {
                        if (syncFromRemote()) {
                            lastSession = cookies
                        }
                    }
                }
        }
    }

    /** Triggers an on-demand pull of remote Liked Music into [Library]. */
    fun refresh() {
        if (account.cookies.value == null) return
        scope.launch(Dispatchers.IO) { syncFromRemote() }
    }

    private fun onLocalLikeToggled(track: Track, liked: Boolean) {
        if (track.id.source != youtube.id) return
        if (account.cookies.value.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                updateKnownId(track.id.value, liked)
                youtube.like(track.id, liked)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Silent fallback: network timeouts or API blocks never interrupt playback.
            }
        }
    }

    private suspend fun syncFromRemote(): Boolean = mutex.withLock {
        try {
            removeRedundantLikedPlaylists()
            val firstOutcome = youtube.likedSongs()
            val firstPage = (firstOutcome as? Outcome.Ok)?.value ?: return@withLock false
            val allTracks = ArrayList<Track>(firstPage.tracks)
            var nextToken = firstPage.next
            var pages = 0
            while (nextToken != null && pages++ < MAX_PAGES) {
                val more = (youtube.tracksMore(nextToken) as? Outcome.Ok)?.value ?: break
                allTracks += more.items
                nextToken = more.next
            }
            val remoteIds = allTracks.mapTo(LinkedHashSet()) { it.id.value }
            val previousRemoteIds = prefs.getStringSet(KEY_KNOWN_REMOTE_IDS, emptySet()).orEmpty()
            val removedRemotely = previousRemoteIds - remoteIds
            library.ingestRemoteLikes(allTracks, removedRemotely)
            prefs.edit().putStringSet(KEY_KNOWN_REMOTE_IDS, remoteIds).apply()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private suspend fun removeRedundantLikedPlaylists() {
        runCatching {
            library.unsave(MediaId("ytm:LM"))
            library.unsave(MediaId("ytm:VLLM"))
            library.unsave(MediaId("ytm:VLPLLM"))
        }
    }

    @Synchronized
    private fun updateKnownId(id: String, liked: Boolean) {
        val current = prefs.getStringSet(KEY_KNOWN_REMOTE_IDS, emptySet()).orEmpty().toMutableSet()
        if (liked) current.add(id) else current.remove(id)
        prefs.edit().putStringSet(KEY_KNOWN_REMOTE_IDS, current).apply()
    }

    companion object {
        private const val PREFS_NAME = "yt_favorites_sync"
        private const val KEY_KNOWN_REMOTE_IDS = "known_remote_ids"
        private const val MAX_PAGES = 20
    }
}
