package net.alummahnow.core.bridge.attestation

import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class HttpAttestationApiTest {
    private val server = MockWebServer()
    private lateinit var api: HttpAttestationApi

    @Before fun setUp() {
        server.start()
        api = HttpAttestationApi(server.url("/").toString(), "test-agent")
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(
        code
    ).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `config reads the android block`() = runTest {
        server.enqueue(
            json(
                200,
                """{"success":true,"result":{"apple":{"enabled":true},"android":{"enabled":true,"cloudProjectNumber":"123456789012"}}}"""
            )
        )
        assertEquals(AttestationConfig(true, 123456789012L), api.config())
        assertEquals("/api/apps/attestation/config", server.takeRequest().path)
    }

    @Test fun `defaults are off`() = runTest {
        server.enqueue(
            json(200, """{"success":true,"result":{"apple":{"enabled":false},"android":{"enabled":false,"cloudProjectNumber":null}}}""")
        )
        assertEquals(AttestationConfig.OFF, api.config())
    }

    @Test fun `attestation_off 404 means off`() = runTest {
        server.enqueue(json(404, """{"success":false,"code":"attestation_off"}"""))
        assertEquals(AttestationConfig.OFF, api.config())
    }

    @Test fun `challenge then android exchange`() = runTest {
        server.enqueue(json(200, """{"success":true,"result":{"challenge":"abc","expiresAt":"2026-11-14T12:05:00Z"}}"""))
        server.enqueue(json(200, """{"success":true,"result":{"proof":"p1","expiresAt":"2026-11-14T13:00:00Z"}}"""))
        val ch = api.challenge()
        assertEquals("abc", ch.value)
        assertEquals("POST", server.takeRequest().method)
        assertEquals(Proof("p1", Instant.parse("2026-11-14T13:00:00Z")), api.android("tok", "abc", "net.alummahnow.studio"))
        val req = server.takeRequest()
        assertEquals("/api/apps/attestation/android", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("tok", body["token"]!!.jsonPrimitive.content)
        assertEquals("abc", body["challenge"]!!.jsonPrimitive.content)
        assertEquals("net.alummahnow.studio", body["packageName"]!!.jsonPrimitive.content)
    }
}
