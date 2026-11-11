package app.aether.ui.settings

import app.aether.player.CrossfadeRules
import app.aether.ui.common.rememberPageListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aether.core.designsystem.theme.separate
import app.aether.core.designsystem.art.ArtTreatment
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.GlassSegments
import app.aether.core.designsystem.component.Glyph
import app.aether.core.designsystem.component.LightCross
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.component.quietClick
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Space
import app.aether.core.designsystem.theme.Type
import app.aether.core.model.songCount
import app.aether.core.model.songsIncluded
import app.aether.ui.LocalActions
import app.aether.ui.Route
import app.aether.ui.common.LocalContainer
import app.aether.ui.detail.BackLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.aether.ui.common.Colophon
import androidx.compose.ui.platform.LocalContext
import app.aether.ui.account.SignInActivity
import app.aether.core.model.Outcome
import app.aether.core.model.SourceError
import app.aether.sources.addon.AddonSource
import app.aether.ui.common.NameDialog

/**
 * Settings, set like the back pages of a magazine rather than a form: numbered heads in spaced
 * capitals over a hairline; each setting named as everything in AETHER is named (the grotesk's
 * capitals) with its reason beneath in the serif; and every control a key of the tab bar's glass,
 * a choice marked by the bar's lit drop sliding to it.
 */
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val settings = container.settings
    val scope = rememberCoroutineScope()
    val haptics by settings.haptics.collectAsStateWithLifecycle()
    val reduce by settings.reduceMotion.collectAsStateWithLifecycle()
    val efficient by settings.efficient.collectAsStateWithLifecycle()
    val artwork by settings.artwork.collectAsStateWithLifecycle()
    val simpleGlass by settings.simpleGlass.collectAsStateWithLifecycle()
    val timedLyrics by settings.timedLyrics.collectAsStateWithLifecycle()
    val artistArt by settings.artistArt.collectAsStateWithLifecycle()
    val lyricsStyle by settings.lyricsStyle.collectAsStateWithLifecycle()
    val crossfade by settings.crossfade.collectAsStateWithLifecycle()
    val fullScreen by settings.fullScreen.collectAsStateWithLifecycle()
    val syncHistory by settings.syncHistory.collectAsStateWithLifecycle()
    val songVideo by settings.songVideo.collectAsStateWithLifecycle()
    val romanise by settings.romanise.collectAsStateWithLifecycle()
    val sourcesOff by settings.lyricsSourcesOff.collectAsStateWithLifecycle()
    val eqShaping by container.equaliser.shaping.collectAsStateWithLifecycle()
    val updatesAutomatic by container.updates.automatic.collectAsStateWithLifecycle()
    val phoneFolders by container.local.folders.collectAsStateWithLifecycle()
    val phoneChoice by container.local.selection.collectAsStateWithLifecycle()
    val actions = LocalActions.current
    LaunchedEffect(Unit) { if (container.local.hasPermission() && phoneFolders == null) container.local.refresh() }
    val session by container.account.cookies.collectAsStateWithLifecycle()
    val sources by container.sources.added.collectAsStateWithLifecycle()
    val addresses by container.settings.addons.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    val choices by settings.addonChoices.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lyricsFolders by settings.lyricsFolders.collectAsStateWithLifecycle()
    // Android's own folder picker: the folder chosen is read, nothing else, until it's removed here.
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            settings.addLyricsFolder(tree.toString())
            scope.launch { container.localLyrics.forget() }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageListState(), contentPadding = PaddingValues(bottom = 0.dp)) {
        item { BackLine() }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.edge + 12.dp).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt("Settings", Type.Monument, align = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                Txt("How AETHER sounds, how it moves, and what it keeps.", Type.Aside, color = Ink.Grey, align = TextAlign.Center)
            }
        }
        item {
            Section("I", "Account") {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "YouTube Music",
                        if (session != null) "Signed in. Home, your library and mixes are your own."
                        else "Sign in on Google's own page for your Home, library and mixes. AETHER never sees your password.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    if (session != null) {
                        GlassPill("Sign out", small = true, onClick = {
                            container.account.signOut()
                            container.catalog.clear()
                        })
                    } else {
                        GlassPill("Sign in", small = true, lit = true, onClick = { context.startActivity(SignInActivity.intent(context)) })
                    }
                }
                Rule()
                Toggle(
                    "Sync playback history with YouTube Music",
                    "When signed in, songs you play continuously are reported upstream so they appear in your YouTube Music history and Year-End Recap.",
                    syncHistory,
                ) { settings.setSyncHistory(!syncHistory) }
            }
        }
        item {
            Section("II", "Sources") {
                Words(
                    "Your own sources",
                    "Add a source by its address: your own server, or a library that speaks the addon protocol BitChord and LastWave use. For a song from YouTube Music, your sources are asked first for an exact copy (the same recording, the same length); the first that has one plays it, lossless when it is, and YouTube Music plays whatever they don't have. AETHER plays open media only, and ships no sources.",
                    Modifier.padding(vertical = 18.dp),
                )
                val output by container.losslessOutput.state.collectAsStateWithLifecycle()
                addresses.forEach { address ->
                    val connected = sources.firstOrNull { (it as? AddonSource)?.address == address } as? AddonSource
                    AddedSource(
                        address, connected, choices[address].orEmpty(), output,
                        onChoose = { key, value -> settings.setAddonChoice(address, key, value) },
                        onRetry = { container.reconnectAddons() },
                        onOutputChanged = { container.losslessOutput.refresh() },
                    ) {
                        settings.removeAddon(address)
                        container.sources.unregister(AddonSource.idFor(address))
                    }
                }
                addError?.let { Txt(it, Type.Meta, color = Ink.Grey, modifier = Modifier.padding(top = 12.dp)) }
                GlassPill(if (connecting) "Asking…" else "Add a source", icon = Glyph.Plus, onClick = { if (!connecting) adding = true }, modifier = Modifier.padding(vertical = 18.dp))
                Rule()
            }
        }
        item {
            Section("III", "Music on this phone") {
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "Music folders",
                        phoneFolders?.let { f -> songsIncluded(f, phoneChoice).let { n -> "${songCount(n)} from the folders you've chosen. Voice notes and other apps' audio are left out unless you add them." } }
                            ?: "Choose which of the phone's folders AETHER reads. They show on Listen, under On this phone.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill("Choose", small = true, trailingIcon = Glyph.Forward, onClick = { actions.nav.open(Route.MusicFolders()) })
                }
                Rule()
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "Lyrics files",
                        if (lyricsFolders.isEmpty()) "A song's words are read from its own file. To read .lrc files beside your songs (Song.lrc for Song.flac), let AETHER read the folder they're in."
                        else "The .lrc files in ${lyricsFolders.joinToString { folderName(it) }}, for the songs beside them, and the words inside every song's own file.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill(if (lyricsFolders.isEmpty()) "Choose" else "Add", small = true, onClick = { pickFolder.launch(null) })
                }
                lyricsFolders.forEach { tree ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Txt(folderName(tree), Type.Meta, color = Ink.Grey, modifier = Modifier.weight(1f))
                        GlassPill("Remove", small = true, onClick = {
                            runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(tree), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            settings.removeLyricsFolder(tree)
                            scope.launch { container.localLyrics.forget() }
                        })
                    }
                }
                Rule()
            }
        }
        item {
            Section("IV", "Sound") {
                Toggle("Data saver", "Lower-bitrate streams for metered networks. Off plays the best the source has.", efficient) { settings.setEfficient(!efficient) }
                Choice(
                    title = "Crossfade",
                    detail = "The end of one song and the start of the next play together for this many seconds, one going down as the other comes up. Never between an album's songs that run into each other, nor while a DAC plays bit-perfect.",
                    options = CrossfadeRules.Choices.map { if (it == 0) "Off" else "$it" },
                    selected = CrossfadeRules.Choices.indexOf(crossfade).coerceAtLeast(0),
                    onSelect = { settings.setCrossfade(CrossfadeRules.Choices[it]) },
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "Equaliser",
                        eqShaping?.let { listOfNotNull(it.group, it.name).joinToString(" ") } ?: "Off. Tunings, corrections measured for your headphones, or a profile of your own.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill("Open", small = true, trailingIcon = Glyph.Forward, onClick = { actions.nav.open(Route.Equaliser) })
                }
                Rule()
            }
        }
        item {
            Section("V", "Feel") {
                Toggle("Haptics", "A light touch you can feel on the actions that matter.", haptics) { settings.setHaptics(!haptics) }
                Toggle("Reduce motion", "No stretch, ripples or drifting. Also follows your phone's setting.", reduce) { settings.setReduceMotion(!reduce) }
                Toggle("Full screen", "Android's bars hidden, the clock above and the keys below, so the page has the whole screen. A swipe from the edge brings them for a moment.", fullScreen) { settings.setFullScreen(!fullScreen) }
                Choice(
                    title = "Glass",
                    detail = "Simple is PODIUM's glass: frosted, lit at the rim, the screen's edges fading to black. Liquid bends light at every edge and frosts the screen's edges live; it asks much more of the phone.",
                    options = listOf("Simple", "Liquid"),
                    selected = if (simpleGlass) 0 else 1,
                    onSelect = { settings.setSimpleGlass(it == 0) },
                )
            }
        }
        item {
            Section("VI", "Now Playing") {
                Toggle(
                    "Timed lyrics",
                    "Words in time with the song, word by word where a library has them, from the lyrics libraries below. They're asked together and the best answer is kept; asking sends a song's title, artist, album and length, nothing else. Off, the music service's own words are shown.",
                    timedLyrics,
                ) { settings.setTimedLyrics(!timedLyrics) }
                if (timedLyrics) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                        Txt("Libraries", Type.Label, color = Ink.Grey, upper = true)
                        Spacer(Modifier.height(4.dp))
                        container.lyricsLibraries.forEach { library ->
                            val on = library.id !in sourcesOff
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Words(library.attribution.name, LibraryNotes[library.id], Modifier.weight(1f))
                                Spacer(Modifier.width(16.dp))
                                GlassSegments(listOf("Off", "On"), if (on) 1 else 0, { if ((it == 1) != on) settings.setLyricsSource(library.id, !on) })
                            }
                        }
                    }
                    Rule()
                }
                Choice(
                    title = "How lyrics are drawn",
                    detail = "Verse sets the song as a page that's sung, each word lighting as it's sung; touch a line to go to it. Constellations sets each word as a star in a night sky. Line writes the song by hand, as one unbroken line.",
                    options = listOf("Verse", "Constellations", "Line"),
                    selected = when (lyricsStyle) { 2 -> 0; 1 -> 2; else -> 1 },
                    onSelect = { settings.setLyricsStyle(when (it) { 0 -> 2; 2 -> 1; else -> 0 }) },
                )
                Toggle(
                    "Words in Latin letters",
                    "Lyrics in another script are given in Latin letters too: Korean as it's said, Chinese in pinyin, Japanese where it's in kana, and the rest. Worked out on the phone; nothing is sent. The Latin key on the lyrics turns it off and on.",
                    romanise,
                ) { settings.setRomanise(!romanise) }
                Toggle(
                    "Song video",
                    "A song's cover gives way to its short film, in colour, for the albums Apple Music has one for, once it has loaded at its best; a music video plays as itself, in step with the music. The video key on Now Playing switches it too. Not loaded on a slow connection or the data saver. Asking Apple sends the song's title and artist; nothing else. A song's film needs Artist and album art.",
                    songVideo,
                ) { settings.setSongVideo(!songVideo) }
                Choice(
                    title = "The cover",
                    detail = "How the print is shown on Now Playing. A song that has a music video plays it in the cover's place when you press the video key there.",
                    options = listOf("Original", "Mono", "Halftone", "ASCII", "Dither"),
                    selected = artwork.mod(ArtTreatment.Cycle.size),
                    onSelect = { settings.setArtwork(it) },
                )
            }
        }
        item {
            Section("VII", "Artists and albums") {
                Toggle(
                    "Artist and album art",
                    "A portrait, the artist's own lettering and a short film at the head of their page, and an album's moving cover at the head of its, from Apple Music's public pages, for those who have them. It also asks what kind of music your most played artists make, for your Profile. Asking sends the artist's name and the album's title; nothing else. Off, or on the data saver for the films, a page uses what the source gives.",
                    artistArt,
                ) { settings.setArtistArt(!artistArt) }
            }
        }
        item {
            Section("VIII", "Your data") {
                Erase("Listening history", "Everything you've played, gone from this phone.") { scope.launch { container.library.clearHistory() } }
                Erase("Recent searches", "What you've looked for on Discover.") { scope.launch { container.library.clearSearches() } }
            }
        }
        item {
            Section("IX", "Software update") {
                if (container.updates.looksByItself) {
                    Toggle(
                        "Look for updates",
                        "When AETHER opens, it asks GitHub, at most every few hours, whether there's a newer release, and offers it. Nothing about you goes with the question. The update is checked against the release and signed like your AETHER before Android is asked to install it.",
                        updatesAutomatic,
                    ) { container.updates.setAutomatic(!updatesAutomatic) }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Words(
                        "AETHER ${container.updates.version}",
                        if (container.updates.looksByItself) "The version on this phone." else "A test build: it looks for updates only when asked, and a release installs from GitHub.",
                        Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(18.dp))
                    GlassPill("Check now", small = true, onClick = { container.updates.checkNow() })
                }
                Rule()
            }
        }
        item {
            Section("X", "Privacy") {
                Paragraph("AETHER has no analytics and no accounts. Your likes, playlists and history stay on this phone. The only requests it makes are the ones that load pages and play music, and, if you leave them on, the ones for timed lyrics, song video, artist and album art, and a newer AETHER. The music on the phone is read on the phone.")
            }
        }
        item {
            Section("XI", "About") {
                Spacer(Modifier.height(16.dp))
                Txt("AETHER ${container.updates.version}", Type.TitleSmall)
                // What went wrong last time AETHER closed by itself: kept on the phone, sent only if you send it.
                var crash by remember { mutableStateOf(app.aether.CrashNote.read(context)) }
                crash?.let { note ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Words("AETHER closed by itself", "What went wrong is kept on this phone. Share it with whoever makes AETHER, if you like; nothing is sent otherwise.", Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassPill("Share", small = true, onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, note)
                                context.startActivity(android.content.Intent.createChooser(send, "Share what went wrong"))
                            })
                            GlassPill("Clear", small = true, onClick = { app.aether.CrashNote.clear(context); crash = null })
                        }
                    }
                }
                Paragraph(
                    "Free software under the GNU GPL v3. Built on PODIUM's, LastWave's, BitChord's and NewPipe's work, Media3, Coil and Kyant's Backdrop; set in Pinyon Script, EB Garamond, Montserrat and JetBrains Mono (SIL Open Font License).",
                    top = 6.dp,
                )
            }
        }
        item(key = "colophon") { Colophon() }
    }
    if (adding) NameDialog("Source address", "https://", onDone = { typed ->
        adding = false
        val address = typed.trim()
        if (address in addresses) {
            addError = "That source is already in your list."
            return@NameDialog
        }
        connecting = true
        scope.launch {
            when (val r = container.connectAddon(address)) {
                is Outcome.Ok -> {
                    container.sources.register(r.value)
                    settings.addAddon(address)
                    addError = null
                }
                is Outcome.Err -> addError = when (val e = r.error) {
                    is SourceError.NotPlayable -> e.reason ?: "That address didn't answer as a source."
                    SourceError.Network, SourceError.Offline -> "That address didn't answer. Check it, and your connection, and try again."
                    is SourceError.Server -> "That address answered, but not as a source${e.code?.let { " (it said $it)" }.orEmpty()}."
                    else -> "That address didn't answer as a source."
                }
            }
            connecting = false
        }
    }, onDismiss = { adding = false })
}

/**
 * A source the listener added: its name, where it is (the host alone: the rest of an address can
 * carry their token), how it's doing in its own words, and each setting it declares as a choice on the
 * bar of glass. One that asks for a lossless output says whether one is connected, and offers the
 * Nearby devices permission when that's all that stands between Bluetooth headphones and an answer.
 */
@Composable
private fun AddedSource(
    address: String,
    source: AddonSource?,
    chosen: Map<String, String>,
    output: app.aether.output.LosslessOutput.State,
    onChoose: (String, String) -> Unit,
    onRetry: () -> Unit,
    onOutputChanged: () -> Unit,
    onRemove: () -> Unit,
) {
    val host = remember(address) { AddonSource.base(address)?.host ?: address }
    // Read so the row follows the source as it's asked: an error, a rest, an answer.
    val changes = (source?.changes ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsStateWithLifecycle()
    changes.value
    val wantsLossless = source?.manifest?.requiresLosslessOutput == true
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { onOutputChanged() }
    val associate = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { onOutputChanged() }
    var companionTrouble by remember { mutableStateOf<String?>(null) }
    val nearbyGranted = android.os.Build.VERSION.SDK_INT < 31 ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val state = when {
            source == null -> "Not reachable just now, at $host. Asked again when the connection's back."
            wantsLossless && !output.capable -> "$host. It asks to be used only with an output that carries lossless audio: wired or USB headphones, HDMI, or Bluetooth on LDAC, LHDC or aptX Lossless. " +
                when {
                    output.bluetoothCodec != null -> "Your headphones are on ${output.bluetoothCodec}, so it waits."
                    output.bluetoothCodecUnknown && !nearbyGranted -> "Android tells AETHER your headphones' codec only with Nearby devices."
                    output.needsCompanion -> "Android names your headphones' codec only to an app it knows them by: allow AETHER once, and it can tell when they play over LDAC."
                    output.bluetoothCodecUnknown -> "Android hasn't named your headphones' codec yet: it does when they connect."
                    else -> "None is connected, so it waits."
                } + (companionTrouble?.let { " ($it)" }.orEmpty())
            source.trouble != null -> "$host. Resting: ${source.trouble}"
            source.lastError != null -> "$host. Its last answer: \u201c${source.lastError}\u201d"
            wantsLossless -> "$host. Lossless output connected${output.bluetoothCodec?.let { " ($it)" }.orEmpty()}: asked first for exact copies of songs from YouTube Music."
            else -> "$host. Asked first for exact copies of songs from YouTube Music."
        }
        Words(source?.name ?: "A source", state, Modifier.weight(1f))
        Spacer(Modifier.width(18.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (source == null) {
                GlassPill("Try again", small = true, onClick = onRetry)
                Spacer(Modifier.height(10.dp))
            }
            if (wantsLossless && output.bluetoothCodecUnknown && !nearbyGranted) {
                GlassPill("Nearby devices", small = true, lit = true, onClick = { ask.launch(android.Manifest.permission.BLUETOOTH_CONNECT) })
                Spacer(Modifier.height(10.dp))
            } else if (wantsLossless && output.needsCompanion) {
                GlassPill("Allow headphones", small = true, lit = true, onClick = {
                    val address = app.aether.output.LosslessOutput.headphonesAddress
                    if (address == null) {
                        companionTrouble = "Android didn't say which headphones are connected."
                    } else {
                        app.aether.output.CodecCompanion.ask(
                            context, address,
                            onPending = { sender -> associate.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) },
                            onDone = { companionTrouble = null; onOutputChanged() },
                            onFailed = { why -> companionTrouble = why },
                        )
                    }
                })
                Spacer(Modifier.height(10.dp))
            }
            GlassPill("Remove", small = true, onClick = onRemove)
        }
    }
    source?.manifest?.settings?.filter { it.type != app.aether.sources.addon.AddonSetting.Type.TEXT }?.forEach { setting ->
        val options = when (setting.type) {
            app.aether.sources.addon.AddonSetting.Type.SELECT -> setting.options
            else -> listOf(app.aether.sources.addon.AddonSetting.Option("Off", "false"), app.aether.sources.addon.AddonSetting.Option("On", "true"))
        }.take(4)
        if (options.size < 2) return@forEach
        // The quality a source is asked for is its best unless the listener chose another.
        val value = chosen[setting.key]?.takeIf { v -> options.any { it.value == v } }
            ?: (if (setting.key == app.aether.sources.addon.AddonManifest.QUALITY) options.first().value else setting.accept(null))
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Txt(setting.label, Type.Label, color = Ink.Grey, upper = true)
            Spacer(Modifier.height(10.dp))
            GlassSegments(options.map { it.label }, options.indexOfFirst { it.value == value }.coerceAtLeast(0), { i -> onChoose(setting.key, options[i].value) })
        }
    }
    Rule()
}

/** A folder the listener chose, by its path below the storage's root ("Music/Lyrics"). */
private fun folderName(tree: String): String = runCatching {
    val id = android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(tree))
    id.substringAfter(':').ifBlank { "the phone's storage" }
}.getOrDefault("a folder")

/** What each lyrics library is, in a line. */
private val LibraryNotes = mapOf(
    "lrcred" to "Apple Music's timings, syllable by syllable, found by the recording.",
    "bini" to "The same timings, matched on the recording itself.",
    "betterlyrics" to "Apple Music's timings, word by word, from the YouTube Music extension's library.",
    "lyricsplus" to "Syllable by syllable from several catalogues, on community mirrors.",
    "unison" to "Lyrics written and voted on by listeners.",
    "lrclib" to "An open library of lyrics, timed by the line.",
    "kugou" to "Timed by the line, and strong outside the English-speaking catalogue.",
)

/** A numbered head: the numeral in italic, the name in spaced capitals, a hairline beneath. */
@Composable
private fun Section(number: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.separate().fillMaxWidth().padding(horizontal = Space.edge).padding(top = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(number, Type.Aside.copy(fontSize = 15.sp, lineHeight = 18.sp), color = Ink.Dim)
            Spacer(Modifier.width(12.dp))
            Txt(title, Type.Label, color = Ink.Grey, upper = true)
        }
        Spacer(Modifier.height(12.dp))
        Rule()
        content()
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ink.Hairline))
}

@Composable
private fun Words(title: String, detail: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Txt(title, Type.TitleSmall)
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Txt(detail, Type.Meta, color = Ink.Grey)
        }
    }
}

/** On or off: the words, and at the right a switch of the tab bar's glass whose lit drop slides to the choice. */
@Composable
private fun Toggle(title: String, detail: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) { role = Role.Switch; stateDescription = if (on) "On" else "Off" }
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassSegments(listOf("Off", "On"), if (on) 1 else 0, { if ((it == 1) != on) onToggle() })
    }
    Rule()
}

/** One of a few: the words, then the choices on one bar of glass, the chosen one under the lit drop. */
@Composable
private fun Choice(title: String, detail: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        Words(title, detail)
        Spacer(Modifier.height(16.dp))
        GlassSegments(options, selected, onSelect)
    }
    Rule()
}

/**
 * Something you can wipe. Nothing goes on the first touch: the line asks you to touch again, for a
 * few seconds, and only that second touch clears it.
 */
@Composable
private fun Erase(title: String, detail: String, onErase: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(3500); armed = false } }
    LaunchedEffect(done) { if (done) { delay(2200); done = false } }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Words(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        GlassPill(
            when { done -> "Cleared"; armed -> "Touch again"; else -> "Clear" },
            small = true,
            lit = armed,
            icon = if (done) Glyph.Check else null,
            onClick = { if (armed) { onErase(); armed = false; done = true } else if (!done) armed = true },
        )
    }
    Rule()
}

@Composable
private fun Paragraph(text: String, top: androidx.compose.ui.unit.Dp = 16.dp) {
    Txt(text, Type.Body.copy(lineHeight = 21.sp), color = Ink.Grey, modifier = Modifier.padding(top = top, bottom = 4.dp))
}
