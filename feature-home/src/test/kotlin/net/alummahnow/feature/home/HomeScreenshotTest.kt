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

    /** The brand channel is named in the frame's language. */
    private fun content(lang: Lang) = HomeUiState.Content(
        listOf(Rail(RailKind.TRENDING, sampleVideos.inLang(lang)), Rail(RailKind.RECENT, recentVideos.inLang(lang)))
    )

    private fun home(state: HomeUiState) = @androidx.compose.runtime.Composable {
        HomeScreen(state, onVideoClick = {}, onRetry = {}, onOpenSettings = {})
    }

    private fun home(lang: Lang) = home(content(lang))

    @Test fun phoneEnDark() = shoot("home", Lang.EN, ThemeMode.DARK, content = home(Lang.EN))

    @Test fun phoneEnLight() = shoot("home", Lang.EN, ThemeMode.LIGHT, content = home(Lang.EN))

    @Test fun phoneArDark() = shoot("home", Lang.AR, ThemeMode.DARK, content = home(Lang.AR))

    @Test fun phoneArLight() = shoot("home", Lang.AR, ThemeMode.LIGHT, content = home(Lang.AR))

    @Test fun tabletEnDark() = shoot("home", Lang.EN, ThemeMode.DARK, Device.TABLET, home(Lang.EN))

    @Test fun tabletArLight() = shoot("home", Lang.AR, ThemeMode.LIGHT, Device.TABLET, home(Lang.AR))

    @Test fun tabletEnLight() = shoot("home", Lang.EN, ThemeMode.LIGHT, Device.TABLET, home(Lang.EN))

    @Test fun tabletArDark() = shoot("home", Lang.AR, ThemeMode.DARK, Device.TABLET, home(Lang.AR))

    @Test fun errorPhoneEnLight() = error(Lang.EN, ThemeMode.LIGHT, Device.PHONE)

    @Test fun errorPhoneEnDark() = error(Lang.EN, ThemeMode.DARK, Device.PHONE)

    @Test fun errorPhoneArLight() = error(Lang.AR, ThemeMode.LIGHT, Device.PHONE)

    @Test fun errorPhoneArDark() = error(Lang.AR, ThemeMode.DARK, Device.PHONE)

    @Test fun errorTabletEnLight() = error(Lang.EN, ThemeMode.LIGHT, Device.TABLET)

    @Test fun errorTabletEnDark() = error(Lang.EN, ThemeMode.DARK, Device.TABLET)

    @Test fun errorTabletArLight() = error(Lang.AR, ThemeMode.LIGHT, Device.TABLET)

    @Test fun errorTabletArDark() = error(Lang.AR, ThemeMode.DARK, Device.TABLET)

    private fun error(lang: Lang, mode: ThemeMode, device: Device) =
        shoot("home_error", lang, mode, device, home(HomeUiState.Error(ErrorKind.NETWORK)))

    @Test fun `tapping a card reports its id and Arabic titles are used in Arabic`() {
        org.robolectric.RuntimeEnvironment.setQualifiers("ar-ldrtl")
        var clicked: String? = null
        compose.setContent {
            net.alummahnow.design.theme.AunTheme {
                HomeScreen(content(Lang.AR), onVideoClick = { clicked = it }, onRetry = {}, onOpenSettings = {})
            }
        }
        compose.onNodeWithText("الأكثر رواجاً").assertExists()
        // Trending and New hold different videos: each rail's first video appears once, in Arabic.
        compose.onAllNodes(androidx.compose.ui.test.hasText("تذكيرات رمضان")).assertCountEquals(1)
        compose.onAllNodes(androidx.compose.ui.test.hasText("تلاوة سورة الكهف")).assertCountEquals(1)
        compose.onAllNodesWithTextFirst("تذكيرات رمضان").performClick()
        assertEquals("v2", clicked)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextFirst(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text))[0]
}
