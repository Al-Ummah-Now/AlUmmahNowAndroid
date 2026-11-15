package net.alummahnow.studio

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.robolectric.RuntimeEnvironment

/**
 * Play store screenshots, written straight into Gradle Play Publisher's layout
 * (`src/main/play/listings/<locale>/graphics/<device>-screenshots/`, gitignored) so `publish…Listing` uploads them.
 * Off unless `-PaunStoreScreenshots=true` (the "Store screenshots" workflow and the release job): normal test runs
 * skip these. Sizes are Play's: phone 1080×1920, 7" 1200×1920, 10" 2560×1600, TV 1920×1080.
 */
internal enum class StoreDevice(val qualifiers: String, val dir: String) {
    PHONE("w360dp-h640dp-port-xxhdpi", "phone-screenshots"),
    TABLET_7("w600dp-h960dp-port-xhdpi", "tablet-screenshots"),
    TABLET_10("w1280dp-h800dp-land-xhdpi", "large-tablet-screenshots"),
    TV("w960dp-h540dp-land-television-xhdpi", "tv-screenshots")
}

/** Robolectric needs the layout direction spelled out (CLAUDE.md). [dir] is Play's locale folder. */
internal enum class StoreLocale(val qualifier: String, val dir: String) { EN("en-ldltr", "en-US"), AR("ar-ldrtl", "ar") }

internal object StoreShots {
    fun assumeEnabled() = assumeTrue(
        "store screenshots are generated with -PaunStoreScreenshots=true",
        System.getProperty("aun.storeScreenshots") == "true"
    )

    fun shoot(name: String, locale: StoreLocale, device: StoreDevice, mode: ThemeMode, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("${locale.qualifier}-${device.qualifiers}")
        // Infinite animations (lantern loader) never let Compose idle; reduced motion renders them static.
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val file = "src/main/play/listings/${locale.dir}/graphics/${device.dir}/$name.png"
        captureRoboImage(file, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)) {
            AunTheme(mode = mode) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() } }
        }
    }
}
