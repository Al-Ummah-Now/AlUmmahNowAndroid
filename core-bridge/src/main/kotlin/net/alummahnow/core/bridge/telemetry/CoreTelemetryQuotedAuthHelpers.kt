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
    private const val BASIC_LEN = 5
    private const val REDACTED_LEN = 10
    private const val HEX_DIGITS = "0123456789abcdef"
    private val basicTokenRegex = Regex(
        """\b(Basic)[\s\p{Zs}]+(?:("|'|\\"|\\u0022)[\t\p{Zs}]*([A-Za-z0-9+/_-]{2,}={0,2})""" +
            """(?![A-Za-z0-9+/_=-])(?:[\t\p{Zs}]*(\2|\\"|\\u0022)|(?=[\t\p{Zs}]*(?:\r|\n|\\r|\\n|$)))|""" +
            """([A-Za-z0-9+/_-]{2,}={0,2})(?![A-Za-z0-9+/_=-]))""",
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

    private data class BearerScanFlags(val inDecodedSpan: Boolean)

    fun redactBearerTokens(text: String): String {
        if (!text.contains("bearer", ignoreCase = true)) return text
        val ranges = findBearerTokenRanges(text, BearerScanFlags(inDecodedSpan = false))
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

    private data class BearerQuoteState(val inEscapedQuote: Boolean, val inLiteralDoubleQuote: Boolean)

    private fun findBearerTokenRanges(text: String, flags: BearerScanFlags): List<BearerMatch> {
        val ranges = ArrayList<BearerMatch>()
        var state = BearerQuoteState(inEscapedQuote = false, inLiteralDoubleQuote = false)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val stepped = stepBearerQuoteState(text, i, ch, state)
            if (stepped != null) {
                i = stepped.first
                state = stepped.second
            } else if (!matchesBearerPrefix(text, i)) {
                i++
            } else {
                var wsEnd = i + BEARER_LEN
                while (wsEnd < text.length && CoreTelemetryScanners.isSpace(text[wsEnd])) wsEnd++
                i = advanceAfterBearerPrefix(text, i, wsEnd, state, flags, ranges)
            }
        }
        return ranges
    }

    private fun stepBearerQuoteState(
        text: String,
        pos: Int,
        ch: Char,
        state: BearerQuoteState
    ): Pair<Int, BearerQuoteState>? = when {
        ch == '\\' && pos + 1 < text.length -> {
            val (nextPos, nextEsc) = stepBearerBackslash(text, pos, state.inEscapedQuote)
            nextPos to state.copy(inEscapedQuote = nextEsc)
        }
        ch == '"' -> (pos + 1) to BearerQuoteState(inEscapedQuote = false, inLiteralDoubleQuote = !state.inLiteralDoubleQuote)
        ch == '\r' || ch == '\n' -> (pos + 1) to BearerQuoteState(inEscapedQuote = false, inLiteralDoubleQuote = false)
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

    private fun matchesBearerAt(text: String, pos: Int): Boolean =
        pos + BEARER_LEN < text.length &&
            text.regionMatches(pos, "bearer", 0, BEARER_LEN, ignoreCase = true) &&
            CoreTelemetryScanners.isSpace(text[pos + BEARER_LEN])

    private fun hasRedactedBasicAt(text: String, pos: Int): Boolean {
        val hasPrefix = pos + BASIC_LEN < text.length &&
            text.regionMatches(pos, "basic", 0, BASIC_LEN, ignoreCase = true) &&
            CoreTelemetryScanners.isSpace(text[pos + BASIC_LEN])
        if (!hasPrefix) return false
        var p = pos + BASIC_LEN
        while (p < text.length && CoreTelemetryScanners.isSpace(text[p])) p++
        if (p >= text.length) return false
        val openCh = text[p]
        val openLen = if (openCh == '"' || openCh == '\'') 1 else escapedDoubleQuoteLen(text, p)
        return isRedactedBearerPlaceholder(text, p + openLen, stopAtEscapedQuote = openLen > 1)
    }

    private fun isHorizontalSpace(ch: Char): Boolean =
        CoreTelemetryScanners.isSpace(ch) && ch != '\r' && ch != '\n'

    private fun skipHorizontalBearerSpaces(text: String, pos: Int, allowEscaped: Boolean): Int {
        var p = pos
        while (p < text.length) {
            val step = horizontalBearerSpaceStep(text, p, allowEscaped)
            if (step <= 0) break
            p += step
        }
        return p
    }

    private fun horizontalBearerSpaceStep(text: String, pos: Int, allowEscaped: Boolean): Int {
        val ch = text[pos]
        return when {
            isHorizontalSpace(ch) -> 1
            !allowEscaped || ch != '\\' || pos + 1 >= text.length -> 0
            text[pos + 1] == 't' -> 2
            text[pos + 1] == 'u' && pos + HEX_ESCAPE_LEN <= text.length -> {
                val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
                if (code >= 0 && isHorizontalSpace(code.toChar())) HEX_ESCAPE_LEN else 0
            }
            else -> 0
        }
    }

    private fun isEscapedNewlineAt(text: String, pos: Int): Boolean {
        if (pos + 1 >= text.length || text[pos] != '\\') return false
        val esc = text[pos + 1]
        if (esc == 'n' || esc == 'r') return true
        if (esc == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            return code == '\n'.code || code == '\r'.code
        }
        return false
    }

    private fun resolveQuotedBearerEnd(text: String, innerEnd: Int, openLen: Int, openCh: Char): Int {
        val tail = skipHorizontalBearerSpaces(text, innerEnd, allowEscaped = openLen > 1)
        val closeLen = closingBearerQuoteLen(text, tail, openLen, openCh)
        if (closeLen > 0) return tail + closeLen
        val atLineEnd = tail >= text.length ||
            text[tail] == '\r' ||
            text[tail] == '\n' ||
            (openLen > 1 && isEscapedNewlineAt(text, tail))
        return if (atLineEnd) innerEnd else -1
    }

    private fun hasBearerTokenAt(text: String, pos: Int, state: BearerQuoteState, flags: BearerScanFlags): Boolean {
        if (pos >= text.length) return false
        val wsCh = text[pos]
        val openLen = openingBearerQuoteLen(text, pos, wsCh, state, flags.inDecodedSpan)
        if (openLen > 0) {
            return hasQuotedBearerTokenAt(text, pos, openLen, wsCh)
        }
        return isRedactedBearerPlaceholder(text, pos, state.inEscapedQuote) ||
            scanBearerTokenEnd(text, pos, state.inEscapedQuote) > pos
    }

    private fun hasQuotedBearerTokenAt(text: String, pos: Int, openLen: Int, wsCh: Char): Boolean {
        val innerStart = skipHorizontalBearerSpaces(text, pos + openLen, allowEscaped = openLen > 1)
        val stopEsc = openLen > 1
        if (isRedactedBearerPlaceholder(text, innerStart, stopEsc)) return true
        val innerEnd = scanBearerTokenEnd(text, innerStart, stopEsc)
        return innerEnd > innerStart && resolveQuotedBearerEnd(text, innerEnd, openLen, wsCh) >= 0
    }

    @Suppress("LongParameterList")
    private fun stepRepeatedBearerChain(
        text: String,
        prefixStart: Int,
        wsEnd: Int,
        state: BearerQuoteState,
        flags: BearerScanFlags,
        ranges: MutableList<BearerMatch>
    ): Int {
        var prevBearer = prefixStart
        var lastBearer = wsEnd
        var afterWs = wsEnd
        while (true) {
            var nextWs = lastBearer + BEARER_LEN
            while (nextWs < text.length && CoreTelemetryScanners.isSpace(text[nextWs])) nextWs++
            if (nextWs < text.length && matchesBearerAt(text, nextWs)) {
                prevBearer = lastBearer
                lastBearer = nextWs
            } else {
                afterWs = nextWs
                break
            }
        }
        val hasCred = afterWs < text.length &&
            (
                hasRedactedBasicAt(text, afterWs) ||
                    hasBearerTokenAt(text, afterWs, state, flags)
                )
        if (hasCred) return lastBearer
        ranges += BearerMatch(prevBearer, lastBearer + BEARER_LEN, redactedOnly = false)
        return lastBearer + BEARER_LEN
    }

    @Suppress("LongParameterList")
    private fun advanceAfterBearerPrefix(
        text: String,
        prefixStart: Int,
        wsEnd: Int,
        state: BearerQuoteState,
        flags: BearerScanFlags,
        ranges: MutableList<BearerMatch>
    ): Int {
        if (wsEnd >= text.length) return text.length
        if (matchesBearerAt(text, wsEnd)) {
            return stepRepeatedBearerChain(text, prefixStart, wsEnd, state, flags, ranges)
        }
        if (hasRedactedBasicAt(text, wsEnd)) return wsEnd
        val quotedNext = if (!state.inEscapedQuote) {
            tryScanQuotedBearerToken(text, wsEnd, state, flags, ranges)
        } else {
            -1
        }
        return when {
            quotedNext >= 0 -> quotedNext
            isRedactedBearerPlaceholder(text, wsEnd, state.inEscapedQuote) -> wsEnd + REDACTED_LEN
            else -> {
                val tokEnd = scanBearerTokenEnd(text, wsEnd, state.inEscapedQuote)
                if (tokEnd > wsEnd) ranges += BearerMatch(prefixStart, tokEnd, redactedOnly = false)
                if (tokEnd > wsEnd) tokEnd else wsEnd
            }
        }
    }

    private fun tryScanQuotedBearerToken(
        text: String,
        wsEnd: Int,
        state: BearerQuoteState,
        flags: BearerScanFlags,
        ranges: MutableList<BearerMatch>
    ): Int {
        val wsCh = text[wsEnd]
        val openLen = openingBearerQuoteLen(text, wsEnd, wsCh, state, flags.inDecodedSpan)
        if (openLen <= 0) return -1
        val innerStart = skipHorizontalBearerSpaces(text, wsEnd + openLen, allowEscaped = openLen > 1)
        val stopEsc = openLen > 1
        if (isRedactedBearerPlaceholder(text, innerStart, stopEsc)) return innerStart + REDACTED_LEN
        val innerEnd = scanBearerTokenEnd(text, innerStart, stopEsc)
        val afterEnd = resolveQuotedBearerEnd(text, innerEnd, openLen, wsCh)
        if (afterEnd >= 0) {
            if (innerEnd > innerStart) ranges += BearerMatch(innerStart, innerEnd, redactedOnly = true)
            return afterEnd
        }
        return wsEnd
    }

    private fun openingBearerQuoteLen(
        text: String,
        wsEnd: Int,
        wsCh: Char,
        state: BearerQuoteState,
        inDecodedSpan: Boolean
    ): Int = when {
        state.inEscapedQuote -> 0
        wsCh == '"' -> if (!state.inLiteralDoubleQuote || inDecodedSpan) 1 else 0
        wsCh == '\'' -> 1
        else -> escapedDoubleQuoteLen(text, wsEnd)
    }

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
        if (!stopAtEscapedQuote && isTrailingEscapedQuoteBoundary(text, pos)) return 0
        val isValidHex = text[pos + 1] == 'u' &&
            pos + HEX_ESCAPE_LEN <= text.length &&
            CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2) >= 0
        return if (isValidHex) HEX_ESCAPE_LEN else 2
    }

    private fun isTrailingEscapedQuoteBoundary(text: String, pos: Int): Boolean {
        val escQ = escapedDoubleQuoteLen(text, pos)
        if (escQ <= 0) return false
        val afterQ = pos + escQ
        if (afterQ >= text.length) return true
        val ch = text[afterQ]
        return CoreTelemetryScanners.isSpace(ch) ||
            isBearerStopPunct(ch) ||
            isJsonEscapeBoundaryAfterRedacted(text, afterQ, stopAtEscapedQuote = true)
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
        val flags = BearerScanFlags(inDecodedSpan = true)
        for (match in findBearerTokenRanges(decoded, flags)) {
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

    private fun areBasicQuotesCompatible(openQ: String, closeQ: String): Boolean =
        closeQ.isEmpty() || openQ == closeQ || (openQ.startsWith('\\') && closeQ.startsWith('\\'))

    fun redactBasicTokens(text: String, isValidBasic: (String) -> Boolean): String {
        if (!text.contains("basic", ignoreCase = true)) return text
        var sb: StringBuilder? = null
        var copied = 0
        var pos = 0
        while (pos < text.length) {
            val match = basicTokenRegex.find(text, pos) ?: break
            val quotedToken = match.groups[3]?.value
            val token = quotedToken ?: match.groupValues[5]
            val quotesOk = quotedToken == null || areBasicQuotesCompatible(match.groupValues[2], match.groupValues[4])
            if (!quotesOk || !isValidBasic(token)) {
                pos = match.range.first + BASIC_LEN
            } else {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                val quotedGroup = match.groups[3]
                if (quotedGroup != null) {
                    out.append(text, copied, quotedGroup.range.first).append("[redacted]")
                    copied = quotedGroup.range.last + 1
                } else {
                    out.append(text, copied, match.range.first).append(match.groupValues[1]).append(" [redacted]")
                    copied = match.range.last + 1
                }
                pos = match.range.last + 1
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
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
        var pos = 0
        while (pos < decoded.length) {
            val match = regex.find(decoded, pos) ?: break
            val quotedGroup = match.groups[3]
            val token = quotedGroup?.value ?: match.groupValues[5]
            val quotesOk = quotedGroup == null || areBasicQuotesCompatible(match.groupValues[2], match.groupValues[4])
            if (!quotesOk || !validator(token)) {
                pos = match.range.first + BASIC_LEN
            } else {
                val targetRange = quotedGroup?.range ?: match.range
                val rawStart = rawOffsets[targetRange.first]
                val rawEnd = rawOffsets[targetRange.last + 1]
                if (rawStart >= copied) {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    val repl = if (quotedGroup != null) "[redacted]" else "${match.groupValues[1]} [redacted]"
                    out.append(text, copied, rawStart).append(repl)
                    copied = rawEnd
                }
                pos = match.range.last + 1
            }
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
