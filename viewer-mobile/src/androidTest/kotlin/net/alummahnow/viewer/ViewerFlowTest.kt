package net.alummahnow.viewer

import android.content.ComponentName
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.design.components.VIDEO_CARD_TAG
import net.alummahnow.player.AunPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end against the public QA API (release gate only, scripts/qa-deployed-tests.sh): home rails load,
 * a video opens, and the HLS stream reaches READY
 * in the background playback service.
 */
@RunWith(AndroidJUnit4::class)
class ViewerFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeLoadsAndVideoPlays() {
        val trending = compose.activity.getString(net.alummahnow.design.R.string.aun_rail_trending)
        try {
            compose.waitUntil(30_000) { compose.onAllNodesWithText(trending).fetchSemanticsNodes().isNotEmpty() }
        } catch (e: ComposeTimeoutException) {
            // Is it the app or the server: ask again for what Home shows.
            val rails = runCatching { runBlocking { TemporaryCatalogRepository(BuildConfig.API_BASE_URL).homeRails() } }
                .fold({ rails -> rails.joinToString(prefix = "rails: [", postfix = "]") { "${it.kind} ${it.videos.size}" } }, { "$it" })
            throw AssertionError("Home has no Trending rail; the server says: $rails", e)
        }

        // First card of the first rail, found by tag so the test works in Arabic and English.
        val cards = compose.onAllNodesWithTag(VIDEO_CARD_TAG)
        compose.waitUntil(10_000) { cards.fetchSemanticsNodes().isNotEmpty() }
        cards[0].performClick()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = runBlocking {
            withContext(Dispatchers.Main) {
                MediaController.Builder(context, SessionToken(context, ComponentName(context, AunPlaybackService::class.java)))
                    .buildAsync()
                    .await()
            }
        }
        // Poll through the compose rule: it only advances frames (and so runs the watch page's effects)
        // while the test synchronises with it. The controller itself must be read on the main thread.
        compose.waitUntil(45_000) { compose.runOnUiThread { controller.playbackState } == Player.STATE_READY }
        compose.runOnUiThread {
            assertEquals(Player.STATE_READY, controller.playbackState)
            controller.release()
        }
    }
}
