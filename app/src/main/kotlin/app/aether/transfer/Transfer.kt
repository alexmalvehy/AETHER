package app.aether.transfer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.aether.Online
import app.aether.UserAgent
import app.aether.core.data.Library
import app.aether.core.data.SourceManager
import app.aether.core.model.Track
import app.aether.core.transfer.AetherFile
import app.aether.core.transfer.AppleMusicLink
import app.aether.core.transfer.Csv
import app.aether.core.transfer.Destination
import app.aether.core.transfer.Existing
import app.aether.core.transfer.ImportFormat
import app.aether.core.transfer.ImportItem
import app.aether.core.transfer.ImportRead
import app.aether.core.transfer.ImportedLibrary
import app.aether.core.transfer.ImportedPlaylist
import app.aether.core.transfer.Imports
import app.aether.core.transfer.M3u
import app.aether.core.transfer.Plan
import app.aether.core.transfer.Songs
import app.aether.core.transfer.SpotifyLink
import app.aether.sources.local.LocalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

/**
 * Playlists in and out of AETHER (LastWave's pipeline, BitChord's Spotify page, PODIUM's matching):
 * a file or a public playlist's link read, every song found, and written to the Library so that
 * nothing is ever doubled ([Plan], [Songs], [Origins]); and the listener's playlists written out as
 * AETHER's own file, M3U8 or CSV.
 *
 * One import at a time, in the app's own scope and off the main thread: leaving the screen doesn't stop
 * it, and asking again while one runs does nothing. Each playlist is written whole once its songs are
 * found, so one stopped halfway leaves the playlists before it, and importing again finishes the rest
 * without doubling them. Links are asked for as AETHER, never as a browser.
 */
class Transfer(
    private val app: Application,
    private val scope: CoroutineScope,
    private val library: Library,
    private val sources: SourceManager,
    private val local: LocalSource,
    private val http: OkHttpClient,
    private val online: Online,
    private val version: () -> String,
) {
    val origins = Origins(File(app.filesDir, "playlist-origins.txt"))

    sealed interface State {
        data object Idle : State
        data class Reading(val what: String) : State
        /** Finding [playlist]'s songs, the [index]th of [of]: [done] of [total] songs in all looked for. */
        data class Finding(val playlist: String, val index: Int, val of: Int, val done: Int, val total: Int) : State
        data class Done(val summary: Summary) : State
        data class Failed(val reason: String) : State
    }

    /** One playlist brought in: where it went, what it gained, what couldn't be found. */
    data class Brought(
        val name: String,
        /** The playlist it's in; null when none of its songs could be found and nothing was made. */
        val playlistId: Long?,
        val added: Int,
        /** Its songs that playlist had already. */
        val already: Int,
        val missing: List<String>,
        val created: Boolean,
        val partial: Boolean,
    )

    data class Summary(
        val format: ImportFormat,
        val playlists: List<Brought>,
        /** Songs newly liked, and the liked songs that couldn't be found. */
        val liked: Int,
        val likedMissing: List<String>,
        /** Why it stopped before the end, when it did. */
        val stopped: String? = null,
    ) {
        val songsAdded: Int get() = playlists.sumOf { it.added }

        /** In a few words, for a note when no pane shows it. */
        val headline: String
            get() {
                val made = playlists.count { it.created }
                val what = when {
                    made > 0 -> if (made == 1) "A new playlist brought in" else "$made new playlists brought in"
                    songsAdded > 0 -> if (songsAdded == 1) "1 song added" else "$songsAdded songs added"
                    liked > 0 -> if (liked == 1) "1 song liked" else "$liked songs liked"
                    else -> "Nothing new to bring in"
                }
                return if (stopped != null) "Import stopped. $what" else what
            }
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true

    /** How many panes show the import now; with none, the shell says when it ends. */
    private val watchers = AtomicInteger(0)
    val watched: Boolean get() = watchers.get() > 0
    fun watch(on: Boolean) { if (on) watchers.incrementAndGet() else watchers.decrementAndGet() }

    fun importFile(uri: Uri) = start("Reading the file") { readFile(uri) }

    fun importLink(text: String) = start("Asking for the playlist") { readLink(text) }

    /** Stops an import: the playlists already written stay; importing again finishes, never doubling. */
    fun stop() {
        job?.cancel()
    }

    private var announced: State? = null

    /** Whether [s], an import's end, is yet to be said: said once, however often the shell is drawn again. */
    fun toAnnounce(s: State): Boolean {
        if (s !is State.Done && s !is State.Failed) return false
        if (watched || announced === s) return false
        announced = s
        return true
    }

    /** Puts a finished import's account away. */
    fun dismiss() {
        if (!busy) _state.value = State.Idle
    }

    private fun start(first: String, read: suspend () -> ImportRead) {
        if (busy) return
        _state.value = State.Reading(first)
        job = scope.launch(Dispatchers.IO) {
            var format = ImportFormat.CSV
            val brought = ArrayList<Brought>()
            var liked = 0
            val likedMissing = ArrayList<String>()
            fun summary(stopped: String?) = State.Done(Summary(format, brought.toList(), liked, likedMissing.toList(), stopped))
            try {
                when (val r = read()) {
                    is ImportRead.Failed -> _state.value = State.Failed(r.reason)
                    is ImportRead.Ok -> {
                        format = r.library.format
                        bring(r.library, brought) { n, missing -> liked = n; likedMissing += missing }
                        _state.value = summary(null)
                    }
                }
            } catch (e: NoConnection) {
                _state.value = summary(
                    if (e.unanswered) "The music service stopped answering. Import again in a while to finish: nothing will be doubled."
                    else "The connection went. Import again when it's back to finish: nothing will be doubled.",
                )
            } catch (e: CancellationException) {
                _state.value = if (brought.isEmpty() && liked == 0) State.Idle else summary("Stopped. Import again to finish: nothing will be doubled.")
                throw e
            } catch (e: Exception) {
                _state.value = if (brought.isEmpty()) State.Failed("Something went wrong bringing it in.") else summary("Something went wrong partway. Import again to finish: nothing will be doubled.")
            }
        }
    }

    private suspend fun bring(lib: ImportedLibrary, brought: MutableList<Brought>, onLiked: (Int, List<String>) -> Unit) {
        val finder = Finder(
            catalogue = sources.primary,
            phone = local,
            onPhone = { id -> phoneSong(id) },
            sourceHere = { id -> sources.all.any { it.id == id } },
            online = { online.now.value },
        )
        val total = lib.playlists.sumOf { it.items.size } + lib.liked.size
        val done = AtomicInteger(0)
        // Four songs are looked for at once, so a count can arrive after a larger one: progress only goes forward.
        fun progress(name: String, index: Int, of: Int, n: Int) = _state.update { now ->
            if (now is State.Finding && now.index == index && now.done >= n) now else State.Finding(name, index, of, n, total)
        }
        for ((i, p) in lib.playlists.withIndex()) {
            progress(p.name, i + 1, lib.playlists.size, done.get())
            val found = findAll(finder, p.items) { progress(p.name, i + 1, lib.playlists.size, done.incrementAndGet()) }
            // Once begun, a playlist is written whole, its origin with it: a stop lands between playlists.
            brought += withContext(NonCancellable) { write(p, found) }
        }
        if (lib.liked.isNotEmpty()) {
            val step = { progress("Liked songs", lib.playlists.size + 1, lib.playlists.size + 1, done.incrementAndGet()) }
            val found = findAll(finder, lib.liked, step)
            val added = withContext(NonCancellable) { library.likeAll(Songs.distinct(found.filterNotNull())) }
            onLiked(added, lib.liked.zip(found).filter { it.second == null }.map { it.first.label })
        }
    }

    /** Every song of [items] looked for, a few at once (the service turns away a crowd), in [items]' order. */
    private suspend fun findAll(finder: Finder, items: List<ImportItem>, step: () -> Unit): List<Track?> = coroutineScope {
        val gate = Semaphore(AT_ONCE)
        items.map { item -> async { gate.withPermit { finder.find(item).also { step() } } } }.awaitAll()
    }

    /** [p] into the Library with its [found] songs: into the playlist it made before, or the same one made another way, or a new one. */
    private suspend fun write(p: ImportedPlaylist, found: List<Track?>): Brought {
        val tracks = Songs.distinct(found.filterNotNull())
        val missing = p.items.zip(found).filter { it.second == null }.map { it.first.label }
        val all = library.playlists.first()
        origins.prune(all.mapTo(HashSet()) { it.id })
        val owner = origins.owner(p.origin) ?: kin(p, all.map { it.id to it.title })
        // Only the playlists it could go into are read whole.
        val near = all.filter { it.id == owner || it.title.trim().equals(p.name.trim(), ignoreCase = true) }.mapTo(HashSet()) { it.id }
        val existing = all.map { lp -> Existing(lp.id, lp.title, if (lp.id in near) library.entries(lp.id).first().map { it.track } else emptyList()) }
        return when (val to = Plan.destination(p, owner, existing, tracks)) {
            is Destination.Into -> {
                val there = existing.first { it.id == to.playlistId }
                val add = Songs.missing(there.tracks, tracks)
                if (add.isNotEmpty()) library.add(to.playlistId, add)
                origins.remember(p.origin, to.playlistId)
                Brought(there.title, to.playlistId, add.size, tracks.size - add.size, missing, created = false, partial = p.partial)
            }
            is Destination.New -> {
                if (tracks.isEmpty()) {
                    Brought(to.name, null, 0, 0, missing, created = false, partial = p.partial)
                } else {
                    val folder = p.folder?.let { folderFor(it) }
                    val id = library.createPlaylist(to.name, p.description, folder, tracks)
                    origins.remember(p.origin, id)
                    Brought(to.name, id, tracks.size, 0, missing, created = true, partial = p.partial)
                }
            }
        }
    }

    /**
     * A Spotify playlist met again another way: one read from its link before and now in the data
     * download (which names playlists but gives them no id), or the other way round. The same name, made
     * from Spotify before, is the same playlist.
     */
    private suspend fun kin(p: ImportedPlaylist, all: List<Pair<Long, String>>): Long? {
        if (!p.origin.startsWith(SPOTIFY)) return null
        return all.filter { it.second.trim().equals(p.name.trim(), ignoreCase = true) }
            .firstOrNull { (id, _) -> origins.of(id).any { it.startsWith(SPOTIFY) } }?.first
    }

    private suspend fun folderFor(name: String): Long =
        library.folders.first().firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }?.id ?: library.createFolder(name)

    /** A song of the phone's by its id, when it's on this phone (its id is this phone's own). */
    private suspend fun phoneSong(id: app.aether.core.model.MediaId): Track? {
        // Not looked at yet: a search has the phone's music read first, and waits for it.
        if (local.songs.value == null && local.hasPermission()) local.search(id.key)
        return local.songs.value?.firstOrNull { it.track.id == id }?.track
    }

    // --- Reading ---------------------------------------------------------------------------------

    private fun readFile(uri: Uri): ImportRead {
        val name = displayName(uri)
        _state.value = State.Reading(name?.let { "Reading “$it”" } ?: "Reading the file")
        val scratch = File(app.cacheDir, "import-${System.nanoTime()}.zip")
        return try {
            app.contentResolver.openInputStream(uri)?.use { Imports.read(it, name, scratch) }
                ?: ImportRead.Failed("AETHER couldn't open that file.")
        } catch (e: Exception) {
            ImportRead.Failed("AETHER couldn't open that file.")
        } finally {
            scratch.delete()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private suspend fun readLink(text: String): ImportRead {
        var input = text.trim()
        if (SpotifyLink.isShortLink(input)) {
            val page = fetch(input) as? Page.Ok ?: return ImportRead.Failed("AETHER couldn't reach Spotify.")
            // Followed to the playlist, or to a page that names it inside.
            input = if (SpotifyLink.playlistId(page.at) != null) page.at
            else NAMED_PLAYLIST.find(page.html)?.value?.let { "https://$it" } ?: page.at
        }
        SpotifyLink.playlistId(input)?.let { id ->
            return when (val page = fetch(SpotifyLink.embedUrl(id))) {
                is Page.Ok -> SpotifyLink.read(page.html, id)
                Page.Missing -> ImportRead.Failed("Spotify has no public playlist at that link. A private one can come in Spotify's data download.")
                Page.Unreachable -> ImportRead.Failed("AETHER couldn't reach Spotify.")
            }
        }
        AppleMusicLink.playlistId(input)?.let { id ->
            return when (val page = fetch(input)) {
                is Page.Ok -> AppleMusicLink.read(page.html, id)
                Page.Missing -> ImportRead.Failed("Apple Music has no public playlist at that link.")
                Page.Unreachable -> ImportRead.Failed("AETHER couldn't reach Apple Music.")
            }
        }
        return ImportRead.Failed(
            when {
                SpotifyLink.isSpotify(input) -> "That's a Spotify link, but not to a playlist."
                AppleMusicLink.isAppleMusic(input) -> "That's an Apple Music link, but not to a playlist."
                else -> "Paste the link to a public Spotify or Apple Music playlist."
            },
        )
    }

    private sealed interface Page {
        /** The page, and the address it came from once redirects were followed. */
        data class Ok(val html: String, val at: String) : Page
        data object Missing : Page
        data object Unreachable : Page
    }

    /** A public page, asked for as AETHER, read to at most [MAX_PAGE_BYTES]. */
    private suspend fun fetch(url: String): Page = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UserAgent).header("Accept", "text/html,application/xhtml+xml").build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 404 || r.code == 410 -> Page.Missing
                    !r.isSuccessful -> Page.Unreachable
                    else -> r.body?.byteStream()?.let { capped(it, MAX_PAGE_BYTES) }?.let { Page.Ok(String(it, Charsets.UTF_8), r.request.url.toString()) } ?: Page.Unreachable
                }
            }
        } catch (e: IOException) {
            Page.Unreachable
        } catch (e: IllegalArgumentException) {
            Page.Unreachable
        }
    }

    private fun capped(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    // --- Writing out -----------------------------------------------------------------------------

    enum class Format(val label: String, val detail: String, val mime: String, val extension: String) {
        AETHER("AETHER file", "Every song whole, for AETHER on this phone or another. Imported again, it adds only what's missing.", "application/json", "json"),
        M3U("M3U playlist", "For other players: each song by name, with its YouTube Music link.", "application/vnd.apple.mpegurl", "m3u8"),
        CSV("Spreadsheet", "A CSV of titles, artists, albums, lengths and links.", "text/csv", "csv"),
    }

    /** What a playlist's file is called when it's written: its name, the date for all of them. */
    fun fileName(title: String, format: Format): String = "${title.replace(UNSAFE, " ").trim().ifEmpty { "Playlist" }}.${format.extension}"

    fun allFileName(): String = "AETHER playlists ${LocalDate.now()}.json"

    /** One playlist written to [uri] as [format]. */
    suspend fun export(playlistId: Long, format: Format, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val p = library.playlists.first().firstOrNull { it.id == playlistId } ?: return@withContext false
        val tracks = library.entries(playlistId).first().map { it.track }
        val text = when (format) {
            Format.AETHER -> AetherFile.write(listOf(out(p.id, p.title, p.description, p.folderId, tracks)), liked = null, version(), Instant.now().toString())
            Format.M3U -> M3u.write(p.title, tracks)
            Format.CSV -> Csv.write(tracks)
        }
        writeText(uri, text)
    }

    /** Every playlist, with the liked songs, written to [uri] as one AETHER file. Returns how many playlists, or null. */
    suspend fun exportAll(uri: Uri): Int? = withContext(Dispatchers.IO) {
        val playlists = library.playlists.first()
        val outs = playlists.map { p -> out(p.id, p.title, p.description, p.folderId, library.entries(p.id).first().map { it.track }) }
        val text = AetherFile.write(outs, liked = library.liked.first(), version(), Instant.now().toString())
        if (writeText(uri, text)) outs.size else null
    }

    private suspend fun out(id: Long, title: String, description: String?, folderId: Long?, tracks: List<Track>): AetherFile.Out {
        val folder = folderId?.let { f -> library.folders.first().firstOrNull { it.id == f }?.name }
        return AetherFile.Out(origins.aetherId(id), title, description, folder, tracks)
    }

    /**
     * [text] into the file the listener chose, the old file cut away first ("wt"): written plainly
     * ("w"), some providers leave the end of a longer file there (LastWave's FileExportHelper).
     */
    private fun writeText(uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (mode in listOf("wt", "w")) {
            val written = runCatching {
                app.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes); it.flush() } != null
            }.getOrDefault(false)
            if (written) return true
        }
        return false
    }

    private companion object {
        const val AT_ONCE = 4
        const val MAX_PAGE_BYTES = 8 * 1024 * 1024
        const val SPOTIFY = "spotify"
        val NAMED_PLAYLIST = Regex("""open\.spotify\.com/playlist/[A-Za-z0-9]{22}""")
        val UNSAFE = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")
    }
}
