package app.aether.ytmusic

import kotlinx.serialization.json.JsonObject
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What changing the account's own playlists reads back. A guest's capture shows a playlist that isn't
 * theirs; the answers to making and editing one are as YouTube Music gives them (checked live from the
 * phone, signed in, on 2026-11-24: docs/ytmusic-verification.md).
 */
class AccountEditTest {

    private fun fixture(name: String): JsonObject {
        val stream = checkNotNull(javaClass.getResourceAsStream("/fixtures/$name.json.gz")) { "missing fixture $name" }
        val bytes = GZIPInputStream(stream).use { it.readBytes() }
        return checkNotNull(YouTubeMusicClient.parseJson(bytes)) { "unreadable fixture $name" }
    }

    private fun json(text: String) = checkNotNull(YouTubeMusicClient.parseJson(text.toByteArray()))

    private val p = YouTubeMusicParser

    @Test
    fun `a chart is nobody's own playlist`() {
        val chart = checkNotNull(p.collection(fixture("playlist_chart"), albumPage = false))
        assertFalse(chart.editable)
    }

    @Test
    fun `the listener's own playlist comes in the header that offers to edit it`() {
        val page = json(
            """
            {"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"title":{"runs":[{"text":"Late nights"}]}}}}}
            ]}}}}],
            "secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"playlistId":"PLabc","contents":[
              {"musicResponsiveListItemRenderer":{
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Espresso","navigationEndpoint":{"watchEndpoint":{"videoId":"eVli-tstM5E"}}}]}}}],
                "playlistItemData":{"videoId":"eVli-tstM5E","playlistSetVideoId":"56B44F6D10557CC6"}}}
            ]}}]}}}}}
            """,
        )
        val mine = checkNotNull(p.collection(page, albumPage = false))
        assertTrue(mine.editable)
        assertEquals("Late nights", mine.title)
        assertEquals("56B44F6D10557CC6", mine.tracks.single().setVideoId)
    }

    @Test
    fun `a new playlist's id, and whether an edit took`() {
        assertEquals("PLtest123", p.createdPlaylist(json("""{"playlistId":"PLtest123"}""")))
        assertNull(p.createdPlaylist(json("""{"responseContext":{}}""")))
        assertTrue(p.edited(json("""{"status":"STATUS_SUCCEEDED","playlistEditResults":[]}""")))
        assertFalse(p.edited(json("""{"status":"STATUS_FAILED"}""")))
        assertFalse(p.edited(json("""{}""")))
    }
}
