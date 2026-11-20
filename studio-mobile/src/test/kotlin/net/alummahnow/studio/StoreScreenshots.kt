package net.alummahnow.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZonedDateTime
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.DailyMetric
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.VideoStat
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.analytics.AnalyticsScreen
import net.alummahnow.feature.studio.analytics.AnalyticsUiState
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.feature.upload.PickedFile
import net.alummahnow.feature.upload.SetForAll
import net.alummahnow.feature.upload.UploadActions
import net.alummahnow.feature.upload.UploadScreen
import net.alummahnow.feature.upload.UploadUiState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Studio store screenshots (phone, 7" and 10" tablets; en + ar) from the real screens inside Studio's navigation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: StudioApplication starts aun-core (native), which Robolectric on the JVM cannot load.
@Config(sdk = [35], application = android.app.Application::class)
class StoreScreenshots {
    @Before fun enabled() = StoreShots.assumeEnabled()

    private val channel = StudioChannel("c1", "Al Ummah Now", mapOf("ar" to "الأمة الآن"), handle = "alummahnow", isMain = true)

    // The same five videos (titles, views, durations, art) as the viewer fixtures, so every screen agrees.
    private fun published(id: String, en: String, ar: String, thumb: String, day: Int, views: Long, seconds: Double, category: String) =
        StudioVideo(
            id,
            en,
            thumbnailUrl = "${FIXTURE_THUMBS}$thumb.jpg",
            createdAt = "2026-11-%02dT10:00:00Z".format(day),
            titleLocalized = mapOf("ar" to ar),
            views = views,
            durationSeconds = seconds,
            category = category,
            status = ContentStatus.PUBLISHED
        )

    private val videos = listOf(
        published("v4", "Surat Al-Kahf recitation", "تلاوة سورة الكهف", "kahf", 7, 8_020, 2_710.0, "quran_recitation"),
        published("v1", "Introduction to Tafsir", "مقدمة في التفسير", "tafsir", 6, 1_520, 1_830.0, "quran_tafsir"),
        published("v5", "Friday khutbah: patience", "خطبة الجمعة: الصبر", "khutbah", 5, 1_310, 1_260.0, "khutbah"),
        published("v3", "Fiqh of Prayer", "فقه الصلاة", "fiqh", 4, 2_140, 3_725.0, "fiqh"),
        published("v2", "Ramadan reminders", "تذكيرات رمضان", "ramadan", 2, 12_000, 45.0, "ramadan")
    )

    private val dashboard = DashboardUiState.Content(CreatorAccess(true, false, null), ContentCounts(5, 1, 1, 0, 0))

    private val analytics = AnalyticsUiState(
        loading = false,
        data = ChannelAnalytics(
            totalViews = 24_674,
            totalVideos = 5,
            totalSubscribers = 412,
            totalLikes = 1_980,
            totalComments = 133,
            avgViewsPerVideo = 4_998.0,
            engagementRate = 8.2,
            topVideos = listOf(
                VideoStat("v2", "Ramadan reminders", null, 12_000, 800, 90, 360_000, 40.0, mapOf("ar" to "تذكيرات رمضان")),
                VideoStat("v4", "Surat Al-Kahf recitation", null, 8_020, 610, 41, 540_000, 1_355.0, mapOf("ar" to "تلاوة سورة الكهف"))
            ),
            // Lifetime views of the five videos add up to 24,990; the last 28 days had 24,674 of them.
            // October 2026: weekly rhythm (Fridays highest), noise, two spikes; adds up to totalViews (24,674).
            daily = listOf(
                755L, 1015L, 908L, 704L, 659L, 674L, 684L, 814L, 1809L, 910L, 732L, 631L, 709L, 819L,
                788L, 1114L, 979L, 891L, 718L, 733L, 872L, 806L, 1880L, 956L, 801L, 684L, 748L, 881L
            ).mapIndexed { i, v -> DailyMetric("2026-10-%02d".format(i + 1), v, v * 7 / 100, v / 90, v / 120, v * 240) }
        )
    )

    private val upload = UploadUiState(
        files = listOf(
            PickedFile("u1", "kahf-part-1.mp4", 734_003_200, "video/mp4"),
            PickedFile("u2", "kahf-part-2.mp4", 512_000_000, "video/mp4")
        ),
        form = SetForAll(
            titlePattern = "Surat Al-Kahf {n}",
            padNumbers = true,
            category = "quran_recitation",
            language = "ar",
            playlistIds = setOf("p1"),
            tagsText = "quran, قرآن"
        ),
        playlists = listOf(StudioPlaylist("p1", "Quran recitation", true), StudioPlaylist("p2", "Friday khutbahs", true)),
        queue = listOf(
            UploadItem("q1", "Ramadan reminder", UploadPhase.UPLOADING, 300_000_000, 700_000_000, null, null),
            UploadItem("q2", "Seerah overview", UploadPhase.DONE, 400_000_000, 400_000_000, "v2", null)
        )
    )

    private val contentActions = ContentActions(
        onStatus = {}, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {}, onEdit = {},
        onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )
    private val uploadActions = UploadActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, { ZonedDateTime.now() })

    /** The app's Studio frame, as StudioApp draws it: the web header over the bar (phone) or the rail (tablet). */
    private fun inStudio(tab: StudioTab, body: @Composable () -> Unit): @Composable () -> Unit = {
        val wide = LocalConfiguration.current.screenWidthDp.dp >= RAIL_BREAKPOINT
        Scaffold(
            topBar = {
                StudioTopBar(
                    title = stringResource(tab.label),
                    identity = if (wide) null else channel,
                    railAligned = wide,
                    onAccount = {}
                )
            }
        ) { padding ->
            StudioHome(
                StudioTab.entries,
                tab,
                onSelect = {},
                modifier = Modifier.padding(padding),
                railHeader = { IdentityButton(channel, onClick = {}) }
            ) { body() }
        }
    }

    internal val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "dashboard" to inStudio(StudioTab.DASHBOARD) { DashboardScreen(dashboard, onRetry = {}) },
        "content" to inStudio(StudioTab.CONTENT) { ContentScreen(ContentUiState(videos = videos, loading = false), contentActions) },
        "upload" to inStudio(StudioTab.UPLOAD) { UploadScreen(upload, listOf("Surat Al-Kahf 01", "Surat Al-Kahf 02"), uploadActions) },
        "analytics" to inStudio(StudioTab.ANALYTICS) { AnalyticsScreen(analytics, onRange = {}, onRetry = {}) }
    )

    private fun set(locale: StoreLocale, device: StoreDevice, modes: List<ThemeMode>) {
        var n = 1
        modes.forEach { mode ->
            screens.forEach { (name, screen) ->
                StoreShots.shoot("%02d-$name-${mode.name.lowercase()}".format(n++), locale, device, mode, screen)
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
