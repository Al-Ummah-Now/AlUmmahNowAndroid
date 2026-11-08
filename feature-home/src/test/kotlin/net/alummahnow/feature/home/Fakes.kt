package net.alummahnow.feature.home

import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

internal fun summary(id: String, en: String, ar: String, views: Long = 4167, duration: Long = 75) = VideoSummary(
    id = id,
    title = LocalizedText(en, mapOf("ar" to ar)),
    thumbnailUrl = null,
    durationSeconds = duration,
    views = views,
    channelId = "c1",
    channelName = "Al Ummah Now — Lectures",
    publishedAt = null,
    category = "quran_tafsir"
)

internal val sampleVideos = listOf(
    summary("v1", "Introduction to Tafsir", "مقدمة في التفسير"),
    summary("v2", "Seerah Overview", "نظرة عامة على السيرة", views = 1_650, duration = 65),
    summary("v3", "Fiqh of Prayer", "فقه الصلاة", views = 2, duration = 3725)
)

internal fun sampleDetail(ready: Boolean = true) = VideoDetail(
    summary = sampleVideos[0],
    description = LocalizedText("An introduction to tafsir.", mapOf("ar" to "مقدمة في علم التفسير.")),
    playUrl = "https://cdn.example/v1/playlist.m3u8",
    availableResolutions = listOf("240p", "360p"),
    chapters = listOf(
        Chapter("c1", 0.0, 15.0, LocalizedText("Introduction", mapOf("ar" to "المقدمة"))),
        Chapter("c2", 15.0, 30.0, LocalizedText("What is tafsir?", mapOf("ar" to "ما هو التفسير؟")))
    ),
    subtitles = emptyList(),
    likes = 0,
    tags = emptyList(),
    isLive = false,
    isReady = ready
)

internal class FakeCatalog(
    var railsResult: () -> List<Rail> = { listOf(Rail(RailKind.TRENDING, sampleVideos), Rail(RailKind.RECENT, sampleVideos.reversed())) },
    var videoResult: (String) -> VideoDetail = { sampleDetail() },
    var relatedResult: () -> List<VideoSummary> = { sampleVideos.drop(1) }
) : CatalogRepository {
    var homeCalls = 0
    override suspend fun homeRails(): List<Rail> {
        homeCalls++
        return railsResult()
    }
    override suspend fun video(id: String) = videoResult(id)
    override suspend fun related(video: VideoDetail, limit: Int) = relatedResult()
    override suspend fun search(query: String, limit: Int) = emptyList<VideoSummary>()
}

internal fun networkError(): Nothing = throw CoreError.Network(java.io.IOException("offline"))
