@file:Suppress("SpreadOperator") // vararg arguments are passed through to String.format

package net.alummahnow.design.format

import android.content.res.Resources
import android.text.format.Formatter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.FormatStyle
import java.util.Locale
import net.alummahnow.design.R

// Compose and Android-resource entry points of [AunFormat]: the app language comes from the configuration.

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

/** The locale of [res] (for code outside composition: notifications, snackbars). */
fun Resources.displayLocale(): Locale = configuration.locales[0]

/** A string resource with arguments; integer arguments get the app's digits (٠–٩ in Arabic). Use instead of `stringResource(id, …)`. */
@Composable
fun aunString(@StringRes id: Int, vararg args: Any?): String =
    AunFormat.format(LocalResources.current.getString(id), currentLocale(), *args)

/** A plural resource with arguments, digits as in [aunString]. Use instead of `pluralStringResource(id, n, …)`. */
@Composable
fun aunPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
    AunFormat.format(LocalResources.current.getQuantityString(id, count), currentLocale(), *args)

/** [aunString] outside composition. */
fun Resources.aunString(@StringRes id: Int, vararg args: Any?): String = AunFormat.format(getString(id), displayLocale(), *args)

/** [aunPlural] outside composition. */
fun Resources.aunPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
    AunFormat.format(getQuantityString(id, count), displayLocale(), *args)

/** A meta line ("Public · 1.2K views"); Arabic uses « • » because «·» reads as «٠». */
@Composable
fun joinMeta(vararg parts: String?): String = AunFormat.joinMeta(parts.toList(), currentLocale())

@Composable
fun joinMeta(parts: List<String?>): String = AunFormat.joinMeta(parts, currentLocale())

@Composable
fun numberLabel(value: Long): String = AunFormat.number(value, currentLocale())

@Composable
fun percentLabel(fraction: Double): String = AunFormat.percent(fraction, currentLocale())

@Composable
fun dateLabel(date: LocalDate, style: FormatStyle = FormatStyle.MEDIUM): String = AunFormat.date(date, currentLocale(), style)

@Composable
fun dateTimeLabel(dateTime: ZonedDateTime): String = AunFormat.dateTime(dateTime, currentLocale())

/** A file size ("12 MB" / "١٢ م.ب"): the platform's units, with the app's digits. */
@Composable
fun fileSizeLabel(bytes: Long): String = AunFormat.decimal(Formatter.formatShortFileSize(LocalContext.current, bytes), currentLocale())

@Composable
fun compactCount(n: Long): String {
    val c = AunFormat.compact(n, currentLocale())
    return when (c.scale) {
        AunFormat.Scale.NONE -> c.number
        AunFormat.Scale.THOUSAND -> aunString(R.string.aun_count_thousand, c.number)
        AunFormat.Scale.MILLION -> aunString(R.string.aun_count_million, c.number)
        AunFormat.Scale.BILLION -> aunString(R.string.aun_count_billion, c.number)
    }
}

/** "1.2K views" / "١٫٢ ألف مشاهدة", with correct Arabic plural forms for small counts. */
@Composable
fun viewsLabel(views: Long): String {
    val quantity = views.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return aunPlural(R.plurals.aun_views, quantity, compactCount(views))
}

@Composable
fun durationLabel(seconds: Long): String = AunFormat.duration(seconds, currentLocale())

/** Numeric text assembled elsewhere (already in Arabic numerals 0–9) shown with the app's digits. */
@Composable
fun localizedDigits(text: String): String = AunFormat.digits(text, currentLocale())

/** Like [localizedDigits] but also uses the Arabic decimal separator (٫) for numbers such as "1.5". */
@Composable
fun localizedDecimal(text: String): String = AunFormat.decimal(text, currentLocale())

/**
 * Isolates user-supplied text (names, e-mails, titles) inside a localised sentence (FSI … PDI), so
 * Latin text in an Arabic sentence (and the reverse) does not reorder the punctuation around it.
 */
fun isolate(text: String): String = "\u2068$text\u2069"
