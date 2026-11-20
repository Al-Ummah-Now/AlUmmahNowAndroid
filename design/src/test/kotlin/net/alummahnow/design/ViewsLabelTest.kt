package net.alummahnow.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.format.compactCount
import net.alummahnow.design.format.viewsLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Arabic number grammar for view counts and chart ticks. Below 1,000 the noun follows the full count's plural form. A
 * compact count is counted by its scale word, so the noun stays singular, and no digit ever comes before 1 or the dual:
 * «ألف مشاهدة», «ألفا مشاهدة», «٣ آلاف مشاهدة», «١١ ألف مشاهدة», «١٫٣ ألف مشاهدة». Ticks (no noun): «ألف», «ألفان», «٣ آلاف».
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ViewsLabelTest {
    @get:Rule val compose = createComposeRule()

    private fun render(qualifiers: String, counts: List<Long>, label: @Composable (Long) -> String): List<String> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val out = mutableListOf<String>()
        compose.setContent { counts.forEach { out += label(it) } }
        compose.waitForIdle()
        return out
    }

    private val counts = listOf<Long>(
        1, 2, 3, 10, 11, 100, 1_000, 1_300, 2_000, 2_500, 3_000, 8_000, 11_000, 1_000_000, 2_000_000, 3_000_000, 15_000_000
    )

    @Test fun arabic() {
        val want = listOf(
            "مشاهدة واحدة",
            "مشاهدتان",
            "٣ مشاهدات",
            "١٠ مشاهدات",
            "١١ مشاهدة",
            "١٠٠ مشاهدة",
            "ألف مشاهدة",
            "١٫٣ ألف مشاهدة",
            "ألفا مشاهدة",
            "٢٫٥ ألف مشاهدة",
            "٣ آلاف مشاهدة",
            "٨ آلاف مشاهدة",
            "١١ ألف مشاهدة",
            "مليون مشاهدة",
            "مليونا مشاهدة",
            "٣ ملايين مشاهدة",
            "١٥ مليون مشاهدة"
        )
        assertEquals(want, render("ar-ldrtl", counts) { viewsLabel(it) })
    }

    @Test fun english() {
        val want = listOf(
            "1 view", "2 views", "3 views", "10 views", "11 views", "100 views", "1K views", "1.3K views", "2K views",
            "2.5K views", "3K views", "8K views", "11K views", "1M views", "2M views", "3M views", "15M views"
        )
        assertEquals(want, render("en-ldltr", counts) { viewsLabel(it) })
    }

    @Test fun chartTicks() {
        val ticks = listOf<Long>(500, 1_000, 1_500, 2_000, 3_000, 2_000_000)
        assertEquals(
            listOf("٥٠٠", "ألف", "١٫٥ ألف", "ألفان", "٣ آلاف", "مليونان"),
            render("ar-ldrtl", ticks) { compactCount(it) }
        )
        assertEquals(listOf("500", "1K", "1.5K", "2K", "3K", "2M"), render("en-ldltr", ticks) { compactCount(it) })
    }
}
