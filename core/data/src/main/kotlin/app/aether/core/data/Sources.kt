package app.aether.core.data

import android.content.Context
import android.content.SharedPreferences
import app.aether.core.model.MediaId
import app.aether.core.model.MusicSource
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.core.model.Stream
import app.aether.core.model.StreamPurpose
import app.aether.core.model.Track
import app.aether.core.model.CopySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The sources AETHER knows, routed by id: `ytm:…` goes to YouTube Music, `local:…` to the phone.
 * [primary] fills Home, Explore and search. Screens never hold a source themselves.
 *
 * For listening, the listener's own sources ([CopySource]: the addons they added) are asked first for
 * an exact copy of a song from another source (architecture.md §4, PODIUM's preferred sources): all of
 * them together within [COPY_BUDGET_MS], in the listener's order; the first copy found plays in the
 * song's place, and stays chosen for as long as the song plays, so its every read is the same file.
 * When none has it, the song's own source plays it as ever. When they're slow, the song begins from its
 * own source and the search goes on: a copy that turns up is offered to the player ([lateCopies]), which
 * carries the song over to it mid-song (PODIUM's D-92), and is the song's copy from its next play. A
 * song kept on the phone is always its own source's audio.
 */
class SourceManager(sources: List<MusicSource>, primaryId: String, private val now: () -> Long = System::currentTimeMillis) {
    private val byId = java.util.concurrent.ConcurrentHashMap(sources.associateBy { it.id })
    val primary: MusicSource = byId.getValue(primaryId)
    private val _all = MutableStateFlow(sources)

    /** Every source, the listener's added ones included, as they come and go. */
    val added: StateFlow<List<MusicSource>> = _all.asStateFlow()
    val all: List<MusicSource> get() = _all.value

    /** The copy each song is playing from, by the song's id, once one has been chosen. */
    private val copies = java.util.concurrent.ConcurrentHashMap<MediaId, Stream>()

    /** Songs none of the listener's sources had, and when they may be asked again. */
    private val misses = java.util.concurrent.ConcurrentHashMap<MediaId, Long>()

    /**
     * Copies that turned up after their song had begun from its own source. Kept apart from [copies]:
     * the song is still reading its own source's file, and every read of a play must be the same file.
     */
    private val late = java.util.concurrent.ConcurrentHashMap<MediaId, Stream>()

    /** Searches still going on past the budget, so a song asked for twice is searched for once. */
    private val searching = java.util.concurrent.ConcurrentHashMap<MediaId, Deferred<Stream?>>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lateCopies = MutableSharedFlow<MediaId>(extraBufferCapacity = 16)

    /** A song's id, each time a copy of it turns up after the song had already begun from its own source. */
    val lateCopies: SharedFlow<MediaId> = _lateCopies.asSharedFlow()

    private val _served = MutableStateFlow<Map<MediaId, String>>(emptyMap())

    /** Which of the listener's own sources a song is playing from, when it isn't the song's own source. */
    val served: StateFlow<Map<MediaId, String>> = _served.asStateFlow()

    /** A source the listener added (by its address) joins the others. */
    fun register(source: MusicSource) {
        byId[source.id] = source
        _all.value = _all.value.filter { it.id != source.id } + source
        // Something new to ask: what no one had may be there now.
        misses.clear()
    }

    fun unregister(id: String) {
        byId.remove(id)
        _all.value = _all.value.filter { it.id != id }
    }

    fun of(id: MediaId): MusicSource? = byId[id.source]
    fun of(sourceId: String): MusicSource? = byId[sourceId]

    /**
     * The audio for [id]. For [StreamPurpose.LISTEN] with the whole [track] known, an exact copy from
     * the listener's own sources is looked for first (not on the data saver); without the track, the
     * copy already chosen for it, if any. Keeping is always the song's own source.
     */
    suspend fun stream(id: MediaId, efficient: Boolean, purpose: StreamPurpose = StreamPurpose.LISTEN, track: Track? = null): Outcome<Stream> {
        val own = of(id) ?: return Outcome.Err(SourceError.NotFound("source"))
        if (purpose == StreamPurpose.LISTEN && own !is CopySource) {
            copies[id]?.let { chosen ->
                // Read again mid-song, it's the same copy; asked afresh for a new play, an expired one is let go.
                if (track == null || (chosen.expiresAtMs ?: Long.MAX_VALUE) > now() + 60_000) return Outcome.Ok(chosen)
                forgetCopy(id)
            }
            if (track != null && !efficient) copyOf(track)?.let { return Outcome.Ok(it) }
        }
        return own.stream(id, efficient)
    }

    private suspend fun copyOf(track: Track): Stream? {
        val askable = all.filterIsInstance<CopySource>().filter { it.ready }
        if (askable.isEmpty()) return null
        // One that turned up too late for an earlier play is this play's from its start.
        late.remove(track.id)?.let { found ->
            if ((found.expiresAtMs ?: Long.MAX_VALUE) > now() + 60_000) {
                choose(track.id, found)
                return found
            }
        }
        misses[track.id]?.let { until -> if (now() < until) return null else misses.remove(track.id) }
        val search = searching.computeIfAbsent(track.id) { background.async { find(track, askable) } }
        val found = withTimeoutOrNull(COPY_BUDGET_MS) { search.await() }
        if (found != null) {
            searching.remove(track.id)
            choose(track.id, found)
            return found
        }
        if (search.isCompleted) {
            // Only "none of them has it" is remembered.
            searching.remove(track.id)
            misses[track.id] = now() + MISS_MS
            return null
        }
        // Still being asked: the song begins from its own source, and a copy that turns up is offered
        // to the player to go over to.
        background.launch {
            val copy = runCatching { search.await() }.getOrNull()
            searching.remove(track.id)
            if (copy == null) {
                misses[track.id] = now() + MISS_MS
            } else {
                late[track.id] = copy
                _lateCopies.emit(track.id)
            }
        }
        return null
    }

    /** The listener's sources in their order, until one has the very recording. */
    private suspend fun find(track: Track, askable: List<CopySource>): Stream? {
        for (source in askable) {
            val copy = runCatching { source.exactCopy(track) }.getOrNull()
            if (copy != null) return copy.copy(servedBy = copy.servedBy ?: source.name)
        }
        return null
    }

    private fun choose(id: MediaId, copy: Stream) {
        copies[id] = copy
        _served.value = _served.value + (id to copy.servedBy.orEmpty())
    }

    /** The copy that turned up late for [id], if one did and it still plays, to carry the song over to. */
    fun lateCopy(id: MediaId): Stream? = late[id]?.takeIf { (it.expiresAtMs ?: Long.MAX_VALUE) > now() + 60_000 }

    /** [id] is heard from its late copy now (carried over to it): Now Playing names where it came from. */
    fun hearing(id: MediaId) {
        late[id]?.let { _served.value = _served.value + (id to it.servedBy.orEmpty()) }
    }

    /** [id] is heard from its own source again, unless its copy has been chosen for it. */
    fun notHearing(id: MediaId) {
        if (!copies.containsKey(id)) _served.value = _served.value - id
    }

    /** The player goes over to [id]'s late copy for good: from now on, read again, it's the copy. */
    fun adopt(id: MediaId): Boolean {
        val copy = late.remove(id) ?: return false
        choose(id, copy)
        return true
    }

    /** A late copy that wouldn't play isn't offered again for a while. */
    fun dropLate(id: MediaId) {
        if (late.remove(id) != null) misses[id] = now() + REFUSED_MS
        notHearing(id)
    }

    private fun forgetCopy(id: MediaId) {
        copies.remove(id)
        _served.value = _served.value - id
    }

    /**
     * Forget a stream the player was refused. A copy that was refused isn't asked for again for a
     * while: the song's own source plays it.
     */
    fun invalidate(id: MediaId) {
        if (copies.containsKey(id)) {
            forgetCopy(id)
            misses[id] = now() + REFUSED_MS
            return
        }
        of(id)?.invalidate(id)
    }

    companion object {
        /** All of the listener's sources together may take this long before the song's own source plays it. */
        const val COPY_BUDGET_MS = 5_000L

        /** A song none of them had is looked for again after this. */
        const val MISS_MS = 30 * 60_000L

        /** A copy that stopped being served isn't asked for again for this long. */
        const val REFUSED_MS = 10 * 60_000L
    }
}

/** The listener's choices, kept on the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("aether", Context.MODE_PRIVATE)

    private val _songOrders = MutableStateFlow(
        prefs.getStringSet(SONG_ORDERS, emptySet()).orEmpty().mapNotNull { e -> e.substringBeforeLast('=', "").takeIf { it.isNotEmpty() }?.let { it to e.substringAfterLast('=') } }.toMap(),
    )
    /** How each playlist's songs stand, by the playlist's key; a playlist not here is in its own order. */
    val songOrders: StateFlow<Map<String, String>> = _songOrders.asStateFlow()
    fun setSongOrder(playlist: String, order: String?) {
        val next = if (order == null) _songOrders.value - playlist else _songOrders.value + (playlist to order)
        prefs.edit().putStringSet(SONG_ORDERS, next.map { (k, v) -> "$k=$v" }.toSet()).apply()
        _songOrders.value = next
    }

    private fun flag(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _onboarded = flag(ONBOARDED, false)
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    private val _haptics = flag(HAPTICS, true)
    val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _reduceMotion = flag(REDUCE_MOTION, false)
    val reduceMotion: StateFlow<Boolean> = _reduceMotion.asStateFlow()

    /** Lower-bitrate streams, for metered networks. */
    private val _efficient = flag(EFFICIENT, false)
    val efficient: StateFlow<Boolean> = _efficient.asStateFlow()

    /**
     * Timed lyrics are asked of LRCLIB (which is sent a song's title, artist and length, nothing
     * else). On unless the listener turns it off, on the welcome or in Settings.
     */
    private val _timedLyrics = flag(TIMED_LYRICS, true)
    val timedLyrics: StateFlow<Boolean> = _timedLyrics.asStateFlow()

    private val _lyricsSourcesOff = MutableStateFlow(prefs.getStringSet(LYRICS_SOURCES_OFF, emptySet()).orEmpty().toSet())

    /** The lyrics libraries the listener has turned off, by their ids. */
    val lyricsSourcesOff: StateFlow<Set<String>> = _lyricsSourcesOff.asStateFlow()

    fun setLyricsSource(id: String, on: Boolean) {
        val next = if (on) _lyricsSourcesOff.value - id else _lyricsSourcesOff.value + id
        prefs.edit().putStringSet(LYRICS_SOURCES_OFF, next).apply()
        _lyricsSourcesOff.value = next
    }

    private val _lyricsFolders = MutableStateFlow(prefs.getStringSet(LYRICS_FOLDERS, emptySet()).orEmpty().toList().sorted())

    /** Folders the listener let AETHER read `.lrc` files from (Android's document trees), for the songs on the phone. */
    val lyricsFolders: StateFlow<List<String>> = _lyricsFolders.asStateFlow()

    fun addLyricsFolder(tree: String) = setLyricsFolders(_lyricsFolders.value + tree)
    fun removeLyricsFolder(tree: String) = setLyricsFolders(_lyricsFolders.value - tree)
    private fun setLyricsFolders(list: List<String>) {
        val distinct = list.distinct().sorted()
        prefs.edit().putStringSet(LYRICS_FOLDERS, distinct.toSet()).apply()
        _lyricsFolders.value = distinct
    }

    private val _romanise = flag(ROMANISE, true)

    /**
     * Words in another script are given in Latin letters as well (Korean, Chinese, Japanese kana,
     * Cyrillic, Greek, Arabic, Hebrew, the Indian scripts, Thai), worked out on the phone.
     */
    val romanise: StateFlow<Boolean> = _romanise.asStateFlow()
    fun setRomanise(on: Boolean) = set(ROMANISE, on, _romanise)

    private val _artistArt = flag(ARTIST_ART, true)

    /** An artist's portrait, lettered name and film, asked of Apple Music by the artist's name. */
    val artistArt: StateFlow<Boolean> = _artistArt.asStateFlow()

    private val _addons = MutableStateFlow(prefs.getStringSet(ADDONS, emptySet()).orEmpty().toList().sorted())

    /** The addresses of the sources the listener has added. */
    val addons: StateFlow<List<String>> = _addons.asStateFlow()

    fun addAddon(address: String) = setAddons(_addons.value + address.trim())
    fun removeAddon(address: String) {
        setAddons(_addons.value - address)
        setAddonChoices(_addonChoices.value - address)
    }

    private val _addonChoices = MutableStateFlow(readChoices())

    /** What the listener chose for each added source's settings (its quality, say), by its address. */
    val addonChoices: StateFlow<Map<String, Map<String, String>>> = _addonChoices.asStateFlow()

    fun addonChoices(address: String): Map<String, String> = _addonChoices.value[address].orEmpty()

    fun setAddonChoice(address: String, key: String, value: String) =
        setAddonChoices(_addonChoices.value + (address to (addonChoices(address) + (key to value))))

    private fun setAddonChoices(all: Map<String, Map<String, String>>) {
        val json = org.json.JSONObject()
        all.forEach { (address, values) -> json.put(address, org.json.JSONObject(values)) }
        prefs.edit().putString(ADDON_CHOICES, json.toString()).apply()
        _addonChoices.value = all
    }

    private fun readChoices(): Map<String, Map<String, String>> = runCatching {
        val json = org.json.JSONObject(prefs.getString(ADDON_CHOICES, null) ?: return emptyMap())
        json.keys().asSequence().associateWith { address ->
            val values = json.getJSONObject(address)
            values.keys().asSequence().associateWith { values.getString(it) }
        }
    }.getOrDefault(emptyMap())

    private fun setAddons(list: List<String>) {
        val distinct = list.distinct().sorted()
        prefs.edit().putStringSet(ADDONS, distinct.toSet()).apply()
        _addons.value = distinct
    }

    /** PODIUM's lighter glass instead of the full liquid glass. On unless the listener asks for the full effect. */
    private val _simpleGlass = flag(SIMPLE_GLASS, true)
    val simpleGlass: StateFlow<Boolean> = _simpleGlass.asStateFlow()

    // The cycle used to open on black and white, with the original second; the rest kept their places.
    private val _artwork = MutableStateFlow(prefs.getInt(ARTWORK_CYCLE, prefs.getInt(ARTWORK, 0).let { if (it <= 1) 0 else it }))
    /** The treatment Now Playing opens with (an index into the cycle). */
    val artwork: StateFlow<Int> = _artwork.asStateFlow()

    private val _lyricsStyle = MutableStateFlow(prefs.getInt(LYRICS_STYLE, 0))
    /** How timed lyrics are drawn: 0 as constellations, 1 as one continuous line. */
    val lyricsStyle: StateFlow<Int> = _lyricsStyle.asStateFlow()

    private val _keptCollections = MutableStateFlow(prefs.getStringSet(KEPT_COLLECTIONS, emptySet()).orEmpty().toSet())
    /** The playlists and albums whose new songs are kept as well. */
    val keptCollections: StateFlow<Set<String>> = _keptCollections.asStateFlow()

    private val _profileName = MutableStateFlow(prefs.getString(PROFILE_NAME, null))
    /** The name the listener gave their Profile, if they did. */
    val profileName: StateFlow<String?> = _profileName.asStateFlow()

    private val _songVideo = flag(SONG_VIDEO, true)
    /** A song's cover plays its short film on Now Playing, where it has one. */
    val songVideo: StateFlow<Boolean> = _songVideo.asStateFlow()

    private val _musicVideo = flag(MUSIC_VIDEO, false)
    /**
     * A video (an official music video, or one someone uploaded) plays as video on Now Playing, its
     * pictures in step with its sound, instead of its cover. Off until the listener asks for it.
     */
    val musicVideo: StateFlow<Boolean> = _musicVideo.asStateFlow()
    fun setMusicVideo(on: Boolean) = set(MUSIC_VIDEO, on, _musicVideo)

    private val _fullScreen = flag(FULL_SCREEN, false)
    /** Android's bars hidden (PODIUM's D-85); a swipe from the edge brings them for a moment. */
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()
    fun setFullScreen(on: Boolean) = set(FULL_SCREEN, on, _fullScreen)

    private val _syncHistory = flag(SYNC_HISTORY, false)
    /** Opt-in: report playback progress upstream so tracks appear in YouTube Music history and Recap. */
    val syncHistory: StateFlow<Boolean> = _syncHistory.asStateFlow()
    fun setSyncHistory(on: Boolean) = set(SYNC_HISTORY, on, _syncHistory)

    private val _crossfade = MutableStateFlow(prefs.getInt(CROSSFADE, 0))
    /** Seconds the end of one song and the start of the next overlap; 0 is off. */
    val crossfade: StateFlow<Int> = _crossfade.asStateFlow()
    fun setCrossfade(seconds: Int) { prefs.edit().putInt(CROSSFADE, seconds).apply(); _crossfade.value = seconds }

    private val _chartsCountry = MutableStateFlow(prefs.getString(CHARTS_COUNTRY, null))
    val chartsCountry: StateFlow<String?> = _chartsCountry.asStateFlow()

    /** Permissions the listener has already answered: never asked again. */
    fun asked(permission: String): Boolean = prefs.getBoolean("asked:$permission", false)
    fun markAsked(permission: String) = prefs.edit().putBoolean("asked:$permission", true).apply()

    fun setOnboarded() = set(ONBOARDED, true, _onboarded)
    fun setHaptics(on: Boolean) = set(HAPTICS, on, _haptics)
    fun setReduceMotion(on: Boolean) = set(REDUCE_MOTION, on, _reduceMotion)
    fun setEfficient(on: Boolean) = set(EFFICIENT, on, _efficient)
    fun setSimpleGlass(on: Boolean) = set(SIMPLE_GLASS, on, _simpleGlass)
    fun setTimedLyrics(on: Boolean) = set(TIMED_LYRICS, on, _timedLyrics)
    fun setArtistArt(on: Boolean) = set(ARTIST_ART, on, _artistArt)
    fun setArtwork(index: Int) { prefs.edit().putInt(ARTWORK_CYCLE, index).apply(); _artwork.value = index }
    fun setLyricsStyle(index: Int) { prefs.edit().putInt(LYRICS_STYLE, index).apply(); _lyricsStyle.value = index }
    fun setCollectionKept(key: String, on: Boolean) {
        val next = if (on) _keptCollections.value + key else _keptCollections.value - key
        prefs.edit().putStringSet(KEPT_COLLECTIONS, next).apply()
        _keptCollections.value = next
    }
    fun setProfileName(name: String) {
        val trimmed = name.trim().ifEmpty { null }
        prefs.edit().putString(PROFILE_NAME, trimmed).apply()
        _profileName.value = trimmed
    }
    fun setSongVideo(on: Boolean) = set(SONG_VIDEO, on, _songVideo)

    /** Whether the next card is silver: each takes the other finish from the one before. */
    fun nextCardSilver(): Boolean {
        val next = !prefs.getBoolean(CARD_SILVER, true)
        prefs.edit().putBoolean(CARD_SILVER, next).apply()
        return next
    }

    fun setChartsCountry(code: String?) { prefs.edit().putString(CHARTS_COUNTRY, code).apply(); _chartsCountry.value = code }

    private fun set(key: String, value: Boolean, state: MutableStateFlow<Boolean>) {
        prefs.edit().putBoolean(key, value).apply()
        state.value = value
    }

    private companion object {
        const val ONBOARDED = "onboarded"
        const val HAPTICS = "haptics"
        const val REDUCE_MOTION = "reduce_motion"
        const val EFFICIENT = "efficient"
        const val SIMPLE_GLASS = "simple_glass"
        const val TIMED_LYRICS = "timed_lyrics"
        const val ARTIST_ART = "artist_art"
        const val ADDONS = "addons"
        const val ADDON_CHOICES = "addon_choices"
        const val LYRICS_SOURCES_OFF = "lyrics_sources_off"
        const val ROMANISE = "romanise"
        const val LYRICS_FOLDERS = "lyrics_folders"
        const val ARTWORK = "artwork_treatment"
        const val ARTWORK_CYCLE = "artwork_cycle"
        const val LYRICS_STYLE = "lyrics_style"
        const val SONG_VIDEO = "song_video"
        const val MUSIC_VIDEO = "music_video"
        const val CARD_SILVER = "card_silver"
        const val PROFILE_NAME = "profile_name"
        const val KEPT_COLLECTIONS = "kept_collections"
        const val CHARTS_COUNTRY = "charts_country"
        const val CROSSFADE = "crossfade"
        const val SONG_ORDERS = "song_orders"
        const val FULL_SCREEN = "full_screen"
        const val SYNC_HISTORY = "sync_history"
    }
}
