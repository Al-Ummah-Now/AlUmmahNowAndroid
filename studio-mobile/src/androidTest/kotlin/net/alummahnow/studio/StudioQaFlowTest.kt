package net.alummahnow.studio

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real app against QA (videos.api.qa / auth.qa): browser sign-in with PKCE in a Custom Tab,
 * a resumable upload through aun-upload and the WorkManager worker, bulk edit through the UI, then
 * clean-up and sign-out. Runs only when the QA seed password is passed (`-e qaPassword`).
 */
@RunWith(AndroidJUnit4::class)
class StudioQaFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val app get() = compose.activity.application as StudioApplication

    @Test
    fun signInUploadBulkEditSignOut() {
        val email = args.getString("qaEmail")
        val password = args.getString("qaPassword")
        assumeTrue("QA password not provided", !password.isNullOrBlank())

        signIn(email!!, password!!)
        val channel = pickChannel()

        // Upload: a 3-second clip, private, with a unique title, through the real queue + worker.
        val title = "android-qa-${System.currentTimeMillis()}"
        val clip = copyAsset(compose.activity, "qa-test-clip.mp4")
        runBlocking {
            app.container.uploads.enqueue(
                listOf(UploadDraft(clip.absolutePath, clip.name, clip.length(), "video/mp4", title, "Instrumented test", channel, Visibility.PRIVATE, tags = listOf("qa-instrumented")))
            )
        }
        compose.waitUntil(UPLOAD_TIMEOUT_MS) {
            app.container.uploads.items.value.any { it.title == title && it.phase in setOf(UploadPhase.DONE, UploadPhase.FAILED) }
        }
        val uploaded = app.container.uploads.items.value.first { it.title == title }
        assertEquals("upload error: ${uploaded.error}", UploadPhase.DONE, uploaded.phase)
        val videoId = assertNotNullAnd(uploaded.videoId)
        val fresh = runBlocking { app.container.repository.video(videoId) }
        assertEquals(title, fresh.title)
        assertTrue(!fresh.isPublic)

        // Bulk edit through the UI: find it, select it, add a tag.
        compose.onNodeWithTag("tab-content").performClick()
        compose.onNodeWithTag("content-search").performTextInput(title)
        // A fresh upload is under Processing until the server finishes, then Published.
        compose.waitUntil(UPLOAD_TIMEOUT_MS) {
            val found = compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isNotEmpty()
            if (!found) {
                val tab = if (System.currentTimeMillis() / TAB_SWITCH_MS % 2 == 0L) "status-processing" else "status-published"
                runCatching { compose.onNodeWithTag(tab).performClick() }
            }
            found
        }
        compose.onNodeWithTag("video-$videoId").performTouchInput { longClick() }
        compose.onNodeWithTag("bulk-edit").performClick()
        compose.onNodeWithTag("bulk-add-tags").performTextInput("qa-bulk")
        compose.onNodeWithTag("bulk-apply").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { runBlocking { app.container.repository.video(videoId) }.tags.contains("qa-bulk") }

        // Clean up through the UI: delete asks first (Cancel focused), then deletes.
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("selection-bar").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("video-$videoId").performTouchInput { longClick() }
        compose.onNodeWithTag("bulk-delete").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isEmpty() }

        // Sign out (revokes the refresh token, opens the auth logout page in the browser).
        compose.onNodeWithTag("account-button").performClick()
        val user = app.container.session.active.value!!.userId
        compose.onNodeWithTag("sign-out-$user").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { app.container.session.accounts.value.none { it.userId == user } }
        device.pressBack()
    }

    private fun signIn(email: String, password: String) {
        compose.waitUntil(LIST_TIMEOUT_MS) {
            compose.onAllNodesWithTag("signin-button").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty()) return
        compose.onNodeWithTag("signin-button").performClick()
        // The auth form in the Custom Tab (Chrome). The browser may already be signed in.
        val emailField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), BROWSER_TIMEOUT_MS)
        if (emailField != null) {
            emailField.text = email
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            fields.last().text = password
            device.findObject(By.text("Continue"))?.click() ?: device.pressEnter()
        }
        compose.waitUntil(SIGN_IN_TIMEOUT_MS) { compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Picks the seed channel "Al Ummah Now QA — Lectures" (or the only one). */
    private fun pickChannel(): String {
        compose.waitUntil(LIST_TIMEOUT_MS) {
            compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("dashboard").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithTag("channel-list").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNode(hasText("Lectures", substring = true) and channelRow).performClick()
        }
        compose.waitUntil(LIST_TIMEOUT_MS) { app.container.session.active.value?.activeChannelId != null }
        return app.container.session.active.value!!.activeChannelId!!
    }

    private val channelRow = SemanticsMatcher("channel row") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("channel-") == true }

    private fun copyAsset(context: Context, name: String): File {
        val out = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val BROWSER_TIMEOUT_MS = 20_000L
        const val SIGN_IN_TIMEOUT_MS = 30_000L
        const val LIST_TIMEOUT_MS = 30_000L
        const val UPLOAD_TIMEOUT_MS = 180_000L
        const val TAB_SWITCH_MS = 3_000L
    }
}
