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
    fun `the null service is off and accepts only off`() = runTest {
        assertEquals(TelemetryConsent.OFF, NullTelemetryService.consent)
        assertTrue(NullTelemetryService.setConsent(TelemetryConsent.OFF))
        assertFalse(NullTelemetryService.setConsent(TelemetryConsent.FULL))
        NullTelemetryService.reportCrash("IllegalStateException", "boom")
        NullTelemetryService.flush()
    }

    @Test
    fun `without the native library the factory falls back to the null service`() {
        // JVM unit tests have no libaun_core: the app must still start, with telemetry off
        val service = CoreTelemetryService.createOrNull(
            appVersion = "1.0.0",
            deviceClass = "phone",
            videosApiBaseUrl = "https://videos.api.alummahnow.net",
            userAgent = "AlUmmahNow-Android/1.0.0",
            clientHeader = "aun-android/1.0.0 (Android 15)",
            bufferPath = null,
            initialEnabled = true
        )
        assertEquals(NullTelemetryService, service)
    }
}
