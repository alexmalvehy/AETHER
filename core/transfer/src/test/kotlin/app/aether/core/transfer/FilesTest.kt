package app.aether.core.transfer

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CSV, text lists and M3U, as other apps and people write them, and as AETHER writes them. */
class FilesTest {
    private fun ok(r: ImportRead): ImportedLibrary = assertIs<ImportRead.Ok>(r, (r as? ImportRead.Failed)?.reason).library
    private fun csv(text: String, name: String = "Chill_Vibes.csv") = ok(Csv.read(text, name)).playlists.single()

    @Test fun `Exportify's columns, quoted commas and all`() {
        val p = csv(
            "\"Track URI\",\"Track Name\",\"Album Name\",\"Artist Name(s)\",\"Release Date\",\"Duration (ms)\",\"Popularity\",\"Explicit\",\"ISRC\"\n" +
                "\"spotify:track:3h5T5JypYU7huFiVYhv1dr\",\"BbY WOW\",\"Tropicoqueta\",\"KAROL G,Judeline,rusowsky\",\"2025-06-20\",\"225834\",\"80\",\"false\",\"USUM72510001\"\n" +
                "\"spotify:track:0aB\",\"Song, With a Comma\",\"An \"\"Album\"\"\",\"Someone\",\"2020\",\"180000\",\"50\",\"true\",\"\"\n",
        )
        assertEquals("Chill Vibes", p.name)
        assertEquals(listOf("BbY WOW", "Song, With a Comma"), p.items.map { it.title })
        val first = p.items[0]
        assertEquals(listOf("KAROL G,Judeline,rusowsky"), first.artists)
        assertEquals("Tropicoqueta", first.album)
        assertEquals(225_834L, first.durationMs)
        assertEquals(false, first.explicit)
        assertEquals("USUM72510001", first.isrc)
        assertNull(first.youtubeId, "a Spotify uri isn't a YouTube link")
        assertEquals("An \"Album\"", p.items[1].album)
        assertEquals(true, p.items[1].explicit)
    }

    @Test fun `AETHER's own CSV comes back as itself, a title beginning with a hash included`() {
        val tracks = listOf(
            Track(MediaId("ytm:dQw4w9WgXcQ"), "#icanteven (feat. French Montana)", listOf(ArtistRef("The Neighbourhood")), AlbumRef("Wiped Out!"), durationMs = 235_000),
            Track(MediaId("local:42"), "Track, \"Quoted\"", listOf(ArtistRef("A"), ArtistRef("B")), durationMs = 61_000),
        )
        val p = csv(Csv.write(tracks), "Mine.csv")
        assertEquals(listOf("#icanteven (feat. French Montana)", "Track, \"Quoted\""), p.items.map { it.title })
        assertEquals("dQw4w9WgXcQ", p.items[0].youtubeId)
        assertEquals(listOf("The Neighbourhood"), p.items[0].artists)
        assertEquals("Wiped Out!", p.items[0].album)
        assertEquals(235_000L, p.items[0].durationMs)
        assertEquals(listOf("A, B"), p.items[1].artists)
        assertNull(p.items[1].youtubeId)
    }

    @Test fun `tabs win over a comma inside an artist, by how evenly they split the rows`() {
        val p = csv("Title\tArtist\tAlbum\nOne\tTyler, The Creator\tIGOR\nTwo\tEarth, Wind & Fire\tSeptember\n", "x.tsv")
        assertEquals(listOf("One", "Two"), p.items.map { it.title })
        assertEquals(listOf("Tyler, The Creator"), p.items[0].artists)
    }

    @Test fun `Excel's sep line and comments above the table are passed over`() {
        val p = csv("sep=;\n# exported by something\nTitre;Artiste\nLa Vie en rose;Édith Piaf\n")
        assertEquals("La Vie en rose", p.items.single().title)
        assertEquals(listOf("Édith Piaf"), p.items.single().artists)
    }

    @Test fun `an unclosed quote doesn't swallow every row after it`() {
        val p = csv("Title,Artist\n\"Broken,Someone\nSecond,Two\nThird,Three\n")
        assertEquals(3, p.items.size)
        assertEquals(listOf("Second", "Third"), p.items.drop(1).map { it.title })
    }

    @Test fun `a table without a header is read by what its columns hold`() {
        val p = csv("1,Blinding Lights,The Weeknd\n2,Levitating,Dua Lipa\n3,Stay,The Kid LAROI\n")
        assertEquals(listOf("Blinding Lights", "Levitating", "Stay"), p.items.map { it.title })
        assertEquals(listOf("The Weeknd"), p.items[0].artists)
    }

    @Test fun `a list of songs, one a line, the ways people write them`() {
        val items = ok(Csv.read("1. Daft Punk - One More Time\nStand by Me - Ben E. King\nHello by Adele\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ&si=x\nJust A Title\n", "mix.txt")).playlists.single().items
        assertEquals("One More Time", items[0].title)
        assertEquals(listOf("Daft Punk"), items[0].artists)
        // A dash in free text could go either way round: tried both ways, and never as a song called "Stand".
        assertTrue(items[1].loose)
        assertTrue(Matcher.readings(items[1]).any { it.title == "Stand by Me" && it.artists == listOf("Ben E. King") })
        assertTrue(Matcher.readings(items[1]).none { it.title == "Stand" })
        assertEquals("Hello", items[2].title)
        assertTrue(items[2].loose, "a split at \"by\" is a guess")
        assertEquals("dQw4w9WgXcQ", items[3].youtubeId)
        assertEquals("Just A Title", items[4].title)
        assertTrue(items[4].artists.isEmpty())
    }

    @Test fun `a placeholder artist is no artist`() {
        assertTrue(csv("Title,Artist\nSomething,Unknown Artist\n").items.single().artists.isEmpty())
    }

    @Test fun `M3U as AETHER writes it comes back whole`() {
        val tracks = listOf(
            Track(MediaId("ytm:dQw4w9WgXcQ"), "Never Gonna Give You Up", listOf(ArtistRef("Rick Astley")), AlbumRef("Whenever You Need Somebody"), durationMs = 213_000),
            Track(MediaId("local:7"), "Kept Here", listOf(ArtistRef("Someone")), durationMs = 100_000),
        )
        val p = ok(M3u.read(M3u.write("Road, Trip", tracks), "whatever.m3u8")).playlists.single()
        assertEquals("Road, Trip", p.name)
        assertEquals(listOf("Never Gonna Give You Up", "Kept Here"), p.items.map { it.title })
        assertEquals("dQw4w9WgXcQ", p.items[0].youtubeId)
        assertEquals(listOf("Rick Astley"), p.items[0].artists)
        assertEquals(213_000L, p.items[0].durationMs)
        assertEquals("Whenever You Need Somebody", p.items[0].album)
        assertNull(p.items[1].youtubeId, "a song with no link of its own is found by its words")
    }

    @Test fun `M3U from a desktop player, with paths, attributes and a song with no EXTINF`() {
        val text = "#EXTM3U\r\n" +
            "#EXTINF:245,Radiohead - Karma Police\r\nC:\\Music\\Radiohead\\OK Computer\\06 Karma Police.mp3\r\n" +
            "#EXTINF:-1 artist=\"Björk\" title=\"Jóga\",ignored words\r\n/sdcard/Music/joga.flac\r\n" +
            "Music/Massive Attack - Teardrop.mp3\r\n"
        val items = ok(M3u.read(text, "favourites.m3u")).playlists.single().items
        assertEquals(listOf("Karma Police", "Jóga", "Teardrop"), items.map { it.title })
        assertEquals(listOf("Radiohead"), items[0].artists)
        assertEquals(245_000L, items[0].durationMs)
        assertEquals(listOf("Björk"), items[1].artists)
        assertEquals(listOf("Massive Attack"), items[2].artists)
        assertTrue(items.all { it.local }, "files on a computer: the phone's music is looked through first")
    }

    @Test fun `the same playlist in another file has the same origin`() {
        val a = ok(Csv.read("Title,Artist\nA,B\n", "Chill.csv")).playlists.single()
        val b = ok(M3u.read("#EXTM3U\n#EXTINF:1,B - A\nx.mp3\n", "chill.m3u8")).playlists.single()
        assertEquals(a.origin, b.origin)
    }

    @Test fun `bytes from an old Windows player read as themselves`() {
        val bytes = "#EXTM3U\n#EXTINF:200,Beyoncé - Halo\nhalo.mp3\n".toByteArray(charset("windows-1252"))
        val r = Imports.read(bytes.inputStream(), "old.m3u", kotlin.io.path.createTempFile().toFile())
        assertEquals(listOf("Beyoncé"), ok(r).playlists.single().items.single().artists)
    }
}
