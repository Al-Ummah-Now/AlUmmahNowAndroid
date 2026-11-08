package net.alummahnow.core.bridge.internal

import net.alummahnow.core.bridge.model.Chapter
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
