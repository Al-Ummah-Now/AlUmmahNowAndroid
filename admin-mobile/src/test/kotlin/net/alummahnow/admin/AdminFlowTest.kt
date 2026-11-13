package net.alummahnow.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import net.alummahnow.admin.ui.AdminApp
import net.alummahnow.admin.ui.OpenTarget
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.admin.CaptureEvent
import net.alummahnow.core.bridge.admin.FixtureReviewService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Admin app end to end against the fixture queue: no network, no pushes, no AI. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AdminFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val res get() = context.resources

    @Before fun setUp() {
        AunLog.enabled = false
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h891dp")
        // Robolectric's default network has no INTERNET capability; decisions are off offline.
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        org.robolectric.Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun launch(
        services: FakeServices,
        secure: Boolean = true,
        openTargets: MutableStateFlow<OpenTarget?> = MutableStateFlow(null),
        captures: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1),
        browser: MutableList<String> = mutableListOf()
    ) {
        compose.setContent {
            AdminApp(
                services = services,
                redirects = emptyFlow(),
                openTargets = openTargets,
                captures = captures,
                deviceSecure = { secure },
                openInBrowser = {
                    browser += it
                    true
                },
                requestUnlock = { services.lock.unlocked() },
                onLanguageChanged = {}
            )
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    @Test fun `no secure lock screen, no sign-in`() {
        launch(FakeServices(context, session = FakeSession(signedIn = false)), secure = false)
        compose.onNodeWithTag("needs-lock").assertIsDisplayed()
        compose.onNodeWithTag("sign-in").assertIsNotEnabled()
    }

    @Test fun `signed out opens sign-in in the browser`() {
        val browser = mutableListOf<String>()
        launch(FakeServices(context, session = FakeSession(signedIn = false)), browser = browser)
        compose.onNodeWithTag("sign-in").performClick()
        assertEquals(1, browser.size)
    }

    @Test fun `locked until the device unlock`() {
        val services = FakeServices(context, unlocked = false)
        launch(services)
        compose.onNodeWithTag("unlock").performClick()
        waitForTag("queue-list")
    }

    @Test fun `non-staff see the staff-only screen and can sign out`() {
        val services = FakeServices(context, fixture = FixtureReviewService(FixtureReviewService.Role.VIEWER))
        launch(services)
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("sign-out").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(res.getString(R.string.not_staff_title)).assertIsDisplayed()
        compose.onNodeWithTag("sign-out").performClick()
        compose.waitUntil(5_000) { services.session.signedOut }
    }

    @Test fun `approve from the review screen, then undo from the toast`() {
        val services = FakeServices(context)
        launch(services)
        waitForTag("row-fx-risk")
        compose.onNodeWithTag("row-fx-risk").performClick()
        waitForTag("approve")
        compose.waitUntil(5_000) { services.fixture.claims.contains("fx-risk") }
        compose.onNodeWithTag("approve").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(res.getString(R.string.undo)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("row-fx-risk").assertCountEqualsZero()
        compose.onNodeWithText(res.getString(R.string.undo)).performClick()
        waitForTag("row-fx-risk")
    }

    @Test fun `a conflict says who decided first`() {
        val services = FakeServices(context)
        launch(services)
        waitForTag("row-fx-conflict")
        compose.onNodeWithTag("row-fx-conflict").performClick()
        waitForTag("approve")
        compose.waitUntil(5_000) { services.fixture.claims.contains("fx-conflict") }
        compose.onNodeWithTag("approve").performClick()
        waitForTag("review-message")
        compose.onNodeWithText("Amina", substring = true).assertIsDisplayed()
    }

    @Test fun `NSFW items stay blurred until an audited Show`() {
        val services = FakeServices(context)
        launch(services)
        waitForTag("row-fx-nsfw")
        compose.onNodeWithTag("row-fx-nsfw").performClick()
        waitForTag("show")
        compose.onNodeWithTag("show").performClick()
        compose.waitUntil(5_000) { services.fixture.reveals == listOf("fx-nsfw:item") }
    }

    @Test fun `a notification's Claim opens the item, a capture attempt is audited`() {
        val services = FakeServices(context)
        val targets = MutableStateFlow<OpenTarget?>(null)
        val captures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        launch(services, openTargets = targets, captures = captures)
        waitForTag("queue-list")
        targets.value = OpenTarget("fx-appeal", claim = true)
        compose.waitUntil(5_000) { services.fixture.claims.contains("fx-appeal") }
        captures.tryEmit(Unit)
        compose.waitUntil(5_000) { services.fixture.captures == listOf(CaptureEvent.SCREENSHOT) }
        assertTrue(targets.value == null)
    }

    @Test fun `push without Firebase config says so in Settings`() {
        launch(FakeServices(context))
        waitForTag("settings")
        compose.onNodeWithTag("settings").performClick()
        waitForTag("alerts-status")
        compose.onNodeWithText(res.getString(R.string.alerts_not_configured)).assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertEquals(0, fetchSemanticsNodes().size)

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text))
}
