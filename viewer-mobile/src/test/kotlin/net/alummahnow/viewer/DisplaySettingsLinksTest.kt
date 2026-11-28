package net.alummahnow.viewer

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class DisplaySettingsLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `DisplaySettingsSheetContent routes all four links through passed QA webHost`() {
        RuntimeEnvironment.setQualifiers("ar-ldrtl")
        val opened = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            AunTheme {
                DisplaySettingsSheetContent(
                    theme = ThemeMode.DARK,
                    language = "ar",
                    languageSwitchSupported = true,
                    onTheme = {},
                    onLanguage = {},
                    onDismiss = { dismissed++ },
                    onOpenUrl = { opened += it },
                    webHost = "videos.qa.alummahnow.net"
                )
            }
        }
        compose.onNodeWithTag("settings-help").performScrollTo().performClick()
        compose.onNodeWithTag("settings-contact").performScrollTo().performClick()
        compose.onNodeWithTag("settings-privacy").performScrollTo().performClick()
        compose.onNodeWithTag("settings-terms").performScrollTo().performClick()
        assertEquals(4, dismissed)
        assertEquals(
            listOf(
                "https://videos.qa.alummahnow.net/ar/help",
                "https://videos.qa.alummahnow.net/ar/contact",
                "https://www.qa.alummahnow.net/ar/privacy/",
                "https://www.qa.alummahnow.net/ar/terms/"
            ),
            opened
        )
    }

    private fun shootSheet(name: String, lang: String, mode: ThemeMode, enabled: Boolean = false) {
        val dir = if (lang == "ar") "ar-ldrtl" else "en-ldltr"
        RuntimeEnvironment.setQualifiers("$dir-w411dp-h891dp-port-xxhdpi")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        captureRoboImage("src/test/screenshots/$name.png") {
            AunTheme(mode = mode) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    ) {
                        DisplaySettingsSheetContent(
                            theme = mode,
                            language = lang,
                            languageSwitchSupported = true,
                            onTheme = {},
                            onLanguage = {},
                            onDismiss = {},
                            onOpenUrl = {},
                            webHost = "videos.alummahnow.net",
                            telemetryEnabled = enabled,
                            onTelemetryChanged = {}
                        )
                    }
                }
            }
        }
    }

    @Test
    fun viewerSettingsLinksEnDarkPhone() = shootSheet("viewer_settings_links_en_dark_phone", "en", ThemeMode.DARK)

    @Test
    fun viewerSettingsLinksArLightPhone() = shootSheet("viewer_settings_links_ar_light_phone", "ar", ThemeMode.LIGHT)

    @Test
    fun viewerSettingsLinksEnLightPhone() = shootSheet("viewer_settings_links_en_light_phone", "en", ThemeMode.LIGHT)

    @Test
    fun viewerSettingsLinksArDarkPhone() = shootSheet("viewer_settings_links_ar_dark_phone", "ar", ThemeMode.DARK)

    @Test
    fun diagnosticsEnDarkEnabled() = shootSheet("viewer_settings_links_en_dark_phone_on", "en", ThemeMode.DARK, enabled = true)

    @Test
    fun diagnosticsArDarkEnabled() = shootSheet("viewer_settings_links_ar_dark_phone_on", "ar", ThemeMode.DARK, enabled = true)
}
