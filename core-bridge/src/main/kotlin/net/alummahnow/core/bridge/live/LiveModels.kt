package net.alummahnow.core.bridge.live

import java.time.Instant

/**
 * Live streams and premieres for viewers (wiki E17): what is on, what is coming, and the chat beside it. Reading only:
 * the viewer has no account yet, and writing and reminders need one.
 */
enum class LiveStatus { SCHEDULED, LIVE, ENDED, CANCELLED }

enum class LiveKind { LIVE, PREMIERE }

/** The four fixed event tags (shared-types `LIVE_EVENT_TYPES`). */
enum class LiveEventType { JUMUAH, TARAWEEH, CONFERENCE, HALQA }

data class LiveStream(
    val id: String,
    val title: String,
    val status: LiveStatus,
    val description: String? = null,
    val kind: LiveKind = LiveKind.LIVE,
    /** null while event tags are off. */
    val eventType: LiveEventType? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val scheduledStart: Instant? = null,
    val viewers: Long? = null
)

/** `GET /api/live-streams/:id/state`. What an older server leaves out is off. */
data class LiveState(
    val status: LiveStatus,
    val sessionStatus: String? = null,
    val kind: LiveKind = LiveKind.LIVE,
    val playbackUrl: String? = null,
    val vodUrl: String? = null,
    val startedAt: Instant? = null,
    val scheduledStart: Instant? = null,
    /** The video a premiere shows, once it is on or over. */
    val premiereVideoId: String? = null,
    val durationSeconds: Double? = null,
    val eventType: LiveEventType? = null,
    val viewers: Long? = null,
    val chatEnabled: Boolean = false,
    val chatReplay: Boolean = false,
    /** How far the server's clock is ahead of the device's. */
    val clockOffsetMs: Long = 0,
    /** When the server wants this read again; null when it does not say. */
    val pollAfterMs: Long? = null
)

data class LiveChatMessage(
    val id: String,
    val authorName: String,
    val text: String,
    /** Milliseconds from the stream's start. */
    val offsetMs: Long,
    val role: Role? = null
) {
    enum class Role { OWNER, MODERATOR }
}

/** [truncated]: a replay window holds more than one answer. [pollAfterMs]: when the server wants the chat read again. */
data class LiveChatPage(
    val messages: List<LiveChatMessage>,
    val pinned: LiveChatMessage? = null,
    val truncated: Boolean = false,
    val pollAfterMs: Long? = null
)

/** What viewers are offered; everything off when it cannot be read. */
data class LiveFeatures(
    val chat: Boolean = false,
    val premieres: Boolean = false,
    val reminders: Boolean = false,
    val events: Boolean = false
) {
    /** The coming list is Epic 17's: premieres, reminders and event tags. */
    val hasComing: Boolean get() = premieres || reminders || events
}

interface LiveRepository {
    suspend fun features(): LiveFeatures

    suspend fun streams(status: LiveStatus, event: LiveEventType? = null): List<LiveStream>

    suspend fun stream(id: String): LiveStream

    suspend fun state(id: String): LiveState

    /** The newest messages. */
    suspend fun chat(id: String): LiveChatPage

    /** The messages of a recording between two moments, at most 60 seconds apart. */
    suspend fun chatReplay(id: String, fromMs: Long, toMs: Long): LiveChatPage

    /** Counts this screen among those watching, and answers how many there are; null when the server does not know. */
    suspend fun beat(id: String, viewerId: String, leaving: Boolean): Long?
}
