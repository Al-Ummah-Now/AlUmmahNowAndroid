package net.alummahnow.feature.studio

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.CommentModeration
import net.alummahnow.core.bridge.studio.CommentReportReason
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.HeldComment
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.feature.studio.dashboard.DashboardSection
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.feature.studio.dashboard.DashboardViewModel
import net.alummahnow.feature.studio.dashboard.HeldCommentAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeldCommentActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val first = HeldComment("first", "First", "Author", "user", "video", "target", false)
    private val second = first.copy(id = "second")
    private val third = first.copy(id = "third", authorId = "other")
    private val comments = listOf(first, second, third)

    @Before fun setMain() = Dispatchers.setMain(dispatcher)

    @After fun resetMain() = Dispatchers.resetMain()

    @Test fun approve() = verify(HeldCommentAction.Approve, listOf(second, third), "channel:first:APPROVE")

    @Test fun remove() = verify(HeldCommentAction.Remove, listOf(second, third), "channel:first:REMOVE")

    @Test fun report() = verify(
        HeldCommentAction.Report(CommentReportReason.SPAM, "Details"),
        comments,
        "first:SPAM:Details"
    )

    @Test fun hide() = verify(HeldCommentAction.HideAuthor, listOf(third), "channel:user")

    @Test
    fun `pagination excludes hidden authors and reconciles the final count`() = runTest(dispatcher) {
        val repo = TestRepository(
            mapOf(
                null to CommentsSummary(3, 0, listOf(first), "middle"),
                "middle" to CommentsSummary(3, 0, listOf(second), "last"),
                "last" to CommentsSummary(3, 0, listOf(first.copy(id = "last")))
            )
        ).apply { result.complete(Unit) }
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        vm.actOnComment(first.id, HeldCommentAction.HideAuthor)
        advanceUntilIdle()
        vm.loadMoreComments()
        advanceUntilIdle()
        assertEquals(CommentsSummary(1, 0, nextCursor = "last"), summary(vm))

        vm.loadMoreComments()
        advanceUntilIdle()
        assertEquals(CommentsSummary(0, 0), summary(vm))
    }

    @Test
    fun `reloading a complete page does not count an author hidden earlier`() = runTest(dispatcher) {
        val repo = TestRepository(mapOf(null to CommentsSummary(2, 0, listOf(first))))
            .apply { result.complete(Unit) }
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        vm.actOnComment(first.id, HeldCommentAction.HideAuthor)
        advanceUntilIdle()
        vm.load()
        advanceUntilIdle()

        assertEquals(CommentsSummary(0, 0), summary(vm))
    }

    @Test
    fun `hide with no author and moderation of platform-held comments are ignored`() = runTest(dispatcher) {
        val held = first.copy(authorId = null, platformHold = true)
        val repo = TestRepository(mapOf(null to CommentsSummary(1, 0, listOf(held))))
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        vm.actOnComment(held.id, HeldCommentAction.HideAuthor)
        vm.actOnComment(held.id, HeldCommentAction.Approve)
        vm.actOnComment(held.id, HeldCommentAction.Remove)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), repo.calls)
        assertEquals(emptySet<String>(), content(vm).pendingComments)
        assertEquals(listOf(held), items(vm))
    }

    @Test
    fun `a pending action prevents hiding the author from a different row`() = runTest(dispatcher) {
        val repo = TestRepository()
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        vm.actOnComment(first.id, HeldCommentAction.Approve)
        vm.actOnComment(second.id, HeldCommentAction.HideAuthor)
        advanceUntilIdle()

        assertEquals(listOf("channel:first:APPROVE"), repo.calls)
        assertEquals(setOf(first.id), content(vm).pendingComments)
        repo.result.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `feature off hides the section even when another write completes later`() = runTest(dispatcher) {
        val repo = TestRepository()
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        val featureOff = repo.result
        vm.actOnComment(first.id, HeldCommentAction.Approve)
        advanceUntilIdle()
        repo.result = CompletableDeferred()
        vm.actOnComment(third.id, HeldCommentAction.Remove)
        advanceUntilIdle()

        featureOff.completeExceptionally(StudioError.FeatureOff())
        advanceUntilIdle()
        assertEquals(DashboardSection.Hidden, content(vm).comments)
        assertEquals(null, content(vm).commentMessage)
        assertEquals(setOf(third.id), content(vm).pendingComments)

        repo.result.complete(Unit)
        advanceUntilIdle()
        assertEquals(DashboardSection.Hidden, content(vm).comments)
        assertEquals(null, content(vm).commentMessage)
        assertEquals(emptySet<String>(), content(vm).pendingComments)
    }

    @Test
    fun `comment failures show the matching localized message`() = runTest(dispatcher) {
        val failures = listOf(
            CoreError.Network(IOException()) to net.alummahnow.design.R.string.aun_error_network,
            CoreError.NotSignedIn() to R.string.studio_error_signed_out,
            StudioError.Forbidden() to R.string.studio_error_forbidden,
            StudioError.Invalid("cannot_block_member") to R.string.studio_error_invalid
        )
        val repo = TestRepository()
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        failures.forEach { (error, message) ->
            repo.result = CompletableDeferred<Unit>().apply { completeExceptionally(error) }
            vm.actOnComment(first.id, HeldCommentAction.HideAuthor)
            advanceUntilIdle()
            assertEquals(message, content(vm).commentMessage)
            assertEquals(true, content(vm).commentError)
            assertEquals(comments, items(vm))
        }
    }

    private fun verify(action: HeldCommentAction, afterSuccess: List<HeldComment>, expectedCall: String) = runTest(dispatcher) {
        val repo = TestRepository()
        val vm = DashboardViewModel(repo, "channel")
        advanceUntilIdle()

        vm.actOnComment(first.id, action)
        vm.actOnComment(first.id, action)
        advanceUntilIdle()
        assertEquals(listOf(expectedCall), repo.calls)
        val expectedPending = if (action == HeldCommentAction.HideAuthor) setOf(first.id, second.id) else setOf(first.id)
        assertEquals(expectedPending, content(vm).pendingComments)
        assertEquals(comments, items(vm))

        repo.result.completeExceptionally(CoreError.Http(503))
        advanceUntilIdle()
        assertEquals(comments, items(vm))
        assertEquals(net.alummahnow.design.R.string.aun_error_generic, content(vm).commentMessage)
        assertEquals(true, content(vm).commentError)
        assertEquals(emptySet<String>(), content(vm).pendingComments)

        repo.result = CompletableDeferred(Unit)
        vm.actOnComment(first.id, action)
        advanceUntilIdle()
        assertEquals(listOf(expectedCall, expectedCall), repo.calls)
        assertEquals(afterSuccess, items(vm))
        assertEquals(false, content(vm).commentError)
        assertEquals(emptySet<String>(), content(vm).pendingComments)
    }

    private fun content(vm: DashboardViewModel) = vm.state.value as DashboardUiState.Content
    private fun summary(vm: DashboardViewModel) = (content(vm).comments as DashboardSection.Ready).value
    private fun items(vm: DashboardViewModel) = summary(vm).items

    private inner class TestRepository(
        private val pages: Map<String?, CommentsSummary> = mapOf(null to CommentsSummary(comments.size, 0, comments))
    ) : StudioRepository by FakeRepository() {
        val calls = mutableListOf<String>()
        var result = CompletableDeferred<Unit>()

        override suspend fun commentsSummary(channelId: String, cursor: String?) = pages.getValue(cursor)

        override suspend fun moderateComment(channelId: String, comment: HeldComment, action: CommentModeration) {
            submit("$channelId:${comment.id}:$action")
        }

        override suspend fun reportComment(commentId: String, reason: CommentReportReason, description: String?) {
            submit("$commentId:$reason:$description")
        }

        override suspend fun hideCommentAuthor(channelId: String, authorId: String) {
            submit("$channelId:$authorId")
        }

        private suspend fun submit(call: String) {
            calls += call
            result.await()
        }
    }
}
