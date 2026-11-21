package net.alummahnow.feature.watch

import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

/** The brand channel's official name in each language: a frame shows the one in its language. */
internal fun brandChannel(lang: Lang) = if (lang == Lang.AR) "الأمة الآن" else "Al Ummah Now"

/** These videos as the brand channel's uploads, with the channel named in [lang]. */
internal fun List<VideoSummary>.inLang(lang: Lang) = map { it.copy(channelName = brandChannel(lang)) }

internal fun summary(id: String, en: String, ar: String, views: Long, duration: Long, thumb: String?) = VideoSummary(
    id = id,
    title = LocalizedText(en, mapOf("ar" to ar)),
    // Local title-card art for screenshots (no network); null shows the designed placeholder.
    thumbnailUrl = thumb?.let { "${FIXTURE_THUMBS}$it.jpg" },
    durationSeconds = duration,
    views = views,
    channelId = "c1",
    channelName = brandChannel(Lang.EN),
    publishedAt = null,
    category = "quran_tafsir"
)

// The same videos, durations and views as the Studio fixtures where they overlap.
internal val sampleVideos = listOf(
    summary("v1", "Introduction to Tafsir", "مقدمة في التفسير", views = 1_520, duration = 1_830, thumb = "tafsir"),
    summary("v2", "Ramadan reminders", "تذكيرات رمضان", views = 12_000, duration = 45, thumb = "ramadan"),
    summary("v3", "Fiqh of Prayer", "فقه الصلاة", views = 2_140, duration = 3_725, thumb = "fiqh")
)

/** The New rail: different videos from Trending; the last has no thumbnail (the placeholder, as when an image fails). */
internal val recentVideos = listOf(
    summary("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", views = 8_020, duration = 2_710, thumb = "kahf"),
    summary("v5", "Friday khutbah: patience", "خطبة الجمعة: الصبر", views = 1_310, duration = 1_260, thumb = "khutbah"),
    summary("v6", "Patience in hardship", "الصبر عند الشدائد", views = 640, duration = 980, thumb = null)
)

internal fun sampleDetail(ready: Boolean = true, lang: Lang = Lang.EN) = VideoDetail(
    summary = sampleVideos.inLang(lang)[0],
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
    var railsResult: () -> List<Rail> = { listOf(Rail(RailKind.TRENDING, sampleVideos), Rail(RailKind.RECENT, recentVideos)) },
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
