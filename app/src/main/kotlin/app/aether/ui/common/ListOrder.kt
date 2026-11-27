package app.aether.ui.common

import app.aether.core.model.Track
import java.text.CollationKey
import java.text.Collator

/**
 * The ways a list can stand. Each list offers only those it can honour: an order with nothing to go by
 * (the service doesn't say when a song came to its playlist; nothing changes a saved album) isn't offered.
 * [label] is how a choice reads in the menu; [short] names the order on the key, in place. [key] is how a
 * choice is kept: the keys a playlist's order was kept under before (own, title, artist, album, added,
 * length) still read as they did.
 */
enum class ListOrder(val key: String, val label: String, val short: String) {
    Own("own", "Your order", "Your order"),
    AToZ("title", "Alphabetical (A to Z)", "A to Z"),
    ZToA("title-desc", "Alphabetical (Z to A)", "Z to A"),
    Added("added", "Date added (newest first)", "Date added"),
    Modified("modified", "Date modified (newest first)", "Date modified"),
    Artist("artist", "Artist", "Artist"),
    Album("album", "Album", "Album"),
    Length("length", "Length", "Length");

    companion object {
        /** The order kept under [key], if [offered] has it; [fallback] otherwise. */
        fun of(key: String?, offered: List<ListOrder>, fallback: ListOrder): ListOrder = offered.firstOrNull { it.key == key } ?: fallback

        /** Songs gathered in the app (liked, kept): when each came is the list's own order. */
        val Songs = listOf(AToZ, ZToA, Added, Artist, Album, Length)

        /** The phone's own songs: their files say when they came, and when they last changed. */
        val PhoneSongs = listOf(AToZ, ZToA, Added, Modified, Artist, Album, Length)

        /** A playlist the listener made: as they arranged it, and when each song came. */
        val OwnPlaylist = listOf(Own, AToZ, ZToA, Added, Artist, Album, Length)

        /** A playlist of the catalogue's: as it stands there; the service doesn't say when a song came. */
        val CataloguePlaylist = listOf(Own, AToZ, ZToA, Artist, Album, Length)

        /** The Library's playlists: made or saved, and changed since. */
        val Playlists = listOf(AToZ, ZToA, Added, Modified)

        val Albums = listOf(AToZ, ZToA, Added, Artist)

        val Artists = listOf(AToZ, ZToA, Added)
    }
}

/**
 * What a list's items are sorted on. Times are any clock's, larger being newer. Left out, [added] means
 * the list already stands newest first; an order with nothing to sort on leaves the list as it stands.
 */
class OrderBy<T>(
    val name: (T) -> String?,
    val added: ((T) -> Long)? = null,
    val modified: ((T) -> Long)? = null,
    val artist: ((T) -> String?)? = null,
    val album: ((T) -> String?)? = null,
    val length: ((T) -> Long?)? = null,
) {
    companion object {
        /** Items that are songs ([track]): their names, artists, albums and lengths, and the times given. */
        fun <T> songs(track: (T) -> Track, added: ((T) -> Long)? = null, modified: ((T) -> Long)? = null) = OrderBy<T>(
            name = { track(it).title },
            added = added,
            modified = modified,
            artist = { track(it).artistLine },
            album = { track(it).album?.title },
            length = { track(it).durationMs },
        )

        /** Plain songs, the list standing newest first. */
        val Songs: OrderBy<Track> = songs({ it })
    }
}

/**
 * The list in [order]. Names compare as the phone's language does, case and accents aside; an item with
 * nothing to sort on goes last, whichever way; items that tie keep the order they stood in. Newest first
 * for times, shortest first for lengths.
 */
fun <T> List<T>.inOrder(order: ListOrder, by: OrderBy<T>, collator: Collator = Collator.getInstance()): List<T> {
    if (size < 2) return this
    fun byText(key: ((T) -> String?)?, descending: Boolean = false): List<T> {
        key ?: return this
        val words = (collator.clone() as Collator).apply { strength = Collator.PRIMARY }
        val keys: List<CollationKey?> = map { item -> key(item)?.trim()?.takeIf { it.isNotEmpty() }?.let(words::getCollationKey) }
        return indices.sortedWith { a, b ->
            val x = keys[a]
            val y = keys[b]
            when {
                x == null && y == null -> a - b
                x == null -> 1
                y == null -> -1
                else -> (if (descending) y.compareTo(x) else x.compareTo(y)).takeIf { it != 0 } ?: (a - b)
            }
        }.map { this[it] }
    }
    fun byTime(key: ((T) -> Long)?): List<T> {
        key ?: return this
        val times = map(key)
        return indices.sortedWith { a, b -> times[b].compareTo(times[a]).takeIf { it != 0 } ?: (a - b) }.map { this[it] }
    }
    return when (order) {
        ListOrder.Own -> this
        ListOrder.AToZ -> byText(by.name)
        ListOrder.ZToA -> byText(by.name, descending = true)
        ListOrder.Added -> byTime(by.added)
        ListOrder.Modified -> byTime(by.modified)
        ListOrder.Artist -> byText(by.artist)
        ListOrder.Album -> byText(by.album)
        ListOrder.Length -> {
            val length = by.length ?: return this
            val lengths = map(length)
            indices.sortedWith { a, b ->
                val x = lengths[a]
                val y = lengths[b]
                when {
                    x == null && y == null -> a - b
                    x == null -> 1
                    y == null -> -1
                    else -> x.compareTo(y).takeIf { it != 0 } ?: (a - b)
                }
            }.map { this[it] }
        }
    }
}
