package net.alummahnow.core.bridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreTelemetryAuthSanitizerTest {
    @Test
    fun `sanitizeLocalPaths redacts auth headers domain-ext emails and repeated filename emails`() {
        assertEquals("[redacted-email]", CoreTelemetryService.sanitizeLocalPaths("alice@internal.pdf.example.org"))
        assertEquals("[redacted-email]", CoreTelemetryService.sanitizeLocalPaths("alice.txt@example.zip.private"))
        assertEquals(
            "Authorization: Basic [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: Basic YWxpY2U6c2VjcmV0")
        )
        assertEquals(
            "Proxy-Authorization: Basic [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Proxy-Authorization: Basic YWxpY2U6c2VjcmV0")
        )
        assertEquals(
            "Authorization: Digest [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: Digest username=\"alice\", response=\"deadbeef\"")
        )
        assertEquals(
            "{\"Authorization\": \"Basic [redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\": \"Basic YWxpY2U6c2VjcmV0\"}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"opaqueSecret123\"}")
        )
        assertEquals(
            "{\"Proxy-Authorization\":\"Digest [redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Proxy-Authorization\":\"Digest username=alice, response=deadbeef\"}")
        )
        assertEquals(
            "{\"Authorization\":\"Digest [redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"Digest username=\\\"alice\\\", response=\\\"deadbeef\\\"\"}")
        )
        assertEquals(
            "{\"Authorization\":\"Bearer [redacted]\",\"status\":401}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"Bearer testSecret\",\"status\":401}")
        )
        assertEquals(
            "{\"Authorization\":[\"Bearer [redacted]\"],\"status\":401}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":[\"Bearer testSecret\"],\"status\":401}")
        )
    }

    @Test
    fun `sanitizeLocalPaths redacts nested folded and escaped Authorization headers`() {
        assertEquals(
            "{\"Authorization\":[[\"[redacted]\"],\"[redacted]\"]}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":[[\"firstValue\"],\"opaqueSecret123\"]}")
        )
        assertEquals("Authorization: [[redacted]]", CoreTelemetryService.sanitizeLocalPaths("Authorization: [opaqueSecret123]"))
        assertEquals(
            "Authorization: [[redacted]]\r\n\t[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: [firstValue]\r\n\topaqueSecret123")
        )
        assertEquals(
            "Authorization: \"[redacted]\"\r\n\t[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: \"firstValue\"\r\n\topaqueSecret123")
        )
        assertEquals(
            "Authorization: \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: \"firstValue\" opaqueSecret123")
        )
        assertEquals(
            "Authorization: \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: \"firstValue\" x opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\": \"[redacted]\", \"status\": \"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\": \"opaqueSecret123\", \"status\": \"safe\"}")
        )
    }

    @Test
    fun `sanitizeLocalPaths preserves comma-first JSON primitives and folded headers`() {
        assertEquals(
            "{\"Authorization\":\"[redacted]\"\n  , \"status\":\"safe\"\n}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"opaqueSecret123\"\n  , \"status\":\"safe\"\n}")
        )
        assertEquals(
            "Authorization: \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: \"firstValue\" ]opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":[\"[redacted]\",\"[redacted]\",null]}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":[123,true,null]}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\",\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":12345,\"status\":\"safe\"}")
        )
        assertEquals(
            "{\"Authorization\":null,\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":null,\"status\":\"safe\"}")
        )
    }

    @Test
    fun `sanitizeLocalPaths redacts JSON object auth values and non-JSON quoted keys`() {
        assertEquals(
            "\"Authorization\": \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("\"Authorization\": \"firstValue\" ]opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\",\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":{\"token\":\"secret\"},\"status\":\"safe\"}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\",\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":{\n\"token\":\"opaqueSecret123\"\n},\"status\":\"safe\"}")
        )
        assertEquals(
            "{\"Authorization\":[\"[redacted]\"],\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":[{\"token\":\"start],opaqueSecret123\"}],\"status\":\"safe\"}")
        )
        val paddedUnmatched =
            "Authorization: {\nAuthorization: {\nAuthorization: {\nAuthorization: {\n" +
                "x".repeat(5_000) +
                "\n{\"Authorization\":{\n\"token\":\"opaqueSecret123\"\n},\"status\":\"safe\"}"
        val scrubbedPadded = CoreTelemetryService.sanitizeLocalPaths(paddedUnmatched)
        assertEquals(false, scrubbedPadded.contains("opaqueSecret123"))
        assertEquals(true, scrubbedPadded.endsWith("{\"Authorization\":\"[redacted]\",\"status\":\"safe\"}"))
    }

    @Test
    fun `sanitizeLocalPaths handles quote prefixes truncated JSON objects and non-JSON commas`() {
        assertEquals(
            "note \" {\"Authorization\":\"[redacted]\",\"status\":\"safe\"}",
            CoreTelemetryService.sanitizeLocalPaths("note \" {\"Authorization\":{\"token\":\n\"opaqueSecret123\"\n},\"status\":\"safe\"}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\"",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":{\n\"token\":\"opaqueSecret123\"\n")
        )
        assertEquals(
            "prefix, \"Authorization\": \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("prefix, \"Authorization\": \"firstValue\" }opaqueSecret123")
        )
        assertEquals(
            "{\"status\":401,\"Authorization\":\"[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"status\":401,\"Authorization\":\"opaqueSecret123\"}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\"[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"firstValue\", \"opaqueSecret123\"}")
        )
        assertEquals(
            "note \"hi\", \"Authorization\": \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("note \"hi\", \"Authorization\": \"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "note: \"hi\", \"Authorization\": \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("note: \"hi\", \"Authorization\": \"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "request, \"note\": \"hi\", \"Authorization\": \"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("request, \"note\": \"hi\", \"Authorization\": \"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"bad\tkey\":\"hi\",\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"bad\tkey\":\"hi\",\"Authorization\":\"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"a\":1,\"b\":\"hi\",\"Authorization\":\"[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"a\":1,\"b\":\"hi\",\"Authorization\":\"opaqueSecret123\"}")
        )
        assertEquals(
            "{\"a\":{\"b\":1},\"Authorization\":\"[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"a\":{\"b\":1},\"Authorization\":\"opaqueSecret123\"}")
        )
    }

    @Test
    fun `sanitizeLocalPaths scales linearly on nested JSON and redacts webhook URL tokens`() {
        val repeatedMalformedBack = "\"\n" + "}, \"Authorization\": ".repeat(2_000)
        assertEquals(true, CoreTelemetryService.sanitizeLocalPaths(repeatedMalformedBack).isNotEmpty())
        val nestedJsonAuth = "{\"a\":".repeat(2_000) + "{}" + ",\"Authorization\":\"secret\"}".repeat(2_000)
        val scrubbedNested = CoreTelemetryService.sanitizeLocalPaths(nestedJsonAuth)
        assertEquals(false, scrubbedNested.contains("secret"))
        assertEquals(true, scrubbedNested.endsWith(",\"Authorization\":\"[redacted]\"}"))
        assertEquals(
            "https://discord.com/api/webhooks/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://discord." + "com/api/webhooks/123456789/fakeWebhookToken")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken")
        )
        assertEquals(
            "https://hooks.slack.com/services/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://hooks.slack." + "com/services/T0000/B0000/fakeWebhookToken")
        )
        assertEquals(
            "https://hooks.slack.com:443/services/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://hooks.slack." + "com:443/services/T0000/B0000/fakeWebhookToken")
        )
        assertEquals(
            "https://api.telegram.org/bot123456:[redacted]/getMe",
            CoreTelemetryService.sanitizeLocalPaths("https://api.telegram." + "org/bot123456:fakeBotToken/getMe")
        )
        assertEquals(
            "Authorization: [[redacted]]\r\nNextHeader: safe",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: [firstValue]\r\nNextHeader: safe")
        )
        assertEquals(
            "{\n  \"Authorization\": \"[redacted]\"\n  }",
            CoreTelemetryService.sanitizeLocalPaths("{\n  \"Authorization\": \"opaqueSecret123\"\n  }")
        )
        assertEquals("Authorization:\r\n\t[redacted]", CoreTelemetryService.sanitizeLocalPaths("Authorization:\r\n\topaqueSecret123"))
        assertEquals(
            "Authorization: Digest [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: Digest username=\"alice\",\r\n response=\"deadbeef\"")
        )
        assertEquals(
            "Authorization:\r\nNextHeader: safe",
            CoreTelemetryService.sanitizeLocalPaths("Authorization:\r\nNextHeader: safe")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\",\"status\":401}",
            CoreTelemetryService.sanitizeLocalPaths("{\"\\u0041uthorization\":\"opaqueSecret123\",\"status\":401}")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authoriz\\u0061tion\":\"opaqueSecret123\"}")
        )
        assertEquals(
            "{\"Authorization\": \n \"[redacted]\",\"status\":401}",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\": \n \"opaqueSecret123\",\"status\":401}")
        )
    }

    @Test
    fun `sanitizeLocalPaths redacts standalone Basic tokens and opaque Authorization headers`() {
        assertEquals(
            "\"Authorization\": [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("\"Authorization\": opaqueSecret123 nextPart")
        )
        assertEquals(
            "Authorization: [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: opaqueSecret123 nextPart")
        )
        assertEquals(
            "Authorization: [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("Authorization: X-Secret secondword")
        )
        assertEquals("Basic [redacted]", CoreTelemetryService.sanitizeLocalPaths("Basic YTpi"))
        assertEquals("Basic [redacted].", CoreTelemetryService.sanitizeLocalPaths("Basic YTpi."))
        assertEquals("Basic [redacted]", CoreTelemetryService.sanitizeLocalPaths("Basic YTo="))
        assertEquals("Basic YTp=", CoreTelemetryService.sanitizeLocalPaths("Basic YTp="))
        assertEquals("Basic [redacted]", CoreTelemetryService.sanitizeLocalPaths("Basic Og=="))
        assertEquals("Basic [redacted]", CoreTelemetryService.sanitizeLocalPaths("Basic ajpqampq"))
        assertEquals("BASIC [redacted]", CoreTelemetryService.sanitizeLocalPaths("BASIC YWxpY2U6c2VjcmV0"))
        assertEquals("Basic [redacted]", CoreTelemetryService.sanitizeLocalPaths("Basic YWxpY2U6Y2Fm6Q=="))
        assertEquals("Basic YWJjZGU=", CoreTelemetryService.sanitizeLocalPaths("Basic YWJjZGU="))
        assertEquals("Basic YTpiCQ", CoreTelemetryService.sanitizeLocalPaths("Basic YTpiCQ"))
        assertEquals("Basic auth failed", CoreTelemetryService.sanitizeLocalPaths("Basic auth failed"))
        val repeatedFilenameEmails = "alice.txt@example.org ".repeat(5_000)
        assertEquals(
            "[redacted-email] ".repeat(5_000),
            CoreTelemetryService.sanitizeLocalPaths(repeatedFilenameEmails)
        )
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("/tmp/Private\u2028Acquisition Plan"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Private.qzx'2 Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 2's Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 2' Acquisition.qzx'"))
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("'Studio 'Private Acquisition.qzx'"))
        assertEquals(
            "status \"ready; detail [redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("status \"ready; detail \"Private Project.qzx\"")
        )
        assertEquals("[redacted-path]", CoreTelemetryService.sanitizeLocalPaths("private]folder\\secret"))
        assertEquals(
            "progress 1/2. (https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("progress 1/2. (https://example.com/api)")
        )
        assertEquals("opening [redacted-path]", CoreTelemetryService.sanitizeLocalPaths("opening 1/2.mov"))
        assertEquals(
            "opening\u00A0[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("opening\u00A0Acquisition.qzx")
        )
    }

    @Test
    fun `sanitizeLocalPaths rejects malformed JSON escapes or containers and scrubs encoded webhook URLs`() {
        val badEscapeScrubbed = CoreTelemetryService.sanitizeLocalPaths(
            "{\"bad\\qkey\":\"hi\",\"Authorization\":\"firstValue\" } opaqueSecret123"
        )
        assertEquals(false, badEscapeScrubbed.contains("firstValue"))
        assertEquals(false, badEscapeScrubbed.contains("opaqueSecret123"))
        assertEquals(
            "{\"\\q\":\"hi\",\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"\\q\":\"hi\",\"Authorization\":\"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"a\":[{],\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"a\":[{],\"Authorization\":\"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"bad\\qval\" } opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"\\Authorization\":\"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\" [redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"firstValue\" } opaqueSecret123")
        )
        assertEquals(
            "{\"Authorization\":\"[redacted]\"[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("{\"Authorization\":\"firstValue\",\"a\":[{]} opaqueSecret123")
        )
        assertEquals(
            "[{\"Authorization\":\"[redacted]\"},{\"status\":\"safe\"}]",
            CoreTelemetryService.sanitizeLocalPaths("[{\"Authorization\":\"opaqueSecret123\"},{\"status\":\"safe\"}]")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken,remainingSecret123")
        )
        assertEquals(
            "https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/,remainingSecret123")
        )
        assertEquals(
            "https://example.test/%77ebhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/%77ebhook/fakeToken")
        )
        assertEquals(
            "https://example.test/webhook/[redacted], next",
            CoreTelemetryService.sanitizeLocalPaths("https://example.test/webhook/fakeToken, next")
        )
    }
}
