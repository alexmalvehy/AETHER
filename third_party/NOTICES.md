# Third-party notices

- **Backdrop** by Kyant0 (Apache License 2.0, `licenses/Apache-2.0-Kyant-backdrop.txt`), v2.0.0, vendored
  in `core/designsystem/.../glass/backdrop` from BitChord (kushagrasinghx/BitChord, GPL-3.0), which added a
  backdrop resolution scale. Each file keeps its notice.
- **Fonts** under the SIL Open Font License 1.1 (`licenses/OFL-*.txt`): Pinyon Script, Cormorant Garamond, EB Garamond,
  Montserrat, JetBrains Mono, Tinos.
- **Phosphor Icons** 2.1.1 (MIT, `licenses/MIT-phosphor-icons.txt`): path data in
  `core/designsystem/.../component/PhosphorPaths.kt`.
- **Welcome water**: "Dark Rippling Water with Subtle Light Reflections", Pexels video 27700444, under
  the Pexels License (free to use and modify, no attribution required); graded, slowed and looped
  for AETHER as `app/src/main/res/raw/welcome_water.mp4`.
- **Statue photographs** behind Listen: eleven photographs from Wikimedia Commons, some CC BY-SA, listed with their
  photographers and licences in `STATUE_PHOTOS.md`; cropped, relit and made black and white.
- **Mood and genre photographs**: 53 Pexels photographs under the Pexels License, reduced and made black
  and white; listed in `MOOD_PHOTOS.md`.
- **Artist and album art lookup** (`sources/apple`): after BitChord's `AppleArtistArt.kt` and `AppleCatalogue.kt`
  (kushagrasinghx/BitChord, GPL-3.0). The pictures and films it shows are Apple Music's and the artists',
  fetched at run time for the listener and not part of AETHER.
- **Continuous line lyrics** (`app/.../ui/lyrics/line`): from PODIUM (`feature/nowplaying/line` and
  `LyricClock`, GPL-3.0), with its hand-authored single-stroke letters. Ink changed to white on black.
- **Phone music folders** (`core/model/.../Folders.kt`, `ui/settings/MusicFolders.kt`): from PODIUM
  (`sources/api/Folders.kt`, `feature/settings/MusicFolders.kt`, GPL-3.0).
- **Equaliser** (`player/.../Equaliser.kt`): the audio processor from PODIUM (`player/service/EqualizerProcessor.kt`, GPL-3.0).
- **Headphone corrections** (`core/data/src/main/assets/eq_headphones.json`): parametric results from AutoEq by Jaakko
  Pasanen (MIT, `licenses/MIT-AutoEq.txt`), from measurements by oratory1990, HypetheSonics, Rtings, kr0mka and
  Kuulokenurkka, each named beside its headphones in the app.
- **Orbit rings and stats band** (`app/.../ui/profile`): rebuilt in Compose after Orbit Ring Stats by ham7a311 and
  Stats Band by kuratlielia (21st.dev, MIT).
- **Liquid-lens boot film**: optical model adapted from Travis Fischer's MIT-licensed recreation, via the
  HorizonX "Liquid Lens Logo" export the listener supplied.
- **Automix** (`core/automix`, `player/.../automix`, `player/src/main/cpp`): BitChord's Automix (kushagrasinghx/BitChord,
  GPL-3.0), its analysis, planner, two-player controller and transition filters; each file keeps its notice. Its two
  models, as BitChord quantised them, are run by **ONNX Runtime** 1.28.0 (Microsoft, MIT): the beat model is
  **Beat This!** by the Institute of Computational Perception, JKU Linz (`player/src/main/assets/beat_this_final0_q8.onnx`, MIT) and the voice model **open-unmix**
  UMX-HQ vocals by Inria (`player/src/main/assets/vocals_umxhq_int8.onnx`, MIT).
- **Signalsmith Stretch** 1.3.2 and **Signalsmith Linear** 0.6.6 by Signalsmith Audio (MIT), copied verbatim in
  `player/src/main/cpp/native/third_party` with their `LICENSE.txt`: Automix's loop mix.
- **Playing on a TV** (`player/.../cast`, `app/.../cast`): after BitChord's `CastController`, `CastPlayback` and cast
  sheet, and LastWave-native's `CastStreamServer` (Clash-Projects/LastWave-Native, GPL-3.0); each file keeps its notice.
  It uses **Google Play services' Cast framework** 22.2.0 (Google's Android SDK terms, not open source), **AndroidX
  MediaRouter** 1.8.1 (Apache-2.0) and **NanoHTTPD** 2.3.1 (BSD-3-Clause), which serves each song to the TV from the phone.
- **hls.js** 1.5.20 (Apache-2.0, `site/cast/vendor/hls.js-LICENSE.txt`), vendored in `site/cast/vendor` for the TV's
  screen to play a cover's film.
- **Moving covers from the community** (`sources/canvas`): the community's index of looping covers, asked as BitChord
  and LastWave ask it. The films are the community's and the artists', fetched at run time and not part of AETHER.
- **The sound path** (`player/.../audio`): BitChord's `PrecisionAudioSink`, `AudioOutputPolicy` and the half of its
  `AudioRouting` that says where music goes (GPL-3.0); its `DirectAudioProbe`, `BitPerfectRoutingSink`,
  `BitPerfectAudioSink`, `BitPerfectOutput` and `BitPerfectTransport` (Copyright 2026 BitChord Project, Apache License 2.0);
  and its `MixerTransport` and `BitPerfectPcm`, which BitChord adapted from Mornye (github.com/mornyeapp/Mornye,
  Copyright 2026 zarzet, LGPL-3.0-only). Each file keeps its notice; their tests came with them.
- **Apple Lossless** (`player/src/main/java/org/nift4/alacdecoder`, `com/beatofthedrum/alacdecoder`): Gramophone's
  `AlacRenderer` and `AlacDecoder` (github.com/FoedusProgramme/Gramophone, Copyright 2025 nift4, GPL-3.0) over Peter
  McQuillan's Java ALAC decoder, after David Hammerton's (BSD-3-Clause, notice in each file), unchanged.
- **ReplayGain** (`player/.../audio/ReplayGain.kt`): Gramophone's `ReplayGainUtil` and the gain of its
  `ReplayGainAudioProcessor` (Copyright 2024 nift4, GPL-3.0).
- **Home-screen widgets** (`app/.../widget`, `player/.../WidgetState.kt`): after BitChord's `MediaWidget`,
  `MediaWidgetActions`, `MediaWidgetArt` and `MediaWidgetSnapshot` (GPL-3.0), noted at the head of each file.
- **Songs fetched in bounded ranges** (`player/.../ChunkedDataSource.kt`): BitChord's `ChunkedDataSource` and
  `PlayerClient.rangeBytesFor` (GPL-3.0), noted at the head of the file.
- **The light behind Now Playing** (`app/.../ui/player/Ambient.kt`, `core/designsystem/.../art/AmbientColors.kt`,
  `PaletteQuantizer.kt`): BitChord's `MeshGradientBackground` and its four colours from a cover (`MeshGradient.kt`,
  GPL-3.0), and its port of androidx Palette's colour cut (`PaletteQuantizer.kt`; Palette is the Android Open Source
  Project's, Apache License 2.0). What AETHER changed is noted at the head of each file.
- **Sleep timer, moving songs in Up Next, and the songs that keep the music going**: from BitChord (`SleepTimer.kt`,
  `PlayerQueue.kt`, `QueueBuilder.kt`; GPL-3.0), in `player/.../SleepTimer.kt`, Now Playing's Up Next and `Station.kt`.
