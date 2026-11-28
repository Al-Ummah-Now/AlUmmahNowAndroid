package net.alummahnow.tv

import android.provider.Settings
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TvDiagnosticsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun englishLight() = shoot(StoreLocale.EN, ThemeMode.LIGHT)

    @Test
    fun englishDark() = shoot(StoreLocale.EN, ThemeMode.DARK)

    @Test
    fun arabicLight() = shoot(StoreLocale.AR, ThemeMode.LIGHT)

    @Test
    fun arabicDark() = shoot(StoreLocale.AR, ThemeMode.DARK)

    @Test
    fun englishDarkEnabled() = shoot(StoreLocale.EN, ThemeMode.DARK, enabled = true)

    @Test
    fun arabicDarkEnabled() = shoot(StoreLocale.AR, ThemeMode.DARK, enabled = true)

    private fun shoot(locale: StoreLocale, mode: ThemeMode, enabled: Boolean = false) {
        RuntimeEnvironment.setQualifiers("${locale.qualifier}-${StoreDevice.TV.qualifiers}")
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { TvApp(mode = mode, telemetryEnabled = enabled, onToggleTelemetry = {}) }
        compose.onNodeWithText(app.getString(R.string.settings)).assertIsFocused().performClick()
        compose.onNodeWithText(app.getString(R.string.support_links)).assertIsFocused().performKeyInput {
            pressKey(Key.DirectionDown)
        }
        val label = app.getString(DesignR.string.aun_diagnostics_toggle)
        val state = app.getString(if (enabled) DesignR.string.aun_diagnostics_on else DesignR.string.aun_diagnostics_off)
        compose.onNodeWithText("$label: $state").assertIsFocused()
        compose.onNodeWithText(app.getString(DesignR.string.aun_diagnostics_description)).assertIsDisplayed()
        val suffix = if (enabled) "_on" else ""
        val name = "tv_diagnostics_${locale.name.lowercase()}_${mode.name.lowercase()}$suffix"
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
