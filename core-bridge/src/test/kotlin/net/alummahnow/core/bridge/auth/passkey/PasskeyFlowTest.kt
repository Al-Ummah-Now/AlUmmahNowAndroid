package net.alummahnow.core.bridge.auth.passkey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeAuthenticator(
    override val isSupported: Boolean = true,
    var assertion: () -> String = { """{"id":"cred","type":"public-key"}""" },
    var attestation: () -> String = { """{"id":"new","type":"public-key"}""" }
) : PasskeyAuthenticator {
    val requests = mutableListOf<String>()
    override suspend fun signIn(requestJson: String): String = assertion().also { requests += requestJson }
    override suspend fun create(requestJson: String): String = attestation().also { requests += requestJson }
}

class PasskeyFlowTest {
    private val server = MockWebServer()
    private val redirects = mutableListOf<String>()
    private var token: String? = "tok"
    private lateinit var flow: PasskeyFlow<String>

    @Before fun setUp() {
        AunLog.enabled = false
        server.start()
        flow = PasskeyFlow(
            api = PasskeyApi(server.url("/").toString()),
            clientId = "client-1",
            redirectUri = "alummahnow-studio://auth/callback",
            beginAuthorization = {
                "https://auth.qa.alummahnow.net/authorize?client_id=client-1&state=st%2B1&code_challenge=CHAL&code_challenge_method=S256"
            },
            completeRedirect = {
                redirects += it
                "account"
            },
            accessToken = { token }
        )
    }

    @After fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test fun `sign-in uses the PKCE attempt's challenge and finishes through the normal redirect`() = runTest {
        server.enqueue(ok("""{"challenge_id":"ch1","publicKey":{"challenge":"abc"}}"""))
        server.enqueue(ok("""{"code":"c/1 2"}"""))
        val auth = FakeAuthenticator()
        assertEquals("account", flow.signIn(auth))
        val opts = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("CHAL", opts["code_challenge"]!!.jsonPrimitive.content)
        assertEquals("""{"challenge":"abc"}""", auth.requests.single())
        server.takeRequest()
        // The code and state are URL-encoded; aun-auth checks state and exchanges the code with its verifier.
        assertEquals(listOf("alummahnow-studio://auth/callback?code=c%2F1+2&state=st%2B1"), redirects)
    }

    @Test fun `cancel in the OS sheet stops before verify`() = runTest {
        server.enqueue(ok("""{"challenge_id":"ch1","publicKey":{"challenge":"abc"}}"""))
        val auth = FakeAuthenticator(assertion = { throw PasskeyError.Cancelled() })
        assertTrue(runCatching { flow.signIn(auth) }.exceptionOrNull() is PasskeyError.Cancelled)
        assertEquals(1, server.requestCount)
        assertTrue(redirects.isEmpty())
    }

    @Test fun `register needs a signed-in account`() = runTest {
        token = null
        assertTrue(runCatching { flow.register(FakeAuthenticator()) }.exceptionOrNull() is CoreError.NotSignedIn)
        assertEquals(0, server.requestCount)
    }

    @Test fun `register runs options, create, verify`() = runTest {
        server.enqueue(ok("""{"challenge_id":"r1","publicKey":{"challenge":"x"}}"""))
        server.enqueue(ok("""{"id":"pk1","created_at":"2026-11-13T12:00:00Z"}"""))
        val pk = flow.register(FakeAuthenticator())
        assertEquals("pk1", pk.id)
        assertNull(pk.name)
        assertEquals(2, server.requestCount)
    }

    @Test fun `step-up required, then a retry after signing in again succeeds`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"error":"step_up_required"}"""))
        assertTrue(runCatching { flow.register(FakeAuthenticator()) }.exceptionOrNull() is PasskeyError.StepUpRequired)
        token = "fresh"
        server.enqueue(ok("""{"challenge_id":"r1","publicKey":{"challenge":"x"}}"""))
        server.enqueue(ok("""{"id":"pk1"}"""))
        assertEquals("pk1", flow.register(FakeAuthenticator()).id)
        server.takeRequest()
        assertEquals("Bearer fresh", server.takeRequest().headers["Authorization"])
    }
}
