package net.alummahnow.studio

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
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
import java.io.File
import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.Visibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith

/**
 * The real app against QA (videos.api.qa / auth.qa): browser sign-in with PKCE in a Custom Tab,
 * a resumable upload through aun-upload and the WorkManager worker, bulk edit through the UI, then
 * clean-up and sign-out. Runs only in the release gate (qa-e2e.yml, scripts/qa-deployed-tests.sh), never on
 * PRs or main; skipped when the QA seed password is not passed (`-e qaPassword`).
 */
@RunWith(AndroidJUnit4::class)
class StudioQaFlowTest {
    @get:Rule(order = 0)
    val compose = createAndroidComposeRule<MainActivity>()

    /** On failure, a device screenshot lands in the connected-test additional output (uploaded by CI). */
    @get:Rule(order = 1)
    val screenshotOnFailure = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
            runCatching { device.takeScreenshot(File(dir, "${description.methodName}-failure.png")) }
        }
    }

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

        // When the Terms switch is on, the server refuses an upload until they are accepted.
        runBlocking {
            val access = app.container.repository.creatorAccess()
            if (access.termsAccepted == false) access.termsVersion?.let { app.container.repository.acceptCreatorTerms(it) }
        }

        val title = "android-qa-${System.currentTimeMillis()}"
        try {
            uploadEditAndDelete(channel, title)
        } catch (failure: Throwable) {
            removeLeftovers(channel, title, failure)
            throw failure
        }

        // Sign out (revokes the refresh token, opens the auth logout page in the browser).
        compose.onNodeWithTag("account-button").performClick()
        val user = app.container.session.active.value!!.userId
        compose.onNodeWithTag("sign-out-$user").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { app.container.session.accounts.value.none { it.userId == user } }
        device.pressBack()
    }

    private fun uploadEditAndDelete(channel: String, title: String) {
        // Upload: a 3-second clip, private, with a unique title, through the real queue + worker.
        val clip = copyAsset(compose.activity, "qa-test-clip.mp4")
        runBlocking {
            app.container.uploads.enqueue(
                listOf(
                    UploadDraft(
                        clip.absolutePath, clip.name, clip.length(), "video/mp4", title, "Instrumented test", channel, Visibility.PRIVATE,
                        tags = listOf(
                            "qa-instrumented"
                        )
                    )
                )
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
        findInAnyTab(videoId, channel, title)
        compose.onNodeWithTag("video-$videoId").performTouchInput { longClick() }
        compose.onNodeWithTag("bulk-edit").performClick()
        compose.onNodeWithTag("bulk-add-tags").performTextInput("qa-bulk")
        compose.onNodeWithTag("bulk-apply").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { runBlocking { app.container.repository.video(videoId) }.tags.contains("qa-bulk") }

        // Delete through the UI (asks first), restore it from Recently deleted, then delete it for good.
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("selection-bar").fetchSemanticsNodes().isEmpty() }
        deleteFromList(videoId)
        openTab("status-deleted")
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("video-$videoId").performClick() // selects in this tab
        compose.onNodeWithTag("bulk-restore").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isEmpty() }
        assertEquals(title, runBlocking { app.container.repository.video(videoId) }.title)

        openTab("status-processing")
        findInAnyTab(videoId, channel, title)
        deleteFromList(videoId)
        openTab("status-deleted")
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("forever-$videoId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("forever-$videoId").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isEmpty() }
    }

    /** A failed run leaves nothing on QA: what it uploaded is deleted for good, whatever tab it is (or isn't) in. */
    private fun removeLeftovers(channel: String, title: String, failure: Throwable) {
        val repository = app.container.repository
        runCatching {
            runBlocking {
                val queued = app.container.uploads.items.value.filter { it.title == title }
                queued.filterNot { it.isFinished }.forEach { app.container.uploads.cancel(it.id) }
                val found = ContentStatus.entries.flatMap { status ->
                    runCatching { repository.content(ContentQuery(channel, status, title)).items }.getOrDefault(emptyList())
                }.filter { it.title == title }.map { it.id }
                (queued.mapNotNull { it.videoId } + found).distinct().forEach { id ->
                    // Already in Recently deleted is fine: only the second step has to succeed.
                    runCatching { repository.delete(listOf(id), channel) }
                    // Not there any more is what it is for.
                    runCatching { repository.deletePermanently(id, channel) }
                        .onFailure { if (it !is CoreError.NotFound && it !is StudioError.NotSupported) throw it }
                }
            }
        }.onFailure { failure.addSuppressed(AssertionError("left on QA: $title", it)) }
    }

    /** What the server says about the video, for a list that does not show it: is it the app or the server? */
    private fun serverSays(videoId: String, channel: String, title: String): String = runBlocking {
        val repository = app.container.repository
        val record = runCatching { repository.video(videoId) }.fold({ "status=${it.status} public=${it.isPublic}" }, { "$it" })
        val lists = (ContentStatus.entries - ContentStatus.DELETED).map { status ->
            val named = runCatching { repository.content(ContentQuery(channel, status, title)).items }
            val all = runCatching { repository.content(ContentQuery(channel, status)).items }
            "${status.apiValue}: ${size(named)} with the title, ${size(all)} on the first page"
        }.joinToString()
        val counts = runCatching { repository.counts(channel) }.fold({ "$it" }, { "$it" })
        "video by id: $record; lists: $lists; counts: $counts"
    }

    private fun size(answer: Result<List<*>>): String = answer.fold({ "${it.size}" }, { "$it" })

    /** Waits until the video shows in one of the live tabs. Where it shows depends on the server's
     *  processing (Processing, then Published; Failed if the clip could not be processed). */
    private fun findInAnyTab(videoId: String, channel: String, title: String) {
        var i = 0
        try {
            compose.waitUntil(UPLOAD_TIMEOUT_MS) {
                val found = has("video-$videoId")
                if (!found) {
                    openTab(LIVE_TABS[i++ % LIVE_TABS.size], optional = true)
                    Thread.sleep(TAB_SWITCH_MS)
                }
                found
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the new video is in no tab; the server says: ${serverSays(videoId, channel, title)}", e)
        }
    }

    /**
     * Taps a status tab; one that doesn't fit the row is in its More menu. A tab that isn't there fails the test, unless
     * it is [optional] and one of the review tabs, which are only there with a video in them.
     */
    private fun openTab(tag: String, optional: Boolean = false) {
        val menu = !has(tag) && has("status-more")
        if (menu) compose.onNodeWithTag("status-more").performClick()
        if (optional && tag in REVIEW_TABS && !has(tag)) {
            if (menu) device.pressBack() // closes it
            return
        }
        compose.onNodeWithTag(tag).performClick()
    }

    private fun deleteFromList(videoId: String) {
        compose.onNodeWithTag("video-$videoId").performTouchInput { longClick() }
        compose.onNodeWithTag("bulk-delete").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitUntil(LIST_TIMEOUT_MS) { compose.onAllNodesWithTag("video-$videoId").fetchSemanticsNodes().isEmpty() }
    }

    /** True when a node with [tag] is on screen. While the browser covers the app there is no
     *  Compose hierarchy at all, which counts as "not there yet". */
    private fun has(
        tag: String
    ): Boolean = runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun signIn(email: String, password: String) {
        compose.waitUntil(LIST_TIMEOUT_MS) { has("signin-button") || has("account-button") }
        if (has("account-button")) return
        compose.onNodeWithTag("signin-button").performClick()
        // The auth form in a Chrome Custom Tab. A fresh emulator's Chrome may first ask about terms,
        // sign-in or notifications; the browser may also already be signed in (then it returns at once).
        val deadline = System.currentTimeMillis() + SIGN_IN_TIMEOUT_MS
        var submitted = false
        while (System.currentTimeMillis() < deadline && !has("account-button")) {
            val prompt = CHROME_PROMPTS.firstNotNullOfOrNull { device.findObject(By.text(it)) }
            if (prompt != null) {
                prompt.click()
            } else if (!submitted) {
                val fields = device.findObjects(By.clazz("android.widget.EditText"))
                if (fields.size >= 2) {
                    fields.first().text = email
                    fields.last().text = password
                    device.findObject(By.text("Continue"))?.click() ?: device.pressEnter()
                    submitted = true
                }
            }
            device.waitForIdle(POLL_MS)
            Thread.sleep(POLL_MS)
        }
        compose.waitUntil(LIST_TIMEOUT_MS) { has("account-button") }
    }

    /** Picks the seed channel "Al Ummah Now QA — Lectures" (or the only one). */
    private fun pickChannel(): String {
        compose.waitUntil(LIST_TIMEOUT_MS) { has("channel-list") || has("dashboard") }
        if (has("channel-list")) {
            compose.onNode(hasText("Lectures", substring = true) and channelRow).performClick()
        }
        compose.waitUntil(LIST_TIMEOUT_MS) { app.container.session.active.value?.activeChannelId != null }
        return app.container.session.active.value!!.activeChannelId!!
    }

    private val channelRow = SemanticsMatcher("channel row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("channel-") ==
            true
    }

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
        const val SIGN_IN_TIMEOUT_MS = 90_000L
        const val POLL_MS = 1_000L

        // "Wait" dismisses a system "… isn't responding" dialog (a slow CI emulator's launcher) over the browser.
        val CHROME_PROMPTS = listOf(
            "Wait",
            "Accept & continue",
            "Use without an account",
            "No thanks",
            "No, thanks",
            "Got it",
            "Continue as guest"
        )

        const val LIST_TIMEOUT_MS = 30_000L
        const val UPLOAD_TIMEOUT_MS = 180_000L
        const val TAB_SWITCH_MS = 2_000L
        val REVIEW_TABS = listOf("status-in_review", "status-not_approved")
        val LIVE_TABS = listOf("status-processing", "status-published") + REVIEW_TABS +
            listOf("status-failed", "status-drafts", "status-scheduled")
    }
}
