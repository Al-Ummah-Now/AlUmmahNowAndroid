package net.alummahnow.core.bridge.auth

import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.CoreError
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OAuthAuthBridgeTest {
    private val server = MockWebServer()
    private val storage = MemoryStorage()
    private var clock = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun bridge(redirect: String? = "alummahnow://auth/callback") = OAuthAuthBridge(
        OAuthConfig(server.url("").toString().trimEnd('/'), "client-1", redirect),
        TokenStore(storage),
        now = { clock },
        sleep = {
            sleeps += it
            clock += it
        }
    )

    private fun jwt(id: String, email: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val payload = buildJsonObject {
            put("sub", "user:$id")
            put(
                "properties",
                buildJsonObject {
                    put("id", id)
                    put("email", email)
                }
            )
        }.toString()
        return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
    }

    private fun tokenJson(access: String, refresh: String = "r1", expires: Int = 3600) =
        MockResponse().setBody("""{"access_token":"$access","refresh_token":"$refresh","expires_in":$expires}""")

    private fun form(body: String) = body.split('&').associate {
        it.substringBefore('=') to
            java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    @Test fun `pkce sign-in sends S256 challenge and exchanges the code with the verifier`() = runTest {
        val auth = bridge()
        val url = auth.beginPkceSignIn().toHttpUrl()
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("client-1", url.queryParameter("client_id"))
        assertEquals("alummahnow://auth/callback", url.queryParameter("redirect_uri"))
        val state = url.queryParameter("state")!!

        server.enqueue(tokenJson(jwt("u1", "a@example.com")))
        val result = auth.completePkceSignIn("alummahnow://auth/callback?code=c1&state=$state")

        assertEquals(AuthState.SignedIn("u1", "a@example.com"), result)
        assertEquals(result, auth.state.value)
        val sent = form(server.takeRequest().body.readUtf8())
        assertEquals("authorization_code", sent["grant_type"])
        assertEquals("c1", sent["code"])
        assertEquals(url.queryParameter("code_challenge"), Pkce.challenge(sent.getValue("code_verifier")))
    }

    @Test fun `a callback with the wrong state is rejected and nothing is stored`() = runTest {
        val auth = bridge()
        auth.beginPkceSignIn()
        val error = runCatching { auth.completePkceSignIn("alummahnow://auth/callback?code=c1&state=forged") }.exceptionOrNull()
        assertTrue(error is CoreError.Http)
        assertEquals(0, server.requestCount)
        assertTrue(storage.items.isEmpty())
    }

    @Test fun `tokens survive a restart and refresh rotates the refresh token`() = runTest {
        val auth = bridge()
        val state = auth.beginPkceSignIn().toHttpUrl().queryParameter("state")
        server.enqueue(tokenJson(jwt("u1", "a@example.com"), refresh = "r1", expires = 120))
        auth.completePkceSignIn("alummahnow://auth/callback?code=c&state=$state")
        server.takeRequest()

        val restarted = bridge()
        assertEquals(AuthState.SignedIn("u1", "a@example.com"), restarted.state.value)

        clock += 90_000 // inside the 60 s refresh margin
        server.enqueue(tokenJson(jwt("u1", "a@example.com"), refresh = "r2"))
        assertTrue(restarted.accessToken()!!.isNotEmpty())
        assertEquals("r1", form(server.takeRequest().body.readUtf8())["refresh_token"])
        assertEquals("r2", TokenStore(storage).load()!!.refreshToken)
    }

    @Test fun `a rejected refresh token signs the user out`() = runTest {
        TokenStore(storage).save(StoredTokens(jwt("u1", "a@example.com"), "old", expiresAtMs = clock - 1))
        val auth = bridge()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        assertNull(auth.accessToken())
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertNull(TokenStore(storage).load())
    }

    @Test fun `device flow waits through pending and slow_down, then signs in`() = runTest {
        val auth = bridge(redirect = null)
        server.enqueue(
            MockResponse().setBody(
                """{"device_code":"d1","user_code":"ABCD-EFGH","verification_uri":"https://auth/activate",""" +
                    """"verification_uri_complete":"https://auth/activate?user_code=ABCD-EFGH","expires_in":600,"interval":5}"""
            )
        )
        val code = auth.beginDeviceSignIn()
        assertEquals("ABCD-EFGH", code.userCode)

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"slow_down"}"""))
        server.enqueue(tokenJson(jwt("u2", "tv@example.com")))
        assertEquals(AuthState.SignedIn("u2", "tv@example.com"), auth.awaitDeviceSignIn(code))
        assertEquals(listOf(5_000L, 5_000L, 10_000L), sleeps)

        server.takeRequest()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", form(server.takeRequest().body.readUtf8())["grant_type"])
    }

    @Test fun `device flow returns signed out when the user denies`() = runTest {
        val auth = bridge(redirect = null)
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"access_denied"}"""))
        assertEquals(AuthState.SignedOut, auth.awaitDeviceSignIn(DeviceCode("X", "u", null, 600, "d", 5)))
    }

    @Test fun `sign out clears the stored tokens`() = runTest {
        TokenStore(storage).save(StoredTokens(jwt("u1", "a@example.com"), "r", clock + 3_600_000))
        val auth = bridge()
        auth.signOut()
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertNull(auth.accessToken())
    }
}
