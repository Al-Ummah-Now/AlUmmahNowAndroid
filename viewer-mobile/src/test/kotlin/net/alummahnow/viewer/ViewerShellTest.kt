package net.alummahnow.viewer

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveChatPage
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveFeatures
import net.alummahnow.core.bridge.live.LiveRepository
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.core.bridge.safety.ViewerBlockStore
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** The viewer's own navigation over fakes: what a block does on Home, Watch, Up next, the live list and a live page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class ViewerShellTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val mine = sampleSafetyVideo("v-1", "ch-1", "Example Channel", "Blocked video")
    private val other = sampleSafetyVideo("v-2", "ch-2", "Other Channel", "Kept video")
    private val catalog = FakeCatalog(listOf(mine, other))
    private val live = FakeLive()
    private val opened = mutableListOf<String>()
    private var browser = true
    private val links = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private lateinit var player: RecordingPlayer
    private lateinit var container: ViewerContainer

    @Before fun setUp() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h2000dp-port")
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = RecordingPlayer(ExoPlayer.Builder(context).build())
        container = object : ViewerContainer {
            override val catalog = this@ViewerShellTest.catalog
            override val live = this@ViewerShellTest.live
            override val displayPrefs = DisplayPrefsStore(context)
            override val blockStore = ViewerBlockStore()
        }
    }

    @After fun tearDown() = player.release()

    private fun show() = compose.setContent {
        AunTheme(mode = ThemeMode.DARK) {
            ViewerNavHost(
                container,
                inPip = false,
                links = links,
                onPipEligible = {},
                onFullscreen = {},
                openInBrowser = {
                    opened += it
                    browser
                },
                rememberPlayer = { player }
            )
        }
    }

    private fun go(route: String) {
        compose.waitForIdle()
        links.tryEmit(route)
    }

    private fun waitForTag(tag: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun blockFromMenu(menu: String, item: String) {
        waitForTag(menu)
        compose.onNodeWithTag(menu).performClick()
        waitForTag(item)
        compose.onNodeWithTag(item).performClick()
        waitForTag("confirm-ok")
        compose.onNodeWithTag("confirm-ok").performClick()
        compose.waitForIdle()
    }

    @Test
    fun blockingOnWatchStopsPlaybackAndHidesTheChannelEverywhere() {
        show()
        waitForText("Blocked video")
        go(Routes.watch("v-1"))
        compose.waitUntil(TIMEOUT_MS) { "setMediaItem v-1" in player.calls }
        blockFromMenu("watch-more-actions", "watch-block-channel-item")
        assertTrue("stop" in player.calls && "clearMediaItems" in player.calls)
        assertEquals(null, player.currentMediaItem)
        // Home again: its video is gone, the other channel's stays.
        waitForText("Kept video")
        compose.onAllNodesWithText("Blocked video").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        // Up next of another video does not offer it either.
        go(Routes.watch("v-2"))
        compose.waitUntil(TIMEOUT_MS) { "setMediaItem v-2" in player.calls }
        compose.waitForIdle()
        compose.onAllNodesWithText("Blocked video").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun aLinkToABlockedChannelNeverLoadsItsMediaUntilUnblocked() {
        container.blockStore.blockChannel("ch-1", "Example Channel")
        show()
        go(Routes.watch("v-1"))
        waitForTag("watch-channel-blocked")
        compose.waitForIdle()
        assertFalse(player.calls.any { it.startsWith("setMediaItem") || it == "prepare" })
        compose.onNodeWithTag("watch-unblock-channel").performClick()
        compose.waitUntil(TIMEOUT_MS) { "setMediaItem v-1" in player.calls && "prepare" in player.calls }
        assertTrue(container.blockStore.entries.value.isEmpty())
    }

    /** The shared player can still be on it from before (background playback, a notification). */
    @Test
    fun openingABlockedPageEndsWhatIsStillPlaying() {
        container.blockStore.blockChannel("ch-1", "Example Channel")
        show()
        for (route in listOf(Routes.watch("v-1") to "watch-channel-blocked", Routes.live("s-1") to "live-channel-blocked")) {
            compose.runOnUiThread { player.setMediaItem(MediaItem.Builder().setMediaId("v-1").setUri("https://x.test/v-1.m3u8").build()) }
            go(route.first)
            waitForTag(route.second)
            compose.waitForIdle()
            assertEquals(route.first, null, player.currentMediaItem)
        }
    }

    @Test
    fun aBlockedLivePageIsNotCountedPolledOrPlayedEvenWhenItsAddressChanges() {
        container.blockStore.blockChannel("ch-1", "Example Channel")
        show()
        go(Routes.live("s-1"))
        waitForTag("live-channel-blocked")
        compose.mainClock.advanceTimeBy(90_000)
        assertEquals(1, live.stateReads) // the page's own read, which says whose stream it is
        assertEquals(0, live.chatReads)
        assertTrue(live.beats.isEmpty())
        live.address = "https://x.test/new.m3u8"
        compose.mainClock.advanceTimeBy(90_000)
        assertFalse(player.calls.any { it.startsWith("setMediaItem") || it == "prepare" })

        compose.onNodeWithTag("live-unblock-channel").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.mainClock.advanceTimeBy(1_000)
            live.beats.isNotEmpty() && live.chatReads > 0 && player.calls.any { it.startsWith("setMediaItem") }
        }
    }

    @Test
    fun blockingOnALivePageStopsItAndHidesItFromTheLists() {
        show()
        go(Routes.live("s-1"))
        compose.waitUntil(TIMEOUT_MS) { player.calls.any { it.startsWith("setMediaItem") } }
        blockFromMenu("live-more-actions", "live-block-channel-item")
        assertEquals(null, player.currentMediaItem)
        val beats = live.beats.count { !it }
        compose.mainClock.advanceTimeBy(90_000)
        assertEquals(beats, live.beats.count { !it })
        go(Routes.LIVE_LIST)
        waitForText("Live s-2")
        compose.onAllNodesWithText("Live s-1").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    @Test
    fun aBlockedChatUserAndTheirPinnedMessageDisappear() {
        show()
        go(Routes.live("s-1"))
        blockFromMenu("live-chat-more-m-bad", "live-block-user-item")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("pinned by them").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithTag("live-chat-message-m-bad").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
        compose.onNodeWithTag("live-chat-message-m-ok").assertIsDisplayed()
    }

    @Test
    fun watchMenuCopiesShortLink() {
        show()
        go(Routes.watch("v-1"))
        waitForTag("watch-more-actions")
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithTag("watch-copy-link-item").performClick()
        compose.runOnIdle {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            assertEquals("https://qa.alummahnow.link/v/v-1", clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    @Test
    fun privateWatchMenuHasNoCopyLink() {
        catalog.privateVideo = true
        show()
        go(Routes.watch("v-1"))
        waitForTag("watch-more-actions")
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithTag("watch-copy-link-item").assertDoesNotExist()
    }

    @Test
    fun reportsOpenThisBuildsHostWithTheTarget() {
        show()
        go(Routes.watch("v-1"))
        waitForTag("watch-more-actions")
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithText("Report video").performClick()
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithTag("watch-report-channel-item").performClick()
        val base = "https://${BuildConfig.WEB_HOST}/en/report"
        assertEquals(listOf("$base?type=video&id=v-1", "$base?type=channel&id=ch-1"), opened)
        assertEquals("videos.qa.alummahnow.net", BuildConfig.WEB_HOST) // unit tests run the QA flavour
    }

    @Test
    fun withNoBrowserAReportSaysSo() {
        browser = false
        show()
        go(Routes.watch("v-1"))
        waitForTag("watch-more-actions")
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithText("Report video").performClick()
        compose.waitForIdle()
        assertEquals("No browser is available to open this page.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun whenEverythingIsBlockedHomeSaysWhyAndTheListUnblocks() {
        live.streams = emptyList()
        container.blockStore.blockChannel("ch-1", "Example Channel")
        container.blockStore.blockChannel("ch-2", "Other Channel")
        show()
        waitForText("Everything here is from channels you've blocked")
        compose.onNodeWithContentDescription("Display settings").performClick()
        waitForTag("settings-blocked-list")
        compose.onNodeWithTag("settings-blocked-list").performScrollTo().performClick()
        waitForTag("unblock-channel:id:ch-2")
        compose.onNodeWithTag("unblock-channel:id:ch-2").performClick()
        compose.onNodeWithTag("blocked-list-close").performClick()
        waitForText("Kept video")
    }

    private class FakeCatalog(private val videos: List<VideoSummary>) : CatalogRepository {
        var privateVideo = false

        override suspend fun homeRails() = listOf(Rail(RailKind.RECENT, videos))
        override suspend fun video(id: String): VideoDetail {
            val v = videos.firstOrNull { it.id == id } ?: throw CoreError.NotFound(id)
            return sampleSafetyDetail(playUrl = "https://x.test/$id.m3u8").copy(summary = v, isPrivate = privateVideo)
        }
        override suspend fun related(video: VideoDetail, limit: Int) = videos.filterNot { it.id == video.id }
        override suspend fun search(query: String, limit: Int) = emptyList<VideoSummary>()
    }

    private class FakeLive : LiveRepository {
        var streams = listOf(
            sampleSafetyStream("s-1", "ch-1", "Example Channel"),
            sampleSafetyStream("s-2", "ch-2", "Other Channel")
        )
        var address = "https://x.test/s.m3u8"
        var stateReads = 0
        var chatReads = 0
        val beats = mutableListOf<Boolean>()
        override suspend fun features() = LiveFeatures(chat = true, premieres = false, events = false)
        override suspend fun streams(status: LiveStatus, event: LiveEventType?) =
            if (status == LiveStatus.LIVE) streams else emptyList()
        override suspend fun stream(id: String): LiveStream = streams.first { it.id == id }
        override suspend fun state(id: String): LiveState {
            stateReads++
            return LiveState(LiveStatus.LIVE, playbackUrl = address, chatEnabled = true)
        }
        override suspend fun chat(id: String): LiveChatPage {
            chatReads++
            return LiveChatPage(
                listOf(
                    LiveChatMessage("m-bad", "Guest", "spam", 1_000, userId = "u-bad"),
                    LiveChatMessage("m-ok", "Guest", "salaam", 2_000, userId = "u-ok")
                ),
                LiveChatMessage("m-pin", "Guest", "pinned by them", 500, userId = "u-bad")
            )
        }
        override suspend fun chatReplay(id: String, fromMs: Long, toMs: Long) = LiveChatPage(emptyList())
        override suspend fun beat(id: String, viewerId: String, leaving: Boolean): Long? {
            beats += leaving
            return 5
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

/** A real player that never touches the network: it records what it was asked and keeps its playlist. */
@androidx.annotation.OptIn(UnstableApi::class)
private class RecordingPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
    val calls = mutableListOf<String>()
    override fun setMediaItem(mediaItem: MediaItem) {
        calls += "setMediaItem ${mediaItem.mediaId}"
        super.setMediaItem(mediaItem)
    }
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
        calls += "setMediaItem ${mediaItem.mediaId}"
        super.setMediaItem(mediaItem, startPositionMs)
    }
    override fun prepare() {
        calls += "prepare"
    }
    override fun stop() {
        calls += "stop"
        super.stop()
    }
    override fun clearMediaItems() {
        calls += "clearMediaItems"
        super.clearMediaItems()
    }
}
