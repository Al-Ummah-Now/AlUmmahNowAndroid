package net.alummahnow.core.bridge.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            CoreTelemetryService.sanitizeLocalPaths("\"https://example.test/webhook/fakeToken('a)first opaqueSecret123')\"")
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
            "{'a':'https://example.test/webhook/[redacted]'}",
            CoreTelemetryService.sanitizeLocalPaths("{'a':'https://example.test/webhook/fakeToken('}")
        )
        assertEquals(
            "{\"a\":\"https://example.test/webhook/[redacted]\",\"b\":\"public-text\"}",
            CoreTelemetryService.sanitizeLocalPaths("{\"a\":\"https://example.test/webhook/fakeToken(\",\"b\":\"public-text\"}")
        )
        assertEquals(
            "[\"https://a.test/webhook/[redacted]\",\"https://other.com/webhook/[redacted]\"]",
            CoreTelemetryService.sanitizeLocalPaths(
                "[\"https://a.test/webhook/tok(\",\"https://other.com/webhook/tok2('x)secret')\"]"
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

    @Test
    fun `sanitizeLocalPaths handles container-aware quote closers and punctuation-leading secrets`() {
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken(' )opaqueSecret123')'"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken(') opaqueSecret123')'"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken(')first opaqueSecret123')'"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]'",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken('} opaqueSecret123')'"
            )
        )
        assertEquals(
            "[\"https://example.test/webhook/[redacted]\",123,\")public-text\"]",
            CoreTelemetryService.sanitizeLocalPaths(
                "[\"https://example.test/webhook/fakeToken(\",123,\")public-text\"]"
            )
        )
        assertEquals(
            "[{\"a\":\"https://example.test/webhook/[redacted]\"},\")public\"]",
            CoreTelemetryService.sanitizeLocalPaths(
                "[{\"a\":\"https://example.test/webhook/fakeToken(\"},\")public\"]"
            )
        )
        assertEquals(
            "['https://example.test/webhook/[redacted]']",
            CoreTelemetryService.sanitizeLocalPaths(
                "['https://example.test/webhook/fakeToken(',123, opaqueSecret123')']"
            )
        )
    }

    @Test
    fun `sanitizeLocalPaths enforces RFC 8259 double quotes and scrubs unclosed single-quoted webhooks`() {
        assertEquals(
            "['https://example.test/webhook/[redacted]']",
            CoreTelemetryService.sanitizeLocalPaths(
                "['https://example.test/webhook/fakeToken(',{ opaqueSecret123')']"
            )
        )
        assertEquals(
            "[\n  \"https://example.test/webhook/[redacted]\",123,\")public-text\"]",
            CoreTelemetryService.sanitizeLocalPaths(
                "[\n  \"https://example.test/webhook/fakeToken(\",123,\")public-text\"]"
            )
        )
        assertEquals(
            "'https://example.test/webhook/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths(
                "'https://example.test/webhook/fakeToken('unclosed opaqueSecret123"
            )
        )
    }

    @Test
    fun `sanitizeLocalPaths enforces RFC 8259 quote precedence and JSON escapes`() {
        val cases = listOf(
            "[\"https://example.test/webhook/fakeToken('open\",123,\")public\"]" to
                "[\"https://example.test/webhook/[redacted]\",123,\")public\"]",
            "[\"https://example.test/webhook/fakeToken('a\",\")public-text\"]" to
                "[\"https://example.test/webhook/[redacted]\",\")public-text\"]",
            "[\"see https://example.test/webhook/fakeToken('open\",123,\")public\"]" to
                "[\"see https://example.test/webhook/[redacted]\",123,\")public\"]",
            "[\"see https://example.test/webhook/fakeToken('a)first opaqueSecret123')\"]" to
                "[\"see https://example.test/webhook/[redacted]\"]",
            "[\"https://example.test/webhook/fakeToken(\\\"opaqueSecret123)\"]" to
                "[\"https://example.test/webhook/[redacted]\"]",
            "[\"https://example.test/webhook/fakeToken(\\\"opaqueSecret123\\\")\"]" to
                "[\"https://example.test/webhook/[redacted]\"]",
            "[\"https://example.test/webhook/fakeToken(\\\"first opaqueSecret123\\\")\",\"public\"]" to
                "[\"https://example.test/webhook/[redacted]\",\"public\"]",
            "[\"https://example.test/webhook/fakeToken\\\"opaqueSecret123\"]" to
                "[\"https://example.test/webhook/[redacted]\"]",
            "[\"https://example.com/api{1:\\\"see https://other.com/x?token=opaqueSecret123\\\"}?outer=value\"]" to
                "[\"https://example.com/api{1:\\\"see https://other.com/x?[redacted]}?[redacted]\"]",
            "[\"https://example.com/api?token=opaqueSecret123\\\"moreSecret\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api?token=first\\\",opaqueSecret123\",\"public\"]" to
                "[\"https://example.com/api?[redacted]\",\"public\"]",
            "[\"https://example.com/api{1:\\\"see https://other.com/x?token=opaqueSecret123\\\"}\"]" to
                "[\"https://example.com/api{1:\\\"see https://other.com/x?[redacted]}\"]",
            "https://example.com/api?token=first\\\"opaqueSecret123" to
                "https://example.com/api?[redacted]",
            "[\"see \\\"example https://example.com/api?token=first\\\",opaqueSecret123\"]" to
                "[\"see \\\"example https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api?token=first\\\">opaqueSecret123\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api?token=first<opaqueSecret123\",\"public\"]" to
                "[\"https://example.com/api?[redacted]\",\"public\"]",
            "[\"https://example.com/api(x)?token=first<opaqueSecret123\",\"public\"]" to
                "[\"https://example.com/api(x)?[redacted]\",\"public\"]",
            "[\"see <https://example.com/api?token=secret> public\"]" to
                "[\"see <https://example.com/api?[redacted]> public\"]",
            "[\"Acquisition.mov\"]" to "[\"[redacted-path]\"]",
            "[\"report.pdf\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"https://example.com/api?token=opaqueSecret123\",\"Quarterly Report.pdf\"]" to
                "[\"https://example.com/api?[redacted]\",\"[redacted-path]\"]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val repeatedRejected = "https:// ".repeat(2000)
        assertEquals("http[redacted-path] ", CoreTelemetryService.sanitizeLocalPaths(repeatedRejected))
    }

    @Test
    fun `sanitizeLocalPaths handles angle-wrapped queries, escaped Bearer quotes, and quoted JSON paths`() {
        val cases = listOf(
            "[\"see <https://example.com/api?token=first<opaqueSecret123> public\"]" to
                "[\"see <https://example.com/api?[redacted]> public\"]",
            "<https://example.com/api?token=first<opaqueSecret123> public" to
                "<https://example.com/api?[redacted]> public",
            "[\"Bearer abc\\\"opaqueSecret123\"]" to "[\"Bearer [redacted]\"]",
            "[\"Bearer abc\\\"opaqueSecret123\",\"public\"]" to "[\"Bearer [redacted]\",\"public\"]",
            "[\"Bearer abc\\\\\",\"public\"]" to "[\"Bearer [redacted]\",\"public\"]",
            "[\"C://Secret/Acquisition.mov\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"Failed reading C://Secret/Acquisition.mov\",\"public\"]" to
                "[\"Failed reading [redacted-path]\",\"public\"]",
            "[\"/Users/alice/Secret.mov\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"relative/Secret.mov\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"https://example.com/api?token=opaqueSecret123\",\"C://Secret/Acquisition.mov\",\"public\"]" to
                "[\"https://example.com/api?[redacted]\",\"[redacted-path]\",\"public\"]",
            "[\"hello \\\"world\\\"\",\"public\"]" to "[\"hello \\\"world\\\"\",\"public\"]",
            "[\"/\",\"public\"]" to "[\"/\",\"public\"]",
            "[\"\\/private/secret\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"\\/\",\"public\"]" to "[\"\\/\",\"public\"]",
            "[\"relative/\",\"public\"]" to "[\"relative/\",\"public\"]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val manySlashes = "[\"${"/".repeat(10_000)}\",\"public\"]"
        assertEquals(manySlashes, CoreTelemetryService.sanitizeLocalPaths(manySlashes))
    }

    @Test
    fun `sanitizeLocalPaths handles JSON escapes in quoted paths and unquoted fallback boundaries`() {
        val cases = listOf(
            "[\"relative\\/\",\"public\"]" to "[\"relative\\/\",\"public\"]",
            "[\"relative\\u002F\",\"public\"]" to "[\"relative\\u002F\",\"public\"]",
            "[\"\\u002Fprivate\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"\\u002fAcquisition\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"C\\u003A\\u002FSecret\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"\\u007E\\u002FSecret\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"\\u005C\\u005CServer\\u005CShare\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"hello\\nworld\",\"public\"]" to "[\"hello\\nworld\",\"public\"]",
            "[\"path=secret\",\"public\"]" to "[\"path=[redacted-path]\",\"public\"]",
            "[\"path=alpha\",\"path=beta\"]" to "[\"path=[redacted-path]\",\"path=[redacted-path]\"]",
            "[\"hello\\nworld\"] relative\\secret" to "[\"hello\\nworld\"] [redacted-path]",
            "[\"hello\\nworld\",\"public\"] relative\\Secret" to "[\"hello\\nworld\",\"public\"] [redacted-path]",
            "[\"public\",\"report.pdf attached\"]" to "[\"public\",\"[redacted-path] attached\"]",
            "{\"message\":\"see report.pdf now\",\"keep\":\"public\"}" to
                "{\"message\":\"[redacted-path] now\",\"keep\":\"public\"}",
            "{\"status\":\"secret.mov pending\"}" to "{\"status\":\"[redacted-path] pending\"}",
            "[\"path=alpha\",{\"keep\":\"beta.pdf attached\"}]" to
                "[\"path=[redacted-path]\",{\"keep\":\"[redacted-path] attached\"}]",
            "[\"read this\",{\"x\":\"report.pdf attached\"}]" to
                "[\"read this\",{\"x\":\"[redacted-path] attached\"}]",
            "{\"'\":\"secret.pdf' pending\"}" to "{\"'\":\"[redacted-path]' pending\"}",
            "[\"hello\\npath=Acquisition\"]" to "[\"hello\\npath=[redacted-path]\"]",
            "[\"hello\\npath=alpha\\npath=beta\"]" to "[\"hello\\npath=[redacted-path]\\npath=[redacted-path]\"]",
            "[\"path\\u003dAcquisition\"]" to "[\"path\\u003d[redacted-path]\"]",
            "[\"\\\\Secret\"]" to "[\"[redacted-path]\"]",
            "[\"\\u005CSecret\",\"public\"]" to "[\"[redacted-path]\",\"public\"]",
            "[\"\\\\\",\"public\"]" to "[\"\\\\\",\"public\"]",
            "[\"path=Project\\u005EAcquisition\"]" to "[\"path=[redacted-path]\"]",
            "[\"path=alpha\\u001fAcquisition\"]" to "[\"path=[redacted-path]\"]",
            "[\"path=alpha\\u001eAcquisition\"]" to "[\"path=[redacted-path]\"]",
            "{\"file \":\"Acquisition\"}" to "{\"file \":\"Acquisition\"}",
            "{\"path \":\"public\"}" to "{\"path \":\"public\"}",
            "[\"file 'Secret'\",\"public\"]" to "[\"file [redacted-path]\",\"public\"]",
            "read 'Top \"Secret\" file.pdf'" to "read [redacted-path]",
            "[\"secret\\u002epdf pending\"]" to "[\"[redacted-path] pending\"]",
            "[\"secret\\u002epdf\"]" to "[\"[redacted-path]\"]",
            "[\"read Private\\\"Merger.custom pending\"]" to "[\"read [redacted-path] pending\"]",
            "Failed reading /Users/alice/My \"Secret\" Folder/file.mov" to "Failed reading [redacted-path]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val manyPublic = "[${List(2000) { "\"public\"" }.joinToString(",")}]"
        assertEquals(manyPublic, CoreTelemetryService.sanitizeLocalPaths(manyPublic))
        val manyNewlines = "[${List(2000) { "\"hello\\nworld\"" }.joinToString(",")}]"
        assertEquals(manyNewlines, CoreTelemetryService.sanitizeLocalPaths(manyNewlines))
    }

    @Test
    fun `sanitizeLocalPaths bounds quoted file URIs and decodes quote and URL escapes`() {
        val cases = listOf(
            "[\"file \\u0027Acquisition\\u0027\"]" to "[\"file [redacted-path]\"]",
            "[\"file \\\"Acquisition\\\"\"]" to "[\"file [redacted-path]\"]",
            "[\"file \\u0022Acquisition\\u0022\"]" to "[\"file [redacted-path]\"]",
            "[\"file \\\"Acquisition\\\"\",\"public\"]" to "[\"file [redacted-path]\",\"public\"]",
            "[\"file \\\"Acquisition\\\" and path \\\"Secret\\\"\"]" to
                "[\"file [redacted-path] and path [redacted-path]\"]",
            "[\"\\u0027Secret.custom\\u0027 pending\"]" to "[\"[redacted-path] pending\"]",
            "[\"\\\"Secret.custom\\\" pending\"]" to "[\"\\\"[redacted-path]\\\" pending\"]",
            "[\"\\u0022Secret.custom\\u0022 pending\"]" to "[\"\\u0022[redacted-path]\\u0022 pending\"]",
            "[\"Secret\\\"file.custom\"]" to "[\"[redacted-path]\"]",
            "[\"file \\\"O'Brien Acquisition\\\"\"]" to "[\"file [redacted-path]\"]",
            "[\"file \\\"Acquisition's Plan\\\"\"]" to "[\"file [redacted-path]\"]",
            "[\"file 'Acquisition\\u0027s Plan'\"]" to "[\"file [redacted-path]\"]",
            "{\"message\":\"open file:///Users/Alice/Secret.mov\",\"status\":\"public\"}" to
                "{\"message\":\"open [redacted-path]\",\"status\":\"public\"}",
            "{\"message\":\"open file:\\/\\/\\/Users\\/Alice\\/Secret.mov\",\"status\":\"public\"}" to
                "{\"message\":\"open [redacted-path]\",\"status\":\"public\"}",
            "[\"https:\\/\\/example.com\\/public\"]" to "[\"https:\\/\\/example.com\\/public\"]",
            "[\"https:\\/\\/example.com\\/api?token=opaqueSecret123\",\"public\"]" to
                "[\"https://example.com/api?[redacted]\",\"public\"]",
            "[\"https:\\/\\/example.com\\/api\\u003ftoken=opaqueSecret123\",\"public\"]" to
                "[\"https://example.com/api?[redacted]\",\"public\"]",
            "[\"https://example.com/api\\u0023opaqueSecret123\"]" to
                "[\"https://example.com/api#[redacted]\"]",
            "[\"https://example.com/api\\u003ftoken=opaqueSecret123\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.test/webhook/fake\\u0054oken\"]" to
                "[\"https://example.test/webhook/[redacted]\"]",
            "[\"https://example.com/api\\u003Ftoken=prefix\\u0020opaqueSecret123\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api?token=first\\u0020secondSecret\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api\\u0023prefix\\u0020opaqueSecret123\"]" to
                "[\"https://example.com/api#[redacted]\"]",
            "[\"https://example.test/webhook/\\u00e9PrivateToken\"]" to
                "[\"https://example.test/webhook/[redacted]\"]",
            "[\"HTTP/2\"]" to "[\"HTTP/2\"]",
            "[\"HTTP/1.1\"]" to "[\"HTTP/1.1\"]",
            "[\"HTTP\\/2\"]" to "[\"HTTP\\/2\"]",
            "[\"TLS/1.3\"]" to "[\"TLS/1.3\"]",
            "[\"SSL/3.0\"]" to "[\"SSL/3.0\"]",
            "[\"HTTP/2/Secret.mov\"]" to "[\"[redacted-path]\"]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val manyApostrophes = "file '${"a'".repeat(5000)}end'"
        assertEquals("file [redacted-path]", CoreTelemetryService.sanitizeLocalPaths(manyApostrophes))
        val manyUnclosedApostrophes = "file '${"a'".repeat(5000)}end"
        assertEquals(manyUnclosedApostrophes, CoreTelemetryService.sanitizeLocalPaths(manyUnclosedApostrophes))
        val manyFileUris = List(2000) { "open file:///Users/Alice/Secret.mov" }.joinToString("\n")
        val expectedFileUris = List(2000) { "open [redacted-path]" }.joinToString("\n")
        assertEquals(expectedFileUris, CoreTelemetryService.sanitizeLocalPaths(manyFileUris))
    }

    @Test
    fun `sanitizeLocalPaths preserves vault quote state inline query spaces and escapes before emails`() {
        val cases = listOf(
            "[\"https://example.test/api\\u0028v1\\u0029?token=opaqueSecret123\"]" to
                "[\"https://example.test/api(v1)?[redacted]\"]",
            "[\"https://example.com/api?token=first secondSecret\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api?token=first\u00A0secondSecret\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/api#first secondSecret\"]" to
                "[\"https://example.com/api#[redacted]\"]",
            "[\"https:\\/\\/example.com\\/api?token=first secondSecret\"]" to
                "[\"https://example.com/api?[redacted]\"]",
            "[\"https://example.com/webhook/first\\u0022secondSecret\"]" to
                "[\"https://example.com/webhook/[redacted]\"]",
            "[\"prefix\\nalice@example.com\"]" to "[\"prefix\\n[redacted-email]\"]",
            "[\"\\u0020alice@example.com ok\"]" to "[\"\\u0020[redacted-email] ok\"]",
            "[\"https://a.test\",\"open /foo\",\"https://b.test\"]" to
                "[\"https://a.test\",\"open [redacted-path]\",\"https://b.test\"]",
            "[\"https://example.com/webhook/first\\u00A0secondSecret\"]" to
                "[\"https://example.com/webhook/[redacted]\"]",
            "[\"https://example.com/webhook/\\u00A0secondSecret\"]" to
                "[\"https://example.com/webhook/[redacted]\"]",
            "[\" https://example.com/api?token=first secondSecret\"]" to
                "[\" https://example.com/api?[redacted]\"]",
            "[\"fetch https://example.com/api?token=first secondSecret\"]" to
                "[\"fetch https://example.com/api?[redacted]\"]",
            "[\"fetch https://example.com/api?token=first secondSecret\",\"public\"]" to
                "[\"fetch https://example.com/api?[redacted]\",\"public\"]",
            "[\"al\\u0069\\u0063e@example.com ok\"]" to "[\"[redacted-email] ok\"]",
            "[\"prefix\\nal\\u0069\\u0063e@example.com\"]" to "[\"prefix\\n[redacted-email]\"]",
            "[\"\\u0020al\\u0069\\u0063e@example.com ok\"]" to "[\"\\u0020[redacted-email] ok\"]",
            "[\"alice\\u0040example.com ok\"]" to "[\"[redacted-email] ok\"]",
            "[\"al\\u0069\\u0063e\\u0040example\\u002e\\u0063om ok\"]" to "[\"[redacted-email] ok\"]",
            "[\"see <https://example.com/api?token=first secondSecret> public\"]" to
                "[\"see <https://example.com/api?[redacted]> public\"]",
            "[\"https://example.com/docs/[redacted] public\",\"sibling\"]" to
                "[\"https://example.com/docs/[redacted] public\",\"sibling\"]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
    }

    @Test
    fun `sanitizeLocalPaths scrubs JSON-embedded auth headers escaped tokens quoted emails and spaced URLs`() {
        val cases = listOf(
            "[\"alice\\u0040exa\\u0301mple.com ok\"]" to "[\"[redacted-email] ok\"]",
            "{\"detail\":\"alice@exa\\u0301mple.test failed\"}" to "{\"detail\":\"[redacted-email] failed\"}",
            "{\"detail\":\"\\\"Jane Doe\\\"@example.test failed\"}" to "{\"detail\":\"[redacted-email] failed\"}",
            "{\"detail\":\"\\u0022Jane Doe\\u0022\\u0040exa\\u0301mple.test failed\"}" to
                "{\"detail\":\"[redacted-email] failed\"}",
            "[\"see \\u003chttps://example.com/api?token=first secondSecret\\u003e public\"]" to
                "[\"see \\u003chttps://example.com/api?[redacted]\\u003e public\"]",
            "{\"detail\":\"<https://example.test/webhook/SECRET TAIL>\"}" to
                "{\"detail\":\"<https://example.test/webhook/[redacted]>\"}",
            "{\"detail\":\"see <https://example.test/webhook/SECRET TAIL> public\"}" to
                "{\"detail\":\"see <https://example.test/webhook/[redacted]> public\"}",
            "{\"detail\":\"see \\u003chttps://example.test/webhook/SECRET TAIL\\u003e public\"}" to
                "{\"detail\":\"see \\u003chttps://example.test/webhook/[redacted]\\u003e public\"}",
            "{\"message\":\"Authorization: Bearer secret\",\"status\":\"ok\"}" to
                "{\"message\":\"Authorization: Bearer [redacted]\",\"status\":\"ok\"}",
            "{\"message\":\"Authorization:\\u0020Bearer\\u0020secret\",\"status\":\"ok\"}" to
                "{\"message\":\"Authorization:\\u0020Bearer [redacted]\",\"status\":\"ok\"}",
            "{\"message\":\"Authorization: Bearer secret\\nX-Public: ok\",\"status\":\"ok\"}" to
                "{\"message\":\"Authorization: Bearer [redacted]\\nX-Public: ok\",\"status\":\"ok\"}",
            "{\"message\":\"Bearer\\u0020secret\"}" to "{\"message\":\"Bearer [redacted]\"}",
            "{\"message\":\"Bearer\\tsecret\"}" to "{\"message\":\"Bearer [redacted]\"}",
            "{\"message\":\"Bearer\\u00A0secret\"}" to "{\"message\":\"Bearer [redacted]\"}",
            "{\"message\":\"Basic\\u0020dXNlcjpwYXNz\"}" to "{\"message\":\"Basic [redacted]\"}",
            "{\"message\":\"https://example.com/path name?token=private\"}" to
                "{\"message\":\"https://example.com/path name?[redacted]\"}",
            "{\"message\":\"https://example.com/path\\u0020name?token=private\"}" to
                "{\"message\":\"https://example.com/path\\u0020name?[redacted]\"}",
            "{\"message\":\"see https://example.com/public Why did it fail?\"}" to
                "{\"message\":\"see https://example.com/public Why did it fail?\"}",
            "{\"message\":\"see https://example.com/public issue #123\"}" to
                "{\"message\":\"see https://example.com/public issue #123\"}",
            "\"Authorization\\u003a secret\"" to "\"Authorization\\u003a [redacted]\"",
            "\"Authorization\\u003dsecret\"" to "\"Authorization\\u003d[redacted]\"",
            "headers=\"Authorization\\u003a opaqueSecret123\"" to "headers=\"Authorization\\u003a [redacted]\"",
            "headers=\"Authorization\\u003d opaqueSecret123\" status=ok" to
                "headers=\"Authorization\\u003d [redacted]\" status=ok"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
    }

    @Test
    fun `sanitizeLocalPaths scales linearly on repeated unclosed angles and serializeProperties redacts secrets`() {
        val repeatedAngles = "<https://example.test/webhooks/token ".repeat(500)
        val expectedAngles = "<https://example.test/webhooks/[redacted] ".repeat(500)
        assertEquals(expectedAngles, CoreTelemetryService.sanitizeLocalPaths(repeatedAngles))
        val spacedHashes = "\"" + " ".repeat(2000) + "https://example.test/path " + "# ".repeat(2000) + "\""
        assertEquals(spacedHashes, CoreTelemetryService.sanitizeLocalPaths(spacedHashes))

        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "detail" to "Authorization: Bearer secret",
                "path" to "/Users/Alice/Secret.mov",
                "/Users/Alice/Acquisition.mov" to 1,
                "/Users/Bob/Merger.mov" to 2,
                "Authorization" to "opaqueSecret123",
                "Cookie" to "session=opaqueSecret123",
                "Set-Cookie" to "sid=opaqueSecret456",
                "uri" to java.net.URI("https://example.com/api?token=secret"),
                "count" to 2,
                "ok" to true
            )
        )
        assertTrue(props != null)
        assertTrue(!props!!.contains("secret", ignoreCase = true))
        assertTrue(!props.contains("session=", ignoreCase = true))
        assertTrue(!props.contains("Alice"))
        assertTrue(!props.contains("Bob"))
        assertTrue(!props.contains("Acquisition"))
        assertTrue(!props.contains("Merger"))
        assertTrue(props.contains("\"[redacted-path]\":1"))
        assertTrue(props.contains("\"[redacted-path]#2\":2"))
        assertTrue(props.contains("\"detail\":\"Authorization: Bearer [redacted]\""))
        assertTrue(props.contains("\"path\":\"[redacted-path]\""))
        assertTrue(props.contains("\"Authorization\":\"[redacted]\""))
        assertTrue(props.contains("\"Cookie\":\"[redacted]\""))
        assertTrue(props.contains("\"Set-Cookie\":\"[redacted]\""))
        assertTrue(props.contains("\"uri\":\"https://example.com/api?[redacted]\""))
        assertTrue(props.contains("\"count\":2"))
        assertTrue(props.contains("\"ok\":true"))

        val manyColliding = LinkedHashMap<String, Any?>(5000)
        for (idx in 0 until 5000) manyColliding["/home/alice/private-$idx"] = idx
        val manyJson = CoreTelemetryService.serializeProperties(manyColliding)
        assertTrue(manyJson != null)
        assertTrue(!manyJson!!.contains("alice", ignoreCase = true))
        assertTrue(manyJson.contains("\"[redacted-path]#5000\":4999"))
    }
}
