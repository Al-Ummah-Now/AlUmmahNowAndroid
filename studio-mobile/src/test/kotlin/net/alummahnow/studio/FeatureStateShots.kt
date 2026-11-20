package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardSection
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Features that are off, unconfigured or outside the tier: the real Studio frame with only the sections this person
 * has, so no page is ever hollow. Golden screenshots (recorded on Linux), en + ar, phone + tablet.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FeatureStateShots {
    private val channel = StudioChannel("c1", "Al Ummah Now", mapOf("ar" to "الأمة الآن"), handle = "alummahnow", isMain = true)

    // The one Studio library (StudioLibrary): the same videos, views and counts as every other Studio and viewer frame.
    private val recent = StudioLibrary.published.take(3)
    private val drafts = listOf(StudioLibrary.draft)
    private val scheduled = listOf(StudioLibrary.scheduled)
    private val published = StudioLibrary.published
    private val counts = StudioLibrary.counts

    private fun dashboard(
        canUpload: Boolean,
        comments: DashboardSection<CommentsSummary> = DashboardSection.Ready(CommentsSummary(2, 5))
    ) = DashboardUiState.Content(
        CreatorAccess(canUpload, false, null),
        counts,
        recent = DashboardSection.Ready(recent),
        comments = comments,
        drafts = DashboardSection.Ready(drafts),
        scheduled = DashboardSection.Ready(scheduled)
    )

    private val contentActions = ContentActions(
        onStatus = {}, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {}, onEdit = {},
        onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )

    private val all = UserFeatures(setOf(UserFeatures.UPLOAD))
    private val noUpload = UserFeatures(emptySet())

    /** Studio's frame with the tabs [studioTabs] leaves for these features. */
    private fun frame(tabs: List<StudioTab>, tab: StudioTab, body: @Composable () -> Unit): @Composable () -> Unit = {
        val wide = LocalConfiguration.current.screenWidthDp.dp >= RAIL_BREAKPOINT
        Scaffold(
            topBar = {
                StudioTopBar(title = stringResource(tab.label), identity = if (wide) null else channel, railAligned = wide, onAccount = {})
            }
        ) { padding ->
            StudioHome(
                tabs,
                tab,
                onSelect = {},
                modifier = Modifier.padding(padding),
                railHeader = { IdentityButton(channel, onClick = {}) }
            ) { body() }
        }
    }

    private val states: List<Pair<String, @Composable () -> Unit>> = listOf(
        // Everything on: every section and tab.
        "all-on" to frame(studioTabs(all, analyticsVisible = true), StudioTab.DASHBOARD) {
            DashboardScreen(dashboard(canUpload = true), onRetry = {})
        },
        // Uploads by invitation (tier): no Upload tab and no upload card; the rest stays.
        "upload-off" to frame(studioTabs(noUpload, analyticsVisible = true), StudioTab.DASHBOARD) {
            DashboardScreen(dashboard(canUpload = false), onRetry = {})
        },
        // Analytics switched off by the admin (404 feature_off): its tab is gone.
        "analytics-off" to frame(studioTabs(all, analyticsVisible = false), StudioTab.DASHBOARD) {
            DashboardScreen(dashboard(canUpload = true), onRetry = {})
        },
        // Comments off: that card is gone and recent uploads take the full width.
        "comments-off" to frame(studioTabs(all, analyticsVisible = true), StudioTab.DASHBOARD) {
            DashboardScreen(dashboard(canUpload = true, comments = DashboardSection.Hidden), onRetry = {})
        },
        // One section failed to load: it says so in its own card with Try again; the others stay.
        "section-failed" to frame(studioTabs(all, analyticsVisible = true), StudioTab.DASHBOARD) {
            DashboardScreen(
                dashboard(canUpload = true, comments = DashboardSection.Failed(net.alummahnow.design.R.string.aun_error_generic)),
                onRetry = {}
            )
        },
        // Nothing left on the Dashboard (no uploads, every section off): Studio opens Content first, and with
        // Recently deleted off there is no such filter.
        "content-only" to frame(studioTabs(noUpload, analyticsVisible = false, dashboardVisible = false), StudioTab.CONTENT) {
            // The Published tab lists published videos only (not the draft or the processing one).
            ContentScreen(ContentUiState(videos = published, loading = false, softDelete = false), contentActions)
        }
    )

    private fun shoot(name: String, lang: StoreLocale, device: StoreDevice, mode: ThemeMode, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-${device.qualifiers}")
        // Infinite animations (lantern loader) never let Compose idle; reduced motion renders them static.
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val lang2 = if (lang == StoreLocale.AR) "ar" else "en"
        val file = "src/test/screenshots/state_${name}_${lang2}_${mode.name.lowercase()}_${device.name.lowercase()}.png"
        captureRoboImage(file) {
            AunTheme(mode = mode) {
                WithFixtureImages { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
            }
        }
    }

    private fun set(lang: StoreLocale, device: StoreDevice, mode: ThemeMode, only: Set<String>? = null) {
        states.filter { only == null || it.first in only }.forEach { (name, screen) -> shoot(name, lang, device, mode, screen) }
    }

    // On a phone, comments-off and section-failed differ from all-on only below the fold, so their phone frames
    // would be identical to all-on; the tablet frames show those two states.
    private val phoneStates = setOf("all-on", "upload-off", "analytics-off", "content-only")

    @Test fun phoneEn() = set(StoreLocale.EN, StoreDevice.PHONE, ThemeMode.DARK, phoneStates)

    @Test fun phoneAr() = set(StoreLocale.AR, StoreDevice.PHONE, ThemeMode.DARK, phoneStates)

    @Test fun phoneEnLight() = set(StoreLocale.EN, StoreDevice.PHONE, ThemeMode.LIGHT, phoneStates)

    @Test fun phoneArLight() = set(StoreLocale.AR, StoreDevice.PHONE, ThemeMode.LIGHT, phoneStates)

    @Test fun tabletEnLight() = set(StoreLocale.EN, StoreDevice.TABLET_10, ThemeMode.LIGHT)

    @Test fun tabletEn() = set(StoreLocale.EN, StoreDevice.TABLET_10, ThemeMode.DARK)

    @Test fun tabletAr() = set(StoreLocale.AR, StoreDevice.TABLET_10, ThemeMode.DARK)
}
