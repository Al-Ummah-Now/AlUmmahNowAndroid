package net.alummahnow.core.bridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryQuotedAuthTest {
    @Test
    fun `sanitizeLocalPaths handles Bearer truncation prose JSON keys and multiline Basic`() {
        assertEquals(
            "received Bearer \"[redacted] ",
            CoreTelemetryService.sanitizeLocalPaths("received Bearer \"opaqueSecret123 ")
        )
        assertEquals(
            "Bearer \"hello, world\"",
            CoreTelemetryService.sanitizeLocalPaths("Bearer \"hello, world\"")
        )
        assertEquals(
            """{"msg":"Bearer \"hello world\""}""",
            CoreTelemetryService.sanitizeLocalPaths("""{"msg":"Bearer \"hello world\""}""")
        )
        assertEquals(
            """{"Bearer ":"ok"}""",
            CoreTelemetryService.sanitizeLocalPaths("""{"Bearer ":"ok"}""")
        )
        assertEquals(
            "Basic \"[redacted]\nnext line",
            CoreTelemetryService.sanitizeLocalPaths("Basic \"dTpw\nnext line")
        )
        assertEquals(
            "Basic \"[redacted] ",
            CoreTelemetryService.sanitizeLocalPaths("Basic \"dTpw ")
        )
        assertEquals(
            "Bearer \"[redacted] \"",
            CoreTelemetryService.sanitizeLocalPaths("Bearer \"opaqueSecret123 \"")
        )
        assertEquals(
            "Basic \"[redacted] \"",
            CoreTelemetryService.sanitizeLocalPaths("Basic \"dTpw \"")
        )
        val bearerEscapedNewline =
            CoreTelemetryService.sanitizeLocalPaths("""Bearer \"opaqueSecret123\nnext""")
        assertFalse(bearerEscapedNewline.contains("opaqueSecret123"))
        assertTrue(bearerEscapedNewline.startsWith("""Bearer \""""))
        val basicEscapedNewline =
            CoreTelemetryService.sanitizeLocalPaths("""Basic \"dTpw\nnext""")
        assertFalse(basicEscapedNewline.contains("dTpw"))
        assertTrue(basicEscapedNewline.startsWith("""Basic \""""))
        assertEquals(
            """Bearer \"[redacted]\"""",
            CoreTelemetryService.sanitizeLocalPaths("""Bearer \"opaqueSecret123\"""")
        )
        assertEquals(
            """{"msg":"Bearer [redacted]\" next"}""",
            CoreTelemetryService.sanitizeLocalPaths("""{"msg":"Bearer opaqueSecret123\" next"}""")
        )
    }

    @Test
    fun `sanitizeLocalPaths scrubs OAuth keys JWTs empty-port Slack webhooks and long protocol versions`() {
        assertEquals(
            """{"refresh_token":"[redacted]","access_token":"[redacted]","id_token":"[redacted]"}""",
            CoreTelemetryService.sanitizeLocalPaths(
                """{"refresh_token":"opaqueSecret123","access_token":"accSecret456","id_token":"idSecret789"}"""
            )
        )
        assertEquals(
            "jwt=[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("jwt=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTYifQ.sigSecret")
        )
        assertEquals(
            "https://[redacted-email]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/users/alice%40%65xample.com")
        )
        assertEquals(
            "https://[redacted-email]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/users/alice%40b%C3%BCcher.example")
        )
        assertEquals(
            "https://hooks.slack.com:/services/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://hooks.slack.com:/services/T000/B000/Canary")
        )
        val longProto = "(HTTP/1" + ".1".repeat(50_000) + "x)"
        assertEquals("([redacted-path])", CoreTelemetryService.sanitizeLocalPaths(longProto))
        assertEquals(
            "https://example.com/?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("""https://example.com/?access_token="super secret"""")
        )
        assertEquals(
            "https://example.com/?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("""https://example.com/?session_key="super secret"""")
        )
        assertEquals(
            "https://example.com/?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/?session_key='super secret'")
        )
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths(".env"))
    }

    @Test
    fun `sanitizeLocalPaths and serializeProperties scrub root-relative paths reset routes AWS headers and tokens`() {
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("""\Confidential\Payroll"""))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("Acquisition.blend"))
        assertEquals(
            "https://example.com/reset/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/reset/super-secret-token")
        )
        assertEquals(
            "X-Amz-Security-Token: [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("X-Amz-Security-Token: secretcredential")
        )
        assertEquals(
            """{"X-Amz-Security-Token":"[redacted]"}""",
            CoreTelemetryService.serializeProperties(mapOf("X-Amz-Security-Token" to "secretcredential"))
        )
        assertEquals(
            """{"access_token":"[redacted]","refresh_token":"[redacted]","id_token":"[redacted]"}""",
            CoreTelemetryService.serializeProperties(
                mapOf(
                    "access_token" to "accSecret456",
                    "refresh_token" to "opaqueSecret123",
                    "id_token" to "idSecret789"
                )
            )
        )
        assertEquals(
            """{"[redacted-path]":"[redacted]"}""",
            CoreTelemetryService.serializeProperties(mapOf("/storage/emulated/0/Acquisition/client_token" to "dummy"))
        )
        assertEquals(
            """{"contact [redacted-phone]":true}""",
            CoreTelemetryService.serializeProperties(mapOf("contact +1 2025550142" to true))
        )
        assertEquals(
            "[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("smb://server/Confidential/Acquisition.mov")
        )
        val tmpBuf = java.io.File.createTempFile("aun-fallback-buf-", ".json")
        try {
            tmpBuf.writeText("""{"events":[]}""")
            val fallback = UnavailableNativeTelemetryService(tmpBuf.absolutePath)
            assertFalse(tmpBuf.exists())
            assertFalse(fallback.lastPurgeFailed)
            assertFalse(fallback.setConsent(TelemetryConsent.FULL))
            assertTrue(fallback.setConsent(TelemetryConsent.OFF))
        } finally {
            tmpBuf.delete()
        }
        val stack = CoreTelemetryService.formatSanitizedCrashStack(
            IllegalStateException("Could not load profile for Jane Doe")
        )
        assertTrue(stack.contains("IllegalStateException"))
        assertFalse(stack.contains("Jane Doe"))
    }
}
