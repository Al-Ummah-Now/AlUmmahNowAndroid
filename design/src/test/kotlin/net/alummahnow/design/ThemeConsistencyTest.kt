package net.alummahnow.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import net.alummahnow.design.theme.AunTheme
import net.alummahnow.design.theme.FanousTheme
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.ThemeMode
import net.alummahnow.design.theme.colorScheme
import net.alummahnow.fanous.FanousPalette
import net.alummahnow.fanous.FanousRgba
import net.alummahnow.fanous.FanousThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every surface and every text follows the active mode: no theme may put text under 4.5:1 on its own surfaces, the light
 * Material scheme may not carry a dark-only token (or the reverse), and AunTheme hands its content the mode it was asked
 * for. A mixed screen (dark cards and cream text on a light page) fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ThemeConsistencyTest {
    @get:Rule val compose = createComposeRule()

    private fun luminance(c: FanousRgba): Double {
        fun channel(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: FanousRgba, b: FanousRgba): Double {
        val (la, lb) = luminance(a) to luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun `text on every surface is at least 4_5 to 1 in every theme and mode`() {
        FanousThemeId.entries.forEach { id ->
            listOf(false, true).forEach { dark ->
                val c = id.tokens.mode(dark).colors
                val texts = mapOf("textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary, "textMuted" to c.textMuted)
                val surfaces = mapOf("background" to c.background, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised)
                texts.forEach { (tn, t) ->
                    surfaces.forEach { (sn, s) ->
                        val r = contrast(t, s)
                        assertTrue("$id ${if (dark) "dark" else "light"}: $tn on $sn is ${"%.2f".format(r)}:1", r >= MIN_CONTRAST)
                    }
                }
            }
        }
    }

    /**
     * Accent-coloured text (rank numbers, a "2 comments are waiting" line) uses the theme's `link` colour, never the
     * button gold `primary`: the gold is unreadable on cream in Light and under 4.5:1 in some dark themes.
     */
    @Test fun `accent text (link) is at least 4_5 to 1 on every surface in every theme and mode`() {
        FanousThemeId.entries.forEach { id ->
            listOf(false, true).forEach { dark ->
                val c = id.tokens.mode(dark).colors
                listOf("background" to c.background, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised).forEach { (sn, s) ->
                    val r = contrast(c.link, s)
                    assertTrue("$id ${if (dark) "dark" else "light"}: link on $sn is ${"%.2f".format(r)}:1", r >= MIN_CONTRAST)
                }
            }
        }
    }

    private fun palette(p: FanousPalette): Set<Int> = p.javaClass.declaredFields
        .filter { it.type == FanousRgba::class.java }
        .map { field ->
            field.isAccessible = true
            (field.get(p) as FanousRgba).argb
        }
        .toSet()

    /** The scheme roles screens actually paint with (pages, cards, text). */
    private fun painted(s: ColorScheme): Set<Int> = listOf(
        s.background, s.onBackground, s.surface, s.onSurface, s.surfaceVariant, s.onSurfaceVariant, s.surfaceContainer,
        s.surfaceContainerLow, s.surfaceContainerHigh, s.secondaryContainer, s.onSecondaryContainer, s.outline
    ).map { it.toArgb() }.toSet()

    @Test fun `the light scheme carries no dark-only token and the dark scheme no light-only one`() {
        FanousThemeId.entries.forEach { id ->
            val light = FanousTheme(id.tokens, dark = false)
            val dark = FanousTheme(id.tokens, dark = true)
            val lightOnly = palette(light.colors) - palette(dark.colors)
            val darkOnly = palette(dark.colors) - palette(light.colors)
            val inLight = painted(colorScheme(light)) intersect darkOnly
            val inDark = painted(colorScheme(dark)) intersect lightOnly
            assertTrue("$id light scheme uses dark tokens: ${inLight.map { Integer.toHexString(it) }}", inLight.isEmpty())
            assertTrue("$id dark scheme uses light tokens: ${inDark.map { Integer.toHexString(it) }}", inDark.isEmpty())
        }
    }

    private fun checkMode(mode: ThemeMode) {
        var fanousDark: Boolean? = null
        var background = 0
        var fanousBackground = 0
        compose.setContent {
            AunTheme(mode = mode) {
                fanousDark = LocalFanous.current.dark
                background = MaterialTheme.colorScheme.background.toArgb()
                fanousBackground = LocalFanous.current.colors.background.argb
            }
        }
        compose.waitForIdle()
        assertEquals("$mode: Fanous mode", mode == ThemeMode.DARK, fanousDark)
        assertEquals("$mode: Material page = Fanous page", fanousBackground, background)
    }

    @Test fun `AunTheme Light gives Material and Fanous the light mode`() = checkMode(ThemeMode.LIGHT)

    @Test fun `AunTheme Dark gives Material and Fanous the dark mode`() = checkMode(ThemeMode.DARK)

    private companion object {
        const val MIN_CONTRAST = 4.5
    }
}
