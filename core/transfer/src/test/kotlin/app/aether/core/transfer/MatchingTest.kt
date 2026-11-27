package app.aether.core.transfer

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding the song a file names, and keeping every song once. */
class MatchingTest {
    private fun t(id: String, title: String, vararg artists: String, ms: Long? = null, album: String? = null, explicit: Boolean = false, kind: MediaKind = MediaKind.SONG) =
        Track(MediaId("ytm:$id"), title, artists.map { ArtistRef(it) }, album?.let { AlbumRef(it) }, durationMs = ms, explicit = explicit, kind = kind)

    private fun item(title: String, vararg artists: String, ms: Long? = null, explicit: Boolean? = null, album: String? = null) =
        ImportItem(title, artists.toList(), album = album, durationMs = ms, explicit = explicit)

    @Test fun `the song itself, its packaging set aside`() {
        val want = item("Blinding Lights", "The Weeknd", ms = 200_040)
        val found = Matcher.pick(want, listOf(
            t("cover", "Blinding Lights", "Some Covers Band", ms = 200_000),
            t("real", "Blinding Lights (Official Audio)", "The Weeknd", ms = 201_000),
        ))
        assertEquals("real", found?.id?.key)
    }

    @Test fun `a remaster is the same song, featured artists in the title are the song's`() {
        assertTrue(Matcher.accepts(item("Here Comes The Sun - Remastered 2019", "The Beatles"), t("a", "Here Comes The Sun (Remastered 2019)", "The Beatles")))
        assertTrue(Matcher.accepts(item("Goosebumps", "Travis Scott", "Kendrick Lamar"), t("b", "Goosebumps (feat. Kendrick Lamar)", "Travis Scott")))
    }

    @Test fun `live and studio, two remixes, never cross, and nothing is taken rather than the wrong one`() {
        assertNull(Matcher.pick(item("Yellow (Live)", "Coldplay"), listOf(t("studio", "Yellow", "Coldplay"))))
        assertNull(Matcher.pick(item("Yellow", "Coldplay"), listOf(t("live", "Yellow (Live at Glastonbury)", "Coldplay"))))
        assertNull(Matcher.pick(item("Titanium (David Guetta Remix)", "Sia"), listOf(t("other", "Titanium (Alesso Remix)", "Sia"))))
    }

    @Test fun `another artist's song of the same name isn't it`() {
        assertNull(Matcher.pick(item("Hello", "Adele"), listOf(t("x", "Hello", "Lionel Richie"), t("y", "Hello", "Evanescence"))))
    }

    @Test fun `lengths far apart are two recordings`() {
        assertNull(Matcher.pick(item("Intro", "The xx", ms = 128_000), listOf(t("long", "Intro", "The xx", ms = 300_000))))
        assertTrue(Matcher.accepts(item("Intro", "The xx", ms = 128_000), t("ok", "Intro", "The xx", ms = 131_000)))
    }

    @Test fun `known by its title alone, it must be the title exactly and close in length`() {
        assertTrue(Matcher.accepts(item("Housewife", ms = 208_000), t("a", "Housewife", "Tate McRae", ms = 208_810)))
        assertFalse(Matcher.accepts(item("Housewife", ms = 208_000), t("b", "Housewife", "Someone", ms = 260_000)))
        assertFalse(Matcher.accepts(item("Housewife"), t("c", "Housewife (Sped Up)", "Tate McRae")))
    }

    @Test fun `explicit where the file says so, a clean edition only when there's nothing else`() {
        val want = item("Not Like Us", "Kendrick Lamar", explicit = true)
        assertEquals("e", Matcher.pick(want, listOf(t("c", "Not Like Us", "Kendrick Lamar"), t("e", "Not Like Us", "Kendrick Lamar", explicit = true)))?.id?.key)
        assertNull(Matcher.pick(item("Song (Clean)", "A"), listOf(t("x", "Song (Explicit)", "A"))))
    }

    @Test fun `the song before its video, the same album before another`() {
        val want = item("Stateside", "PinkPantheress", album = "Fancy That")
        val found = Matcher.pick(want, listOf(
            t("video", "Stateside", "PinkPantheress", kind = MediaKind.MUSIC_VIDEO),
            t("other", "Stateside", "PinkPantheress", album = "Stateside (Remixes)"),
            t("album", "Stateside", "PinkPantheress", album = "Fancy That"),
        ))
        assertEquals("album", found?.id?.key)
    }

    @Test fun `a link is checked against the words beside it`() {
        val track = t("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley")
        assertTrue(Matcher.confirms(item("Never Gonna Give You Up", "Rick Astley"), track))
        assertFalse(Matcher.confirms(item("Bohemian Rhapsody", "Queen"), track), "a stale link leads to some other video")
        assertTrue(Matcher.confirms(ImportItem(title = "", youtubeId = "dQw4w9WgXcQ"), track), "a link alone claims nothing more")
    }

    @Test fun `queries go from the most precise`() {
        assertEquals(
            listOf("Goosebumps Travis Scott", "Goosebumps Travis Scott, Kendrick Lamar", "Travis Scott, Kendrick Lamar Goosebumps", "Goosebumps"),
            Matcher.queries(item("Goosebumps", "Travis Scott", "Kendrick Lamar")),
        )
        val guessed = ImportItem("Hello", listOf("Adele"), reference = "Hello by Adele", loose = true)
        assertEquals(listOf("Hello" to listOf("Adele"), "Hello by Adele" to emptyList()), Matcher.readings(guessed).map { it.title to it.artists })
    }

    @Test fun `the same song is the same id, or the same title and artists however spaced`() {
        assertTrue(Songs.same(t("a", "Song", "X"), t("a", "Other", "Y")))
        assertTrue(Songs.same(t("a", "Boy's a liar  Pt. 2 ", "PinkPantheress"), t("b", "boy's a liar pt. 2", "pinkpantheress")))
        assertFalse(Songs.same(t("a", "Song", "X"), t("b", "Song", "Y")))
    }

    @Test fun `only what a playlist hasn't is added, each song once, in order`() {
        val have = listOf(t("1", "One", "A"), t("2", "Two", "A"))
        val incoming = listOf(t("3", "Three", "A"), t("1", "One", "A"), t("2b", "Two", "A"), t("4", "Four", "A"), t("3", "Three", "A"))
        assertEquals(listOf("3", "4"), Songs.missing(have, incoming).map { it.id.key })
        assertEquals(listOf("3", "1", "2b", "4"), Songs.distinct(incoming).map { it.id.key })
    }

    @Test fun `a playlist read again goes into the one it made, never a second`() {
        val songs = listOf(t("1", "One", "A"))
        val here = listOf(Existing(5, "Chill", songs), Existing(6, "Road", emptyList()))
        val chill = ImportedPlaylist("Chill", emptyList(), origin = "spotify:abc")
        assertEquals(Destination.Into(6), Plan.destination(chill, owner = 6, existing = here, resolved = songs), "by its origin, even renamed")
        assertEquals(Destination.Into(5), Plan.destination(chill, owner = null, existing = here, resolved = songs), "the same name with every song already")
        assertEquals(Destination.New("Chill 2"), Plan.destination(chill, owner = null, existing = here, resolved = songs + t("2", "Two", "A")), "the same name, other songs")
        assertEquals(Destination.New("Chill 2"), Plan.destination(chill, owner = 99, existing = here, resolved = listOf(t("9", "Nine", "B"))), "its old playlist deleted")
        assertEquals("Chill 3", Songs.uniqueName("Chill", listOf("chill", "Chill 2")))
    }
}
