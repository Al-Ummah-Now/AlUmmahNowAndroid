package net.alummahnow.core.bridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryQuotedEscapedTest {
    @Test
    fun `sanitizeLocalPaths scrubs cookies escaped header dumps tuples quoted bearer and file uri boundaries`() {
        val cases = listOf(
            "received (\"Authorization\\u003a opaqueSecret123\")" to "received (\"Authorization\\u003a [redacted]\")",
            "Authorization\\u003a opaqueSecret123" to "[redacted-path]",
            "{\"msg\":\"Basic YWI6\\u00632VjcmV0\"}" to "{\"msg\":\"Basic [redacted]\"}",
            "{\"m\":\"literal=\\\"test\\\\stuff\\\"tail\",\"next\":1}" to
                "{\"m\":\"literal=\\\"[redacted-path]\\\"tail\",\"next\":1}",
            "{\"m\":\"{\\\"path\\\":\\\"/Users/Alice/Secret.mov\\\"}\",\"next\":1}" to
                "{\"m\":\"{\\\"path\\\":\\\"[redacted-path]\\\"}\",\"next\":1}",
            "received \"Authorizatio\\u006e: opaqueSecret123\"" to "received \"Authorizatio\\u006e: [redacted]\"",
            "Cookie: session=opaqueSecret123" to "Cookie: [redacted]",
            "Set-Cookie: session=opaqueSecret123; HttpOnly\r\nNext: ok" to "Set-Cookie: [redacted]\r\nNext: ok",
            "{\"Cooki\\u0065\":\"session=opaqueSecret123\",\"status\":\"ok\"}" to
                "{\"Cookie\":\"[redacted]\",\"status\":\"ok\"}",
            "{\"m\":\"\\\"Bearer opaqueSecret123\\\"tail\",\"next\":1}" to
                "{\"m\":\"\\\"Bearer [redacted]\\\"tail\",\"next\":1}",
            "{\"m\":\"Bearer secret\\nX-Public: ok\"}" to "{\"m\":\"Bearer [redacted]\\nX-Public: ok\"}",
            "{\"Cookie \":\"session=opaqueSecret123\"}" to "{\"Cookie \":\"[redacted]\"}",
            "{\"Cookie\\t\":\"opaqueSecret123\"}" to "{\"Cookie\\t\":\"[redacted]\"}",
            "{\"\\tCookie\":\"opaqueSecret123\",\"Cookie\":\"otherSecret\"}" to
                "{\"\\tCookie\":\"[redacted]\",\"Cookie\":\"[redacted]\"}",
            "[[\"Cookie\",\"session=opaqueSecret123\"]]" to "[[\"Cookie\",\"[redacted]\"]]",
            "[[\"Cookie\\t\",\"opaqueSecret123\"]]" to "[[\"Cookie\\t\",\"[redacted]\"]]",
            "[[\"Authorization\",\"opaqueSecret123\"]]" to "[[\"Authorization\",\"[redacted]\"]]",
            "[\"Authorization\",\"Cookie\",\"Accept\"]" to "[\"Authorization\",\"Cookie\",\"Accept\"]",
            "{\\\"Authorization\\\":\\\"opaqueSecret123\\\"}" to "{\\\"Authorization\\\":\\\"[redacted]\\\"}",
            "{\\\"Cookie\\\":\\\"opaqueSecret123\\\"}" to "{\\\"Cookie\\\":\\\"[redacted]\\\"}",
            "{\\\"Authorization\\\":[123456789]}" to "{\\\"Authorization\\\":[\\\"[redacted]\\\"]}",
            "{\\\"Authorization\\\":123456789,\\\"next\\\":1}" to "{\\\"Authorization\\\":\\\"[redacted]\\\",\\\"next\\\":1}",
            "{\\\"Authorization\\\":{\\\"token\\\":\\\"secret\\\"},\\\"next\\\":1}" to
                "{\\\"Authorization\\\":\\\"[redacted]\\\",\\\"next\\\":1}",
            "{\"m\":\"auth = {\\\"a\\\":1,\\\"Cookie\\\":\\\"opaqueSecret123\\\"}; next\",\"next\":1}" to
                "{\"m\":\"auth = {\\\"a\\\":1,\\\"Cookie\\\":\\\"[redacted]\\\"}; next\",\"next\":1}",
            "{\"m\":\"Bearer \\\"opaqueSecret123\\\"tail\",\"next\":1}" to
                "{\"m\":\"Bearer \\\"[redacted]\\\"tail\",\"next\":1}",
            "{\"a\":\"Bearer \",\"b\":\"safe\"}" to "{\"a\":\"Bearer \",\"b\":\"safe\"}",
            "{\"m\":\"literal=\\\"file:///tmp/private.mov\\\"tail\",\"next\":1}" to
                "{\"m\":\"literal=\\\"[redacted-path]\\\"tail\",\"next\":1}"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        assertEquals("Bearer [redacted]", CoreTelemetryService.sanitizeLocalPaths("Bearer " + "A".repeat(65536)))
    }

    @Test
    fun `sanitizeLocalPaths handles escaped tuple containers multiline escaped dumps padded keys and prose JSON`() {
        val paddedCookie = "{\"Cookie" + " ".repeat(60) + "\":\"opaqueSecret123\"}"
        val expectedPadded = "{\"Cookie" + " ".repeat(60) + "\":\"[redacted]\"}"
        val cases = listOf(
            "[[\\\"Authorization\\\",[\\\"opaqueSecret123\\\"]]]" to "[[\\\"Authorization\\\",[\\\"[redacted]\\\"]]]",
            "[[\\\"Authorization\\\",[123456789]]]" to "[[\\\"Authorization\\\",[\\\"[redacted]\\\"]]]",
            "[[\\\"Authorization\\\",{\\\"token\\\":\\\"opaqueSecret123\\\"}]]" to "[[\\\"Authorization\\\",\\\"[redacted]\\\"]]",
            "{\\\"Authorization\\\":[\n  123456789\n],\\\"next\\\":1}" to
                "{\\\"Authorization\\\":[\n  \\\"[redacted]\\\"\n],\\\"next\\\":1}",
            "{\\\"Authorization\\\":\n\\\"opaqueSecret123\\\"}" to "{\\\"Authorization\\\":\n\\\"[redacted]\\\"}",
            paddedCookie to expectedPadded,
            "{\"m\":\"before {\\\"Cookie\\\":\\\"opaqueSecret123\\\"} after\",\"next\":1}" to
                "{\"m\":\"before {\\\"Cookie\\\":\\\"[redacted]\\\"} after\",\"next\":1}",
            "{\"m\":\"{\\\"Authorization\\\":\\\"opaqueSecret123\\\"} after\",\"next\":1}" to
                "{\"m\":\"{\\\"Authorization\\\":\\\"[redacted]\\\"} after\",\"next\":1}",
            "{\"m\":\"{\\\"Authorization\\\":\\\"opaqueSecret123\\\"}after\",\"next\":1}" to
                "{\"m\":\"{\\\"Authorization\\\":\\\"[redacted]\\\"}after\",\"next\":1}",
            "{\"\\u0063ookie\":\"firstSecret\",\"Cookie\":\"secondSecret\"}" to
                "{\"cookie\":\"[redacted]\",\"Cookie\":\"[redacted]\"}",
            "{\\\"\tCookie\t\\\":\\\"opaqueSecret123\\\"}" to "{\\\"\tCookie\t\\\":\\\"[redacted]\\\"}",
            "[[\\\"Authorization\\\",\\\"opaqueSecret123\\\"\\n]]" to "[[\\\"Authorization\\\",\\\"[redacted]\\\"\\n]]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        assertTrue(!CoreTelemetryService.sanitizeLocalPaths("{\"\tCookie\t\":\"opaqueSecret123\"}").contains("opaqueSecret123"))
        val malformedEscaped = "{\\\"Authorization\\\":[" + "{,".repeat(2000) + "]"
        assertTrue(CoreTelemetryService.sanitizeLocalPaths(malformedEscaped).startsWith("{\\\"Authorization\\\":[\\\"[redacted]\\\""))
        val repeatedVaultPrefix = "\u001EAUNURLx ".repeat(5000) + "https://example/path"
        assertEquals(repeatedVaultPrefix, CoreTelemetryService.sanitizeLocalPaths(repeatedVaultPrefix))
    }

    @Test
    fun `sanitizeLocalPaths redacts Slack dot-segments API keys content URIs and preserves path colons and empty ports`() {
        val cases = listOf(
            "https://hooks.slack.com/./services/T000/B000/fakeWebhookSecret" to
                "https://hooks.slack.com/./services/[redacted]",
            "https://hooks.slack.com/a/../services/T000/B000/fakeWebhookSecret" to
                "https://hooks.slack.com/a/../services/[redacted]",
            "https://hooks.slack.com/%2e/services/T000/B000/fakeWebhookSecret" to
                "https://hooks.slack.com/%2e/services/[redacted]",
            "https://hooks.slack.com//services/T000/B000/fakeWebhookSecret" to
                "https://hooks.slack.com//services/[redacted]",
            "https://example.com/projectc:/foo" to "https://example.com/projectc:/foo",
            "https://example.com/my-c:/foo" to "https://example.com/my-c:/foo",
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FPrivateAcquisition.mov" to
                "[redacted-path]",
            (
                "{\"uri\":\"content://com.android.externalstorage.documents/document/" +
                    "primary%3ADownload%2FPrivateAcquisition.mov\",\"ok\":true}"
                ) to
                "{\"uri\":\"[redacted-path]\",\"ok\":true}",
            (
                "{\"uri\":\"content:\\/\\/com.android.externalstorage.documents\\/document\\/" +
                    "primary%3ADownload%2FPrivateAcquisition.mov\",\"ok\":true}"
                ) to
                "{\"uri\":\"[redacted-path]\",\"ok\":true}",
            "https://proxy.example.com/https://hooks.slack.com/./services/T000/B000/fakeWebhookSecret" to
                "https://proxy.example.com/https://hooks.slack.com/./services/[redacted]",
            "X-API-Key: secret123" to "X-API-Key: [redacted]",
            "Api-Key: secret123" to "Api-Key: [redacted]",
            "X-Auth-Token: secret123" to "X-Auth-Token: [redacted]",
            "X-Access-Token: secret123" to "X-Access-Token: [redacted]",
            "{\"X-API-Key\":\"secret123\",\"Api-Key\":\"secret456\",\"X-Auth-Token\":\"tok789\"}" to
                "{\"X-API-Key\":\"[redacted]\",\"Api-Key\":\"[redacted]\",\"X-Auth-Token\":\"[redacted]\"}",
            "https://example.com:/api?token=secret123" to "https://example.com:/api?[redacted]",
            "https://example.com:?token=secret123" to "https://example.com:?[redacted]",
            "https://example.com:#secret123" to "https://example.com:#[redacted]",
            "HTTP/1/PrivateReorganization" to "[redacted-path]",
            "(HTTP/1/PrivateReorganization)" to "([redacted-path])",
            "HTTP/1.1 503 Service Unavailable" to "HTTP/1.1 503 Service Unavailable",
            "open \"proposal.pdf\" then completed in 3.14 seconds" to
                "open \"[redacted-path]\" then completed in 3.14 seconds",
            "open failed after 3.14 seconds" to "open failed after 3.14 seconds",
            "failed file:/Users/alice/secret.txt" to "failed [redacted-path]",
            "{\"uri\":\"file:\\/Users\\/alice\\/secret.txt\",\"ok\":true}" to
                "{\"uri\":\"[redacted-path]\",\"ok\":true}"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val repeatedSlackHosts = "https://hooks.slack.com" + "/https://hooks.slack.com".repeat(2000) + "/x"
        assertEquals(repeatedSlackHosts, CoreTelemetryService.sanitizeLocalPaths(repeatedSlackHosts))
        val props = CoreTelemetryService.serializeProperties(
            mapOf("X-API-Key" to "apiSecret789", "X-Auth-Token" to "authTok999", "screen" to "home")
        )
        assertTrue(props != null && !props.contains("apiSecret789") && !props.contains("authTok999"))
        assertTrue(props!!.contains("\"X-API-Key\":\"[redacted]\"") && props.contains("\"X-Auth-Token\":\"[redacted]\""))
    }
}
