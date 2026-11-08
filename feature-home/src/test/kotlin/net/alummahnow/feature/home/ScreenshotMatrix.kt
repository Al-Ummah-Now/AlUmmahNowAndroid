package net.alummahnow.feature.home

import android.provider.Settings
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.robolectric.RuntimeEnvironment

/** en/ar × dark/light × phone/tablet. Robolectric needs ldrtl/ldltr spelled out (see CLAUDE.md). */
internal enum class Lang(val qualifier: String) { EN("en-ldltr"), AR("ar-ldrtl") }

internal enum class Device(val qualifier: String) {
    PHONE("w411dp-h891dp-port-xxhdpi"),
    TABLET("w1280dp-h800dp-land-xhdpi")
}

internal fun shoot(name: String, lang: Lang, mode: ThemeMode, device: Device = Device.PHONE, content: @Composable () -> Unit) {
    RuntimeEnvironment.setQualifiers("${lang.qualifier}-${device.qualifier}")
    // Infinite animations (lantern loader) never let Compose idle; reduced motion renders them static.
    Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    val file = "src/test/screenshots/${name}_${lang.name.lowercase()}_${mode.name.lowercase()}_${device.name.lowercase()}.png"
    captureRoboImage(file) { AunTheme(mode = mode) { content() } }
}
