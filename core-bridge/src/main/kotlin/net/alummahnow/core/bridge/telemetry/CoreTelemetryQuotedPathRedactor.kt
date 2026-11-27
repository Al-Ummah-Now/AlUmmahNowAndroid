package net.alummahnow.core.bridge.telemetry

internal object CoreTelemetryQuotedPathRedactor {
    private data class QuotedPathSpan(
        val close: Int,
        val lastVaultEnd: Int,
        val stopPos: Int
    )

    private data class QuotedScanStep(
        val found: Int,
        val nextPos: Int,
        val nextTokenStart: Int
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
                    val pathPos = findAdjustedPathPos(text, sliceStart, span.close)
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

    private fun findAdjustedPathPos(text: String, sliceStart: Int, close: Int): Int {
        if (sliceStart >= close) return -1
        val pos = findLocalPathStartInQuoted(text, sliceStart, close)
        return if (pos > sliceStart && isOddBackslashesBefore(text, pos, sliceStart)) pos - 1 else pos
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
            val step = stepQuotedPathScanner(text, pos, end, tokenStart)
            found = step.found
            pos = step.nextPos
            tokenStart = step.nextTokenStart
        }
        return found
    }

    private fun stepQuotedPathScanner(text: String, pos: Int, end: Int, tokenStart: Int): QuotedScanStep {
        val ch = text[pos]
        return when {
            isEscapedDoubleQuote(text, pos, end) -> QuotedScanStep(-1, pos + 2, -1)
            CoreTelemetryScanners.isWindowsDriveAt(text, pos) -> checkTrailingNonWs(text, pos, pos + 3, end)
            ch == '/' || isEscapedSlash(text, pos, end) -> {
                val step = if (ch == '\\') 2 else 1
                checkSlashRun(text, pos, pos + step, end, tokenStart)
            }
            tokenStart < 0 && isQuotedHomeOrUncPrefix(text, pos, end) -> checkTrailingNonWs(text, pos, pos + 2, end)
            else -> stepPlainQuotedChar(text, pos, end, tokenStart)
        }
    }

    private fun stepPlainQuotedChar(text: String, pos: Int, end: Int, tokenStart: Int): QuotedScanStep {
        val ch = text[pos]
        return when {
            ch.isWhitespace() || isQuotedPathDelimiter(ch) -> QuotedScanStep(-1, pos + 1, -1)
            ch == '\\' && tokenStart >= 0 -> checkTrailingNonWs(text, tokenStart, pos + 1, end)
            ch == '\\' -> QuotedScanStep(-1, pos + 1, tokenStart)
            else -> QuotedScanStep(-1, pos + 1, if (tokenStart < 0) pos else tokenStart)
        }
    }

    private fun checkSlashRun(text: String, pos: Int, afterPos: Int, end: Int, tokenStart: Int): QuotedScanStep {
        if (tokenStart >= 0) return checkTrailingNonWs(text, tokenStart, afterPos, end)
        val nextRoot = scanSlashRunEnd(text, afterPos, end)
        val found = if (nextRoot < end && !text[nextRoot].isWhitespace()) pos else -1
        return QuotedScanStep(found, nextRoot, -1)
    }

    private fun checkTrailingNonWs(text: String, matchPos: Int, afterPos: Int, end: Int): QuotedScanStep {
        val next = nextNonHorizontalSpace(text, afterPos, end)
        val found = if (next < end && !text[next].isWhitespace()) matchPos else -1
        return QuotedScanStep(found, next, -1)
    }

    private fun isEscapedDoubleQuote(text: String, pos: Int, end: Int): Boolean =
        text[pos] == '\\' && pos + 1 < end && text[pos + 1] == '"'

    private fun isEscapedSlash(text: String, pos: Int, end: Int): Boolean =
        text[pos] == '\\' && pos + 1 < end && text[pos + 1] == '/'

    private fun isQuotedHomeOrUncPrefix(text: String, pos: Int, end: Int): Boolean {
        if (pos + 1 >= end) return false
        val ch = text[pos]
        val next = text[pos + 1]
        return (ch == '~' && next == '/') || (ch == '\\' && next == '\\')
    }

    private fun scanSlashRunEnd(text: String, pos: Int, end: Int): Int {
        var next = pos
        var done = false
        while (next < end && !done) {
            val ch = text[next]
            when {
                ch == '/' || isHorizontalSpace(ch) -> next++
                isEscapedSlash(text, next, end) -> next += 2
                else -> done = true
            }
        }
        return next
    }

    private fun nextNonHorizontalSpace(text: String, pos: Int, end: Int): Int {
        var idx = pos
        while (idx < end && isHorizontalSpace(text[idx])) idx++
        return idx
    }

    private fun isHorizontalSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'

    private fun isQuotedPathDelimiter(ch: Char): Boolean = ch in "\"'<>()[]{}:,;="
}
