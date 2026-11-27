package net.alummahnow.core.bridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreTelemetryUrlContinuationTest {
    @Test
    fun `sanitizeLocalPaths handles quoted webhook groups brace filenames and linear nested parens`() {
        assertEquals(
            "https://example.com/api{tag:\"value)\"}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api{tag:\"value)\"}?token=opaqueSecret123")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken{tag:\"first opaqueSecret123\"}")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken{tag:'first opaqueSecret123'}")
        )
        assertEquals(
            "https://example.com/api{tag:'value)'}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api{tag:'value)'}?token=opaqueSecret123")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken{tag:\"a)opaqueSecret123\"}suffixSecret")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken{abc://opaqueSecret123}")
        )
        assertEquals(
            "https://example.com/users/[redacted-path]}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/users/{Secret.mov}?token=opaqueSecret123")
        )
        val nestedParens = "https://example.com/a" + "(".repeat(500) + "b/".repeat(500) + ")".repeat(500)
        assertEquals(nestedParens, CoreTelemetryService.sanitizeLocalPaths(nestedParens))
    }

    @Test
    fun `sanitizeLocalPaths vaults nested URLs preserves single quotes and scales linearly on equals`() {
        assertEquals(
            "https://example.com/api{url:\"see https://other.com/x?[redacted]\"}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:\"see https://other.com/x?token=opaqueSecret123\"}?outer=value"
            )
        )
        assertEquals(
            "https://example.com/api{url:\"see https://other.com/x?[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:\"see https://other.com/x?token=opaqueSecret123\"}"
            )
        )
        assertEquals(
            "https://example.com/api{url:\"https://other.com/x,public?[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:\"https://other.com/x,public?token=opaqueSecret123\"}"
            )
        )
        assertEquals(
            "https://example.com/api{url:'see https://other.com/x?[redacted]'}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:'see https://other.com/x?token=opaqueSecret123'}?outer=value"
            )
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken('a)first opaqueSecret123')")
        )
        val repeatedEquals = List(500) { "https://a.test/x{a:\"https://b.test/x\"}" }.joinToString("=")
        assertEquals(repeatedEquals, CoreTelemetryService.sanitizeLocalPaths(repeatedEquals))
    }
}
