package app.aether.ui.lyrics

import app.aether.Container
import app.aether.core.lyrics.Lyrics
import app.aether.core.lyrics.LyricsFormatter
import app.aether.core.lyrics.LyricsLine
import app.aether.core.lyrics.LyricsRequest
import app.aether.core.lyrics.LyricsResult
import app.aether.core.model.Track
import app.aether.ui.player.isVideo

/** What the lyrics view has to show. */
internal sealed interface Words {
    data object Waiting : Words
    data class Timed(val lines: List<LyricsLine>, val estimated: Boolean, val credit: String) : Words
    data class Untimed(val lines: List<String>, val credit: String) : Words
    data object Instrumental : Words
    data class Silent(val title: String, val body: String) : Words
}

internal fun shape(lyrics: Lyrics, credit: String): Words = when (lyrics) {
    is Lyrics.Synced -> Words.Timed(lyrics.lines, lyrics.estimated, credit)
    is Lyrics.Plain -> Words.Untimed(lyrics.lines, credit)
    Lyrics.Instrumental -> Words.Instrumental
}

/**
 * The song's words: timed ones from LRCLIB (on unless the listener turned it off, on the welcome or
 * in Settings), otherwise (or when it has none) the music service's own words ([own]), paced across
 * the song and marked as estimated. A song on the phone brings its own first; a kept one falls back
 * to the words it was kept with. Nothing is asked here: the choice lives in Settings.
 *
 * Worked out the same way wherever the words are shown: the lyrics view, and a TV's stage
 * (cast/CastStage.kt), so a TV never shows other words, or other credit, than the phone would.
 * [ownPending] while the music service is still being asked.
 */
internal suspend fun wordsFor(container: Container, track: Track, own: app.aether.core.model.Lyrics?, ownPending: Boolean): Words {
    // A video plays without lyrics: its sound runs to its own clock, and words timed to the song
    // would drift from it. Nothing is asked.
    if (track.isVideo) return Words.Silent("No lyrics for videos", "A video's sound runs to its own clock, so words timed to the song would drift from it.")
    // A song on the phone brings its own words first: its .lrc beside it, or the words in its file.
    val onPhone = track.id.source == container.local.id
    if (onPhone) container.localLyrics.of(track)?.let { found ->
        return shape(LyricsFormatter.forPlayback(found.lyrics, track.durationMs, track.title), found.credit)
    }
    val request = LyricsRequest(key = track.id.toString(), title = track.title, artist = track.artistLine, album = track.album?.title, durationMs = track.durationMs)
    val answer = container.lyrics.lyrics(request)
    // A kept song, offline or unanswered: the words it was kept with.
    if (!onPhone && answer !is LyricsResult.Found) container.localLyrics.of(track)?.let { found ->
        return shape(LyricsFormatter.forPlayback(found.lyrics, track.durationMs, track.title), found.credit)
    }
    val theirs: Words? = own?.let { l ->
        val lines = l.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) null else shape(LyricsFormatter.forPlayback(Lyrics.Plain(lines), track.durationMs), l.source ?: "YouTube Music")
    }
    val missing = if (ownPending) Words.Waiting else null
    return when (answer) {
        is LyricsResult.Found -> shape(answer.lyrics, answer.provider.name)
        LyricsResult.NeedsConsent -> theirs ?: missing ?: Words.Silent("No words yet", "Timed lyrics are off. Turn them on in Settings to ask LRCLIB, an open library of lyrics.")
        LyricsResult.NotFound -> theirs ?: missing ?: Words.Silent("No words for this one", "Neither LRCLIB nor the music service has lyrics for this song.")
        LyricsResult.Offline -> theirs ?: missing ?: Words.Silent("Offline", "Lyrics need a connection the first time.")
        LyricsResult.Failed -> theirs ?: missing ?: Words.Silent("No answer", "The lyrics service didn't answer. Try again in a while.")
        is LyricsResult.RateLimited -> theirs ?: missing ?: Words.Silent("Asked too often", "LRCLIB asked for a pause; it'll be asked again shortly.")
    }
}
