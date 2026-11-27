package app.aether.player

import app.aether.core.model.ArtistRef
import app.aether.core.model.MediaId
import app.aether.core.model.MediaKind
import app.aether.core.model.Track
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/** BitChord's station rules, over AETHER's tracks. */
class StationTest {
    private fun song(id: String, title: String, vararg artists: String, kind: MediaKind = MediaKind.SONG) =
        Track(MediaId("ytm:$id"), title, artists.map { ArtistRef(it) }, kind = kind)

    @Test
    fun theSameRecordingUnderAnotherIdIsTheSameSong() {
        val audio = song("a", "Kesariya", "Arijit Singh")
        val video = song("b", "Kesariya (From \"Brahmastra\") | Official Video", "Arijit Singh", kind = MediaKind.MUSIC_VIDEO)
        assertTrue(Station.sameRecording(audio, video))
    }

    @Test
    fun billingOrderDoesntMakeAnotherSong() {
        val one = song("a", "Tum Kya Mile", "Pritam, Arijit Singh & Shilpa Rao")
        val two = song("b", "Tum Kya Mile", "Shilpa Rao", "Arijit Singh", "Pritam")
        assertTrue(Station.sameRecording(one, two))
    }

    @Test
    fun aRemixIsAnotherSong() {
        assertFalse(Station.sameRecording(song("a", "Levitating", "Dua Lipa"), song("b", "Levitating (Remix)", "Dua Lipa")))
    }

    @Test
    fun theSameTitleBySomeoneElseIsAnotherSong() {
        assertFalse(Station.sameRecording(song("a", "Hello", "Adele"), song("b", "Hello", "Lionel Richie")))
    }

    @Test
    fun nothingAlreadyQueuedComesAgain() {
        val queued = listOf(song("a", "One", "X"), song("b", "Two", "Y"))
        val radio = listOf(song("a", "One", "X"), song("c", "Two | Official Video", "Y", kind = MediaKind.MUSIC_VIDEO), song("d", "Three", "Z"))
        assertEquals(listOf("ytm:d"), Station.extend(queued, radio, limit = 10).map { it.id.value })
    }

    @Test
    fun songsComeBeforeVideosAndVideosOnlyWhenThereAreNoSongs() {
        val seed = song("s", "Seed", "S")
        val radio = listOf(song("v", "Video", "V", kind = MediaKind.MUSIC_VIDEO), song("t", "Track", "T"))
        assertEquals(listOf("ytm:t"), Station.extend(listOf(seed), radio, limit = 10).map { it.id.value })
        val onlyVideos = listOf(song("v", "Video", "V", kind = MediaKind.MUSIC_VIDEO))
        assertEquals(listOf("ytm:v"), Station.extend(listOf(seed), onlyVideos, limit = 10).map { it.id.value })
    }

    @Test
    fun noArtistTakesOverTheStation() {
        val seed = song("s", "Seed", "Seed Artist")
        val radio = (1..6).map { song("o$it", "Other $it", "Other") } + (1..6).map { song("s$it", "Own $it", "Seed Artist") }
        val out = Station.extend(listOf(seed), radio, limit = 20, seed = seed)
        assertEquals(Station.PER_ARTIST_LIMIT, out.count { it.artists.first().name == "Other" })
        assertEquals(Station.SEED_ARTIST_LIMIT, out.count { it.artists.first().name == "Seed Artist" })
    }

    @Test
    fun noMoreThanAskedFor() {
        val radio = (1..30).map { song("r$it", "Song $it", "Artist $it") }
        assertEquals(10, Station.extend(emptyList(), radio, limit = 10).size)
        assertEquals(0, Station.extend(emptyList(), radio, limit = 0).size)
    }

    @Test
    fun aSongThatCantPlayIsLeftOut() {
        val radio = listOf(song("r", "Gone", "A").copy(available = false), song("k", "Here", "B"))
        assertEquals(listOf("ytm:k"), Station.extend(emptyList(), radio, limit = 10).map { it.id.value })
    }
}
