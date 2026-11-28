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
                    """{"userId":["alice42", "bob73"]} status="password=first;second" """ +
                    "password=\u201Corchid velvet\u201D displayName=Alice Smith username=Jane Doe C:ProjectCodename C:秘密 " +
                    """pass="correct horse battery staple" """ +
                    """{"pass":"multi word phrase"} fullName="Alice Wonderland" pwd=shortsecret""",
                "user_id" to "alice123",
                "p\\u0061ssword" to "violet-sparrow",
                "token violet7" to "ok",
                "http://u:p@localhost/x" to "https://1drv.ms/u/s!Aq82F1ZLPx0K " +
                    "alummahnow://watch/v1 " +
                    "https://uploads.example.test/upload/853876e8-cc19-465d-bafa-7459aba00936 " +
                    "https://example.org/assets/%41bCdEfGhIjKlMnOpQrStUvWxYz0123456789 " +
                    "https://example.test/lookup/%2B%31%32%30%32%35%35%35%30%31%32%33 " +
                    "https%25253A%25252F%25252Fexample.test%25252Flogin%25253Ftoken%25253Ds3cr3t " +
                    "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ " +
                    "https://example.test/password%3Dshortcredential " +
                    "https%3A%2F%2Fexample.test%2F%3Fcode%3Dorchid-meadow",
                "badVal" to throwingProp
            )
        )
        assertNotNull(props)
        val json = props!!
        val forbidden = listOf(
            "eyJzdWIiOiJhbGljZSJ9", "sigValue123", "56\u202F78", "12 34 56 78",
            "482731", "first;second", "second", "alice123", "alice42", "bob73", "violet-sparrow", "秘密", "12 Cedar Rd",
            "orchid", "velvet", "shortcredential", "horse battery staple", "word phrase",
            "Alice", "Wonderland", "Smith", "Jane", "Doe", "ProjectCodename", "violet7",
            "orchid-meadow", "shortsecret", "u:p@localhost", "u:[redacted-email]", "Aq82F1ZLPx0K",
            "watch/v1", "853876e8-cc19-465d-bafa-7459aba00936", "bCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "%2B%31%32%30%32", "s3cr3t", "ghp_abcdefghijklmnopqrstuvwxyzABCDEFGHIJ"
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
