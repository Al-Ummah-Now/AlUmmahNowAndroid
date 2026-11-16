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
 * Owner decision (2026-11-15): in Arabic the apps show Eastern Arabic (Hindi) numerals ٠–٩ everywhere. The digits do
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
    private const val ARABIC_GROUPING = '٬'
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

    /** Like [digits], and the decimal point becomes the Arabic decimal separator (٫): "1.5" → "١٫٥". */
    fun decimal(text: String, locale: Locale): String =
        if (isArabic(locale)) digits(text.replace('.', ARABIC_DECIMAL), locale) else text

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
                groupingSeparator = ARABIC_GROUPING
                percent = ARABIC_PERCENT
            }
        } else {
            DecimalFormatSymbols.getInstance(locale)
        }

    private fun decimalFormat(locale: Locale, pattern: String, minFraction: Int, maxFraction: Int): DecimalFormat =
        DecimalFormat(pattern, symbols(locale)).apply {
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
        }

    /** 1234 → "1,234" / "١٬٢٣٤". */
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

    enum class Scale { NONE, THOUSAND, MILLION, BILLION }

    /** The number part of a compact count; the scale word comes from string resources (see [compactCount]). */
    data class Compact(val number: String, val scale: Scale)

    /** 950 → ("950", NONE), 1_250 → ("1.2", THOUSAND), 3_400_000 → ("3.4", MILLION). Truncates, never rounds up. */
    fun compact(n: Long, locale: Locale): Compact {
        val (value, scale) = when {
            n >= 1_000_000_000 -> n / 1_000_000_000.0 to Scale.BILLION
            n >= 1_000_000 -> n / 1_000_000.0 to Scale.MILLION
            n >= 1_000 -> n / 1_000.0 to Scale.THOUSAND
            else -> return Compact(number(n.coerceAtLeast(0), locale), Scale.NONE)
        }
        val truncated = kotlin.math.floor(value * 10) / 10
        return Compact(number(truncated, locale, maxFraction = 1), scale)
    }

    private fun dateFormatter(base: DateTimeFormatter, locale: Locale): DateTimeFormatter {
        val style = if (isArabic(locale)) DecimalStyle.STANDARD.withZeroDigit(EASTERN_ZERO) else DecimalStyle.STANDARD
        return base.withLocale(numberingLocale(locale)).withDecimalStyle(style)
    }

    /** A calendar date in the locale's [style] ("Nov 15, 2026" in English), with ٠–٩ in Arabic. */
    fun date(date: LocalDate, locale: Locale, style: FormatStyle = FormatStyle.MEDIUM): String =
        digits(date.format(dateFormatter(DateTimeFormatter.ofLocalizedDate(style), locale)), locale)

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

    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val MS_PER_SECOND = 1000L
}
