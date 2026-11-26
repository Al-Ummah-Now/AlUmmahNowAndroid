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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.ContentPage
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.ReviewStep
import net.alummahnow.core.bridge.studio.VideoReview
import net.alummahnow.design.format.isolate
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
import org.junit.Assert.assertFalse
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
        advanceUntilIdle()
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
        val repo = repo().apply { reviewError = CoreError.Http(500) }
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
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
        advanceUntilIdle()
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

    private fun TestScope.notApproved(repo: FakeRepository): ContentViewModel {
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.setStatus(ContentStatus.NOT_APPROVED)
        advanceUntilIdle()
        return vm
    }

    private fun ContentViewModel.ids() = state.value.videos.map { it.id }

    @Test
    fun `a video that was sent can't be sent again while the list reloads, nor when that fails`() = runTest(dispatcher) {
        val appeals: ContentViewModel.() -> Unit = {
            openAppeal("v1")
            sendAppeal("mine")
        }
        for (send in listOf<ContentViewModel.() -> Unit>({ resubmit("v1") }, appeals)) {
            val repo = repo()
            val vm = notApproved(repo)
            vm.toggle("v1")
            val reload = CompletableDeferred<Unit>()
            repo.contentAnswer = {
                reload.await()
                throw CoreError.Http(500)
            }
            vm.send()
            runCurrent()
            assertTrue(vm.state.value.loading)
            assertTrue(vm.ids().isEmpty())
            assertTrue(vm.state.value.selected.isEmpty())
            vm.send()
            reload.complete(Unit)
            advanceUntilIdle()
            vm.send()
            advanceUntilIdle()
            assertEquals(1, repo.resubmits.size + repo.appeals.size)
            assertTrue(vm.ids().isEmpty())
        }
    }

    @Test
    fun `an appeal and a new check are never both on their way`() = runTest(dispatcher) {
        val repo = repo()
        val vm = notApproved(repo)
        vm.resubmit("v1")
        vm.openAppeal("v1")
        assertNull(vm.state.value.appeal)
        advanceUntilIdle()
        val other = repo()
        val second = notApproved(other)
        second.openAppeal("v1")
        second.sendAppeal("mine")
        second.resubmit("v1")
        advanceUntilIdle()
        assertEquals(1 to 0, other.appeals.size to other.resubmits.size)
        val third = repo()
        val both = notApproved(third)
        both.openAppeal("v1")
        both.resubmit("v1")
        both.sendAppeal("mine")
        advanceUntilIdle()
        assertEquals(0 to 1, third.appeals.size to third.resubmits.size)
    }

    @Test
    fun `a video that moved on elsewhere leaves the list, and no one is told to try again`() = runTest(dispatcher) {
        for (appeal in listOf(false, true)) {
            val repo = repo().apply { reviewError = CoreError.Http(409) }
            val vm = notApproved(repo)
            repo.onReview = {
                repo.videos.replaceAll { if (it.id == "v1") it.copy(status = ContentStatus.IN_REVIEW) else it }
            }
            if (appeal) {
                vm.openAppeal("v1")
                vm.sendAppeal("mine")
            } else {
                vm.resubmit("v1")
            }
            advanceUntilIdle()
            assertEquals(ContentNotice.Review(R.string.studio_review_changed), vm.state.value.notice)
            assertNull(vm.state.value.appeal)
            assertTrue(!vm.state.value.busy)
            assertTrue(vm.ids().isEmpty())
            assertTrue(ContentStatus.IN_REVIEW in vm.state.value.tabs)
        }
    }

    @Test
    fun `when sending fails the list is read again`() = runTest(dispatcher) {
        for (appeal in listOf(false, true)) {
            val repo = repo().apply { reviewError = CoreError.Http(500) }
            val vm = notApproved(repo)
            repo.onReview = { repo.videos.removeAll { it.id == "v1" } }
            if (appeal) {
                vm.openAppeal("v1")
                vm.sendAppeal("mine")
            } else {
                vm.resubmit("v1")
            }
            advanceUntilIdle()
            assertTrue(vm.ids().isEmpty())
        }
    }

    // --- Its tabs ---

    private val reviewTabs = setOf(ContentStatus.IN_REVIEW, ContentStatus.NOT_APPROVED)

    private fun ContentViewModel.reviewTabs() = state.value.tabs.filter { it in reviewTabs }

    @Test
    fun `a review tab is there only with a video in it, and an open one until it is left`() = runTest(dispatcher) {
        val off = ContentViewModel(FakeRepository(), "c1")
        advanceUntilIdle()
        assertTrue(off.reviewTabs().isEmpty())
        off.setStatus(ContentStatus.IN_REVIEW) // not there, so nothing to open
        assertEquals(ContentStatus.PUBLISHED, off.state.value.status)

        val repo = repo()
        val vm = notApproved(repo)
        assertEquals(listOf(ContentStatus.NOT_APPROVED), vm.reviewTabs())
        val counts = CompletableDeferred<Unit>()
        repo.countsGate = counts
        vm.resubmit("v1")
        advanceUntilIdle()
        assertEquals(reviewTabs.toList(), vm.reviewTabs()) // at once, before the new counts are here
        counts.complete(Unit)
        advanceUntilIdle()
        assertEquals(reviewTabs.toList(), vm.reviewTabs())
        vm.setStatus(ContentStatus.PUBLISHED)
        advanceUntilIdle()
        assertEquals(listOf(ContentStatus.IN_REVIEW), vm.reviewTabs())
    }

    @Test
    fun `counts that can't be read leave the review tabs as they were, a server without them has none`() = runTest(dispatcher) {
        val repo = repo()
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        repo.countsError = CoreError.Http(500)
        vm.load()
        advanceUntilIdle()
        assertEquals(listOf(ContentStatus.NOT_APPROVED), vm.reviewTabs())
        repo.countsError = null
        repo.countsMissing = true
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.reviewTabs().isEmpty())
    }

    // --- More of a list ---

    private val held = libraryVideos.map { it.copy(status = ContentStatus.IN_REVIEW, review = VideoReview(ReviewStep.HELD)) }

    private fun paged(second: suspend () -> ContentPage) = FakeRepository().apply {
        videos += held[0].copy(id = "r1")
        contentAnswer = { q ->
            when {
                q.status != ContentStatus.IN_REVIEW -> null
                q.cursor == null -> ContentPage(listOf(held[0]), "2", null)
                else -> second()
            }
        }
    }

    private fun TestScope.inReview(repo: FakeRepository): ContentViewModel {
        val vm = ContentViewModel(repo, "c1")
        advanceUntilIdle()
        vm.setStatus(ContentStatus.IN_REVIEW)
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `the wait is said when only a later page knows it, and kept when the next one does not`() = runTest(dispatcher) {
        var page = 0
        val repo = paged { if (page++ == 0) ContentPage(listOf(held[1]), "3", 40) else ContentPage(listOf(held[2]), null, null) }
        val vm = inReview(repo)
        assertNull(vm.state.value.reviewQueueMinutes)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(40, vm.state.value.reviewQueueMinutes)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(held.take(3).map { it.id }, vm.ids())
        assertEquals(40, vm.state.value.reviewQueueMinutes)
        vm.setStatus(ContentStatus.PUBLISHED)
        assertNull(vm.state.value.reviewQueueMinutes)
        assertNull(vm.state.value.cursor)
    }

    @Test
    fun `a page that comes after the tab was left or the list read again is not added`() = runTest(dispatcher) {
        for (next in listOf<ContentViewModel.() -> Unit>({ setStatus(ContentStatus.PUBLISHED) }, { load() })) {
            val gate = CompletableDeferred<Unit>()
            val repo = paged {
                gate.await()
                ContentPage(listOf(held[1].copy(id = "late")), null, null)
            }
            val vm = inReview(repo)
            vm.loadMore()
            runCurrent()
            assertTrue(vm.state.value.loadingMore)
            vm.next()
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue("late" !in vm.ids())
            assertTrue(!vm.state.value.loadingMore)
        }
    }

    @Test
    fun `more of a list is asked for with the words it was found with`() = runTest(dispatcher) {
        val repo = paged { ContentPage(emptyList(), null, null) }
        val vm = inReview(repo)
        vm.setQuery("tafsir")
        vm.loadMore()
        runCurrent()
        val asked = repo.queries.last()
        assertEquals("2" to "", asked.cursor to asked.text)
    }

    // --- What the screen shows ---

    private val log = mutableListOf<String>()
    private val actions = ContentActions(
        onStatus = { log += "status:$it" }, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {},
        onEdit = { log += "edit:$it" }, onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {},
        onResubmit = { log += "resubmit:$it" }, onAppeal = { log += "appeal:$it" }
    )

    private fun tab(status: ContentStatus, vararg reviews: VideoReview, queue: Int? = null) = ContentUiState(
        reviewTabs = reviewTabs,
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

    private fun layout(tag: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val tagged = hasTestTag("$tag-${libraryVideos[0].id}")
        // The row's texts carry their tag themselves, a button's text is inside it.
        compose.onNode((tagged or hasAnyAncestor(tagged)) and hasTextLayout, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

    private fun assertWhole(tag: String) = with(layout(tag)) {
        val last = lineCount - 1
        val shown = "$tag: ${layoutInput.text}, $lineCount lines to ${getLineEnd(last, visibleEnd = true)}"
        assertFalse(shown, multiParagraph.didExceedMaxLines || isLineEllipsized(last))
        assertEquals(shown, layoutInput.text.length, getLineEnd(last))
        // No line ends inside a word.
        (0 until last).map { layoutInput.text[getLineEnd(it) - 1] }.forEach { assertTrue("$shown: '$it'", it.isWhitespace() || it == '-') }
    }

    @Test
    fun `at a large font no word of a row is cut or broken`() {
        show(inReview, fontScale = 2f)
        listOf("title", "review", "meta", "date").forEach(::assertWhole)
    }

    @Test
    fun `nor on a narrow phone`() {
        show(notApproved, "en-ldltr-w320dp-h1600dp-port", fontScale = 2f)
        listOf("title", "review", "meta", "date").forEach(::assertWhole)
    }

    @Test
    fun `a long title at a large font has four lines`() {
        val long = libraryVideos[0].copy(title = "A title with a good many words ".repeat(8), titleLocalized = emptyMap())
        show(inReview.copy(videos = listOf(long)), fontScale = 2f)
        assertEquals(4, layout("title").lineCount)
    }

    @Test
    fun `on a tablet the words stay beside the thumbnail at a large font`() {
        show(inReview, "en-ldltr-w800dp-h1280dp-port", fontScale = 2f)
        val id = libraryVideos[0].id
        val title = compose.onNodeWithTag("title-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val check = compose.onNodeWithTag("select-$id").fetchSemanticsNode().boundsInRoot
        assertTrue("$title $check", title.left > check.right)
    }

    @Test
    fun `beside the thumbnail on a narrow phone the date takes the lines it needs`() {
        show(inReview, "en-ldltr-w292dp-h1600dp-port", fontScale = 1.2f)
        assertWhole("date")
        assertTrue(layout("date").lineCount > 1)
    }

    @Test
    fun `at a large font on a narrow phone the actions are whole, and inside the card`() {
        show(notApproved, "en-ldltr-w320dp-h1600dp-port", fontScale = 2f)
        assertActionsWhole()
    }

    @Test
    fun `at a large font on a narrow phone the Arabic actions are whole, and inside the card`() {
        show(notApproved, "ar-ldrtl-w320dp-h1600dp-port", fontScale = 2f)
        assertActionsWhole()
    }

    private fun assertActionsWhole() {
        val card = compose.onNodeWithTag("video-${libraryVideos[0].id}").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("appeal", "resubmit")) {
            assertWhole(tag)
            val button = compose.onNodeWithTag("$tag-${libraryVideos[0].id}").fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $button in $card", button.left >= card.left && button.right <= card.right)
            assertTrue("$tag $button in $card", button.bottom <= card.bottom)
        }
        assertTrue(layout("resubmit").lineCount > 1)
    }

    @Test
    fun `the actions are named with their video`() {
        show(notApproved)
        val (first, _) = libraryVideos
        compose.onNodeWithTag("appeal-${first.id}").assertContentDescriptionEquals("Appeal: ${isolate(first.title)}")
        compose.onNodeWithTag("resubmit-${first.id}")
            .assertContentDescriptionEquals("Check again after editing: ${isolate(first.title)}")
    }

    @Test
    fun `an appeal on its way says so`() {
        compose.setContent { AunTheme { AppealDialog(AppealState("v1", sending = true), {}, {}, initialText = "mine") } }
        compose.onNodeWithTag("appeal-send").assertTextEquals("Sending…").assertIsNotEnabled()
        compose.onNodeWithText("Sending…", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
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
        size: String = Device.PHONE.qualifier,
        content: @Composable () -> Unit
    ) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-$size")
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
        val scale = if (fontScale == 1f) "" else "_font200"
        captureScreenRoboImage("src/test/screenshots/${name}${scale}_${lang.name.lowercase()}_${mode.name.lowercase()}_phone.png")
    }

    private fun more(lang: Lang, mode: ThemeMode) =
        shootScreen("content_more", lang, mode, tap = "status-more") {
            ContentScreen(ContentUiState(videos = libraryVideos, loading = false, softDelete = true, reviewTabs = reviewTabs), actions)
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

    @Test fun appealSendingEnDark() =
        appeal(Lang.EN, ThemeMode.DARK, AppealState("v1", sending = true), words.getValue(Lang.EN), "appeal_sending")

    @Test fun appealSendingArLight() =
        appeal(Lang.AR, ThemeMode.LIGHT, AppealState("v1", sending = true), words.getValue(Lang.AR), "appeal_sending")

    @Test fun appealFailedArDark200() =
        appeal(Lang.AR, ThemeMode.DARK, AppealState("v1", failed = true), words.getValue(Lang.AR), "appeal_failed", scale = 2f)

    @Test fun notApprovedArDark200() = shootScreen("content_not_approved", Lang.AR, ThemeMode.DARK, 2f) {
        ContentScreen(notApproved, actions)
    }

    private fun narrow(lang: Lang, mode: ThemeMode) =
        shootScreen("content_not_approved_w360", lang, mode, 2f, size = "w360dp-h800dp-port-xhdpi") { ContentScreen(notApproved, actions) }

    @Test fun notApprovedNarrowEnDark200() = narrow(Lang.EN, ThemeMode.DARK)

    @Test fun notApprovedNarrowArLight200() = narrow(Lang.AR, ThemeMode.LIGHT)

    @Test fun inReviewEnLight200() = shootScreen("content_in_review", Lang.EN, ThemeMode.LIGHT, 2f) { ContentScreen(inReview, actions) }
}
