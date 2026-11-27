# YouTube Music: what the app can get

Verified live on 2026-11-03 from this machine (India, signed out), as YouTube Music's own web client
(`WEB_REMIX` 1.20261031.09.00). Rerun any time with `./gradlew :ytmusic:liveCheck` (add
`-Pcountry=US` for another country's charts). The answers captured that day are the parser's test
fixtures (`ytmusic/src/test/resources/fixtures`, visitor id and tracking parameters removed).

## 1. Every page the official app shows

| Surface | Request | What came back (India, signed out) |
|---|---|---|
| Home | `browse FEmusic_home` | 11 chips (Podcasts, Romance, Feel good, Relax, Party, Energize, Workout, Commute, Sad, Focus, Sleep), a background image, then about nine shelves over three continuation pages: Quick picks (20 songs, each carrying its radio), Trending community playlists, India's biggest hits, Dancing on your own, Old School Romance, 90s Throwback Fun, Trending songs for you, New releases. Shelves carry straplines ("DANCE YOUR STRESS AWAY"). |
| A home chip | `browse FEmusic_home` + the chip's `params` | Home filtered: Relax gives Coffee shop blends, Quick picks, Chilled… and continues. |
| Explore | `browse FEmusic_explore` | Four entries with icons (New releases, Charts, Moods & genres, Podcasts → `FEmusic_non_music_audio`), New albums & singles (24), Moods & genres (50 coloured tiles), Popular episodes (24), Trending (20 videos), New music videos (24). |
| Charts | `browse FEmusic_charts`, country in `formData.selectedValues` | A 70-entry country menu (69 countries and Global, code `ZZ`). India: Video charts (Trending 20, Daily Top Music Videos, Top 100 Music Videos), Languages (7 weekly charts), Top artists (40). United States adds Top 100 Live Performances, Genres (8 "Top 50" genre charts) and Weekly top podcast shows (50). |
| Moods & genres | `browse FEmusic_moods_and_genres` | Moods & moments (12) and Genres (38), each tile with its colour. A tile opens `FEmusic_moods_and_genres_category` with its `params` (Chill: 13 shelves of playlists). |
| New releases | `FEmusic_new_releases`, `…_albums`, `…_videos` | The page (Albums & singles 24, Music videos 24) and full grids (about 130–140 albums, 100 videos). |
| Podcasts | `browse FEmusic_podcasts` | Popular shows, Popular episodes and about 15 topic shelves. |
| Album | `browse MPRE…` | Title, "Album • 2026", "9 songs • 43 minutes", every track with plays and duration, the album's own playlist. |
| Playlist, chart | `browse VL…` | Header and every track (Trending 20 India: 20). |
| Artist | `browse UC…` | Name, description, subscribers, **monthly audience** ("657M monthly audience"), Top songs, Albums, Singles & EPs, Videos, Live performances, Featured on, Playlists by, Fans might also like, radio and shuffle. |
| Search | `search` | Nine filters (Artists, Featured playlists, Albums, Songs, Videos, Community playlists, Profiles, Episodes, Podcasts). Unfiltered results are now one flat list: a top card, then one section per result. |
| Suggestions | `music/get_search_suggestions` | Queries and items to open directly. |
| Up next, radio | `next` with `RDAMVM<videoId>`, `wAEB` | A 50-song radio, and the song's Lyrics and Related tabs. |
| Lyrics | `browse MPLY…` | Plain lyrics with their credit ("Source: LyricFind"). Synced lyrics need other providers. |
| Related | `browse MPTR…` | You might also like, Recommended playlists, Similar artists, more by the artist. |

Signed in (a session from Google's own sign-in page in a WebView, as PODIUM does it), Home becomes
personal (Listen again, Mixed for you…) and the library, likes and history open. Those calls are in
the client but haven't been run live here: there was no session to run them with.

## 2. Region and language

- **Home and Explore follow where the listener really is**, as in the official app. Asking with
  `gl=US`, `JP` or `BR`, with no visitor, with a visitor fetched "as" another country, or with a
  visitor id whose region field was rewritten, still answered with India's shelves ("India's biggest
  hits", "Diwali szn"). The visitor id carries the region (protobuf field 6.1) and the service
  replaces a rewritten one.
- **But Home's picks follow `gl`** (2026-11-11). From India, `gl=US` kept the same shelves but Quick
  picks turned American (Beat It, A Thousand Years, Paparazzi). So AETHER sends what the web app
  sends: the country the music page placed the visitor in (`INNERTUBE_CONTEXT_GL` in the page, from
  the connection), not the phone's region setting, which travels with the phone. The phone's region
  is used only when the page can't be read.
- **Some languages and countries are refused outright** (2026-11-11): a 400 to every request.
  Languages: Yiddish (`yi`, `ji`), Santali, Manipuri, Sorani Kurdish, Kashmiri, Sindhi, Dogri,
  Konkani, Maithili, Bodo, and a blank. Countries: CN, IR, AF, KP, CU, SY, SD, MM, XK, `419`, and a
  blank. Taken: every major Indian language besides those, `in`/`id`, `iw`/`he`, `fil`/`tl`,
  `nb`/`no`, zh with TW or HK, BY, VE. The client learns a refusal by asking again without the
  language (English), then without the country (US), and remembers which it was
  (`RefusedLocaleTest`; live: `./gradlew :sources:youtube:localeCheck`). Streams aren't affected:
  the player answered with CN and IR.
- **Charts take any of the 70 countries**, on request. That's the country picker.
- `hl` sets the language of titles and labels.

## 3. "Top monthly"

YouTube Music's charts are daily (Daily Top Music Videos), weekly (Top 100, the language and genre
"Top 50"s, podcasts) and trending (Trending 20). There is no monthly chart. Monthly figures exist
per artist: their monthly audience, on the artist page and in search.

## 4. Streams

Checked the way PODIUM checks a stream before the player gets it: the open-ended first request
(`bytes=0-`) and the last two bytes, both must answer 2xx.

| App asked | Answer | Best audio | Streams |
|---|---|---|---|
| `VISIONOS` (PODIUM's first) | OK | Opus, itag 251, 158–162 kbps | **Play** (206, 206) |
| `ANDROID_VR` | OK | Opus, itag 251 | Refused (403, 403): needs a PoToken now |
| `IOS` | OK | Opus, itag 251 | Refused (403, 403) |

- Signed out, the best YouTube Music audio is Opus at about 160 kbps (itag 251) or AAC at 128 kbps
  (itag 140). The 256 kbps tier (Opus itag 774, AAC itag 141) is for the listener's own Premium account.
- No DRC (dynamic-range-compressed) variants were offered for the two songs checked; PODIUM's
  resolver excludes them when they appear.

## 5. NewPipeExtractor (v0.26.5, the version both apps use)

It covers YouTube Music **search** (songs, videos, albums, playlists, artists), streams, playlists,
and a "Trending music videos" kiosk read from YouTube Charts (`charts.youtube.com`,
`FEmusic_analytics_charts_home`), plus trending podcasts, gaming, movies and live. It has **no** Home,
chips, Explore, moods and genres, new releases, YouTube Music's Charts page, or YouTube Music artist
and album pages. So the catalogue is read from InnerTube directly (as both PODIUM and LastWave do),
and NewPipe stays what it is in PODIUM: the stream failsafe.

## 6. Found in PODIUM along the way

The guest visitor id the music page is served with is now 446 characters (a new protobuf field,
12). PODIUM's `PageConfigParser` accepts at most 200, so PODIUM's catalogue client currently runs
without a visitor id. Requests still answer, but every one is a new visitor. The client here accepts
up to 2,000.

## 7. Changing the account's own playlists (signed in, 2026-11-24)

Checked live from the A059 through the debug build's `--es aether.debug synccheck`, `adoptcheck`,
`gonecheck`, `likecheck` and `syncprobe` (src/debug/SyncCheck.kt), on the listener's own account, with
their leave, on throwaway playlists named "AETHER sync test".

| What | Request | What came back |
|---|---|---|
| Make a playlist | `playlist/create` with `title`, `description`, `privacyStatus: PRIVATE`, `videoIds` | `{"playlistId": "PL…"}`. Read straight back it doesn't answer (the page is unreadable); a second later it's whole, its songs in order. |
| Add songs | `browse/edit_playlist`, `ACTION_ADD_VIDEO` with `addedVideoId`, `dedupeOption: DEDUPE_OPTION_SKIP` | `status: STATUS_SUCCEEDED`; the songs at the end, read back at once. |
| Take songs out | `ACTION_REMOVE_VIDEO` with `removedVideoId` and the entry's `setVideoId` (`playlistItemData.playlistSetVideoId` on the playlist's page) | Succeeded; gone when read back. |
| Rename | `ACTION_SET_PLAYLIST_NAME` with `playlistName` | Succeeded; the new name read back at once (once, a read soon after gave the old name). |
| Delete | `playlist/delete` | Succeeded. The deleted playlist's page doesn't answer "not found": it reads as unreadable, for minutes after, as a playlist does for a moment after it's made. Gone from the account's library list (`FEmusic_liked_playlists`). |
| Whose playlist | the page's header | The listener's own come wrapped in `musicEditablePlaylistDetailHeaderRenderer`; a playlist saved from someone else doesn't (checked on both kinds in the listener's library). |
| Like, unlike | `like/like`, `like/removelike` with `target.videoId` | A like shows in Liked Music (`VLLM`) at once; an unlike still showed 1.5 s later and was gone minutes later. |
