package net.alummahnow.core.bridge.telemetry

/**
 * Isolated balanced-JSON-string auth header scrubbing, iterative O(n) Bearer scanning,
 * and escaped Basic/Bearer token redaction.
 */
@Suppress("TooManyFunctions")
internal object CoreTelemetryQuotedAuthHelpers {
    private const val ASCII_CONTROL_LIMIT = 0x20
    private const val HEX_ESCAPE_LEN = 6
    private const val BEARER_LEN = 6
    private const val REDACTED_LEN = 10
    private const val HEX_DIGITS = "0123456789abcdef"

    fun matchAuthSeparator(text: String, pos: Int): Pair<Int, Boolean>? {
        if (pos >= text.length) return null
        val ch = text[pos]
        if (ch == ':' || ch == '=') return 1 to (ch == ':')
        val hasUnicodeEsc = ch == '\\' && pos + HEX_ESCAPE_LEN <= text.length && text[pos + 1] == 'u'
        if (!hasUnicodeEsc || CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos, 0)) return null
        val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
        return if (code == ':'.code || code == '='.code) HEX_ESCAPE_LEN to (code == ':'.code) else null
    }

    fun findBalancedJsonStringEnd(text: String, open: Int): Int {
        val prev = skipJsonWhitespaceBackward(text, open - 1)
        if (prev >= 0 && text[prev] !in ":=[,{(") return -1
        var pos = open + 1
        var escaped = false
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == '\r' || ch == '\n') return -1
            if (ch == '"' && !escaped) {
                return if (isQuotedSpanEndBoundary(text, pos + 1)) pos else -1
            }
            escaped = ch == '\\' && !escaped
            pos++
        }
        return -1
    }

    private fun isQuotedSpanEndBoundary(text: String, afterPos: Int): Boolean {
        if (afterPos >= text.length || CoreTelemetryScanners.isSpace(text[afterPos])) return true
        val after = CoreTelemetryAuthJsonHelpers.skipJsonWhitespace(text, afterPos)
        return after >= text.length || text[after] in ",}]:;)"
    }

    private fun skipJsonWhitespaceBackward(text: String, start: Int): Int {
        var p = start
        while (p >= 0 && text[p] in " \t\r\n") p--
        return p
    }

    fun containsAuthHeaderCandidate(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val ch = text[i]
            val isUnicodeEsc = ch == '\\' && i + HEX_ESCAPE_LEN <= end && text[i + 1] == 'u'
            if (ch == ':' || ch == '=' || isUnicodeEsc) return true
        }
        return false
    }

    fun escapeJsonStringContent(text: String): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"' || ch == '\\' || ch.code < ASCII_CONTROL_LIMIT) {
                val out = sb ?: StringBuilder(text.length + 8).also { sb = it }
                out.append(text, copied, i).append(escapeSingleJsonChar(ch))
                copied = i + 1
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun escapeSingleJsonChar(ch: Char): String = when (ch) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> formatUnicodeHex(ch.code)
    }

    private fun formatUnicodeHex(code: Int): String = buildString(HEX_ESCAPE_LEN) {
        append("\\u")
        append(HEX_DIGITS[(code ushr 12) and 0xF])
        append(HEX_DIGITS[(code ushr 8) and 0xF])
        append(HEX_DIGITS[(code ushr 4) and 0xF])
        append(HEX_DIGITS[code and 0xF])
    }

    fun redactBearerTokens(text: String): String {
        if (!text.contains("bearer", ignoreCase = true)) return text
        val ranges = findBearerTokenRanges(text)
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var copied = 0
        for ((start, end) in ranges) {
            sb.append(text, copied, start).append(text, start, start + BEARER_LEN).append(" [redacted]")
            copied = end
        }
        return sb.append(text, copied, text.length).toString()
    }

    private fun findBearerTokenRanges(text: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i + BEARER_LEN < text.length) {
            if (!matchesBearerPrefix(text, i)) {
                i++
            } else {
                var wsEnd = i + BEARER_LEN
                while (wsEnd < text.length && CoreTelemetryScanners.isSpace(text[wsEnd])) wsEnd++
                i = advanceAfterBearerPrefix(text, i, wsEnd, ranges)
            }
        }
        return ranges
    }

    private fun matchesBearerPrefix(text: String, pos: Int): Boolean {
        val wordBefore = pos > 0 && CoreTelemetryScanners.isAsciiWordChar(text[pos - 1])
        return !wordBefore &&
            text.regionMatches(pos, "bearer", 0, BEARER_LEN, ignoreCase = true) &&
            CoreTelemetryScanners.isSpace(text[pos + BEARER_LEN])
    }

    private fun advanceAfterBearerPrefix(
        text: String,
        prefixStart: Int,
        wsEnd: Int,
        ranges: MutableList<Pair<Int, Int>>
    ): Int {
        if (wsEnd >= text.length) return text.length
        if (isRedactedBearerPlaceholder(text, wsEnd)) return wsEnd + REDACTED_LEN
        val tokEnd = scanBearerTokenEnd(text, wsEnd)
        if (tokEnd > wsEnd) {
            ranges += prefixStart to tokEnd
            return tokEnd
        }
        return wsEnd
    }

    private fun isRedactedBearerPlaceholder(text: String, pos: Int): Boolean {
        if (!text.regionMatches(pos, "[redacted]", 0, REDACTED_LEN)) return false
        val after = pos + REDACTED_LEN
        if (after >= text.length) return true
        val ch = text[after]
        if (CoreTelemetryScanners.isSpace(ch) || ch in "\"',;)]}") return true
        return isJsonEscapeBoundaryAfterRedacted(text, after)
    }

    private fun isJsonEscapeBoundaryAfterRedacted(text: String, pos: Int): Boolean {
        if (text[pos] != '\\' || pos + 1 >= text.length) return false
        val esc = text[pos + 1]
        if (esc in "nrtbf\"") return true
        if (esc == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            return code >= 0 && CoreTelemetryScanners.isSpace(code.toChar())
        }
        return false
    }

    private fun scanBearerTokenEnd(text: String, start: Int): Int {
        var p = start
        var done = false
        while (p < text.length && !done) {
            val ch = text[p]
            if (ch == '\\') {
                val atTrailing = p + 1 >= text.length || CoreTelemetryScanners.isSpace(text[p + 1])
                p += if (atTrailing) 1 else 2
                if (atTrailing) done = true
            } else if (CoreTelemetryScanners.isSpace(ch) || ch in "\"',;)]}") {
                done = true
            } else {
                p++
            }
        }
        return p
    }

    fun redactEscapedBearerInBalancedQuotes(text: String): String {
        if (text.indexOf('"') < 0 || text.indexOf('\\') < 0) return text
        val spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return text
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && hasBackslashInRange(text, innerStart, span.close)) {
                val (nextSb, nextCopied) = applyEscapedBearerSpan(text, innerStart, span.close, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun applyEscapedBearerSpan(
        text: String,
        innerStart: Int,
        close: Int,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(text, innerStart, close)
        if (!decoded.contains("bearer", ignoreCase = true)) return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        for ((mStart, mEnd) in findBearerTokenRanges(decoded)) {
            val rawStart = rawOffsets[mStart]
            val rawEnd = rawOffsets[mEnd]
            if (rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawStart)
                    .append(decoded, mStart, mStart + BEARER_LEN)
                    .append(" [redacted]")
                copied = rawEnd
            }
        }
        return sb to copied
    }

    fun redactEscapedBasicInBalancedQuotes(
        text: String,
        basicRegex: Regex,
        isValidBasic: (String) -> Boolean
    ): String {
        if (text.indexOf('"') < 0 || text.indexOf('\\') < 0) return text
        val spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return text
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && hasBackslashInRange(text, innerStart, span.close)) {
                val (nextSb, nextCopied) = applyEscapedBasicSpan(
                    text,
                    innerStart,
                    span.close,
                    basicRegex,
                    isValidBasic,
                    sb,
                    copied
                )
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    @Suppress("LongParameterList")
    private fun applyEscapedBasicSpan(
        text: String,
        innerStart: Int,
        close: Int,
        regex: Regex,
        validator: (String) -> Boolean,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(text, innerStart, close)
        if (!decoded.contains("basic", ignoreCase = true)) return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        var match = regex.find(decoded)
        while (match != null) {
            val rawStart = rawOffsets[match.range.first]
            val rawEnd = rawOffsets[match.range.last + 1]
            if (validator(match.groupValues[2]) && rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawStart).append(match.groupValues[1]).append(" [redacted]")
                copied = rawEnd
            }
            match = match.next()
        }
        return sb to copied
    }

    private fun hasBackslashInRange(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (text[i] == '\\') return true
        }
        return false
    }
}
