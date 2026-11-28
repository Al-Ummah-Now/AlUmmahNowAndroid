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
    private val basicTokenRegex = Regex(
        """\b(Basic)[\s\p{Zs}]+(?:("|'|\\"|\\u0022)([A-Za-z0-9+/_-]{2,}={0,2})""" +
            """(?![A-Za-z0-9+/_=-])(\2|$)|([A-Za-z0-9+/_-]{2,}={0,2})(?![A-Za-z0-9+/_=-]))""",
        RegexOption.IGNORE_CASE
    )

    fun matchAuthSeparator(text: String, pos: Int): Pair<Int, Boolean>? {
        if (pos >= text.length) return null
        val ch = text[pos]
        if (ch == ':' || ch == '=') return 1 to (ch == ':')
        val hasUnicodeEsc = ch == '\\' && pos + HEX_ESCAPE_LEN <= text.length && text[pos + 1] == 'u'
        if (!hasUnicodeEsc || CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos, 0)) return null
        val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
        return if (code == ':'.code || code == '='.code) HEX_ESCAPE_LEN to (code == ':'.code) else null
    }

    fun isJsonStringStartBoundary(text: String, open: Int): Boolean {
        val prev = skipJsonWhitespaceBackward(text, open - 1)
        return prev < 0 || text[prev] in ":=[,{("
    }

    fun findDoubleQuotedSpanEnd(text: String, open: Int): Int {
        var pos = open + 1
        var escaped = false
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == '\r' || ch == '\n') return -1
            if (ch == '"' && !escaped) return pos
            escaped = ch == '\\' && !escaped
            pos++
        }
        return -1
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
            if (ch in ":=[" || isUnicodeEsc) return true
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

    private data class BearerMatch(val start: Int, val end: Int, val redactedOnly: Boolean)

    fun redactBearerTokens(text: String): String {
        if (!text.contains("bearer", ignoreCase = true)) return text
        val ranges = findBearerTokenRanges(text, allowLiteralQuoted = true, requireClosingLiteralQuote = true)
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var copied = 0
        for (match in ranges) {
            sb.append(text, copied, match.start)
            if (match.redactedOnly) {
                sb.append("[redacted]")
            } else {
                sb.append(text, match.start, match.start + BEARER_LEN).append(" [redacted]")
            }
            copied = match.end
        }
        return sb.append(text, copied, text.length).toString()
    }

    private fun escapedDoubleQuoteLen(text: String, pos: Int): Int {
        if (pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] == '"') return 2
        val hasUnicode = pos + HEX_ESCAPE_LEN <= text.length && text[pos] == '\\' && text[pos + 1] == 'u'
        if (hasUnicode && CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2) == '"'.code) return HEX_ESCAPE_LEN
        return 0
    }

    private fun findBearerTokenRanges(
        text: String,
        allowLiteralQuoted: Boolean,
        requireClosingLiteralQuote: Boolean
    ): List<BearerMatch> {
        val ranges = ArrayList<BearerMatch>()
        var inEscapedQuote = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val stepped = stepBearerQuoteState(text, i, ch, inEscapedQuote)
            if (stepped != null) {
                i = stepped.first
                inEscapedQuote = stepped.second
            } else if (!matchesBearerPrefix(text, i)) {
                i++
            } else {
                var wsEnd = i + BEARER_LEN
                while (wsEnd < text.length && CoreTelemetryScanners.isSpace(text[wsEnd])) wsEnd++
                i = advanceAfterBearerPrefix(
                    text,
                    i,
                    wsEnd,
                    inEscapedQuote,
                    allowLiteralQuoted,
                    requireClosingLiteralQuote,
                    ranges
                )
            }
        }
        return ranges
    }

    private fun stepBearerQuoteState(text: String, pos: Int, ch: Char, inEscapedQuote: Boolean): Pair<Int, Boolean>? =
        when {
            ch == '\\' && pos + 1 < text.length -> stepBearerBackslash(text, pos, inEscapedQuote)
            ch == '"' || ch == '\r' || ch == '\n' -> (pos + 1) to false
            else -> null
        }

    private fun stepBearerBackslash(text: String, pos: Int, inEscapedQuote: Boolean): Pair<Int, Boolean> {
        val next = text[pos + 1]
        if (next == '"') return (pos + 2) to !inEscapedQuote
        if (next == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            if (code >= 0) {
                return (pos + HEX_ESCAPE_LEN) to (if (code == '"'.code) !inEscapedQuote else inEscapedQuote)
            }
        }
        return (pos + 2) to inEscapedQuote
    }

    private fun matchesBearerPrefix(text: String, pos: Int): Boolean {
        if (pos + BEARER_LEN >= text.length) return false
        val wordBefore = pos > 0 && CoreTelemetryScanners.isAsciiWordChar(text[pos - 1])
        return !wordBefore &&
            text.regionMatches(pos, "bearer", 0, BEARER_LEN, ignoreCase = true) &&
            CoreTelemetryScanners.isSpace(text[pos + BEARER_LEN])
    }

    @Suppress("LongParameterList")
    private fun advanceAfterBearerPrefix(
        text: String,
        prefixStart: Int,
        wsEnd: Int,
        stopAtEscapedQuote: Boolean,
        allowLiteralQuoted: Boolean,
        requireClosingLiteralQuote: Boolean,
        ranges: MutableList<BearerMatch>
    ): Int {
        if (wsEnd >= text.length) return text.length
        val quotedNext = if (!stopAtEscapedQuote) {
            tryScanQuotedBearerToken(text, wsEnd, allowLiteralQuoted, requireClosingLiteralQuote, ranges)
        } else {
            -1
        }
        return when {
            quotedNext >= 0 -> quotedNext
            isRedactedBearerPlaceholder(text, wsEnd, stopAtEscapedQuote) -> wsEnd + REDACTED_LEN
            else -> {
                val tokEnd = scanBearerTokenEnd(text, wsEnd, stopAtEscapedQuote)
                if (tokEnd > wsEnd) ranges += BearerMatch(prefixStart, tokEnd, redactedOnly = false)
                if (tokEnd > wsEnd) tokEnd else wsEnd
            }
        }
    }

    private fun tryScanQuotedBearerToken(
        text: String,
        wsEnd: Int,
        allowLiteralQuoted: Boolean,
        requireClosingLiteralQuote: Boolean,
        ranges: MutableList<BearerMatch>
    ): Int {
        val wsCh = text[wsEnd]
        val openLen = openingBearerQuoteLen(text, wsEnd, wsCh, allowLiteralQuoted)
        if (openLen <= 0) return -1
        val innerStart = wsEnd + openLen
        val stopEsc = openLen > 1
        if (isRedactedBearerPlaceholder(text, innerStart, stopEsc)) return innerStart + REDACTED_LEN
        val innerEnd = scanBearerTokenEnd(text, innerStart, stopEsc)
        val closeLen = closingBearerQuoteLen(text, innerEnd, openLen, wsCh)
        val allowUnclosed = openLen == 1 && !requireClosingLiteralQuote
        if (innerEnd > innerStart && (closeLen > 0 || allowUnclosed)) {
            ranges += BearerMatch(innerStart, innerEnd, redactedOnly = true)
            return innerEnd + closeLen
        }
        return if (innerEnd == innerStart && closeLen > 0) innerEnd + closeLen else -1
    }

    private fun openingBearerQuoteLen(text: String, wsEnd: Int, wsCh: Char, allowLiteralQuoted: Boolean): Int =
        if (allowLiteralQuoted && (wsCh == '"' || wsCh == '\'')) 1 else escapedDoubleQuoteLen(text, wsEnd)

    private fun closingBearerQuoteLen(text: String, innerEnd: Int, openLen: Int, wsCh: Char): Int =
        if (openLen == 1) {
            if (innerEnd < text.length && text[innerEnd] == wsCh) 1 else 0
        } else {
            escapedDoubleQuoteLen(text, innerEnd)
        }

    private fun isBearerStopPunct(ch: Char): Boolean = ch in "\"',;)]}"

    private fun isRedactedBearerPlaceholder(text: String, pos: Int, stopAtEscapedQuote: Boolean): Boolean {
        if (!text.regionMatches(pos, "[redacted]", 0, REDACTED_LEN)) return false
        val after = pos + REDACTED_LEN
        if (after >= text.length) return true
        val ch = text[after]
        if (CoreTelemetryScanners.isSpace(ch) || isBearerStopPunct(ch)) return true
        return isJsonEscapeBoundaryAfterRedacted(text, after, stopAtEscapedQuote)
    }

    private fun isJsonEscapeBoundaryAfterRedacted(text: String, pos: Int, stopAtEscapedQuote: Boolean): Boolean {
        if (text[pos] != '\\' || pos + 1 >= text.length) return false
        val esc = text[pos + 1]
        if (esc in "nrtbf" || (stopAtEscapedQuote && esc == '"')) return true
        if (esc == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            if (code >= 0) {
                val decoded = code.toChar()
                return CoreTelemetryScanners.isSpace(decoded) || (stopAtEscapedQuote && decoded == '"')
            }
        }
        return false
    }

    private fun scanBearerTokenEnd(text: String, start: Int, stopAtEscapedQuote: Boolean): Int {
        var p = start
        var done = false
        while (p < text.length && !done) {
            val ch = text[p]
            if (ch == '\\') {
                val step = scanBearerEscapeStep(text, p, stopAtEscapedQuote)
                if (step <= 0) {
                    if (step < 0) p++
                    done = true
                } else {
                    p += step
                }
            } else if (CoreTelemetryScanners.isSpace(ch) || isBearerStopPunct(ch)) {
                done = true
            } else {
                p++
            }
        }
        return p
    }

    private fun scanBearerEscapeStep(text: String, pos: Int, stopAtEscapedQuote: Boolean): Int {
        if (pos + 1 >= text.length || CoreTelemetryScanners.isSpace(text[pos + 1])) return -1
        if (isJsonEscapeBoundaryAfterRedacted(text, pos, stopAtEscapedQuote)) return 0
        val isValidHex = text[pos + 1] == 'u' &&
            pos + HEX_ESCAPE_LEN <= text.length &&
            CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2) >= 0
        return if (isValidHex) HEX_ESCAPE_LEN else 2
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
        for (match in findBearerTokenRanges(decoded, allowLiteralQuoted = true, requireClosingLiteralQuote = false)) {
            val rawStart = rawOffsets[match.start]
            val rawEnd = rawOffsets[match.end]
            if (rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawStart)
                if (match.redactedOnly) {
                    out.append("[redacted]")
                } else {
                    out.append(decoded, match.start, match.start + BEARER_LEN).append(" [redacted]")
                }
                copied = rawEnd
            }
        }
        return sb to copied
    }

    fun redactBasicTokens(text: String, isValidBasic: (String) -> Boolean): String =
        basicTokenRegex.replace(text) { match ->
            val quotedToken = match.groups[3]?.value
            val token = quotedToken ?: match.groupValues[5]
            if (!isValidBasic(token)) {
                match.value
            } else if (quotedToken != null) {
                "${match.groupValues[1]} ${match.groupValues[2]}[redacted]${match.groupValues[4]}"
            } else {
                "${match.groupValues[1]} [redacted]"
            }
        }

    fun redactEscapedBasicInBalancedQuotes(
        text: String,
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
                    basicTokenRegex,
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
            val quotedGroup = match.groups[3]
            val token = quotedGroup?.value ?: match.groupValues[5]
            val targetRange = quotedGroup?.range ?: match.range
            val rawStart = rawOffsets[targetRange.first]
            val rawEnd = rawOffsets[targetRange.last + 1]
            if (validator(token) && rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                val repl = if (quotedGroup != null) "[redacted]" else "${match.groupValues[1]} [redacted]"
                out.append(text, copied, rawStart).append(repl)
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
