package net.alummahnow.feature.upload

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale
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
    private val playlists = listOf(
        StudioPlaylist("p1", "Original series", true, mapOf("en" to "Tafsir series", "ar" to "سلسلة التفسير")),
        StudioPlaylist("p2", "Original talks", false, mapOf("en" to "Khutbahs", "ar" to "الخطب"))
    )

    /**
     * Creator content (files, titles, playlists, tags, queue) in the frame's language. Generated titles come from
     * TitlePattern, with the frame language's digits («التفسير ٠١», "Tafsir 01").
     */
    private fun state(arabic: Boolean) = if (arabic) {
        UploadUiState(
            files = listOf(
                PickedFile("u1", "مقدمة-التفسير.mp4", 734_003_200, "video/mp4"),
                PickedFile("u2", "أسباب-النزول.mp4", 512_000_000, "video/mp4")
            ),
            form = SetForAll(
                titlePattern = "التفسير {n}",
                padNumbers = true,
                category = "quran_tafsir",
                language = "ar",
                playlistIds = setOf("p1"),
                tagsText = "تفسير، قرآن"
            ),
            playlists = playlists,
            queue = listOf(
                UploadItem("q1", "تذكير رمضان", UploadPhase.UPLOADING, 300_000_000, 700_000_000, null, null),
                UploadItem("q2", "السيرة: الهجرة", UploadPhase.PAUSED, 50_000_000, 400_000_000, null, null),
                UploadItem("q3", "السيرة: غزوة أحد", UploadPhase.FAILED, 0, 400_000_000, null, "network"),
                UploadItem("q4", "السيرة: غزوة بدر", UploadPhase.DONE, 400_000_000, 400_000_000, "v", null)
            )
        )
    } else {
        UploadUiState(
            files = listOf(
                PickedFile("u1", "tafsir-01.mp4", 734_003_200, "video/mp4"),
                PickedFile("u2", "tafsir-02.mp4", 512_000_000, "video/mp4")
            ),
            form = SetForAll(
                titlePattern = "Tafsir {n}",
                padNumbers = true,
                category = "quran_tafsir",
                language = "ar",
                playlistIds = setOf("p1"),
                tagsText = "tafsir, quran"
            ),
            playlists = playlists,
            queue = listOf(
                UploadItem("q1", "Ramadan reminder", UploadPhase.UPLOADING, 300_000_000, 700_000_000, null, null),
                UploadItem("q2", "Seerah: the Hijrah", UploadPhase.PAUSED, 50_000_000, 400_000_000, null, null),
                UploadItem("q3", "Seerah: the Battle of Uhud", UploadPhase.FAILED, 0, 400_000_000, null, "network"),
                UploadItem("q4", "Seerah: the Battle of Badr", UploadPhase.DONE, 400_000_000, 400_000_000, "v", null)
            )
        )
    }
    private val actions = UploadActions({}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {}, {}, { ZonedDateTime.now() })

    private fun screen(arabic: Boolean) = @Composable {
        val state = state(arabic)
        val locale = Locale.forLanguageTag(if (arabic) "ar" else "en")
        val titles = state.files.indices.map { i ->
            TitlePattern.apply(state.form.titlePattern, state.files[i].name, i, LocalDate.of(2026, 11, 21), "", locale, pad = 2)
        }
        UploadScreen(state, titles, actions)
    }

    /** A refused upload says why: the Terms (known), or the server's refusal (reason not known). */
    private fun refused(arabic: Boolean) = @Composable {
        val (first, second) = if (arabic) "تذكير رمضان" to "السيرة: الهجرة" else "Ramadan reminder" to "Seerah: the Hijrah"
        val queue = listOf(
            UploadItem("q1", first, UploadPhase.FAILED, 0, 400_000_000, null, "forbidden: terms"),
            UploadItem("q2", second, UploadPhase.FAILED, 0, 400_000_000, null, "forbidden: role")
        )
        UploadScreen(UploadUiState(queue = queue), emptyList(), actions, termsRefused = setOf("q1"))
    }

    private fun shootRefused(name: String, qualifiers: String, mode: ThemeMode, fontScale: Float = 1f) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val screen = refused(arabic = qualifiers.startsWith("ar"))
        captureRoboImage("src/test/screenshots/$name.png") { AunTheme(mode = mode) { screen() } }
    }

    @Test fun refusedEnDark() = shootRefused("upload_refused_en_dark_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun refusedEnLight() = shootRefused("upload_refused_en_light_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun refusedArDark() = shootRefused("upload_refused_ar_dark_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun refusedArLight() = shootRefused("upload_refused_ar_light_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun refusedFont200ArLight() =
        shootRefused("upload_refused_font200_ar_light_phone", "ar-ldrtl-w411dp-h2400dp-port-xxhdpi", ThemeMode.LIGHT, fontScale = 2f)

    private fun shoot(name: String, qualifiers: String, mode: ThemeMode) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val screen = screen(arabic = qualifiers.startsWith("ar"))
        captureRoboImage("src/test/screenshots/$name.png") { AunTheme(mode = mode) { screen() } }
    }

    @Test fun enLight() = shoot("upload_en_light_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun enDark() = shoot("upload_en_dark_phone", "en-ldltr-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun arDark() = shoot("upload_ar_dark_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.DARK)

    @Test fun arLight() = shoot("upload_ar_light_phone", "ar-ldrtl-w411dp-h1600dp-port-xxhdpi", ThemeMode.LIGHT)

    @Test fun tabletArDark() = shoot("upload_ar_dark_tablet", "ar-ldrtl-w1280dp-h800dp-land-xhdpi", ThemeMode.DARK)
}
