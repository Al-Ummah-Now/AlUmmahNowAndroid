package net.alummahnow.design

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.AunFormat.Scale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner decision 2026-11-15: Arabic shows Eastern Arabic (Hindi) numerals ٠–٩ everywhere; English keeps 0–9. */
class AunFormatTest {
    private val en = Locale.ENGLISH
    private val ar = Locale.forLanguageTag("ar")

    // Regions whose platform default numbering is Arabic numerals 0–9: the result must not depend on it.
    private val arabicLocales = listOf("ar", "ar-MA", "ar-AE", "ar-SA", "ar-u-nu-latn").map(Locale::forLanguageTag)
    private val day = LocalDate.of(2026, 11, 15)
    private val moment = ZonedDateTime.of(2026, 11, 15, 21, 30, 0, 0, ZoneId.of("UTC"))

    private fun assertNoArabicNumerals(text: String) = assertFalse("\"$text\" has 0–9", text.any { it in '0'..'9' })

    @Test fun `every Arabic formatter produces Eastern Arabic numerals only`() {
        for (locale in arabicLocales) {
            listOf(
                AunFormat.number(1_234_567, locale),
                AunFormat.number(12.5, locale),
                AunFormat.percent(0.42, locale),
                AunFormat.duration(3725, locale),
                AunFormat.timecode(42_000, locale),
                AunFormat.compact(1_250, locale).number,
                AunFormat.compact(950, locale).number,
                AunFormat.date(day, locale),
                AunFormat.dateTime(moment, locale),
                AunFormat.money(12.69, "USD", locale),
                AunFormat.format("%1\$d of %2\$d · %3\$s", locale, 3, 9L, "x"),
                AunFormat.digits("2026-11", locale)
            ).forEach { text ->
                assertNoArabicNumerals(text)
                assertTrue("\"$text\" has no ٠–٩", text.any { it in '٠'..'٩' })
            }
        }
    }

    @Test fun `Arabic uses the Arabic separators and percent sign`() {
        assertEquals("١٬٢٣٤٬٥٦٧", AunFormat.number(1_234_567, ar))
        assertEquals("١٢٫٥", AunFormat.number(12.5, ar))
        assertEquals("٤٢٪", AunFormat.percent(0.42, ar))
        assertEquals("١:١٥", AunFormat.duration(75, ar))
        assertEquals("١:٠٢:٠٥", AunFormat.duration(3725, ar))
        assertEquals("٣ من ٩", AunFormat.format("%1\$d من %2\$d", ar, 3, 9))
    }

    @Test fun `Arabic money uses the currency name, never US$`() {
        val text = AunFormat.money(12.69, "USD", ar)
        assertEquals("١٢٫٦٩ دولار أمريكي", text)
        assertFalse(text.contains("US"))
        assertFalse(text.contains("$"))
    }

    @Test fun `English is unchanged`() {
        assertEquals("1,234,567", AunFormat.number(1_234_567, en))
        assertEquals("12.5", AunFormat.number(12.5, en))
        assertEquals("42%", AunFormat.percent(0.42, en))
        assertEquals("0:00", AunFormat.duration(0, en))
        assertEquals("1:15", AunFormat.duration(75, en))
        assertEquals("1:02:05", AunFormat.duration(3725, en))
        assertEquals("0:42", AunFormat.timecode(42_000, en))
        assertEquals("0:00", AunFormat.duration(-5, en))
        assertEquals("Nov 15, 2026", AunFormat.date(day, en))
        assertEquals("$12.69", AunFormat.money(12.69, "USD", Locale.US))
        assertEquals("3 of 9 · x", AunFormat.format("%1\$d of %2\$d · %3\$s", en, 3, 9L, "x"))
        assertEquals("100%", AunFormat.format("%1\$d%%", en, 100))
        assertEquals("4167", AunFormat.digits("4167", en))
    }

    @Test fun `compact counts truncate and localize the decimal separator`() {
        assertEquals(AunFormat.Compact("950", Scale.NONE), AunFormat.compact(950, en))
        assertEquals(AunFormat.Compact("1.2", Scale.THOUSAND), AunFormat.compact(1_299, en))
        assertEquals(AunFormat.Compact("4", Scale.THOUSAND), AunFormat.compact(4_000, en))
        assertEquals(AunFormat.Compact("3.4", Scale.MILLION), AunFormat.compact(3_499_999, en))
        assertEquals(AunFormat.Compact("2", Scale.BILLION), AunFormat.compact(2_000_000_000, en))
        assertEquals(AunFormat.Compact("١٫٢", Scale.THOUSAND), AunFormat.compact(1_250, ar))
    }

    @Test fun `Arabic meta lines use a bullet, because the middle dot reads as zero`() {
        assertEquals("عام \u2022 ١٫٥ ألف مشاهدة", AunFormat.joinMeta(listOf("عام", null, "", "١٫٥ ألف مشاهدة"), ar))
        assertEquals("Public \u00B7 1.5K views", AunFormat.joinMeta(listOf("Public", "1.5K views"), en))
        assertFalse(AunFormat.separator(ar).contains('\u00B7'))
    }

    @Test fun `only a decimal point between digits becomes the Arabic decimal separator`() {
        assertEquals("٧٣٤ م.ب", AunFormat.decimal("734 م.ب", ar))
        assertEquals("١٫٥ م.ب", AunFormat.decimal("1.5 م.ب", ar))
        assertEquals("1.5 MB", AunFormat.decimal("1.5 MB", en))
    }

    @Test fun `text arguments are never rewritten`() {
        assertEquals("Lesson 3 · ١", AunFormat.format("%1\$s · %2\$d", ar, "Lesson 3", 1))
    }

    @Test fun `typed Eastern Arabic and Persian digits normalise to Arabic numerals`() {
        assertEquals("0123456789", AunFormat.normalizeDigits("٠١٢٣٤٥٦٧٨٩"))
        assertEquals("0123456789", AunFormat.normalizeDigits("۰۱۲۳۴۵۶۷۸۹"))
        assertEquals("abc 42", AunFormat.normalizeDigits("abc ٤2"))
    }

    @Test fun `numbering locale is explicit for Arabic only`() {
        assertEquals("arab", AunFormat.numberingLocale(ar).getUnicodeLocaleType("nu"))
        assertEquals("arab", AunFormat.numberingLocale(Locale.forLanguageTag("ar-u-nu-latn")).getUnicodeLocaleType("nu"))
        assertEquals(en, AunFormat.numberingLocale(en))
    }
}
