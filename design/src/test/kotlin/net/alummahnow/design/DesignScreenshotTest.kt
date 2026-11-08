package net.alummahnow.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.design.components.ErrorState
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
    private val enItems = listOf(
        VideoCardData("1", "Introduction to Tafsir", null, 75, "Al Ummah Now — Lectures", 4167),
        VideoCardData("2", "Seerah in 30 lessons: Part 1", null, 3725, "Seerah Institute", 1)
    )
    private val arItems = listOf(
        VideoCardData("1", "مقدمة في التفسير", null, 75, "الأمة الآن — محاضرات", 4167),
        VideoCardData("2", "السيرة في ٣٠ درساً: الجزء الأول", null, 3725, "معهد السيرة", 2)
    )

    @Composable
    private fun Sample(mode: ThemeMode, items: List<VideoCardData>, title: String) {
        AunTheme(mode = mode) {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                VideoRail(title = title, items = items, onItemClick = {})
                ErrorState(message = stringResource(R.string.aun_error_network), onRetry = {})
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
