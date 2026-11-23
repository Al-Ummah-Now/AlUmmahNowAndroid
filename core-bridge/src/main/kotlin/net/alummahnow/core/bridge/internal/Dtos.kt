package net.alummahnow.core.bridge.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ListEnvelope(val data: List<VideoDto> = emptyList(), val cursor: String? = null)

@Serializable
internal data class VideoDto(
    val id: String,
    val title: String = "",
    val titleLocalized: Map<String, String> = emptyMap(),
    val description: String = "",
    val descriptionLocalized: Map<String, String> = emptyMap(),
    val thumbnailUrl: String? = null,
    val playUrl: String? = null,
    val duration: Double? = null,
    val views: Long = 0,
    val channelId: String? = null,
    val channelName: String? = null,
    val dateCreated: String? = null,
    val category: String? = null,
    val availableResolutions: List<String> = emptyList(),
    val chapters: ChaptersDto? = null,
    val subtitles: List<SubtitleDto> = emptyList(),
    val likesCount: Long = 0,
    val tags: List<String> = emptyList(),
    val isLive: Boolean = false,
    val isPublic: Boolean = true,
    val uploadStatus: String? = null
)

@Serializable
internal data class ChaptersDto(val chapters: List<ChapterDto> = emptyList())

@Serializable
internal data class ChapterDto(
    val id: String,
    val startTime: Double,
    val endTime: Double? = null,
    val title: String = "",
    val titleLocalized: Map<String, String> = emptyMap()
)

@Serializable
internal data class SubtitleDto(
    val language: String,
    val label: String = "",
    val url: String,
    val status: String? = null,
    val isDefault: Boolean = false,
    val kind: String? = null,
    val extra: JsonElement? = null
)

@Serializable
internal data class LiveStreamsEnvelope(val data: List<LiveStreamDto> = emptyList())

@Serializable
internal data class LiveStreamEnvelope(val data: LiveStreamDto? = null)

@Serializable
internal data class LiveStreamDto(
    val id: String,
    val channelId: String = "",
    val channelName: String = "",
    val channelAvatar: String? = null,
    val title: String = "",
    val titleLocalized: Map<String, String> = emptyMap(),
    val description: String = "",
    val descriptionLocalized: Map<String, String> = emptyMap(),
    val thumbnailUrl: String? = null,
    val status: String = "scheduled",
    val streamKind: String = "live",
    val eventType: String = "none",
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
)

@Serializable
internal data class LiveStreamStateEnvelope(val data: LiveStreamStateDto? = null)

@Serializable
internal data class LiveStreamStateDto(
    val id: String = "",
    val status: String = "scheduled",
    val streamKind: String = "live",
    val eventType: String = "none",
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

@Serializable
internal data class LiveChatMessageDto(
    val id: String,
    val streamId: String = "",
    val userId: String = "",
    val channelId: String = "",
    val authorName: String = "",
    val authorHandle: String? = null,
    val authorAvatar: String? = null,
    val authorRole: String = "viewer",
    val text: String = "",
    val offsetMs: Long = 0,
    val status: String = "published",
    val isPinned: Boolean = false,
    val moderationCategory: String? = null,
    val createdAt: String = ""
)

@Serializable
internal data class LiveChatSettingsDto(
    val chatEnabled: Boolean = true,
    val slowModeSeconds: Int = 0,
    val linksAllowed: Boolean = false,
    val isReadOnly: Boolean = false,
    val featureEnabled: Boolean = true
)

@Serializable
internal data class LiveChatPageEnvelope(
    val data: List<LiveChatMessageDto> = emptyList(),
    val pinnedMessage: LiveChatMessageDto? = null,
    val nextAfterId: String? = null,
    val nextOffsetMs: Long? = null,
    val settings: LiveChatSettingsDto = LiveChatSettingsDto()
)

@Serializable
internal data class LiveChatSingleEnvelope(val data: LiveChatMessageDto)

@Serializable
internal data class LiveReminderEnvelope(
    val data: LiveReminderDto? = null,
    val reminded: Boolean? = null,
    val reminderCount: Long? = null
)

@Serializable
internal data class LiveReminderDto(val reminded: Boolean = false, val reminderCount: Long = 0)
