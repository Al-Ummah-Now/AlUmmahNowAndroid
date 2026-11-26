package net.alummahnow.core.bridge.media

const val QURAN_FOUNDATION_URL = "https://quran.foundation/"
const val QURAN_CREDIT_EN = "Source: Quran Foundation"
const val QURAN_CREDIT_AR = "المصدر: مؤسسة القرآن"

private const val LEGACY_EN = "Quran data provided by Quran Foundation ($QURAN_FOUNDATION_URL)"
private const val LEGACY_AR = "بيانات القرآن مقدَّمة من مؤسسة القرآن ($QURAN_FOUNDATION_URL\u200e)"

data class QuranCaptionCredit(val text: String, val language: String? = null) {
    val credited: Boolean get() = language != null
    val url: String? get() = QURAN_FOUNDATION_URL.takeIf { credited }
}

/** Matches the served credit line, leaving the rest of the cue unchanged. */
fun splitQuranCredit(text: String): QuranCaptionCredit {
    val end = text.lastIndexOf('\n')
    if (end < 0) return QuranCaptionCredit(text)
    val language = when (text.substring(end + 1).trimStart('\u200e', '\u200f').trim()) {
        QURAN_CREDIT_EN, LEGACY_EN, LEGACY_EN.replace("Quran Foundation (", "Quran\u00a0Foundation\u00a0(") -> "en"
        QURAN_CREDIT_AR, LEGACY_AR, LEGACY_AR.replace("مؤسسة القرآن (", "مؤسسة\u00a0القرآن\u00a0(") -> "ar"
        else -> return QuranCaptionCredit(text)
    }
    return QuranCaptionCredit(text.substring(0, end), language)
}
