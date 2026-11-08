package net.alummahnow.design.format

import java.util.Locale

/** Locale-aware number and duration formatting shared by every screen. Pure functions so they are unit-tested. */
object AunFormat {
    private const val EASTERN_ZERO = '٠'

    /** Replaces ASCII digits with Eastern Arabic (٠١٢…) when the locale is Arabic and the user wants them. */
    fun digits(text: String, locale: Locale, eastern: Boolean): String {
        if (!eastern || locale.language != "ar") return text
        return buildString(text.length) {
            text.forEach { c -> append(if (c in '0'..'9') EASTERN_ZERO + (c - '0') else c) }
        }
    }

    /** 75 → "1:15", 3725 → "1:02:05". Durations are always LTR-ordered digits, as in the web player. */
    fun duration(totalSeconds: Long, locale: Locale, eastern: Boolean): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val raw = if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, sec) else "%d:%02d".format(Locale.ROOT, m, sec)
        return digits(raw, locale, eastern)
    }

    enum class Scale { NONE, THOUSAND, MILLION, BILLION }

    /** The number part of a compact count; the scale word comes from string resources (see [compactCount]). */
    data class Compact(val number: String, val scale: Scale)

    /** 950 → ("950", NONE), 1_250 → ("1.2", THOUSAND), 3_400_000 → ("3.4", MILLION). Truncates, never rounds up. */
    fun compact(n: Long, locale: Locale, eastern: Boolean): Compact {
        val (value, scale) = when {
            n >= 1_000_000_000 -> n / 1_000_000_000.0 to Scale.BILLION
            n >= 1_000_000 -> n / 1_000_000.0 to Scale.MILLION
            n >= 1_000 -> n / 1_000.0 to Scale.THOUSAND
            else -> return Compact(digits(n.coerceAtLeast(0).toString(), locale, eastern), Scale.NONE)
        }
        val truncated = kotlin.math.floor(value * 10) / 10
        val number = if (truncated % 1.0 == 0.0) truncated.toLong().toString() else "%.1f".format(Locale.ROOT, truncated)
        val localized = if (locale.language == "ar") number.replace('.', '\u066B') else number
        return Compact(digits(localized, locale, eastern), scale)
    }
}
