package net.alummahnow.core.bridge.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuranCaptionCreditTest {
    @Test
    fun `matches English Arabic and legacy served credit lines`() {
        val examples = mapOf(
            "Source: Quran Foundation" to "en",
            "المصدر: مؤسسة القرآن" to "ar",
            "\u200fالمصدر: مؤسسة القرآن" to "ar",
            "\u200e\u200fالمصدر: مؤسسة القرآن  " to "ar",
            "Quran data provided by Quran Foundation (https://quran.foundation/)" to "en",
            "Quran data provided by Quran\u00a0Foundation\u00a0(https://quran.foundation/)" to "en",
            "بيانات القرآن مقدَّمة من مؤسسة القرآن (https://quran.foundation/\u200e)" to "ar",
            "بيانات القرآن مقدَّمة من مؤسسة\u00a0القرآن\u00a0(https://quran.foundation/\u200e)" to "ar"
        )
        val caption = " First line\n\nSecond line  "
        for ((credit, language) in examples) {
            val result = splitQuranCredit("$caption\n$credit")
            assertTrue(result.credited)
            assertEquals(caption, result.text)
            assertEquals(language, result.language)
            assertEquals("https://quran.foundation/", result.url)
        }
    }

    @Test
    fun `leaves blank uncredited and non trailing credit text untouched`() {
        for (caption in listOf("", "  \n ", "A caption", "Source: Quran Foundation", "Source: Quran Foundation\nMore text")) {
            val result = splitQuranCredit(caption)
            assertEquals(caption, result.text)
            assertFalse(result.credited)
            assertNull(result.language)
            assertNull(result.url)
        }
    }
}
