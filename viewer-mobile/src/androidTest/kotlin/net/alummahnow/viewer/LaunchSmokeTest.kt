package net.alummahnow.viewer

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** The home header shows the brand in the app's language (works with any per-app locale). */
    @Test
    fun launchesAndShowsBrand() {
        val brand = compose.activity.getString(net.alummahnow.feature.home.R.string.home_brand)
        compose.waitUntil(30_000) { compose.onAllNodesWithText(brand).fetchSemanticsNodes().isNotEmpty() }
    }
}
