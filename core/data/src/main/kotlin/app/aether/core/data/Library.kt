package app.aether.core.data

import app.aether.core.data.db.AetherDatabase
import app.aether.core.data.db.FolderRow
import app.aether.core.data.db.HistoryRow
import app.aether.core.data.db.LikedRow
import app.aether.core.data.db.PlaylistEntryRow
import app.aether.core.data.db.PlaylistRow
import app.aether.core.data.db.QueueItemRow
import app.aether.core.data.db.QueueStateRow
import app.aether.core.data.db.SavedRow
import app.aether.core.data.db.SearchRow
import app.aether.core.data.db.TrackRow
import app.aether.core.model.Album
import app.aether.core.model.Artist
import app.aether.core.model.MediaId
import app.aether.core.model.PlayRecorder
import app.aether.core.model.Playlist
import app.aether.core.model.QueueStore
import app.aether.core.model.RepeatMode
import app.aether.core.model.SavedQueue
import app.aether.core.model.Track
import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A playlist the listener made, as the library shows it. */
data class LocalPlaylist(
    val id: Long,
    val title: String,
    val description: String?,
    val artworkUri: String?,
    val folderId: Long?,
    val count: Int,
    val updatedAt: Long,
    /** The first few songs, for the folder's face and its peeking files. */
    val preview: List<Track>,
)

data class PlaylistEntry(val entryId: Long, val track: Track)

data class Folder(val id: Long, val name: String)

data class Played(val at: Long, val track: Track)

/**
 * Everything the listener keeps: liked songs, saved albums and artists, their playlists and
 * folders, what they've played and searched. Every write is local first; nothing here talks to a
 * source.
 */
class Library(private val db: AetherDatabase, private val now: () -> Long = System::currentTimeMillis) : PlayRecorder {
    private val dao = db.library()
    private val lists = db.playlists()

    suspend fun remember(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val at = now()
        dao.upsertTracks(tracks.distinctBy { it.id }.map { TrackRow(it.id.value, it.toJson(), at) })
    }

    // --- Liked songs ---

    val liked: Flow<List<Track>> = dao.liked().map { rows -> rows.mapNotNull(::trackFromJson) }
    val likedIds: Flow<Set<String>> = dao.likedIds().map { it.toSet() }
    fun isLiked(id: MediaId): Flow<Boolean> = dao.isLiked(id.value)

    /** Invoked after a local [setLiked] mutation so two-way sync can push the change upstream. */
    @Volatile var onLikeChanged: ((Track, Boolean) -> Unit)? = null

    suspend fun setLiked(track: Track, liked: Boolean) {
        if (liked) {
            remember(listOf(track))
            dao.like(LikedRow(track.id.value, now()))
        } else {
            dao.unlike(track.id.value)
        }
        onLikeChanged?.invoke(track, liked)
    }

    /**
     * Ingests tracks from the remote Liked Music collection (`LM` / `VLLM`) directly into local
     * favorites storage without re-triggering [onLikeChanged].
     */
    suspend fun ingestRemoteLikes(remoteTracks: List<Track>, removedIds: Set<String> = emptySet()) {
        if (remoteTracks.isEmpty() && removedIds.isEmpty()) return
        val existingIds = dao.likedIds().first().toSet()
        val newTracks = remoteTracks.filter { it.id.value !in existingIds }
        if (remoteTracks.isNotEmpty()) {
            remember(remoteTracks)
        }
        val baseTime = now()
        db.withWriteTransaction {
            newTracks.forEachIndexed { index, track ->
                dao.like(LikedRow(track.id.value, baseTime - index))
            }
            removedIds.forEach { id ->
                dao.unlike(id)
            }
        }
    }

    // --- History ---

    fun recent(limit: Int = 30): Flow<List<Track>> = dao.recent(limit).map { rows -> rows.mapNotNull(::trackFromJson) }
    fun history(limit: Int = 300): Flow<List<Played>> = dao.history(limit).map { rows -> rows.mapNotNull { r -> trackFromJson(r.json)?.let { Played(r.playedAt, it) } } }
    suspend fun clearHistory() = dao.clearHistory()

    override suspend fun played(track: Track) {
        remember(listOf(track))
        dao.insertHistory(HistoryRow(trackId = track.id.value, playedAt = now()))
    }

    // --- Saved albums, artists, playlists from sources ---

    val albums: Flow<List<Album>> = dao.saved(ALBUM).map { it.mapNotNull(::albumFromJson) }
    val artists: Flow<List<Artist>> = dao.saved(ARTIST).map { it.mapNotNull(::artistFromJson) }
    val savedPlaylists: Flow<List<Playlist>> = dao.saved(PLAYLIST).map { rows ->
        rows.mapNotNull(::playlistFromJson).filterNot { it.id.source == "ytm" && it.id.key.removePrefix("VL") == "LM" }
    }
    fun isSaved(id: MediaId): Flow<Boolean> = dao.isSaved(id.value)

    suspend fun save(album: Album, keep: Boolean) = if (keep) dao.save(SavedRow(album.id.value, ALBUM, album.toSavedJson(), now())) else dao.unsave(album.id.value)
    suspend fun follow(artist: Artist, keep: Boolean) = if (keep) dao.save(SavedRow(artist.id.value, ARTIST, artist.toSavedJson(), now())) else dao.unsave(artist.id.value)
    suspend fun save(playlist: Playlist, keep: Boolean) = if (keep) dao.save(SavedRow(playlist.id.value, PLAYLIST, playlist.toSavedJson(), now())) else dao.unsave(playlist.id.value)
    suspend fun unsave(id: MediaId) = dao.unsave(id.value)

    // --- The listener's playlists and folders ---

    val folders: Flow<List<Folder>> = lists.folders().map { rows -> rows.map { Folder(it.id, it.name) } }

    val playlists: Flow<List<LocalPlaylist>> = lists.playlists().map { rows ->
        rows.map { p ->
            LocalPlaylist(p.id, p.title, p.description, p.artworkUri, p.folderId, p.count, p.updatedAt, preview = emptyList())
        }
    }

    /**
     * Playlists with their first songs, for folders on Home and in the library. Every change to a
     * playlist's songs touches its row, so this re-reads exactly when a preview can have changed.
     */
    fun playlistsWithPreview(): Flow<List<LocalPlaylist>> = lists.playlists().map { rows ->
        rows.map { p ->
            val preview = lists.entries(p.id).first().take(3).mapNotNull { trackFromJson(it.json) }
            LocalPlaylist(p.id, p.title, p.description, p.artworkUri, p.folderId, p.count, p.updatedAt, preview)
        }
    }

    fun playlist(id: Long): Flow<LocalPlaylist?> = combine(lists.playlist(id), lists.entries(id)) { row, entries ->
        row?.let { p ->
            val tracks = entries.mapNotNull { trackFromJson(it.json) }
            LocalPlaylist(p.id, p.title, p.description, p.artworkUri, p.folderId, tracks.size, p.updatedAt, tracks.take(3))
        }
    }

    fun entries(id: Long): Flow<List<PlaylistEntry>> = lists.entries(id).map { rows -> rows.mapNotNull { r -> trackFromJson(r.json)?.let { PlaylistEntry(r.entryId, it) } } }

    suspend fun createPlaylist(title: String, description: String? = null, folderId: Long? = null, tracks: List<Track> = emptyList()): Long {
        val at = now()
        val id = lists.insert(PlaylistRow(title = title.trim().ifEmpty { "Untitled" }, description = description, artworkUri = null, folderId = folderId, position = lists.lastPosition() + 1, createdAt = at, updatedAt = at))
        if (tracks.isNotEmpty()) add(id, tracks)
        return id
    }

    suspend fun editPlaylist(id: Long, title: String, description: String?) = lists.edit(id, title.trim().ifEmpty { "Untitled" }, description?.trim()?.ifEmpty { null }, now())
    suspend fun setPlaylistArtwork(id: Long, uri: String?) = lists.setArtwork(id, uri, now())
    suspend fun moveToFolder(id: Long, folderId: Long?) = lists.setFolder(id, folderId, now())

    suspend fun deletePlaylist(id: Long) = db.withWriteTransaction {
        lists.deleteEntries(id)
        lists.delete(id)
    }

    suspend fun add(playlistId: Long, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        remember(tracks)
        db.withWriteTransaction {
            var position = lists.lastEntryPosition(playlistId)
            lists.insertEntries(tracks.map { t -> position += 1.0; PlaylistEntryRow(playlistId = playlistId, position = position, trackId = t.id.value, addedAt = now()) })
            lists.touch(playlistId, now())
        }
    }

    suspend fun remove(playlistId: Long, entryId: Long) {
        lists.removeEntry(entryId)
        lists.touch(playlistId, now())
    }

    /** Moves one entry to [toIndex]: halfway between its new neighbours, renumbering only when the gap runs out. */
    suspend fun move(playlistId: Long, entryId: Long, toIndex: Int) = db.withWriteTransaction {
        val others = lists.entryPositions(playlistId).filter { it.entryId != entryId }
        val index = toIndex.coerceIn(0, others.size)
        val before = others.getOrNull(index - 1)?.position
        val after = others.getOrNull(index)?.position
        val position = when {
            before != null && after != null && after - before < 1e-6 -> {
                others.forEachIndexed { i, e -> lists.setEntryPosition(e.entryId, (i + 1).toDouble()) }
                index + 0.5
            }
            before != null && after != null -> (before + after) / 2
            before != null -> before + 1.0
            after != null -> after - 1.0
            else -> 1.0
        }
        lists.setEntryPosition(entryId, position)
        lists.touch(playlistId, now())
    }

    suspend fun createFolder(name: String): Long = lists.insertFolder(FolderRow(name = name.trim().ifEmpty { "Folder" }, position = 0, createdAt = now()))
    suspend fun renameFolder(id: Long, name: String) = lists.renameFolder(id, name.trim().ifEmpty { "Folder" })
    suspend fun deleteFolder(id: Long) = db.withWriteTransaction {
        lists.emptyFolder(id)
        lists.deleteFolder(id)
    }

    // --- Searches ---

    fun searches(limit: Int = 10): Flow<List<String>> = dao.searches(limit)
    suspend fun searched(query: String) { if (query.isNotBlank()) dao.searched(SearchRow(query.trim(), now())) }
    suspend fun forgetSearch(query: String) = dao.forgetSearch(query)
    suspend fun clearSearches() = dao.clearSearches()

    private companion object {
        const val ALBUM = "album"
        const val ARTIST = "artist"
        const val PLAYLIST = "playlist"
    }
}

/** The queue between runs, in the database. */
class DatabaseQueueStore(private val db: AetherDatabase) : QueueStore {
    private val dao = db.queue()

    override suspend fun save(queue: SavedQueue) = db.withWriteTransaction {
        dao.clearItems()
        if (queue.tracks.isEmpty()) {
            dao.clearState()
            return@withWriteTransaction
        }
        dao.insertItems(queue.tracks.mapIndexed { i, t -> QueueItemRow(i, t.toJson()) })
        dao.upsertState(QueueStateRow(currentIndex = queue.index, positionMs = queue.positionMs, shuffle = queue.shuffle, repeat = queue.repeat.ordinal))
    }

    override suspend fun load(): SavedQueue? {
        val state = dao.state() ?: return null
        val tracks = dao.items().mapNotNull { trackFromJson(it.trackJson) }
        if (tracks.isEmpty()) return null
        return SavedQueue(tracks, state.currentIndex.coerceIn(0, tracks.lastIndex), state.positionMs, state.shuffle, RepeatMode.entries.getOrElse(state.repeat) { RepeatMode.OFF })
    }
}
