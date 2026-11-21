package net.alummahnow.feature.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.FanousTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.fanous.FanousThemeId
import net.alummahnow.feature.studio.analytics.AnalyticsScreen
import net.alummahnow.feature.studio.analytics.AnalyticsUiState
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.fixtures.BrandChannel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every Studio page paints the active mode's page background itself. A page that left it to its host showed white
 * behind dark cards in Dark (the Dashboard, 2026-11-20). The pixel checked sits in the page's side margin, where only
 * the page background can be.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PageBackgroundTest {
    @get:Rule val compose = createComposeRule()

    private val noop = ContentActions(
        onStatus = {}, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {}, onEdit = {},
        onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )

    private fun check(mode: ThemeMode, page: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        compose.setContent { AunTheme(mode = mode) { page() } }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        // Bottom corner, inside the side margin: nothing but the page is drawn there.
        val pixel = image.getPixel(image.width - 2, image.height - 2)
        val expected = FanousTheme(FanousThemeId.FALLBACK.tokens, dark = mode == ThemeMode.DARK).colors.background.argb
        assertEquals("$mode page background", Integer.toHexString(expected), Integer.toHexString(pixel))
    }

    private val dashboard = @Composable {
        DashboardScreen(DashboardUiState.Content(CreatorAccess(true, false, null), BrandChannel.counts), {})
    }
    private val content = @Composable { ContentScreen(ContentUiState(videos = libraryVideos, loading = false), noop) }
    private val analytics = @Composable { AnalyticsScreen(AnalyticsUiState(loading = false, data = sampleAnalytics), {}, {}) }

    @Test fun dashboardDark() = check(ThemeMode.DARK, dashboard)

    @Test fun dashboardLight() = check(ThemeMode.LIGHT, dashboard)

    @Test fun contentDark() = check(ThemeMode.DARK, content)

    @Test fun contentLight() = check(ThemeMode.LIGHT, content)

    @Test fun analyticsDark() = check(ThemeMode.DARK, analytics)

    @Test fun analyticsLight() = check(ThemeMode.LIGHT, analytics)
}
