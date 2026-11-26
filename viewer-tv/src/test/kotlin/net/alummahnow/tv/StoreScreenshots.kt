package net.alummahnow.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.theme.ThemeMode
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** TV store screenshots (1920×1080, en + ar) of the TV app's root screen, [TvApp]. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StoreScreenshots {
    @Before fun enabled() = StoreShots.assumeEnabled()

    @Test fun tvEn() = StoreShots.shoot("01-home-dark", StoreLocale.EN, StoreDevice.TV, ThemeMode.DARK) { TvApp(mode = ThemeMode.DARK) }

    @Test fun tvAr() = StoreShots.shoot("01-home-dark", StoreLocale.AR, StoreDevice.TV, ThemeMode.DARK) { TvApp(mode = ThemeMode.DARK) }
}
