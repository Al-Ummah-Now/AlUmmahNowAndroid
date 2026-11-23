package net.alummahnow.core.bridge.internal

import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.LiveAuthorRole
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatPage
import net.alummahnow.core.bridge.model.LiveChatSettings
import net.alummahnow.core.bridge.model.LiveChatStatus
import net.alummahnow.core.bridge.model.LiveEventType
import net.alummahnow.core.bridge.model.LiveReminderStatus
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamKind
import net.alummahnow.core.bridge.model.LiveStreamState
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.SubtitleTrack
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

internal fun VideoDto.toSummary() = VideoSummary(
    id = id,
    title = LocalizedText(title, titleLocalized),
    thumbnailUrl = thumbnailUrl,
    durationSeconds = duration?.toLong()?.takeIf { it > 0 },
    views = views,
    channelId = channelId,
    channelName = channelName,
    publishedAt = dateCreated,
    category = category
)

internal fun VideoDto.toDetail() = VideoDetail(
    summary = toSummary(),
    description = LocalizedText(description, descriptionLocalized),
    playUrl = playUrl,
    availableResolutions = availableResolutions,
    chapters = chapters?.chapters.orEmpty()
        .sortedBy { it.startTime }
        .map { Chapter(it.id, it.startTime, it.endTime, LocalizedText(it.title, it.titleLocalized)) },
    subtitles = subtitles
        .filter { it.status == null || it.status == "ready" }
        .map { SubtitleTrack(it.language, it.label.ifBlank { it.language }, it.url, it.isDefault) },
    likes = likesCount,
    tags = tags,
    isLive = isLive,
    // Older records have no uploadStatus; treat them as ready when they carry a play URL.
    isReady = (uploadStatus == null || uploadStatus == "ready") && !playUrl.isNullOrBlank()
)

internal fun LiveStreamDto.toDomain() = LiveStream(
    id = id,
    channelId = channelId,
    channelName = channelName,
    channelAvatar = channelAvatar,
    title = LocalizedText(title, titleLocalized),
    description = LocalizedText(description, descriptionLocalized),
    thumbnailUrl = thumbnailUrl,
    status = LiveStreamStatus.from(status),
    streamKind = LiveStreamKind.from(streamKind),
    eventType = LiveEventType.from(eventType),
    premiereVideoId = premiereVideoId,
    scheduledStartAt = scheduledStartAt,
    startedAt = startedAt,
    endedAt = endedAt,
    viewerCount = viewerCount,
    peakViewerCount = peakViewerCount,
    chatEnabled = chatEnabled,
    slowModeSeconds = slowModeSeconds,
    dvrEnabled = dvrEnabled,
    dvrWindowSeconds = dvrWindowSeconds,
    playbackUrl = playbackUrl,
    vodUrl = vodUrl,
    category = category
)

internal fun LiveStreamStateDto.toDomain(fallbackId: String = "") = LiveStreamState(
    id = id.ifBlank { fallbackId },
    status = LiveStreamStatus.from(status),
    streamKind = LiveStreamKind.from(streamKind),
    eventType = LiveEventType.from(eventType),
    viewerCount = viewerCount,
    peakViewerCount = peakViewerCount,
    startedAt = startedAt,
    endedAt = endedAt,
    scheduledStartAt = scheduledStartAt,
    playbackUrl = playbackUrl,
    vodUrl = vodUrl,
    dvrEnabled = dvrEnabled,
    dvrWindowSeconds = dvrWindowSeconds,
    chatEnabled = chatEnabled,
    slowModeSeconds = slowModeSeconds
)

internal fun LiveChatMessageDto.toDomain() = LiveChatMessage(
    id = id,
    streamId = streamId,
    userId = userId,
    channelId = channelId,
    authorName = authorName,
    authorHandle = authorHandle,
    authorAvatar = authorAvatar,
    authorRole = LiveAuthorRole.from(authorRole),
    text = text,
    offsetMs = offsetMs,
    status = LiveChatStatus.from(status),
    isPinned = isPinned,
    moderationCategory = moderationCategory,
    createdAt = createdAt
)

internal fun LiveChatSettingsDto.toDomain() = LiveChatSettings(
    chatEnabled = chatEnabled,
    slowModeSeconds = slowModeSeconds,
    linksAllowed = linksAllowed,
    isReadOnly = isReadOnly,
    featureEnabled = featureEnabled
)

internal fun LiveChatPageEnvelope.toDomain() = LiveChatPage(
    items = data.map { it.toDomain() },
    pinnedMessage = pinnedMessage?.toDomain(),
    nextAfterId = nextAfterId,
    nextOffsetMs = nextOffsetMs,
    settings = settings.toDomain()
)

internal fun LiveReminderEnvelope.toDomain() = LiveReminderStatus(
    reminded = data?.reminded ?: reminded ?: false,
    reminderCount = data?.reminderCount ?: reminderCount ?: 0
)
