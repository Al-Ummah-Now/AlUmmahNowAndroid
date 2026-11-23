package net.alummahnow.feature.live

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.LiveAuthorRole
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatPage
import net.alummahnow.core.bridge.model.LiveChatSettings
import net.alummahnow.core.bridge.model.LiveChatStatus
import net.alummahnow.core.bridge.model.LiveChatValidationError
import net.alummahnow.core.bridge.model.LiveEventType
import net.alummahnow.core.bridge.model.LiveModerationAction
import net.alummahnow.core.bridge.model.LiveReminderStatus
import net.alummahnow.core.bridge.model.LiveReportReason
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamKind
import net.alummahnow.core.bridge.model.LiveStreamState
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveWatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `loads live stream, beats viewer, validates and posts chat, and moderates`() = runTest(dispatcher) {
        val fake = FakeLiveCatalog()
        val vm = LiveWatchViewModel(fake, "live-1", canModerate = true)
        advanceUntilIdle()

        val initial = vm.state.value as LiveWatchUiState.Content
        assertEquals(LiveStreamStatus.LIVE, initial.stream.status)
        assertEquals(1, initial.visibleChatMessages.size)
        assertTrue(initial.showChatPanel)

        vm.beatViewer()
        advanceUntilIdle()
        assertEquals(125L, (vm.state.value as LiveWatchUiState.Content).stream.viewerCount)

        // Links are blocked by fast filter when linksAllowed = false
        vm.onDraftChanged("Check https://spam.example")
        vm.sendChatMessage()
        assertEquals(
            LiveChatValidationError.LINKS_NOT_ALLOWED,
            (vm.state.value as LiveWatchUiState.Content).chatError
        )

        // Valid chat message posts and appends
        vm.onDraftChanged("BarakAllahu feekum")
        vm.sendChatMessage()
        advanceUntilIdle()
        val afterPost = vm.state.value as LiveWatchUiState.Content
        assertEquals(2, afterPost.visibleChatMessages.size)
        assertEquals("", afterPost.draftMessage)

        // Remove message confirmation flow hides removed message
        val firstMsg = afterPost.visibleChatMessages.first()
        vm.requestRemoveMessage(firstMsg)
        assertEquals(firstMsg.id, (vm.state.value as LiveWatchUiState.Content).pendingRemovalMessage?.id)
        vm.confirmRemoveMessage()
        advanceUntilIdle()
        assertEquals(1, (vm.state.value as LiveWatchUiState.Content).visibleChatMessages.size)
    }

    @Test fun `scheduled premiere loads reminder and toggles reminder state`() = runTest(dispatcher) {
        val fake = FakeLiveCatalog(
            stream = sampleStream(status = LiveStreamStatus.SCHEDULED, kind = LiveStreamKind.PREMIERE)
        )
        val vm = LiveWatchViewModel(fake, "live-1")
        vm.state.test {
            assertEquals(LiveWatchUiState.Loading, awaitItem())
            val loaded = awaitItem() as LiveWatchUiState.Content
            assertFalse(loaded.reminder.reminded)
            assertEquals(10L, loaded.reminder.reminderCount)

            vm.toggleReminder()
            advanceUntilIdle()
            val toggled = expectMostRecentItem() as LiveWatchUiState.Content
            assertTrue(toggled.reminder.reminded)
            assertEquals(11L, toggled.reminder.reminderCount)
        }
    }

    @Test fun `vod recording filters chat replay by playback position and kids content hides chat`() = runTest(dispatcher) {
        val endedFake = FakeLiveCatalog(
            stream = sampleStream(status = LiveStreamStatus.ENDED),
            messages = listOf(
                sampleMessage("m1", offsetMs = 5_000),
                sampleMessage("m2", offsetMs = 30_000)
            )
        )
        val vm = LiveWatchViewModel(endedFake, "live-1")
        advanceUntilIdle()

        assertEquals(0, (vm.state.value as LiveWatchUiState.Content).visibleChatMessages.size)
        vm.updatePlaybackState(positionMs = 10_000, isAtLiveEdge = false)
        assertEquals(listOf("m1"), (vm.state.value as LiveWatchUiState.Content).visibleChatMessages.map { it.id })
        vm.updatePlaybackState(positionMs = 35_000, isAtLiveEdge = false)
        assertEquals(listOf("m1", "m2"), (vm.state.value as LiveWatchUiState.Content).visibleChatMessages.map { it.id })

        // Kids category hides chat completely (Epic 20 safety rule)
        val kidsFake = FakeLiveCatalog(stream = sampleStream(category = "islamic_kids"))
        val kidsVm = LiveWatchViewModel(kidsFake, "live-1")
        advanceUntilIdle()
        assertFalse((kidsVm.state.value as LiveWatchUiState.Content).showChatPanel)
    }
}

private fun sampleStream(
    status: LiveStreamStatus = LiveStreamStatus.LIVE,
    kind: LiveStreamKind = LiveStreamKind.LIVE,
    category: String? = "khutbah"
) = LiveStream(
    id = "live-1",
    channelId = "ch-1",
    channelName = "Al Ummah Now",
    title = LocalizedText("Friday Khutbah", mapOf("ar" to "خطبة الجمعة")),
    status = status,
    streamKind = kind,
    eventType = LiveEventType.JUMMAH,
    viewerCount = 90,
    peakViewerCount = 120,
    playbackUrl = "https://cdn.example/live-1/index.m3u8",
    vodUrl = "https://cdn.example/live-1/vod.m3u8",
    category = category
)

private fun sampleMessage(id: String, offsetMs: Long = 1_000, text: String = "Assalamu alaikum") = LiveChatMessage(
    id = id,
    streamId = "live-1",
    userId = "u-1",
    channelId = "ch-1",
    authorName = "Ahmad",
    authorRole = LiveAuthorRole.VIEWER,
    text = text,
    offsetMs = offsetMs,
    status = LiveChatStatus.PUBLISHED,
    createdAt = "2026-11-23T12:00:00Z"
)

private class FakeLiveCatalog(
    private val stream: LiveStream = sampleStream(),
    private val messages: List<LiveChatMessage> = listOf(sampleMessage("m1"))
) : CatalogRepository {
    private var reminder = LiveReminderStatus(reminded = false, reminderCount = 10)

    override suspend fun homeRails(): List<Rail> = emptyList()
    override suspend fun video(id: String): VideoDetail = error("not used")
    override suspend fun related(video: VideoDetail, limit: Int): List<VideoSummary> = emptyList()
    override suspend fun search(query: String, limit: Int): List<VideoSummary> = emptyList()

    override suspend fun liveStream(id: String): LiveStream = stream

    override suspend fun liveStreamState(id: String): LiveStreamState = LiveStreamState(
        id = stream.id,
        status = stream.status,
        streamKind = stream.streamKind,
        eventType = stream.eventType,
        viewerCount = stream.viewerCount,
        peakViewerCount = stream.peakViewerCount,
        playbackUrl = stream.playbackUrl,
        vodUrl = stream.vodUrl
    )

    override suspend fun sendLiveHeartbeat(id: String, viewerId: String): LiveStreamState = LiveStreamState(
        id = stream.id,
        status = stream.status,
        viewerCount = 125,
        peakViewerCount = 130,
        playbackUrl = stream.playbackUrl
    )

    override suspend fun liveChatMessages(
        streamId: String,
        afterId: String?,
        maxOffsetMs: Long?,
        limit: Int
    ): LiveChatPage = LiveChatPage(
        items = messages,
        settings = LiveChatSettings(chatEnabled = true, slowModeSeconds = 5, linksAllowed = false)
    )

    override suspend fun postLiveChatMessage(streamId: String, text: String, offsetMs: Long?): LiveChatMessage =
        sampleMessage(id = "m-posted", offsetMs = offsetMs ?: 0L, text = text)

    override suspend fun moderateLiveChatMessage(
        streamId: String,
        messageId: String,
        action: LiveModerationAction
    ): LiveChatMessage {
        val target = messages.firstOrNull { it.id == messageId } ?: sampleMessage(messageId)
        return when (action) {
            LiveModerationAction.REMOVE -> target.copy(status = LiveChatStatus.REMOVED)
            LiveModerationAction.PIN -> target.copy(isPinned = true)
            LiveModerationAction.UNPIN -> target.copy(isPinned = false)
            LiveModerationAction.HOLD -> target.copy(status = LiveChatStatus.HELD)
            LiveModerationAction.APPROVE -> target.copy(status = LiveChatStatus.PUBLISHED)
        }
    }

    override suspend fun reportLiveChatMessage(
        streamId: String,
        messageId: String,
        reason: LiveReportReason,
        details: String?
    ) = Unit

    override suspend fun liveReminderStatus(streamId: String): LiveReminderStatus = reminder

    override suspend fun setLiveReminder(streamId: String, remind: Boolean): LiveReminderStatus {
        reminder = LiveReminderStatus(
            reminded = remind,
            reminderCount = if (remind) reminder.reminderCount + 1 else reminder.reminderCount - 1
        )
        return reminder
    }
}
