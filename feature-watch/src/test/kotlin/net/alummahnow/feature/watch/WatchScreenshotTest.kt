package net.alummahnow.feature.watch

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.theme.ThemeMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Player is null here (no service in Robolectric), so the player area shows the lantern placeholder. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WatchScreenshotTest {
    /** The brand channel is named in the frame's language. */
    private fun content(lang: Lang) = WatchUiState.Content(sampleDetail(lang = lang), sampleVideos.inLang(lang).drop(1))

    private fun watch(lang: Lang): @Composable () -> Unit = {
        WatchScreen(content(lang), player = null, isFullscreen = false, isInPictureInPicture = false, onToggleFullscreen = {
        }, onVideoClick = {}, onRetry = {})
    }

    @Test fun phoneEnDark() = shoot("watch", Lang.EN, ThemeMode.DARK, content = watch(Lang.EN))

    @Test fun phoneArDark() = shoot("watch", Lang.AR, ThemeMode.DARK, content = watch(Lang.AR))

    @Test fun phoneArLight() = shoot("watch", Lang.AR, ThemeMode.LIGHT, content = watch(Lang.AR))

    @Test fun phoneEnLight() = shoot("watch", Lang.EN, ThemeMode.LIGHT, content = watch(Lang.EN))

    @Test fun tabletTwoPaneEn() = shoot("watch", Lang.EN, ThemeMode.DARK, Device.TABLET, watch(Lang.EN))

    @Test fun tabletTwoPaneAr() = shoot("watch", Lang.AR, ThemeMode.DARK, Device.TABLET, watch(Lang.AR))
}
