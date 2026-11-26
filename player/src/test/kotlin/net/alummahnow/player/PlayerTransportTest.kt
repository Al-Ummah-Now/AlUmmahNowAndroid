package net.alummahnow.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.fixtures.PausedPlayer
import net.alummahnow.player.ui.AunVideoPlayer
import net.alummahnow.player.ui.InactiveSeekTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "ar-ldrtl")
class PlayerTransportTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `Arabic player keeps media time LTR and the surrounding chrome RTL`() {
        val player = PausedPlayer("video", durationMs = 100_000, positionMs = 50_000)
        compose.setContent {
            AunTheme(mode = ThemeMode.LIGHT) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    AunVideoPlayer(
                        player,
                        false,
                        {},
                        Modifier.size(360.dp, 240.dp).testTag("player"),
                        showCast = false
                    )
                }
            }
        }

        val resources = RuntimeEnvironment.getApplication().resources
        val back = compose.onNodeWithContentDescription("رجوع ١٠ ثوانٍ").fetchSemanticsNode().boundsInRoot
        val forward = compose.onNodeWithContentDescription("تقديم ١٠ ثوانٍ").fetchSemanticsNode().boundsInRoot
        assertTrue(back.center.x < forward.center.x)

        val fullscreen = compose.onNodeWithContentDescription(resources.getString(R.string.aun_player_fullscreen))
        val playerBounds = compose.onNodeWithTag("player").fetchSemanticsNode().boundsInRoot
        assertTrue(fullscreen.fetchSemanticsNode().boundsInRoot.center.x < playerBounds.center.x)

        val seek = compose.onNodeWithContentDescription(resources.getString(R.string.aun_player_seek))
        seek.performTouchInput { click(Offset(width * 0.15f, centerY)) }
        compose.runOnIdle { assertTrue(player.lastSeekMs!! < 25_000) }
        seek.performTouchInput { click(Offset(width * 0.85f, centerY)) }
        compose.runOnIdle { assertTrue(player.lastSeekMs!! > 75_000) }

        compose.onNodeWithTag("player").performTouchInput { doubleClick(Offset(width * 0.15f, height / 4f)) }
        compose.runOnIdle { assertEquals(40_000L, player.lastSeekMs) }
        compose.onNodeWithTag("player").performTouchInput { doubleClick(Offset(width * 0.85f, height / 4f)) }
        compose.runOnIdle { assertEquals(60_000L, player.lastSeekMs) }
    }

    @Test
    fun `active caption credit follows the cue language and opens without controlling playback`() {
        val player = PausedPlayer("video", durationMs = 100_000, positionMs = 50_000)
        val opened = mutableListOf<String>()
        compose.setContent {
            AunTheme(mode = ThemeMode.LIGHT) {
                AunVideoPlayer(
                    player,
                    false,
                    {},
                    Modifier.size(360.dp, 202.dp),
                    showCast = false,
                    onOpenCaptionCredit = { _, url -> opened += url }
                )
            }
        }

        val english = compose.onNodeWithContentDescription("Source: Quran Foundation, opens in browser")
        val arabic = compose.onNodeWithContentDescription("المصدر: مؤسسة القرآن، يُفتح في المتصفح")
        english.assertDoesNotExist()
        arabic.assertDoesNotExist()

        val englishVerse = "A longer English verse used to check alignment"
        compose.runOnIdle { player.showCaption("$englishVerse\nSource: Quran Foundation") }
        val englishCue = compose.onNodeWithText(englishVerse).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val englishLink = english.fetchSemanticsNode().boundsInRoot
        val seekLabel = RuntimeEnvironment.getApplication().getString(R.string.aun_player_seek)
        val seekBar = compose.onNodeWithContentDescription(seekLabel).fetchSemanticsNode().boundsInRoot
        assertTrue(englishLink.bottom <= seekBar.top)
        assertTrue(englishLink.top >= englishCue.bottom)
        assertTrue(englishLink.center.x > englishCue.center.x)
        english.assertIsDisplayed().performTouchInput { doubleClick() }

        val arabicVerse = "هذه آية عربية لاختبار محاذاة النص"
        compose.runOnIdle { player.showCaption("$arabicVerse\n\u200fالمصدر: مؤسسة القرآن") }
        english.assertDoesNotExist()
        val arabicCue = compose.onNodeWithText(arabicVerse).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val arabicLink = arabic.fetchSemanticsNode().boundsInRoot
        assertTrue(arabicLink.top >= arabicCue.bottom)
        assertTrue(arabicLink.center.x < arabicCue.center.x)
        arabic.assertIsDisplayed().performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(List(3) { "https://quran.foundation/" }, opened)
            assertEquals(0, player.playPauseRequests)
            assertEquals(null, player.lastSeekMs)
            assertEquals(50_000L, player.currentPosition)
            player.showCaption("Ordinary caption")
        }
        arabic.assertDoesNotExist()
        compose.onNodeWithText("Ordinary caption").assertIsDisplayed()
        compose.runOnIdle { player.showCaption(null) }
        compose.onNodeWithText("Ordinary caption").assertDoesNotExist()
    }

    @Test
    fun `both parts of the track meet 3 to 1 contrast on the black player surface`() {
        for (track in listOf(FanousTokens.Lantern, InactiveSeekTrack)) {
            assertTrue((track.luminance() + 0.05f) / (Color.Black.luminance() + 0.05f) >= 3f)
        }
    }
}
