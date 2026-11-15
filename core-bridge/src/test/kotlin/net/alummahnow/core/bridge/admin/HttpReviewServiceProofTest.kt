package net.alummahnow.core.bridge.admin

import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.attestation.NATIVE_PROOF_HEADER
import net.alummahnow.core.bridge.attestation.NativeProofProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Admin app's api-videos writes (device registry) carry the attestation proof; admin-api calls never do. */
class HttpReviewServiceProofTest {
    private val server = MockWebServer()
    private lateinit var service: HttpReviewService
    private val proofs = object : NativeProofProvider {
        var n = 0
        override suspend fun proof() = "proof${++n}"
        override fun invalidate() = Unit
    }

    @Before fun setUp() {
        server.start()
        service = HttpReviewService(
            adminApi = server.url("/admin/").toString(),
            videosApi = server.url("/videos/").toString(),
            accessToken = { "tok" },
            proofs = proofs
        )
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(
        code
    ).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `a device registration carries the proof`() = runTest {
        server.enqueue(json(200, """{"success":true,"result":{}}"""))
        service.register("i1", "fcm", "en", "1.0")
        assertEquals("proof1", server.takeRequest().getHeader(NATIVE_PROOF_HEADER))
    }

    @Test fun `native_proof_required gets one retry with a fresh proof`() = runTest {
        server.enqueue(json(403, """{"success":false,"code":"native_proof_required","error":"native_proof_required"}"""))
        server.enqueue(json(200, """{"success":true,"result":{}}"""))
        service.unregister("i1")
        assertEquals("proof1", server.takeRequest().getHeader(NATIVE_PROOF_HEADER))
        assertEquals("proof2", server.takeRequest().getHeader(NATIVE_PROOF_HEADER))
    }

    @Test fun `a second refusal surfaces as a plain 403`() = runTest {
        repeat(2) { server.enqueue(json(403, """{"success":false,"code":"native_proof_required"}""")) }
        val error = runCatching { service.unregister("i1") }.exceptionOrNull()
        assertTrue(error is CoreError.Http && error.code == 403)
        assertEquals(2, server.requestCount)
    }

    @Test fun `admin-api calls never carry the proof`() = runTest {
        server.enqueue(json(200, """{"result":{"items":[],"nextCursor":null,"counts":{}}}"""))
        runCatching { service.queue(ReviewTab.ACTION_NEEDED, null, null) }
        val req = server.takeRequest()
        assertTrue(req.path!!.startsWith("/admin/"))
        assertNull(req.getHeader(NATIVE_PROOF_HEADER))
    }
}
