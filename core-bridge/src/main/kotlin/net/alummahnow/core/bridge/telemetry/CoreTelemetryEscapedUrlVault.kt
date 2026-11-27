package net.alummahnow.core.bridge.telemetry

internal object CoreTelemetryEscapedUrlVault {
    private const val ESC_VAULT_PREFIX = "\u001EAUNESC"

    fun vaultJsonEscapedUrlsInBalancedQuotes(
        text: String,
        prefix: String,
        vaultedUrls: MutableList<String>,
        vaultRunner: (String, String, MutableList<String>) -> String
    ): String {
        val hasEsc = text.contains("\\/") || text.contains("\\u")
        if (text.indexOf('"') < 0 || !hasEsc) return text
        val spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return text
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsSlashOrUnicodeEscape(text, innerStart, span.close)) {
                val (nextSb, nextCopied) = vaultEscapedSpan(text, span, prefix, vaultedUrls, vaultRunner, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    @Suppress("LongParameterList")
    private fun vaultEscapedSpan(
        text: String,
        span: CoreTelemetryQuotedPathRedactor.QuotedPathSpan,
        prefix: String,
        vaultedUrls: MutableList<String>,
        vaultRunner: (String, String, MutableList<String>) -> String,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val innerStart = span.open + 1
        val norm = decodeUrlEscapesForVault(text, innerStart, span.close)
        if (norm.indexOf("://") < 0) return initialSb to initialCopied
        val innerVaulted = mutableListOf<String>()
        val innerResult = vaultRunner("\"$norm\"", ESC_VAULT_PREFIX, innerVaulted)
        if (innerVaulted.isEmpty()) return initialSb to initialCopied
        val out = initialSb ?: StringBuilder(text.length)
        out.append(text, initialCopied, innerStart)
        val isVerbatimSingle = innerVaulted.size == 1 &&
            innerResult == "\"${ESC_VAULT_PREFIX}0\u001F\"" &&
            innerVaulted[0] == norm
        if (isVerbatimSingle) {
            val idx = vaultedUrls.size
            vaultedUrls += text.substring(innerStart, span.close)
            out.append(prefix).append(idx).append('\u001F')
        } else {
            appendRemappedEscapedVault(out, innerResult, 1, innerResult.length - 1, prefix, vaultedUrls, innerVaulted)
        }
        return out to span.close
    }

    @Suppress("LongParameterList")
    private fun appendRemappedEscapedVault(
        out: StringBuilder,
        text: String,
        start: Int,
        end: Int,
        prefix: String,
        vaultedUrls: MutableList<String>,
        innerVaulted: List<String>
    ) {
        var pos = start
        while (pos < end) {
            val found = text.indexOf(ESC_VAULT_PREFIX, pos)
            if (found < 0 || found >= end) {
                out.append(text, pos, end)
                break
            }
            out.append(text, pos, found)
            val numStart = found + ESC_VAULT_PREFIX.length
            val numEnd = text.indexOf('\u001F', numStart)
            val innerIdx = if (numEnd in (numStart + 1) until end) {
                text.substring(numStart, numEnd).toIntOrNull()
            } else {
                null
            }
            if (innerIdx != null && innerIdx in innerVaulted.indices) {
                val globalIdx = vaultedUrls.size
                vaultedUrls += innerVaulted[innerIdx]
                out.append(prefix).append(globalIdx).append('\u001F')
                pos = numEnd + 1
            } else {
                out.append(ESC_VAULT_PREFIX)
                pos = numStart
            }
        }
    }

    private fun containsSlashOrUnicodeEscape(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            if (text[i] == '\\' && i + 1 < end) {
                val next = text[i + 1]
                if (next == '/' || (next == 'u' && i + 5 < end)) return true
                i += 2
            } else {
                i++
            }
        }
        return false
    }

    private fun isUrlStructuralEscape(ch: Char): Boolean {
        val blockedRange = ch <= '\u0020' || ch in '\u007F'..'\u009F' || ch in '\uD800'..'\uDFFF'
        if (blockedRange || ch.isWhitespace() || Character.isSpaceChar(ch)) return false
        return ch !in "\"\\<>"
    }

    private fun decodeUrlEscapesForVault(text: String, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var pos = start
        while (pos < end) {
            val ch = text[pos]
            if (ch == '\\' && pos + 1 < end) {
                val next = text[pos + 1]
                val uCode = if (next == 'u' && pos + 5 < end) parseHex4(text, pos + 2) else -1
                when {
                    next == '/' -> {
                        sb.append('/')
                        pos += 2
                    }
                    uCode == '"'.code -> {
                        sb.append("\\\"")
                        pos += 6
                    }
                    uCode >= 0 && isUrlStructuralEscape(uCode.toChar()) -> {
                        sb.append(uCode.toChar())
                        pos += 6
                    }
                    else -> {
                        sb.append(ch).append(next)
                        pos += 2
                    }
                }
            } else {
                sb.append(ch)
                pos++
            }
        }
        return sb.toString()
    }

    private fun parseHex4(text: String, start: Int): Int {
        var value = 0
        for (i in 0 until 4) {
            val digit = text[start + i].digitToIntOrNull(16) ?: return -1
            value = (value shl 4) or digit
        }
        return value
    }
}
