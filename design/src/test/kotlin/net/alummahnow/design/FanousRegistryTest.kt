package net.alummahnow.design

import androidx.compose.ui.graphics.Color
import net.alummahnow.design.theme.FanousTheme
import net.alummahnow.design.theme.color
import net.alummahnow.design.theme.colorScheme
import net.alummahnow.fanous.FanousFill
import net.alummahnow.fanous.FanousThemeId
import org.junit.Assert.assertEquals
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
}
