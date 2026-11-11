package net.alummahnow.feature.studio

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.feature.studio.channels.AccountSheet
import net.alummahnow.feature.studio.channels.ChannelPickerScreen
import net.alummahnow.feature.studio.channels.ChannelsUiState
import net.alummahnow.feature.studio.channels.ChannelsViewModel
import net.alummahnow.feature.studio.dashboard.DashboardScreen
import net.alummahnow.feature.studio.dashboard.DashboardUiState
import net.alummahnow.feature.studio.signin.SignInScreen
import net.alummahnow.feature.studio.signin.SignInUiState
import net.alummahnow.feature.studio.signin.SignInViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class SignInAndChannelsTest {
    @get:Rule val compose = createComposeRule()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    // --- ViewModels ---

    @Test
    fun `sign-in opens the browser, completes, and reports a cancelled redirect`() = runTest {
        val session = FakeSession(signedIn = false)
        val vm = SignInViewModel(session)
        assertEquals("https://auth.example/authorize?mode=DEFAULT", vm.start(SignInMode.DEFAULT))
        assertEquals(SignInUiState.InBrowser, vm.state.value)
        var done = false
        vm.complete("alummahnow-studio://auth/callback?code=x&state=y") { done = true }
        assertTrue(done)
        assertEquals("u1", session.active.value?.userId)

        session.completeError = IllegalStateException("denied")
        vm.complete("alummahnow-studio://auth/callback?error=access_denied&state=y")
        assertEquals(SignInUiState.Error(R.string.studio_signin_cancelled), vm.state.value)
        session.completeError = CoreError.Network(java.io.IOException())
        vm.complete("alummahnow-studio://auth/callback?code=x&state=y")
        assertEquals(SignInUiState.Error(net.alummahnow.design.R.string.aun_error_network), vm.state.value)
    }

    @Test
    fun `closing the browser returns to idle`() {
        val vm = SignInViewModel(FakeSession(signedIn = false))
        vm.start(SignInMode.ADD_ACCOUNT)
        vm.browserClosed()
        assertEquals(SignInUiState.Idle, vm.state.value)
    }

    @Test
    fun `one channel is selected automatically, several ask, the choice is remembered`() = runTest {
        val session = FakeSession()
        val repo = FakeRepository()
        val vm = ChannelsViewModel(session, repo)
        val s = vm.state.value as ChannelsUiState.Content
        assertNull(s.selectedId)
        vm.select("c2")
        assertEquals("c2", (vm.state.value as ChannelsUiState.Content).selected?.id)
        assertEquals("c2", session.active.value?.activeChannelId)

        repo.channels = repo.channels.take(1)
        val single = ChannelsViewModel(FakeSession(), repo)
        assertEquals("c1", (single.state.value as ChannelsUiState.Content).selectedId)

        repo.channelsError = CoreError.Network(java.io.IOException())
        val failed = ChannelsViewModel(FakeSession(), repo)
        assertTrue(failed.state.value is ChannelsUiState.Error)
    }

    // --- UI behaviour ---

    @Test
    fun `sign-out asks first and Cancel keeps the account`() {
        RuntimeEnvironment.setQualifiers("en-ldltr")
        val signedOut = mutableListOf<String>()
        compose.setContent {
            AunTheme {
                AccountSheet(listOf(sampleAccount), "u1", sampleChannels[0], {}, {}, { signedOut += it }, {}, {})
            }
        }
        compose.onNodeWithTag("sign-out-u1").performClick()
        compose.onNodeWithTag("confirm-dialog").assertExists()
        compose.onNodeWithTag("confirm-cancel").performClick()
        assertTrue(signedOut.isEmpty())
        compose.onNodeWithTag("sign-out-u1").performClick()
        compose.onNodeWithTag("confirm-ok").performClick()
        assertEquals(listOf("u1"), signedOut)
    }

    @Test
    fun `picker shows Arabic channel names in Arabic`() {
        RuntimeEnvironment.setQualifiers("ar-ldrtl")
        var picked: String? = null
        compose.setContent { AunTheme { ChannelPickerScreen(ChannelsUiState.Content(sampleChannels, null), { picked = it }, {}) } }
        compose.onNodeWithText("أناشيد").performClick()
        assertEquals("c2", picked)
    }

    // --- Screenshots ---

    private val signIn = @androidx.compose.runtime.Composable { SignInScreen(SignInUiState.Idle, {}, null, null) }
    private val signInError = @androidx.compose.runtime.Composable {
        SignInScreen(SignInUiState.Error(R.string.studio_signin_failed), {}, null, null)
    }
    private val picker = @androidx.compose.runtime.Composable { ChannelPickerScreen(ChannelsUiState.Content(sampleChannels, "c1"), {}, {}) }
    private val dashboard = @androidx.compose.runtime.Composable {
        DashboardScreen(
            sampleChannels[0],
            DashboardUiState.Content(
                net.alummahnow.core.bridge.studio.CreatorAccess(true, false, null),
                net.alummahnow.core.bridge.studio.ContentCounts(12, 2, 3, 1, 1)
            ),
            {}
        )
    }

    @Test fun signInEnLight() = shoot("signin", Lang.EN, ThemeMode.LIGHT, content = signIn)

    @Test fun signInEnDark() = shoot("signin", Lang.EN, ThemeMode.DARK, content = signIn)

    @Test fun signInArDark() = shoot("signin", Lang.AR, ThemeMode.DARK, content = signIn)

    @Test fun signInArLight() = shoot("signin", Lang.AR, ThemeMode.LIGHT, content = signIn)

    @Test fun signInErrorArDark() = shoot("signin_error", Lang.AR, ThemeMode.DARK, content = signInError)

    @Test fun pickerEnLight() = shoot("channels", Lang.EN, ThemeMode.LIGHT, content = picker)

    @Test fun pickerEnDark() = shoot("channels", Lang.EN, ThemeMode.DARK, content = picker)

    @Test fun pickerArDark() = shoot("channels", Lang.AR, ThemeMode.DARK, content = picker)

    @Test fun pickerArLight() = shoot("channels", Lang.AR, ThemeMode.LIGHT, content = picker)

    @Test fun pickerTabletArDark() = shoot("channels", Lang.AR, ThemeMode.DARK, Device.TABLET, picker)

    @Test fun dashboardEnDark() = shoot("dashboard", Lang.EN, ThemeMode.DARK, content = dashboard)

    @Test fun dashboardArLight() = shoot("dashboard", Lang.AR, ThemeMode.LIGHT, content = dashboard)

    @Test fun dashboardTabletEnLight() = shoot("dashboard", Lang.EN, ThemeMode.LIGHT, Device.TABLET, dashboard)
}
