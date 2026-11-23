package net.alummahnow.core.bridge.model

enum class LiveStreamStatus(val apiValue: String) {
    SCHEDULED("scheduled"),
    LIVE("live"),
    ENDED("ended");

    companion object {
        fun from(value: String?): LiveStreamStatus = entries.firstOrNull { it.apiValue == value } ?: SCHEDULED
    }
}

enum class LiveStreamKind(val apiValue: String) {
    LIVE("live"),
    PREMIERE("premiere");

    companion object {
        fun from(value: String?): LiveStreamKind = entries.firstOrNull { it.apiValue == value } ?: LIVE
    }
}

enum class LiveEventType(val apiValue: String) {
    NONE("none"),
    JUMMAH("jummah"),
    TARAWEH("taraweh"),
    EID("eid"),
    HALQA("halqa");

    companion object {
        fun from(value: String?): LiveEventType = entries.firstOrNull { it.apiValue == value } ?: NONE
    }
}

enum class LiveAuthorRole(val apiValue: String) {
    VIEWER("viewer"),
    MODERATOR("moderator"),
    OWNER("owner");

    companion object {
        fun from(value: String?): LiveAuthorRole = entries.firstOrNull { it.apiValue == value } ?: VIEWER
    }
}

enum class LiveChatStatus(val apiValue: String) {
    PUBLISHED("published"),
    HELD("held"),
    REMOVED("removed");

    companion object {
        fun from(value: String?): LiveChatStatus = entries.firstOrNull { it.apiValue == value } ?: PUBLISHED
    }
}

enum class LiveModerationAction(val apiValue: String) {
    HOLD("hold"),
    APPROVE("approve"),
    REMOVE("remove"),
    PIN("pin"),
    UNPIN("unpin")
}

enum class LiveReportReason(val apiValue: String) {
    SPAM("spam"),
    ABUSE("abuse"),
    HARASSMENT("harassment"),
    MISINFORMATION("misinformation"),
    COPYRIGHT("copyright"),
    OTHER("other")
}

data class LiveStream(
    val id: String,
    val channelId: String,
    val channelName: String,
    val channelAvatar: String? = null,
    val title: LocalizedText,
    val description: LocalizedText = LocalizedText.of(""),
    val thumbnailUrl: String? = null,
    val status: LiveStreamStatus = LiveStreamStatus.SCHEDULED,
    val streamKind: LiveStreamKind = LiveStreamKind.LIVE,
    val eventType: LiveEventType = LiveEventType.NONE,
    val premiereVideoId: String? = null,
    val scheduledStartAt: String? = null,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val viewerCount: Long = 0,
    val peakViewerCount: Long = 0,
    val chatEnabled: Boolean = true,
    val slowModeSeconds: Int = 0,
    val dvrEnabled: Boolean = true,
    val dvrWindowSeconds: Long = 7_200,
    val playbackUrl: String? = null,
    val vodUrl: String? = null,
    val category: String? = null
) {
    /** Ummah Kids (Epic 20) safety rule: kids content never exposes live chat. */
    val isKidsContent: Boolean
        get() = category == "islamic_kids" || category == "kids"
}

data class LiveStreamState(
    val id: String,
    val status: LiveStreamStatus,
    val streamKind: LiveStreamKind = LiveStreamKind.LIVE,
    val eventType: LiveEventType = LiveEventType.NONE,
    val viewerCount: Long = 0,
    val peakViewerCount: Long = 0,
    val startedAt: String? = null,
    val endedAt: String? = null,
    val scheduledStartAt: String? = null,
    val playbackUrl: String? = null,
    val vodUrl: String? = null,
    val dvrEnabled: Boolean = true,
    val dvrWindowSeconds: Long = 7_200,
    val chatEnabled: Boolean = true,
    val slowModeSeconds: Int = 0
)

data class LiveChatMessage(
    val id: String,
    val streamId: String,
    val userId: String,
    val channelId: String,
    val authorName: String,
    val authorHandle: String? = null,
    val authorAvatar: String? = null,
    val authorRole: LiveAuthorRole = LiveAuthorRole.VIEWER,
    val text: String,
    val offsetMs: Long = 0,
    val status: LiveChatStatus = LiveChatStatus.PUBLISHED,
    val isPinned: Boolean = false,
    val moderationCategory: String? = null,
    val createdAt: String
)

data class LiveChatSettings(
    val chatEnabled: Boolean = true,
    val slowModeSeconds: Int = 0,
    val linksAllowed: Boolean = false,
    val isReadOnly: Boolean = false,
    val featureEnabled: Boolean = true
)

data class LiveChatPage(
    val items: List<LiveChatMessage>,
    val pinnedMessage: LiveChatMessage? = null,
    val nextAfterId: String? = null,
    val nextOffsetMs: Long? = null,
    val settings: LiveChatSettings = LiveChatSettings()
)

data class LiveReminderStatus(
    val reminded: Boolean,
    val reminderCount: Long
)

enum class LiveChatValidationError {
    EMPTY,
    TOO_LONG,
    LINKS_NOT_ALLOWED,
    BLOCKED_CONTENT
}

/** Client-side fast filter mirroring `apps/api-videos/src/lib/live/chat.ts`. */
object LiveChatFastFilter {
    const val MAX_MESSAGE_LENGTH = 300

    private val LINK_REGEX = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val BLOCKED_PATTERNS = listOf(
        Regex("""(?i)\b(?:kill\s+yourself|kys)\b"""),
        Regex("""(?i)\b(?:free\s+crypto|whatsapp\s+\+?\d{7,})\b""")
    )

    fun containsLink(text: String): Boolean = LINK_REGEX.containsMatchIn(text)

    fun containsBlockedPattern(text: String): Boolean = BLOCKED_PATTERNS.any { it.containsMatchIn(text) }

    fun validate(text: String, linksAllowed: Boolean = false): LiveChatValidationError? {
        val trimmed = text.trim()
        return when {
            trimmed.isEmpty() -> LiveChatValidationError.EMPTY
            trimmed.length > MAX_MESSAGE_LENGTH -> LiveChatValidationError.TOO_LONG
            !linksAllowed && containsLink(trimmed) -> LiveChatValidationError.LINKS_NOT_ALLOWED
            containsBlockedPattern(trimmed) -> LiveChatValidationError.BLOCKED_CONTENT
            else -> null
        }
    }
}
