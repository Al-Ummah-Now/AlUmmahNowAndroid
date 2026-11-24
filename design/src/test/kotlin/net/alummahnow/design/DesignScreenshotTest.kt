package net.alummahnow.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.VideoCardData
import net.alummahnow.design.components.VideoRail
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class DesignScreenshotTest {
    // The brand channel's videos with the same lifetime views and art as Home, Watch and Studio (BrandChannel in
    // core-bridge/src/test/fixtures; :design does not see core-bridge), ordered by views as Trending is. The brand
    // channel has an official name in each language, so each frame shows the one in its language.
    private val enItems = listOf(
        VideoCardData("3", "Fiqh of Prayer", "${FIXTURE_THUMBS}fiqh.jpg", 3_725, "Al Ummah Now", 2_460),
        VideoCardData("1", "Introduction to Tafsir", "${FIXTURE_THUMBS}tafsir.jpg", 1_830, "Al Ummah Now", 1_870),
        VideoCardData("8", "Patience in hardship", "${FIXTURE_THUMBS}patience.jpg", 980, "Al Ummah Now", 640)
    )
    private val arItems = listOf(
        VideoCardData("3", "فقه الصلاة", "${FIXTURE_THUMBS}fiqh.jpg", 3_725, "الأمة الآن", 2_460),
        VideoCardData("1", "مقدمة في التفسير", "${FIXTURE_THUMBS}tafsir.jpg", 1_830, "الأمة الآن", 1_870),
        VideoCardData("8", "الصبر عند الشدائد", "${FIXTURE_THUMBS}patience.jpg", 980, "الأمة الآن", 640)
    )

    @Composable
    private fun Sample(mode: ThemeMode, items: List<VideoCardData>, title: String) {
        AunTheme(mode = mode) {
            WithFixtureImages {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    VideoRail(title = title, items = items, onItemClick = {})
                }
            }
        }
    }

    private fun shoot(name: String, qualifier: String, mode: ThemeMode, items: List<VideoCardData>, title: String) {
        RuntimeEnvironment.setQualifiers("+$qualifier")
        captureRoboImage("src/test/screenshots/design_$name.png") { Sample(mode, items, title) }
    }

    @Test fun railEnDark() = shoot("rail_en_dark", "en-ldltr", ThemeMode.DARK, enItems, "Trending")

    @Test fun railEnLight() = shoot("rail_en_light", "en-ldltr", ThemeMode.LIGHT, enItems, "Trending")

    @Test fun railArDark() = shoot("rail_ar_dark", "ar-ldrtl", ThemeMode.DARK, arItems, "الأكثر رواجاً")

    @Test fun railArLight() = shoot("rail_ar_light", "ar-ldrtl", ThemeMode.LIGHT, arItems, "الأكثر رواجاً")

    /** Every button kind, enabled and disabled, on the page and on a card. */
    private fun shootButtons(name: String, qualifier: String, mode: ThemeMode, label: String) {
        RuntimeEnvironment.setQualifiers("+$qualifier")
        captureRoboImage("src/test/screenshots/design_buttons_$name.png") {
            AunTheme(mode = mode) {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FanousButtonKind.entries.forEach { kind ->
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            FanousButton(label, {}, kind = kind)
                            FanousButton(label, {}, kind = kind, enabled = false)
                        }
                    }
                }
            }
        }
    }

    @Test fun buttonsEnDark() = shootButtons("en_dark", "en-ldltr", ThemeMode.DARK, "Delete")

    @Test fun buttonsEnLight() = shootButtons("en_light", "en-ldltr", ThemeMode.LIGHT, "Delete")

    @Test fun buttonsArDark() = shootButtons("ar_dark", "ar-ldrtl", ThemeMode.DARK, "حذف")

    @Test fun buttonsArLight() = shootButtons("ar_light", "ar-ldrtl", ThemeMode.LIGHT, "حذف")
}
