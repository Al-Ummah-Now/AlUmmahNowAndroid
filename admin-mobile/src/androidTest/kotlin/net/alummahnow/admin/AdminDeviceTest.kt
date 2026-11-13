package net.alummahnow.admin

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import net.alummahnow.admin.media.PrivateMedia
import net.alummahnow.admin.security.SecureWindows
import net.alummahnow.admin.ui.AdminApp
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** On a real device: FLAG_SECURE, the fixture review flow, and no media on disk after the player streams. */
@RunWith(AndroidJUnit4::class)
class AdminDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mainWindowIsSecure() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(SecureWindows.isSecure(it.window)) }
        }
    }

    @Test fun approveAndUndoAgainstFixtures() {
        val services = FakeServices(context)
        compose.setContent {
            AdminApp(services, emptyFlow(), MutableStateFlow(null), MutableSharedFlow(), {
                true
            }, { true }, { services.lock.unlocked() }, {})
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("row-fx-risk").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("row-fx-risk").performClick()
        compose.waitUntil(10_000) { services.fixture.claims.contains("fx-risk") }
        compose.onNodeWithTag("approve").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("queue-list").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("row-fx-risk").fetchSemanticsNodes().isEmpty() }
    }

    @OptIn(UnstableApi::class)
    @Test
    fun streamedMediaNeverReachesDisk() {
        val marker = "AUN-DEVICE-MEDIA-MARKER".toByteArray()
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(marker).write(ByteArray(256 * 1024)).write(marker))
        )
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val url = server.url("/v.mp4").toString() // resolves the host: not on the main thread
            instrumentation.runOnMainSync {
                val player = PrivateMedia.newPlayer(context)
                player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                player.prepare()
                player.play()
                Thread {
                    Thread.sleep(PLAY_MS)
                    instrumentation.runOnMainSync { player.release() }
                }.start()
            }
            assertNotNull("the player fetched the stream", server.takeRequest(10, TimeUnit.SECONDS))
            Thread.sleep(PLAY_MS + 1_000)
            val roots =
                listOfNotNull(
                    context.cacheDir,
                    context.filesDir,
                    context.codeCacheDir,
                    context.noBackupFilesDir,
                    context.externalCacheDir,
                    File(context.applicationInfo.dataDir)
                )
            val hits = roots.flatMap { it.walkTopDown().filter(File::isFile).toList() }.distinct().filter { f ->
                f.length() < MAX_SCAN &&
                    f.readBytes().let { b -> (0..b.size - marker.size).any { i -> marker.indices.all { b[i + it] == marker[it] } } }
            }
            assertTrue("media on disk: $hits", hits.isEmpty())
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        const val PLAY_MS = 3_000L
        const val MAX_SCAN = 8L * 1024 * 1024
    }
}
