package app.aether.update

import android.content.Intent
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The look for a newer AETHER as the app comes to the front: afresh once a launch, then remembered. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckTest {
    private val newer = """{"tag_name":"v9.0.0","html_url":"https://github.com/GHOSTxASR/AETHER/releases/tag/v9.0.0","body":"","draft":false,"prerelease":false,"assets":[]}"""

    private class Net(private val answer: String) : UpdateNet {
        var asked = 0
        override suspend fun text(url: String): String {
            asked++
            return answer
        }
        override suspend fun download(url: String, to: File, onProgress: (Long, Long?) -> Unit) = Unit
    }

    private class Store(override var lastAnswer: String?, override var lastCheckedAt: Long) : UpdateStore {
        override var automatic = true
        override var ignoredTag: String? = null
        override var remindAfter = 0L
    }

    private object Installer : UpdateInstaller {
        override fun allowed() = true
        override fun permissionPage() = Intent()
        override fun inspect(file: File) = ApkCheck.UPDATE
        override fun install(file: File) = Unit
    }

    private fun TestScope.center(net: Net, store: Store, clock: Long) = UpdateCenter(
        net = net,
        store = store,
        installer = Installer,
        current = Version.of("2.3.0")!!,
        downloads = File("build/tmp"),
        scope = this,
        now = { clock },
        checksByItself = true,
        canInstallReleases = false,
    )

    @Test
    fun theFirstLookOfALaunchAsksGitHubEvenWithARecentAnswerKept() = runTest {
        val clock = 10 * 60 * 60_000L
        // An answer an hour old says there's nothing newer; a release has come out since.
        val store = Store(lastAnswer = """{"tag_name":"v2.3.0","html_url":"https://github.com/x/y","draft":false,"prerelease":false}""", lastCheckedAt = clock - 60 * 60_000L)
        val net = Net(newer)
        val updates = center(net, store, clock)
        updates.autoCheck()
        advanceUntilIdle()
        assertEquals(1, net.asked)
        assertIs<UpdateState.Offer>(updates.state.value)
    }

    @Test
    fun laterLooksInTheSameLaunchUseWhatItWasTold() = runTest {
        val clock = 10 * 60 * 60_000L
        val store = Store(lastAnswer = null, lastCheckedAt = 0L)
        val net = Net("""{"tag_name":"v2.3.0","html_url":"https://github.com/x/y","draft":false,"prerelease":false}""")
        val updates = center(net, store, clock)
        updates.autoCheck()
        advanceUntilIdle()
        updates.autoCheck()
        advanceUntilIdle()
        assertEquals(1, net.asked)
    }
}
