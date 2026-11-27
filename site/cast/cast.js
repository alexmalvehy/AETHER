// AETHER on a TV: the Google Cast receiver a phone running AETHER casts to.
//
// Now Playing, turned on its side (cast.css draws the grid), and the song's words full screen when the
// remote asks for them. The music is the phone's, served from it over the room's Wi-Fi and played here
// by Google's player. What stands with it is the phone's too, sent over the cast's own channel
// (urn:x-cast:app.aether.stage, as app/.../cast/CastStage.kt sends it): the song, its cover at its
// largest, its film, its words, and whether the listener wants the words up. Until that arrives a song
// is shown from what the cast itself says of it.
//
// Everything that moves moves on Now Playing's own springs (theme/Fluid.kt: Apple's response and
// damping, solved exactly), with the phone's numbers: the print's wipe, the film's rise, the hand's
// travel, the words' page, the wave the song arrives on (KeepFlight's, through art/PullRipple.kt's
// sheet). The one tween is the print developing, which is tone, not movement, as on the phone.
//
// ?preview shows it with stand-in words and pictures, for designing on a computer: &film for its film,
// &words for the words up, &plain for a song without any, &still to skip the arrival, &freeze=0.6 to
// hold the arrival at that second, &paused. window.aether.next() / .back() / .lyrics() / .remote(key).
(() => {
  'use strict'

  const NS = 'urn:x-cast:app.aether.stage'
  const CAF = 'https://www.gstatic.com/cast/sdk/libs/caf_receiver/v3/cast_receiver_framework.js'
  const query = new URLSearchParams(location.search)
  const preview = query.has('preview')
  if (preview) window.addEventListener('error', (e) => { document.title = 'ERROR ' + e.message + ' @' + e.lineno })
  const $ = (id) => document.getElementById(id)
  const el = {
    stage: $('stage'), soft: $('soft'), film: $('film'), video: $('filmVideo'), mark: $('mark'),
    heading: $('heading'), place: $('place'), print: $('print'), sheets: $('sheets'), micro: $('micro'),
    song: $('song'), title: $('title'), aside: $('aside'), artist: $('artist'), format: $('format'),
    dialBox: $('dialBox'), dial: $('dial'), now: $('now'), left: $('left'), key: $('lyricsKey'),
    keyWord: $('keyWord'), lyrics: $('lyrics'), words: $('words'), lines: $('lines'),
    lyricsName: $('lyricsName'), credit: $('credit'), veil: $('veil'),
  }
  const rem = () => window.innerWidth / 192

  // --- Springs: Now Playing's FluidSpring, solved exactly ----------------------------------------------

  function solve (response, damping, x0, v0, t) {
    const w = 2 * Math.PI / response
    const z = damping
    if (Math.abs(z - 1) < 1e-5) {
      const b = v0 + w * x0
      const e = Math.exp(-w * t)
      return [(x0 + b * t) * e, (b - w * (x0 + b * t)) * e]
    }
    if (z < 1) {
      const wd = w * Math.sqrt(1 - z * z)
      const b = (v0 + z * w * x0) / wd
      const e = Math.exp(-z * w * t)
      const c = Math.cos(wd * t)
      const s = Math.sin(wd * t)
      return [e * (x0 * c + b * s), e * ((b * wd - x0 * z * w) * c - (x0 * wd + b * z * w) * s)]
    }
    const g = Math.sqrt(z * z - 1)
    const r1 = -w * (z - g)
    const r2 = -w * (z + g)
    const c2 = (v0 - r1 * x0) / (r2 - r1)
    const c1 = x0 - c2
    return [c1 * Math.exp(r1 * t) + c2 * Math.exp(r2 * t), c1 * r1 * Math.exp(r1 * t) + c2 * r2 * Math.exp(r2 * t)]
  }

  /** A value on a spring (Now Playing's Glide): retargeted, it carries on at the speed it had. */
  class Fluid {
    constructor (value, response = 0.5, damping = 1, rest = 0.0005) {
      this.value = value
      this.target = value
      this.velocity = 0
      this.response = response
      this.damping = damping
      this.rest = rest
      this.from = value
      this.v0 = 0
      this.t = 0
      this.resting = true
      this.waiting = []
    }

    to (target, response = this.response, damping = this.damping) {
      this.response = response
      this.damping = damping
      if (target === this.target && this.resting) return
      this.from = this.value
      this.v0 = this.velocity
      this.target = target
      this.t = 0
      this.resting = false
    }

    /** As [to], and resolves once it has come to rest. */
    go (target, response, damping) {
      this.to(target, response, damping)
      if (this.resting) return Promise.resolve()
      return new Promise((resolve) => this.waiting.push(resolve))
    }

    snap (value) {
      this.value = this.target = this.from = value
      this.velocity = this.v0 = 0
      this.resting = true
      this.settle()
    }

    step (dt) {
      if (this.resting) return this.value
      this.t += dt
      const [x, v] = solve(this.response, this.damping, this.from - this.target, this.v0, this.t)
      if (Math.abs(x) < this.rest && Math.abs(v) < this.rest * 8) {
        this.value = this.target
        this.velocity = 0
        this.resting = true
        this.settle()
      } else {
        this.value = this.target + x
        this.velocity = v
      }
      return this.value
    }

    settle () {
      const waiting = this.waiting
      this.waiting = []
      waiting.forEach((resolve) => resolve())
    }
  }

  /** Motion.Editorial: cubic-bezier(0.22, 0.61, 0.36, 1). */
  function editorial (x) {
    const [x1, y1, x2, y2] = [0.22, 0.61, 0.36, 1]
    const at = (a, b, t) => 3 * a * (1 - t) * (1 - t) * t + 3 * b * (1 - t) * t * t + t * t * t
    let t = x
    for (let i = 0; i < 8; i++) {
      const dx = at(x1, x2, t) - x
      const d = 3 * x1 * (1 - t) * (1 - t) + 6 * (x2 - x1) * (1 - t) * t + 3 * (1 - x2) * t * t
      if (Math.abs(dx) < 1e-5 || d === 0) break
      t = Math.min(1, Math.max(0, t - dx / d))
    }
    return at(y1, y2, t)
  }

  /** The clock everything moves by: the frame's, or (holding a moment in the preview) a stand-in. */
  let virtual = null
  const now = () => (virtual !== null ? virtual : performance.now() / 1000)
  /** Resolves [seconds] later by that clock. */
  const timers = []
  const after = (seconds) => new Promise((resolve) => timers.push({ at: now() + seconds, resolve }))

  // --- What's shown -------------------------------------------------------------------------------------

  /** The stage for each song, by its id: one may arrive before its song plays. */
  const stages = new Map()
  let song = null
  let lastIndex = -1
  const print = { sheets: [], up: new Fluid(0, 0.7, 1) }
  const film = { up: new Fluid(0, 0.7, 1, 0.002), url: '', hls: null }
  const words = { list: [], timed: false, focus: -2, scroll: new Fluid(0, 0.4, 1), up: new Fluid(0, 0.55, 1), placed: false }
  const key = { shown: new Fluid(0, 0.4, 1), lit: new Fluid(0, 0.38, 0.82), focus: new Fluid(0, 0.38, 0.82), until: 0 }
  const soft = { layers: [], src: '' }
  let lyricsSetting = false
  let lyricsChosen = null

  /** "Gabriela (feat. Someone)": the name, and what qualifies it, set apart as Now Playing sets them. */
  function splitTitle (title) {
    let name = (title || '').trim()
    const asides = []
    for (;;) {
      const m = /\s*[([]([^()[\]]+)[)\]]\s*$/.exec(name)
      if (!m) break
      const rest = name.slice(0, m.index).trim()
      if (!rest) break
      asides.unshift(m[1].trim())
      name = rest
    }
    const dash = /\s+-\s+((?:\d{4}\s+)?(?:Remaster(?:ed)?|Live|Radio Edit|Acoustic|Mono|Stereo)[^-]*)$/i.exec(name)
    if (dash && dash.index > 0) {
      asides.unshift(dash[1].trim())
      name = name.slice(0, dash.index).trim()
    }
    return [name, asides.join('  ·  ')]
  }

  function clock (ms) {
    if (!(ms >= 0)) return ''
    const s = Math.floor(ms / 1000)
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0')
  }

  function show (next) {
    const prev = song
    const same = !!(prev && next && prev.id === next.id)
    song = next
    if (!next) return
    const [name, aside] = splitTitle(next.title)
    el.title.textContent = name
    if (next.explicit) {
      const mark = document.createElement('span')
      mark.className = 'explicit'
      mark.textContent = 'E'
      el.title.appendChild(mark)
    }
    el.aside.textContent = aside
    el.artist.textContent = next.artist || ''
    // What the phone's decoder measured, as it says it; nothing when it measured nothing.
    el.format.textContent = next.format || ''
    el.lyricsName.textContent = name
    if (typeof next.lyricsOnTv === 'boolean' && next.lyricsOnTv !== lyricsSetting) {
      lyricsSetting = next.lyricsOnTv
      lyricsChosen = null
    }
    if (!prev || prev.art !== next.art) {
      const fromAbove = typeof next.index === 'number' && lastIndex >= 0 && next.index < lastIndex
      lay(next, fromAbove)
      glow(next.art)
    }
    if (typeof next.index === 'number') lastIndex = next.index
    if (!same || ((next.film && next.film.url) || '') !== film.url) setFilm(next.film)
    if (!same || JSON.stringify((prev && prev.words) || null) !== JSON.stringify(next.words || null)) setWords(next.words)
    tattoo(next)
    // A new song's hand sweeps back to its start rather than leaping there.
    if (!same) hand.free = false
  }

  // --- The print: each cover a sheet wiped in over the last, developing into its tones -----------------

  /** Now Playing's PrintSheet: the next rises from below; going back, the earlier one comes down from above. */
  function lay (next, fromAbove) {
    const url = next.art || ''
    const top = print.sheets[print.sheets.length - 1]
    if (top && top.url === url) return
    const first = !top
    const img = new Image()
    img.decoding = 'async'
    img.alt = ''
    const placed = new Promise((resolve) => {
      img.onload = () => resolve(true)
      img.onerror = () => resolve(false)
      // How long a next print is waited for before the wipe starts without it (PrintWaitMs).
      after(1.8).then(() => resolve(false))
    })
    if (url) img.src = url
    print.waiting = placed
    placed.then(() => {
      if (song && song.art !== url) return
      const node = document.createElement('div')
      node.className = 'sheet'
      node.appendChild(img)
      el.sheets.appendChild(node)
      const sheet = { url, node, img, fromAbove, wipe: new Fluid(first ? 1 : 0, 0.9, 1, 0.0008), develop: first && arrival.done ? 0 : 1, began: -1 }
      print.sheets.push(sheet)
      while (print.sheets.length > 3) print.sheets.shift().node.remove()
      if (!first) {
        sheet.began = now()
        sheet.wipe.go(1).then(() => {
          // All of it is over those beneath: they're gone.
          const at = print.sheets.indexOf(sheet)
          if (at > 0) print.sheets.splice(0, at).forEach((s) => s.node.remove())
        })
      } else if (!arrival.done) {
        // The first print develops as the song arrives.
        if (arrival.waveAt >= 0) sheet.began = arrival.waveAt
        else arrival.onWave.push(() => { sheet.began = now() })
      }
      print.up.to(1)
    })
  }

  /** The darkroom: the print comes up blown out to a high-key threshold, and settles into its tones. */
  function developFilter (d) {
    if (d <= 0.001) return 'none'
    const exposure = (1 + 0.25 * d) * (1 + 1.6 * d)
    return `brightness(${exposure.toFixed(3)}) grayscale(${(0.9 * d).toFixed(3)}) brightness(${(1 + 0.4286 * d).toFixed(3)}) contrast(${(1 + 6.1 * d).toFixed(3)})`
  }

  function drawPrint (dt, t) {
    for (const s of print.sheets) {
      const w = s.wipe.step(dt)
      if (s.began >= 0 && s.develop > 0) {
        // Tone, not movement: it comes up at the darkroom's own pace (1.9 s, after 0.45 s).
        const p = Math.min(1, Math.max(0, (t - s.began - 0.45) / 1.9))
        s.develop = 1 - editorial(p)
      }
      const clip = w >= 0.999 ? 'none' : s.fromAbove ? `inset(0 0 ${((1 - w) * 100).toFixed(2)}% 0)` : `inset(${((1 - w) * 100).toFixed(2)}% 0 0 0)`
      if (s.clip !== clip) { s.node.style.clipPath = clip; s.clip = clip }
      const scale = w >= 0.999 ? 'none' : `scaleX(${(1 + 0.035 * (1 - w)).toFixed(4)})`
      if (s.scale !== scale) {
        s.node.style.transform = scale
        s.node.style.transformOrigin = s.fromAbove ? '50% 0' : '50% 100%'
        s.scale = scale
      }
      const filter = developFilter(s.develop)
      if (s.filter !== filter) { s.img.style.filter = filter; s.filter = filter }
    }
  }

  /** The print's tattoo (Now Playing's microtype): the house and the song's place, its words, its name. */
  function tattoo (s) {
    const said = (s.words && s.words.lines ? s.words.lines.map((l) => (l[1] || '').trim()) : []).filter((l) => l && l.length < 40)
    const distinct = [...new Set(said)].slice(0, 9)
    const firstPart = (s.format || '').split(/\s{2,}/)[0].trim()
    const head = ['Aether', typeof s.index === 'number' ? 'Nº ' + s.index : '', s.year ? String(s.year) : (s.album || ''), firstPart]
    const middle = distinct.length ? distinct : [s.artist, s.album, s.duration ? clock(s.duration) : '', s.explicit ? 'Explicit' : ''].filter(Boolean)
    while (middle.length < 9) middle.push('')
    const tail = [splitTitle(s.title)[0], (s.artist || '').split(/,|&/)[0].trim(), s.duration ? clock(s.duration) : '']
    const cell = (text) => { const d = document.createElement('div'); d.textContent = text; return d }
    const row = (cls, items) => {
      const r = document.createElement('div')
      r.className = 'row ' + cls
      items.forEach((item) => r.appendChild(item))
      return r
    }
    el.micro.textContent = ''
    el.micro.appendChild(row('head', head.map(cell)))
    el.micro.appendChild(row('middle', [0, 1, 2].map((c) => {
      const col = document.createElement('div')
      middle.slice(c * 3, c * 3 + 3).forEach((text) => { const d = cell(text); d.className = 'line2'; col.appendChild(d) })
      return col
    })))
    el.micro.appendChild(row('tail', tail.map(cell)))
  }

  // --- The cover's light, far behind (Now Playing's SoftArt) ----------------------------------------------

  function glow (url) {
    if (!url || url === soft.src) return
    soft.src = url
    const img = new Image()
    img.onload = () => {
      if (soft.src !== url) return
      const canvas = document.createElement('canvas')
      canvas.width = canvas.height = 48
      canvas.className = 'soft'
      const c = canvas.getContext('2d')
      c.filter = 'blur(3px)'
      c.drawImage(img, -6, -6, 60, 60)
      el.soft.parentNode.insertBefore(canvas, el.soft.nextSibling)
      const layer = { canvas, alpha: new Fluid(0, 0.9, 1) }
      soft.layers.push(layer)
      layer.alpha.go(1).then(() => {
        const at = soft.layers.indexOf(layer)
        if (at > 0) soft.layers.splice(0, at).forEach((l) => l.canvas.remove())
      })
    }
    img.src = url
  }

  // --- The film -----------------------------------------------------------------------------------------

  function setFilm (next) {
    const url = (next && next.url) || ''
    film.url = url
    // It goes as the next cover arrives, at Now Playing's pace (Going), and the next rises once it has a frame.
    film.up.to(0, 0.34, 1)
    const video = el.video
    if (film.hls) { film.hls.destroy(); film.hls = null }
    video.pause()
    video.removeAttribute('src')
    video.load()
    if (!url) return
    video.onplaying = () => { if (film.url === url) film.up.to(1, 0.7, 1) }
    video.onerror = () => { if (film.url === url) film.up.to(0, 0.34, 1) }
    if (next.hls && window.Hls && window.Hls.isSupported()) {
      const hls = new window.Hls({ enableWorker: false, capLevelToPlayerSize: false })
      film.hls = hls
      hls.on(window.Hls.Events.MANIFEST_PARSED, () => {
        // The finest copy the screen can use, from its first frame: a short loop never runs long enough to climb.
        let best = 0
        hls.levels.forEach((l, i) => { if (l.height <= 1440 && l.bitrate >= hls.levels[best].bitrate) best = i })
        hls.currentLevel = best
        video.play().catch(() => {})
      })
      hls.on(window.Hls.Events.ERROR, (_, data) => { if (data && data.fatal && film.url === url) film.up.to(0, 0.34, 1) })
      hls.loadSource(url)
      hls.attachMedia(video)
    } else if (!next.hls || video.canPlayType('application/vnd.apple.mpegurl')) {
      video.src = url
      video.play().catch(() => {})
    }
  }

  // --- The words, full screen: Now Playing's Verse --------------------------------------------------

  /** Every other line, by its distance from the one being sung, and how far out of focus (phone dp). */
  const FALLOFF = [1, 0.74, 0.6, 0.48, 0.4, 0.34]
  const FALLOFF_BLUR = [0, 1, 1, 1.7, 2.4]
  const UNSUNG = 0.45
  /** The scale the phone's dp is drawn at here (the dial's): 0.249 rem to the dp. */
  const DP = 45.06 / 181

  function setWords (w) {
    el.lines.textContent = ''
    words.list = []
    words.focus = -2
    words.placed = false
    words.timed = !!(w && w.timed)
    el.lines.className = 'lines' + (w && !w.timed ? ' untimed' : '')
    el.credit.textContent = w && w.by ? 'Words: ' + w.by + (w.estimated ? '  ·  timed by AETHER' : '') : ''
    if (!w || !w.lines || w.instrumental) return
    w.lines.forEach(([start, text], i) => {
      const div = document.createElement('div')
      const gap = !text || !text.trim()
      div.className = gap ? 'line gap' : 'line'
      if (!gap) div.textContent = text
      el.lines.appendChild(div)
      const next = w.lines[i + 1]
      words.list.push({
        start: typeof start === 'number' ? start : -1,
        end: next && typeof next[0] === 'number' ? next[0] : Infinity,
        gap,
        el: div,
        alpha: new Fluid(0, 0.4, 1, 0.001),
        lit: new Fluid(UNSUNG, 0.4, 1, 0.001),
        scale: new Fluid(0.98, 0.4, 1, 0.0005),
        blur: -1,
        drawn: '',
      })
    })
  }

  const hasWords = () => words.list.some((w) => !w.gap)
  const lyricsOn = () => hasWords() && (lyricsChosen !== null ? lyricsChosen : lyricsSetting)

  /** The line being sung at [ms]: the last to have begun and not ended; -1 before the first. */
  function sungAt (ms) {
    let at = -1
    for (let i = 0; i < words.list.length; i++) {
      if (words.list[i].start >= 0 && words.list[i].start <= ms) at = i
      else if (words.list[i].start > ms) break
    }
    return at
  }

  function drawLyrics (positionMs, durationMs, dt) {
    const up = words.up.step(dt)
    if (up < 0.0015 && words.up.resting) {
      if (el.lyrics.style.visibility !== 'hidden') el.lyrics.style.visibility = 'hidden'
      return
    }
    // The page comes up over Now Playing, from the foot, as the phone's does.
    el.lyrics.style.visibility = 'visible'
    el.lyrics.style.transform = `translate3d(0, ${((1 - up) * window.innerHeight).toFixed(1)}px, 0)`
    if (!words.list.length) return
    let sung = -1
    let focus = -1
    if (words.timed) {
      sung = sungAt(positionMs)
      // The page moves a beat ahead of the singing, so the words are under the eye as they land.
      const next = words.list[sung + 1]
      const silence = next && sung >= 0 ? next.start - words.list[sung].end : 0
      const lead = Math.min(500, Math.max(350, silence))
      focus = sungAt(positionMs + lead)
    } else if (durationMs > 0) {
      // Words with no times are read at the song's pace, none of them lit as if it were being sung.
      focus = Math.min(words.list.length - 1, Math.floor((positionMs / durationMs) * words.list.length))
    }
    // Where the line being sung stands: the phone's (0.3 of its page, below its name), as a share of this screen.
    const anchor = words.timed ? window.innerHeight * 0.34 : window.innerHeight * 0.42
    if (focus !== words.focus) {
      words.focus = focus
      const line = words.list[Math.max(0, focus)].el
      const to = anchor - (words.timed ? line.offsetTop : line.offsetTop + line.offsetHeight / 2)
      if (!words.placed) { words.scroll.snap(to); words.placed = true } else words.scroll.to(to)
    }
    el.lines.style.transform = `translate3d(0, ${words.scroll.step(dt).toFixed(1)}px, 0)`
    const pxPerDp = DP * rem()
    for (let i = 0; i < words.list.length; i++) {
      const w = words.list[i]
      let alpha
      let lit = 1
      let scale = 1
      let blur = 0
      if (!words.timed) alpha = 0.85
      else {
        // Brightness follows the line being sung, never the one the page is heading for.
        const step = sung < 0 ? Math.min(i + 1, FALLOFF.length - 1) : Math.min(Math.abs(i - sung), FALLOFF.length - 1)
        const active = i === sung
        alpha = active ? 1 : FALLOFF[step]
        lit = active || (sung >= 0 && i < sung) ? 1 : UNSUNG
        scale = active ? 1 : 0.98
        blur = active ? 0 : FALLOFF_BLUR[Math.min(step, FALLOFF_BLUR.length - 1)]
      }
      w.alpha.to(alpha)
      w.lit.to(lit)
      w.scale.to(scale)
      const a = w.alpha.step(dt) * w.lit.step(dt)
      const s = w.scale.step(dt)
      const drawn = a.toFixed(3) + ':' + s.toFixed(4)
      if (drawn !== w.drawn) {
        w.el.style.opacity = a.toFixed(3)
        w.el.style.transform = s < 0.9999 ? `scale(${s.toFixed(4)})` : 'none'
        w.drawn = drawn
      }
      // A little out of focus, more the further off: set once per change, not every frame.
      if (blur !== w.blur) {
        w.el.style.filter = blur > 0 ? `blur(${(blur * pxPerDp).toFixed(2)}px)` : 'none'
        w.blur = blur
      }
    }
  }

  // --- The key, and the TV's remote ----------------------------------------------------------------

  function toggleLyrics () {
    if (!hasWords()) return
    lyricsChosen = !lyricsOn()
  }

  /** The remote: OK turns the words on and off; any other press shows where the key is. */
  function onKey (event) {
    const code = event.keyCode
    const name = event.key
    const ok = name === 'Enter' || name === 'Select' || name === 'Accept' || code === 13 || code === 23 || code === 66
    const arrow = /^Arrow/.test(name) || (code >= 37 && code <= 40) || (code >= 19 && code <= 22)
    if (!ok && !arrow) return
    if (!hasWords()) return
    key.until = now() + 6
    if (ok) {
      toggleLyrics()
      event.preventDefault()
      event.stopPropagation()
    }
  }

  window.addEventListener('keydown', onKey, true)

  function drawKey (dt, t, shake) {
    key.shown.to(hasWords() ? 1 : 0)
    const shown = key.shown.step(dt)
    key.lit.to(lyricsOn() ? 1 : 0)
    const lit = key.lit.step(dt)
    key.focus.to(t < key.until && shown > 0.5 ? 1 : 0)
    const focus = key.focus.step(dt)
    el.key.style.visibility = shown < 0.002 ? 'hidden' : 'visible'
    el.key.style.opacity = shown.toFixed(3)
    el.key.style.transform = `${shake}scale(${(1 + 0.08 * focus).toFixed(4)})`
    el.key.firstElementChild.style.opacity = Math.max(lit, focus * 0.55).toFixed(3)
    el.key.setAttribute('aria-pressed', String(lyricsOn()))
    el.keyWord.style.opacity = (focus * shown).toFixed(3)
    el.keyWord.style.transform = shake || 'none'
    const word = lyricsOn() ? 'Hide lyrics' : 'Lyrics'
    if (el.keyWord.textContent !== word) el.keyWord.textContent = word
  }

  // --- The dial: the phone's (core/designsystem/.../ClockDial.kt), standing on the right edge ----------

  /** ClockDial's own sizes, in the phone's dp, drawn at the dial's radius here (181 dp on the phone). */
  const DIAL = { radiusDp: 181, long: 13, short: 6, crown: 14, foot: 6, minor: 1, major: 1.4, inner: 7, hand: 1.8, bead: 2.6, past: 7, reach: 16 }
  const dialCtx = el.dial.getContext('2d')
  let dialDrawn = ''
  const hand = { shown: 0, free: true, travel: new Fluid(0, 0.55, 1, 1 / 900) }

  function drawDial (shown) {
    const box = el.dial.getBoundingClientRect()
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const w = Math.round(box.width * dpr)
    const h = Math.round(box.height * dpr)
    const sig = w + 'x' + h + ':' + shown.toFixed(4)
    if (sig === dialDrawn) return
    dialDrawn = sig
    if (el.dial.width !== w || el.dial.height !== h) { el.dial.width = w; el.dial.height = h }
    const c = dialCtx
    const crownOverR = DIAL.crown / DIAL.radiusDp
    const footOverR = DIAL.foot / DIAL.radiusDp
    // The box is the dial's, with room for the hand beyond its marks and for its foot at the edge.
    const r = Math.min(h / 2 / (1 + crownOverR), w / (1 + crownOverR + footOverR))
    const dp = r / DIAL.radiusDp
    const px = w - DIAL.foot * dp
    const py = h / 2
    // Turned to stand on the right edge: the phone's angle a points (-sin a, -cos a) here, so the hand
    // still goes round clockwise, from the foot of the arc, past its middle, to its top.
    const dir = (a) => [-Math.sin(a), -Math.cos(a)]
    c.clearRect(0, 0, w, h)
    c.lineCap = 'round'
    const marks = (alphaMinor, alphaMajor) => {
      for (let i = 0; i <= 60; i++) {
        const [dx, dy] = dir(Math.PI * (1 - i / 60))
        const major = i % 5 === 0
        const from = r - (major ? DIAL.long : DIAL.short) * dp
        c.strokeStyle = `rgba(255,255,255,${major ? alphaMajor : alphaMinor})`
        c.lineWidth = Math.max(dpr, (major ? DIAL.major : DIAL.minor) * dp)
        c.beginPath()
        c.moveTo(px + dx * from, py + dy * from)
        c.lineTo(px + dx * r, py + dy * r)
        c.stroke()
      }
    }
    marks(0.18, 0.4)
    if (shown > 0) {
      // The marks the hand has passed, brighter: the same marks, through a wedge to the hand.
      c.save()
      c.beginPath()
      c.moveTo(px, py)
      c.arc(px, py, r + DIAL.reach * dp, Math.PI / 2, Math.PI / 2 + Math.PI * shown + 0.0105, false)
      c.closePath()
      c.clip()
      marks(0.58, 0.92)
      c.restore()
    }
    // A hairline arc just inside the marks, so the dial reads as one instrument.
    const inner = r - DIAL.long * dp - DIAL.inner * dp
    c.strokeStyle = 'rgba(255,255,255,0.08)'
    c.lineWidth = Math.max(dpr, DIAL.minor * dp)
    c.beginPath()
    c.arc(px, py, inner, Math.PI / 2, Math.PI * 1.5, false)
    c.stroke()
    // The hand: thin, red, from inside the marks to just past them, with a bead at its foot.
    const [hx, hy] = dir(Math.PI * (1 - shown))
    const fx = px + hx * r * 0.74
    const fy = py + hy * r * 0.74
    c.strokeStyle = '#ff3b30'
    c.lineWidth = DIAL.hand * dp
    c.beginPath()
    c.moveTo(fx, fy)
    c.lineTo(px + hx * (r + DIAL.past * dp), py + hy * (r + DIAL.past * dp))
    c.stroke()
    c.fillStyle = '#ff3b30'
    c.beginPath()
    c.arc(fx, fy, DIAL.bead * dp, 0, 2 * Math.PI)
    c.fill()
  }

  /** ClockDial's playhead: the hand is where the song is, but a jump is travelled, not leapt. */
  function stepHand (fraction, dt) {
    if (hand.free && Math.abs(hand.shown - fraction) > 0.015) {
      hand.free = false
      hand.travel.snap(hand.shown)
    }
    if (hand.free) hand.shown = fraction
    else {
      hand.travel.to(fraction)
      hand.shown = hand.travel.step(dt)
      if (hand.travel.resting) hand.free = true
    }
    return hand.shown
  }

  // --- The arrival: the name on black, and the song coming up through it --------------------------------

  /**
   * KeepFlight's arrival, from the foot of the screen: a bright mouth opens at the middle of the foot,
   * the screen is drawn down after it and springs back, and one wave runs up through everything (as
   * AirDrop's does), the song showing where it has passed. The wave is art/PullRipple.kt's: each point
   * pushed along the line from the mouth by a damped swing that reaches it later the further it is, and
   * its crest catching the light. Here the screen's pieces each move as the wave reaches them.
   */
  const arrival = {
    started: -1, waveAt: -1, done: false, onWave: [], pull: new Fluid(0, 0.2, 1), glow: new Fluid(0, 0.2, 1),
    rise: new Fluid(0, 0.62, 0.84), shown: new Fluid(1, 0.6, 1), drawn: '',
  }
  const WAVE = { amplitudeDp: 30, frequency: 14, decay: 2.9, speedOfHeight: 1.05, shine: 0.6, seconds: 1.7 }
  const veilCtx = el.veil.getContext('2d')

  async function arrive () {
    if (arrival.started >= 0 && !arrival.done) return
    arrival.started = now()
    arrival.done = false
    arrival.waveAt = -1
    arrival.shown.snap(1)
    await after(0.11)
    arrival.waveAt = now()
    arrival.onWave.splice(0).forEach((f) => f())
    arrival.rise.snap(1)
    arrival.rise.to(0)
    ;(async () => {
      await arrival.pull.go(1, 0.2, 1)
      await arrival.pull.go(0, 0.55, 0.62)
    })()
    ;(async () => {
      await arrival.glow.go(1, 0.2, 1)
      await arrival.glow.go(0, 0.9, 1)
    })()
    await after(WAVE.seconds)
    arrival.done = true
  }

  /** Back to the name on black: nothing has played for a while. */
  function rest () {
    arrival.started = -1
    arrival.waveAt = -1
    arrival.done = false
    arrival.shown.to(1)
  }

  /** How far a point [dist] px from the mouth is pushed toward it now: the pull, and the wave's swing. */
  function pushAt (dist, t, H) {
    const pull = arrival.pull.value * H * 0.1 * Math.exp(-dist / (H * 0.5))
    if (arrival.waveAt < 0 || arrival.done) return pull
    const local = t - arrival.waveAt - dist / (WAVE.speedOfHeight * H)
    if (local <= 0) return pull
    const dp = (45.06 * window.innerWidth / 192) / DIAL.radiusDp
    return pull + WAVE.amplitudeDp * dp * Math.sin(WAVE.frequency * local) * Math.exp(-WAVE.decay * local)
  }

  /** Each piece of the screen moved as the wave and the pull have it, about its middle. */
  const pieces = [el.mark, el.heading, el.print, el.song, el.dialBox]
  let rested = null
  function shakeOf (node, t) {
    const W = window.innerWidth
    const H = window.innerHeight
    // Where it stands at rest, measured once as the wave sets off.
    const [cx, cy] = rested.get(node)
    const dx = cx - W / 2
    const dy = cy - H
    const dist = Math.hypot(dx, dy) || 1
    const push = pushAt(dist, t, H)
    return Math.abs(push) < 0.05 ? '' : `translate3d(${(-dx / dist * push).toFixed(2)}px, ${(-dy / dist * push).toFixed(2)}px, 0) `
  }

  function shake (t) {
    const moving = arrival.started >= 0 && (!arrival.done || !arrival.pull.resting)
    if (!moving) rested = null
    else if (!rested) {
      rested = new Map()
      for (const node of [...pieces, el.key]) {
        const r = node.getBoundingClientRect()
        rested.set(node, [r.left + r.width / 2, r.top + r.height / 2])
      }
    }
    for (const node of pieces) {
      const s = moving ? shakeOf(node, t) : ''
      if (node.shaken !== s) { node.style.transform = s; node.shaken = s }
    }
    return moving ? shakeOf(el.key, t) : ''
  }

  function drawVeil (dt, t) {
    const pull = arrival.pull.step(dt)
    const glowNow = arrival.glow.step(dt)
    const shown = arrival.shown.step(dt)
    const waiting = arrival.started < 0
    if (arrival.done && glowNow < 0.002 && pull < 0.002) {
      if (el.veil.style.display !== 'none') el.veil.style.display = 'none'
      return
    }
    el.veil.style.display = 'block'
    el.veil.style.opacity = waiting ? shown.toFixed(3) : '1'
    const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
    const W = Math.round(window.innerWidth * dpr)
    const H = Math.round(window.innerHeight * dpr)
    const tw = arrival.waveAt >= 0 ? t - arrival.waveAt : -1
    const sig = [W, H, pull.toFixed(4), glowNow.toFixed(4), tw.toFixed(3), waiting, arrival.done].join(':')
    if (sig === arrival.drawn) return
    arrival.drawn = sig
    if (el.veil.width !== W || el.veil.height !== H) { el.veil.width = W; el.veil.height = H }
    const c = veilCtx
    const mx = W / 2
    const my = H
    const speed = WAVE.speedOfHeight * H
    c.setTransform(1, 0, 0, 1, 0, 0)
    c.globalCompositeOperation = 'source-over'
    c.clearRect(0, 0, W, H)
    if (!arrival.done) {
      c.fillStyle = '#000'
      c.fillRect(0, 0, W, H)
      // The name, drawn toward the mouth as the screen is, and moved by the wave as it passes.
      const size = 15 * W / 192
      const cx = W / 2
      const cy = H / 2 - 1.2 * W / 192
      const dist = Math.hypot(cx - mx, cy - my)
      const push = pushAt(dist / dpr, t, H / dpr) * dpr
      const reach = Math.exp(-dist / (H * 0.5))
      c.save()
      c.translate(cx, cy + push)
      c.scale(1, 1 + 0.05 * pull * reach)
      c.fillStyle = '#fff'
      c.font = `${size}px 'Pinyon Script'`
      c.textAlign = 'center'
      c.textBaseline = 'middle'
      c.fillText('Aether', 0, 0)
      c.restore()
      if (tw > 0) {
        // Where the wave has been, the song shows through: a little behind its front, softly.
        const front = speed * tw
        const soft = H * 0.18
        if (front > 1) {
          const g = c.createRadialGradient(mx, my, 0, mx, my, front)
          g.addColorStop(0, 'rgba(0,0,0,1)')
          g.addColorStop(Math.max(0, (front - soft) / front), 'rgba(0,0,0,1)')
          g.addColorStop(1, 'rgba(0,0,0,0)')
          c.globalCompositeOperation = 'destination-out'
          c.fillStyle = g
          c.fillRect(0, 0, W, H)
          c.globalCompositeOperation = 'source-over'
        }
      }
    }
    // The light where the wave begins: a bright mouth at the middle of the foot. On the phone it spans the
    // foot of a tall screen (0.3 of its width); a wide one has it the same share of its height instead,
    // so it's a drop of light and not a wash.
    if (glowNow > 0.002) {
      const sigma = 0.3 * (1080 / 2392) * H
      const radius = sigma * 3
      const g = c.createRadialGradient(mx, my, 0, mx, my, radius)
      for (const f of [0, 0.12, 0.25, 0.4, 0.55, 0.7, 0.85]) {
        const d = f * radius
        const a = 0.85 * glowNow * Math.exp(-(d * d) / (2 * sigma * sigma))
        g.addColorStop(f, `rgba(255,255,255,${a.toFixed(4)})`)
      }
      // To nothing at its edge, so the square it's painted in never shows.
      g.addColorStop(1, 'rgba(255,255,255,0)')
      c.fillStyle = g
      c.fillRect(mx - radius, my - radius, radius * 2, radius)
    }
    // Only the very top of the wave catches the light, so it's a rim and not a wash.
    if (tw > 0 && tw < WAVE.seconds) {
      for (let k = 0; k < 3; k++) {
        const at = (Math.PI / 2 + 2 * Math.PI * k) / WAVE.frequency
        const radius = speed * (tw - at)
        if (radius <= 0) continue
        const strength = WAVE.shine * Math.exp(-WAVE.decay * at) * Math.min(1, (WAVE.seconds - tw) / 0.25)
        if (strength < 0.01) continue
        const half = speed * 0.06
        const g = c.createRadialGradient(mx, my, Math.max(0, radius - half), mx, my, radius + half)
        g.addColorStop(0, 'rgba(255,255,255,0)')
        g.addColorStop(0.5, `rgba(255,255,255,${strength.toFixed(4)})`)
        g.addColorStop(1, 'rgba(255,255,255,0)')
        c.fillStyle = g
        c.fillRect(0, 0, W, H)
      }
    }
  }

  // --- The clock: Google's player on a TV, or a stand-in in the preview ---------------------------------

  let readClock = () => ({ t: 0, d: 0, state: 'IDLE' })

  // --- Every frame ----------------------------------------------------------------------------------

  let lastFrame = now()
  let idleSince = now()
  let shownNow = ''
  let shownLeft = ''

  function frame () {
    const t = now()
    // At most a thirtieth of a second a frame: a long frame holds what's moving rather than skipping it.
    // (A computer's preview out of sight draws a frame a second: there it keeps to the clock instead.)
    const dt = Math.min(preview && virtual === null ? 1 : 1 / 30, Math.max(0, t - lastFrame))
    lastFrame = t
    for (let i = timers.length - 1; i >= 0; i--) if (timers[i].at <= t) timers.splice(i, 1)[0].resolve()
    const { t: at, d, state } = readClock()
    const durationMs = d > 0 ? d * 1000 : (song && song.duration) || 0
    const positionMs = Math.max(0, at * 1000)

    // Back to the name on black once nothing has played for a while; a song brings the stage back up.
    if (state !== 'IDLE') idleSince = t
    if (song && state !== 'IDLE' && arrival.started < 0 && !query.has('boot')) arrive()
    if (arrival.started >= 0 && arrival.done && state === 'IDLE' && t - idleSince > 4) rest()

    // The stage rises a little as the song arrives, from the foot where it came in.
    const rise = arrival.rise.step(dt)
    el.stage.style.transform = Math.abs(rise) > 0.0005 ? `translate3d(0, ${(rise * 2.4 * rem()).toFixed(2)}px, 0) scale(${(1 - 0.015 * rise).toFixed(4)})` : 'none'

    for (const layer of soft.layers) layer.canvas.style.opacity = (0.34 * layer.alpha.step(dt)).toFixed(3)

    const filmUp = film.up.step(dt)
    el.film.style.opacity = filmUp.toFixed(3)
    if (film.url) {
      if (state === 'PAUSED' && !el.video.paused) el.video.pause()
      else if (state !== 'PAUSED' && el.video.paused && el.video.readyState >= 2) el.video.play().catch(() => {})
    }

    // The print gives way to the film, and comes back when that goes.
    const printUp = print.up.step(dt)
    el.print.style.opacity = (printUp * (1 - Math.min(1, filmUp))).toFixed(3)
    drawPrint(dt, t)

    const place = song && song.count > 1 && song.index ? song.index + ' of ' + song.count : ''
    if (el.place.textContent !== place) el.place.textContent = place

    const fraction = durationMs > 0 ? Math.min(1, positionMs / durationMs) : 0
    drawDial(stepHand(fraction, dt))
    const second = Math.floor(positionMs / 1000) * 1000
    const nowText = clock(second) || '0:00'
    const leftText = durationMs > 0 ? '−' + clock(Math.max(0, durationMs - second)) : ''
    if (nowText !== shownNow) { el.now.textContent = nowText; shownNow = nowText }
    if (leftText !== shownLeft) { el.left.textContent = leftText; shownLeft = leftText }

    words.up.to(lyricsOn() ? 1 : 0)
    drawLyrics(positionMs, durationMs, dt)
    drawKey(dt, t, shake(t))
    drawVeil(dt, t)
    schedule()
  }

  /** The next frame, once: whichever of its callers comes first draws it, and the other finds it drawn. */
  let ticket = 0
  function schedule () {
    if (virtual !== null) return
    const mine = ++ticket
    const go = () => {
      if (mine !== ticket) return
      ticket++
      frame()
    }
    requestAnimationFrame(go)
    // A tab drawn by nobody (a computer's preview behind another window) still moves on.
    if (preview) setTimeout(go, 50)
  }

  // --- On a TV --------------------------------------------------------------------------------------

  function fromMedia (media) {
    if (!media) return null
    const m = media.metadata || {}
    const id = (media.customData && media.customData.mediaId) || media.contentId
    const images = m.images || []
    return {
      id,
      title: m.title || '',
      artist: m.artist || m.albumArtist || '',
      album: m.albumName || '',
      art: images.length ? images[images.length - 1].url : '',
      duration: media.duration ? media.duration * 1000 : 0,
    }
  }

  function startCast () {
    const context = cast.framework.CastReceiverContext.getInstance()
    const player = context.getPlayerManager()
    context.setLoggerLevel(cast.framework.LoggerLevel.NONE)
    const states = cast.framework.messages.PlayerState
    readClock = () => {
      const state = player.getPlayerState()
      return {
        t: player.getCurrentTimeSec() || 0,
        d: player.getDurationSec() || 0,
        state: state === states.PLAYING ? 'PLAYING' : state === states.PAUSED ? 'PAUSED' : state === states.BUFFERING ? 'BUFFERING' : 'IDLE',
      }
    }
    const follow = () => {
      const base = fromMedia(player.getMediaInformation())
      if (!base) return
      const sent = stages.get(base.id)
      if (!song || song.id !== base.id || (sent && !song.fromStage)) show(sent ? Object.assign({}, base, sent, { fromStage: true }) : base)
    }
    context.addCustomMessageListener(NS, (event) => {
      let m = event.data
      if (typeof m === 'string') { try { m = JSON.parse(m) } catch (_) { return } }
      if (!m || m.type !== 'stage' || !m.id) return
      stages.set(m.id, m)
      if (stages.size > 24) stages.delete(stages.keys().next().value)
      if (song && song.id === m.id) show(Object.assign({}, song, m, { fromStage: true }))
      else follow()
    })
    player.addEventListener(cast.framework.events.EventType.MEDIA_STATUS, follow)
    player.addEventListener(cast.framework.events.EventType.PLAYER_LOAD_COMPLETE, follow)
    const hello = (senderId) => { try { context.sendCustomMessage(NS, senderId, { type: 'hello' }) } catch (_) {} }
    context.addEventListener(cast.framework.system.EventType.READY, () => hello(undefined))
    context.addEventListener(cast.framework.system.EventType.SENDER_CONNECTED, (e) => hello(e.senderId))
    const options = new cast.framework.CastReceiverOptions()
    options.customNamespaces = { [NS]: cast.framework.system.MessageType.JSON }
    context.start(options)
  }

  // --- On a computer, for designing it ------------------------------------------------------------------

  function startPreview () {
    const verse = [
      'Light comes in under the door', 'and the kettle sings its two notes', '',
      'We left the windows open all night', 'the city breathing on the glass', 'Say it once and say it slow',
      'there is nowhere else to go', '', 'Hold the morning like a cup', 'warm in both your hands',
      'the room is ours until it isn\'t', 'and then it is again', '', 'Light comes in under the door',
    ]
    const songs = [
      {
        id: 'one', title: 'A Room for the Morning (feat. Somebody)', artist: 'The Quiet Hours', album: 'Weather Report',
        year: 2026, index: 3, count: 11, format: 'OPUS   48 kHz   160 kbps', art: '../assets/media/earth.jpg', duration: 214000,
        film: query.has('film') ? { url: '../assets/media/sea.mp4', hls: false } : null,
        words: query.has('plain') ? null : { timed: true, by: 'LRCLIB', lines: verse.map((text, i) => [9000 + i * 4200, text]) },
        lyricsOnTv: query.has('words'),
      },
      {
        id: 'two', title: 'Shoreline', artist: 'The Quiet Hours', album: 'Weather Report', year: 2026, index: 4, count: 11,
        format: 'AAC   44.1 kHz   256 kbps', art: '../assets/media/share.jpg', duration: 187000, explicit: true,
        film: null, words: null, lyricsOnTv: query.has('words'),
      },
    ]
    let at = 0
    let startedAt = now() - Number(query.get('at') || 31)
    const paused = query.has('paused')
    readClock = () => ({ t: Math.min(now() - startedAt, songs[at].duration / 1000), d: songs[at].duration / 1000, state: paused ? 'PAUSED' : 'PLAYING' })
    window.aether = {
      next () { at = (at + 1) % songs.length; startedAt = now() - 12; show(songs[at]) },
      back () { at = (at + songs.length - 1) % songs.length; startedAt = now() - 12; show(songs[at]) },
      lyrics () { toggleLyrics() },
      remote (name) { onKey({ key: name, keyCode: 0, preventDefault () {}, stopPropagation () {} }) },
    }
    if (query.has('boot')) return
    const freeze = query.get('freeze')
    if (query.has('still')) {
      arrival.started = 0
      arrival.done = true
      arrival.shown.snap(0)
    }
    if (freeze === null) {
      show(songs[0])
      return
    }
    // Held at one moment of the arrival: all of it worked out to there, a frame at a time.
    virtual = 0
    startedAt = -Number(query.get('at') || 31)
    show(songs[0])
    // A turn of the event loop that a hidden tab doesn't hold back (as it does its timers).
    const channel = new MessageChannel()
    const turn = () => new Promise((resolve) => { channel.port2.onmessage = resolve; channel.port1.postMessage(0) })
    Promise.all([document.fonts.ready, print.waiting]).then(async () => {
      const until = Number(freeze)
      while (virtual < until) {
        virtual = Math.min(until, virtual + 1 / 60)
        frame()
        // What the frame set going (the arrival's next step, a spring come to rest) runs before the next.
        await turn()
      }
    })
  }

  // --- Begin ----------------------------------------------------------------------------------------

  if (preview) startPreview()
  else {
    const s = document.createElement('script')
    s.src = CAF
    s.onload = startCast
    document.head.appendChild(s)
  }
  if (virtual === null) schedule()
})()
