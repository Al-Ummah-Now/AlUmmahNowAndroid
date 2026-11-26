package net.alummahnow.feature.studio

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
        assertEquals(R.string.studio_held_failed, content(vm).commentMessage)
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
    private fun items(vm: DashboardViewModel) = (content(vm).comments as DashboardSection.Ready).value.items

    private inner class TestRepository : StudioRepository by FakeRepository() {
        val calls = mutableListOf<String>()
        var result = CompletableDeferred<Unit>()

        override suspend fun commentsSummary(channelId: String, cursor: String?) = CommentsSummary(comments.size, 0, comments)

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
