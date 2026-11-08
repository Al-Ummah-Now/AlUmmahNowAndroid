package net.alummahnow.viewer

import android.app.Application
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import net.alummahnow.core.bridge.auth.AuthState
import net.alummahnow.design.theme.AunTheme
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
@Config(sdk = [35], application = Application::class)
class AccountSectionTest {
    @get:Rule val compose = createComposeRule()

    private val signedIn = AuthState.SignedIn("u1", "aisha@example.com")

    private fun shoot(name: String, lang: String, mode: ThemeMode, content: @Composable () -> Unit) {
        // Robolectric needs ldrtl/ldltr spelled out (see CLAUDE.md).
        RuntimeEnvironment.setQualifiers("${if (lang == "ar") "ar-ldrtl" else "en-ldltr"}-w411dp-h891dp-port-xxhdpi")
        captureRoboImage("src/test/screenshots/account_${name}_${lang}_${mode.name.lowercase()}.png") {
            // Surface supplies the content colour the real bottom sheet provides.
            AunTheme(mode = mode) { Surface(Modifier.fillMaxWidth()) { content() } }
        }
    }

    private fun section(state: AuthState, failed: Boolean = false) = @Composable {
        AccountSection(state, signInAvailable = true, signInFailed = failed, onSignIn = {}, onSignOut = {})
    }

    @Test fun signedOutEnDark() = shoot("signed_out", "en", ThemeMode.DARK, section(AuthState.SignedOut))

    @Test fun signedOutArLight() = shoot("signed_out", "ar", ThemeMode.LIGHT, section(AuthState.SignedOut))

    @Test fun signedInEnLight() = shoot("signed_in", "en", ThemeMode.LIGHT, section(signedIn))

    @Test fun signedInArDark() = shoot("signed_in", "ar", ThemeMode.DARK, section(signedIn))

    @Test fun failedArDark() = shoot("failed", "ar", ThemeMode.DARK, section(AuthState.SignedOut, failed = true))

    @Test fun `sign in and sign out buttons call back, in Arabic`() {
        RuntimeEnvironment.setQualifiers("ar-ldrtl")
        var signIns = 0
        var signOuts = 0
        var state: AuthState by mutableStateOf<AuthState>(AuthState.SignedOut)
        compose.setContent {
            AunTheme {
                AccountSection(state, signInAvailable = true, signInFailed = false, onSignIn = { signIns++ }, onSignOut = { signOuts++ })
            }
        }
        compose.onNodeWithText("تسجيل الدخول").performClick()
        state = signedIn
        compose.onNodeWithText("aisha@example.com").assertExists()
        compose.onNodeWithText("تسجيل الخروج").performClick()
        assertEquals(1, signIns)
        assertEquals(1, signOuts)
    }

    @Test fun `nothing is shown when this build has no client and nobody is signed in`() {
        compose.setContent {
            AunTheme { AccountSection(AuthState.SignedOut, signInAvailable = false, signInFailed = false, onSignIn = {}, onSignOut = {}) }
        }
        compose.onNodeWithText("Sign in").assertDoesNotExist()
    }
}
