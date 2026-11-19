package net.alummahnow.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.time.ZonedDateTime

/** Studio store screenshots (phone, 7" and 10" tablets; en + ar) from the real screens inside Studio's navigation. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: StudioApplication starts aun-core (native), which Robolectric on the JVM cannot load.
@Config(sdk = [35], application = android.app.Application::class)
class StoreScreenshots {
    @Before fun enabled() = StoreShots.assumeEnabled()

    private val channel = StudioChannel("c1", "Al Ummah Now", mapOf("ar" to "الأمة الآن"), handle = "alummahnow", isMain = true)

    private val videos = listOf(
        StudioVideo(
            "v1",
            "Introduction to tafsir",
            titleLocalized = mapOf("ar" to "مقدمة في التفسير"),
            views = 4_167,
            durationSeconds = 1_830.0,
            category = "quran_tafsir",
            status = ContentStatus.PUBLISHED
        ),
        StudioVideo(
            "v2",
            "Seerah overview",
            titleLocalized = mapOf("ar" to "نظرة عامة على السيرة"),
            isPublic = false,
            views = 87,
            durationSeconds = 1_265.0,
            category = "islamic_history",
            status = ContentStatus.SCHEDULED
        ),
        StudioVideo(
            "v3",
            "Ramadan reminder",
            titleLocalized = mapOf("ar" to "تذكير رمضان"),
            views = 12_400,
            durationSeconds = 245.0,
            category = "ramadan",
            status = ContentStatus.PUBLISHED
        ),
        StudioVideo(
            "v4",
            "Surat Al-Kahf recitation",
            titleLocalized = mapOf("ar" to "تلاوة سورة الكهف"),
            views = 8_020,
            durationSeconds = 2_710.0,
            category = "quran_recitation",
            status = ContentStatus.PUBLISHED
        )
    )

    private val dashboard = DashboardUiState.Content(CreatorAccess(true, false, null), ContentCounts(24, 2, 3, 1, 0))

    private val analytics = AnalyticsUiState(
        loading = false,
        data = ChannelAnalytics(
            totalViews = 24_674,
            totalVideos = 24,
            totalSubscribers = 412,
            totalLikes = 1_980,
            totalComments = 133,
            avgViewsPerVideo = 1_028.1,
            engagementRate = 8.2,
            topVideos = listOf(
                VideoStat("v3", "Ramadan reminder", null, 12_400, 800, 90, 360_000, 180.0),
                VideoStat("v4", "Surat Al-Kahf recitation", null, 8_020, 610, 41, 540_000, 1_355.0)
            ),
            daily = (1..28).map { d -> DailyMetric("2026-10-%02d".format(d), (d * 37L) % 500 + 40, 5, 1, d % 3L, 3_600L * (d % 5)) }
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

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
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
