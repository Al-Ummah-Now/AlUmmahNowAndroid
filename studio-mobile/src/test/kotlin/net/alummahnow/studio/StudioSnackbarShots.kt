package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.content.ContentActions
import net.alummahnow.feature.studio.content.ContentScreen
import net.alummahnow.feature.studio.content.ContentUiState
import net.alummahnow.feature.upload.UploadActions
import net.alummahnow.feature.upload.UploadScreen
import net.alummahnow.feature.upload.UploadUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Studio's messages about the Terms and exports, in its frame, with the app's own snackbar host. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class StudioSnackbarShots {
    @get:Rule val compose = createComposeRule()

    private val channel = StudioChannel("c1", "Al Ummah Now", mapOf("ar" to "الأمة الآن"), handle = "alummahnow", isMain = true)
    private val contentActions = ContentActions(
        onStatus = {}, onQuery = {}, onPickSuggestion = {}, onToggle = {}, onSelectAll = {}, onClearSelection = {}, onEdit = {},
        onBulkEdit = {}, onDelete = {}, onRestore = {}, onDeleteForever = {}, onRetry = {}, onLoadMore = {}
    )
    private val uploadActions = UploadActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, { java.time.ZonedDateTime.now() })

    private fun show(text: Int, tab: StudioTab, lang: StoreLocale, mode: ThemeMode, device: StoreDevice = StoreDevice.PHONE) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-${device.qualifiers}")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent {
            AunTheme(mode = mode) {
                WithFixtureImages {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { Frame(text, tab) }
                }
            }
        }
        val words = RuntimeEnvironment.getApplication().getString(text)
        compose.waitUntil { compose.onAllNodes(hasText(words)).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(FADE_MS)
    }

    @androidx.compose.runtime.Composable
    private fun Frame(text: Int, tab: StudioTab) {
        val snackbar = remember { SnackbarHostState() }
        val message = stringResource(text)
        LaunchedEffect(Unit) { snackbar.showSnackbar(message, duration = SnackbarDuration.Indefinite) }
        val wide = LocalConfiguration.current.screenWidthDp.dp >= RAIL_BREAKPOINT
        Scaffold(
            topBar = {
                StudioTopBar(stringResource(tab.label), identity = if (wide) null else channel, railAligned = wide, onAccount = {})
            },
            snackbarHost = { StudioSnackbar(snackbar, aboveBar = !wide) }
        ) { padding ->
            StudioHome(
                studioTabs(UserFeatures(setOf(UserFeatures.UPLOAD)), analyticsVisible = true),
                tab,
                onSelect = {},
                modifier = Modifier.padding(padding),
                railHeader = { IdentityButton(channel, onClick = {}) }
            ) {
                if (tab == StudioTab.UPLOAD) {
                    UploadScreen(UploadUiState(), emptyList(), uploadActions)
                } else {
                    ContentScreen(ContentUiState(videos = StudioLibrary.published, loading = false, softDelete = false), contentActions)
                }
            }
        }
    }

    private fun shoot(name: String, text: Int, tab: StudioTab, lang: StoreLocale, mode: ThemeMode, device: StoreDevice) {
        show(text, tab, lang, mode, device)
        val l = if (lang == StoreLocale.AR) "ar" else "en"
        captureScreenRoboImage(
            "src/test/screenshots/snackbar_${name}_${l}_${mode.name.lowercase()}_${device.name.lowercase()}.png"
        )
    }

    @Test
    fun `a message never covers the tabs it may name`() {
        show(R.string.studio_upload_refused_terms, StudioTab.CONTENT, StoreLocale.EN, ThemeMode.DARK)
        val words = RuntimeEnvironment.getApplication().getString(R.string.studio_upload_refused_terms)
        val message = compose.onNode(hasText(words)).fetchSemanticsNode().boundsInRoot
        val bar = compose.onNode(hasTestTag("tab-upload")).fetchSemanticsNode().boundsInRoot
        assertTrue("$message over $bar", message.bottom <= bar.top)
    }

    private fun refused(lang: StoreLocale, mode: ThemeMode, device: StoreDevice = StoreDevice.PHONE) =
        shoot("upload_refused", R.string.studio_upload_refused_terms, StudioTab.CONTENT, lang, mode, device)

    private fun exportGone(lang: StoreLocale, mode: ThemeMode) =
        shoot("export_gone", R.string.studio_export_gone, StudioTab.UPLOAD, lang, mode, StoreDevice.PHONE)

    @Test fun refusedEnDark() = refused(StoreLocale.EN, ThemeMode.DARK)

    @Test fun refusedEnLight() = refused(StoreLocale.EN, ThemeMode.LIGHT)

    @Test fun refusedArDark() = refused(StoreLocale.AR, ThemeMode.DARK)

    @Test fun refusedArLight() = refused(StoreLocale.AR, ThemeMode.LIGHT)

    @Test fun refusedEnDarkTablet() = refused(StoreLocale.EN, ThemeMode.DARK, StoreDevice.TABLET_10)

    @Test fun exportGoneEnDark() = exportGone(StoreLocale.EN, ThemeMode.DARK)

    @Test fun exportGoneEnLight() = exportGone(StoreLocale.EN, ThemeMode.LIGHT)

    @Test fun exportGoneArDark() = exportGone(StoreLocale.AR, ThemeMode.DARK)

    @Test fun exportGoneArLight() = exportGone(StoreLocale.AR, ThemeMode.LIGHT)

    private companion object {
        const val FADE_MS = 500L
    }
}
