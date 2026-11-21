package net.alummahnow.feature.home

import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.fixtures.BrandChannel

/** The brand channel's official name in each language: a frame shows the one in its language. */
internal fun brandChannel(lang: Lang) = BrandChannel.name(lang == Lang.AR)

/** These videos as the brand channel's uploads, with the channel named in [lang]. */
internal fun List<VideoSummary>.inLang(lang: Lang) = map { it.copy(channelName = brandChannel(lang)) }

private fun brandVideos(vararg videos: BrandChannel.ChannelVideo) = videos.map { BrandChannel.summary(it, arabic = false) }

// The brand channel's videos (BrandChannel, shared with the Studio fixtures): the same titles, art, durations and
// lifetime views as the Studio Content list.
// Trending is ordered by views, most first.
internal val sampleVideos = brandVideos(BrandChannel.ramadan, BrandChannel.fiqh, BrandChannel.tafsir)

/** The New rail: different videos from Trending, newest first. */
internal val recentVideos = brandVideos(BrandChannel.kahf, BrandChannel.khutbah, BrandChannel.patience)

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
