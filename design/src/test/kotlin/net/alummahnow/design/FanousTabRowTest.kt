package net.alummahnow.design

import net.alummahnow.design.components.fittingTabs
import org.junit.Assert.assertEquals
import org.junit.Test

class FanousTabRowTest {
    private val widths = List(6) { 100f }

    private fun fit(available: Float, selected: Int) = fittingTabs(
        widths,
        more = 60f,
        gap = 10f,
        available = available,
        selected = selected
    )

    @Test
    fun `all of them, and no More, when they fit`() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), fit(650f, 0))
    }

    @Test
    fun `as many as fit with More`() {
        assertEquals(listOf(0, 1, 2, 3, 4), fit(610f, 0))
        // 3 chips, 2 gaps, a gap and More: 390.
        assertEquals(listOf(0, 1, 2), fit(390f, 1))
        assertEquals(listOf(0, 1), fit(389f, 1))
    }

    @Test
    fun `the selected one is always there, in the place of the last`() {
        assertEquals(listOf(0, 1, 5), fit(390f, 5))
        assertEquals(listOf(0, 4), fit(389f, 4))
    }

    @Test
    fun `it stays alone when nothing else has room`() {
        assertEquals(listOf(3), fit(100f, 3))
        assertEquals(emptyList<Int>(), fit(100f, -1))
    }

    @Test
    fun `a wide selected one takes the place of as many as it needs`() {
        val uneven = listOf(100f, 100f, 100f, 250f)
        assertEquals(listOf(0, 3), fittingTabs(uneven, 60f, 10f, available = 430f, selected = 3))
    }
}
