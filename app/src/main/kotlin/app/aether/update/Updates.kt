// After PODIUM's update check (app/update, GPL-3.0), the listener's own app; adapted for AETHER.
package app.aether.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** What the update sheet shows; [Idle] shows nothing. */
internal sealed interface UpdateState {
    data object Idle : UpdateState

    /** A check the listener asked for, under way (Settings, Software update). */
    data object Checking : UpdateState

    /** A check the listener asked for found nothing newer. */
    data class UpToDate(val current: Version) : UpdateState

    /** A newer release: its notes in short, and whether AETHER can install it itself. */
    data class Offer(val release: Release, val highlights: List<String>, val canInstall: Boolean) : UpdateState

    data class Downloading(val release: Release, val done: Long, val total: Long?) : UpdateState

    data class Verifying(val release: Release) : UpdateState

    /** Downloaded and checked; Android must let AETHER install apps first. */
    data class NeedsPermission(val release: Release) : UpdateState

    /** Handed to Android's installer, which asks the listener to confirm. */
    data class Installing(val release: Release) : UpdateState

    data class Failed(val release: Release?, val problem: UpdateProblem, val detail: String? = null) : UpdateState
}

internal enum class UpdateProblem { NO_CONNECTION, RATE_LIMITED, NOTHING_TO_DOWNLOAD, DOWNLOAD, CORRUPT, OTHER_APP, NOT_NEWER, INSTALL }

/** What the update check keeps between runs: one small preferences file, nothing about the listener. */
internal interface UpdateStore {
    var automatic: Boolean
    var lastCheckedAt: Long
    var ignoredTag: String?
    var remindAfter: Long

    /** The last answer from GitHub, so a start within [UpdateCenter.CHECK_EVERY_MS] needn't ask again. */
    var lastAnswer: String?
}

internal class PrefsUpdateStore(context: Context) : UpdateStore {
    private val prefs = context.applicationContext.getSharedPreferences("aether-updates", Context.MODE_PRIVATE)
    override var automatic: Boolean
        get() = prefs.getBoolean("automatic", true)
        set(v) = prefs.edit().putBoolean("automatic", v).apply()
    override var lastCheckedAt: Long
        get() = prefs.getLong("last-checked", 0L)
        set(v) = prefs.edit().putLong("last-checked", v).apply()
    override var ignoredTag: String?
        get() = prefs.getString("ignored", null)
        set(v) = prefs.edit().putString("ignored", v).apply()
    override var remindAfter: Long
        get() = prefs.getLong("remind-after", 0L)
        set(v) = prefs.edit().putLong("remind-after", v).apply()
    override var lastAnswer: String?
        get() = prefs.getString("last-answer", null)
        set(v) = prefs.edit().putString("last-answer", v).apply()
}

/**
 * AETHER's own updates, as PODIUM's. When the app comes to the front (release builds, unless turned
 * off), GitHub is asked for the newest release: afresh the first time each launch (BitChord's "once per
 * launch", so a release out since the last look is seen at once), then at most every [CHECK_EVERY_MS]. A newer one is offered
 * on a sheet with its notes in short: download and install it right there, open its page on GitHub, or
 * leave that version be. Put away, the offer comes back after [REMIND_AFTER_MS].
 *
 * Installing: the release's APK is downloaded into the app's cache, checked against the SHA-256 GitHub
 * gives for it, then checked to be this app, newer, and signed by the same key, and only then handed
 * to Android's installer, which asks the listener to confirm. Nothing about the listener is sent.
 */
class UpdateCenter internal constructor(
    private val net: UpdateNet,
    private val store: UpdateStore,
    private val installer: UpdateInstaller,
    private val current: Version,
    private val downloads: File,
    /** Off the main thread: downloads, checksums and handing over to the installer read and write files. */
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    /** Checks by itself (release builds); test builds check only when asked. */
    private val checksByItself: Boolean,
    /** Whether a release's app can replace this one (the release package; never a test build). */
    private val canInstallReleases: Boolean,
    private val feedUrl: String = LATEST_URL,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    internal val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether the sheet is up; a download goes on behind it when it's put away. */
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    private val _automatic = MutableStateFlow(store.automatic)
    val automatic: StateFlow<Boolean> = _automatic.asStateFlow()

    /** This build's version, as its releases are named. */
    val version: String get() = current.toString()

    /** Whether this build looks for updates by itself (a test build never does). */
    val looksByItself: Boolean get() = checksByItself

    /** Android's page where the listener lets AETHER install apps. */
    fun permissionPage() = installer.permissionPage()

    private var job: Job? = null

    /** This launch has asked GitHub itself; until it has, a remembered answer won't do. */
    private var askedThisLaunch = false

    fun setAutomatic(on: Boolean) {
        store.automatic = on
        _automatic.value = on
    }

    /** The app came to the front: a quiet check, when it's time. Problems stay quiet. */
    fun autoCheck() {
        if (!checksByItself || !store.automatic || job?.isActive == true || _state.value != UpdateState.Idle) return
        job = scope.launch {
            val release = try {
                val recent = store.lastAnswer?.takeIf { askedThisLaunch && now() - store.lastCheckedAt < CHECK_EVERY_MS }
                Releases.parse(recent ?: fetch().also { askedThisLaunch = true })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return@launch
            if (release.version <= current || release.tag == store.ignoredTag || now() < store.remindAfter) return@launch
            offer(release)
        }
    }

    /**
     * Settings, Software update, Check now: always asks, and says what it found. [pretend] is a test
     * build's way of seeing an offer: it checks as if it were that version ("0.0.1").
     */
    fun checkNow(pretend: String? = null) {
        job?.cancel()
        _state.value = UpdateState.Checking
        _shown.value = true
        job = scope.launch {
            val release = try {
                Releases.parse(fetch())
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpError) {
                fail(null, if (e.code == 403 || e.code == 429) UpdateProblem.RATE_LIMITED else UpdateProblem.NO_CONNECTION)
                return@launch
            } catch (e: IOException) {
                fail(null, UpdateProblem.NO_CONNECTION)
                return@launch
            }
            val mine = pretend?.let(Version::of) ?: current
            if (release == null || release.version <= mine) {
                _state.value = UpdateState.UpToDate(current)
            } else {
                offer(release)
            }
        }
    }

    private suspend fun fetch(): String {
        val text = net.text(feedUrl)
        store.lastCheckedAt = now()
        store.lastAnswer = text
        return text
    }

    private fun offer(release: Release) {
        val install = canInstallReleases && release.apk != null
        _state.value = UpdateState.Offer(release, ReleaseNotes.short(release.notes), install)
        _shown.value = true
    }

    /** "Download and install". */
    fun install() {
        val release = (_state.value as? UpdateState.Offer)?.release ?: (_state.value as? UpdateState.Failed)?.release ?: return
        val apk = release.apk ?: return fail(release, UpdateProblem.NOTHING_TO_DOWNLOAD)
        job?.cancel()
        job = scope.launch {
            val file = File(downloads, "aether-${release.tag.filter { it.isLetterOrDigit() || it == '.' || it == '-' }}.apk")
            downloads.listFiles()?.filter { it != file }?.forEach { it.delete() }
            if (!(file.isFile && apk.sha256 != null && file.sha256() == apk.sha256)) {
                val part = File(downloads, file.name + ".part")
                _state.value = UpdateState.Downloading(release, 0, apk.size.takeIf { it > 0 })
                var shownAt = 0L
                try {
                    net.download(apk.url, part) { done, total ->
                        // A step a percent (or a megabyte), not every buffer.
                        val step = maxOf((total ?: apk.size).coerceAtLeast(1) / 100, 1L shl 20)
                        if (done - shownAt >= step || done == total) {
                            shownAt = done
                            _state.value = UpdateState.Downloading(release, done, total ?: apk.size.takeIf { it > 0 })
                        }
                    }
                } catch (e: CancellationException) {
                    part.delete()
                    throw e
                } catch (e: Exception) {
                    part.delete()
                    return@launch fail(release, UpdateProblem.DOWNLOAD)
                }
                file.delete()
                if (!part.renameTo(file)) return@launch fail(release, UpdateProblem.DOWNLOAD)
            }
            _state.value = UpdateState.Verifying(release)
            if (apk.sha256 != null && file.sha256() != apk.sha256) {
                file.delete()
                return@launch fail(release, UpdateProblem.CORRUPT)
            }
            handOver(release, file)
        }
    }

    /** Checked, then to Android's installer (or first, to Android's page that allows it). */
    private fun handOver(release: Release, file: File) {
        when (installer.inspect(file)) {
            ApkCheck.UPDATE -> Unit
            ApkCheck.OTHER_APP -> return fail(release, UpdateProblem.OTHER_APP)
            ApkCheck.NOT_NEWER -> return fail(release, UpdateProblem.NOT_NEWER)
            ApkCheck.UNREADABLE -> {
                file.delete()
                return fail(release, UpdateProblem.CORRUPT)
            }
        }
        pending = release to file
        if (!installer.allowed()) {
            _state.value = UpdateState.NeedsPermission(release)
            _shown.value = true
            return
        }
        _state.value = UpdateState.Installing(release)
        _shown.value = true
        try {
            installer.install(file)
        } catch (e: Exception) {
            fail(release, UpdateProblem.INSTALL)
        }
    }

    private var pending: Pair<Release, File>? = null

    /** AETHER is back in front: if the listener has just allowed installs, carry on. */
    fun resumed() {
        val (release, file) = pending ?: return
        if (_state.value is UpdateState.NeedsPermission && installer.allowed()) {
            job = scope.launch { handOver(release, file) }
        }
    }

    /** What Android's installer said. Done means Android is replacing AETHER. */
    internal fun installFinished(outcome: InstallOutcome, detail: String?) {
        val release = (_state.value as? UpdateState.Installing)?.release ?: pending?.first
        when (outcome) {
            InstallOutcome.DONE -> {
                _state.value = UpdateState.Idle
                _shown.value = false
            }
            InstallOutcome.CANCELLED -> release?.let { offer(it) }
            InstallOutcome.FAILED -> fail(release, UpdateProblem.INSTALL, detail)
        }
    }

    /** "Not this version": not offered again; a newer one still is. */
    fun ignore() {
        (_state.value as? UpdateState.Offer)?.release?.let { store.ignoredTag = it.tag }
        close()
    }

    /** Pulled down, or "Later": the sheet goes. An offer comes back later; a download carries on. */
    fun dismiss() {
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Verifying -> _shown.value = false
            is UpdateState.Offer -> {
                store.remindAfter = now() + REMIND_AFTER_MS
                close()
            }
            else -> close()
        }
    }

    /** "Stop" while downloading. */
    fun cancel() {
        job?.cancel()
        (_state.value as? UpdateState.Downloading)?.release?.let { offer(it) } ?: close()
    }

    private fun close() {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Verifying) job?.cancel()
        _state.value = UpdateState.Idle
        _shown.value = false
    }

    private fun fail(release: Release?, problem: UpdateProblem, detail: String? = null) {
        _state.value = UpdateState.Failed(release, problem, detail)
        _shown.value = true
    }

    companion object {
        const val REPOSITORY = "GHOSTxASR/AETHER"
        const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"

        /** At most one check this often (GitHub allows 60 anonymous requests an hour). */
        const val CHECK_EVERY_MS = 6 * 60 * 60_000L

        /** An offer put away comes back after this. */
        const val REMIND_AFTER_MS = 24 * 60 * 60_000L

        /** The updates for the app in [context], for its version and package as installed. */
        fun of(context: Context, scope: CoroutineScope): UpdateCenter {
            val app = context.applicationContext
            val name = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
            // A test build ends `.debug`, a release candidate beside the phone's own `.rc`: neither can
            // take a release's APK, and only the listener's own AETHER looks for one by itself.
            val release = app.packageName == RELEASE_PACKAGE
            return UpdateCenter(
                net = HttpsUpdateNet("AETHER/$name (Android music player; update check)"),
                store = PrefsUpdateStore(app),
                installer = PackageUpdateInstaller(app),
                current = Version.of(name) ?: Version(listOf(0), "0"),
                downloads = File(app.cacheDir, "updates"),
                scope = scope,
                checksByItself = release,
                canInstallReleases = release,
            )
        }

        private const val RELEASE_PACKAGE = "app.aether"
    }
}
