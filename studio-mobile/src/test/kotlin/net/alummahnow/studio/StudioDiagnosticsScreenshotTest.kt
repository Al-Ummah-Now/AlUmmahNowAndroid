package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class StudioDiagnosticsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun englishLight() = shoot(StoreLocale.EN, ThemeMode.LIGHT)

    @Test
    fun englishDark() = shoot(StoreLocale.EN, ThemeMode.DARK)

    @Test
    fun arabicLight() = shoot(StoreLocale.AR, ThemeMode.LIGHT)

    @Test
    fun arabicDark() = shoot(StoreLocale.AR, ThemeMode.DARK)

    private fun shoot(locale: StoreLocale, mode: ThemeMode) {
        RuntimeEnvironment.setQualifiers("${locale.qualifier}-${StoreDevice.PHONE.qualifiers}")
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val label = if (locale == StoreLocale.AR) "أمينة يوسف" else "Amina Yusuf"
        val account = StudioAccount("u1", label, "amina.yusuf@example.org", null, null, false)
        compose.setContent {
            AunTheme(mode = mode) {
                AccountSheet(
                    accounts = listOf(account),
                    activeUserId = account.userId,
                    channel = null,
                    onSwitchAccount = {},
                    onAddAccount = {},
                    onSignOut = {},
                    onSwitchChannel = {},
                    onDismiss = {},
                    webHost = "videos.alummahnow.net",
                    onTelemetryChanged = {}
                )
            }
        }
        compose.onNodeWithTag("studio-diagnostics").assertIsDisplayed()
        val name = "studio_diagnostics_${locale.name.lowercase()}_${mode.name.lowercase()}"
        compose.onNodeWithTag("account-sheet").captureRoboImage("src/test/screenshots/$name.png")
    }
}
