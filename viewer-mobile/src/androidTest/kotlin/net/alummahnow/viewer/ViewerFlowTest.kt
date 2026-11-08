package net.alummahnow.viewer

import android.content.ComponentName
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.alummahnow.player.AunPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end against the public QA API: home rails load, a video opens, and the HLS stream reaches READY
 * in the background playback service.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeLoadsAndVideoPlays() {
        val trending = compose.activity.getString(net.alummahnow.design.R.string.aun_rail_trending)
        compose.waitUntil(30_000) { compose.onAllNodesWithText(trending).fetchSemanticsNodes().isNotEmpty() }

        // First card of the first rail: any node with a view count label below the Trending heading.
        val views = compose.onAllNodes(hasText("view", substring = true, ignoreCase = true))
        compose.waitUntil(10_000) { views.fetchSemanticsNodes().isNotEmpty() }
        views[0].performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            val controller = withContext(Dispatchers.Main) {
                MediaController.Builder(
                    context,
                    SessionToken(context, ComponentName(context, AunPlaybackService::class.java))
                ).buildAsync().await()
            }
            withTimeout(45_000) {
                while (withContext(Dispatchers.Main) { controller.playbackState != Player.STATE_READY }) delay(250)
            }
            withContext(Dispatchers.Main) {
                assertEquals(Player.STATE_READY, controller.playbackState)
                controller.release()
            }
        }
    }
}
