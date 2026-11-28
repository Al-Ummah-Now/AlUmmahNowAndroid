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

    @Test
    fun `property keys identifiers numeric values and share links scrub PII and short tokens`() {
        assertEquals(
            """{"[redacted-phone]":true}""",
            CoreTelemetryService.serializeProperties(mapOf("+٩٧١٥٠١٢٣٤٥٦" to true))
        )
        assertEquals(
            """{"[redacted]":"[redacted]"}""",
            CoreTelemetryService.serializeProperties(mapOf("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig_part-1" to "failed"))
        )
        assertEquals(
            """{"[redacted-ip]":true,"recipient":"[redacted-number]"}""",
            CoreTelemetryService.serializeProperties(mapOf("192.0.2.10" to true, "recipient" to 12025550148L))
        )
        assertEquals("[redacted-phone]", CoreTelemetryService.sanitizePropertyKey("2025550148"))
        assertEquals(
            "https://share.example.com/s/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://share.example.com/s/V8r2Qp6Lm4")
        )
        assertEquals(
            "https://www.dropbox.com/scl/[redacted]",
            CoreTelemetryService.sanitizeLocalPaths(
                "https://www.dropbox.com/scl/fi/a1b2c3d4e5f6g7h8i9j0/PrivateAcquisition.pdf"
            )
        )
        assertEquals("[redacted-ip]", CoreTelemetryService.sanitizePropertyKey("fe80::1"))
        assertEquals("[redacted-ip]", CoreTelemetryService.sanitizePropertyKey("2001:db8::beef:cafe"))
        val detailProps = CoreTelemetryService.serializeProperties(
            mapOf("detail" to "Peer 192.168.42.17 called +٩٧١٥٠١٢٣٤٥٦٧ from fe80::1")
        )
        assertEquals(
            """{"detail":"Peer [redacted-ip] called [redacted-phone] from [redacted-ip]"}""",
            detailProps
        )
    }

    @Test
    fun `punctuation IPs camelCase credentials supplementary phones JWEs Drive URLs and BigDecimal are scrubbed`() {
        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "token=cobalt-orchard" to true,
                "verificationCode" to "482193",
                "reference" to java.math.BigDecimal("1.23456789E+10"),
                "detail" to """Connection failed at 10.0.0.1. peer:2001:db8::1 """ +
                    """clientSecret="cobalt orchard" Callback +𝟏𝟐𝟏𝟐𝟓𝟓𝟓𝟎𝟏𝟐𝟑 Call ٠٥٠‑١٢٣‑٤٥٦٧ """ +
                    """eyJhbGciOiJkaXIifQ..iv1234.cipher5678.tag9012 """ +
                    """eyJhbGciOiJSU0EtT0FFUCJ9.encKey1.iv1234.cipher9999.tag8888 """ +
                    """https://drive.google.com/file/u/0/d/AbCdEfGhIjKlMnOpQrStUvWxYzABCDE/view """ +
                    """https://example.com/a;jsessionid=abc123"""
            )
        )
        assertTrue(props != null)
        assertFalse(props!!.contains("cobalt-orchard"))
        assertFalse(props.contains("482193"))
        assertFalse(props.contains("12345678900"))
        assertFalse(props.contains("1.23456789"))
        assertFalse(props.contains("10.0.0.1"))
        assertFalse(props.contains("2001:db8"))
        assertFalse(props.contains("orchard"))
        assertFalse(props.contains("𝟏𝟐𝟏𝟐"))
        assertFalse(props.contains("١٢٣"))
        assertFalse(props.contains("cipher5678"))
        assertFalse(props.contains("cipher9999"))
        assertFalse(props.contains("AbCdEfGhIjKlMnOpQrStUvWxYzABCDE"))
        assertFalse(props.contains("abc123"))
        assertEquals("[redacted-ip].", CoreTelemetryService.sanitizePropertyKey("10.0.0.1."))
        assertEquals("token=[redacted]", CoreTelemetryService.sanitizePropertyKey("token=cobalt-orchard"))
    }

    @Test
    fun `prefixed verification codes ipv4-tail ipv6 encoded url jwes whitespace jwes and huge-scale BigDecimal are safe`() {
        val throwingObj = object {
            override fun toString(): String = error("boom")
        }
        val wsJwe = "IHsiYWxnIjoiZGlyIiwiZW5jIjoiQTI1NkdDTSJ9..iv1234.cipher5678.tag9012"
        val props = CoreTelemetryService.serializeProperties(
            mapOf(
                "challenge.verificationCode" to 123456,
                "tinyScale" to java.math.BigDecimal.valueOf(1L, Int.MAX_VALUE),
                "badObj" to throwingObj,
                "ipv6Tail" to "peer 2001:db8:1234:5678:9abc:def0:192.0.2.128 source:2001:db8::1",
                "encodedUrl" to "https://example.com/download/eyJhbGciOiJkaXIifQ%2E%2Eiv1234%2Ecipher9999%2Etag8888",
                "wsJwe" to wsJwe
            )
        )
        assertTrue(props != null)
        assertFalse(props!!.contains("123456"))
        assertFalse(props.contains("2001:db8"))
        assertFalse(props.contains("9abc:def0"))
        assertFalse(props.contains("192.0.2.128"))
        assertFalse(props.contains("cipher9999"))
        assertFalse(props.contains("cipher5678"))
        assertTrue(props.contains("\"tinyScale\":0.0"))
        assertEquals("[redacted]", CoreTelemetryService.sanitizePropertyKey(wsJwe))
    }

    @Test
    fun `crash stack sanitizer rejects single-token headers and arbitrary filenames`() {
        assertEquals(null, CoreTelemetryService.sanitizeCrashStackText("AliceJohnson"))
        assertEquals(
            "com.example.Worker.run(line:42)",
            CoreTelemetryService.formatSafeStackFrame(
                StackTraceElement("com.example.Worker", "run", "AliceJohnson.kt", 42)
            )
        )
        assertEquals(
            "java.lang.IllegalStateException\n\tat com.example.Worker.run(line:42)",
            CoreTelemetryService.sanitizeCrashStackText(
                "java.lang.IllegalStateException\n" +
                    "\tat com.example.Worker.run(AliceJohnson.kt:42)\n" +
                    "\tat com.example.Worker.run(line:42)\n" +
                    "AliceJohnson"
            )
        )
    }
}
