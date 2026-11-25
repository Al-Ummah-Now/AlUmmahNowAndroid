package net.alummahnow.feature.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.ReviewStep
import net.alummahnow.core.bridge.studio.VideoReview
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.content.AppealDialog
import net.alummahnow.feature.studio.content.AppealState
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentNotice
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.content.ContentViewModel
import net.alummahnow.feature.studio.content.reasonText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In review and Not approved (#77): no video is in no tab, its words are the web Studio's. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReviewTest {
    @get:Rule val compose = createComposeRule()
    private val dispatcher = StandardTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    private val rejected = VideoReview(ReviewStep.REJECTED, reason = "copyright")

    private fun repo(review: VideoReview = rejected) = FakeRepository().apply {
        videos.replaceAll { if (it.id == "v1") it.copy(status = ContentStatus.NOT_APPROVED, review = review) else it }
    }

    @Test
    fun `a video sent for a new check moves to In review`() = runTest(dispatcher) {
        val repo = repo()
        repo.reviewQueueMinutes = 40
        val vm = ContentViewModel(repo, "c1")
        vm.setStatus(ContentStatus.NOT_APPROVED)
        advanceUntilIdle()
        assertEquals(listOf("v1"), vm.state.value.videos.map { it.id })
        assertEquals(40, vm.state.value.reviewQueueMinutes)
        vm.resubmit("v1")
        vm.resubmit("v1") // a second tap while it is sent
        advanceUntilIdle()
        assertEquals(listOf("v1"), repo.resubmits)
        assertEquals(ContentNotice.Review(R.string.studio_review_resubmitted), vm.state.value.notice)
        assertTrue(vm.state.value.videos.isEmpty())
        vm.setStatus(ContentStatus.IN_REVIEW)
        advanceUntilIdle()
        assertEquals(listOf("v1"), vm.state.value.videos.map { it.id })
    }

    @Test
    fun `a new check that can't be sent says so and changes nothing`() = runTest(dispatcher) {
        val repo = repo().apply { reviewError = CoreError.Http(409) }
        val vm = ContentViewModel(repo, "c1")
        vm.setStatus(ContentStatus.NOT_APPROVED)
        advanceUntilIdle()
        vm.resubmit("v1")
        advanceUntilIdle()
        assertEquals(ContentNotice.Review(R.string.studio_review_resubmit_failed), vm.state.value.notice)
        assertEquals(listOf("v1"), vm.state.value.videos.map { it.id })
        assertTrue(!vm.state.value.busy)
    }

    @Test
    fun `an appeal is sent once, and the dialog stays with its text when it can't be`() = runTest(dispatcher) {
        val repo = repo().apply { reviewError = CoreError.Http(500) }
        val vm = ContentViewModel(repo, "c1")
        vm.setStatus(ContentStatus.NOT_APPROVED)
        advanceUntilIdle()
        vm.openAppeal("v1")
        vm.sendAppeal("  ")
        advanceUntilIdle()
        assertEquals(AppealState("v1"), vm.state.value.appeal)
        vm.sendAppeal("mine")
        vm.closeAppeal() // not while it is being sent
        assertEquals(true, vm.state.value.appeal?.sending)
        advanceUntilIdle()
        assertEquals(AppealState("v1", failed = true), vm.state.value.appeal)
        repo.reviewError = null
        vm.sendAppeal(" It is my own recording. ")
        vm.sendAppeal("again")
        advanceUntilIdle()
        assertEquals(listOf("v1" to "It is my own recording."), repo.appeals)
        assertNull(vm.state.value.appeal)
        assertEquals(ContentNotice.Review(R.string.studio_appeal_sent), vm.state.value.notice)
        assertTrue(vm.state.value.videos.isEmpty())
    }

    // --- What the screen shows ---

    private val log = mutableListOf<String>()
    private val actions = ContentActions(
        onStatus = { log += "status:$it" }, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {},
        onEdit = { log += "edit:$it" }, onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {},
        onResubmit = { log += "resubmit:$it" }, onAppeal = { log += "appeal:$it" }
    )

    private fun tab(status: ContentStatus, vararg reviews: VideoReview, queue: Int? = null) = ContentUiState(
        status = status,
        loading = false,
        softDelete = true,
        reviewQueueMinutes = queue,
        videos = reviews.mapIndexed { i, r -> libraryVideos[i].copy(status = status, isPublic = true, views = 0, review = r) }
    )

    private val inReview = tab(
        ContentStatus.IN_REVIEW,
        VideoReview(ReviewStep.CHECKING, etaMinutes = 5),
        VideoReview(ReviewStep.HELD),
        VideoReview(ReviewStep.APPEALED, appealUsed = true),
        queue = 40
    )
    private val notApproved = tab(
        ContentStatus.NOT_APPROVED,
        rejected,
        VideoReview(ReviewStep.REJECTED, reason = "misleading_metadata", appealUsed = true)
    )

    private fun show(state: ContentUiState, qualifiers: String = "en-ldltr-w411dp-h900dp-port", fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent { AunTheme { ContentScreen(state, actions) } }
    }

    private fun line(i: Int) = compose.onNodeWithTag("review-${libraryVideos[i].id}", useUnmergedTree = true)

    @Test
    fun `each step says where the video is`() {
        show(inReview)
        line(0).assertTextEquals("In review • usually within 5 minutes")
        line(1).assertTextEquals("A reviewer will check it • about 40 min")
        line(2).assertTextEquals("Appeal sent • a different reviewer will check it")
        compose.onNodeWithTag("appeal-${libraryVideos[0].id}").assertDoesNotExist()
        compose.onNodeWithTag("resubmit-${libraryVideos[0].id}").assertDoesNotExist()
    }

    @Test
    fun `without a known queue no time is promised`() {
        show(tab(ContentStatus.IN_REVIEW, VideoReview(ReviewStep.HELD)))
        line(0).assertTextEquals("A reviewer will check it")
    }

    @Test
    fun `a video that was turned down says why and what can be done, the appeal once`() {
        show(notApproved)
        val (first, second) = libraryVideos.map { it.id }
        line(0).assertTextEquals("Not approved: content you do not have the rights to share")
        compose.onNodeWithTag("appeal-$first").performClick()
        compose.onNodeWithTag("resubmit-$first").performClick()
        compose.onNodeWithTag("resubmit-$second").performClick()
        compose.onNodeWithTag("appeal-$second").assertDoesNotExist()
        assertEquals(listOf("appeal:$first", "resubmit:$first", "resubmit:$second"), log)
    }

    @Test
    fun `Arabic counts and digits are right`() {
        show(inReview, "ar-ldrtl-w411dp-h900dp-port")
        line(0).assertTextEquals("قيد المراجعة، عادةً خلال ٥ دقائق")
        line(1).assertTextEquals("سيراجعه أحد المراجعين، نحو ٤٠ دقيقة")
    }

    @Test
    fun `every reason has its own words, and an unknown one the general ones`() {
        val codes = listOf(
            "sexual", "child_safety", "graphic_violence", "self_harm", "hate", "violent_extremism", "harassment", "spam_scam",
            "impersonation", "privacy", "copyright", "misleading_metadata", "religious_misattribution", "other"
        )
        assertEquals(codes.size, codes.map(::reasonText).toSet().size)
        assertEquals(R.string.studio_review_reason_other, reasonText("something_new"))
        assertEquals(R.string.studio_review_reason_other, reasonText(null))
    }

    @Test
    fun `an empty tab says which one is empty`() {
        show(tab(ContentStatus.NOT_APPROVED))
        compose.onNodeWithTag("content-empty").assertTextEquals("No videos were turned down.")
    }

    @Test
    fun `on a phone the tabs are one row, the others are under More, and the chosen one is in the row`() {
        show(tab(ContentStatus.PUBLISHED))
        val more = compose.onNodeWithTag("status-more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val published = compose.onNodeWithTag("status-published").fetchSemanticsNode().boundsInRoot
        assertEquals(published.top, more.top)
        assertTrue("$more", more.right <= compose.onNodeWithTag("content-search").fetchSemanticsNode().boundsInRoot.right)
        compose.onNodeWithTag("status-not_approved").assertDoesNotExist()
        compose.onNodeWithTag("status-more").performClick()
        compose.onNodeWithTag("status-in_review").assertIsDisplayed()
        compose.onNodeWithTag("status-not_approved").performClick()
        assertEquals(listOf("status:NOT_APPROVED"), log)
    }

    @Test
    fun `a chosen tab from More takes a place in the row`() {
        show(notApproved)
        compose.onNodeWithTag("status-not_approved").assertIsDisplayed()
        compose.onNodeWithTag("status-more").assertIsDisplayed()
        compose.onNodeWithTag("status-published").performClick()
        assertEquals(listOf("status:PUBLISHED"), log)
    }

    @Test
    fun `on a tablet every tab is in the row`() {
        show(tab(ContentStatus.PUBLISHED), "en-ldltr-w1280dp-h800dp-land")
        ContentStatus.entries.forEach { compose.onNodeWithTag("status-${it.apiValue}").assertIsDisplayed() }
        compose.onNodeWithTag("status-more").assertDoesNotExist()
    }

    @Test
    fun `at a large font the tabs are still one row inside the screen`() {
        show(notApproved, "ar-ldrtl-w360dp-h800dp-port", fontScale = 2f)
        val more = compose.onNodeWithTag("status-more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val chosen = compose.onNodeWithTag("status-not_approved").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("$more", more.left >= 0f)
        assertEquals(chosen.top, more.top)
        assertTrue("$chosen $more", chosen.left >= more.right)
    }

    @Test
    fun `a chosen tab wider than the screen leaves room for the whole of More`() {
        var state by mutableStateOf(tab(ContentStatus.DRAFTS))
        RuntimeEnvironment.setQualifiers("en-ldltr-w320dp-h800dp-port")
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { AunTheme { ContentScreen(state, actions) } }
        val whole = compose.onNodeWithTag("status-more").fetchSemanticsNode().size.width
        state = tab(ContentStatus.DELETED)
        compose.waitForIdle()
        val more = compose.onNodeWithTag("status-more").assertIsDisplayed().fetchSemanticsNode()
        assertEquals(whole, more.size.width)
        assertTrue(more.boundsInRoot.right <= compose.onNodeWithTag("content-search").fetchSemanticsNode().boundsInRoot.right)
    }

    @Test
    fun `an appeal needs words, has a limit, and says when it failed`() {
        val sent = mutableListOf<String>()
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h900dp-port")
        compose.setContent { AunTheme { AppealDialog(AppealState("v1", failed = true), { sent += it }, {}) } }
        compose.onNodeWithTag("appeal-send").assertIsNotEnabled()
        compose.onNodeWithTag("appeal-failed").assertTextContains("Could not send the appeal. Try again.")
        compose.onNodeWithTag("appeal-text").performTextInput("x".repeat(2_100))
        compose.onNodeWithTag("appeal-send").assertIsEnabled().performClick()
        assertEquals(2_000, sent.single().length)
        compose.onNodeWithTag("appeal-text").performTextClearance()
        compose.onNodeWithTag("appeal-send").assertIsNotEnabled()
    }

    // --- Screenshots ---

    private fun shootReview(status: String, state: ContentUiState, lang: Lang, mode: ThemeMode, device: Device = Device.PHONE) =
        shoot("content_$status", lang, mode, device) { ContentScreen(state, actions) }

    @Test fun inReviewEnDark() = shootReview("in_review", inReview, Lang.EN, ThemeMode.DARK)

    @Test fun inReviewEnLight() = shootReview("in_review", inReview, Lang.EN, ThemeMode.LIGHT)

    @Test fun inReviewArDark() = shootReview("in_review", inReview, Lang.AR, ThemeMode.DARK)

    @Test fun inReviewArLight() = shootReview("in_review", inReview, Lang.AR, ThemeMode.LIGHT)

    @Test fun inReviewTabletEnDark() = shootReview("in_review", inReview, Lang.EN, ThemeMode.DARK, Device.TABLET)

    @Test fun notApprovedEnDark() = shootReview("not_approved", notApproved, Lang.EN, ThemeMode.DARK)

    @Test fun notApprovedEnLight() = shootReview("not_approved", notApproved, Lang.EN, ThemeMode.LIGHT)

    @Test fun notApprovedArDark() = shootReview("not_approved", notApproved, Lang.AR, ThemeMode.DARK)

    @Test fun notApprovedArLight() = shootReview("not_approved", notApproved, Lang.AR, ThemeMode.LIGHT)

    @Test fun notApprovedTabletArLight() = shootReview("not_approved", notApproved, Lang.AR, ThemeMode.LIGHT, Device.TABLET)

    @Test fun emptyArDark() = shootReview("in_review_empty", tab(ContentStatus.IN_REVIEW), Lang.AR, ThemeMode.DARK)

    /** Whole screens: a menu and a dialog are windows of their own. */
    private fun shootScreen(
        name: String,
        lang: Lang,
        mode: ThemeMode,
        fontScale: Float = 1f,
        tap: String? = null,
        content: @Composable () -> Unit
    ) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-${Device.PHONE.qualifier}")
        RuntimeEnvironment.setFontScale(fontScale)
        android.provider.Settings.Global.putFloat(
            RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )
        compose.setContent {
            AunTheme(mode = mode) {
                WithFixtureImages { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
            }
        }
        tap?.let { compose.onNode(hasTestTag(it)).performClick() }
        compose.waitForIdle()
        val size = if (fontScale == 1f) "" else "_font200"
        captureScreenRoboImage("src/test/screenshots/${name}${size}_${lang.name.lowercase()}_${mode.name.lowercase()}_phone.png")
    }

    private fun more(lang: Lang, mode: ThemeMode) =
        shootScreen("content_more", lang, mode, tap = "status-more") {
            ContentScreen(ContentUiState(videos = libraryVideos, loading = false, softDelete = true), actions)
        }

    @Test fun moreEnDark() = more(Lang.EN, ThemeMode.DARK)

    @Test fun moreEnLight() = more(Lang.EN, ThemeMode.LIGHT)

    @Test fun moreArDark() = more(Lang.AR, ThemeMode.DARK)

    @Test fun moreArLight() = more(Lang.AR, ThemeMode.LIGHT)

    private fun appeal(
        lang: Lang,
        mode: ThemeMode,
        state: AppealState = AppealState(
            "v1"
        ),
        text: String = "",
        name: String = "appeal",
        scale: Float = 1f
    ) =
        shootScreen(name, lang, mode, scale) { AppealDialog(state, {}, {}, initialText = text) }

    private val words = mapOf(
        Lang.EN to "The recording and the pictures are our own.",
        Lang.AR to "التسجيل والصور من إنتاجنا."
    )

    @Test fun appealEnDark() = appeal(Lang.EN, ThemeMode.DARK)

    @Test fun appealEnLight() = appeal(Lang.EN, ThemeMode.LIGHT, text = words.getValue(Lang.EN), name = "appeal_typed")

    @Test fun appealArDark() = appeal(Lang.AR, ThemeMode.DARK, text = words.getValue(Lang.AR), name = "appeal_typed")

    @Test fun appealArLight() = appeal(Lang.AR, ThemeMode.LIGHT)

    @Test fun appealFailedEnLight() =
        appeal(Lang.EN, ThemeMode.LIGHT, AppealState("v1", failed = true), words.getValue(Lang.EN), "appeal_failed")

    @Test fun appealFailedArDark200() =
        appeal(Lang.AR, ThemeMode.DARK, AppealState("v1", failed = true), words.getValue(Lang.AR), "appeal_failed", scale = 2f)

    @Test fun notApprovedArDark200() = shootScreen("content_not_approved", Lang.AR, ThemeMode.DARK, 2f) {
        ContentScreen(notApproved, actions)
    }

    @Test fun inReviewEnLight200() = shootScreen("content_in_review", Lang.EN, ThemeMode.LIGHT, 2f) { ContentScreen(inReview, actions) }
}
