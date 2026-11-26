package net.alummahnow.feature.watch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.fixtures.PausedPlayer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The player is loaded and paused at the start (a [PausedPlayer]: Robolectric has no playback service), so the frames
 * show the settled player: the video's poster with the play button, seek bar and times.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WatchScreenshotTest {
    /** The brand channel is named in the frame's language. */
    private fun content(lang: Lang) = WatchUiState.Content(sampleDetail(lang = lang), sampleVideos.inLang(lang).drop(1))

    private fun watch(lang: Lang): @Composable () -> Unit = {
        val video = sampleDetail(lang = lang)
        val player = remember { PausedPlayer(video.id, (video.summary.durationSeconds ?: 0) * MS_PER_S) }
        WatchScreen(content(lang), player = player, isFullscreen = false, isInPictureInPicture = false, onToggleFullscreen = {
        }, onVideoClick = {}, onRetry = {})
    }

    @Test fun phoneEnDark() = shoot("watch", Lang.EN, ThemeMode.DARK, content = watch(Lang.EN))

    @Test fun phoneArDark() = shoot("watch", Lang.AR, ThemeMode.DARK, content = watch(Lang.AR))

    @Test fun phoneArLight() = shoot("watch", Lang.AR, ThemeMode.LIGHT, content = watch(Lang.AR))

    @Test fun phoneEnLight() = shoot("watch", Lang.EN, ThemeMode.LIGHT, content = watch(Lang.EN))

    @Test fun tabletTwoPaneEn() = shoot("watch", Lang.EN, ThemeMode.DARK, Device.TABLET, watch(Lang.EN))

    @Test fun tabletTwoPaneAr() = shoot("watch", Lang.AR, ThemeMode.DARK, Device.TABLET, watch(Lang.AR))

    @Test fun tabletTwoPaneEnLight() = shoot("watch", Lang.EN, ThemeMode.LIGHT, Device.TABLET, watch(Lang.EN))

    @Test fun tabletTwoPaneArLight() = shoot("watch", Lang.AR, ThemeMode.LIGHT, Device.TABLET, watch(Lang.AR))

    private companion object {
        const val MS_PER_S = 1_000L
    }
}
