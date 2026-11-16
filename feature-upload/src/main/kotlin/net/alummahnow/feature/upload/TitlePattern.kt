package net.alummahnow.feature.upload

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Batch title pattern ("set for all"), the same tokens and rules as the web's shared rename engine
 * (`packages/shared-types/src/bulk-rename.ts`, AlUmmahNow#765):
 * - `{n}`: position among the files, from [start], zero-padded to [pad] digits
 * - `{filename}`: the file name without its last extension
 * - `{date}`: today as a long date in the title's language ("10 November 2026" / "10 نوفمبر 2026"). Titles are
 *   text people edit and the server stores, so they keep Arabic numerals 0–9, like the web engine's default
 * - `{channel}`: the channel's name
 * Nothing else is touched: no trimming, no normalisation. Unknown `{…}` text stays as typed.
 * A title over [MAX_TITLE] characters is refused, not cut.
 */
object TitlePattern {
    const val DEFAULT = "{filename}"
    const val MAX_TITLE = 100
    val TOKENS = listOf("{n}", "{filename}", "{date}", "{channel}")

    private val token = Regex("""\{(n|filename|date|channel|title)\}""")

    fun apply(
        pattern: String,
        fileName: String,
        index: Int,
        today: LocalDate,
        channelName: String,
        locale: Locale = Locale.ENGLISH,
        start: Int = 1,
        pad: Int = 1
    ): String = pattern.ifEmpty { DEFAULT }.replace(token) { m ->
        when (m.groupValues[1]) {
            "n" -> number(index, start, pad)
            "filename" -> stripExtension(fileName)
            "date" -> date(today, locale)
            "channel" -> channelName
            else -> "" // {title}: a new upload has no title yet
        }
    }

    fun number(index: Int, start: Int = 1, pad: Int = 1): String {
        val value = start + index
        val text = kotlin.math.abs(value).toString().padStart(pad.coerceIn(1, MAX_PAD), '0')
        return if (value < 0) "-$text" else text
    }

    /** "Lesson 03.final.mp4" → "Lesson 03.final" (only the last extension). */
    fun stripExtension(name: String): String = name.lastIndexOf('.').let { if (it > 0) name.substring(0, it) else name }

    /** Title text people can edit and the server stores, so its digits stay Arabic numerals 0–9 (not display formatting). */
    fun date(day: LocalDate, locale: Locale): String =
        // display-format: not shown (title content)
        day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(if (locale.language == "ar") locale else Locale.UK))

    /** Characters (code points), the way the server counts the limit. */
    fun length(text: String): Int = text.codePointCount(0, text.length)

    /** Tags typed as "a, b ،c" → unique, trimmed, ≤ 30 tags of ≤ 50 chars. */
    fun tags(text: String): List<String> =
        text.split(',', '،').map {
            it.trim()
        }.filter { it.isNotEmpty() }.map { it.take(MAX_TAG) }.distinctBy { it.lowercase() }.take(MAX_TAGS)

    private const val MAX_TAG = 50
    private const val MAX_TAGS = 30
    private const val MAX_PAD = 9
}
