package net.alummahnow.feature.passkey

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.auth.passkey.PasskeyError
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PasskeyUiTest {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() {
        AunLog.enabled = false
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val res get() = RuntimeEnvironment.getApplication().resources

    /** The sign-in screen's two buttons wired to a [PasskeyViewModel], with the fake Credential Manager. */
    @Composable
    private fun SignIn(vm: PasskeyViewModel, auth: FakeAuthenticator, onOtherWay: () -> Unit) {
        CompositionLocalProvider(LocalPasskeyAuthenticator provides { auth }) {
            AunTheme {
                val authenticator = rememberPasskeyAuthenticator()
                Column {
                    PasskeySignInOptions(
                        supported = authenticator.isSupported,
                        enabled = true,
                        working = false,
                        fallbackLabel = "Sign in",
                        onPasskey = { vm.signIn(authenticator) },
                        onOtherWay = onOtherWay
                    )
                }
                PasskeyHost(vm, onOtherWay = onOtherWay, onSignInAgain = {})
            }
        }
    }

    @Test fun `passkey button signs in without the browser`() {
        val session = FakeSession()
        val auth = FakeAuthenticator()
        var browser = 0
        val vm = PasskeyViewModel(session)
        compose.setContent { SignIn(vm, auth) { browser++ } }
        compose.onNodeWithText(res.getString(R.string.passkey_sign_in)).performClick()
        compose.waitForIdle()
        assertEquals(1, auth.signIns)
        assertEquals("u1", session.active.value?.userId)
        assertEquals(0, browser)
        compose.onNodeWithTag("passkey-dialog").assertDoesNotExist()
    }

    @Test fun `sign in another way opens the browser sign-in`() {
        val auth = FakeAuthenticator()
        var browser = 0
        val vm = PasskeyViewModel(FakeSession())
        compose.setContent { SignIn(vm, auth) { browser++ } }
        compose.onNodeWithText(res.getString(R.string.passkey_sign_in_other)).performClick()
        assertEquals(1, browser)
        assertEquals(0, auth.signIns)
    }

    @Test fun `no passkey offers sign in another way`() {
        val auth = FakeAuthenticator().apply { onSignIn = { throw PasskeyError.NoCredential() } }
        var browser = 0
        val vm = PasskeyViewModel(FakeSession())
        compose.setContent { SignIn(vm, auth) { browser++ } }
        compose.onNodeWithTag("signin-passkey").performClick()
        compose.onNodeWithText(res.getString(R.string.passkey_none)).assertIsDisplayed()
        compose.onNodeWithTag("passkey-dialog-confirm").performClick()
        assertEquals(1, browser)
        compose.onNodeWithTag("passkey-dialog").assertDoesNotExist()
    }

    @Test fun `cancel shows nothing`() {
        val auth = FakeAuthenticator().apply { onSignIn = { throw PasskeyError.Cancelled() } }
        val vm = PasskeyViewModel(FakeSession())
        compose.setContent { SignIn(vm, auth) {} }
        compose.onNodeWithTag("signin-passkey").performClick()
        compose.onNodeWithTag("passkey-dialog").assertDoesNotExist()
    }

    @Test fun `a failed passkey can be tried again`() {
        val auth = FakeAuthenticator().apply { onSignIn = { throw PasskeyError.Failed() } }
        val vm = PasskeyViewModel(FakeSession())
        compose.setContent { SignIn(vm, auth) {} }
        compose.onNodeWithTag("signin-passkey").performClick()
        compose.onNodeWithText(res.getString(R.string.passkey_signin_failed)).assertIsDisplayed()
        auth.onSignIn = { """{"id":"cred"}""" }
        compose.onNodeWithTag("passkey-dialog-dismiss").performClick()
        compose.waitForIdle()
        assertEquals(2, auth.signIns)
        compose.onNodeWithTag("passkey-dialog").assertDoesNotExist()
    }

    @Test fun `unsupported devices only get the browser button`() {
        val auth = FakeAuthenticator(isSupported = false)
        var browser = 0
        val vm = PasskeyViewModel(FakeSession())
        compose.setContent { SignIn(vm, auth) { browser++ } }
        compose.onNodeWithTag("signin-passkey").assertDoesNotExist()
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(1, browser)
    }

    @Test fun `disabled while busy`() {
        compose.setContent {
            AunTheme { PasskeySignInOptions(true, enabled = true, working = true, fallbackLabel = "x", onPasskey = {}, onOtherWay = {}) }
        }
        compose.onNodeWithTag("signin-passkey").assertIsNotEnabled()
        compose.onNodeWithText(res.getString(R.string.passkey_working)).assertIsDisplayed()
    }

    @Test fun `add a passkey after signing in again`() {
        val session = FakeSession(signedIn = true).apply { serverErrors += PasskeyError.StepUpRequired() }
        val vm = PasskeyViewModel(session)
        val auth = FakeAuthenticator()
        var stepUps = 0
        compose.setContent {
            CompositionLocalProvider(LocalPasskeyAuthenticator provides { auth }) {
                AunTheme { PasskeyHost(vm, onOtherWay = {}, onSignInAgain = { stepUps++ }) }
            }
        }
        vm.add(auth)
        compose.onNodeWithText(res.getString(R.string.passkey_stale)).assertIsDisplayed()
        compose.onNodeWithTag("passkey-dialog-confirm").performClick()
        assertEquals(1, stepUps)
        compose.onNodeWithTag("passkey-dialog").assertDoesNotExist()
        // The browser re-sign-in came back as the same person: the add runs again by itself.
        vm.signedIn(account("u1"))
        compose.waitForIdle()
        assertEquals(1, auth.creates)
        compose.onNodeWithText(res.getString(R.string.passkey_added)).assertIsDisplayed()
    }

    // --- Screenshots: en/ar × light/dark (Robolectric needs ldrtl/ldltr spelled out) ---

    private fun shoot(name: String, lang: String, mode: ThemeMode, content: @Composable () -> Unit) {
        val qualifier = if (lang == "ar") "ar-ldrtl" else "en-ldltr"
        RuntimeEnvironment.setQualifiers("$qualifier-w411dp-h891dp-port-xxhdpi")
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        captureRoboImage("src/test/screenshots/${name}_${lang}_${mode.name.lowercase()}.png") {
            AunTheme(mode = mode) { Surface { content() } }
        }
    }

    private fun matrix(name: String, content: @Composable () -> Unit) {
        for (lang in listOf("en", "ar")) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) shoot(name, lang, mode, content)
    }

    @Test fun `screenshots - sign-in options`() = matrix("passkey_signin") {
        PasskeySignInOptions(true, enabled = true, working = false, fallbackLabel = "", onPasskey = {}, onOtherWay = {})
    }

    @Test fun `screenshots - no passkey dialog`() = matrix("passkey_dialog_none") {
        PasskeyDialog(PasskeyUiState.Problem(PasskeyProblem.NO_PASSKEY), {}, {}, {}, {})
    }

    @Test fun `screenshots - sign in again dialog`() = matrix("passkey_dialog_stale") {
        PasskeyDialog(PasskeyUiState.NeedsSignInAgain, {}, {}, {}, {})
    }
}
