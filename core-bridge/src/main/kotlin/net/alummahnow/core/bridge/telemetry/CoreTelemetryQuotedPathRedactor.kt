package net.alummahnow.core.bridge.telemetry

internal object CoreTelemetryQuotedPathRedactor {
    private data class QuotedPathSpan(
        val close: Int,
        val lastVaultEnd: Int,
        val stopPos: Int
    )

    fun redactDoubleQuotedLocalPaths(text: String): String {
        if (text.indexOf('"') < 0) return text
        var sb: StringBuilder? = null
        var copied = 0
        var idx = 0
        while (idx < text.length) {
            if (text[idx] != '"' || isOddBackslashesBefore(text, idx, copied)) {
                idx++
            } else {
                val span = findDoubleQuotedPathEnd(text, idx + 1)
                if (span.close < 0) {
                    idx = span.stopPos + 1
                } else {
                    val sliceStart = if (span.lastVaultEnd > idx + 1) span.lastVaultEnd else idx + 1
                    val pathPos = if (sliceStart < span.close) findLocalPathStartInQuoted(text, sliceStart, span.close) else -1
                    if (pathPos >= 0) {
                        val out = sb ?: StringBuilder(text.length).also { sb = it }
                        out.append(text, copied, pathPos).append("[redacted-path]")
                        copied = span.close
                    }
                    idx = span.close + 1
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun isOddBackslashesBefore(text: String, pos: Int, minPos: Int): Boolean {
        var bs = 0
        while (pos - 1 - bs >= minPos && text[pos - 1 - bs] == '\\') bs++
        return (bs and 1) == 1
    }

    private fun findDoubleQuotedPathEnd(text: String, start: Int): QuotedPathSpan {
        var lastVaultEnd = -1
        var escaped = false
        var pos = start
        var result: QuotedPathSpan? = null
        while (pos < text.length && result == null) {
            val ch = text[pos]
            when {
                ch == '\r' || ch == '\n' -> result = QuotedPathSpan(-1, lastVaultEnd, pos)
                ch == '\u001F' -> {
                    lastVaultEnd = pos + 1
                    escaped = false
                    pos++
                }
                ch == '"' && !escaped -> result = QuotedPathSpan(pos, lastVaultEnd, pos)
                else -> {
                    escaped = ch == '\\' && !escaped
                    pos++
                }
            }
        }
        return result ?: QuotedPathSpan(-1, lastVaultEnd, text.length)
    }

    private fun findLocalPathStartInQuoted(text: String, start: Int, end: Int): Int {
        var tokenStart = -1
        var pos = start
        var found = -1
        while (pos < end && found < 0) {
            val ch = text[pos]
            when {
                isEscapedDoubleQuote(text, pos, end) -> {
                    tokenStart = -1
                    pos += 2
                }
                isWindowsDrivePath(text, pos, end) -> found = pos
                tokenStart < 0 && isQuotedLocalPathStart(text, pos, end) -> found = pos
                ch.isWhitespace() || isQuotedPathDelimiter(ch) -> {
                    tokenStart = -1
                    pos++
                }
                ch == '/' || ch == '\\' -> {
                    if (tokenStart >= 0 && hasNonWhitespaceAfter(text, pos + 1, end)) {
                        found = tokenStart
                    } else {
                        tokenStart = -1
                        pos++
                    }
                }
                else -> {
                    if (tokenStart < 0) tokenStart = pos
                    pos++
                }
            }
        }
        return found
    }

    private fun isEscapedDoubleQuote(text: String, pos: Int, end: Int): Boolean =
        text[pos] == '\\' && pos + 1 < end && text[pos + 1] == '"'

    private fun isWindowsDrivePath(text: String, pos: Int, end: Int): Boolean =
        CoreTelemetryScanners.isWindowsDriveAt(text, pos) && hasNonWhitespaceAfter(text, pos + 3, end)

    private fun isQuotedLocalPathStart(text: String, pos: Int, end: Int): Boolean {
        val ch = text[pos]
        return when {
            ch == '/' -> hasRootedPathContent(text, pos, end)
            ch == '~' && pos + 1 < end && text[pos + 1] == '/' -> hasNonWhitespaceAfter(text, pos + 2, end)
            ch == '\\' && pos + 1 < end && text[pos + 1] == '\\' -> hasNonWhitespaceAfter(text, pos + 2, end)
            else -> false
        }
    }

    private fun hasRootedPathContent(text: String, pos: Int, end: Int): Boolean {
        var next = pos + 1
        while (next < end && text[next] == '/') next++
        while (next < end && isHorizontalSpace(text[next])) next++
        return next < end && !text[next].isWhitespace() && text[next] != '/'
    }

    private fun hasNonWhitespaceAfter(text: String, pos: Int, end: Int): Boolean {
        var idx = pos
        while (idx < end && isHorizontalSpace(text[idx])) idx++
        return idx < end && !text[idx].isWhitespace()
    }

    private fun isHorizontalSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'

    private fun isQuotedPathDelimiter(ch: Char): Boolean = ch in "\"'<>()[]{}:,;="
}
