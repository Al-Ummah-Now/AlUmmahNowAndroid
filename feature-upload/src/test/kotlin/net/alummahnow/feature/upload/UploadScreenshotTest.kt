package net.alummahnow.feature.upload

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.ZonedDateTime
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class UploadScreenshotTest {
    private val state = UploadUiState(
        files = listOf(PickedFile("u1", "dars-01.mp4", 734_003_200, "video/mp4"), PickedFile("u2", "dars-02.mp4", 512_000_000, "video/mp4")),
        form = SetForAll(titlePattern = "Tafsir {{nn}}", category = "quran_tafsir", language = "ar", playlistIds = setOf("p1"), tagsText = "tafsir, قرآن"),
        playlists = listOf(StudioPlaylist("p1", "Tafsir series", true), StudioPlaylist("p2", "Khutbahs", false)),
        queue = listOf(
            UploadItem("q1", "Ramadan reminder", UploadPhase.UPLOADING, 300_000_000, 700_000_000, null, null),
            UploadItem("q2", "Seerah 4", UploadPhase.PAUSED, 50_000_000, 400_000_000, null, null),
            UploadItem("q3", "Seerah 3", UploadPhase.FAILED, 0, 400_000_000, null, "network"),
            UploadItem("q4", "Seerah 2", UploadPhase.DONE, 400_000_000, 400_000_000, "v", null)
        )
    )
    private val actions = UploadActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, { ZonedDateTime.now() })
    private val screen = @Composable { UploadScreen(state, listOf("Tafsir 01", "Tafsir 02"), actions) }

    private fun shoot(name: String, qualifiers: String, mode: ThemeMode) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        captureRoboImage("src/test/screenshots/$name.png") { AunTheme(mode = mode) { screen() } }
    }

    @Test fun enLight() = shoot("upload_en_light_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun enDark() = shoot("upload_en_dark_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun arDark() = shoot("upload_ar_dark_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun arLight() = shoot("upload_ar_light_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun tabletArDark() = shoot("upload_ar_dark_tablet", "ar-ldrtl-w1280dp-h800dp-land-xhdpi", ThemeMode.DARK)
}
