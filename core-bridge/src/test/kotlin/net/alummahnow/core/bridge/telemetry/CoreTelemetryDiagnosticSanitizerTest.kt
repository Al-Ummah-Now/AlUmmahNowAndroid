package net.alummahnow.core.bridge.telemetry

import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryDiagnosticSanitizerTest {
    @Test
    fun `diagnostic sanitizer handles JOSE edge cases unicode phones credentials userinfo and checked toString`() {
        val longHeaderJson = " {\"alg\":\"HS256\",\"pad\":\"" + "a".repeat(600) + "\"}"
        val longHeaderB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(longHeaderJson.toByteArray(Charsets.UTF_8))
        val longSignedJwt = "$longHeaderB64.eyJzdWIiOiJhbGljZSJ9.sigValue123"
        val throwingProp = object {
            override fun toString(): String = throw IOException("checked failure")
        }
        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "algNone" to "IHsiYWxnIjoibm9uZSJ9.eyJzdWIiOiJhbGljZSJ9.",
                "longJwt" to longSignedJwt,
                "safeId" to "eyJunction.load.failed",
                "nbText" to "+33\u202F6\u202F12\u202F34\u202F56\u202F78",
                "detailText" to "12 34 56 78 and 2026-11-27",
                "creds" to """{"challenge.verificationCode":"482731"} {"home\u0041ddress":"12 Cedar Rd"} """ +
                    """status="password=first;second" """ +
                    "password=\u201Corchid velvet\u201D displayName=Alice Smith username=Jane Doe C:ProjectCodename " +
                    """pass="correct horse battery staple" """ +
                    """{"pass":"multi word phrase"} fullName="Alice Wonderland" pwd=shortsecret""",
                "user_id" to "alice123",
                "token violet7" to "ok",
                "http://u:p@localhost/x" to "https://1drv.ms/u/s!Aq82F1ZLPx0K " +
                    "https://example.test/password%3Dshortcredential " +
                    "https%3A%2F%2Fexample.test%2F%3Fcode%3Dorchid-meadow",
                "badVal" to throwingProp
            )
        )
        assertNotNull(props)
        val json = props!!
        val forbidden = listOf(
            "eyJzdWIiOiJhbGljZSJ9", "sigValue123", "56\u202F78", "12 34 56 78",
            "482731", "first;second", "second", "alice123", "12 Cedar Rd",
            "orchid", "velvet", "shortcredential", "horse battery staple", "word phrase",
            "Alice", "Wonderland", "Smith", "Jane", "Doe", "ProjectCodename", "violet7",
            "orchid-meadow", "shortsecret", "u:p@localhost", "u:[redacted-email]", "Aq82F1ZLPx0K"
        )
        for (token in forbidden) {
            assertFalse("Expected redacted token: $token", json.contains(token))
        }
        assertTrue(json.contains("eyJunction.load.failed"))
        assertTrue(json.contains("2026-11-27"))
        assertFalse(CoreTelemetryService.sanitizePropertyKey("phone_2025550123").contains("2025550123"))
        assertEquals(
            "http://[redacted]@localhost/x",
            CoreTelemetryService.sanitizeLocalPaths("http://u:p@localhost/x")
        )
    }
}
