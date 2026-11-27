// The same-song rule is LastWave-Native's (app/.../data/generate/GeneratedTrack.kt: sameSongAs,
// distinctSongs, normalizeTrackText; GPL-3.0), and so are a playlist saved again with the songs it has
// being no new playlist, and one of the same name with other songs being named "Name 2"
// (data/playlist/PlaylistRepository.kt: save). Merging by where a playlist came from is AETHER's.
package app.aether.core.transfer

import app.aether.core.model.Track

/** When two songs are the same song, for never putting one in a playlist twice. */
object Songs {
    /** Its title and who made it, trimmed, spaces run together, case set aside. */
    fun key(t: Track): String = "${tidy(t.title)}|${tidy(t.artistLine)}"

    /**
     * The same id is the same song outright; otherwise the same title and artists, which catches one
     * song's two uploads ("the same song twice, two ids") as one.
     */
    fun same(a: Track, b: Track): Boolean = a.id == b.id || (a.title.isNotBlank() && b.title.isNotBlank() && key(a) == key(b))

    /** [tracks] with each song once, where it first comes. */
    fun distinct(tracks: List<Track>): List<Track> = missing(emptyList(), tracks)

    /** What of [incoming] [existing] doesn't have yet, each song once, in [incoming]'s order. */
    fun missing(existing: List<Track>, incoming: List<Track>): List<Track> {
        val ids = existing.mapTo(HashSet()) { it.id }
        val keys = existing.filter { it.title.isNotBlank() }.mapTo(HashSet(), ::key)
        val out = ArrayList<Track>()
        for (t in incoming) {
            if (t.id in ids) continue
            val named = t.title.isNotBlank()
            if (named && key(t) in keys) continue
            out += t
            ids += t.id
            if (named) keys += key(t)
        }
        return out
    }

    /** [name], or "Name 2", "Name 3"… when it's taken, case set aside. */
    fun uniqueName(name: String, taken: Collection<String>): String {
        val used = taken.mapTo(HashSet()) { it.trim().lowercase() }
        if (name.trim().lowercase() !in used) return name
        var n = 2
        while ("$name $n".lowercase() in used) n++
        return "$name $n"
    }

    private fun tidy(s: String): String = s.trim().replace(SPACES, " ").lowercase()

    private val SPACES = Regex("""\s+""")
}

/** Where an imported playlist's songs go. */
sealed interface Destination {
    /** Into a playlist that's here already, adding only what it hasn't. */
    data class Into(val playlistId: Long) : Destination

    /** Into a new playlist of this name. */
    data class New(val name: String) : Destination
}

/** A playlist on the phone, as an import sees it. */
data class Existing(val id: Long, val title: String, val tracks: List<Track>)

object Plan {
    /**
     * Where [incoming] goes. Into the playlist it made before, if one is here ([owner], found by its
     * origin): read again, a playlist only gains what it's missing. Else into a playlist of the same
     * name that has every one of its songs already: the same playlist made another way, nothing to
     * add. Else a new playlist, named "Name 2" if the name is taken.
     */
    fun destination(incoming: ImportedPlaylist, owner: Long?, existing: List<Existing>, resolved: List<Track>): Destination {
        if (owner != null && existing.any { it.id == owner }) return Destination.Into(owner)
        val name = incoming.name.trim().ifEmpty { "Imported playlist" }
        val sameName = existing.filter { it.title.trim().equals(name, ignoreCase = true) }
        sameName.firstOrNull { e -> resolved.isNotEmpty() && Songs.missing(e.tracks, resolved).isEmpty() }?.let { return Destination.Into(it.id) }
        return Destination.New(Songs.uniqueName(name, existing.map { it.title }))
    }
}
