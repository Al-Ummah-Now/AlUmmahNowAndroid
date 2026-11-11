package net.alummahnow.feature.upload

import java.time.LocalDate

/**
 * Batch title pattern ("set for all"). Tokens use the web upload templates' `{{…}}` syntax:
 * - `{{filename}}`: the file name without its extension
 * - `{{n}}`: position in the batch (1, 2, 3 …); `{{nn}}` zero-pads to the batch size (01 … 12)
 * - `{{total}}`: number of files in the batch
 * - `{{date}}`: today, ISO (2026-11-10); `{{year}}`, `{{month}}`, `{{day}}`
 * - `{{channel_name}}`: the channel uploaded to
 * Unknown tokens are left as typed so a typo is visible in the preview.
 */
object TitlePattern {
    const val DEFAULT = "{{filename}}"
    const val MAX_TITLE = 100
    val TOKENS = listOf("{{filename}}", "{{n}}", "{{nn}}", "{{total}}", "{{date}}", "{{channel_name}}")

    private val token = Regex("""\{\{\s*([a-z_]+)\s*\}\}""")

    fun apply(pattern: String, fileName: String, index: Int, total: Int, today: LocalDate, channelName: String): String {
        val n = index + 1
        val width = total.toString().length
        val values = mapOf(
            "filename" to fileName.substringBeforeLast('.').ifBlank { fileName },
            "n" to n.toString(),
            "nn" to n.toString().padStart(width, '0'),
            "total" to total.toString(),
            "date" to today.toString(),
            "year" to today.year.toString(),
            "month" to today.monthValue.toString().padStart(2, '0'),
            "day" to today.dayOfMonth.toString().padStart(2, '0'),
            "channel_name" to channelName
        )
        val out = token.replace(pattern.ifBlank { DEFAULT }) { m -> values[m.groupValues[1]] ?: m.value }
        return out.replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)
    }

    /** Tags typed as "a, b ,c" → unique, trimmed, ≤ 30 tags of ≤ 50 chars. */
    fun tags(text: String): List<String> =
        text.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(MAX_TAG) }.distinctBy { it.lowercase() }.take(MAX_TAGS)

    private const val MAX_TAG = 50
    private const val MAX_TAGS = 30
}
