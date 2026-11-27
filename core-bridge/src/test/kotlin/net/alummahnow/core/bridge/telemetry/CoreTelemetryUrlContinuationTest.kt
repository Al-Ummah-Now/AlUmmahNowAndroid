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

    @Test
    fun `sanitizeLocalPaths handles outer quoted nested webhooks apostrophes and unclosed quote fallback`() {
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths("'https://example.test/webhook/fakeToken('a)first opaqueSecret123')'")
        )
        assertEquals(
            "\"https://example.test/webhook/[redacted]\"",
            CoreTelemetryService.sanitizeLocalPaths("\"https://example.test/webhook/fakeToken(\"a)first opaqueSecret123\")\"")
        )
        assertEquals(
            "https://example.test/wiki/Writer_(O'Reilly)?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/wiki/Writer_(O'Reilly)?private=opaqueSecret123")
        )
        assertEquals(
            "https://example.test/wiki/(Writers')?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/wiki/(Writers')?private=opaqueSecret123")
        )
        assertEquals(
            "https://example.test/wiki/('unclosed)?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/wiki/('unclosed)?private=opaqueSecret123")
        )
        assertEquals(
            "'see https://example.com/api{tag:\"value\"}?[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths("'see https://example.com/api{tag:\"value\"}?outer=value'")
        )
        assertEquals(
            "https://example.com/api{url:\"see https://other.com/webhook/[redacted]\"}?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:\"see https://other.com/webhook/fakeToken('a)first opaqueSecret123')\"}?outer=value"
            )
        )
        assertEquals(
            "https://example.com/api{url:\"see https://other.com/webhook/[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://example.com/api{url:\"see https://other.com/webhook/fakeToken('a)first opaqueSecret123')\"}"
            )
        )
        assertEquals(
            "{'a':'https://example.test/webhook/[redacted]','b':'public-text'}",
            CoreTelemetryService.sanitizeLocalPaths("{'a':'https://example.test/webhook/fakeToken(','b':'public-text'}")
        )
        assertEquals(
            "{\"a\":\"https://example.test/webhook/[redacted]\",\"b\":\"public-text\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"a\":\"https://example.test/webhook/fakeToken(\",\"b\":\"public-text\"}")
        )
        assertEquals(
            "['https://a.test/webhook/[redacted]','https://other.com/webhook/[redacted]']",
            CoreTelemetryService.sanitizeLocalPaths(
                "['https://a.test/webhook/tok(','https://other.com/webhook/tok2('x)secret')']"
            )
        )
    }

    @Test
    fun `sanitizeLocalPaths handles balanced outer-quoted webhook schemes and sibling array closers`() {
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken('a)first opaqueSecret123',https://other.test/x)'"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]', more)'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken('a)first opaqueSecret123', more)'"
            )
        )
        assertEquals(
            "[\"https://example.test/webhook/[redacted]\",\")public-text\"]",
            CoreTelemetryService.sanitizeLocalPaths(
                "[\"https://example.test/webhook/fakeToken(\",\")public-text\"]"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken(' opaqueSecret123')'"
            )
        )
    }
}
