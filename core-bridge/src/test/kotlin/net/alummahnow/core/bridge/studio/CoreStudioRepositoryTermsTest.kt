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
        repo.acceptCreatorTerms()
        assertEquals(1, calls.size)
        assertEquals("POST", calls[0].method)
        assertEquals("/api/me/creator/terms", calls[0].pathTemplate)
        assertTrue(calls[0].authRequired)
    }

    @Test
    fun `acceptCreatorTerms surfaces 404 as CoreError NotFound`() = runTest {
        val repo = CoreStudioRepository(
            client = { error("unused") },
            executePath = { throw AunException.NotFound("Not Found") }
        )
        val result = runCatching { repo.acceptCreatorTerms() }
        assertTrue(result.exceptionOrNull() is CoreError.NotFound)
    }
}
