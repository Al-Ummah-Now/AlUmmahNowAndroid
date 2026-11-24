package net.alummahnow.design.format

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AunFormatE22Test {
    private val en = Locale.ENGLISH
    private val ar = Locale.forLanguageTag("ar")

    @Test fun `badgeCap9 uses Eastern Arabic numerals and caps at 9 plus`() {
        assertNull(AunFormat.badgeCap9(0, en))
        assertNull(AunFormat.badgeCap9(-2, ar))
        assertEquals("4", AunFormat.badgeCap9(4, en))
        assertEquals("٤", AunFormat.badgeCap9(4, ar))
        assertEquals("9", AunFormat.badgeCap9(9, en))
        assertEquals("٩", AunFormat.badgeCap9(9, ar))
        assertEquals("9+", AunFormat.badgeCap9(10, en))
        assertEquals("٩+", AunFormat.badgeCap9(25, ar))
    }

    @Test fun `minuteOfDay formats 24h clock in English and Eastern Arabic numerals`() {
        assertEquals("22:00", AunFormat.minuteOfDay(1320, en))
        assertEquals("٢٢:٠٠", AunFormat.minuteOfDay(1320, ar))
        assertEquals("06:05", AunFormat.minuteOfDay(365, en))
        assertEquals("٠٦:٠٥", AunFormat.minuteOfDay(365, ar))
    }
}
