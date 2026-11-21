package net.alummahnow.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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
    // The same videos, views and art in both languages; one card without a thumbnail shows the placeholder. The brand
    // channel has an official name in each language, so each frame shows the one in its language (as on Home and Watch).
    private val enItems = listOf(
        VideoCardData("1", "Introduction to Tafsir", "${FIXTURE_THUMBS}tafsir.jpg", 1_830, "Al Ummah Now", 1_520),
        VideoCardData("2", "Fiqh of Prayer", "${FIXTURE_THUMBS}fiqh.jpg", 3_725, "Al Ummah Now", 2_140),
        VideoCardData("3", "Patience in hardship", null, 980, "Al Ummah Now", 640)
    )
    private val arItems = listOf(
        VideoCardData("1", "مقدمة في التفسير", "${FIXTURE_THUMBS}tafsir.jpg", 1_830, "الأمة الآن", 1_520),
        VideoCardData("2", "فقه الصلاة", "${FIXTURE_THUMBS}fiqh.jpg", 3_725, "الأمة الآن", 2_140),
        VideoCardData("3", "الصبر عند الشدائد", null, 980, "الأمة الآن", 640)
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
}
