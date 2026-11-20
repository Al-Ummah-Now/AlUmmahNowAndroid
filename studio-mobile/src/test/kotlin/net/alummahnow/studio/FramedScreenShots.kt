package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Studio screens inside the real Studio frame (the web header over the bar on phones, the rail on tablets), Light and
 * Dark, en + ar: what a person sees, as golden screenshots for review (the screens' own tests draw them without the frame).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class FramedScreenShots {
    private val screens = StoreScreenshots().screens.filter { it.first in setOf("content", "upload", "analytics") }

    private fun shoot(name: String, lang: StoreLocale, device: StoreDevice, mode: ThemeMode, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("${lang.qualifier}-${device.qualifiers}")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val l = if (lang == StoreLocale.AR) "ar" else "en"
        val file = "src/test/screenshots/framed_${name}_${l}_${mode.name.lowercase()}_${device.name.lowercase()}.png"
        captureRoboImage(file) {
            AunTheme(mode = mode) {
                WithFixtureImages { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
            }
        }
    }

    private fun set(lang: StoreLocale, device: StoreDevice, mode: ThemeMode) =
        screens.forEach { (name, screen) -> shoot(name, lang, device, mode, screen) }

    @Test fun phoneEnLight() = set(StoreLocale.EN, StoreDevice.PHONE, ThemeMode.LIGHT)

    @Test fun phoneEnDark() = set(StoreLocale.EN, StoreDevice.PHONE, ThemeMode.DARK)

    @Test fun phoneArLight() = set(StoreLocale.AR, StoreDevice.PHONE, ThemeMode.LIGHT)

    @Test fun phoneArDark() = set(StoreLocale.AR, StoreDevice.PHONE, ThemeMode.DARK)

    @Test fun tabletEnLight() = set(StoreLocale.EN, StoreDevice.TABLET_10, ThemeMode.LIGHT)

    @Test fun tabletArDark() = set(StoreLocale.AR, StoreDevice.TABLET_10, ThemeMode.DARK)
}
