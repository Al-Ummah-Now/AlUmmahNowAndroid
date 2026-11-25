package net.alummahnow.core.bridge.studio.deletion

import kotlinx.coroutines.test.runTest
import net.alummahnow.core.SecureStorage
import net.alummahnow.core.bridge.attestation.NativeProofProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeletionApiTest {
    private val server = MockWebServer()
    private val proofs = Proofs()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun api() = HttpDeletionApi(server.url("/api").toString(), server.url("/auth").toString(), "UA", "aun-studio/1", proofs)

    @Test
    fun `the erase confirms, hands the token back and reports the answer as it came`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "300").setBody("""{"code":"resume_unavailable"}"""))
        val answer = api().erase("tok", "opaque")
        assertEquals(DeletionAnswer(503, """{"code":"resume_unavailable"}""", "300"), answer)
        val request = server.takeRequest()
        assertEquals("DELETE /api/api/privacy/data HTTP/1.1", request.requestLine)
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertEquals("aun-studio/1", request.getHeader("X-AUN-Client"))
        assertEquals("""{"confirm":"DELETE","resumeToken":"opaque"}""", request.body.readUtf8())
    }

    @Test
    fun `the first pass sends no token and the account call sends no body`() = runTest {
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"deleted":true}"""))
        api().erase("tok", null)
        assertEquals(200, api().deleteAccount("tok").status)
        assertEquals("""{"confirm":"DELETE"}""", server.takeRequest().body.readUtf8())
        val account = server.takeRequest()
        assertEquals("DELETE /auth/account HTTP/1.1", account.requestLine)
        assertEquals("", account.body.readUtf8())
    }

    @Test
    fun `a proof the server asks for is fetched once`() = runTest {
        proofs.values += listOf("old", "fresh")
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"native_proof_required"}"""))
        server.enqueue(MockResponse().setBody("{}"))
        assertEquals(200, api().erase("tok", null).status)
        assertEquals(2, server.requestCount)
        assertEquals(1, proofs.invalidated)
    }

    @Test
    fun `the server's clock is its Date header, or nothing`() = runTest {
        server.enqueue(MockResponse().setHeader("Date", "Sun, 09 Sep 2001 01:46:40 GMT"))
        assertEquals(1_000_000_000L, api().serverNowSeconds())
        server.enqueue(MockResponse().setHeader("Date", "yesterday"))
        assertNull(api().serverNowSeconds())
        server.shutdown()
        assertNull(api().serverNowSeconds())
    }

    @Test
    fun `records survive a new process, and an unreadable store throws instead of saying nothing started`() {
        val storage = Storage()
        val first = SecureDeletionRecords(storage)
        first.put(DeletionRecord("u1", "a@example.org", resumeToken = "t", startedAtMs = 5))
        first.put(DeletionRecord("u2", "b@example.org", DeletionStage.CONTENT_ERASED, leftover = Leftover.CHANNEL))
        first.put(DeletionRecord("u1", "a@example.org", resumeToken = "t2", startedAtMs = 5))
        val next = SecureDeletionRecords(storage)
        assertEquals(listOf("u2", "u1"), next.records.value.map { it.userId })
        assertEquals("t2", next.get("u1")?.resumeToken)
        next.remove("u2")
        assertEquals(listOf("u1"), SecureDeletionRecords(storage).records.value.map { it.userId })
        storage.broken = true
        assertTrue(runCatching { next.get("u1") }.isFailure)
        assertTrue(runCatching { next.put(DeletionRecord("u3", "c")) }.isFailure)
        assertTrue(SecureDeletionRecords(storage).records.value.isEmpty())
    }

    private class Proofs : NativeProofProvider {
        val values = ArrayDeque<String>()
        var invalidated = 0
        override suspend fun proof(): String? = values.removeFirstOrNull()
        override fun invalidate() {
            invalidated++
        }
    }

    private class Storage : SecureStorage {
        private val map = mutableMapOf<String, ByteArray>()
        var broken = false
        override fun get(key: String): ByteArray? {
            check(!broken) { "key lost" }
            return map[key]
        }
        override fun set(key: String, value: ByteArray) {
            check(!broken) { "key lost" }
            map[key] = value
        }
        override fun delete(key: String) {
            map.remove(key)
        }
        override fun list(prefix: String) = map.keys.filter { it.startsWith(prefix) }
    }
}
