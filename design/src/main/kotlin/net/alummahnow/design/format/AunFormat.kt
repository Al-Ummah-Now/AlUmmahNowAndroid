@file:Suppress("SpreadOperator") // vararg arguments are passed through to String.format

package net.alummahnow.design.format

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * The one display formatter of the Android apps. Every number, count, percentage, date, time, duration,
 * file size and amount of money that people see goes through here (a CI guard, `scripts/check-display-format.py`,
 * rejects display formatting anywhere else).
 *
 * Owner decisions (2026-11-15, 2026-11-21): in Arabic the apps show Eastern Arabic (Hindi) numerals ٠–٩ everywhere, never
 * grouped («٤٨٨٧», «١٢٣٤٫٥»): in most Arabic UI fonts a thousands mark «٬» looks like the decimal mark «٫», so «٫» is the
 * only comma-like mark. English keeps "4,887". The digits do
 * not depend on the device region or numbering settings: Arabic formatting always uses the `arab` numbering system
 * (`ar-u-nu-arab`) with explicit symbols, and English keeps Arabic numerals 0–9. Codes, ids, URLs, version numbers
 * and text people type stay as typed; [normalizeDigits] turns ٠–٩ typed into numeric fields into 0–9.
 *
 * Pure functions of their arguments, so they are unit-tested on the JVM.
 */
object AunFormat {
    private const val EASTERN_ZERO = '٠'
    private const val EXTENDED_ZERO = '۰'
    private const val ARABIC_DECIMAL = '٫'
    private const val ARABIC_PERCENT = '٪'

    /** Arabic UI → Eastern Arabic (Hindi) numerals. */
    fun isArabic(locale: Locale): Boolean = locale.language == "ar"

    /** The locale with an explicit numbering system: Arabic → `ar-u-nu-arab`, others unchanged. */
    fun numberingLocale(locale: Locale): Locale =
        if (isArabic(locale)) Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("nu", "arab").build() else locale

    /** Replaces Arabic numerals 0–9 in already-assembled numeric text with ٠–٩ (Arabic UI only). */
    fun digits(text: String, locale: Locale): String {
        if (!isArabic(locale)) return text
        return buildString(text.length) {
            text.forEach { c -> append(if (c in '0'..'9') EASTERN_ZERO + (c - '0') else c) }
        }
    }

    /**
     * Like [digits], and a decimal point between two digits becomes the Arabic decimal separator (٫): "1.5" → "١٫٥".
     * Dots elsewhere (the one in the «م.ب» abbreviation) stay.
     */
    fun decimal(text: String, locale: Locale): String =
        if (isArabic(locale)) digits(DECIMAL_POINT.replace(text, ARABIC_DECIMAL.toString()), locale) else text

    private val DECIMAL_POINT = Regex("(?<=[0-9\u0660-\u0669])\\.(?=[0-9\u0660-\u0669])")

    /**
     * The separator between values in a meta line ("Public · 1.2K views · Nov 6"). In Arabic a dot («·» or «•») reads as
     * the digit zero «٠» («عام • ١٢ ألف» looks like «١٢٠ ألف»), so Arabic uses the Arabic comma: «عام، ١٢ ألف مشاهدة».
     */
    fun separator(locale: Locale): String = if (isArabic(locale)) "\u060C " else " \u00B7 "

    /**
     * A list of user-supplied words (tags, keywords) in a sentence: «سباحة، رياضة» in Arabic, "swimming, sport" in
     * English. Each item keeps its own direction ([isolate]), so a Latin tag in an Arabic list cannot reorder the commas.
     */
    fun joinList(items: List<String>, locale: Locale): String =
        items.filter { it.isNotBlank() }.joinToString(if (isArabic(locale)) "\u060C " else ", ") { isolate(it) }

    /**
     * Joins the non-blank [parts] of a meta line with [separator]. Each part is a direction [isolate], so an Arabic
     * channel name in an English line (or English in an Arabic one) keeps its own order and cannot pull a neighbouring
     * number into it ("1.5 · الأمة الآنK views").
     */
    fun joinMeta(parts: List<String?>, locale: Locale): String =
        parts.filterNot { it.isNullOrBlank() }.joinToString(separator(locale)) { isolate(it!!) }

    /**
     * Keeps [text] in its own direction inside a line. Left-to-right text, or text with no letters (a time, «٨٢٪»), is
     * wrapped in LRI (U+2066) … PDI (U+2069). Arabic text without digits is wrapped in RLI (U+2067) … PDI, which keeps
     * an Arabic channel name from pulling the number after it in an English line. Arabic text holding digits is
     * bracketed by right-to-left marks (U+200F) instead: Android text layout reorders digits inside a right-to-left
     * isolate or embedding, so «عند ٠:٤٢» drew as «٠:٤٢عند» and «٧٣٤ م.ب» as «م.ب٧٣٤» (#53). Letters inside nested
     * isolates do not count.
     */
    fun isolate(text: String): String = when {
        !firstStrongIsRtl(text) -> LRI + text + PDI
        text.any(Char::isDigit) -> RLM + text + RLM
        else -> RLI + text + PDI
    }

    private fun firstStrongIsRtl(text: String): Boolean {
        var depth = 0
        for (c in text) {
            when (c) {
                LRI, RLI, FSI -> depth++
                PDI -> if (depth > 0) depth--
                else -> if (depth == 0) {
                    when (Character.getDirectionality(c)) {
                        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                    }
                }
            }
        }
        return false
    }

    private const val LRI = '\u2066'
    private const val RLI = '\u2067'
    private const val FSI = '\u2068'
    private const val PDI = '\u2069'
    private const val RLM = '\u200F'

    /** ٠–٩ and ۰–۹ typed by people become 0–9, so numeric inputs and codes parse the same in every language. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        text.forEach { c ->
            append(
                when (c) {
                    in EASTERN_ZERO..EASTERN_ZERO + 9 -> '0' + (c - EASTERN_ZERO)
                    in EXTENDED_ZERO..EXTENDED_ZERO + 9 -> '0' + (c - EXTENDED_ZERO)
                    else -> c
                }
            )
        }
    }

    private fun symbols(locale: Locale): DecimalFormatSymbols =
        if (isArabic(locale)) {
            DecimalFormatSymbols.getInstance(Locale.ROOT).apply {
                zeroDigit = EASTERN_ZERO
                decimalSeparator = ARABIC_DECIMAL
                monetaryDecimalSeparator = ARABIC_DECIMAL
                percent = ARABIC_PERCENT
            }
        } else {
            DecimalFormatSymbols.getInstance(locale)
        }

    private fun decimalFormat(locale: Locale, pattern: String, minFraction: Int, maxFraction: Int): DecimalFormat =
        DecimalFormat(pattern, symbols(locale)).apply {
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
            if (isArabic(locale)) isGroupingUsed = false
        }

    /** 1234 → "1,234" / "١٢٣٤". */
    fun number(value: Long, locale: Locale, grouping: Boolean = true): String =
        decimalFormat(locale, if (grouping) "#,##0" else "0", 0, 0).format(value)

    /** 1.5 → "1.5" / "١٫٥", with [maxFraction] digits at most. */
    fun number(value: Double, locale: Locale, maxFraction: Int = 1, minFraction: Int = 0): String =
        decimalFormat(locale, "#,##0.#", minFraction, maxFraction).format(value)

    /** 0.42 → "42%" / "٤٢٪". */
    fun percent(fraction: Double, locale: Locale, maxFraction: Int = 0): String =
        decimalFormat(locale, "#,##0%", 0, maxFraction).format(fraction)

    /** 75 → "1:15", 3725 → "1:02:05" (Arabic: "١:١٥"). Durations keep the clock order, as in the web player. */
    fun duration(totalSeconds: Long, locale: Locale): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / SECONDS_PER_HOUR
        val m = (s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val sec = s % SECONDS_PER_MINUTE
        val two = { v: Long -> v.toString().padStart(2, '0') }
        val raw = if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
        return digits(raw, locale)
    }

    /** A position in a video: 42_000 ms → "0:42" / "٠:٤٢". */
    fun timecode(ms: Long, locale: Locale): String = duration(ms / MS_PER_SECOND, locale)

    /**
     * Capped notification badge count (`null` when `<= 0`, `"1"`..`"9"` / `"١"`..`"٩"`, `"9+"` / `"٩+"` when `>= 10`).
     */
    fun badgeCap9(count: Int, locale: Locale): String? = when {
        count <= 0 -> null
        count > BADGE_CAP -> "${digits(BADGE_CAP.toString(), locale)}+"
        else -> digits(count.toString(), locale)
    }

    /** Clock minute from midnight (`0..1439`) as `"22:00"` / `"٢٢:٠٠"`. */
    fun minuteOfDay(minute: Int, locale: Locale): String {
        val clamped = ((minute % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val h = (clamped / MINUTES_PER_HOUR).toString().padStart(2, '0')
        val m = (clamped % MINUTES_PER_HOUR).toString().padStart(2, '0')
        return digits("$h:$m", locale)
    }

    enum class Scale { NONE, THOUSAND, MILLION, BILLION }

    /**
     * Which form of the scale word the shown number takes. Arabic never puts a digit before 1 or the dual: exactly 1 is
     * the word alone («ألف»), exactly 2 the dual alone («ألفان», «ألفا مشاهدة» before a noun), a whole 3–10 the plural
     * after the digit («٣ آلاف»). A whole number ending in 11–99 takes the accusative when it stands alone («١٢ ألفًا»,
     * «١٢٠ ألفًا») and the singular before a noun («١٢ ألف مشاهدة»): [TAMYIZ]. Anything else (100, any decimal) is the
     * singular after the number («١٠٠ ألف», «١٫٣ ألف»).
     */
    enum class ScaleForm { ONE, DUAL, PLURAL, TAMYIZ, SINGULAR }

    /** The number part of a compact count; the scale word comes from string resources (see [compactCount]). */
    data class Compact(val number: String, val scale: Scale, val form: ScaleForm = ScaleForm.SINGULAR)

    /** 950 → ("950", NONE), 1_250 → ("1.2", THOUSAND), 3_400_000 → ("3.4", MILLION). Truncates, never rounds up. */
    fun compact(n: Long, locale: Locale): Compact {
        val (value, scale) = when {
            n >= 1_000_000_000 -> n / 1_000_000_000.0 to Scale.BILLION
            n >= 1_000_000 -> n / 1_000_000.0 to Scale.MILLION
            n >= 1_000 -> n / 1_000.0 to Scale.THOUSAND
            else -> return Compact(number(n.coerceAtLeast(0), locale), Scale.NONE)
        }
        val truncated = kotlin.math.floor(value * 10) / 10
        val whole = truncated == kotlin.math.floor(truncated)
        val form = when {
            // Millions are always «{n} م» («١ م», «١٫٢ م»; owner 2026-11-21): no word forms to agree with.
            scale == Scale.MILLION -> ScaleForm.SINGULAR
            whole && truncated == ONE_SCALE -> ScaleForm.ONE
            whole && truncated == DUAL_SCALE -> ScaleForm.DUAL
            whole && truncated in PLURAL_SCALE_MIN..PLURAL_SCALE_MAX -> ScaleForm.PLURAL
            whole && (truncated.toLong() % HUNDRED) in TAMYIZ_MIN..TAMYIZ_MAX -> ScaleForm.TAMYIZ
            else -> ScaleForm.SINGULAR
        }
        return Compact(number(truncated, locale, maxFraction = 1), scale, form)
    }

    enum class SizeUnit { BYTE, KILO, MEGA, GIGA, TERA }

    /** The number part of a file size; the unit comes from string resources (see `fileSizeLabel`). */
    data class FileSize(val number: String, val unit: SizeUnit)

    /**
     * A file size in decimal units (1 kB = 1000 bytes, as Android and the web show sizes): 734_003_200 → ("734", MEGA),
     * 1_534_000 → ("1.5", MEGA), 512 → ("512", BYTE). One decimal below 10, none above; cut, never rounded up, like
     * counts. Arabic gets ٠–٩ and «٫», never grouped.
     */
    fun fileSize(bytes: Long, locale: Locale): FileSize {
        var value = bytes.coerceAtLeast(0).toDouble()
        var unit = SizeUnit.BYTE
        while (value >= SIZE_STEP && unit != SizeUnit.TERA) {
            value /= SIZE_STEP
            unit = SizeUnit.entries[unit.ordinal + 1]
        }
        val shown = if (unit != SizeUnit.BYTE && value < ONE_DECIMAL_BELOW) {
            kotlin.math.floor(value * 10) / 10
        } else {
            kotlin.math.floor(value)
        }
        return FileSize(number(shown, locale, maxFraction = 1), unit)
    }

    private fun dateFormatter(base: DateTimeFormatter, locale: Locale): DateTimeFormatter {
        val style = if (isArabic(locale)) DecimalStyle.STANDARD.withZeroDigit(EASTERN_ZERO) else DecimalStyle.STANDARD
        return base.withLocale(numberingLocale(locale)).withDecimalStyle(style)
    }

    /** A calendar date in the locale's [style] ("Nov 15, 2026" in English), with ٠–٩ in Arabic. */
    fun date(date: LocalDate, locale: Locale, style: FormatStyle = FormatStyle.MEDIUM): String =
        digits(date.format(dateFormatter(DateTimeFormatter.ofLocalizedDate(style), locale)), locale)

    /** A short day and month for chart axes ("6 Oct" / «٦ أكتوبر»), with ٠–٩ in Arabic. */
    fun dayMonth(date: LocalDate, locale: Locale): String =
        digits(date.format(dateFormatter(DateTimeFormatter.ofPattern("d MMM"), locale)), locale)

    /** A date and time in the locale's styles, with ٠–٩ in Arabic. */
    fun dateTime(
        dateTime: ZonedDateTime,
        locale: Locale,
        dateStyle: FormatStyle = FormatStyle.MEDIUM,
        timeStyle: FormatStyle = FormatStyle.SHORT
    ): String = digits(dateTime.format(dateFormatter(DateTimeFormatter.ofLocalizedDateTime(dateStyle, timeStyle), locale)), locale)

    /** Money: "$12.69" in English; in Arabic the amount with ٠–٩ and the currency's Arabic name ("١٢٫٦٩ دولار أمريكي"). */
    fun money(amount: Double, currencyCode: String, locale: Locale): String {
        val currency = Currency.getInstance(currencyCode)
        val fraction = currency.defaultFractionDigits.coerceAtLeast(0)
        if (isArabic(locale)) {
            val name = ARABIC_CURRENCY_NAMES[currency.currencyCode] ?: currency.getDisplayName(locale)
            return "${number(amount, locale, maxFraction = fraction, minFraction = fraction)} $name"
        }
        return (java.text.NumberFormat.getCurrencyInstance(locale) as DecimalFormat).apply {
            this.currency = currency
            minimumFractionDigits = fraction
            maximumFractionDigits = fraction
        }.format(amount)
    }

    /**
     * Formats a string resource pattern. Integer arguments (`%d`, `%1$d`) go through [number], so their digits
     * follow the rules above instead of the platform's `String.format` locale data; other arguments are unchanged.
     */
    fun format(pattern: String, locale: Locale, vararg args: Any?): String {
        val converted = args.map { arg ->
            when (arg) {
                is Int -> number(arg.toLong(), locale, grouping = false)
                is Long -> number(arg, locale, grouping = false)
                is Short -> number(arg.toLong(), locale, grouping = false)
                else -> arg
            }
        }
        return String.format(Locale.ROOT, INTEGER_PLACEHOLDER.replace(pattern, "%$1s"), *converted.toTypedArray())
    }

    private val INTEGER_PLACEHOLDER = Regex("%(\\d+\\$)?d")

    /** Names that read naturally in Arabic text; any other currency uses the platform's Arabic name. */
    private val ARABIC_CURRENCY_NAMES = mapOf(
        "USD" to "دولار أمريكي",
        "EUR" to "يورو",
        "GBP" to "جنيه إسترليني",
        "SAR" to "ريال سعودي",
        "AED" to "درهم إماراتي",
        "EGP" to "جنيه مصري"
    )

    private const val SIZE_STEP = 1000.0
    private const val ONE_DECIMAL_BELOW = 10.0
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val MS_PER_SECOND = 1000L
    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 1440
    private const val BADGE_CAP = 9
    private const val ONE_SCALE = 1.0
    private const val HUNDRED = 100L
    private const val TAMYIZ_MIN = 11L
    private const val TAMYIZ_MAX = 99L
    private const val DUAL_SCALE = 2.0
    private const val PLURAL_SCALE_MIN = 3.0
    private const val PLURAL_SCALE_MAX = 10.0
}
