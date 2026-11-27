package app.aether.player.cast

import androidx.media3.common.Player
import com.google.android.gms.cast.MediaStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone tells a TV, and serves it, worked out without a TV. */
class CastTest {

    // --- The ranges a TV asks for --------------------------------------------------------------------

    @Test
    fun noRangeIsTheWholeSong() {
        assertEquals(Ranges.Answer.Whole, Ranges.of(null, 1_000))
    }

    @Test
    fun aTvsFirstAskIsFromTheStartToTheEnd() {
        assertEquals(Ranges.Answer.Part(0, 999), Ranges.of("bytes=0-", 1_000))
    }

    @Test
    fun aClosedRangeIsServedAsAsked() {
        assertEquals(Ranges.Answer.Part(100, 199), Ranges.of("bytes=100-199", 1_000))
    }

    @Test
    fun aRangeRunningPastTheEndStopsAtIt() {
        assertEquals(Ranges.Answer.Part(900, 999), Ranges.of("bytes=900-5000", 1_000))
    }

    @Test
    fun aSuffixIsTheLastBytes() {
        assertEquals(Ranges.Answer.Part(500, 999), Ranges.of("bytes=-500", 1_000))
        // More than there is: all of it.
        assertEquals(Ranges.Answer.Part(0, 999), Ranges.of("bytes=-5000", 1_000))
    }

    @Test
    fun whatCantBeServedIsSaidSo() {
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=1000-", 1_000))
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=200-100", 1_000))
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=-0", 1_000))
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("items=0-10", 1_000))
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=0-10,20-30", 1_000))
    }

    @Test
    fun aSongOfNoKnownLengthIsSentFromItsStart() {
        // A stream that doesn't say how long it is: the TV's first ask is answered whole.
        assertEquals(Ranges.Answer.Whole, Ranges.of("bytes=0-", -1))
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=5000-", -1))
    }

    @Test
    fun anEmptySongHasNoRanges() {
        assertEquals(Ranges.Answer.Unsatisfiable, Ranges.of("bytes=0-", 0))
    }

    // --- What the TV already holds ---------------------------------------------------------------------

    @Test
    fun theMatchingRunAfterThePlayingSongIsKept() {
        assertEquals(2, QueueWindow.kept(listOf("a", "b", "x"), listOf("a", "b", "c")))
        assertEquals(3, QueueWindow.kept(listOf("a", "b", "c"), listOf("a", "b", "c", "d")))
        assertEquals(0, QueueWindow.kept(listOf("x", "b"), listOf("a", "b")))
    }

    @Test
    fun aSongTheTvCantNameMatchesNothing() {
        assertEquals(1, QueueWindow.kept(listOf("a", null, "c"), listOf("a", "b", "c")))
        assertEquals(0, QueueWindow.kept(emptyList(), listOf("a")))
    }

    // --- Where the TV was asked to be ------------------------------------------------------------------

    @Test
    fun aSongPutOnTheTvAtItsPlaceIsShownThereUntilTheTvGetsThere() {
        // Asked for 0:19; the TV's first word, still loading, is the song's start: the 0:19 stands.
        assertFalse(Requested.answered(reportedMs = 0, requestedMs = 19_986, requestedAt = 1_000, now = 2_500))
        assertTrue(Requested.held(requestedAt = 1_000, now = 2_500))
        // There: let go of.
        assertTrue(Requested.answered(reportedMs = 20_100, requestedMs = 19_986, requestedAt = 1_000, now = 4_000))
    }

    @Test
    fun aPlaceTheTvNeverReachesIsGivenUpOn() {
        assertTrue(Requested.answered(reportedMs = 0, requestedMs = 60_000, requestedAt = 1_000, now = 9_000))
        assertFalse(Requested.held(requestedAt = 1_000, now = 9_000))
    }

    @Test
    fun nothingAskedIsNothingHeld() {
        assertTrue(Requested.answered(reportedMs = 5_000, requestedMs = 0, requestedAt = 0, now = 1_000))
        assertFalse(Requested.held(requestedAt = 0, now = 1_000))
    }

    // --- A skip, while the TV gets there --------------------------------------------------------------

    @Test
    fun whatTheTvSaysOnItsWayIsAboutTheSongItsLeaving() {
        val e = Expected()
        e.expect("next", now = 0, forMs = 10_000)
        // Still playing the song the skip left: not followed back.
        assertTrue(e.onTheWay(onTv = "this", idle = false, now = 500))
        // There, and begun: the TV is believed again.
        assertFalse(e.onTheWay(onTv = "next", idle = false, now = 2_000))
        assertEquals(null, e.mediaId)
        // A move after that is the TV's own (a song that ended): followed.
        assertFalse(e.onTheWay(onTv = "after", idle = false, now = 3_000))
    }

    @Test
    fun twoSkipsLandOnTheSecondNotTheFirstOrAThird() {
        val e = Expected()
        e.expect("one", now = 0, forMs = 10_000)
        e.expect("two", now = 300, forMs = 10_000)
        // The TV takes the first skip on its way to the second: the phone stays on the second.
        assertTrue(e.onTheWay(onTv = "one", idle = false, now = 900))
        assertFalse(e.onTheWay(onTv = "two", idle = false, now = 1_800))
    }

    @Test
    fun aSongNotYetBegunIsStillOnItsWay() {
        val e = Expected()
        e.expect("next", now = 0, forMs = 30_000)
        assertTrue(e.onTheWay(onTv = "next", idle = true, now = 4_000))
    }

    @Test
    fun aMoveTheTvNeverFinishesIsGivenUpOn() {
        val e = Expected()
        e.expect("next", now = 0, forMs = 10_000)
        assertFalse(e.onTheWay(onTv = "this", idle = false, now = 10_000))
        assertEquals(null, e.mediaId)
    }

    @Test
    fun nothingAskedForIsNothingWaitedFor() {
        assertFalse(Expected().onTheWay(onTv = "this", idle = false, now = 0))
    }

    // --- Repeat ---------------------------------------------------------------------------------------

    @Test
    fun onlyOneSongRepeatsOnTheTvItself() {
        assertEquals(MediaStatus.REPEAT_MODE_REPEAT_SINGLE, CastPlayback.receiverRepeat(Player.REPEAT_MODE_ONE))
        assertEquals(MediaStatus.REPEAT_MODE_REPEAT_OFF, CastPlayback.receiverRepeat(Player.REPEAT_MODE_OFF))
        // The whole queue's repeat is the phone's to keep: the TV only ever holds a few songs of it.
        assertEquals(MediaStatus.REPEAT_MODE_REPEAT_OFF, CastPlayback.receiverRepeat(Player.REPEAT_MODE_ALL))
    }

    // --- What can be handed on whole ----------------------------------------------------------------

    @Test
    fun aManifestIsKnownByItsTypeOrItsPath() {
        assertTrue(CastMime.isManifest("https://example.com/a", "application/vnd.apple.mpegurl"))
        assertTrue(CastMime.isManifest("https://example.com/a", "application/x-mpegURL"))
        assertTrue(CastMime.isManifest("https://example.com/a", "application/dash+xml"))
        assertTrue(CastMime.isManifest("https://example.com/master.m3u8?token=1", null))
        assertTrue(CastMime.isManifest("https://example.com/stream.MPD#t=10", null))
    }

    @Test
    fun aSongFileIsNoManifest() {
        assertFalse(CastMime.isManifest("https://example.com/song.webm?mime=audio%2Fwebm", "audio/webm"))
        assertFalse(CastMime.isManifest("https://example.com/videoplayback?id=1&file=a.m3u8x", null))
        assertFalse(CastMime.isManifest("file:///storage/song.flac", null))
    }
}
