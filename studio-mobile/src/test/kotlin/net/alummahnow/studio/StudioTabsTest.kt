package net.alummahnow.studio

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.design.theme.AunTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Studio's sections follow GET /api/me/features: what is off is not there at all. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = android.app.Application::class)
class StudioTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `every section when upload is on and analytics answered`() {
        assertEquals(StudioTab.entries, studioTabs(UserFeatures(setOf(UserFeatures.UPLOAD)), analyticsVisible = true))
    }

    @Test fun `no Upload without the upload feature`() {
        assertEquals(
            listOf(StudioTab.DASHBOARD, StudioTab.CONTENT, StudioTab.ANALYTICS, StudioTab.EDITOR),
            studioTabs(UserFeatures(setOf("live")), analyticsVisible = true)
        )
    }

    @Test fun `no Analytics when the server said it is not there`() {
        assertEquals(
            listOf(StudioTab.DASHBOARD, StudioTab.CONTENT, StudioTab.UPLOAD, StudioTab.EDITOR),
            studioTabs(UserFeatures(setOf(UserFeatures.UPLOAD)), analyticsVisible = false)
        )
    }

    @Test fun `before features are read only Dashboard, Content and the on-device Editor`() {
        assertEquals(
            listOf(StudioTab.DASHBOARD, StudioTab.CONTENT, StudioTab.EDITOR),
            studioTabs(UserFeatures.NONE, analyticsVisible = false)
        )
    }

    @Test fun `with nothing left on the Dashboard, Content comes first`() {
        val tabs = studioTabs(UserFeatures.NONE, analyticsVisible = false, dashboardVisible = false)
        assertEquals(listOf(StudioTab.CONTENT, StudioTab.EDITOR), tabs)
        assertEquals(StudioTab.CONTENT, tabs.first())
    }

    @Test fun `a single section shows no navigation bar`() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h891dp")
        compose.setContent { AunTheme { StudioHome(listOf(StudioTab.CONTENT), StudioTab.CONTENT, onSelect = {}) { } } }
        compose.onAllNodesWithTag("tab-content").assertCountEquals(0)
    }

    @Test fun `the navigation bar has no Upload or Analytics item when they are off`() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h891dp")
        compose.setContent {
            AunTheme { StudioHome(studioTabs(UserFeatures.NONE, false), StudioTab.DASHBOARD, onSelect = {}) { } }
        }
        compose.onNodeWithTag("tab-dashboard").assertExists()
        compose.onNodeWithTag("tab-content").assertExists()
        compose.onAllNodesWithTag("tab-upload").assertCountEquals(0)
        compose.onAllNodesWithTag("tab-analytics").assertCountEquals(0)
    }

    @Test fun `the navigation bar has Upload and Analytics when they are on`() {
        RuntimeEnvironment.setQualifiers("en-ldltr-w411dp-h891dp")
        compose.setContent {
            AunTheme { StudioHome(studioTabs(UserFeatures(setOf(UserFeatures.UPLOAD)), true), StudioTab.DASHBOARD, onSelect = {}) { } }
        }
        compose.onNodeWithTag("tab-upload").assertExists()
        compose.onNodeWithTag("tab-analytics").assertExists()
    }
}
