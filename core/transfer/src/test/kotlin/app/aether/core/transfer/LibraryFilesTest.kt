package app.aether.core.transfer

import app.aether.core.model.AlbumRef
import app.aether.core.model.ArtistRef
import app.aether.core.model.Artwork
import app.aether.core.model.MediaId
import app.aether.core.model.Track
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Whole libraries in a file: Spotify's data download, AETHER's own file, and telling files apart. */
class LibraryFilesTest {
    private fun ok(r: ImportRead): ImportedLibrary = assertIs<ImportRead.Ok>(r, (r as? ImportRead.Failed)?.reason).library
    private fun temp(): File = kotlin.io.path.createTempFile().toFile().also { it.deleteOnExit() }

    private fun spotifyPlaylists(vararg names: String) = """{"playlists":[${names.joinToString(",") { n ->
        """{"name":"$n","lastModifiedDate":"2025-01-01","items":[{"track":{"trackName":"$n song","artistName":"$n artist","albumName":"$n album","trackUri":"spotify:track:x$n"},"episode":null,"localTrack":null,"addedDate":"2025-01-01"},""" +
            """{"track":null,"episode":{"episodeName":"A podcast"},"localTrack":null},""" +
            """{"track":null,"episode":null,"localTrack":{"uri":"spotify:local:Some+Band:Their+Album:A+Song+%26+More:215"}}],"description":null,"numberOfFollowers":0}"""
    }}]}"""

    private val yourLibrary = """{"tracks":[{"artist":"Tate McRae","album":"So Close To What","track":"Sports car","uri":"spotify:track:abc"}],"albums":[],"shows":[]}"""

    private fun zip(entries: Map<String, String>): File = temp().also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
    }

    @Test fun `Spotify's data download, every playlist in order and the liked songs`() {
        val file = zip(mapOf(
            "Spotify Account Data/Playlist10.json" to spotifyPlaylists("Ten"),
            "Spotify Account Data/Playlist1.json" to spotifyPlaylists("One", "Two"),
            "Spotify Account Data/Playlist2.json" to spotifyPlaylists("Three"),
            "Spotify Account Data/YourLibrary.json" to yourLibrary,
            "Spotify Account Data/StreamingHistory_music_0.json" to "[]",
            "Spotify Account Data/ReadMeFirst_AccountData.pdf" to "not read",
        ))
        val lib = ok(Imports.read(file.inputStream(), "my_spotify_data.zip", temp()))
        assertEquals(ImportFormat.SPOTIFY_DATA, lib.format)
        assertEquals(listOf("One", "Two", "Three", "Ten"), lib.playlists.map { it.name })
        val one = lib.playlists.first()
        assertEquals(listOf("One song", "A Song & More"), one.items.map { it.title }, "songs and local files; episodes left out")
        assertEquals(listOf("Some Band"), one.items[1].artists)
        assertEquals(215_000L, one.items[1].durationMs)
        assertTrue(one.items[1].local)
        assertEquals("Sports car", lib.liked.single().title)
        assertEquals(listOf("Tate McRae"), lib.liked.single().artists)
    }

    @Test fun `a zip that isn't Spotify's says so`() {
        val r = Imports.read(zip(mapOf("photo.jpg" to "x")).inputStream(), "photos.zip", temp())
        assertTrue((r as ImportRead.Failed).reason.contains("Spotify"))
    }

    @Test fun `one of the download's files taken out reads alone`() {
        val lib = ok(Imports.read(spotifyPlaylists("Solo").byteInputStream(), "Playlist1.json", temp()))
        assertEquals("Solo", lib.playlists.single().name)
    }

    private val song = Track(
        MediaId("ytm:dQw4w9WgXcQ"), "Never Gonna Give You Up", listOf(ArtistRef("Rick Astley", MediaId("ytm:UCuAXFkgsw1L7xaCfnd5JJOw"))),
        AlbumRef("Whenever You Need Somebody", MediaId("ytm:MPREb_x")), durationMs = 213_000, artwork = Artwork.single("https://i.ytimg.com/x.jpg", 544, 544),
        explicit = false, year = 1987,
    )

    @Test fun `AETHER's own file brings every playlist back whole, by its own lasting id`() {
        val text = AetherFile.write(
            listOf(AetherFile.Out("3f1c-uuid", "Road Trip", "for the car", "Moods", listOf(song))),
            liked = listOf(song), appVersion = "2.4.0", exportedAt = "2026-11-16T12:00:00Z",
        )
        val lib = ok(Imports.read(text.byteInputStream(), "AETHER playlists.json", temp()))
        assertEquals(ImportFormat.AETHER, lib.format)
        val p = lib.playlists.single()
        assertEquals("aether:3f1c-uuid", p.origin)
        assertEquals("Moods", p.folder)
        assertEquals("for the car", p.description)
        val back = p.items.single().track!!
        assertEquals(song.id, back.id)
        assertEquals(song.title, back.title)
        assertEquals(song.artists, back.artists)
        assertEquals(song.album, back.album)
        assertEquals(song.artwork, back.artwork)
        assertEquals(1987, back.year)
        assertEquals("dQw4w9WgXcQ", p.items.single().youtubeId)
        assertEquals(song.id, lib.liked.single().track!!.id)
    }

    @Test fun `a newer AETHER's file, or another app's, is refused whole`() {
        val newer = AetherFile.write(emptyList(), null, "9", "now").replace("\"version\": 1", "\"version\": 99")
        assertTrue((Imports.read(newer.byteInputStream(), "a.json", temp()) as ImportRead.Failed).reason.contains("newer AETHER"))
        assertIs<ImportRead.Failed>(Imports.read("""{"app":"other","kind":"playlists"}""".byteInputStream(), "b.json", temp()))
    }

    @Test fun `pictures, empty files and unknown JSON are refused in words`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0, 0, 13)
        assertIs<ImportRead.Failed>(Imports.read(png.inputStream(), "cover.png", temp()))
        assertIs<ImportRead.Failed>(Imports.read(ByteArray(0).inputStream(), "empty.csv", temp()))
        assertIs<ImportRead.Failed>(Imports.read("""{"something":"else"}""".byteInputStream(), "x.json", temp()))
    }
}
