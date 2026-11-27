// Ported from BitChord (app/src/main/java/com/music/bitchord/widget/MediaWidgetSnapshot.kt,
// github.com/kushagrasinghx/BitChord, GPL-3.0): trimmed to what AETHER's widgets show (no like, no shuffle).
package app.aether.player

import android.content.Context

/**
 * Everything a home-screen widget needs to know about playback.
 *
 * Saved rather than read live, because a widget outlives the app: it's on screen while the process is gone,
 * after a reboot, and in the moment before a launcher's first update reaches it, none of which a
 * MediaController can serve without starting [PlaybackService] just to find out what to draw. So the
 * service writes here whenever the answer changes, and the widget only ever reads it.
 */
data class WidgetState(
    /** The song's id; null when nothing has played. */
    val id: String?,
    val title: String,
    val artist: String,
    /** Its cover, at [ART_PX]. */
    val artwork: String?,
    /**
     * Whether the key shows pause. The player's playWhenReady, not isPlaying (BitChord's finding): a song
     * still loading spends seconds not playing, and a widget keyed on that would answer a tap by leaving the
     * play mark where it was.
     */
    val playing: Boolean,
    val hasNext: Boolean,
    /** The next song's cover, at [ART_PX]: its picture is drawn while this one plays, ready for the change. */
    val nextArtwork: String? = null,
) {
    val hasTrack: Boolean get() = id != null

    companion object {
        val EMPTY = WidgetState(null, "", "", null, playing = false, hasNext = false)

        /** The cover's size for a widget: as large as a 2×2 or a 4×2's cover is drawn on a phone. */
        const val ART_PX = 720

        fun save(context: Context, state: WidgetState) {
            prefs(context).edit()
                .putString(ID, state.id)
                .putString(TITLE, state.title)
                .putString(ARTIST, state.artist)
                .putString(ARTWORK, state.artwork)
                .putBoolean(PLAYING, state.playing)
                .putBoolean(HAS_NEXT, state.hasNext)
                .putString(NEXT_ARTWORK, state.nextArtwork)
                .apply()
        }

        /** What the service last said. */
        fun load(context: Context): WidgetState {
            val prefs = prefs(context)
            val id = prefs.getString(ID, null) ?: return EMPTY
            return WidgetState(
                id = id,
                title = prefs.getString(TITLE, "").orEmpty(),
                artist = prefs.getString(ARTIST, "").orEmpty(),
                artwork = prefs.getString(ARTWORK, null),
                playing = prefs.getBoolean(PLAYING, false),
                hasNext = prefs.getBoolean(HAS_NEXT, false),
                nextArtwork = prefs.getString(NEXT_ARTWORK, null),
            )
        }

        private fun prefs(context: Context) = context.getSharedPreferences("aether_widget", Context.MODE_PRIVATE)

        private const val ID = "id"
        private const val TITLE = "title"
        private const val ARTIST = "artist"
        private const val ARTWORK = "artwork"
        private const val PLAYING = "playing"
        private const val HAS_NEXT = "has_next"
        private const val NEXT_ARTWORK = "next_artwork"
    }
}
