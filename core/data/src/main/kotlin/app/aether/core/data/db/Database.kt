package app.aether.core.data.db

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow

/*
 * The listener's own data: what they like, keep, make and play. Tracks are stored once (as JSON of
 * the shared model) and referred to by id everywhere else.
 */

@Entity(tableName = "track")
data class TrackRow(@PrimaryKey val id: String, val json: String, val updatedAt: Long)

@Entity(tableName = "liked")
data class LikedRow(@PrimaryKey val id: String, val likedAt: Long)

@Entity(tableName = "folder")
data class FolderRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val position: Int, val createdAt: Long)

@Entity(tableName = "playlist", indices = [Index("folderId")])
data class PlaylistRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String?,
    /** A picture the listener chose; otherwise its songs' covers make its face. */
    val artworkUri: String?,
    val folderId: Long?,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "playlist_entry", indices = [Index("playlistId")])
data class PlaylistEntryRow(
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0,
    val playlistId: Long,
    /** Gapped, so a move rewrites one row: halfway between its new neighbours. */
    val position: Double,
    val trackId: String,
    val addedAt: Long,
)

@Entity(tableName = "history", indices = [Index("playedAt"), Index("trackId")])
data class HistoryRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val trackId: String, val playedAt: Long)

/** An album, artist or playlist from a source, kept in the library. */
@Entity(tableName = "saved", indices = [Index("kind")])
data class SavedRow(@PrimaryKey val id: String, val kind: String, val json: String, val savedAt: Long)

@Entity(tableName = "search")
data class SearchRow(@PrimaryKey val query: String, val at: Long)

@Entity(tableName = "queue_item")
data class QueueItemRow(@PrimaryKey val ordinal: Int, val trackJson: String)

@Entity(tableName = "queue_state")
data class QueueStateRow(@PrimaryKey val id: Int = 1, val currentIndex: Int, val positionMs: Long, val shuffle: Boolean, val repeat: Int)

/** A playlist with what the screens need of it in one read. */
data class PlaylistSummary(
    val id: Long,
    val title: String,
    val description: String?,
    val artworkUri: String?,
    val folderId: Long?,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val count: Int,
)

@Dao
interface LibraryDao {
    @Upsert suspend fun upsertTracks(rows: List<TrackRow>)
    @Query("SELECT * FROM track WHERE id = :id") suspend fun track(id: String): TrackRow?

    @Upsert suspend fun like(row: LikedRow)
    @Query("DELETE FROM liked WHERE id = :id") suspend fun unlike(id: String)
    @Query("SELECT EXISTS(SELECT 1 FROM liked WHERE id = :id)") fun isLiked(id: String): Flow<Boolean>
    @Query("SELECT t.json FROM liked l JOIN track t ON t.id = l.id ORDER BY l.likedAt DESC") fun liked(): Flow<List<String>>
    @Query("SELECT id FROM liked") fun likedIds(): Flow<List<String>>

    @Insert suspend fun insertHistory(row: HistoryRow)
    @Query(
        "SELECT t.json FROM track t JOIN (SELECT trackId, MAX(playedAt) AS lastPlayed FROM history GROUP BY trackId) h ON h.trackId = t.id " +
            "ORDER BY h.lastPlayed DESC LIMIT :limit",
    )
    fun recent(limit: Int): Flow<List<String>>
    @Query("SELECT h.playedAt AS playedAt, t.json AS json FROM history h JOIN track t ON t.id = h.trackId ORDER BY h.playedAt DESC LIMIT :limit")
    fun history(limit: Int): Flow<List<HistoryEntry>>
    @Query("DELETE FROM history") suspend fun clearHistory()

    @Upsert suspend fun save(row: SavedRow)
    @Query("DELETE FROM saved WHERE id = :id") suspend fun unsave(id: String)
    @Query("SELECT json FROM saved WHERE kind = :kind ORDER BY savedAt DESC") fun saved(kind: String): Flow<List<String>>
    @Query("SELECT EXISTS(SELECT 1 FROM saved WHERE id = :id)") fun isSaved(id: String): Flow<Boolean>
    @Query("SELECT id, savedAt FROM saved WHERE kind = :kind") fun savedTimes(kind: String): Flow<List<SavedTime>>

    @Upsert suspend fun searched(row: SearchRow)
    @Query("SELECT `query` FROM search ORDER BY at DESC LIMIT :limit") fun searches(limit: Int): Flow<List<String>>
    @Query("DELETE FROM search WHERE `query` = :query") suspend fun forgetSearch(query: String)
    @Query("DELETE FROM search") suspend fun clearSearches()
}

data class HistoryEntry(val playedAt: Long, val json: String)

/** When something of the catalogue's was saved in the library. */
data class SavedTime(val id: String, val savedAt: Long)

@Dao
interface PlaylistDao {
    @Insert suspend fun insert(row: PlaylistRow): Long
    @Query("UPDATE playlist SET title = :title, description = :description, updatedAt = :now WHERE id = :id")
    suspend fun edit(id: Long, title: String, description: String?, now: Long)
    @Query("UPDATE playlist SET artworkUri = :uri, updatedAt = :now WHERE id = :id") suspend fun setArtwork(id: Long, uri: String?, now: Long)
    @Query("UPDATE playlist SET folderId = :folderId, updatedAt = :now WHERE id = :id") suspend fun setFolder(id: Long, folderId: Long?, now: Long)
    @Query("UPDATE playlist SET position = :position WHERE id = :id") suspend fun setPosition(id: Long, position: Int)
    @Query("UPDATE playlist SET updatedAt = :now WHERE id = :id") suspend fun touch(id: Long, now: Long)
    @Query("DELETE FROM playlist WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM playlist_entry WHERE playlistId = :id") suspend fun deleteEntries(id: Long)
    @Query(
        "SELECT p.id, p.title, p.description, p.artworkUri, p.folderId, p.position, p.createdAt, p.updatedAt, " +
            "(SELECT COUNT(*) FROM playlist_entry e WHERE e.playlistId = p.id) AS count FROM playlist p ORDER BY p.position, p.createdAt",
    )
    fun playlists(): Flow<List<PlaylistSummary>>
    @Query("SELECT * FROM playlist WHERE id = :id") fun playlist(id: Long): Flow<PlaylistRow?>
    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist") suspend fun lastPosition(): Int

    @Insert suspend fun insertEntries(rows: List<PlaylistEntryRow>)
    @Query("DELETE FROM playlist_entry WHERE entryId = :entryId") suspend fun removeEntry(entryId: Long)
    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist_entry WHERE playlistId = :id") suspend fun lastEntryPosition(id: Long): Double
    @Query("UPDATE playlist_entry SET position = :position WHERE entryId = :entryId") suspend fun setEntryPosition(entryId: Long, position: Double)
    @Query("SELECT entryId, position FROM playlist_entry WHERE playlistId = :id ORDER BY position") suspend fun entryPositions(id: Long): List<EntryPosition>
    @Query("SELECT e.entryId AS entryId, t.json AS json FROM playlist_entry e JOIN track t ON t.id = e.trackId WHERE e.playlistId = :id ORDER BY e.position")
    fun entries(id: Long): Flow<List<PlaylistEntryJson>>

    @Insert suspend fun insertFolder(row: FolderRow): Long
    @Query("UPDATE folder SET name = :name WHERE id = :id") suspend fun renameFolder(id: Long, name: String)
    @Query("DELETE FROM folder WHERE id = :id") suspend fun deleteFolder(id: Long)
    @Query("UPDATE playlist SET folderId = NULL WHERE folderId = :id") suspend fun emptyFolder(id: Long)
    @Query("SELECT * FROM folder ORDER BY position, createdAt") fun folders(): Flow<List<FolderRow>>
}

data class EntryPosition(val entryId: Long, val position: Double)

data class PlaylistEntryJson(val entryId: Long, val json: String)

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_state WHERE id = 1") suspend fun state(): QueueStateRow?
    @Query("SELECT * FROM queue_item ORDER BY ordinal") suspend fun items(): List<QueueItemRow>
    @Upsert suspend fun upsertState(state: QueueStateRow)
    @Query("DELETE FROM queue_item") suspend fun clearItems()
    @Insert suspend fun insertItems(items: List<QueueItemRow>)
    @Query("DELETE FROM queue_state") suspend fun clearState()
}

@Database(
    entities = [
        TrackRow::class, LikedRow::class, FolderRow::class, PlaylistRow::class, PlaylistEntryRow::class,
        HistoryRow::class, SavedRow::class, SearchRow::class, QueueItemRow::class, QueueStateRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AetherDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun playlists(): PlaylistDao
    abstract fun queue(): QueueDao

    companion object {
        fun open(context: Context): AetherDatabase =
            Room.databaseBuilder(context, AetherDatabase::class.java, context.getDatabasePath("aether.db").absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
