package net.alummahnow.core.bridge.studio

import kotlinx.coroutines.test.runTest
import net.alummahnow.core.PathCall
import net.alummahnow.core.bridge.studio.core.CoreStudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreStudioCommentActionsTest {
    @Test
    fun `uses the channel moderation routes and reports as the signed-in person`() = runTest {
        val calls = mutableListOf<PathCall>()
        val repository = CoreStudioRepository(client = { error("Unexpected client access") }, executePath = { call ->
            calls += call
            when (call.pathTemplate) {
                "/api/channels/{id}/comments" -> """
                    {"success":true,"total":1,"items":[{"id":"comment","text":"Text","kind":"post",
                    "targetId":"post","author":{"userId":"user","resolvedDisplayName":"Name"}}]}
                """.trimIndent()
                "/api/channels/{id}/comments/bulk" -> """{"success":true,"results":[{"commentId":"comment","ok":true}]}"""
                "/api/moderation/reports" -> """{"success":true,"result":{"report":{"id":"report"}}}"""
                else -> """{"success":true}"""
            }
        })
        val comment = repository.commentsSummary("channel").items.single()
        assertEquals(HeldComment("comment", "Text", "Name", "user", "post", "post", false), comment)
        repository.moderateComment("channel", comment, CommentModeration.APPROVE)
        repository.moderateComment("channel", comment, CommentModeration.REMOVE)
        repository.reportComment(comment.id, CommentReportReason.CHILD_SAFETY, "Details")
        repository.hideCommentAuthor("channel", "user")

        assertEquals(mapOf("tab" to "held", "limit" to "20"), calls.first().query)
        assertEquals(listOf("GET", "POST", "POST", "POST", "POST"), calls.map { it.method })
        assertTrue(calls.all { it.authRequired })
        assertEquals(listOf("channel", "channel", "channel", null, "channel"), calls.map { it.headers["channelId"] })
        assertEquals(
            listOf(
                """{"action":"approve","items":[{"kind":"post","targetId":"post","commentId":"comment"}]}""",
                """{"action":"remove","items":[{"kind":"post","targetId":"post","commentId":"comment"}]}""",
                """{"entityType":"comment","entityId":"comment","reason":"child_safety","description":"Details"}""",
                """{"userId":"user"}"""
            ),
            calls.drop(1).map { it.bodyJson }
        )
    }

    @Test
    fun `rejects a failed item even when the bulk request succeeds`() = runTest {
        val repository = CoreStudioRepository(client = { error("Unexpected client access") }, executePath = {
            """{"success":true,"results":[{"commentId":"comment","ok":false,"error":"forbidden"}]}"""
        })
        val comment = HeldComment("comment", "Text", "Name", "user", "post", "post", false)
        val failure = runCatching { repository.moderateComment("channel", comment, CommentModeration.APPROVE) }.exceptionOrNull()
        assertTrue(failure is StudioError.Invalid)
    }
}
