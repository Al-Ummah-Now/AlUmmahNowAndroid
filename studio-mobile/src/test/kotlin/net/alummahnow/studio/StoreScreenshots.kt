package net.alummahnow.studio

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import java.time.ZonedDateTime
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase.DONE
import net.alummahnow.core.bridge.studio.UploadPhase.UPLOADING
import net.alummahnow.design.format.currentLocale
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
import net.alummahnow.feature.upload.TitlePattern
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

    private val videos = StudioLibrary.published

    private val dashboard = DashboardUiState.Content(CreatorAccess(true, false, null), StudioLibrary.counts)

    // October analytics of the same videos (BrandChannel): each video's October views are below its lifetime views.
    private val analytics = AnalyticsUiState(loading = false, data = StudioLibrary.analytics)

    /** The upload in progress, written in the frame's language (file names, title pattern, tags, playlists, queue). */
    private fun upload(arabic: Boolean) = UploadUiState(
        files = listOf(
            PickedFile("u1", if (arabic) "الكهف-الجزء-الأول.mp4" else "kahf-part-1.mp4", 734_003_200, "video/mp4"),
            PickedFile("u2", if (arabic) "الكهف-الجزء-الثاني.mp4" else "kahf-part-2.mp4", 512_000_000, "video/mp4")
        ),
        form = SetForAll(
            titlePattern = if (arabic) "سورة الكهف {n}" else "Surat Al-Kahf {n}",
            padNumbers = true,
            category = "quran_recitation",
            language = "ar",
            playlistIds = setOf("p1"),
            tagsText = if (arabic) "قرآن، تلاوة" else "quran, recitation"
        ),
        playlists = listOf(
            StudioPlaylist("p1", if (arabic) "تلاوات القرآن" else "Quran recitation", true),
            StudioPlaylist("p2", if (arabic) "خطب الجمعة" else "Friday khutbahs", true)
        ),
        queue = listOf(
            UploadItem("q1", if (arabic) "تذكير رمضان" else "Ramadan reminder", UPLOADING, 300_000_000, 700_000_000, null, null),
            UploadItem("q2", if (arabic) "نظرة عامة على السيرة" else "Seerah overview", DONE, 400_000_000, 400_000_000, "v2", null)
        )
    )

    @Composable
    private fun UploadInLanguage() {
        val arabic = currentLocale().language == "ar"
        val state = upload(arabic)
        // Titles from the pattern, with the frame language's digits («سورة الكهف ٠١», "Surat Al-Kahf 01").
        val titles = state.files.indices.map { i ->
            TitlePattern.apply(state.form.titlePattern, state.files[i].name, i, LocalDate.of(2026, 11, 21), "", currentLocale(), pad = 2)
        }
        UploadScreen(state, titles, uploadActions)
    }

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
        "content" to
            inStudio(StudioTab.CONTENT) {
                ContentScreen(ContentUiState(videos = videos, loading = false, softDelete = true), contentActions)
            },
        "upload" to inStudio(StudioTab.UPLOAD) { UploadInLanguage() },
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
