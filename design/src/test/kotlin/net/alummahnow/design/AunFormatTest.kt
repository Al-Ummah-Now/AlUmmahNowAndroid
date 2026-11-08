package net.alummahnow.design

import java.util.Locale
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.AunFormat.Scale
import org.junit.Assert.assertEquals
import org.junit.Test

class AunFormatTest {
    private val en = Locale.ENGLISH
    private val ar = Locale.forLanguageTag("ar")

    @Test fun `durations`() {
        assertEquals("0:00", AunFormat.duration(0, en, true))
        assertEquals("1:15", AunFormat.duration(75, en, true))
        assertEquals("1:02:05", AunFormat.duration(3725, en, true))
        assertEquals("١:١٥", AunFormat.duration(75, ar, true))
        assertEquals("1:15", AunFormat.duration(75, ar, false))
        assertEquals("0:00", AunFormat.duration(-5, en, true))
    }

    @Test fun `compact counts truncate and localize the decimal separator`() {
        assertEquals(AunFormat.Compact("950", Scale.NONE), AunFormat.compact(950, en, true))
        assertEquals(AunFormat.Compact("1.2", Scale.THOUSAND), AunFormat.compact(1_299, en, true))
        assertEquals(AunFormat.Compact("4", Scale.THOUSAND), AunFormat.compact(4_000, en, true))
        assertEquals(AunFormat.Compact("3.4", Scale.MILLION), AunFormat.compact(3_499_999, en, true))
        assertEquals(AunFormat.Compact("2", Scale.BILLION), AunFormat.compact(2_000_000_000, en, true))
        assertEquals(AunFormat.Compact("١٫٢", Scale.THOUSAND), AunFormat.compact(1_250, ar, true))
        assertEquals(AunFormat.Compact("1٫2", Scale.THOUSAND), AunFormat.compact(1_250, ar, false))
    }

    @Test fun `digits only change for Arabic with the option on`() {
        assertEquals("٤١٦٧", AunFormat.digits("4167", ar, true))
        assertEquals("4167", AunFormat.digits("4167", en, true))
        assertEquals("a1", AunFormat.digits("a1", ar, false))
    }
}
