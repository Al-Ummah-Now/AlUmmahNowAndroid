package net.alummahnow.studio

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.deletion.DeletionAnswer
import net.alummahnow.core.bridge.studio.deletion.DeletionRecord
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

    private val opened = mutableListOf<String>()
    private val redirects = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private fun show() {
        compose.setContent { AunTheme { StudioApp(container, redirects, openInBrowser = { opened.add(it) }) } }
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
            compose.onAllNodes(hasText("An upload was refused: open Upload to agree to the Terms."))
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
    fun `another account's refused upload says nothing about this one`() {
        repoB.termsAccepted = false
        show()
        container.uploads.items.value = listOf(UploadItem("u1", "x", UploadPhase.FAILED, 0, 10, null, "forbidden: x"))
        compose.waitForIdle()
        // The first account's rows are still in the list while the second one's are being read.
        val wait = java.util.concurrent.CountDownLatch(1)
        container.uploads.refreshWaits = wait
        switchTo("b")
        openUpload()
        waitFor("creator-terms-gate")
        compose.waitForIdle()
        compose.onNodeWithTag("creator-terms-refused").assertDoesNotExist()
        wait.countDown()
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
    fun `a switch shows the new account's uploads, never the other account's`() {
        container.uploads.byUser["a"] = listOf(UploadItem("u1", "x", UploadPhase.FAILED, 0, 10, null, "network error: x"))
        show()
        openUpload()
        waitFor("upload-item-u1")
        switchTo("b")
        openUpload()
        waitFor("upload-pick")
        waitGone("upload-item-u1")
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
            compose.onAllNodes(hasText("The exported video is no longer on this device.", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openDelete() {
        compose.onNodeWithTag("account-button").performClick()
        waitFor("studio-delete-account")
        compose.onNodeWithTag("studio-delete-account").performScrollTo().performClick()
        waitFor("delete-account-dialog")
    }

    private fun typeAndConfirm(word: String = "DELETE") {
        compose.onNodeWithTag("delete-account-input").performScrollTo().performTextInput(word)
        compose.onNodeWithTag("delete-account-confirm").performClick()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `deleting the last account erases, deletes, cleans up and says so on the sign-in screen`() {
        session.accounts.value = session.accounts.value.take(1)
        show()
        openDelete()
        compose.onNodeWithTag("delete-account-name").assertTextContains("a@example.org", substring = true)
        compose.onNodeWithTag("delete-account-channels").assertTextContains("You own 1 channel.", substring = true)
        typeAndConfirm()
        waitFor("signin-notice-0")
        compose.onNodeWithTag("signin-notice-0").assertTextContains("your Al Ummah Now account was deleted", substring = true)
        assertEquals(listOf("erase a -", "account a"), container.deletionApi.calls)
        assertEquals(listOf("a"), container.uploads.purged)
        assertEquals(listOf("a"), container.forgotten)
        assertTrue(container.deletionRecords.records.value.isEmpty())
        assertTrue("https://auth.example.org/logout" in opened)
    }

    @Test
    fun `deleting one of two accounts leaves the other one as it was`() {
        show()
        openDelete()
        typeAndConfirm("حذف")
        waitForText("your Al Ummah Now account was deleted")
        assertEquals("b", session.active.value?.userId)
        assertEquals(listOf("a"), container.uploads.purged)
    }

    @Test
    fun `a channel and other records are said as both, before and after`() {
        val both = DeletionAnswer(200, """{"success":true,"complete":true,"errors":["channel_unreadable:c9","backups_unbound"]}""")
        container.deletionApi.erases += listOf(both, both)
        show()
        openDelete()
        typeAndConfirm()
        waitForText("except for a channel and some records we could not remove")
        waitForText("Delete anyway")
        compose.onNodeWithTag("delete-account-confirm").performClick()
        waitForText("account was deleted and your Videos content was erased, except for a channel and some records")
        assertEquals("account a", container.deletionApi.calls.last())
    }

    @Test
    fun `uploads that can't be held stop the deletion before anything is removed`() {
        container.uploads.holdError = IllegalStateException("a request is out")
        show()
        openDelete()
        typeAndConfirm()
        waitForText("nothing was removed by this attempt")
        assertEquals(emptyList<String>(), container.deletionApi.calls)
        compose.onNodeWithTag("delete-account-cancel").performClick()
        compose.onNodeWithTag("deletion-banner").assertDoesNotExist()
    }

    @Test
    fun `an unreadable channel is said and the person decides`() {
        container.deletionApi.erases +=
            DeletionAnswer(200, """{"success":true,"complete":true,"errors":["channel_unreadable:c9"]}""")
        show()
        openDelete()
        typeAndConfirm()
        waitForText("except for a channel we could not remove")
        assertEquals(listOf("erase a -"), container.deletionApi.calls)
        // Coming back later, the app says it on its own.
        compose.onNodeWithTag("delete-account-cancel").performClick()
        waitFor("deletion-banner")
        compose.onNodeWithTag("deletion-finish").performClick()
        waitFor("delete-account-dialog")
        container.deletionApi.erases +=
            DeletionAnswer(200, """{"success":true,"complete":true,"errors":["channel_unreadable:c9"]}""")
        typeAndConfirm()
        waitForText("Delete anyway")
        // Going on erases again; the channel still can't be read.
        container.deletionApi.erases +=
            DeletionAnswer(200, """{"success":true,"complete":true,"errors":["channel_unreadable:c9"]}""")
        compose.onNodeWithTag("delete-account-confirm").performClick()
        waitForText("account was deleted and your Videos content was erased, except for a channel")
        assertEquals("account a", container.deletionApi.calls.last())
        assertEquals(3, container.deletionApi.calls.count { it.startsWith("erase a") })
    }

    @Test
    fun `an old sign-in asks to sign in again, and nothing is removed until the person confirms again`() {
        session.authTime = container.deletionApi.now - 400
        show()
        openDelete()
        typeAndConfirm()
        waitForText("sign in again to continue")
        assertEquals(emptyList<String>(), container.deletionApi.calls)
        compose.onNodeWithTag("delete-account-confirm").performClick()
        compose.waitUntil(TIMEOUT_MS) { opened.any { "max_age=300" in it } }
        session.signsInAs = "a"
        redirects.tryEmit("alummahnow-studio://auth/callback?code=x&state=st-1")
        waitForText("You are signed in. Confirm to continue.")
        assertEquals(emptyList<String>(), container.deletionApi.calls)
        compose.onNodeWithTag("delete-account-confirm").performClick() // the typed word is still there
        compose.waitUntil(TIMEOUT_MS) { container.deletionApi.calls.size == 2 }
    }

    @Test
    fun `signing in as someone else erases nothing and returns to the first account`() {
        session.authTime = container.deletionApi.now - 400
        show()
        openDelete()
        typeAndConfirm()
        waitForText("sign in again to continue")
        compose.onNodeWithTag("delete-account-confirm").performClick()
        compose.waitUntil(TIMEOUT_MS) { opened.any { "max_age=300" in it } }
        session.signsInAs = "b"
        redirects.tryEmit("alummahnow-studio://auth/callback?code=x&state=st-1")
        waitForText("You signed in to a different account.")
        assertEquals("a", session.active.value?.userId)
        assertEquals(emptyList<String>(), container.deletionApi.calls)
    }

    @Test
    fun `another sign-in's redirect never counts for a deletion`() {
        session.authTime = container.deletionApi.now - 400
        show()
        openDelete()
        typeAndConfirm()
        waitForText("sign in again to continue")
        compose.onNodeWithTag("delete-account-confirm").performClick()
        compose.waitUntil(TIMEOUT_MS) { opened.any { "max_age=300" in it } }
        session.signsInAs = "a"
        redirects.tryEmit("alummahnow-studio://auth/callback?code=x&state=other")
        compose.waitForIdle()
        compose.onAllNodes(hasText("You are signed in. Confirm to continue.")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        assertEquals(emptyList<String>(), container.deletionApi.calls)
    }

    @Test
    fun `an open deletion shows on every screen, in the account list, and when nobody is signed in`() {
        container.deletionRecords.put(DeletionRecord("a", "a@example.org", startedAtMs = 1))
        container.deletionRecords.put(DeletionRecord("b", "b@example.org", startedAtMs = 1))
        show()
        waitFor("deletion-banner")
        for (tab in listOf("content", "upload", "editor")) {
            compose.onNodeWithTag("tab-$tab").performClick()
            compose.onNodeWithTag("deletion-banner").assertIsDisplayed()
        }
        compose.onNodeWithTag("account-button").performClick()
        waitForText("Deletion started, not finished")
        compose.runOnUiThread {
            session.accounts.value = emptyList()
            session.active.value = null
        }
        waitFor("signin-notice-0")
        compose.onNodeWithTag("signin-notice-0").assertTextContains("Sign in to that account to finish", substring = true)
    }

    @Test
    fun `signing out the last account still forgets it and ends the browser's session`() {
        session.accounts.value = session.accounts.value.take(1)
        show()
        signOut("a")
        compose.waitUntil(TIMEOUT_MS) { container.forgotten == listOf("a") && "https://auth.example.org/logout" in opened }
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
