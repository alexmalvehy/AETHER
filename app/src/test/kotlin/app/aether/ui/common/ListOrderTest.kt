package app.aether.ui.common

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class ListOrderTest {

    private val english = Collator.getInstance(Locale.ENGLISH)

    private class Item(val name: String?, val at: Long = 0, val changed: Long = 0)

    private val byName = OrderBy<Item>(name = { it.name }, added = { it.at }, modified = { it.changed })

    private fun List<Item>.names() = map { it.name }

    @Test
    fun aToZ_comparesAsTheLanguageDoes_caseAndAccentsAside() {
        val list = listOf(Item("banana"), Item("Éclair"), Item("apple"), Item("Cherry"))
        assertEquals(listOf("apple", "banana", "Cherry", "Éclair"), list.inOrder(ListOrder.AToZ, byName, english).names())
    }

    @Test
    fun zToA_runsBackwards_butWhatHasNoNameStaysLast() {
        val list = listOf(Item("b"), Item(""), Item("a"), Item(null), Item("c"))
        assertEquals(listOf("c", "b", "a", "", null), list.inOrder(ListOrder.ZToA, byName, english).names())
        assertEquals(listOf("a", "b", "c", "", null), list.inOrder(ListOrder.AToZ, byName, english).names())
    }

    @Test
    fun sameNames_keepTheOrderTheyStoodIn_eitherWay() {
        val first = Item("Intro", at = 1)
        val second = Item("intro", at = 2)
        val list = listOf(first, Item("Outro"), second)
        assertEquals(listOf(first, second), list.inOrder(ListOrder.AToZ, byName, english).filter { it.name!!.lowercase() == "intro" })
        assertEquals(listOf(first, second), list.inOrder(ListOrder.ZToA, byName, english).filter { it.name!!.lowercase() == "intro" })
    }

    @Test
    fun dateAdded_newestFirst_tiesAsTheyStood() {
        val list = listOf(Item("a", at = 10), Item("b", at = 30), Item("c", at = 20), Item("d", at = 30))
        assertEquals(listOf("b", "d", "c", "a"), list.inOrder(ListOrder.Added, byName, english).names())
    }

    @Test
    fun dateAdded_withoutTimes_isTheListAsItStands() {
        // Liked, kept and saved lists are read newest first already.
        val list = listOf(Item("z"), Item("a"), Item("m"))
        assertEquals(list, list.inOrder(ListOrder.Added, OrderBy(name = { it.name }), english))
    }

    @Test
    fun dateModified_newestFirst() {
        val list = listOf(Item("a", changed = 5), Item("b", changed = 50), Item("c", changed = 7))
        assertEquals(listOf("b", "c", "a"), list.inOrder(ListOrder.Modified, byName, english).names())
    }

    @Test
    fun ownOrder_isLeftAlone() {
        val list = listOf(Item("z"), Item("a"))
        assertEquals(list, list.inOrder(ListOrder.Own, byName, english))
    }

    @Test
    fun songs_byArtistAlbumAndLength_unknownsLast() {
        fun song(id: String, artist: String?, album: String?, ms: Long?) =
            Track(MediaId("t:$id"), id, artists = listOfNotNull(artist?.let { ArtistRef(it) }), album = album?.let { AlbumRef(it) }, durationMs = ms)
        val list = listOf(song("1", "Zayn", "Mind", 200_000), song("2", null, null, null), song("3", "adele", "25", 90_000), song("4", "Björk", "Post", 300_000))
        assertEquals(listOf("3", "4", "1", "2"), list.inOrder(ListOrder.Artist, OrderBy.Songs, english).map { it.title })
        assertEquals(listOf("3", "1", "4", "2"), list.inOrder(ListOrder.Album, OrderBy.Songs, english).map { it.title })
        assertEquals(listOf("3", "1", "4", "2"), list.inOrder(ListOrder.Length, OrderBy.Songs, english).map { it.title })
    }

    @Test
    fun aKeptChoice_thatTheListDoesntOffer_fallsBack() {
        // A catalogue playlist doesn't know when its songs came: "added" kept for it reads as its own order.
        assertEquals(ListOrder.Own, ListOrder.of("added", ListOrder.CataloguePlaylist, ListOrder.Own))
        assertEquals(ListOrder.Added, ListOrder.of("nonsense", ListOrder.Songs, ListOrder.Added))
        assertEquals(ListOrder.Added, ListOrder.of(null, ListOrder.Songs, ListOrder.Added))
        assertEquals(ListOrder.ZToA, ListOrder.of("title-desc", ListOrder.Songs, ListOrder.Added))
    }

    @Test
    fun playlistOrdersKeptBefore_readAsTheyDid() {
        // SongOrder's keys: a playlist sorted by Title stays sorted A to Z.
        assertEquals(ListOrder.AToZ, ListOrder.of("title", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.Artist, ListOrder.of("artist", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.Album, ListOrder.of("album", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.Added, ListOrder.of("added", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.Length, ListOrder.of("length", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.Own, ListOrder.of("own", ListOrder.OwnPlaylist, ListOrder.Own))
        assertEquals(ListOrder.entries.size, ListOrder.entries.map { it.key }.toSet().size)
    }

    @Test
    fun everyListOffersItsDefault_andNoListOffersAnOrderTwice() {
        val lists = mapOf(
            ListOrder.Songs to ListOrder.Added,
            ListOrder.PhoneSongs to ListOrder.Added,
            ListOrder.Playlists to ListOrder.Added,
            ListOrder.Albums to ListOrder.Added,
            ListOrder.Artists to ListOrder.Added,
            ListOrder.OwnPlaylist to ListOrder.Own,
            ListOrder.CataloguePlaylist to ListOrder.Own,
        )
        lists.forEach { (offered, fallback) ->
            assert(fallback in offered) { "$offered lacks $fallback" }
            assertEquals(offered.size, offered.toSet().size)
        }
    }
}
