package net.alummahnow.player

import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.SubtitleTrack
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

internal fun summary(id: String, en: String = "Video $id", ar: String = "فيديو $id") = VideoSummary(
    id = id,
    title = LocalizedText(en, mapOf("ar" to ar)),
    thumbnailUrl = "https://cdn.example/$id.jpg",
    durationSeconds = 75,
    views = 10,
    channelId = "c1",
    channelName = "Channel",
    publishedAt = null,
    category = "quran_tafsir"
)

internal fun detail(id: String) = VideoDetail(
    summary = summary(id),
    description = LocalizedText(""),
    playUrl = "https://cdn.example/$id/playlist.m3u8",
    availableResolutions = listOf("240p", "360p"),
    chapters = emptyList(),
    subtitles = listOf(
        SubtitleTrack("en", "English", "https://api.example/$id/subtitles/en", isDefault = true),
        SubtitleTrack("ar", "العربية", "https://api.example/$id/subtitles/ar", isDefault = false)
    ),
    likes = 0,
    tags = emptyList(),
    isLive = false,
    isReady = true
)

internal class FakeCatalog : CatalogRepository {
    override suspend fun homeRails() = listOf(
        Rail(RailKind.TRENDING, listOf(summary("t1"), summary("t2"))),
        Rail(RailKind.RECENT, listOf(summary("r1")))
    )
    override suspend fun video(id: String) = detail(id)
    override suspend fun related(video: VideoDetail, limit: Int) = emptyList<VideoSummary>()
    override suspend fun search(query: String, limit: Int) = emptyList<VideoSummary>()
}
