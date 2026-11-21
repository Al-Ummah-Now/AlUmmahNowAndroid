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

    /** The brand channel's official name in each language: a frame shows the one in its language. */
    private fun channel(locale: StoreLocale) = if (locale == StoreLocale.AR) "الأمة الآن" else "Al Ummah Now"

    private fun video(id: String, en: String, ar: String, views: Long, duration: Long, category: String) = VideoSummary(
        id = id,
        title = LocalizedText(en, mapOf("ar" to ar)),
        thumbnailUrl = null,
        durationSeconds = duration,
        views = views,
        channelId = "c1",
        channelName = channel(StoreLocale.EN),
        publishedAt = null,
        category = category
    )

    // The same videos, views and durations as the Studio and screen fixtures.
    private val videos = listOf(
        video("v1", "Introduction to Tafsir", "مقدمة في التفسير", 1_520, 1_830, "quran_tafsir"),
        video("v5", "Friday khutbah: patience", "خطبة الجمعة: الصبر", 1_310, 1_260, "khutbah"),
        video("v2", "Ramadan reminders", "تذكيرات رمضان", 12_000, 45, "ramadan"),
        video("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", 8_020, 2_710, "quran_recitation"),
        video("v3", "Fiqh of Prayer", "فقه الصلاة", 2_140, 3_725, "fiqh")
    )

    private fun videos(locale: StoreLocale) = videos.map { it.copy(channelName = channel(locale)) }

    private fun home(locale: StoreLocale) =
        HomeUiState.Content(listOf(Rail(RailKind.TRENDING, videos(locale)), Rail(RailKind.RECENT, videos(locale).reversed())))

    private fun watch(locale: StoreLocale) = WatchUiState.Content(
        VideoDetail(
            summary = videos(locale)[0],
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
        videos(locale).drop(1)
    )

    private fun set(locale: StoreLocale, device: StoreDevice, modes: List<ThemeMode>) {
        var n = 1
        modes.forEach { mode ->
            val m = mode.name.lowercase()
            StoreShots.shoot("%02d-home-$m".format(n++), locale, device, mode) {
                HomeScreen(home(locale), onVideoClick = {}, onRetry = {}, onOpenSettings = {})
            }
            StoreShots.shoot("%02d-watch-$m".format(n++), locale, device, mode) {
                WatchScreen(watch(locale), player = null, isFullscreen = false, isInPictureInPicture = false, onToggleFullscreen = {
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
