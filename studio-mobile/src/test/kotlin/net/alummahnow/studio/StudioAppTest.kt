package net.alummahnow.studio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.feature.upload.PickedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The whole Studio shell over fakes: what the Terms gate, the upload queue and the editor's exports do together. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-ldltr-w411dp-h2400dp-port-mdpi")
class StudioAppTest {
    @get:Rule val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule<ComponentActivity>()

    private val base = Files.createTempDirectory("aun-studio").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repoA = FakeStudioRepository(StudioChannel("ca", "Channel A"))
    private val repoB = FakeStudioRepository(StudioChannel("cb", "Channel B"))
    private val session = FakeSession("a", "b")
    private lateinit var container: FakeContainer

    @Before fun start() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h2400dp-port-mdpi")
        container = FakeContainer(base, session, mapOf("a" to repoA, "b" to repoB))
        scope.launch { container.exports.follow(session.accounts) }
    }

    @After fun stop() {
        scope.cancel()
        base.deleteRecursively()
    }

    private fun show() {
        compose.setContent { AunTheme { StudioApp(container, emptyFlow(), openInBrowser = { true }) } }
        waitFor("tab-upload")
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(tag: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun openUpload() = compose.onNodeWithTag("tab-upload").performClick()

    private fun switchTo(id: String) {
        compose.onNodeWithTag("account-button").performClick()
        waitFor("account-$id")
        compose.onNodeWithTag("account-$id").performClick()
        compose.waitUntil(TIMEOUT_MS) { session.active.value?.userId == id }
        waitFor("tab-upload")
    }

    private fun export(userId: String, channelId: String): File {
        val file = File(container.exports.dir(userId).apply { mkdirs() }, "edit-1.mp4").apply { writeText("video") }
        container.exports.add(
            userId,
            channelId,
            PickedFile("content://exports/$userId/edit-1.mp4", "edit-1.mp4", file.length(), "video/mp4")
        )
        return file
    }

    private fun agree() {
        compose.onNodeWithTag("creator-terms-checkbox").performScrollTo().performClick()
        compose.onNodeWithTag("creator-terms-agree").performScrollTo().performClick()
    }

    @Test
    fun `an upload refused for the Terms opens the gate with the reason, and the person retries it after agreeing`() {
        show()
        openUpload()
        waitFor("upload-pick")
        val item = UploadItem("u1", "Ramadan reminder", UploadPhase.FAILED, 0, 10, null, "forbidden: Accept the Terms")
        repoA.termsAccepted = false
        container.uploads.items.value = listOf(item)
        waitFor("creator-terms-refused")

        agree()
        waitFor("upload-reason-u1")
        compose.onNodeWithTag("upload-reason-u1").performScrollTo().assertIsDisplayed()
            .assertTextContains("Refused because the Terms weren't accepted. Once you've agreed, tap Retry.")
        assertEquals(emptyList<String>(), container.uploads.retried) // only the person retries
        compose.onNodeWithTag("upload-retry-u1").performScrollTo().performClick()
        assertEquals(listOf("u1"), container.uploads.retried)
    }

    @Test
    fun `on another tab, the refusal says where to agree`() {
        show()
        compose.onNodeWithTag("tab-content").performClick()
        repoA.termsAccepted = false
        container.uploads.items.value = listOf(UploadItem("u1", "x", UploadPhase.FAILED, 0, 10, null, "forbidden: x"))
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("An upload was refused: open Upload to agree to the Terms."))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `an upload refused for another reason opens no gate and says the server refused it`() {
        show()
        openUpload()
        container.uploads.items.value = listOf(UploadItem("u1", "x", UploadPhase.FAILED, 0, 10, null, "forbidden: role"))
        waitFor("upload-reason-u1")
        compose.onNodeWithTag("upload-reason-u1").assertTextContains("The server refused this upload.", substring = true)
        compose.onNodeWithTag("creator-terms-gate").assertDoesNotExist()
    }

    @Test
    fun `an upload that failed for any other reason shows no refusal`() {
        show()
        openUpload()
        container.uploads.items.value = listOf(UploadItem("u1", "x", UploadPhase.FAILED, 0, 10, null, "network error: x"))
        waitFor("upload-item-u1")
        compose.onNodeWithTag("upload-reason-u1").assertDoesNotExist()
    }

    @Test
    fun `the gate and the acceptance belong to one account`() {
        repoB.termsAccepted = false
        show()
        openUpload()
        waitFor("upload-pick")
        switchTo("b")
        openUpload()
        waitFor("creator-terms-gate")
        agree()
        waitFor("upload-pick")
        assertEquals(0, repoA.accepts)
        assertEquals(1, repoB.accepts)
    }

    @Test
    fun `Terms that changed before the tap are shown again and need a new tick`() {
        repoA.termsAccepted = false
        show()
        openUpload()
        waitFor("creator-terms-gate")
        repoA.termsVersion = "v2"
        agree()
        waitFor("creator-terms-error")
        compose.onNodeWithTag("creator-terms-error").assertTextContains("The Terms of Service were updated.", substring = true)
        compose.onNodeWithTag("creator-terms-agree").assertIsNotEnabled()
        agree()
        waitFor("upload-pick")
        assertEquals(listOf("v1", "v2"), repoA.acceptedVersions)
    }

    @Test
    fun `no answer about the Terms shows no gate`() {
        repoA.termsAccepted = null
        show()
        openUpload()
        waitFor("upload-pick")
    }

    @Test
    fun `coming back to the app asks again`() {
        show()
        openUpload()
        waitFor("upload-pick")
        repoA.termsAccepted = false
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("creator-terms-gate")
    }

    @Test
    fun `an export made before agreeing waits through an account switch and joins the form after agreeing`() {
        repoA.termsAccepted = false
        show()
        val file = export("a", "ca")
        switchTo("b")
        openUpload()
        waitFor("upload-pick")
        compose.onNodeWithTag("upload-file-0").assertDoesNotExist()
        assertTrue(file.exists())
        switchTo("a")
        openUpload()
        waitFor("creator-terms-gate")
        assertEquals(setOf("a" to "ca"), container.exports.waiting.value)
        agree()
        waitFor("upload-file-0")
        assertTrue(file.exists())
    }

    @Test
    fun `a failed sign-out keeps the exports and a successful one deletes them`() {
        repoA.termsAccepted = false
        show()
        val file = export("a", "ca")
        session.signOutError = CoreError.Http(503)
        signOut("a")
        compose.waitForIdle()
        assertTrue(file.exists())
        session.signOutError = null
        signOut("a")
        compose.waitUntil(TIMEOUT_MS) { !file.exists() }
        assertEquals(emptySet<Pair<String, String>>(), container.exports.waiting.value)
        assertEquals(listOf("a"), container.forgotten)
    }

    @Test
    fun `a session that ends by itself leaves no exports behind`() {
        show()
        val file = export("a", "ca")
        session.accounts.value = emptyList()
        session.active.value = null
        compose.waitUntil(TIMEOUT_MS) { !file.exists() }
        assertFalse(container.exports.dir("a").exists())
    }

    @Test
    fun `an export the system removed is said, not dropped in silence`() {
        show()
        export("a", "ca").delete()
        openUpload()
        waitFor("upload-pick")
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("The exported video is no longer on this device.", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun signOut(id: String) {
        compose.onNodeWithTag("account-button").performClick()
        waitFor("sign-out-$id")
        compose.onNodeWithTag("sign-out-$id").performClick()
        waitFor("confirm-ok")
        compose.onNodeWithTag("confirm-ok").performClick()
        waitGone("confirm-ok")
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
