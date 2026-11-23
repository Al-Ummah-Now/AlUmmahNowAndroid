package net.alummahnow.core.bridge.catalog

import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatPage
import net.alummahnow.core.bridge.model.LiveEventType
import net.alummahnow.core.bridge.model.LiveModerationAction
import net.alummahnow.core.bridge.model.LiveReminderStatus
import net.alummahnow.core.bridge.model.LiveReportReason
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamState
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

/**
 * Public catalogue reads. Backed by `aun-api` in the Rust core once the UniFFI AAR ships; until then by
 * [TemporaryCatalogRepository] (OkHttp against the public API).
 */
interface CatalogRepository {
    /** Home rails in display order. Empty rails are dropped. */
    suspend fun homeRails(): List<Rail>

    suspend fun video(id: String): VideoDetail

    /** Videos to show next to [video] (same category first, then trending), excluding [video] itself. */
    suspend fun related(video: VideoDetail, limit: Int = 12): List<VideoSummary>

    suspend fun search(query: String, limit: Int = 20): List<VideoSummary>

    suspend fun liveStreams(
        status: LiveStreamStatus? = null,
        eventType: LiveEventType? = null,
        limit: Int = 20
    ): List<LiveStream> = emptyList()

    suspend fun liveStream(id: String): LiveStream = throw CoreError.NotFound("live $id")

    suspend fun liveStreamState(id: String): LiveStreamState = throw CoreError.NotFound("live state $id")

    suspend fun sendLiveHeartbeat(id: String, viewerId: String): LiveStreamState = liveStreamState(id)

    suspend fun liveChatMessages(
        streamId: String,
        afterId: String? = null,
        maxOffsetMs: Long? = null,
        limit: Int = 50
    ): LiveChatPage = LiveChatPage(emptyList())

    suspend fun postLiveChatMessage(streamId: String, text: String, offsetMs: Long? = null): LiveChatMessage =
        throw CoreError.NotFound("chat $streamId")

    suspend fun moderateLiveChatMessage(
        streamId: String,
        messageId: String,
        action: LiveModerationAction
    ): LiveChatMessage = throw CoreError.NotFound("chat $messageId")

    suspend fun reportLiveChatMessage(
        streamId: String,
        messageId: String,
        reason: LiveReportReason,
        details: String? = null
    ) = Unit

    suspend fun liveReminderStatus(streamId: String): LiveReminderStatus = LiveReminderStatus(false, 0)

    suspend fun setLiveReminder(streamId: String, remind: Boolean): LiveReminderStatus =
        LiveReminderStatus(remind, if (remind) 1 else 0)
}
