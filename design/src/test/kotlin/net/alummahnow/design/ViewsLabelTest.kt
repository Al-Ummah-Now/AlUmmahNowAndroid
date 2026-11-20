package net.alummahnow.design

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.alummahnow.design.format.viewsLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Arabic number grammar for view counts: small counts follow the full count's plural form; a compact count is counted
 * by its scale word, so the noun stays singular («١٫٣ ألف مشاهدة», never «مشاهدات»), and a whole 3–10 takes the
 * plural scale word («٨ آلاف») and a whole 2 the dual («٢ ألفان»).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ViewsLabelTest {
    @get:Rule val compose = createComposeRule()

    private fun labels(qualifiers: String, counts: List<Long>): List<String> {
        RuntimeEnvironment.setQualifiers(qualifiers)
        val out = mutableListOf<String>()
        compose.setContent { counts.forEach { out += viewsLabel(it) } }
        compose.waitForIdle()
        return out
    }

    private val counts = listOf<Long>(1, 2, 3, 8, 10, 11, 1_310, 2_000, 8_020, 12_000, 1_200_000, 3_000_000, 15_000_000)

    @Test fun arabic() {
        val want = listOf(
            "مشاهدة واحدة",
            "مشاهدتان",
            "٣ مشاهدات",
            "٨ مشاهدات",
            "١٠ مشاهدات",
            "١١ مشاهدة",
            "١٫٣ ألف مشاهدة",
            "٢ ألفان مشاهدة",
            "٨ آلاف مشاهدة",
            "١٢ ألف مشاهدة",
            "١٫٢ مليون مشاهدة",
            "٣ ملايين مشاهدة",
            "١٥ مليون مشاهدة"
        )
        assertEquals(want, labels("ar-ldrtl", counts))
    }

    @Test fun english() {
        val want = listOf(
            "1 view", "2 views", "3 views", "8 views", "10 views", "11 views", "1.3K views", "2K views", "8K views", "12K views",
            "1.2M views", "3M views", "15M views"
        )
        assertEquals(want, labels("en-ldltr", counts))
    }
}
