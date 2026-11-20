package net.alummahnow.feature.studio

import net.alummahnow.feature.studio.analytics.countTicks
import net.alummahnow.feature.studio.analytics.dateLabelIndices
import org.junit.Assert.assertEquals
import org.junit.Test

/** The analytics value axis (owner report 2026-11-11: "views chart only has time axis"). */
class ChartScaleTest {
    @Test
    fun `ticks start at 0 on round steps above the largest value`() {
        assertEquals(listOf(0L, 2000, 4000, 6000, 8000), countTicks(6075))
        assertEquals(listOf(0L, 1, 2, 3), countTicks(3))
        assertEquals(listOf(0L, 5000, 10000, 15000, 20000), countTicks(16490))
    }

    @Test
    fun `an empty series still has an axis`() {
        assertEquals(listOf(0L, 1), countTicks(0))
    }

    @Test
    fun `labels the first, middle and last day`() {
        assertEquals(listOf(0, 13, 27), dateLabelIndices(28))
        assertEquals(listOf(0, 1), dateLabelIndices(2))
        assertEquals(emptyList<Int>(), dateLabelIndices(0))
    }
}
