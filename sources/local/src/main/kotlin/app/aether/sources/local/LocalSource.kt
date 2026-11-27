package app.aether.sources.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.Capability
import app.aether.core.model.FolderSelection
import app.aether.core.model.MediaId
import app.aether.core.model.MusicFolder
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.PhoneLibrary
import app.aether.core.model.PhoneSong
import app.aether.core.model.SearchFilter
import app.aether.core.model.SearchResults
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The music on the phone itself (PODIUM's offline library, read from Android's media store): every
 * song the store lists as music in the folders the listener chose, played straight from its file at
 * whatever quality the file is. Nothing leaves the phone. Needs the listener's permission to read
 * their audio; without it, it simply has nothing. Once asked, it watches the store, so a song added
 * to or taken off the phone shows up by itself.
 */
class LocalSource(
    context: Context,
    /** Files that belong elsewhere in AETHER (kept songs, under Kept), by their media store address: not listed here. */
    private val hidden: (String) -> Boolean = { false },
) : MusicSource, PhoneLibrary {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = this.context.getSharedPreferences("local_source", Context.MODE_PRIVATE)

    override val id = "local"
    override val name = "On this phone"
    override val capabilities = setOf(Capability.SEARCH, Capability.STREAM)

    override val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val _folders = MutableStateFlow<List<MusicFolder>?>(null)
    private val _songs = MutableStateFlow<List<PhoneSong>?>(null)
    private val _selection = MutableStateFlow(loadSelection())
    override val folders: StateFlow<List<MusicFolder>?> = _folders.asStateFlow()
    override val songs: StateFlow<List<PhoneSong>?> = _songs.asStateFlow()
    override val selection: StateFlow<FolderSelection> = _selection.asStateFlow()

    /** Every song found, chosen or not: changing the choice doesn't need the phone read again. */
    @Volatile private var everything: List<PhoneSong> = emptyList()
    private var scanning: Job? = null
    private var watching = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scan(afterMs = 1_000)
    }

    override fun hasPermission(): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun refresh() {
        if (!watching) {
            watching = true
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
        scan()
    }

    override fun select(selection: FolderSelection) {
        if (selection == _selection.value) return
        _selection.value = selection
        prefs.edit().putStringSet(INCLUDED, selection.included).putStringSet(EXCLUDED, selection.excluded).apply()
        _songs.value = everything.filter { selection.includes(it.folder) }
    }

    private fun loadSelection(): FolderSelection {
        if (!prefs.contains(INCLUDED) && !prefs.contains(EXCLUDED)) return FolderSelection.Default
        return FolderSelection(prefs.getStringSet(INCLUDED, emptySet()).orEmpty().toSet(), prefs.getStringSet(EXCLUDED, emptySet()).orEmpty().toSet())
    }

    private fun scan(afterMs: Long = 0) {
        scanning?.cancel()
        scanning = scope.launch {
            if (afterMs > 0) delay(afterMs)
            publish()
        }
    }

    private fun publish() {
        if (!hasPermission()) {
            everything = emptyList()
            _folders.value = null
            _songs.value = emptyList()
            return
        }
        val found = read()
        everything = found
        _folders.value = found.groupingBy { it.folder }.eachCount().map { (path, count) -> MusicFolder(path, count) }.sortedBy { it.path.lowercase() }
        val selection = _selection.value
        _songs.value = found.filter { selection.includes(it.folder) }
    }

    private fun read(): List<PhoneSong> {
        // The album's artist is a column of its own from Android 11; before, the song's artist stands in.
        val albumArtist = Build.VERSION.SDK_INT >= 30
        val columns = listOfNotNull(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            if (albumArtist) MediaStore.Audio.Media.ALBUM_ARTIST else null,
        ).toTypedArray()
        val songs = ArrayList<PhoneSong>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                columns,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val iDuration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val iYear = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val iPath = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val iAdded = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val iModified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val iAlbumArtist = if (albumArtist) c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) else -1
                while (c.moveToNext()) {
                    val key = c.getLong(iId)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key)
                    if (runCatching { hidden(uri.toString()) }.getOrDefault(false)) continue
                    val artist = c.getString(iArtist)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING }
                    val track = Track(
                        id = MediaId("$id:$key"),
                        title = c.getString(iTitle) ?: "",
                        artists = listOfNotNull(artist?.let { ArtistRef(it) }),
                        album = c.getString(iAlbum)?.takeIf { it.isNotBlank() }?.let { AlbumRef(it) },
                        durationMs = c.getLong(iDuration).takeIf { it > 0 },
                        // The store's own thumbnail of the file's embedded picture.
                        artwork = Artwork.single(uri.toString()),
                        trackNumber = (c.getInt(iTrack) % 1000).takeIf { it > 0 },
                        year = c.getInt(iYear).takeIf { it > 0 },
                    )
                    songs += PhoneSong(
                        track = track,
                        folder = FolderSelection.normalize(c.getString(iPath).orEmpty()),
                        albumKey = c.getLong(iAlbumId).takeIf { it != 0L }?.toString(),
                        albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist)?.takeUnless { it.isBlank() || it == MediaStore.UNKNOWN_STRING } else null,
                        // The store writes a song's place as its disc's thousands and its own number.
                        disc = c.getInt(iTrack) / 1000,
                        addedAt = c.getLong(iAdded),
                        modifiedAt = c.getLong(iModified),
                    )
                }
            }
        }
        return songs
    }

    override suspend fun search(query: String, filter: SearchFilter?): Outcome<SearchResults> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Outcome.Ok(SearchResults(emptyList(), emptyList(), null))
        if (_songs.value == null) withContext(Dispatchers.IO) { publish() }
        val found = _songs.value.orEmpty().map { it.track }.filter { t -> t.title.lowercase().contains(q) || t.artistLine.lowercase().contains(q) || t.album?.title?.lowercase()?.contains(q) == true }
        return Outcome.Ok(SearchResults(found, emptyList(), null))
    }

    /** Where a song of this source is: its file in the media store, its folder and its name. */
    data class PhoneFile(val uri: android.net.Uri, val folder: String, val name: String)

    /** The file behind [id], when it's this source's and the store still has it. */
    suspend fun file(id: MediaId): PhoneFile? = withContext(Dispatchers.IO) {
        if (id.source != this@LocalSource.id) return@withContext null
        val key = id.key.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key)
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else PhoneFile(uri, c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        }.getOrNull()
    }

    override suspend fun stream(id: MediaId, efficient: Boolean): Outcome<Stream> {
        val key = id.value.substringAfter(':').toLongOrNull() ?: return Outcome.Err(SourceError.NotFound("song"))
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key)
        return Outcome.Ok(Stream(url = uri.toString(), cacheKey = id.value))
    }

    private companion object {
        const val INCLUDED = "folders_included"
        const val EXCLUDED = "folders_excluded"
    }
}
