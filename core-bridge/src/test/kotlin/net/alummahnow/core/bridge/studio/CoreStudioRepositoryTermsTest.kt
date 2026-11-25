package net.alummahnow.core.bridge.studio

import kotlinx.coroutines.test.runTest
import net.alummahnow.core.AunException
import net.alummahnow.core.PathCall
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.core.CoreStudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreStudioRepositoryTermsTest {
    @Test
    fun `creatorAccess GETs api me creator and decodes termsAccepted`() = runTest {
        val calls = mutableListOf<PathCall>()
        val repo = CoreStudioRepository(
            client = { error("client should not be called directly when executePath is injected") },
            executePath = { call ->
                calls += call
                """{"success":true,"result":{"canUpload":true,"canCreateChannel":true,"termsAccepted":false}}"""
            }
        )
        val access = repo.creatorAccess()
        assertEquals(1, calls.size)
        assertEquals("GET", calls[0].method)
        assertEquals("/api/me/creator", calls[0].pathTemplate)
        assertTrue(calls[0].authRequired)
        assertEquals(false, access.termsAccepted)
    }

    @Test
    fun `acceptCreatorTerms POSTs api me creator terms with auth`() = runTest {
        val calls = mutableListOf<PathCall>()
        val repo = CoreStudioRepository(
            client = { error("client should not be called directly when executePath is injected") },
            executePath = { call ->
                calls += call
                """{"success":true}"""
            }
        )
        repo.acceptCreatorTerms("2026-11-24")
        assertEquals(1, calls.size)
        assertEquals("POST", calls[0].method)
        assertEquals("/api/me/creator/terms", calls[0].pathTemplate)
        assertEquals("""{"version":"2026-11-24"}""", calls[0].bodyJson)
        assertTrue(calls[0].authRequired)
    }

    @Test
    fun `an appeal and a new check are POSTs for that video`() = runTest {
        val calls = mutableListOf<PathCall>()
        val repo = CoreStudioRepository(
            client = { error("client should not be called directly when executePath is injected") },
            executePath = { call ->
                calls += call
                """{"success":true}"""
            }
        )
        repo.appealReview("v1", "It is my own recording.")
        repo.resubmitReview("v1")
        assertEquals(listOf("POST", "POST"), calls.map { it.method })
        assertEquals(listOf("/api/videos/{id}/review/appeal", "/api/videos/{id}/review/resubmit"), calls.map { it.pathTemplate })
        assertTrue(calls.all { it.pathParams == mapOf("id" to "v1") && it.authRequired })
        assertEquals("""{"message":"It is my own recording."}""", calls[0].bodyJson)
        assertEquals(null, calls[1].bodyJson)
    }

    @Test
    fun `content asks for the status and reads the queue, the summary reads both counts`() = runTest {
        val calls = mutableListOf<PathCall>()
        val repo = CoreStudioRepository(
            client = { error("client should not be called directly when executePath is injected") },
            executePath = { call ->
                calls += call
                if (call.pathTemplate.endsWith("summary")) {
                    """{"success":true,"result":{"counts":{"published":3,"in_review":2,"not_approved":1}}}"""
                } else {
                    """{"success":true,"result":{"items":[{"id":"v1","studioStatus":"in_review"}],"cursor":null,"reviewQueueMinutes":40}}"""
                }
            }
        )
        val page = repo.content(ContentQuery("c1", ContentStatus.IN_REVIEW))
        assertEquals("in_review", calls.single().query["status"])
        assertEquals(ContentStatus.IN_REVIEW, page.items.single().status)
        assertEquals(40, page.reviewQueueMinutes)
        assertEquals(ContentCounts(published = 3, inReview = 2, notApproved = 1), repo.counts("c1"))
    }

    @Test
    fun `acceptCreatorTerms surfaces 404 as CoreError NotFound`() = runTest {
        val repo = CoreStudioRepository(
            client = { error("unused") },
            executePath = { throw AunException.NotFound("Not Found") }
        )
        val result = runCatching { repo.acceptCreatorTerms("2026-11-24") }
        assertTrue(result.exceptionOrNull() is CoreError.NotFound)
    }

    @Test
    fun `a 409 is changed Terms, any other failure is not`() = runTest {
        suspend fun failure(e: Exception) =
            runCatching {
                CoreStudioRepository(client = { error("unused") }, executePath = { throw e }).acceptCreatorTerms("v")
            }.exceptionOrNull()
        assertTrue(failure(AunException.Conflict("The Terms of Service were updated")) is StudioError.TermsChanged)
        assertEquals(503, (failure(AunException.Http(503u, null, "x")) as CoreError.Http).code)
    }
}
