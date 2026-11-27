// AETHER: BitChord's Automix (app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt,
// github.com/kushagrasinghx/BitChord): its analysis kept word for word; what fed it from BitChord's own
// audio cache (renditions, an opening-only first pass, catalogue matches) replaced by [AnalysisAudio].
/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package app.aether.player.automix

import android.content.Context
import android.media.MediaDataSource
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import app.aether.automix.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import java.util.Locale

/**
 * Where the analyser reads a song's audio: the whole of it, as it is on the phone (a kept song, the
 * phone's own music) or once Automix has fetched it (a stream, into its own cache).
 */
interface AnalysisAudio {
    /**
     * [trackId]'s audio, ready to read now, or null while it isn't on the phone yet; asked again on
     * the next tick. Asking is what starts fetching it. Never blocks.
     */
    fun copyOf(trackId: String): AnalysisCopy?
}

/** One readable copy of a song: [key] names that copy, [open] gives a fresh source over it each time. */
class AnalysisCopy(val key: String, val open: () -> MediaDataSource?)

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
@UnstableApi
class TrackAnalyzer(
    private val context: Context,
    private val audio: AnalysisAudio,
    /** How hard the models may work: threads and priority. */
    private val performance: () -> AutomixPerformanceMode = { AutomixPerformanceMode.BALANCED },
    /** Whether Automix wants analysis now; off, nothing new starts and a pass in flight stops. */
    private val wanted: () -> Boolean = { true },
) {

    private val tracker = BeatTracker(
        modelPath = {
            java.io.File(context.filesDir, BeatTracker.RETIRED_MODEL_ASSET).delete()
            unpackedAsset(BeatTracker.MODEL_ASSET)
        },
        inferenceThreads = { if (warm) 1 else performance().inferenceThreads },
    )
    private val vocals = VocalTracker(
        modelPath = { unpackedAsset(VocalTracker.MODEL_ASSET) },
        inferenceThreads = { if (warm) 1 else performance().inferenceThreads },
    )

    init {
        // The shared trackers report model failures and their timings through
        // this; with no sink they went nowhere on the phone, which is how a
        // beat model that found nothing went unnoticed.
        AnalysisLog.install { message, error -> Log.w(TAG, message, error) }
    }

    /**
     * A model's weights on disk, unpacked from assets the first time they are
     * asked for.
     *
     * ONNX Runtime wants a file it can memory-map, and an asset inside the APK
     * is not one. This used to live inside each tracker; it moved out when they
     * became shared with the desktop build, which unpacks from the jar instead.
     */
    private fun unpackedAsset(name: String): String {
        val file = java.io.File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * Whether analysis is switched off right now: Automix is off. Read fresh at every entry point
     * and between the stages of a pass in flight, so switching it off stops the work already
     * running as well as the work not yet queued (BitChord's guard read its Listen Together party).
     */
    private val stopped: Boolean get() = !wanted()

    /** The last thermal status the system reported; see [overheated]. */
    @Volatile private var thermalStatus = 0

    /**
     * Registered on first use rather than at construction: this class is a field initializer on
     * the playback service, which runs before the service has a base context to ask for a service.
     */
    private val thermalWatch by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.let { power ->
                thermalStatus = power.currentThermalStatus
                power.addThermalStatusListener(Runnable::run) { thermalStatus = it }
            }
        }
    }

    /**
     * Whether the phone has asked apps to shed load. No new pass is started
     * while it has: a pass is tens of seconds of decoding and inference, and
     * started on a phone already throttled to half its clocks it is the load
     * that keeps it there — measured, a session of back-to-back analyses held
     * a phone at SEVERE with every frame of the UI missing its vsync. A track
     * left unmeasured gets a plain crossfade, and is measured on a later tick
     * once the phone has cooled. A pass already running finishes.
     *
     * AETHER: refused outright only at CRITICAL. A phone being charged sits at
     * SEVERE for as long as it charges (the A059 did, measured 2026-11-17), and
     * refusing there left every song unmeasured, every transition a plain
     * crossfade. At SEVERE a pass runs gently instead ([warm]): one thread, at
     * background priority, and never back to back, which is the load BitChord
     * measured holding a phone there.
     */
    private val overheated: Boolean
        get() {
            thermalWatch
            return thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL ||
                (warm && SystemClock.elapsedRealtime() - lastPassEndedAt < WARM_REST_MS)
        }

    /** The phone is hot, short of critical: passes run one at a time, gently. See [overheated]. */
    private val warm: Boolean
        get() {
            thermalWatch
            return thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }

    /** When the last pass finished, so a warm phone rests between them. */
    @Volatile private var lastPassEndedAt = 0L

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore { context.filesDir }

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aether-automix-analysis").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        executor.execute {
            val stored = store.load(trackId) ?: return@execute
            Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
            results.putIfAbsent(trackId, stored)
        }
    }

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] for analysis if it isn't done or in flight. Cheap to call on every tick: a
     * track measured before (in any session) is restored from the store, and one whose audio isn't
     * on the phone yet is asked for and looked at again next tick.
     *
     * One pass, over the whole track, once its audio is all there: AETHER's source fetches a song
     * whole (BitChord's analysed an opening first, while its cache filled as the song played).
     */
    fun request(trackId: String, durationSeconds: Double, urgent: Boolean = false) {
        if (trackId.isBlank()) return
        if (stopped) return
        if (trackId in running) {
            // Already waiting its turn: a mix now waiting on it moves it to the front.
            if (urgent) synchronized(waiting) { waiting.firstOrNull { it.trackId == trackId }?.urgent = true }
            return
        }
        // A track measured in an earlier session costs nothing to restore, so only the work below
        // waits for the phone to cool.
        restoreOnce(trackId)
        if (overheated) return
        val recorded = results[trackId]
        if (recorded != null && recorded.isUsable) return
        val copy = audio.copyOf(trackId) ?: return
        if (recorded != null && copy.key in triedRenditions) return
        if (!running.add(trackId)) return

        // AETHER: passes wait in line, and the one a mix is waiting on ([urgent]: the song coming next,
        // moved there a moment ago, say) goes first, ahead of songs that were next before it. BitChord's
        // single thread took them strictly in the order asked.
        synchronized(waiting) { waiting += Waiting(trackId, durationSeconds, urgent, nextTicket++) }
        executor.execute(::runNext)
    }

    /** A pass waiting for the thread. */
    private class Waiting(val trackId: String, val durationSeconds: Double, @Volatile var urgent: Boolean, val ticket: Long)

    private val waiting = ArrayList<Waiting>()
    private var nextTicket = 0L

    /** One task per request: each runs the most pressing pass still waiting (urgent first, then oldest). */
    private fun runNext() {
        val next = synchronized(waiting) {
            val pick = mostPressing(waiting, { it.urgent }, { it.ticket }) ?: return
            waiting.remove(pick)
            pick
        }
        runPass(next.trackId, next.durationSeconds)
    }

    private fun runPass(trackId: String, durationSeconds: Double) {
        run {
            try {
                Process.setThreadPriority(
                    if (performance() == AutomixPerformanceMode.EFFICIENT || warm) {
                        Process.THREAD_PRIORITY_BACKGROUND
                    } else {
                        Process.THREAD_PRIORITY_DEFAULT
                    },
                )
                val landed = results[trackId]
                if (landed != null && landed.isUsable) return@run
                if (stopped) return@run
                val outcome = analyze(trackId, durationSeconds)
                val whole = outcome.analysis
                if (whole != null) {
                    results[trackId] = whole
                    shortDecodes.remove(trackId)
                    store.save(trackId, whole)
                    restoreAttempted.add(trackId)
                } else if (outcome.decodedShort &&
                    shortDecodes.merge(trackId, 1, Int::plus)!! >= MAX_SHORT_DECODE_ATTEMPTS
                ) {
                    Log.w(TAG, "Giving up on $trackId after $MAX_SHORT_DECODE_ATTEMPTS short decodes")
                    results[trackId] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = trackId,
                        duration = durationSeconds,
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Analysis of $trackId failed", error)
                results[trackId] = TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = durationSeconds,
                )
            } finally {
                lastPassEndedAt = SystemClock.elapsedRealtime()
                running.remove(trackId)
                if (running.isEmpty()) {
                    tracker.release()
                    vocals.release()
                }
            }
        }
    }

    /**
     * What a whole-track pass came back with.
     *
     * [decodedShort] is the difference between "this copy is broken, strike it"
     * and "there was no copy to read", which the caller counts very differently:
     * three strikes writes a track off for the session. Conflating the two spent
     * all three in 922ms on a track whose only complete copy had just been
     * rejected — the following two attempts decoded nothing because there was
     * nothing left to decode, and were counted as though they had tried.
     */
    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false)

    /**
     * What Pass 1 came back with.
     *
     * [decodedShort] has to survive the return rather than collapsing into a null [features]: it is
     * the same distinction [WholeTrack.decodedShort] draws, between a broken copy and no copy, and
     * only one of the two is a strike.
     */
    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
    )

    /**
     * Decodes the whole track and reduces it to DSP features.
     *
     * A method rather than a block in [analyze] for a reason that is about memory, not tidiness —
     * see the call site. Everything it decodes is dead by the time it returns, and returning is
     * what makes that true of the heap as well as of the program.
     */
    private fun structure(
        trackId: String,
        copy: AnalysisCopy,
        effectiveDuration: Double,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = copy.open()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) { stopped } }
            ?: return Structural(null)
        val (pcm, _) = decoded

        // A decode that stops early is indistinguishable, downstream, from a
        // track that simply goes quiet: [TrackFeatures] is handed the
        // container's full duration alongside a short buffer, reads the
        // difference as trailing silence, and puts the mix-out anchor where the
        // bytes ran out. Nothing about the result looks wrong — it is a complete
        // analysis with a plausible contentEnd — and the audible symptom is the
        // track being faded out minutes early. Refused outright rather than
        // published, because a missing analysis degrades to a plain crossfade
        // while a confidently wrong one does not degrade at all.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    // The two causes are different enough to be worth naming. A
                    // cached rendition stops at the first hole read-ahead left in
                    // it; a file on the device has no holes, so a short decode
                    // there means the container itself is truncated or damaged.
                    "container: cached with holes, or a truncated file?",
            )
            // Both halves of this are about *renditions*, so both are conditional
            // on there being one. A local file has no sibling copy to be routed to
            // instead and is not ours to delete either way; the three-strike count
            // in [request] is the whole bound there, and it is enough, because a
            // file on disk that decodes short will decode short again.
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        return Structural(TrackFeatures.analyze(samples, effectiveDuration))
    }

    /**
     * The whole-track pass. A null [WholeTrack.analysis] means "not now, try
     * again"; see the short-decode guard below, which is the one condition that
     * produces a confident-looking analysis that is wrong by minutes rather than
     * merely absent, and the only one that counts as a strike.
     */
    private fun analyze(trackId: String, durationSeconds: Double): WholeTrack {
        val copy = audio.copyOf(trackId) ?: return WholeTrack(null)
        // Recorded before the outcome is known, because what this gates is
        // whether a *written-off* track is worth reopening, and the answer is
        // only ever "yes" for a copy that has not been read yet. Recording it on
        // success alone would leave a failure looking permanently reopenable and
        // re-decode the same file on every tick.
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        // Pass 1 (Phase 1, DSP-only): the analyzer needs the whole track — the energy curve,
        // phrase structure and mix-out anchor all read the tail, not just a window of it — at its
        // own low sample rate, so this is a much smaller decode than a full-rate pass would be.
        //
        // In a frame of its own, and handing back only the features, because of what it allocates
        // to get them: the whole track decoded to mono at the container's rate (35 MB for a
        // 3.5-minute song) plus the resampled copy the DSP reads (8 MB). Neither is touched again
        // after this line, but a local holding either stays reachable to the end of the method, and
        // the rest of the method is Pass 2 — the most allocation-heavy part of the analysis.
        // Measured on the API 28 emulator: those two buffers were 43 MB of an 82 MB live set, still
        // held while the models ran, in a process that was reaching a 256 MB heap limit and had
        // died on it. Returning is what releases them — a `val` cannot be nulled, and a narrower
        // scope alone does not make ART treat one as dead.
        val structural = structure(trackId, copy, effectiveDuration)
        // A party started while Pass 1 was decoding. Dropped as "no result"
        // rather than as a failure — the track was never measured, so nothing
        // should be recorded against it and nothing should stop it being
        // measured properly once the party ends.
        //
        // Read *before* the two lines below, not after, because an abort comes
        // back from [structure] as a decode that produced nothing, and that is
        // exactly what those lines write a permanent "cannot be analysed" for.
        if (stopped) return WholeTrack(null)
        if (structural.decodedShort) return WholeTrack(null, decodedShort = true)
        val features = structural.features ?: return WholeTrack(empty(trackId, effectiveDuration))

        // Pass 2 (Phases 2 and 3, models): the Beat This! grid and the open-unmix vocal mask, over
        // the opening and the ending only. A transition only ever reads the tail of the outgoing
        // track and the head of the incoming one, and a track is both of those at different
        // moments, so the middle is never decoded for this. Both models read the same decoded
        // window, so its stereo buffer is paid for once. The spans match the loop mix's searches:
        // its incoming loop is looked for in the first 45 s, its outgoing one in the last 60.
        val span = modelWindowSeconds()
        // Spans that the windows tile exactly, overlaps included: one window more
        // here is a whole extra pass of both models.
        val headEnd = minOf(effectiveDuration, HEAD_MODEL_WINDOWS * span - (HEAD_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, effectiveDuration - (TAIL_MODEL_WINDOWS * span - (TAIL_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))
        val regions = ArrayList<Region>()
        for ((from, to) in modelWindows(headEnd, tailStart, effectiveDuration, span)) {
            // Checked between windows: an abort leaves a window missing, which
            // is indistinguishable from one the models found nothing in — and
            // that would be published, and persisted, with the DSP estimate
            // standing in. A result assembled out of a half-finished analysis
            // is worse than none.
            if (stopped) return WholeTrack(null)
            region(openSource, from, to, features)?.let(regions::add)
        }
        if (stopped) return WholeTrack(null)

        val beats = seamBeats(regions)
        val headSide = beatSide(beats, 0.0, headEnd)
        val tailSide = beatSide(beats, tailStart, effectiveDuration)
        val headGrid = headSide?.toGrid()
        val tailGrid = tailSide?.toGrid()

        // The tail governs where the outgoing track is mixed out, so it takes precedence; the
        // head is what a track uses when it is the *incoming* side of a different transition.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "grid=${if (leading != null) "model" else "dsp"} " +
                "beats=${headGrid?.beats?.size ?: 0}+${tailGrid?.beats?.size ?: 0} " +
                "downbeats=${headGrid?.downbeats?.size ?: 0}+${tailGrid?.downbeats?.size ?: 0} " +
                "vocalMask=${if (regions.any { it.vocalMask != null }) "model" else "dsp"} " +
                "windows=${regions.size} vocalWindows=${regions.count { it.vocalWindow != null }}",
        )

        return WholeTrack(
                TrackAnalysis(
                    status = TrackAnalysis.STATUS_READY,
                    trackId = trackId,
                    duration = effectiveDuration,
                    contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
                    bpm = leading?.bpm ?: features.bpm,
                beatInterval = leading?.beatInterval ?: features.beatInterval,
                beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
                downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                    .ifEmpty { features.downbeats }
                    .sorted(),
                firstBeat = headGrid?.firstBeat ?: features.firstBeat,
                phraseBoundaries = features.phraseBoundaries,
                key = features.key,
                keyConfidence = features.keyConfidence,
                audibleStartTime = features.audibleStartTime,
                pickupTime = features.pickupTime,
                introEndTime = features.introEndTime,
                outroStartTime = features.outroStartTime,
                mixInTime = features.mixInTime,
                mixOutTime = features.mixOutTime,
                mixInCandidates = features.mixInCandidates,
                mixOutCandidates = features.mixOutCandidates,
                energyCurve = features.energyCurve,
                lowEnergyCurve = features.lowEnergyCurve,
                // The model's mask where it ran, the DSP heuristic's where it didn't. Falling back to
                // the heuristic rather than to nothing matters because the policy reads an
                // absent mask and a neutral one identically — as "no evidence" — so a failed model
                // pass would otherwise silently discard the estimate Phase 1 already had.
                vocalActivityMask = mergeMasks(features.energyCurve.size, regions.mapNotNull { it.vocalMask })
                    ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale),
                vocalProbability = features.vocalProbability,
                headBeats = headSide,
                tailBeats = tailSide,
                vocalWindows = regions.mapNotNull { it.vocalWindow },
                levelWindows = regions.mapNotNull { it.levelWindow },
            ),
        )
    }

    /**
     * Everything a decoded region contributes, once its audio has been let go
     * of. [seconds] is what was actually decoded, which for a partially cached
     * file is not what was asked for.
     */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        /** Only populated when the caller asked for it; see [region]'s `deriveFeatures`. */
        val features: TrackFeatures.Features? = null,
        /** Where the decode really began (the extractor seeks to a sync sample at or before the ask). */
        val start: Double = 0.0,
        /** The vocal model's raw output over this window, for the loop mix's vocal check. */
        val vocalWindow: CurveWindow? = null,
        /** RMS level over this window, for the loop mix's loudness check. */
        val levelWindow: CurveWindow? = null,
    )

    /**
     * Seconds of audio one model window covers: the vocal model's fixed input,
     * which is the shorter of the two models' (the beat model's 1500 frames run
     * to ~30 s). One window is then exactly one inference of each.
     */
    private fun modelWindowSeconds(): Double =
        if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else {
            BeatTracker.WINDOW_SECONDS
        }

    /**
     * The windows the models run over: [HEAD_MODEL_WINDOWS] from the start and
     * [TAIL_MODEL_WINDOWS] up to the end, each [span] long and overlapping its
     * neighbour by [WINDOW_OVERLAP_SECONDS] so the beat at a seam is seen from
     * both sides. A short track, where the two spans meet, is covered once.
     */
    private fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) {
            listOf(0.0 to duration)
        } else {
            listOf(0.0 to headEnd, tailStart to duration)
        }
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            // As few windows as cover the span with at least the overlap between
            // neighbours, spread evenly from its start to its end.
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    private class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    private fun seamBeats(regions: List<Region>): Beats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        val beats = ArrayList<Double>()
        val downbeats = ArrayList<Double>()
        var weighted = 0.0
        var count = 0
        for ((index, region) in graded.withIndex()) {
            val grid = region.grid!!
            val previous = graded.getOrNull(index - 1)
            val next = graded.getOrNull(index + 1)
            val from = previous?.let { (it.start + it.seconds + region.start) / 2 }
                ?.takeIf { it > region.start } ?: Double.NEGATIVE_INFINITY
            val until = next?.let { (region.start + region.seconds + it.start) / 2 }
                ?.takeIf { it < region.start + region.seconds } ?: Double.POSITIVE_INFINITY
            val kept = grid.beats.filter { it >= from && it < until }
            beats += kept
            downbeats += grid.downbeats.filter { it >= from && it < until }
            weighted += grid.beatConfidence * kept.size
            count += kept.size
        }
        return Beats(beats.sorted(), downbeats.sorted(), if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    private fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(
            beats = beats,
            downbeats = all.downbeats.filter { it >= from && it <= to },
            bpm = bpm,
            confidence = all.confidence,
        )
    }

    private fun BeatSide.toGrid() = BeatTracker.Grid(
        beats = beats,
        downbeats = downbeats,
        bpm = bpm,
        beatInterval = 60 / bpm,
        firstBeat = beats.first(),
        beatConfidence = confidence,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only their results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copies are all local, so they become collectible the moment this returns rather
     * than staying live until the whole analysis finishes. A 30 s stereo region is several
     * megabytes before either model's own working set is counted.
     *
     * Null, or a null field, means "no model evidence for this window" — a codec that will not
     * configure, a region too short, a missing model — which [analyze] already falls back on.
     *
     * The extractor seeks to a sync sample at or before what was asked for, so the region's real
     * start (not [startSeconds]) is what its beat times must be stated against.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
    ): Region? {
        val decoded = openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) { stopped } }
            ?: run {
                // The two ways this comes back empty mean opposite things and
                // were reported identically, which cost a round of guessing:
                // this one is the extractor refusing the container outright, so
                // the bytes are wrong or not enough of them are there to parse.
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            // And this one is a container that parsed fine and yielded under a
            // second of audio — a decode that started and ran out, not one that
            // never started.
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        // In a frame of its own so the full-rate mono downmix is released before either model runs.
        // It is 23 MB for this window at 48 kHz — the same size as each of the two channels it
        // averages — and it is read exactly twice, to make the resampled model input and the DSP
        // one. As a local it would nonetheless stay reachable through `tracker.track` and
        // `vocalMask` below, which is where the analysis allocates most heavily and where the
        // process was dying. Same reasoning [derived] already had, one level further out.
        val inputs = regionInputs(stereo, seconds, deriveFeatures)

        val vocalValues = if (features != null) vocalValues(stereo) else null
        return Region(
            grid = inputs.forModel?.let { tracker.track(it, offsetSeconds = actualStart) },
            vocalMask = if (features != null && vocalValues != null) vocalMask(vocalValues, features, actualStart) else null,
            seconds = seconds,
            features = inputs.derived,
            start = actualStart,
            vocalWindow = vocalValues?.let { CurveWindow(actualStart, VocalSpectrogram.frameRate, it) },
            levelWindow = if (features != null) levelCurve(stereo, actualStart) else null,
        )
    }

    /**
     * RMS of the stereo mix in frames of 1 / [LEVEL_FRAME_RATE] s, on the
     * [levelFromRms] scale (stored in hundredths, so it has to fit 0..1). Every
     * fourth sample: plenty for a level, and a quarter of the reads.
     */
    private fun levelCurve(stereo: AudioDecoder.StereoPcm, actualStart: Double): CurveWindow? {
        val frame = (stereo.sampleRate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val count = stereo.left.size / frame
        if (count <= 0) return null
        val values = FloatArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = sample + frame
            while (sample < end) {
                val mixed = (stereo.left[sample] + stereo.right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            values[index] = levelFromRms(kotlin.math.sqrt(sum / n)).toFloat()
        }
        return CurveWindow(actualStart, LEVEL_FRAME_RATE, values)
    }

    /** A region's model input, and its DSP features when the caller asked for them. */
    private class RegionInputs(val forModel: FloatArray?, val derived: TrackFeatures.Features?)

    /**
     * Reduces a decoded region to the buffers the models and the DSP actually read.
     *
     * Both come off one full-rate mono downmix, which is why they are made together rather than on
     * demand: that downmix is the largest single allocation in the analysis, and returning is the
     * only way to be rid of it before the models run.
     */
    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }

        // Derived here rather than by the caller so the mono buffer is still
        // live: handing it back would keep several megabytes reachable for the
        // rest of the analysis, which is the one thing this function exists to
        // avoid.
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        return RegionInputs(forModel, derived)
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only where the model
     * actually ran.
     *
     * The policy indexes the mask against energy-curve sample times and requires the two to be the
     * same length, but the model's window is fixed at about 22 seconds, far less than a track. So
     * the mask is built at full length and filled only over this region.
     *
     * Everywhere else stays at [NEUTRAL_VOCAL]. That is not a guess dressed up as data: it sits
     * below the policy's own VOCAL_ACTIVE_THRESHOLD, so unmeasured material can never trip vocal
     * logic in either direction.
     */
    private fun vocalValues(stereo: AudioDecoder.StereoPcm): FloatArray? {
        if (!VocalSpectrogram.available) return null
        // [VocalTracker.track] refuses anything wider than its graph, so a window that came back a
        // little long is trimmed. Two frames of margin absorb the ±1 sample a rate conversion can
        // land on.
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right
        return vocals.track(left, right, stereo.sampleRate)
    }

    private fun vocalMask(
        values: FloatArray,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty()) return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /**
     * Overlays the head and tail masks onto one full-length curve, or null when neither ran —
     * which the caller answers by keeping the DSP heuristic rather than reporting a mask of
     * nothing but [NEUTRAL_VOCAL].
     */
    private fun mergeMasks(size: Int, masks: List<DoubleArray>): List<Double>? {
        if (size <= 0 || masks.isEmpty()) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in masks) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    /**
     * Stereo audio of [fromSeconds]..[toSeconds] of [trackId] at [sampleRate], for a loop mix deck,
     * read off the copy analysis measured (so the deck's loop is cut from the same audio its downbeats
     * were found in). Blocking: call it off the main thread.
     */
    fun loopRegion(
        trackId: String,
        fromSeconds: Double,
        toSeconds: Double,
        sampleRate: Int,
    ): LoopRegion? {
        val began = SystemClock.elapsedRealtime()
        val copy = runCatching { audio.copyOf(trackId) }.getOrNull() ?: return null
        val (stereo, start) = copy.open()?.use { source ->
            AudioDecoder.decodeRegionStereo(source, max(0.0, fromSeconds), toSeconds) { stopped }
        } ?: return null
        if (stereo.left.isEmpty()) return null
        val rate = sampleRate.toDouble()
        val left: FloatArray
        val right: FloatArray
        if (abs(stereo.sampleRate - rate) > 0.5) {
            left = MelSpectrogram.resample(stereo.left, stereo.sampleRate, rate) ?: return null
            right = MelSpectrogram.resample(stereo.right, stereo.sampleRate, rate) ?: return null
        } else {
            left = stereo.left
            right = stereo.right
        }
        Log.d(
            TAG,
            "Loop region $trackId from ${copy.key}: asked ${"%.2f".format(Locale.ROOT, fromSeconds)}s, " +
                "got ${"%.3f".format(Locale.ROOT, start)}s + ${"%.2f".format(Locale.ROOT, left.size / rate)}s " +
                "at ${stereo.sampleRate.toInt()}->$sampleRate Hz in ${SystemClock.elapsedRealtime() - began}ms",
        )
        return LoopRegion(left, right, start)
    }

    /**
     * A three-band waveform of [fromSeconds]..[toSeconds] of [trackId], read off the same copy
     * analysis measured; null when there's no such copy to read. Blocking: call it off the main thread.
     */
    fun mixWaveform(trackId: String, fromSeconds: Double, toSeconds: Double, bins: Int): MixWaveform.Bands? {
        val copy = runCatching { audio.copyOf(trackId) }.getOrNull() ?: return null
        return copy.open()?.use { source -> MixWaveform.build(source, fromSeconds, toSeconds, bins) { stopped } }
    }

    fun release() {
        executor.shutdownNow()
        restoreAttempted.clear()
        shortDecodes.clear()
        triedRenditions.clear()
        tracker.release()
        vocals.release()
    }

    private companion object {
        const val TAG = "AetherTrackAnalyzer"

        /**
         * What an unmeasured instant reads as. Below the policy's VOCAL_ACTIVE_THRESHOLD by
         * design, so absence of measurement is never mistaken for absence of a vocal, or for the
         * presence of one.
         */
        const val NEUTRAL_VOCAL = 0.2

        /** Model windows over the opening (~45 s) and the ending (~68 s); see [modelWindowSeconds]. */
        const val HEAD_MODEL_WINDOWS = 2
        const val TAIL_MODEL_WINDOWS = 3

        /** Overlap between neighbouring model windows, so a beat on a seam is seen from both sides. */
        const val WINDOW_OVERLAP_SECONDS = 1.0

        /**
         * How much more than the average-bitrate estimate of the opening window
         * to insist on before decoding it. Covers the container header and the
         * fact that a track's opening is rarely at its own average bitrate.
         */
        const val HEAD_BYTES_MARGIN = 1.35

        /**
         * Floor under the computed threshold, and the whole requirement when the
         * duration is unknown. Roughly fifteen seconds at 128 kbps — a little
         * over [MIN_HEAD_SECONDS], so it guarantees a parsable container and a
         * usable decode without quietly reinstating the thirty-second demand the
         * bitrate estimate was just lowered away from.
         */
        const val MIN_HEAD_BYTES = 256L * 1024L

        /**
         * How much of the container's stated duration must actually decode
         * before the whole-track pass is trusted. Not 1.0: a decoder legitimately
         * comes up a frame or two short of the container's rounding, and
         * refusing over that would refuse everything.
         */
        const val MIN_DECODED_FRACTION = 0.95

        /** On a warm phone, the rest between one pass and the next. See [overheated]. */
        const val WARM_REST_MS = 60_000L

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /**
         * Clean-slate retries per rendition. One: a discard is worth doing when
         * the bytes on disk are unrecoverable and nothing would otherwise
         * overwrite them, and worth doing exactly once, because a second identical
         * answer means the source is serving that file rather than the cache
         * having mangled it.
         */
        const val MAX_RENDITION_DISCARDS = 1

        /**
         * How far two renditions' container durations may differ and still count
         * as the same cut. Generous enough for codec padding and the player's own
         * rounding, tight enough that a different edit of the same song — where a
         * borrowed beat grid would be useless — is rejected.
         */
        const val RENDITION_DURATION_TOLERANCE = 1.0

        /**
         * Stand-in bitrate for a rendition whose real length isn't recorded yet,
         * used only to rank copies against each other in [headSecondsOf]. About
         * 160kbps, the middle of what the streams in play here run at.
         */
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        /**
         * The least decoded audio a head-only tempo estimate is allowed to rest
         * on. Twelve seconds is around 24 beats at 120 bpm — enough for the
         * grid's own confidence measure to mean something.
         */
        const val MIN_HEAD_SECONDS = 12.0

        /**
         * How much more of the file has to be cached before the head is worth
         * decoding again. Doubling bounds the attempts to a handful over a whole
         * download while still catching up quickly on a high-bitrate rendition
         * whose first attempt covered only a few seconds.
         */
        const val HEAD_RETRY_GROWTH = 2
    }
}

/** Which waiting pass goes next: one a mix is waiting on ([urgent]) first, then the one asked for first. */
internal fun <T> mostPressing(items: Collection<T>, urgent: (T) -> Boolean, ticket: (T) -> Long): T? =
    items.minWithOrNull(compareBy<T>({ !urgent(it) }, { ticket(it) }))
