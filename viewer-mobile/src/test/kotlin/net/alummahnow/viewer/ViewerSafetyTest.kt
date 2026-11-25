package net.alummahnow.viewer

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.Instant
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.core.bridge.safety.BlockedEntry
import net.alummahnow.core.bridge.safety.BlockedKind
import net.alummahnow.core.bridge.safety.ViewerBlockStore
import net.alummahnow.core.bridge.safety.ViewerReportUrls
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeUiState
import net.alummahnow.feature.live.LiveMode
import net.alummahnow.feature.live.LiveSafetyActions
import net.alummahnow.feature.live.LiveWatchContent
import net.alummahnow.feature.live.LiveWatchScreen
import net.alummahnow.feature.live.LiveWatchUiState
import net.alummahnow.feature.watch.WatchSafetyActions
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ViewerSafetyTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun reportUrlsUsePublicReportRouteHostLocaleAndEncoding() {
        val prod = "videos.alummahnow.net"
        val qa = "videos.qa.alummahnow.net"
        assertEquals(
            "https://videos.alummahnow.net/en/report?type=video&id=v%201",
            ViewerReportUrls.videoUrl("v 1", "en-US", prod)
        )
        assertEquals(
            "https://videos.qa.alummahnow.net/ar/report?type=video&id=v1",
            ViewerReportUrls.videoUrl("v1", "ar-SA", qa)
        )
        assertEquals(
            "https://videos.alummahnow.net/en/report?type=channel&id=ch-9",
            ViewerReportUrls.channelUrl("ch-9", "en", prod)
        )
        assertEquals(
            "https://videos.qa.alummahnow.net/ar/report?type=livestream&id=s-2",
            ViewerReportUrls.liveStreamUrl("s-2", "ar", qa)
        )
        assertEquals(
            "https://videos.alummahnow.net/en/report?type=live_chat&stream=s-2&message=m-5",
            ViewerReportUrls.liveChatUrl("s-2", "m-5", "en", prod)
        )
        // No default host, and no report without its target.
        listOf<() -> String>(
            { ViewerReportUrls.videoUrl("v1", "en", " ") },
            { ViewerReportUrls.channelUrl("ch-9", "en", "") },
            { ViewerReportUrls.channelUrl(" ", "en", qa) },
            { ViewerReportUrls.videoUrl("", "en", qa) },
            { ViewerReportUrls.liveStreamUrl("", "ar", qa) },
            { ViewerReportUrls.liveChatUrl("s-2", "", "en", qa) }
        ).forEachIndexed { i, url -> assertTrue("$i", runCatching(url).isFailure) }
    }

    @Test
    fun blockStoreUsesStableIdsAndAvoidsDisplayNameCollisions() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("test_viewer_blocks", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val store = ViewerBlockStore(prefs)
        store.blockChannel("ch-1", "Daily Dars")
        store.blockChatUser("u-bad", "Ahmad")

        val reloaded = ViewerBlockStore(prefs)
        assertTrue(reloaded.isChannelBlocked("ch-1", "Renamed"))
        assertFalse(reloaded.isChannelBlocked("ch-2", "Daily Dars"))
        assertTrue(reloaded.isChannelBlocked(null, "daily dars"))
        assertTrue(reloaded.isChatUserBlocked("u-bad", "Renamed"))
        assertFalse(reloaded.isChatUserBlocked("u-good", "Ahmad"))

        val keptVideo = sampleVideo("v-keep", "ch-2", "Daily Dars")
        val hiddenVideo = sampleVideo("v-hide", "ch-1", "Daily Dars")
        val rails = listOf(
            Rail(RailKind.RECENT, listOf(hiddenVideo, keptVideo)),
            Rail(RailKind.POPULAR, listOf(hiddenVideo))
        )
        val filteredRails = reloaded.filterRails(rails)
        assertEquals(1, filteredRails.size)
        assertEquals(listOf("v-keep"), filteredRails.first().videos.map { it.id })

        val streams = listOf(
            sampleStream("s-1", "ch-1", "Daily Dars"),
            sampleStream("s-2", "ch-2", "Daily Dars")
        )
        assertEquals(listOf("s-2"), reloaded.filterLiveStreams(streams).map { it.id })

        val messages = listOf(
            LiveChatMessage("m-1", "Ahmad", "bad", 1_000L, null, userId = "u-bad"),
            LiveChatMessage("m-2", "Ahmad", "salaam", 2_000L, null, userId = "u-good")
        )
        assertEquals(listOf("m-2"), reloaded.filterMessages(messages).map { it.id })

        reloaded.unblock(ViewerBlockStore.channelKey("ch-1", "Daily Dars"))
        assertFalse(reloaded.isChannelBlocked("ch-1", "Daily Dars"))
        assertEquals(1, reloaded.entries.value.size)
    }

    @Test
    fun aChatUserIsBlockedOnlyById() {
        val store = ViewerBlockStore()
        store.blockChatUser(null, "Ahmad")
        store.blockChatUser(" ", "Ahmad")
        assertTrue(store.entries.value.isEmpty())
        store.blockChatUser("u-1", "Ahmad")
        assertFalse(store.isChatUserBlocked("u-2", "Ahmad"))
        assertTrue(store.isChatUserBlocked("u-1", "Renamed"))
    }

    @Test
    fun aMessageWithoutAUserIdCanBeReportedButNotBlocked() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val content = sampleLiveContent(messages = listOf(LiveChatMessage("m-9", "Guest", "salaam", 1_000L)))
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                LiveWatchScreen(
                    state = LiveWatchUiState.Content(content),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onReturnToLive = {},
                    onRetry = {},
                    safety = LiveSafetyActions({}, {}, {}, { _, _ -> }, {})
                )
            }
        }
        compose.onNodeWithTag("live-chat-more-m-9").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Report message").assertIsDisplayed()
        compose.onNodeWithTag("live-block-user-item").assertDoesNotExist()
    }

    @Test
    fun unblockRemovesTheEntryHoweverItWasStored() {
        val store = ViewerBlockStore()
        store.blockChannel(null, "Daily Dars")
        assertTrue(store.isChannelBlocked("ch-1", "Daily Dars"))
        store.unblockChannel("ch-1", "Daily Dars")
        assertFalse(store.isChannelBlocked("ch-1", "Daily Dars"))
        store.blockChannel("ch-1", null)
        assertTrue(store.isChannelBlocked("ch-1", null))
        store.unblockChannel("ch-1", "Any name")
        assertTrue(store.entries.value.isEmpty())
        store.blockChannel(null, " ")
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun watchOverflowMenuOpensBlockConfirmDialogAndInvokesCallback() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var blocked: VideoDetail? = null
        val detail = sampleDetail()
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                WatchScreen(
                    state = WatchUiState.Content(detail, emptyList()),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onVideoClick = {},
                    onRetry = {},
                    safety = WatchSafetyActions(
                        onReportVideo = {},
                        onReportChannel = {},
                        onBlockChannel = { blocked = it }
                    )
                )
            }
        }
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.onNodeWithTag("watch-block-channel-item").performClick()
        compose.onNodeWithTag("confirm-dialog").assertIsDisplayed()
        compose.onNodeWithTag("confirm-ok").performClick()
        assertEquals("v-1", blocked?.id)
    }

    @Test
    fun watchBlockedStateShowsUnblockButton() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var unblocked = 0
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                WatchScreen(
                    state = WatchUiState.Content(sampleDetail(), emptyList()),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onVideoClick = {},
                    onRetry = {},
                    isChannelBlocked = true,
                    onUnblockChannel = { unblocked++ }
                )
            }
        }
        compose.onNodeWithTag("watch-channel-blocked").assertIsDisplayed()
        compose.onNodeWithTag("watch-unblock-channel").performClick()
        assertEquals(1, unblocked)
    }

    @Test
    fun blockedListDialogShowsEntriesAndUnblocks() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h891dp-port")
        var unblockedId: String? = null
        val entries = listOf(
            BlockedEntry("channel:id:ch-1", BlockedKind.CHANNEL, "Example Channel"),
            BlockedEntry("user:id:u-1", BlockedKind.CHAT_USER, "Spammer")
        )
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                BlockedListDialog(
                    entries = entries,
                    onUnblock = { unblockedId = it },
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithTag("blocked-list-dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("Unblock \u2066Example Channel\u2069").assertIsDisplayed()
        compose.onNodeWithTag("unblock-channel:id:ch-1").performClick()
        assertEquals("channel:id:ch-1", unblockedId)
    }

    @Test
    fun watchAndLiveHideReportChannelWhenChannelIdIsMissing() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val noIdDetail = sampleDetail(channelId = null, channelName = "Name Only Channel")
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                WatchScreen(
                    state = WatchUiState.Content(noIdDetail, emptyList()),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onVideoClick = {},
                    onRetry = {},
                    safety = WatchSafetyActions(onReportVideo = {}, onReportChannel = {}, onBlockChannel = {})
                )
            }
        }
        compose.onNodeWithTag("watch-more-actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("watch-report-channel-item").assertDoesNotExist()
        compose.onNodeWithTag("watch-block-channel-item").assertIsDisplayed()
    }

    @Test
    fun liveHidesReportChannelWhenChannelIdIsMissing() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val noIdContent = sampleLiveContent(channelId = null, channelName = "Name Only Live")
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                LiveWatchScreen(
                    state = LiveWatchUiState.Content(noIdContent),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onReturnToLive = {},
                    onRetry = {},
                    safety = LiveSafetyActions(
                        onReportStream = {},
                        onReportChannel = {},
                        onBlockChannel = {},
                        onReportMessage = { _, _ -> },
                        onBlockUser = {}
                    )
                )
            }
        }
        compose.onNodeWithTag("live-more-actions").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("live-report-channel-item").assertDoesNotExist()
        compose.onNodeWithTag("live-block-channel-item").assertIsDisplayed()
    }

    @Test
    fun liveStreamAndChatSafetyActionsTriggerCallbacks() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var reportedStream: String? = null
        var blockedChatUser: String? = null
        val content = sampleLiveContent(
            messages = listOf(LiveChatMessage("m-9", "Spammer", "spam", 1_000L, null, userId = "u-9"))
        )
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                LiveWatchScreen(
                    state = LiveWatchUiState.Content(content),
                    player = null,
                    isFullscreen = false,
                    isInPictureInPicture = false,
                    onToggleFullscreen = {},
                    onReturnToLive = {},
                    onRetry = {},
                    safety = LiveSafetyActions(
                        onReportStream = { reportedStream = it.id },
                        onReportChannel = {},
                        onBlockChannel = {},
                        onReportMessage = { _, _ -> },
                        onBlockUser = { blockedChatUser = it.userId }
                    )
                )
            }
        }
        compose.onNodeWithTag("live-more-actions").performClick()
        compose.onNodeWithText("Report live stream").performClick()
        assertEquals("s-1", reportedStream)

        compose.onNodeWithTag("live-chat-more-m-9").performClick()
        compose.onNodeWithTag("live-block-user-item").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        assertEquals("u-9", blockedChatUser)
    }

    @Test
    fun homeScreenShowsEmptyMessageWhenAllContentFilteredOut() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        compose.setContent {
            AunTheme(mode = ThemeMode.DARK) {
                HomeScreen(
                    state = HomeUiState.Content(rails = emptyList()),
                    onVideoClick = {},
                    onRetry = {},
                    onOpenSettings = {},
                    hasLive = false
                )
            }
        }
        compose.onNodeWithText("No videos to show right now.").assertIsDisplayed()
    }

    private fun sampleDetail(
        channelName: String = "Example Channel",
        channelId: String? = "ch-1",
        title: String = "Title v-1",
        playUrl: String? = null
    ) = sampleSafetyDetail(channelName, channelId, title, playUrl)

    private fun sampleVideo(id: String, channelId: String?, channelName: String, title: String = "Title $id") =
        sampleSafetyVideo(id, channelId, channelName, title)

    private fun sampleStream(id: String, channelId: String?, channelName: String) =
        sampleSafetyStream(id, channelId, channelName)

    private fun sampleLiveContent(
        channelName: String = "Example Channel",
        channelId: String? = "ch-1",
        messages: List<LiveChatMessage> = emptyList()
    ) = sampleSafetyLiveContent(channelName, channelId, messages)
}

internal fun sampleSafetyDetail(
    channelName: String = "Example Channel",
    channelId: String? = "ch-1",
    title: String = "Title v-1",
    playUrl: String? = null
) = VideoDetail(
    summary = sampleSafetyVideo("v-1", channelId, channelName, title),
    description = LocalizedText.of("Description"),
    playUrl = playUrl,
    availableResolutions = emptyList(),
    chapters = emptyList(),
    subtitles = emptyList(),
    likes = 0,
    tags = emptyList(),
    isLive = false,
    isReady = true
)

internal fun sampleSafetyVideo(id: String, channelId: String?, channelName: String, title: String = "Title $id") = VideoSummary(
    id = id,
    title = LocalizedText.of(title),
    thumbnailUrl = null,
    durationSeconds = 120L,
    views = 10L,
    channelId = channelId,
    channelName = channelName,
    publishedAt = null,
    category = null
)

internal fun sampleSafetyStream(id: String, channelId: String?, channelName: String) = LiveStream(
    id = id,
    title = "Live $id",
    description = null,
    channelId = channelId,
    channelName = channelName,
    thumbnailUrl = null,
    kind = LiveKind.LIVE,
    status = LiveStatus.LIVE,
    eventType = null,
    scheduledStart = Instant.EPOCH,
    viewers = 5L
)

internal fun sampleSafetyLiveContent(
    channelName: String = "Example Channel",
    channelId: String? = "ch-1",
    messages: List<LiveChatMessage> = emptyList()
) = LiveWatchContent(
    stream = sampleSafetyStream("s-1", channelId, channelName),
    live = LiveState(
        status = LiveStatus.LIVE,
        chatEnabled = messages.isNotEmpty()
    ),
    mode = LiveMode.LIVE,
    messages = messages,
    isBehind = false
)
