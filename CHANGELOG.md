# Changelog

All notable changes to AETHER will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [3.1.0] - 2026-11-25

### Fixed
- A song just kept went to the foot of Kept, out of sight in a long list: Kept stands newest first now, the song kept a moment ago at the top.
- Keeping songs is fast. YouTube sends a song asked for in one long request at about the speed it plays (BitChord measured 15 kB/s), so keeping one took minutes; songs now come in short ranges, each at the connection's full speed, as BitChord's do. The song playing buffers ahead faster for it, and so do Automix's look at the next song and a song sent to a TV.
- 16-bit FLAC played as loud, fast static on Samsung phones (#15, #16). Samsung's own FLAC decoder garbles a file when asked for float, which every song was; FLAC now goes to Android's own decoder (BitChord's fix), and a decoder is asked for float only where a float track will really open.
- An artist's music videos play one after another: tapping one plays it with the rest of the row after it, instead of stopping when it ends.
- Ending a cast brings the music back to the headphones or speaker it came from, not the phone's own speaker.
- As the music goes out to a TV, the whole screen is drawn up after it: it used to come away from the screen's edges and show gaps at the sides. It's held at the edges now and stretches toward the top, as a sheet in a frame would; a download's wave too.
- The fade round the dial has no bands: it falls off along one smooth curve, with a grain finer than a step of colour.

### Added
- Apple Lossless (ALAC) plays everywhere, with Gramophone's decoder: Samsung phones have none of their own.
- ReplayGain (Settings, Sound), Gramophone's: each song brought to one loudness by the levels its file carries, by song or by album, never past its own peaks.
- Bit-perfect to a USB DAC (Settings, Sound, Android 14 and later), BitChord's: lossless songs go to the DAC untouched, at their own sample rate and bit depth. The DAC sets the volume then, and the equaliser, ReplayGain, crossfade and Automix leave those songs alone.
- Words on the cover (Settings, Now Playing): off, the cover is the picture alone, with none of its printed words or lines of the song.
- Home-screen widgets, 2×2 and 4×2 (BitChord's, in AETHER's face): the song playing, its cover going out to black in rings round its keys as Now Playing's film goes out round the dial, its name in Now Playing's capitals and its artists in the book serif, and Now Playing's keys: previous, play or pause, next. A tap anywhere else opens Now Playing. Either can be resized into the other's shape. The play key turns the moment it's touched, and the next song's picture is drawn while this one plays, so a change shows its cover with its name.
- Light from the cover (Settings): off, Now Playing is black with only the cover's faint glow, as it was before the light.
- Latin letters first (Settings, with Words in Latin letters on): in Verse, the Latin letters are sung as the line, large, each word lighting as its own word is sung, and the words in their own script go beneath them, smaller.
- Sync with YouTube Music: a key on every playlist, on Liked songs, and on your own YouTube Music playlists' pages. On, the playlist and its copy in your account are kept as one, both ways: a song added or taken out on either side is added or taken out on the other, and so is a new name. A playlist made in AETHER is made in your account too (private); one of your account's becomes one of AETHER's, kept in step with it; Liked songs follow your account's liked songs. Its arrows turn while it syncs. Off, it stays as it last was. Deleting a playlist in AETHER leaves its copy in your account.
- Every list can be put in order: the Library's playlists, Kept, the phone's music, Songs (and Liked songs' page), albums and artists, and a playlist's page. A key under the list's keys names the order it's in; touched, the orders that list can honour open from it in a pane of glass, the one in force ticked: alphabetical, A to Z or Z to A; date added, newest first; date modified, newest first, where something can change (your playlists, the phone's files); and for songs their artist, album or length. Each list keeps its own, and starts newest first.

### Changed
- A playlist's order is chosen from that key too, in place of the bar of orders under its keys, and it can now run Z to A. A YouTube playlist has no Date added: YouTube doesn't say when a song came to it.
- The equaliser and Automix's filters now shape every song, FLAC and other hi-res files included: Media3 had been taking those past them. The sound path is BitChord's, ported whole, which also lets Automix's loop mix run.
- On the phone's own speaker, songs go out as 16-bit, as BitChord's do; through a DAC or headphones that take it, as float, every bit of a 24-bit file kept.
- Bit-perfect is now the listener's choice, for lossless songs; streams no longer switch a DAC into it by themselves.
- Now Playing's cover, as itself, is shown whole: square, as it was made, not cut to the print's 4:5. The prints stay 4:5.
- The cover's light behind Now Playing is BitChord's mesh, Apple Music's backdrop: four soft lights in the cover's own colours over a dim ground of the first, across the top of the screen, slowly orbiting while the song plays. A new song's light fades in over the last one's on a spring, its colours read while the song before it plays, so it comes in with its cover. It goes out round the dial in the same rings as before, gives way to a song's film, and holds still while a music video plays over it. Drawn once and kept until it moves, it costs opening and closing Now Playing about what no light would. With motion reduced it never moves.

## [3.0.0] - 2026-11-20

### Added
- Play on a TV: the cast key beside the video key on Now Playing finds the TVs and speakers with Google Cast on your Wi-Fi. The phone stays the source (it serves each song to the TV, and the queue stays yours), its volume keys set the TV's volume, and "This phone" brings the music back where the TV had got to. As the music leaves, the screen is drawn up after it from the top, a wave running down through it, as a download's does coming in.
- AETHER's own screen for the TV, in `site/cast/`: Now Playing on its side, the print in the middle with its wipe and darkroom, the dial standing on the right edge, the cover's film across the whole screen. Each song arrives up through the screen on a wave from the foot. The words come up full screen with the TV remote's OK key, or with every song from Settings: Lyrics on the TV. It shows once it's registered with Google Cast and its id is built in (docs/cast.md); 3.0.0 isn't, so a TV plays AETHER on Google's standard screen, and the Lyrics on the TV setting stays hidden until then.
- Moving covers from the community's index of looping covers (the one BitChord and LastWave ask), for songs Apple Music has no film for: played where the film plays, as it does. The index is fetched whole and matched on the phone; nothing about what you play is sent.
- A sleep timer (BitChord's), in Now Playing's menu: after this song, 15, 30 or 45 minutes, an hour, or any length up to twelve hours in five-minute steps. Its row counts down to the pause. With a crossfade or Automix on, the song fades out at its end instead of running into the next; on repeat, it pauses as the song comes round.
- AETHER looks for a newer version every time it's opened, not only every few hours, and says so at once: on its sheet, or, while Now Playing is open, in a few words at the foot of the screen until you close it.
- Automix (Settings, Sound), BitChord's: each song is measured on the phone (tempo, beats and bars, phrases, key, where the voice is) and run into the next as a DJ would, in time, on the bar, with the bass handed over, a filter or an echo where it suits; a crossfade where two songs can't be mixed. Now Playing marks on the dial where the mix will be, and the cover turns into the next song's in a cloud of its own coloured dots for exactly as long as the mix lasts. A song is measured once and remembered; the next song is fetched while this one plays, then played from what was fetched.
- Up next during Automix: the song being mixed in is shown as such ("Mixing in") and stays put, since it's already playing.
- The queue keeps going on its own, as BitChord's AutoPlay does: ten songs suggested by the playing song are kept waiting after yours, topped up as each song begins, with the app closed too. No song comes twice as its own video, and no artist takes over.
- Playlists in and out. Import (in the Library beside New playlist, and in Settings) brings playlists in from a file (AETHER's own, M3U, CSV such as Exportify's, a list of songs, or Spotify's data download with every playlist and your liked songs) or a public Spotify or Apple Music playlist's link. Each song is found on YouTube Music as exactly as AETHER can: a live version never stands in for the studio one, and what can't be found is listed by name. Nothing is doubled: a playlist imported again only gains the songs it's missing. Export, in a playlist's menu, writes it as an AETHER file, M3U8 or CSV; Settings writes every playlist at once.
- Explicit songs are marked, as the catalogues mark them: a small grey E at the head of the song's name, in every list, the mini player, Now Playing, a song's menu and what's coming down, and on cards beside who made it.

### Changed
- The Library scrolls more smoothly, the first time through most of all (measured on the A059: half as many frames again drawn in the same scroll, and its slowest frames up to 40% shorter). A folder's panels are pictures painted once, not outlines worked out again as each row comes into view, and the frost at the screen's edges fades in at the quarter resolution it's made at. Both look exactly as they did.
- A music video plays without lyrics: its sound runs to its own clock (an intro, a scene, a longer cut), so words timed to the song drifted from it, and a video's title found other songs' words. The lyrics key says so.
- An album YouTube Music files by its music videos (KR$NA's "Yours Truly": 14 of its 15 tracks) plays as its songs: each video's song is found (YouTube Music's own pairing when signed in, then its Songs shelf, the album's songs first) and takes its place, number and all. A video whose song isn't found stays as it is.
- The mini player grows into Now Playing the way the Dynamic Island does: the pill itself grows, its dark glass and lit rim to the screen's edges, its cover flying up to be the print, and Now Playing's parts coming in inside it once there's room. Springs with Apple's bounce: the print lands with a little give, and closing (Back, the key, or a pull from anywhere), the pill takes the landing and settles. A song with a film shows its cover flying, and the film comes up over it as it lands.
- A folder flies home into wherever its place is now: scroll the list while it's still in the air and it lands exactly in its place, with no shaking (it used to land where its place had been, vanish, and pop up somewhere else). Its page slides in and out as every page does.
- Android's back gesture moves AETHER's own pages with your thumb, as swiping back does on iPhone, from wherever the page is; let go short and the page comes back.
- Back closes what's on top first: Up Next, a menu, a question, a playlist's search field. (With Up Next open, Back used to close all of Now Playing.)
- Automix: a song you move to play next is measured first, and the mix's mark on the dial glides to its new place.
- Now Playing opens and closes without first building itself or letting everything go. A playlist's page has its keys and its songs as it slides in, not after it lands: the library remembers each playlist, and the songs on show are made in the first few frames.
- Liked songs is a folder like every playlist: it flies to the head of its page.
- Pages, folders and the glass over them are lighter to move: a page sliding is one picture while it moves, and a flying folder paints its outlines once, not every frame.
- Now Playing and the lyrics can be pulled down from the middle of the screen. In the word-by-word lyrics a pull closes them while they follow the song; read back by hand, the page scrolls first.
- The dial: a new song's hand sweeps back to its start instead of jumping there; scrubbing starts from where the hand is, with no flash to the beginning, and doesn't flick back when let go.
- Search lists songs first, always, then artists, albums, videos, playlists and podcasts: five songs, then More songs for the rest, and more again from YouTube Music. The top result leads its own kind instead of standing above the songs, and typed suggestions put songs first too.
- Names of pages fit their place: an artist's name too wide for the screen shrinks to fit whole instead of breaking inside a word ("PinkPanthere / ss"), and a name that needs two lines breaks only at a space and is set smaller, so it doesn't stand huge. Playlists', albums' and your own name follow the same rule.

### Fixed
- A page opened while another is still flying home comes in over it: the folder goes on home under the new page instead of over it, vanishing as it landed, and the page beneath no longer jumps back as the new one starts.
- A folder touched while a card's page was still arriving no longer flew onto that page, and two folders touched at once no longer left the first page saying its playlist was gone while it loaded.
- Search: every song shows its length. The mixed results give one only to the top result's songs; the rest are asked of YouTube Music's queue, all at once.
- Wrong lyrics: KuGou's answers are held to the song now (asked for two lesser songs called "Espresso" it gave Tchaikovsky's "June" and "Spice Up Your Life"). A library that wants a key for a song counts as not having it, and writers' names or a "Title - Artist" line at the head of a file no longer show as sung lines.
- Now Playing closing into the pill no longer flickers after its bounce. The bounce carried the island a hair past closed and back, and coming back it showed again over the pill for about a sixth of a second, its darker tint in place of the pill's glass. Once a close has put it away it stays away until it's opened again.
- With Automix on, a song set to loop (repeat on with only it in the queue, or the same song queued twice running) no longer mixes into itself, which made its second time through stutter: it comes round as a repeat does.
- Two folders touched at once no longer both open, one onto the other: while a page is arriving, a second is ignored, as on iPhone.
- A playlist shown twice on a page (in two sections of Listen) opens from the copy you touched.
- Automix: no more crackling as the next song comes in. Tempo changes no longer restart the audio path (each one clicked), and two songs' peaks can't sum past full scale. A phone that's warm (charging, say) still measures songs, gently, instead of falling back to plain crossfades.
- Automix's change of cover runs smoothly at the screen's full rate.
- A song dragged to a new place in a playlist follows the finger instead of shaking on its way.
- Moving songs in Up Next works as BitChord's does: the song in hand stays under your finger whatever the list does, the songs around it slide aside as you go so you see where it will land, and the list scrolls on when you carry it to the top or the foot. Drag it by its handle, or hold it anywhere first.
- In search, a top result's songs named nobody: they're credited to the artist the result is. Songs searched for show their length and album wherever YouTube Music gives them.
- AETHER could close by itself when one of your own sources was slow to answer: its answer was read, and closed, on the main thread, which Android forbids. Sources are now always asked off it, and a source's answer can no longer bring AETHER down however it misbehaves.
- Lyrics are read off the main thread too: a long answer sometimes failed for the same reason, and showed as no lyrics.

## [2.3.0] - 2026-11-11

### Fixed
- Every page said "The music service didn't answer" on phones set to a language or country YouTube Music doesn't take (Santali, Konkani, Kashmiri, Sindhi, Dogri, Maithili, Bodo, Manipuri, Yiddish; China, Iran, Afghanistan, Myanmar and a few more), signed in or not. AETHER now finds out which it was and asks without it.
- What's offered follows where you are, not where your phone is set: AETHER sends the country YouTube Music finds you in, as its website does.
- A sign-in that has ended says so ("Your sign-in has ended"), instead of saying a page needs an account.
- Songs kept as M4A carry their cover, name, artist, album and words, so file managers and other players show them; a kept song's cover is fetched again if the first try fails, and is always a JPEG or PNG.
- Your own sources: one that didn't answer when AETHER started (no connection yet, a slow server) was left out until AETHER restarted. It's asked again when the connection comes back and every two minutes, and Settings has Try again. Adding one allows a slow server longer.
- A source that refuses says why in Settings, in its own words, and rests after refusing twice, so songs don't wait on it.

### Changed
- Your own sources are handled as BitChord's host handles them: a song's stream is asked for before the address its search row gave (kept as the fallback, so a source that only searches plays too), its songs show their covers, and one that asks to be used only with a lossless output (wired or USB headphones, HDMI, or Bluetooth on LDAC, LHDC or aptX Lossless) waits until one is connected. To tell which codec Bluetooth headphones are using, AETHER asks for Nearby devices and, on Android 14 and later, for Android's one-time permission to know those headphones, both from that source's row and only when it matters.
- The player's notification wears AETHER's mark instead of a play symbol.
- An artist's Albums, Singles & EPs (every "See all" of albums, playlists or artists) run down the page two to a row, as videos do, and the page is named for the shelf, the artist's name above it.

## [2.2.0] - 2026-11-11

### Fixed
- AETHER closed as it opened for anyone whose history held a song with no artist named (an upload, or a song kept under 2.0): Profile assumed every play had one. It now says "None named yet", and those plays count for their artist again where the phone knows who it was.
- A song chosen with no artist (some of YouTube Music's shelves and suggestions leave it out) takes its artist from YouTube's own answer for that song, a moment after it starts: Now Playing, the notification and Profile all name who made it, with no break in the sound.
- Cover films played a middling copy (a 4.9 Mbps 1078 x 1438 film that Apple also serves at 7, 10 and 20 Mbps): every film now plays the richest copy the phone can decode within its size.

### Added
- Playlists in your YouTube Music library come into AETHER's Library when you're signed in, and stay up to date; signing out takes away what the sync brought.
- Sort a playlist's songs by your order, title, artist, album, when they were added (your own playlists) or length. Each playlist keeps its own choice; a long YouTube playlist is fetched whole before it's sorted.
- A song's cover film on Now Playing plays Apple's finest copy, on mobile data too (the data saver still loads no film) (up to 2160 square, 2048 x 2732 tall), drawn down to the screen: sharper than a screen-sized copy drawn up.

## [2.1.0] - 2026-11-11

### Fixed
- A queue saved while a kept song played is mended on the next start.
- Kept songs show their name, artist and cover again on Now Playing, the mini player, the notification and Samsung's Now Bar; their covers are saved for offline.
- A page could close the app when the catalogue listed the same card twice, or an artist had two shelves of one name.
- Cover films play at their best quality for the screen from the first frame, portrait films included.
- "See all" on Trending, and on an artist's Top songs and Videos, opened empty: those are playlists, and are read as playlists now. A "See all" of songs or videos shows every one, two to a row; one YouTube Music sends empty says so.
- Now Playing's video key is one kept setting again: on, a song shows its cover and then its film at its best, and a music video plays as itself; off, just the cover.

### Changed
- A playlist's Rename, Edit and Delete live in a menu at its top; Delete asks first. Its add key is a search that opens into a field, rises to the top of the page and finds songs as you type, or says they're unavailable; the tick closes it and the page comes back as it was.
- A small arrow beside every song kept on the phone, dim while it's still coming down.
- One download disc at the foot, the number coming down written on its cover (up to 99+), sitting lower, clear of the bars.
- Playlists no longer keep new songs by themselves: your own have no keep key, and a catalogue playlist's keeps its songs once.
- Playlists from the catalogue that you saved, or kept songs from, are in your Library; offline, one with nothing kept is greyed out, and one with kept songs opens on them.
- Now Playing's print wears its tattoo again; its style is chosen in Settings, not by touching it.

### Added
- When AETHER closes by itself, what went wrong is kept on the phone, and Settings lets you share it. Nothing is sent otherwise.

## [2.0.0] - 2026-11-10

AETHER v2.

### Added
- **Lyrics from seven libraries:** lrc.red, BiniLyrics, BetterLyrics, LyricsPlus, Unison, LRCLIB and KuGou, asked together, the best answer kept: word by word where a library has it, by the line otherwise. Each can be switched off in Settings.
- **Verse:** a third way to draw lyrics, BitChord's, ported with only the face changed: the song as a page that's sung, each word lighting syllable by syllable behind a soft edge ahead of the voice, held notes swelling letter by letter and glowing, the lines around a step back and out of focus, the answering vocal opening beneath its line as it's sung, duets from both sides, three points counting out each break. The page moves on springs that keep their speed from one line to the next. Touch a line to go to it.
- **Words in Latin letters:** Korean, Chinese, Japanese kana and other scripts romanised on the phone, beneath each line, beside each star, or written by the pen. A Latin key on the lyrics turns it off.
- **Exact copies from your own sources:** for a song from YouTube Music, your addons are asked for the same recording first, at their best quality, and it plays in its place; YouTube Music plays what they don't have. DASH and HLS copies play. Now Playing says which source a song came from.
- **Music videos:** a video plays as video in place of its cover, its pictures following the music, at 720p (480p on the data saver).
- **Albums on Listen:** the music on your phone as albums, and the songs you've kept as albums, two shelves of their own.
- **Crossfade:** 2 to 12 seconds between songs, in Settings under Sound. Never between an album's songs that run into each other.
- **Full screen:** Android's bars hidden in Settings under Feel, the page keeping its place; a swipe from the edge brings them for a moment.
- **A better copy, mid-song:** when your own sources find the same recording after a song has begun, it goes over to that copy while it plays, lined up by its sound and handed over in a short crossfade.
- **Kept songs in your Music folder:** every kept song is a file in Music/AETHER, the same audio that streamed, with its cover and words in its tags, for the file manager and any player.
- **Lyrics on the phone:** a song's own .lrc beside it, or the words inside its file, shown first.
- **Updates in the app:** a newer AETHER is offered on a sheet, downloaded, checked against the release and handed to Android to install.

### Changed
- Nothing is written over covers any more: Now Playing's print and the songs on a playlist's folder are the covers as they are.
- Listen's figures change only once the page has been still for a few seconds, and hold mid-change while you touch or switch.
- An addon that answers only BitChord's own app is refused when added, with the reason.
- A song that fails mid-play is tried again from where it stopped, from another source, app or format, before you're told.
- Turning a music video off and on again carries on where it was, without stalling.

## [1.0.0] - 2026-11-09

The first release.

### Added
- **Listening:** the whole YouTube Music catalogue, as a guest or signed in; your own music by folder; songs and playlists kept for offline, with new additions picked up on Wi-Fi; your own sources by address, open media only.
- **Sound:** quality labels only when measured or verified; bit-perfect output to a USB DAC on Android 14 and later; float output with no resampling by the app.
- **Equaliser:** seven tunings, measured corrections for 39 headphones, and profiles imported from AutoEq, squig.link, Equalizer APO or Wavelet files. A boost can't clip, and with nothing chosen every sample passes through untouched.
- **Now Playing:** a seeking dial; the cover as a print, halftone, ASCII or dither; the album's film in colour where it has one, fading in an arc round the controls; lyrics as constellations or as one unbroken line.
- **Listen:** twelve statues in halftone, the dots of one flying across to become the next.
- **Downloads:** the cover carried into the foot of the screen with a ripple on arrival; several at once as a row of discs, with a list of what's coming down.
- **Profile:** most played artists, your hours as a dial, genres and habits, all worked out on the phone.
- **Cards:** a metal charge card of your profile or any song, black or silver, at 1080 by 1920.
- **Privacy:** no ads, no analytics, no tracking, no account and no server of ours.

[Unreleased]: https://github.com/GHOSTxASR/AETHER/compare/v3.1.0...HEAD
[3.1.0]: https://github.com/GHOSTxASR/AETHER/compare/v3.0.0...v3.1.0
[3.0.0]: https://github.com/GHOSTxASR/AETHER/compare/v2.3.0...v3.0.0
[2.3.0]: https://github.com/GHOSTxASR/AETHER/compare/v2.2.0...v2.3.0
[2.2.0]: https://github.com/GHOSTxASR/AETHER/compare/v2.1.0...v2.2.0
[2.1.0]: https://github.com/GHOSTxASR/AETHER/compare/v2.0.0...v2.1.0
[2.0.0]: https://github.com/GHOSTxASR/AETHER/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/GHOSTxASR/AETHER/releases/tag/v1.0.0
