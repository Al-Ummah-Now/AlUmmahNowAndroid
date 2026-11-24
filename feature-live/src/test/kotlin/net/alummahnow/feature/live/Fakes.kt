package net.alummahnow.feature.live

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

internal class FakeLive(var current: LiveState = LiveState(LiveStatus.SCHEDULED)) : LiveRepository {
    var features = LiveFeatures(chat = true, premieres = true, events = true)
    val lists = mutableMapOf<LiveStatus, List<LiveStream>>()
    val windows = mutableMapOf<Long, LiveChatPage>()
    var stateReads = 0
    var chatReads = 0
    val beats = mutableListOf<Boolean>()
    var stateGate: CompletableDeferred<Unit>? = null
    var chatGate: CompletableDeferred<Unit>? = null

    /** What passes while an answer is on its way. */
    var answering: () -> Unit = {}
    var askedEvent: LiveEventType? = null
    var fails = false
    var chatFails = false
    var chatPollAfter: Long? = null
    var pinned: LiveChatMessage? = null

    /** Every read is refused with this `Retry-After`. */
    var retryAfter: Long? = null

    override suspend fun features() = features

    /** Held back until it is completed, for one event. */
    var listGate: Pair<LiveEventType, CompletableDeferred<Unit>>? = null

    override suspend fun streams(status: LiveStatus, event: LiveEventType?): List<LiveStream> {
        listGate?.let { (held, gate) -> if (event == held) gate.await() }
        if (fails) throw CoreError.Http(503)
        askedEvent = event
        return lists[status].orEmpty()
    }

    override suspend fun stream(id: String) = LiveStream(id, "T", current.status)

    override suspend fun state(id: String): LiveState {
        stateReads++
        answering()
        stateGate?.await()
        refuse()
        return current
    }

    override suspend fun chat(id: String): LiveChatPage {
        chatReads++
        answering()
        chatGate?.await()
        refuse()
        if (chatFails) throw CoreError.Http(503)
        return LiveChatPage(listOf(LiveChatMessage("m", "A", "x", 0)), pinned, pollAfterMs = chatPollAfter)
    }

    private fun refuse() {
        retryAfter?.let { throw CoreError.Http(429, it) }
    }

    override suspend fun chatReplay(id: String, fromMs: Long, toMs: Long) = windows[fromMs] ?: LiveChatPage(emptyList())

    override suspend fun beat(id: String, viewerId: String, leaving: Boolean): Long? {
        beats += leaving
        if (!leaving) answering()
        refuse()
        return 5
    }
}

internal class FakeCatalog(private val videos: Set<String> = setOf("v1"), private val error: CoreError? = null) : CatalogRepository {
    override suspend fun homeRails(): List<Rail> = emptyList()

    override suspend fun video(id: String): VideoDetail {
        error?.let { throw it }
        if (id !in videos) throw CoreError.NotFound(id)
        return VideoDetail(
            summary = VideoSummary(id, LocalizedText.of("V"), null, 600, 0, null, null, null, null),
            description = LocalizedText.of(""),
            playUrl = "https://x.test/v.m3u8",
            availableResolutions = emptyList(),
            chapters = emptyList(),
            subtitles = emptyList(),
            likes = 0,
            tags = emptyList(),
            isLive = false,
            isReady = true
        )
    }

    override suspend fun related(video: VideoDetail, limit: Int): List<VideoSummary> = emptyList()

    override suspend fun search(query: String, limit: Int): List<VideoSummary> = emptyList()
}

internal class TestClock(var at: Instant = Instant.parse("2026-11-24T18:00:00Z")) {
    fun advance(seconds: Long) {
        at = at.plusSeconds(seconds)
    }
}
