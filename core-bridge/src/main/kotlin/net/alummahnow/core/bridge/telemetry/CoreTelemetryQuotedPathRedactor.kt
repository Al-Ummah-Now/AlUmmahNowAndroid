package net.alummahnow.core.bridge.telemetry

@Suppress("TooManyFunctions")
internal object CoreTelemetryQuotedPathRedactor {
    private data class QuotedPathSpan(
        val open: Int,
        val close: Int,
        val stopPos: Int
    )

    private data class QuotedScanStep(
        val found: Int,
        val nextPos: Int,
        val nextTokenStart: Int
    )

    private data class DecodedJsonChar(
        val ch: Char,
        val len: Int
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
                val span = findDoubleQuotedPathEnd(text, idx)
                if (span.close < 0) {
                    idx = span.stopPos + 1
                } else {
                    val out = sb ?: StringBuilder(text.length)
                    val prevCopied = copied
                    copied = redactQuotedNonVaultSegments(text, idx + 1, span.close, out, copied)
                    if (copied != prevCopied) sb = out
                    idx = span.close + 1
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    fun replaceOutsideBalancedDoubleQuotes(text: String, regex: Regex, template: String): String {
        if (text.indexOf('"') < 0) return regex.replace(text, template)
        val spans = collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return regex.replace(text, template)
        val hasPrefix = template.startsWith("$1")
        var spanIdx = 0
        return regex.replace(text) { match ->
            val checkPos = matchContentCheckPos(text, match, hasPrefix)
            while (spanIdx < spans.size && spans[spanIdx].close <= checkPos) spanIdx++
            val inside = spanIdx < spans.size && spans[spanIdx].open < checkPos && checkPos < spans[spanIdx].close
            if (inside) {
                match.value
            } else if (hasPrefix) {
                "${match.groupValues[1]}[redacted-path]"
            } else {
                "[redacted-path]"
            }
        }
    }

    fun replaceKeywordAssignedPaths(text: String, regex: Regex): String {
        if (text.indexOf('"') < 0) return regex.replace(text, "$1[redacted-path]")
        val spans = collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return regex.replace(text, "$1[redacted-path]")
        var spanIdx = 0
        return regex.replace(text) { match ->
            val pos = match.range.first
            while (spanIdx < spans.size && spans[spanIdx].close <= pos) spanIdx++
            val end = match.range.last + 1
            val insideSpan = spanIdx < spans.size && spans[spanIdx].open < pos && pos < spans[spanIdx].close
            if (insideSpan && end > spans[spanIdx].close) {
                val tail = match.value.substring(spans[spanIdx].close - pos)
                "${match.groupValues[1]}[redacted-path]$tail"
            } else {
                "${match.groupValues[1]}[redacted-path]"
            }
        }
    }

    private fun matchContentCheckPos(text: String, match: MatchResult, hasPrefix: Boolean): Int {
        if (hasPrefix) return match.range.first + match.groupValues[1].length
        val loc = match.range.first
        return if (text[loc] == '"' && loc + 1 < text.length) loc + 1 else loc
    }

    private fun collectBalancedDoubleQuoteSpans(text: String): List<QuotedPathSpan> {
        val spans = ArrayList<QuotedPathSpan>()
        var idx = 0
        while (idx < text.length) {
            if (text[idx] != '"' || isOddBackslashesBefore(text, idx, 0)) {
                idx++
            } else {
                val span = findDoubleQuotedPathEnd(text, idx)
                if (span.close < 0) {
                    idx = span.stopPos + 1
                } else {
                    spans += span
                    idx = span.close + 1
                }
            }
        }
        return spans
    }

    private fun redactQuotedNonVaultSegments(
        text: String,
        start: Int,
        close: Int,
        out: StringBuilder,
        initialCopied: Int
    ): Int {
        var copied = initialCopied
        var segStart = start
        var done = false
        while (segStart < close && !done) {
            val rawVault = text.indexOf('\u001E', segStart)
            val vaultStart = if (rawVault in segStart until close) rawVault else -1
            val segEnd = if (vaultStart >= 0) vaultStart else close
            val pathPos = findAdjustedPathPos(text, segStart, segEnd)
            if (pathPos >= 0) {
                val redactEnd = trimVaultLeadingSpace(text, pathPos, segEnd, vaultStart >= 0)
                out.append(text, copied, pathPos).append("[redacted-path]")
                copied = redactEnd
            }
            val rawEnd = if (vaultStart >= 0) text.indexOf('\u001F', vaultStart + 1) else -1
            if (rawEnd in (vaultStart + 1) until close) {
                segStart = rawEnd + 1
            } else {
                done = true
            }
        }
        return copied
    }

    private fun trimVaultLeadingSpace(text: String, pathPos: Int, segEnd: Int, beforeVault: Boolean): Int {
        if (!beforeVault) return segEnd
        var end = segEnd
        while (end > pathPos && isHorizontalSpace(text[end - 1])) end--
        return end
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

    private fun findDoubleQuotedPathEnd(text: String, open: Int): QuotedPathSpan {
        var escaped = false
        var pos = open + 1
        var result: QuotedPathSpan? = null
        while (pos < text.length && result == null) {
            val ch = text[pos]
            when {
                ch == '\r' || ch == '\n' -> result = QuotedPathSpan(open, -1, pos)
                ch == '\u001F' -> {
                    escaped = false
                    pos++
                }
                ch == '"' && !escaped -> result = QuotedPathSpan(open, pos, pos)
                else -> {
                    escaped = ch == '\\' && !escaped
                    pos++
                }
            }
        }
        return result ?: QuotedPathSpan(open, -1, text.length)
    }

    private fun decodeJsonCharAt(text: String, pos: Int, end: Int): DecodedJsonChar {
        val ch = text[pos]
        if (ch != '\\' || pos + 1 >= end) return DecodedJsonChar(ch, 1)
        val next = text[pos + 1]
        val simple = simpleJsonEscape(next)
        if (simple != null) return DecodedJsonChar(simple, 2)
        if (next == 'u' && pos + 5 < end) {
            val code = parseHex4(text, pos + 2)
            if (code >= 0) return DecodedJsonChar(code.toChar(), 6)
        }
        return DecodedJsonChar(ch, 1)
    }

    private fun simpleJsonEscape(ch: Char): Char? = when (ch) {
        '"' -> '"'
        '/' -> '/'
        '\\' -> '\\'
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        else -> null
    }

    private fun parseHex4(text: String, pos: Int): Int {
        var value = 0
        for (offset in 0 until 4) {
            val d = hexDigitValue(text[pos + offset])
            if (d < 0) return -1
            value = (value shl 4) or d
        }
        return value
    }

    private fun hexDigitValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> 10 + (ch - 'a')
        in 'A'..'F' -> 10 + (ch - 'A')
        else -> -1
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
        val dec0 = decodeJsonCharAt(text, pos, end)
        val driveAfter = matchDecodedWindowsDrive(text, pos, end, dec0)
        val prefixAfter = if (tokenStart < 0) matchDecodedHomeOrUnc(text, pos, end, dec0) else -1
        return when {
            driveAfter >= 0 -> checkTrailingNonWs(text, pos, driveAfter, end)
            dec0.ch == '/' -> checkSlashRun(text, pos, pos + dec0.len, end, tokenStart)
            prefixAfter >= 0 -> checkTrailingNonWs(text, pos, prefixAfter, end)
            else -> stepPlainQuotedChar(text, pos, end, dec0, tokenStart)
        }
    }

    private fun matchDecodedWindowsDrive(text: String, pos: Int, end: Int, dec0: DecodedJsonChar): Int {
        val isLetter = dec0.ch in 'A'..'Z' || dec0.ch in 'a'..'z'
        if (!isLetter || pos + dec0.len >= end) return -1
        val p1 = pos + dec0.len
        val dec1 = decodeJsonCharAt(text, p1, end)
        if (dec1.ch != ':' || p1 + dec1.len >= end) return -1
        val p2 = p1 + dec1.len
        val dec2 = decodeJsonCharAt(text, p2, end)
        return if (dec2.ch == '/' || dec2.ch == '\\') p2 + dec2.len else -1
    }

    private fun matchDecodedHomeOrUnc(text: String, pos: Int, end: Int, dec0: DecodedJsonChar): Int {
        if ((dec0.ch != '~' && dec0.ch != '\\') || pos + dec0.len >= end) return -1
        val p1 = pos + dec0.len
        val dec1 = decodeJsonCharAt(text, p1, end)
        val matched = (dec0.ch == '~' && dec1.ch == '/') || (dec0.ch == '\\' && dec1.ch == '\\')
        return if (matched) p1 + dec1.len else -1
    }

    private fun stepPlainQuotedChar(
        text: String,
        pos: Int,
        end: Int,
        dec0: DecodedJsonChar,
        tokenStart: Int
    ): QuotedScanStep = when {
        isDecodedWhitespace(dec0.ch) || isQuotedPathDelimiter(dec0.ch) -> QuotedScanStep(-1, pos + dec0.len, -1)
        dec0.ch == '\\' && tokenStart >= 0 -> checkTrailingNonWs(text, tokenStart, pos + dec0.len, end)
        dec0.ch == '\\' -> QuotedScanStep(-1, pos + dec0.len, tokenStart)
        else -> QuotedScanStep(-1, pos + dec0.len, if (tokenStart < 0) pos else tokenStart)
    }

    private fun checkSlashRun(text: String, pos: Int, afterPos: Int, end: Int, tokenStart: Int): QuotedScanStep {
        if (tokenStart >= 0) return checkTrailingNonWs(text, tokenStart, afterPos, end)
        val (nextRoot, stopCh) = scanSlashRunEnd(text, afterPos, end)
        val found = if (nextRoot < end && !isDecodedWhitespace(stopCh)) pos else -1
        return QuotedScanStep(found, nextRoot, -1)
    }

    private fun checkTrailingNonWs(text: String, matchPos: Int, afterPos: Int, end: Int): QuotedScanStep {
        val (next, stopCh) = nextNonHorizontalSpaceDecoded(text, afterPos, end)
        val found = if (next < end && !isDecodedWhitespace(stopCh)) matchPos else -1
        return QuotedScanStep(found, next, -1)
    }

    private fun scanSlashRunEnd(text: String, pos: Int, end: Int): Pair<Int, Char> {
        var next = pos
        var stopCh = '\u0000'
        var done = false
        while (next < end && !done) {
            val dec = decodeJsonCharAt(text, next, end)
            if (dec.ch == '/' || isHorizontalSpace(dec.ch)) {
                next += dec.len
            } else {
                stopCh = dec.ch
                done = true
            }
        }
        return next to stopCh
    }

    private fun nextNonHorizontalSpaceDecoded(text: String, pos: Int, end: Int): Pair<Int, Char> {
        var idx = pos
        var stopCh = '\u0000'
        var done = false
        while (idx < end && !done) {
            val dec = decodeJsonCharAt(text, idx, end)
            if (isHorizontalSpace(dec.ch)) {
                idx += dec.len
            } else {
                stopCh = dec.ch
                done = true
            }
        }
        return idx to stopCh
    }

    private fun isDecodedWhitespace(ch: Char): Boolean = ch.isWhitespace() || ch == '\b'

    private fun isHorizontalSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'

    private fun isQuotedPathDelimiter(ch: Char): Boolean = ch in "\"'<>()[]{}:,;="
}
