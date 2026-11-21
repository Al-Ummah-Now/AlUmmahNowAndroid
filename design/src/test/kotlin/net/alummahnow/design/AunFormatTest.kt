package net.alummahnow.design

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.AunFormat.Scale
import net.alummahnow.design.format.AunFormat.ScaleForm
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

    @Test fun `Arabic uses the Arabic decimal mark and percent sign, and never groups`() {
        // Owner 2026-11-21: «٫» is the only comma-like mark in Arabic; English keeps its grouping.
        assertEquals("١٢٣٤٥٦٧", AunFormat.number(1_234_567, ar))
        assertEquals("٤٨٨٧", AunFormat.number(4887, ar))
        assertEquals("١٢٣٤٫٥", AunFormat.number(1234.5, ar))
        assertEquals("4,887", AunFormat.number(4887, en))
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
        assertEquals(AunFormat.Compact("4", Scale.THOUSAND, ScaleForm.PLURAL), AunFormat.compact(4_000, en))
        assertEquals(AunFormat.Compact("3.4", Scale.MILLION), AunFormat.compact(3_499_999, en))
        assertEquals(AunFormat.Compact("2", Scale.BILLION, ScaleForm.DUAL), AunFormat.compact(2_000_000_000, en))
        assertEquals(AunFormat.Compact("١٫٢", Scale.THOUSAND), AunFormat.compact(1_250, ar))
    }

    @Test fun `the scale word agrees with the shown number`() {
        fun form(n: Long) = AunFormat.compact(n, ar).form
        assertEquals(ScaleForm.ONE, form(1_000)) // «ألف»
        assertEquals(ScaleForm.DUAL, form(2_000)) // «ألفان»
        assertEquals(ScaleForm.PLURAL, form(3_000)) // «٣ آلاف»
        assertEquals(ScaleForm.PLURAL, form(8_020)) // «٨ آلاف»
        assertEquals(ScaleForm.PLURAL, form(10_000)) // «١٠ آلاف»
        assertEquals(ScaleForm.TAMYIZ, form(11_000)) // «١١ ألفًا» alone, «١١ ألف مشاهدة» before a noun
        assertEquals(ScaleForm.TAMYIZ, form(12_040)) // «١٢ ألفًا»
        assertEquals(ScaleForm.TAMYIZ, form(120_000)) // «١٢٠ ألفًا»
        assertEquals(ScaleForm.SINGULAR, form(100_000)) // «١٠٠ ألف»
        assertEquals(ScaleForm.SINGULAR, form(11_500)) // «١١٫٥ ألف»
        assertEquals(ScaleForm.SINGULAR, form(1_310)) // «١٫٣ ألف»
        assertEquals(ScaleForm.SINGULAR, form(2_500)) // «٢٫٥ ألف»
        assertEquals(ScaleForm.SINGULAR, form(10_400)) // «١٠٫٤ ألف»
        // Millions are always «{n} م» (owner 2026-11-21).
        assertEquals(ScaleForm.SINGULAR, form(1_000_000)) // «١ م»
        assertEquals(ScaleForm.SINGULAR, form(2_000_000)) // «٢ م»
        assertEquals(ScaleForm.SINGULAR, form(1_200_000)) // «١٫٢ م»
        assertEquals(ScaleForm.SINGULAR, form(3_000_000)) // «٣ م»
        assertEquals(ScaleForm.SINGULAR, form(15_000_000)) // «١٥ م»
        assertEquals(ScaleForm.DUAL, form(2_000_000_000)) // «ملياران»
    }

    @Test fun `Arabic meta lines use the Arabic comma, because a dot reads as zero`() {
        assertEquals("\u2067عام\u2069\u060C \u200F١٫٥ ألف مشاهدة\u200F", AunFormat.joinMeta(listOf("عام", null, "", "١٫٥ ألف مشاهدة"), ar))
        assertEquals("\u2066Public\u2069 \u00B7 \u20661.5K views\u2069", AunFormat.joinMeta(listOf("Public", "1.5K views"), en))
        // An Arabic channel name in an English line stays isolated from the number after it.
        assertEquals("\u2067الأمة الآن\u2069 \u00B7 \u20661.5K views\u2069", AunFormat.joinMeta(listOf("الأمة الآن", "1.5K views"), en))
        assertFalse(AunFormat.separator(ar).contains('\u00B7'))
    }

    @Test fun `an isolate keeps its text in its own direction, ignoring nested isolates`() {
        // Re-audit 3 (#53): «عند ٠:٤٢» rendered as «٠:٤٢عند» inside a right-to-left isolate; Arabic with digits takes RLMs.
        assertEquals("\u200Fعند\u00A0\u2066٠:٤٢\u2069\u200F", AunFormat.isolate("عند\u00A0" + AunFormat.isolate("٠:٤٢")))
        assertEquals("\u2066at\u00A0\u20660:42\u2069\u2069", AunFormat.isolate("at\u00A0" + AunFormat.isolate("0:42")))
        assertEquals("\u200F٧٣٤\u00A0م.ب\u200F", AunFormat.isolate("٧٣٤\u00A0م.ب"))
        // Only the nested isolate has letters: the outer one is left-to-right, as FSI would be.
        assertEquals("\u2066\u2067سورة الكهف\u2069.mp4\u2069", AunFormat.isolate(AunFormat.isolate("سورة الكهف") + ".mp4"))
        assertEquals("\u2066٨٢٪\u2069", AunFormat.isolate("٨٢٪"))
    }

    @Test fun `file sizes use decimal units, one decimal below ten, cut and never grouped in Arabic`() {
        assertEquals(AunFormat.FileSize("٧٣٤", AunFormat.SizeUnit.MEGA), AunFormat.fileSize(734_003_200, ar))
        assertEquals(AunFormat.FileSize("734", AunFormat.SizeUnit.MEGA), AunFormat.fileSize(734_003_200, en))
        assertEquals(AunFormat.FileSize("٥١٢", AunFormat.SizeUnit.MEGA), AunFormat.fileSize(512_000_000, ar))
        assertEquals(AunFormat.FileSize("١٫٥", AunFormat.SizeUnit.MEGA), AunFormat.fileSize(1_599_999, ar))
        assertEquals(AunFormat.FileSize("1.5", AunFormat.SizeUnit.GIGA), AunFormat.fileSize(1_590_000_000, en))
        assertEquals(AunFormat.FileSize("٩٩٩", AunFormat.SizeUnit.BYTE), AunFormat.fileSize(999, ar))
        assertEquals(AunFormat.FileSize("١٢", AunFormat.SizeUnit.KILO), AunFormat.fileSize(12_900, ar))
        assertEquals(AunFormat.FileSize("١٢٠٠", AunFormat.SizeUnit.TERA), AunFormat.fileSize(1_200_000_000_000_000, ar))
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

    @Test fun `Arabic compact counts use the Arabic decimal separator, never a Latin comma or point`() {
        val c = AunFormat.compact(1_520, ar)
        assertEquals("١٫٥", c.number)
        // Placed into a resource string ("%1$s ألف مشاهدة") the separator stays U+066B.
        val label = AunFormat.format("%1\$s ألف مشاهدة", ar, c.number)
        assertEquals("١٫٥ ألف مشاهدة", label)
        listOf(",", ".", "\u060C").forEach { assertEquals(false, label.contains(it)) }
        assertEquals(true, label.contains('\u066B'))
    }
}
