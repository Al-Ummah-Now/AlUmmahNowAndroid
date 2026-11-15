package net.alummahnow.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.LocalizedText
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.home.HomeScreen
import net.alummahnow.feature.home.HomeUiState
import net.alummahnow.feature.watch.WatchScreen
import net.alummahnow.feature.watch.WatchUiState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Viewer store screenshots (phone, 7" and 10" tablets; en + ar) from the real screens with fixture data. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StoreScreenshots {
    @Before fun enabled() = StoreShots.assumeEnabled()

    private fun video(id: String, en: String, ar: String, views: Long, duration: Long, category: String) = VideoSummary(
        id = id,
        title = LocalizedText(en, mapOf("ar" to ar)),
        thumbnailUrl = null,
        durationSeconds = duration,
        views = views,
        channelId = "c1",
        channelName = "Al Ummah Now",
        publishedAt = null,
        category = category
    )

    private val videos = listOf(
        video("v1", "Introduction to tafsir", "مقدمة في التفسير", 4_167, 1_830, "quran_tafsir"),
        video("v2", "Seerah overview", "نظرة عامة على السيرة", 1_650, 1_265, "islamic_history"),
        video("v3", "Ramadan reminder", "تذكير رمضان", 12_400, 245, "ramadan"),
        video("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", 8_020, 2_710, "quran_recitation"),
        video("v5", "Fiqh of prayer", "فقه الصلاة", 932, 3_725, "fiqh")
    )

    private val home = HomeUiState.Content(listOf(Rail(RailKind.TRENDING, videos), Rail(RailKind.RECENT, videos.reversed())))

    private val watch = WatchUiState.Content(
        VideoDetail(
            summary = videos[0],
            description = LocalizedText(
                "An introduction to tafsir, uploaded to the Al Ummah Now channel.",
                mapOf("ar" to "مقدمة في التفسير، مرفوعة على قناة الأمة الآن.")
            ),
            playUrl = null,
            availableResolutions = listOf("360p", "720p", "1080p"),
            chapters = listOf(
                Chapter("c1", 0.0, 120.0, LocalizedText("Introduction", mapOf("ar" to "المقدمة"))),
                Chapter("c2", 120.0, 900.0, LocalizedText("What is tafsir?", mapOf("ar" to "ما هو التفسير؟")))
            ),
            subtitles = emptyList(),
            likes = 214,
            tags = emptyList(),
            isLive = false,
            isReady = true
        ),
        videos.drop(1)
    )

    private fun set(locale: StoreLocale, device: StoreDevice, modes: List<ThemeMode>) {
        var n = 1
        modes.forEach { mode ->
            val m = mode.name.lowercase()
            StoreShots.shoot("%02d-home-$m".format(n++), locale, device, mode) {
                HomeScreen(home, onVideoClick = {}, onRetry = {}, onOpenSettings = {})
            }
            StoreShots.shoot("%02d-watch-$m".format(n++), locale, device, mode) {
                WatchScreen(watch, player = null, isFullscreen = false, isInPictureInPicture = false, onToggleFullscreen = {
                }, onVideoClick = {}, onRetry = {})
            }
        }
    }

    @Test fun phoneEn() = set(StoreLocale.EN, StoreDevice.PHONE, listOf(ThemeMode.DARK, ThemeMode.LIGHT))

    @Test fun phoneAr() = set(StoreLocale.AR, StoreDevice.PHONE, listOf(ThemeMode.DARK, ThemeMode.LIGHT))

    @Test fun tablet7En() = set(StoreLocale.EN, StoreDevice.TABLET_7, listOf(ThemeMode.DARK))

    @Test fun tablet7Ar() = set(StoreLocale.AR, StoreDevice.TABLET_7, listOf(ThemeMode.DARK))

    @Test fun tablet10En() = set(StoreLocale.EN, StoreDevice.TABLET_10, listOf(ThemeMode.DARK))

    @Test fun tablet10Ar() = set(StoreLocale.AR, StoreDevice.TABLET_10, listOf(ThemeMode.DARK))
}
