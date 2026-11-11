package net.alummahnow.studio

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** Signed out: the sign-in button; signed in: the account button. Either way the app is up. */
    @Test
    fun launches() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("signin-button").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("account-button").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
