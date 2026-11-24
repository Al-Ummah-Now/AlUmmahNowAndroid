package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.terms.CreatorTermsGate
import net.alummahnow.feature.studio.terms.CreatorTermsUiState
import net.alummahnow.feature.upload.PickedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class StudioTermsWiringTest {
    @get:Rule val compose = createComposeRule()

    private val channel = StudioChannel("c1", "Al Ummah Now", mapOf("ar" to "الأمة الآن"), handle = "alummahnow", isMain = true)

    @Test
    fun `StudioUploadTab gates Upload until Accepted and passes webHost through`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        var state: CreatorTermsUiState by mutableStateOf(CreatorTermsUiState.Required())
        val opened = mutableListOf<String>()
        var agreed = 0
        compose.setContent {
            AunTheme {
                StudioUploadTab(
                    termsState = state,
                    onRetryTerms = {},
                    onAgreeTerms = {
                        agreed++
                        state = CreatorTermsUiState.Accepted
                    },
                    onOpenUrl = { opened += it },
                    webHost = "videos.qa.alummahnow.net"
                ) {
                    Text("upload-ready", Modifier.testTag("upload-ready"))
                }
            }
        }
        compose.onNodeWithTag("creator-terms-gate").assertIsDisplayed()
        compose.onNodeWithTag("upload-ready").assertDoesNotExist()
        compose.onNodeWithTag("creator-terms-link").performClick()
        assertEquals(listOf("https://www.qa.alummahnow.net/en/terms/"), opened)

        compose.onNodeWithTag("creator-terms-checkbox").performClick()
        compose.onNodeWithTag("creator-terms-agree").performClick()
        compose.waitForIdle()
        assertEquals(1, agreed)
        compose.onNodeWithTag("upload-ready").assertIsDisplayed()
    }

    @Test
    fun `PendingExportsSaver preserves multiple exports across channels and round-trips cleanly`() {
        val fileA = PickedFile("file:///a.mp4", "a.mp4", 100L, "video/mp4")
        val fileB = PickedFile("file:///b.mp4", "b.mp4", 200L, "video/mp4")
        val fileC = PickedFile("file:///c.mp4", "c.mp4", 300L, null)
        var map = emptyMap<String, List<PickedFile>>()
        map = enqueuePendingExport(map, "ch1", fileA)
        map = enqueuePendingExport(map, "ch1", fileB)
        map = enqueuePendingExport(map, "ch1", fileA) // duplicate URI deduped
        map = enqueuePendingExport(map, "ch2", fileC)

        assertEquals(listOf(fileA, fileB), map["ch1"])
        assertEquals(listOf(fileC), map["ch2"])

        val saved = with(PendingExportsSaver) {
            androidx.compose.runtime.saveable.SaverScope { true }.save(map)
        }!!
        val restored = PendingExportsSaver.restore(saved)!!
        assertEquals(map, restored)
        assertTrue((restored - "ch1").containsKey("ch2"))
    }

    private fun shootInChrome(name: String, lang: StoreLocale, mode: ThemeMode) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-${StoreDevice.PHONE.qualifiers}")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val tabs = studioTabs(UserFeatures(setOf(UserFeatures.UPLOAD)), analyticsVisible = true)
        captureRoboImage("src/test/screenshots/$name.png") {
            AunTheme(mode = mode) {
                WithFixtureImages {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        Scaffold(
                            topBar = {
                                StudioTopBar(
                                    title = stringResource(StudioTab.UPLOAD.label),
                                    identity = channel,
                                    railAligned = false,
                                    onAccount = {}
                                )
                            }
                        ) { padding ->
                            StudioHome(
                                tabs = tabs,
                                selected = StudioTab.UPLOAD,
                                onSelect = {},
                                modifier = Modifier.padding(padding),
                                railHeader = { IdentityButton(channel, onClick = {}) }
                            ) {
                                CreatorTermsGate(
                                    saving = false,
                                    error = null,
                                    onOpenUrl = {},
                                    onAgree = {},
                                    webHost = "videos.alummahnow.net"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun creatorTermsGateChromeEnDarkPhone() = shootInChrome("creator_terms_gate_chrome_en_dark_phone", StoreLocale.EN, ThemeMode.DARK)

    @Test
    fun creatorTermsGateChromeArLightPhone() = shootInChrome("creator_terms_gate_chrome_ar_light_phone", StoreLocale.AR, ThemeMode.LIGHT)
}
