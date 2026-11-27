package net.alummahnow.core.bridge.telemetry

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTelemetryServiceTest {
    @Test
    fun `resolveIngestUrl requires https strips path query fragment and rejects credentials`() {
        assertEquals(
            "https://videos.api.alummahnow.net/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.alummahnow.net/")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example/some/path?x=1#frag")
        )
        assertEquals(
            "https://videos.api.example:8443/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example:8443/v2?token=abc#top")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example:443/v1")
        )
        assertEquals(
            "https://videos.api.example/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://videos.api.example?x=1")
        )
        assertEquals(
            "https://[::1]:8443/api/v1/telemetry/batches",
            CoreTelemetryService.resolveIngestUrl("https://[::1]:8443/path?a=1#frag")
        )
        assertNull(CoreTelemetryService.resolveIngestUrl("https://user:password@videos.api.example/path"))
        assertNull(CoreTelemetryService.resolveIngestUrl("https://user@videos.api.example"))
        assertNull(CoreTelemetryService.resolveIngestUrl("http://videos.api.alummahnow.net"))
        assertNull(CoreTelemetryService.resolveIngestUrl("http://127.0.0.1:3086"))
        assertNull(CoreTelemetryService.resolveIngestUrl(""))
        assertNull(CoreTelemetryService.resolveIngestUrl(null))
    }

    @Test
    fun `sanitizeLocalPaths redacts unix unicode windows and unc paths`() {
        val raw = buildString {
            appendLine("Opened /workspace/E\u0301lodie/Very Secret Client Project Name.mov")
            appendLine("Failed to open '/data/user/0/O'Brien.mov'")
            appendLine("""Read "/Users/O'Brien/Final.mov"""")
            appendLine("Failed reading /Private (Client).mov")
            appendLine("Label /Private Client")
            appendLine("Key /private-key")
            appendLine("Path /workspace/alice/Project;Acquisition.mov")
            appendLine("Colon '/workspace/alice/Project:2026;Acquisition.mov'")
            appendLine("FakeStack /Users/alice/Draft:77 in open() Acquisition.mov")
            appendLine("Pipe /workspace/alice/Project|Acquisition.mov")
            appendLine("Angle /Users/alice/Secret<Project>.mov")
            appendLine("DoubleSlash open //storage/emulated/0/Private.mov")
            appendLine("""EmbeddedQuote open /storage/emulated/0/Private"Acquisition.mov""")
            appendLine("""LeadingSpace Failed to open "/ Secret/Acquisition.mov"""")
            appendLine("""LeadingSpaceSingle Failed to open "/ SecretFile"""")
            appendLine("UnquotedLeadingSpace / SecretFile.mov")
            appendLine("UnquotedLeadingSpaceNoExt / SecretFile")
            appendLine("UnquotedLeadingSpaceLongExt / SecretFile.abcdefghijkl")
            appendLine("DoubleSlashLeadingSpace // SecretFile")
            appendLine("ColonLeadingSpace 42:/ Secret/Acquisition.mov:77")
            appendLine("ColonDoubleSlash error 42://SecretHost/Acquisition.mov")
            appendLine("""EmbeddedUnc open(path\\unc\Secret\Acquisition.mov)""")
            appendLine("""EmbeddedDrive open(pathC:\Secret\Acquisition.mov)""")
            appendLine("DriveDoubleSlash open(C://Secret/Acquisition.mov)")
            appendLine("""WindowsDoubleSlash C://Users\Alice\Secret.mov""")
            appendLine("""Could not open C:\Users\Alice Smith\Private (Confidential).mp4""")
            appendLine("ColonPrefixed error 42:/home/alice/private.mov")
            appendLine("""\\fileserver\share\secret.mp4""")
            appendLine("~/private/key.pem")
            appendLine("Bang /!Acquisition.mov")
            appendLine("""Quoted "/!Acquisition.mov"""")
            appendLine("java.io.IOException: /Users/alice/Draft:99 in open()")
            appendLine("    at open /storage/emulated/0/Private:99\rCaused by: /Users/alice/Cause:99")
            appendLine("    at net.alummahnow.viewer.MainActivity.onCreate(MainActivity.kt:42)")
        }
        val scrubbed = CoreTelemetryService.sanitizeLocalPaths(raw)
        val forbidden = listOf(
            "alice", "smith", "lodie", "brien", "private", "client", "acquisition",
            "secret", "abcdefghijkl", "project>.mov", "storage", "name.mov",
            "confidential", "workspace", "fileserver", "key.pem"
        )
        for (token in forbidden) {
            assertFalse("Unexpected leak of $token", scrubbed.contains(token, ignoreCase = true))
        }
        assertFalse(scrubbed.contains(":99"))
        assertFalse(scrubbed.contains("in open()"))
        assertTrue(scrubbed.contains("MainActivity.kt:42"))
        assertTrue(scrubbed.contains("[redacted-path]"))
    }

    @Test
    fun `sanitizeLocalPaths redacts bare relative and adjacent filenames`() {
        val raw = buildString {
            appendLine("BareQuoted Could not find file 'Secret.mov'")
            appendLine("RelSlash Could not find file 'private/Secret.mov'")
            appendLine("DotRelSlash Could not find file './Secret.mov'")
            appendLine("ApostropheBare Could not find file 'O'Brien.mov'")
            appendLine("BareNoExt Could not find file 'SecretBareName'")
            appendLine("UnquotedUnknownExt Could not find file Secret.xyz")
            appendLine("UnquotedNumericExt Could not find file Secret.2026")
            appendLine("VerbUnknownExt open Acquisition.custom")
            appendLine("VerbNumericExt open Secret.123")
            appendLine("VerbSpacedExt open Secret Project.mov")
            appendLine("StandaloneSpacedParen Secret Project.mov (missing)")
            appendLine("MultiDotBare Secret.Final.mov")
            appendLine("AdjacentParenUrl (https://example.com)(private/Secret.mov)")
            appendLine("AdjacentCommaUrl https://example.com/api,open(C://Secret/Acquisition.mov)")
            appendLine("UrlParenPath https://example.com(/Users/alice/Secret.mov)")
        }
        val scrubbed = CoreTelemetryService.sanitizeLocalPaths(raw)
        for (token in listOf("secret", "private", "brien", "acquisition", "alice")) {
            assertFalse("Unexpected leak of $token", scrubbed.contains(token, ignoreCase = true))
        }
        assertEquals(
            "open [redacted-path] from https://example.com/api",
            CoreTelemetryService.sanitizeLocalPaths("open Secret.mov from https://example.com/api")
        )
        assertEquals(
            "open failed at MainActivity.kt:42",
            CoreTelemetryService.sanitizeLocalPaths("open failed at MainActivity.kt:42")
        )
        assertEquals(
            "open([redacted-path]) MainActivity.onCreate()",
            CoreTelemetryService.sanitizeLocalPaths("open(/Volumes/Secret/Acquisition.mov) MainActivity.onCreate()")
        )
        assertEquals(
            "[redacted-path] (missing)",
            CoreTelemetryService.sanitizeLocalPaths("Secret Project.mov (missing)")
        )
        assertEquals(
            "open [redacted-path]:42",
            CoreTelemetryService.sanitizeLocalPaths("open Secret.mov:42")
        )
        assertEquals(
            "Failure: Main.ts:42",
            CoreTelemetryService.sanitizeLocalPaths("Failure: Main.ts:42")
        )
    }

    @Test
    fun `sanitizeLocalPaths preserves valid RFC 3986 urls and rejects malformed or file uris`() {
        val preserved = listOf(
            "https://videos.api.alummahnow.net/v1/items",
            "https://[2001:db8::1]/v1/items",
            "https://example.com/a(b)/items",
            "https://example.com/a(b;c)/items",
            "open(https://example.com/a(b)/items)",
            "https://example.com/a'b/items",
            "https://foo!bar.example/api",
            "https://one;two.example/api",
            "https://example.com/v1;version=2/items",
            "git+http2://example.com/repo",
            "profile://example.com/repo",
            "https://[::1]:8443/api",
            "a123456789012345678901234567890123://example.com/repo",
            "HTTP/1.1 503 Service Unavailable"
        )
        for (item in preserved) {
            assertEquals(item, CoreTelemetryService.sanitizeLocalPaths(item))
        }
        assertEquals(
            "https://example.com/api,[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api,private/Secret.mov")
        )
        assertEquals(
            "https://example.com/api;[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api;file:///storage/emulated/0/Secret.mov")
        )
        assertEquals(
            "https://example.com/api;[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api;C://Secret/Acquisition.mov")
        )
        assertEquals(
            "https://example.com/api?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api?path=/storage/emulated/0/Secret.mov")
        )
        assertEquals(
            "https://example.com/api?[redacted]",
            CoreTelemetryService.sanitizeLocalPaths("https://example.com/api?source=%2FUsers%2Falice%2FPrivateProject")
        )
        assertEquals(
            "[redacted-path]",
            CoreTelemetryService.sanitizeLocalPaths("file:///storage/emulated/0/https://Secret/Acquisition.mov")
        )
        assertEquals(
            "([redacted-path] https://example.com/api)",
            CoreTelemetryService.sanitizeLocalPaths("(/storage/emulated/0/Secret.mov and https://example.com/api)")
        )
        assertEquals(
            "[redacted-path] https://example.com/api",
            CoreTelemetryService.sanitizeLocalPaths("file:///storage/emulated/0/Secret.mov and https://example.com/api")
        )
        assertFalse(CoreTelemetryService.sanitizeLocalPaths("https://[:::]/api") == "https://[:::]/api")
        assertFalse(CoreTelemetryService.sanitizeLocalPaths("https://bad%zz/api") == "https://bad%zz/api")
    }

    @Test
    fun `sanitizeLocalPaths scrubs url queries fragments emails bearer tokens and relative paths`() {
        val cases = listOf(
            "https://example.com/api?token=abc[secret]&next=private" to "https://example.com/api?[redacted]",
            "https://example.com/api?q=(PrivateProject)" to "https://example.com/api?[redacted]",
            "(https://example.com/api?q=(PrivateProject))" to "(https://example.com/api?[redacted])",
            "https://example.com/api#first,PrivateProject" to "https://example.com/api#[redacted]",
            "https://example.com/users/alice@example.com" to "https://example.com/users/[redacted-email]",
            "https://example.com/users/alice%40example.com" to "https://example.com/users/[redacted-email]",
            "https://example.com/users/alice%team@example.com" to "https://example.com/users/[redacted-email]",
            "alice%notes@example.com" to "[redacted-email]",
            "https://example.com/?token=abc{Secret}xyz" to "https://example.com/?[redacted]",
            "(https://example.com/?q=secret)." to "(https://example.com/?[redacted]).",
            "file:///private/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "file:///private/My Folder/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "/private/My Folder/(https://Secret/Acquisition.mov)" to "[redacted-path]",
            "(relative/Secret.mov and https://example.com/api)" to "([redacted-path] https://example.com/api)",
            "Failed for alice@example.com with Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig and Bearer abcdefg" to
                "Failed for [redacted-email] with Bearer [redacted] and Bearer [redacted]"
        )
        for ((input, expected) in cases) {
            assertEquals(expected, CoreTelemetryService.sanitizeLocalPaths(input))
        }
        val repeated = "(https://example.com)".repeat(500)
        assertEquals(repeated, CoreTelemetryService.sanitizeLocalPaths(repeated))
    }

    @Test
    fun `nullTelemetryService stays silent and denies enabling`() = runTest {
        assertEquals(TelemetryConsent.OFF, NullTelemetryService.consent)
        assertEquals(0, NullTelemetryService.pendingCount)
        assertFalse(NullTelemetryService.lastPurgeFailed)
        assertTrue(NullTelemetryService.setConsent(TelemetryConsent.OFF))
        assertFalse(NullTelemetryService.setConsent(TelemetryConsent.FULL))
        NullTelemetryService.track("screen_view", mapOf("screen" to "home"))
        NullTelemetryService.reportCrash("IllegalStateException", "boom")
        NullTelemetryService.flush()
        assertEquals(0, NullTelemetryService.pendingCount)
    }

    @Test
    fun `serializeProperties omits blank keys nulls and non-finite floats`() {
        assertNull(CoreTelemetryService.serializeProperties(emptyMap()))
        assertNull(CoreTelemetryService.serializeProperties(mapOf("" to "x", "n" to null, "d" to Double.NaN)))
        val json = CoreTelemetryService.serializeProperties(
            mapOf("screen" to "home", "count" to 2, "ok" to true, "drop" to null)
        )
        assertEquals("""{"screen":"home","count":2,"ok":true}""", json)
    }
}
