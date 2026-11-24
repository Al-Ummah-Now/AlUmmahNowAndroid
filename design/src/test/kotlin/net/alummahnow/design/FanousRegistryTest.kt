package net.alummahnow.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import net.alummahnow.design.theme.FanousTheme
import net.alummahnow.design.theme.color
import net.alummahnow.design.theme.colorScheme
import net.alummahnow.fanous.FanousFill
import net.alummahnow.fanous.FanousThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app header and page are the web's (apps/web materials.css --m-header/--m-header-edge/--m-page, nav tokens). */
class FanousRegistryTest {
    private fun theme(dark: Boolean) = FanousTheme(FanousThemeId.FAWANEES.tokens, dark)

    @Test fun headerIsTheWebHeaderInBothModes() {
        for (dark in listOf(false, true)) {
            val t = theme(dark)
            val (end, mid) = if (dark) 0xFF0B1D2A to 0xFF30342D else 0xFF0E2B3D to 0xFF33403D
            val header = t.material.header.single() as FanousFill.Linear
            assertEquals(90f, header.angle)
            assertEquals(listOf(Color(end), Color(mid), Color(end)), header.stops.map { it.color.color })
            assertEquals(Color(0xFF184456), t.parts.navBorder.color)
            assertEquals(Color(if (dark) 0xFFF7F1E8 else 0xFFFBEFD0), t.parts.navText.color)
            assertEquals(Color(if (dark) 0xFFD6DFE2 else 0xFFF5CF92), t.parts.navIcon.color)
            val rule = t.material.headerEdge.single()
            assertEquals(1f, rule.y)
            assertEquals(0.66f, rule.color.alpha)
        }
    }

    @Test fun lightPageIsTheWebCreamWithNoGoldWash() {
        assertEquals(listOf<FanousFill>(FanousFill.Solid(theme(false).colors.background)), theme(false).material.page)
        assertEquals(Color(0xFFF3EEE5), colorScheme(theme(false)).background)
        assertEquals(Color(0xFF09151E), colorScheme(theme(true)).background)
    }

    /** A disabled button is still read: every kind's label keeps 4.5:1 on its own fill, in every theme and mode. */
    @Test fun disabledButtonsKeepReadableLabels() {
        for (id in FanousThemeId.entries) {
            for (dark in listOf(false, true)) {
                val p = FanousTheme(id.tokens, dark).parts
                mapOf(
                    "primary" to (p.buttonPrimaryDisabledText to p.buttonPrimaryDisabledBg),
                    "secondary" to (p.buttonSecondaryDisabledText to p.buttonSecondaryDisabledBg),
                    "ghost" to (p.buttonGhostDisabledText to p.buttonGhostDisabledBg),
                    "destructive" to (p.buttonDestructiveDisabledText to p.buttonDestructiveDisabledBg)
                ).forEach { (kind, pair) ->
                    val ratio = contrast(pair.first.color, pair.second.color)
                    assertTrue("$id dark=$dark $kind is $ratio", ratio >= 4.5f)
                }
            }
        }
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
