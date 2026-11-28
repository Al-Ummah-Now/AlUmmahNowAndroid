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
}
