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
    fun `sanitizeLocalPaths redacts unix unicode windows unc file uri and spaced filenames`() {
        val rawMessage = buildString {
            appendLine("Opened /workspace/E\u0301lodie/Very Secret Client Project Name.mov")
            appendLine("Failed to open '/data/user/0/O'Brien.mov'")
            appendLine("""Read "/Users/O'Brien/Final.mov"""")
            appendLine("Failed reading /Private (Client).mov")
            appendLine("Secret /Private Client")
            appendLine("Key /private-key")
            appendLine("Path /workspace/alice/Project;Acquisition.mov")
            appendLine("Colon '/workspace/alice/Project:2026;Acquisition.mov'")
            appendLine("FakeStack /Users/alice/Draft:77 in open() Acquisition.mov")
            appendLine("Pipe /workspace/alice/Project|Acquisition.mov")
            appendLine("Angle /Users/alice/Secret<Project>.mov")
            appendLine("Bang /!Acquisition.mov")
            appendLine("""Quoted "/!Acquisition.mov"""")
            appendLine("""Could not open C:\Users\Alice Smith\Private (Confidential).mp4""")
            appendLine("ColonPrefixed error 42:/home/alice/private.mov")
            appendLine("""\\fileserver\share\secret.mp4""")
            appendLine("~/private/key.pem")
        }
        val rawStack = buildString {
            appendLine("""QuotedStack "/home/alice/source.kt:88"""")
            appendLine("file:///Users/alice/Player.swift:42")
            appendLine("at /Users/alice/Player.swift:77 in open()")
            appendLine("at Foo.open() in /data/user/0/net.alummahnow.viewer/files/Final Cut.kt:42")
        }
        val scrubbedMessage = CoreTelemetryService.sanitizeLocalPaths(rawMessage, preserveStackLocations = false)
        val scrubbedStack = CoreTelemetryService.sanitizeLocalPaths(rawStack, preserveStackLocations = true)
        val scrubbed = scrubbedMessage + "\n" + scrubbedStack
        assertFalse(scrubbed.contains("alice", ignoreCase = true))
        assertFalse(scrubbed.contains("smith", ignoreCase = true))
        assertFalse(scrubbed.contains("lodie", ignoreCase = true))
        assertFalse(scrubbed.contains("brien", ignoreCase = true))
        assertFalse(scrubbed.contains("private", ignoreCase = true))
        assertFalse(scrubbed.contains("client", ignoreCase = true))
        assertFalse(scrubbed.contains("acquisition", ignoreCase = true))
        assertFalse(scrubbed.contains("project>.mov", ignoreCase = true))
        assertFalse(scrubbed.contains("name.mov", ignoreCase = true))
        assertFalse(scrubbed.contains("confidential", ignoreCase = true))
        assertFalse(scrubbed.contains("final cut", ignoreCase = true))
        assertFalse(scrubbed.contains("workspace", ignoreCase = true))
        assertFalse(scrubbed.contains("fileserver", ignoreCase = true))
        assertFalse(scrubbed.contains("key.pem", ignoreCase = true))
        assertFalse(scrubbed.contains("source.kt", ignoreCase = true))
        assertFalse(scrubbedMessage.contains("in open()"))
        assertTrue(scrubbedStack.contains("[redacted-path]:42"))
        assertTrue(scrubbedStack.contains("[redacted-path]:77 in open()"))
        assertTrue(scrubbedStack.contains("[redacted-path]:88"))
        assertTrue(scrubbed.contains("[redacted-path]"))
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
