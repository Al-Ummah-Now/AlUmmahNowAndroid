package net.alummahnow.design.format

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.util.Locale
import net.alummahnow.design.R
import net.alummahnow.design.theme.LocalAunDisplayPrefs

@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]

@Composable
fun compactCount(n: Long): String {
    val c = AunFormat.compact(n, currentLocale(), LocalAunDisplayPrefs.current.easternArabicDigits)
    return when (c.scale) {
        AunFormat.Scale.NONE -> c.number
        AunFormat.Scale.THOUSAND -> stringResource(R.string.aun_count_thousand, c.number)
        AunFormat.Scale.MILLION -> stringResource(R.string.aun_count_million, c.number)
        AunFormat.Scale.BILLION -> stringResource(R.string.aun_count_billion, c.number)
    }
}

/** "1.2K views" / "١٫٢ ألف مشاهدة", with correct Arabic plural forms for small counts. */
@Composable
fun viewsLabel(views: Long): String {
    val quantity = views.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(R.plurals.aun_views, quantity, compactCount(views))
}

@Composable
fun durationLabel(seconds: Long): String =
    AunFormat.duration(seconds, currentLocale(), LocalAunDisplayPrefs.current.easternArabicDigits)

@Composable
fun localizedDigits(text: String): String =
    AunFormat.digits(text, currentLocale(), LocalAunDisplayPrefs.current.easternArabicDigits)

/** Like [localizedDigits] but also uses the Arabic decimal separator (٫) for numbers such as "1.5". */
@Composable
fun localizedDecimal(text: String): String {
    val locale = currentLocale()
    val separated = if (locale.language == "ar") text.replace('.', '\u066B') else text
    return AunFormat.digits(separated, locale, LocalAunDisplayPrefs.current.easternArabicDigits)
}
