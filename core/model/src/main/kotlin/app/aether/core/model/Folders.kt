// FolderSelection, MusicFolder and the folder tree are PODIUM's (sources/api/Folders.kt and
// feature/settings/MusicFolders.kt, GPL-3.0).
package app.aether.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * A folder that holds music on the phone. [path] is relative to the storage root, slash-separated
 * and slash-terminated ("Music/Albums/"); [songCount] is the songs directly in it.
 */
data class MusicFolder(val path: String, val songCount: Int)

/**
 * Which folders are read. A folder is read when the closest choice at or above it is an inclusion;
 * with no choice above it, it's read only if nothing is included explicitly. So: nothing chosen reads
 * everything; including "Music/" reads only Music; including "Music/" and excluding "Music/Podcasts/"
 * reads Music without its podcasts.
 */
data class FolderSelection(val included: Set<String> = emptySet(), val excluded: Set<String> = emptySet()) {

    fun includes(path: String): Boolean {
        val p = normalize(path)
        val inc = included.filter { p.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        val exc = excluded.filter { p.startsWith(it) }.maxOfOrNull { it.length } ?: -1
        return when {
            inc < 0 && exc < 0 -> included.isEmpty()
            else -> inc > exc
        }
    }

    /** True when some folder strictly inside [path] is chosen differently from [path] itself. */
    fun isMixedBelow(path: String): Boolean {
        val p = normalize(path)
        val mine = includes(p)
        return (included + excluded).any { it != p && it.startsWith(p) && includes(it) != mine }
    }

    /** Flips [path] and everything inside it, keeping the rest of the choice as it was. */
    fun toggled(path: String): FolderSelection {
        val p = normalize(path)
        val wanted = !includes(p)
        val inc = included.filterNot { it.startsWith(p) }.toMutableSet()
        val exc = excluded.filterNot { it.startsWith(p) }.toMutableSet()
        val cleared = FolderSelection(inc, exc)
        if (cleared.includes(p) == wanted) return cleared
        // Including the first folder narrows "everything" to just it: say so explicitly.
        if (wanted) inc += p else exc += p
        return FolderSelection(inc, exc)
    }

    companion object {
        /** Read every folder. */
        val Everything = FolderSelection()

        /** The starting choice: everything except where apps keep their own audio (voice notes, shared clips, recordings). */
        val Default = FolderSelection(excluded = setOf("Android/media/", "WhatsApp/", "Telegram/", "Recordings/"))

        fun normalize(path: String): String = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    }
}

/** A folder as it's browsed: its own name, the songs inside it at every level, and whether it goes deeper. */
data class FolderNode(val path: String, val name: String, val songCount: Int, val hasSubfolders: Boolean)

/** The folders directly inside [path] ("" is the root), built from the flat list of folders that hold music. */
fun foldersInside(path: String, folders: List<MusicFolder>): List<FolderNode> {
    val parent = FolderSelection.normalize(path)
    return folders.filter { it.path.startsWith(parent) && it.path != parent }
        .groupBy { parent + it.path.removePrefix(parent).substringBefore('/') + "/" }
        .map { (child, inside) ->
            FolderNode(
                path = child,
                name = child.removeSuffix("/").substringAfterLast('/'),
                songCount = inside.sumOf { it.songCount },
                hasSubfolders = inside.any { it.path != child },
            )
        }
        .sortedBy { it.name.lowercase() }
}

/** How many songs a choice of folders reads. */
fun songsIncluded(folders: List<MusicFolder>, selection: FolderSelection): Int =
    folders.filter { selection.includes(it.path) }.sumOf { it.songCount }

/** "1 song", "12 songs". */
fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"

/**
 * A song on the phone and the folder it's in, with what the phone knows of its album: which album it
 * is ([albumKey], the phone's own), whose ([albumArtist], where the file says), which disc of it, and
 * when the song came onto the phone (seconds).
 */
data class PhoneSong(
    val track: Track,
    val folder: String,
    val albumKey: String? = null,
    val albumArtist: String? = null,
    val disc: Int = 0,
    val addedAt: Long = 0,
    /** When its file last changed (seconds). */
    val modifiedAt: Long = 0,
)

/** The music on the phone itself, read from the folders the listener chose. */
interface PhoneLibrary {
    /** The permission it needs to read the phone's audio. */
    val permission: String

    fun hasPermission(): Boolean

    /** Every folder that holds music, chosen or not; null until the phone has been looked at, or without permission. */
    val folders: StateFlow<List<MusicFolder>?>

    /** The songs in the chosen folders; null until the phone has been looked at. */
    val songs: StateFlow<List<PhoneSong>?>

    val selection: StateFlow<FolderSelection>

    fun select(selection: FolderSelection)

    /** Looks at the phone again. It also watches for itself once it has been asked once. */
    fun refresh()
}
