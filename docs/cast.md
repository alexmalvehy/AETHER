# Playing on a TV

AETHER casts to any TV or speaker with Google Cast (Chromecast, Android TV, Google TV, TVs with
Chromecast built in) on the same Wi-Fi as the phone. The cast key sits beside the video key on Now
Playing.

## How it plays

The phone stays the source: it serves each song to the TV over the room's Wi-Fi (the TV can't fetch
AETHER's songs itself), and its queue, history and settings stay its own. So:

- The phone and the TV must be on the same network. On mobile data there's nothing the TV can reach.
- A guest network that keeps devices apart from each other won't work.
- The phone has to stay on while the TV plays; AETHER keeps it awake and its Wi-Fi at full speed for
  as long as the cast lasts.
- The phone's volume keys set the TV's volume. Crossfade and Automix rest while a TV plays.
- "This phone" in the cast sheet brings the music back, carrying on where the TV had got to.

## AETHER's own screen on the TV

Without it, casting uses Google's standard receiver: the music, its name and its cover. AETHER's own
screen (Now Playing on its side, the dial, the film, the lyrics full screen, the song arriving on a
wave) is a Cast web receiver in this repository at `site/cast/`. Google only lets a TV load a receiver
that's registered:

1. Deploy the site (push `main`; Vercel serves `site/`). The receiver is at
   `https://aether-music-player.vercel.app/cast/index.html`.
2. Sign in at the Google Cast SDK Developer Console (cast.google.com/publish) and pay the one-time
   registration fee.
3. Add a new application: Custom Receiver, with the URL above. Note its Application ID (eight
   characters).
4. Until it's published, add your TV as a test device in the console (by its serial number) and
   restart the TV.
5. Build AETHER with the id, in `gradle.properties` or on the command line:
   `-Paether.castReceiverId=XXXXXXXX`. The id isn't a secret; it's sent to every TV the app casts to.

## The TV's remote

On AETHER's screen the remote's OK key turns the words on and off; the arrows show the key. Settings'
"Lyrics on the TV" decides whether a cast opens with them up. Whether a TV hands its remote's keys to a
Cast receiver is the TV's own business: where it doesn't, the setting still works.

## Designing the screen

`node site/dev.mjs` and open `http://localhost:4173/cast/index.html?preview` in a 16:9 window.
`&film`, `&words`, `&plain`, `&paused`, `&boot` and `&still` show its states; `&freeze=0.6` holds the
song's arrival at that second. In the console, `aether.next()`, `aether.back()`, `aether.lyrics()` and
`aether.remote('Enter')` play with it.
