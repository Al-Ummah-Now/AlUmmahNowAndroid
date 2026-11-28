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
                    "password=\u201Corchid velvet\u201D " +
                    """pass="correct horse battery staple" """ +
                    """{"pass":"multi word phrase"} fullName="Alice Wonderland" pwd=shortsecret""",
                "http://u:p@localhost/x" to "https://1drv.ms/u/s!Aq82F1ZLPx0K https://example.test/password%3Dshortcredential",
                "badVal" to throwingProp
            )
        )
        assertNotNull(props)
        val json = props!!
        assertFalse(json.contains("eyJzdWIiOiJhbGljZSJ9"))
        assertFalse(json.contains("sigValue123"))
        assertTrue(json.contains("eyJunction.load.failed"))
        assertFalse(json.contains("56\u202F78"))
        assertFalse(json.contains("12 34 56 78"))
        assertTrue(json.contains("2026-11-27"))
        assertFalse(json.contains("482731"))
        assertFalse(json.contains("12 Cedar Rd"))
        assertFalse(json.contains("orchid"))
        assertFalse(json.contains("velvet"))
        assertFalse(json.contains("shortcredential"))
        assertFalse(json.contains("horse battery staple"))
        assertFalse(json.contains("word phrase"))
        assertFalse(json.contains("Alice"))
        assertFalse(json.contains("Wonderland"))
        assertFalse(json.contains("shortsecret"))
        assertFalse(json.contains("u:p@localhost"))
        assertFalse(json.contains("u:[redacted-email]"))
        assertFalse(json.contains("Aq82F1ZLPx0K"))
        assertFalse(CoreTelemetryService.sanitizePropertyKey("phone_2025550123").contains("2025550123"))
        assertEquals(
            "http://[redacted]@localhost/x",
            CoreTelemetryService.sanitizeLocalPaths("http://u:p@localhost/x")
        )
    }
}
