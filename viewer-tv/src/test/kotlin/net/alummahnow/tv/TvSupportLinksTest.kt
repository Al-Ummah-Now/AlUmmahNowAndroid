package net.alummahnow.tv

import android.provider.Settings
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import net.alummahnow.design.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class TvSupportLinksTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun englishLight() = verifyScreen("en", "en-ldltr", ThemeMode.LIGHT)

    @Test
    fun englishDark() = verifyScreen("en", "en-ldltr", ThemeMode.DARK)

    @Test
    fun arabicLight() = verifyScreen("ar", "ar-ldrtl", ThemeMode.LIGHT)

    @Test
    fun arabicDark() = verifyScreen("ar", "ar-ldrtl", ThemeMode.DARK)

    private fun verifyScreen(language: String, qualifiers: String, mode: ThemeMode) {
        RuntimeEnvironment.setQualifiers("$qualifiers-w960dp-h540dp-land-television-xhdpi")
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.setContent { TvApp(mode) }
        compose.onNodeWithText(app.getString(R.string.settings)).assertIsFocused().performClick()
        compose.onNodeWithText(app.getString(R.string.support_links)).assertIsFocused().performClick()

        val labels = if (language == "ar") {
            listOf("المساعدة", "تواصل معنا", "سياسة الخصوصية", "شروط الاستخدام")
        } else {
            listOf("Help", "Contact us", "Privacy policy", "Terms of Service")
        }
        val host = if (BuildConfig.ENV_NAME == "qa") "qa.alummahnow.net" else "alummahnow.net"
        val expected = listOf(
            "https://videos.$host/$language/help",
            "https://videos.$host/$language/contact",
            "https://www.$host/$language/privacy/",
            "https://www.$host/$language/terms/"
        )

        labels.zip(expected).forEachIndexed { index, (label, url) ->
            val button = compose.onNodeWithText(label).assertIsFocused()
            button.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithText(url).assertExists()
            val qr = compose.onNodeWithTag("support-qr")
            assertEquals(language == "ar", qr.getBoundsInRoot().left < button.getBoundsInRoot().left)
            assertQrScreenshot(url)
            if (index < labels.lastIndex) button.performKeyInput { pressKey(Key.DirectionDown) }
        }
    }

    private fun assertQrScreenshot(expectedUrl: String) {
        val frame = compose.onNodeWithTag("support-qr").captureToImage()
        val pixels = frame.toPixelMap()
        val colors = IntArray(frame.width * frame.height) { index ->
            pixels[index % frame.width, index / frame.width].toArgb()
        }
        assertEquals(WHITE, colors.first())
        assertEquals(WHITE, colors.last())
        val source = RGBLuminanceSource(frame.width, frame.height, colors)
        assertEquals(expectedUrl, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
    }

    private companion object {
        const val WHITE = -1
    }
}
