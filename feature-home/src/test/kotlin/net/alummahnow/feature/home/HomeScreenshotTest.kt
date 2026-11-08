package net.alummahnow.feature.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.design.components.ErrorKind
import net.alummahnow.design.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val content = HomeUiState.Content(listOf(Rail(RailKind.TRENDING, sampleVideos), Rail(RailKind.RECENT, sampleVideos.reversed())))

    private fun home(state: HomeUiState = content) = @androidx.compose.runtime.Composable {
        HomeScreen(state, onVideoClick = {}, onRetry = {}, onOpenSettings = {})
    }

    @Test fun phoneEnDark() = shoot("home", Lang.EN, ThemeMode.DARK, content = home())

    @Test fun phoneEnLight() = shoot("home", Lang.EN, ThemeMode.LIGHT, content = home())

    @Test fun phoneArDark() = shoot("home", Lang.AR, ThemeMode.DARK, content = home())

    @Test fun phoneArLight() = shoot("home", Lang.AR, ThemeMode.LIGHT, content = home())

    @Test fun tabletEnDark() = shoot("home", Lang.EN, ThemeMode.DARK, Device.TABLET, home())

    @Test fun tabletArLight() = shoot("home", Lang.AR, ThemeMode.LIGHT, Device.TABLET, home())

    @Test fun errorAr() = shoot("home_error", Lang.AR, ThemeMode.DARK, content = home(HomeUiState.Error(ErrorKind.NETWORK)))

    @Test fun `tapping a card reports its id and Arabic titles are used in Arabic`() {
        org.robolectric.RuntimeEnvironment.setQualifiers("ar-ldrtl")
        var clicked: String? = null
        compose.setContent {
            net.alummahnow.design.theme.AunTheme { HomeScreen(content, onVideoClick = { clicked = it }, onRetry = {}, onOpenSettings = {}) }
        }
        compose.onNodeWithText("الأكثر رواجاً").assertExists()
        // The same video sits in both rails, so expect it twice, in Arabic.
        compose.onAllNodes(androidx.compose.ui.test.hasText("نظرة عامة على السيرة")).assertCountEquals(2)
        compose.onAllNodesWithTextFirst("مقدمة في التفسير").performClick()
        assertEquals("v1", clicked)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextFirst(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text))[0]
}
