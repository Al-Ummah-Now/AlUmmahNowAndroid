package net.alummahnow.core.bridge.auth.passkey

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The native passkey contract (AUTH-PASSKEYS "Native apps") against a local server. */
class PasskeyApiTest {
    private val server = MockWebServer()
    private lateinit var api: PasskeyApi

    @Before fun setUp() {
        AunLog.enabled = false
        server.start()
        api = PasskeyApi(server.url("/").toString())
    }

    @After fun tearDown() = server.close()

    private fun json(code: Int = 200, body: String, headers: Map<String, String> = emptyMap()): MockResponse {
        val all = (mapOf("Content-Type" to "application/json") + headers).flatMap { listOf(it.key, it.value) }
        return MockResponse(code = code, headers = headersOf(*all.toTypedArray()), body = body)
    }

    @Test fun `sign-in options send client_id and the S256 challenge and return the publicKey JSON as-is`() = runTest {
        server.enqueue(
            json(
                body = """{"challenge_id":"ch1","publicKey":{"challenge":"abc","rpId":"qa.alummahnow.net"}}"""
            )
        )
        val c = api.signInOptions("client-1", "CHAL")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/native/passkey/options", req.url.encodedPath)
        assertNull(req.headers["Authorization"])
        val body = Json.parseToJsonElement(req.body!!.utf8()).jsonObject
        assertEquals("client-1", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("CHAL", body["code_challenge"]!!.jsonPrimitive.content)
        assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
        assertEquals("ch1", c.challengeId)
        val options = Json.parseToJsonElement(c.optionsJson).jsonObject
        assertEquals("qa.alummahnow.net", options["rpId"]!!.jsonPrimitive.content)
    }

    @Test fun `verify posts the credential as JSON (not a string) and returns the code`() = runTest {
        server.enqueue(json(body = """{"code":"auth-code"}"""))
        val code = api.verifySignIn("ch1", "client-1", """{"id":"cred","type":"public-key","response":{"signature":"sig"}}""")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("ch1", body["challenge_id"]!!.jsonPrimitive.content)
        assertEquals("client-1", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("cred", body["credential"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("auth-code", code)
    }

    @Test fun `registration sends the Bearer token and the optional name`() = runTest {
        server.enqueue(json(body = """{"challenge_id":"r1","publicKey":{"challenge":"x","user":{"id":"u","name":"a"}}}"""))
        server.enqueue(json(body = """{"id":"pk1","name":"Pixel","created_at":"2026-11-13T12:00:00Z"}"""))
        val c = api.registerOptions("tok")
        val opts = server.takeRequest()
        assertEquals("/native/passkey/register/options", opts.url.encodedPath)
        assertEquals("Bearer tok", opts.headers["Authorization"])
        val pk = api.registerVerify("tok", c.challengeId, """{"id":"new"}""", "Pixel")
        val verify = server.takeRequest()
        assertEquals("/native/passkey/register/verify", verify.url.encodedPath)
        assertEquals("Bearer tok", verify.headers["Authorization"])
        val body = Json.parseToJsonElement(verify.body!!.utf8()).jsonObject
        assertEquals("r1", body["challenge_id"]!!.jsonPrimitive.content)
        assertEquals("Pixel", body["name"]!!.jsonPrimitive.content)
        assertEquals(RegisteredPasskey("pk1", "Pixel", "2026-11-13T12:00:00Z"), pk)
    }

    @Test fun `a blank name is left out`() = runTest {
        server.enqueue(json(body = """{"id":"pk1"}"""))
        api.registerVerify("tok", "r1", """{"id":"new"}""", " ")
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertTrue("name" !in body)
    }

    @Test fun `401 step_up_required becomes StepUpRequired`() = runTest {
        server.enqueue(json(401, """{"error":"step_up_required","error_description":"sign in again"}"""))
        assertTrue(runCatching { api.registerOptions("tok") }.exceptionOrNull() is PasskeyError.StepUpRequired)
    }

    @Test fun `429 carries Retry-After`() = runTest {
        server.enqueue(json(429, """{"error":"rate_limited"}""", mapOf("Retry-After" to "30")))
        val e = runCatching { api.signInOptions("c", "x") }.exceptionOrNull()
        assertTrue(e is PasskeyError.RateLimited && e.retryAfterSeconds == 30L)
    }

    @Test fun `a refused passkey keeps the server error code`() = runTest {
        server.enqueue(json(400, """{"error":"invalid_credential","error_description":"unknown passkey"}"""))
        val e = runCatching { api.verifySignIn("ch", "c", "{}") }.exceptionOrNull()
        assertTrue(e is PasskeyError.Rejected && e.code == "invalid_credential" && e.status == 400)
    }

    @Test fun `bare 401 is NotSignedIn, bare 404 is Unavailable, 5xx is Http`() = runTest {
        server.enqueue(MockResponse(code = 401))
        assertTrue(runCatching { api.registerOptions("tok") }.exceptionOrNull() is CoreError.NotSignedIn)
        server.enqueue(MockResponse(code = 404))
        assertTrue(runCatching { api.signInOptions("c", "x") }.exceptionOrNull() is CoreError.Unavailable)
        server.enqueue(json(503, """{"error":"server_error"}"""))
        val e = runCatching { api.signInOptions("c", "x") }.exceptionOrNull()
        assertTrue(e is CoreError.Http && e.code == 503)
    }

    @Test fun `missing fields are Decode errors`() = runTest {
        server.enqueue(json(body = """{"challenge_id":"x"}"""))
        assertTrue(runCatching { api.signInOptions("c", "x") }.exceptionOrNull() is CoreError.Decode)
        server.enqueue(json(body = "<html>"))
        assertTrue(runCatching { api.verifySignIn("ch", "c", "{}") }.exceptionOrNull() is CoreError.Decode)
    }

    @Test fun `a dead server is a Network error`() = runTest {
        val dead = PasskeyApi("http://127.0.0.1:1")
        assertTrue(runCatching { dead.signInOptions("c", "x") }.exceptionOrNull() is CoreError.Network)
    }

    @Test fun `plain HTTP to a real host is refused`() = runTest {
        val insecure = PasskeyApi("http://auth.example.com")
        assertTrue(runCatching { insecure.signInOptions("c", "x") }.isFailure)
    }
}
