package net.alummahnow.core.bridge

import io.tus.java.client.TusURLMemoryStore
import java.util.Locale
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.media.Clip
import net.alummahnow.core.bridge.media.EditProject
import net.alummahnow.core.bridge.media.EditValidation
import net.alummahnow.core.bridge.media.TemporaryEditPlanner
import net.alummahnow.core.bridge.media.TextOverlay
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.upload.TemporaryTusUploadEngine
import net.alummahnow.core.bridge.upload.UploadEvent
import net.alummahnow.core.bridge.upload.UploadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeLogicTest {
    @Test fun `localized text falls back when translation missing or blank`() {
        val text = LocalizedText("Hello", mapOf("ar" to "مرحبا", "fr" to " "))
        assertEquals("مرحبا", text.resolve(Locale.forLanguageTag("ar")))
        assertEquals("Hello", text.resolve(Locale.FRENCH))
        assertEquals("Hello", text.resolve(Locale.forLanguageTag("ur")))
    }

    @Test fun `guest auth has sign-in disabled and refuses with Unavailable`() = runTest {
        val auth = GuestOnlyAuthBridge()
        assertFalse(auth.isSignInAvailable)
        assertEquals(null, auth.accessToken())
        assertTrue(runCatching { auth.beginPkceSignIn() }.exceptionOrNull() is CoreError.Unavailable)
        assertTrue(runCatching { auth.beginDeviceSignIn() }.exceptionOrNull() is CoreError.Unavailable)
    }

    @Test fun `upload without a token fails with NotSignedIn before touching the network`() = runTest {
        val engine = TemporaryTusUploadEngine("https://example.invalid/tus", TusURLMemoryStore(), tokenProvider = { null })
        val request = UploadRequest("l1", "/nope.mp4", "nope.mp4", "video/mp4", 1, "t", null)
        val error = runCatching { engine.upload(request).toList() }.exceptionOrNull()
        assertTrue(error is CoreError.NotSignedIn)
    }

    @Test fun `upload progress fraction is clamped`() {
        assertEquals(0.5f, UploadEvent.Progress(5, 10).fraction)
        assertEquals(0f, UploadEvent.Progress(5, 0).fraction)
        assertEquals(1f, UploadEvent.Progress(20, 10).fraction)
    }

    @Test fun `edit planner validates clips and overlays`() {
        val planner = TemporaryEditPlanner()
        val ok = EditProject(clips = listOf(Clip("a", 0, 1000), Clip("b", 500, 1500)), overlays = listOf(TextOverlay("بسم الله", 0, 800)))
        assertEquals(EditValidation.Ok, planner.validate(ok))
        assertEquals(2000L, planner.totalDurationMs(ok))

        val bad = EditProject(clips = listOf(Clip("a", 1000, 1000)), overlays = listOf(TextOverlay(" ", 0, 10)))
        val result = planner.validate(bad)
        assertTrue(result is EditValidation.Invalid && result.problems.size == 3)
        assertTrue(planner.validate(EditProject(clips = emptyList())) is EditValidation.Invalid)
    }

    @Test fun `log format renders key value context`() {
        assertEquals("rail failed kind=TRENDING code=500", AunLog.format("rail failed", arrayOf("kind" to "TRENDING", "code" to 500)))
        assertEquals("plain", AunLog.format("plain", emptyArray()))
    }
}
