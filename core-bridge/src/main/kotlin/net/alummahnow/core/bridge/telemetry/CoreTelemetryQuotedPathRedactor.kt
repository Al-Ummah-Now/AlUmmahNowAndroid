package net.alummahnow.core.bridge.telemetry

@Suppress("TooManyFunctions")
internal object CoreTelemetryQuotedPathRedactor {
    internal data class QuotedPathSpan(
        val open: Int,
        val close: Int,
        val hasVault: Boolean,
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
                    val (nextSb, nextCopied) = redactSingleQuotedSpan(text, span, sb, copied)
                    sb = nextSb
                    copied = nextCopied
                    idx = span.close + 1
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun redactSingleQuotedSpan(
        text: String,
        span: QuotedPathSpan,
        sb: StringBuilder?,
        copied: Int
    ): Pair<StringBuilder?, Int> {
        if (!span.hasVault) {
            return redactQuotedLocalPathSegment(text, span.open + 1, span.close, false, sb, copied)
        }
        return redactQuotedNonVaultSegments(text, span.open + 1, span.close, sb, copied)
    }

    @Suppress("LongParameterList")
    private fun redactQuotedLocalPathSegment(
        text: String,
        segStart: Int,
        segEnd: Int,
        beforeVault: Boolean,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        var sb = initialSb
        var copied = initialCopied
        var cur = segStart
        var done = false
        while (cur < segEnd && !done) {
            val pathPos = findAdjustedPathPos(text, cur, segEnd)
            if (pathPos < 0) {
                done = true
            } else {
                val closeEsc = if (isPrecededByEscapedQuote(text, pathPos, cur)) {
                    findClosingEscapedQuote(text, pathPos, segEnd)
                } else {
                    null
                }
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, pathPos).append("[redacted-path]")
                if (closeEsc != null) {
                    copied = closeEsc.first
                    cur = closeEsc.first + closeEsc.second
                } else {
                    copied = trimVaultLeadingSpace(text, pathPos, segEnd, beforeVault)
                    done = true
                }
            }
        }
        return sb to copied
    }

    fun findInnerEscapedQuoteClose(text: String, pathPos: Int, minPos: Int, segEnd: Int): Int {
        if (!isPrecededByEscapedQuote(text, pathPos, minPos)) return -1
        return findClosingEscapedQuote(text, pathPos, segEnd)?.first ?: -1
    }

    private fun isPrecededByEscapedQuote(text: String, pos: Int, minPos: Int): Boolean {
        val simple = pos - 2 >= minPos && text[pos - 2] == '\\' && text[pos - 1] == '"'
        if (simple && !isOddBackslashesBefore(text, pos - 2, minPos)) return true
        val unicode = pos - 6 >= minPos && text[pos - 6] == '\\' && text[pos - 5] == 'u'
        if (!unicode || isOddBackslashesBefore(text, pos - 6, minPos)) return false
        return parseHex4(text, pos - 4) == '"'.code
    }

    private fun findClosingEscapedQuote(text: String, start: Int, end: Int): Pair<Int, Int>? {
        var pos = start
        var innerEscaped = false
        while (pos < end) {
            val dec = decodeJsonCharAt(text, pos, end)
            if (innerEscaped) {
                innerEscaped = false
            } else if (dec.ch == '\\') {
                innerEscaped = true
            } else if (dec.ch == '"' && dec.len > 1) {
                return pos to dec.len
            }
            pos += dec.len
        }
        return null
    }

    fun replacePreKeywordPathRules(text: String, rules: List<Pair<Regex, String>>): String {
        var current = text
        var masked: String? = null
        var hasMask = false
        for ((regex, template) in rules) {
            if (current.indexOf('"') < 0) {
                current = regex.replace(current, template)
            } else {
                if (!hasMask) {
                    val spans = collectBalancedDoubleQuoteSpans(current)
                    masked = if (spans.isEmpty()) null else buildMaskedDoubleQuotes(current, spans)
                    hasMask = true
                }
                val hasPrefix = template.startsWith("$1")
                val mask = masked
                val next = if (mask != null) {
                    applyMaskedPathRegex(current, mask, regex, hasPrefix)
                } else {
                    regex.replace(current, template)
                }
                if (next !== current) {
                    current = next
                    hasMask = false
                }
            }
        }
        return current
    }

    fun replaceKeywordAssignedPaths(text: String, regex: Regex): String {
        if (text.indexOf('"') < 0) return regex.replace(text, "$1[redacted-path]")
        var spans = collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return regex.replace(text, "$1[redacted-path]")
        val masked = buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = applyMaskedPathRegex(text, masked, regex, hasPrefix = true)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return replaceKeywordAssignedInSpans(afterUnquoted, spans, regex)
    }

    private fun replaceKeywordAssignedInSpans(
        current: String,
        spans: List<QuotedPathSpan>,
        regex: Regex
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsAssignCandidate(current, innerStart, span.close)) {
                val (nextSb, nextCopied) = replaceAssignedInDecodedSpan(current, innerStart, span.close, regex, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    @Suppress("LongParameterList")
    private fun replaceAssignedInDecodedSpan(
        current: String,
        innerStart: Int,
        close: Int,
        regex: Regex,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val (decoded, rawOffsets) = decodeQuotedSpanWithOffsets(current, innerStart, close)
        var match = regex.find(decoded) ?: return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        while (true) {
            val g1 = match.groups[1]
            val rawG1End = rawOffsets[if (g1 != null) g1.range.last + 1 else match.range.first]
            val rawEnd = rawOffsets[match.range.last + 1]
            val out = sb ?: StringBuilder(current.length).also { sb = it }
            out.append(current, copied, rawG1End).append("[redacted-path]")
            copied = rawEnd
            match = match.next() ?: break
        }
        return sb to copied
    }

    private fun containsAssignCandidate(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val ch = text[i]
            val isUnicodeEsc = ch == '\\' && i + 5 < end && text[i + 1] == 'u'
            if (ch == ':' || ch == '=' || isUnicodeEsc) return true
        }
        return false
    }

    fun decodeQuotedSpanWithOffsets(text: String, start: Int, end: Int): Pair<String, IntArray> {
        val cap = end - start
        val chars = CharArray(cap)
        val offsets = IntArray(cap + 1)
        var count = 0
        var pos = start
        while (pos < end) {
            offsets[count] = pos
            val dec = decodeJsonCharAt(text, pos, end)
            chars[count++] = if (dec.len > 1 && (dec.ch == '\u001E' || dec.ch == '\u001F')) '_' else dec.ch
            pos += dec.len
        }
        offsets[count] = end
        return String(chars, 0, count) to offsets
    }

    fun buildMaskedDoubleQuotes(text: String, spans: List<QuotedPathSpan>): String {
        val chars = text.toCharArray()
        for (span in spans) {
            for (k in (span.open + 1) until span.close) {
                if (chars[k] != '\u001E' && chars[k] != '\u001F') chars[k] = 'a'
            }
        }
        return String(chars)
    }

    fun applyMaskedPathRegex(text: String, masked: String, regex: Regex, hasPrefix: Boolean): String {
        var match = regex.find(masked) ?: return text
        val sb = StringBuilder(text.length)
        var copied = 0
        while (true) {
            val start = match.range.first
            val end = match.range.last + 1
            sb.append(text, copied, start)
            if (hasPrefix) {
                val g1 = match.groups[1]
                if (g1 != null) sb.append(text, g1.range.first, g1.range.last + 1)
            }
            sb.append("[redacted-path]")
            copied = end
            match = match.next() ?: break
        }
        return sb.append(text, copied, text.length).toString()
    }

    fun collectBalancedDoubleQuoteSpans(text: String): List<QuotedPathSpan> {
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
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        var sb = initialSb
        var copied = initialCopied
        var segStart = start
        var done = false
        while (segStart < close && !done) {
            val vaultStart = findCharInRange(text, '\u001E', segStart, close)
            val segEnd = if (vaultStart >= 0) vaultStart else close
            if (segStart < segEnd) {
                val (nextSb, nextCopied) = redactQuotedLocalPathSegment(
                    text,
                    segStart,
                    segEnd,
                    vaultStart >= 0,
                    sb,
                    copied
                )
                sb = nextSb
                copied = nextCopied
            }
            val vaultEnd = if (vaultStart >= 0) findCharInRange(text, '\u001F', vaultStart + 1, close) else -1
            if (vaultEnd >= 0) {
                segStart = vaultEnd + 1
            } else {
                done = true
            }
        }
        return sb to copied
    }

    private fun findCharInRange(text: String, target: Char, start: Int, end: Int): Int {
        for (i in start until end) {
            if (text[i] == target) return i
        }
        return -1
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
        var hasVault = false
        var escaped = false
        var pos = open + 1
        var result: QuotedPathSpan? = null
        while (pos < text.length && result == null) {
            val ch = text[pos]
            if (ch == '\u001E') hasVault = true
            when {
                ch == '\r' || ch == '\n' -> result = QuotedPathSpan(open, -1, hasVault, pos)
                ch == '\u001F' -> {
                    escaped = false
                    pos++
                }
                ch == '"' && !escaped -> result = QuotedPathSpan(open, pos, hasVault, pos)
                else -> {
                    escaped = ch == '\\' && !escaped
                    pos++
                }
            }
        }
        return result ?: QuotedPathSpan(open, -1, hasVault, text.length)
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

    fun parseHex4(text: String, pos: Int): Int {
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
        if (isExactProtocolVersion(text, start, end)) return -1
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
        val driveAfter = if (tokenStart < 0) matchDecodedWindowsDrive(text, pos, end, dec0) else -1
        val isFileScheme = tokenStart < 0 &&
            (dec0.ch == 'f' || dec0.ch == 'F' || dec0.ch == 'c' || dec0.ch == 'C') &&
            matchDecodedFileScheme(text, pos, end)
        val homeAfter = if (tokenStart < 0) matchDecodedHome(text, pos, end, dec0) else -1
        val isBackslashSep = dec0.ch == '\\' && (tokenStart >= 0 || dec0.len > 1)
        return when {
            driveAfter >= 0 -> checkTrailingNonWs(text, pos, driveAfter, end)
            isFileScheme -> QuotedScanStep(pos, pos + dec0.len, tokenStart)
            dec0.ch == '/' || isBackslashSep -> checkSepRun(text, pos, pos + dec0.len, end, dec0.ch, tokenStart)
            homeAfter >= 0 -> checkTrailingNonWs(text, pos, homeAfter, end)
            else -> stepPlainQuotedChar(pos, dec0, tokenStart)
        }
    }

    private fun isExactProtocolVersion(text: String, start: Int, end: Int): Boolean {
        var pos = decodeProtocolPrefix(text, start, end)
        if (pos < 0 || pos >= end) return false
        val slash = decodeJsonCharAt(text, pos, end)
        if (slash.ch != '/') return false
        pos += slash.len
        var hasDigit = false
        var expectDigit = true
        while (pos < end) {
            val dec = decodeJsonCharAt(text, pos, end)
            if (dec.ch in '0'..'9') {
                hasDigit = true
                expectDigit = false
            } else if (dec.ch == '.' && !expectDigit) {
                expectDigit = true
            } else {
                return false
            }
            pos += dec.len
        }
        return hasDigit && !expectDigit
    }

    private fun decodeProtocolPrefix(text: String, start: Int, end: Int): Int {
        var pos = start
        val buf = CharArray(4)
        var count = 0
        while (pos < end && count < 4) {
            val dec = decodeJsonCharAt(text, pos, end)
            if (dec.ch == '/') break
            buf[count++] = dec.ch.lowercaseChar()
            pos += dec.len
        }
        val prefix = String(buf, 0, count)
        return if (prefix == "http" || prefix == "tls" || prefix == "ssl") pos else -1
    }

    private fun matchDecodedFileScheme(text: String, pos: Int, end: Int): Boolean =
        matchDecodedSchemePrefix(text, pos, end, "file:/") ||
            matchDecodedSchemePrefix(text, pos, end, "content://")

    private fun matchDecodedSchemePrefix(text: String, pos: Int, end: Int, target: String): Boolean {
        var cursor = pos
        for (expected in target) {
            if (cursor >= end) return false
            val dec = decodeJsonCharAt(text, cursor, end)
            if (dec.ch.lowercaseChar() != expected) return false
            cursor += dec.len
        }
        return true
    }

    fun vaultJsonEscapedUrlsInBalancedQuotes(
        text: String,
        prefix: String,
        vaultedUrls: MutableList<String>,
        vaultRunner: (String, String, MutableList<String>) -> String
    ): String = CoreTelemetryEscapedUrlVault.vaultJsonEscapedUrlsInBalancedQuotes(
        text,
        prefix,
        vaultedUrls,
        vaultRunner
    )

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

    private fun matchDecodedHome(text: String, pos: Int, end: Int, dec0: DecodedJsonChar): Int {
        if (dec0.ch != '~' || pos + dec0.len >= end) return -1
        val p1 = pos + dec0.len
        val dec1 = decodeJsonCharAt(text, p1, end)
        return if (dec1.ch == '/') p1 + dec1.len else -1
    }

    private fun stepPlainQuotedChar(pos: Int, dec0: DecodedJsonChar, tokenStart: Int): QuotedScanStep = when {
        isDecodedWhitespace(dec0.ch) || isQuotedPathDelimiter(dec0.ch) -> QuotedScanStep(-1, pos + dec0.len, -1)
        else -> QuotedScanStep(-1, pos + dec0.len, if (tokenStart < 0) pos else tokenStart)
    }

    @Suppress("LongParameterList")
    private fun checkSepRun(
        text: String,
        pos: Int,
        afterPos: Int,
        end: Int,
        runChar: Char,
        tokenStart: Int
    ): QuotedScanStep {
        if (tokenStart >= 0) return checkTrailingNonWs(text, tokenStart, afterPos, end)
        val (nextRoot, stopCh) = scanCharRunEnd(text, afterPos, end, runChar)
        val found = if (nextRoot < end && !isDecodedWhitespace(stopCh)) pos else -1
        return QuotedScanStep(found, nextRoot, -1)
    }

    private fun checkTrailingNonWs(text: String, matchPos: Int, afterPos: Int, end: Int): QuotedScanStep {
        val (next, stopCh) = nextNonHorizontalSpaceDecoded(text, afterPos, end)
        val found = if (next < end && !isDecodedWhitespace(stopCh)) matchPos else -1
        return QuotedScanStep(found, next, -1)
    }

    private fun scanCharRunEnd(text: String, pos: Int, end: Int, runChar: Char): Pair<Int, Char> {
        var next = pos
        var stopCh = '\u0000'
        var done = false
        while (next < end && !done) {
            val dec = decodeJsonCharAt(text, next, end)
            if (dec.ch == runChar || isHorizontalSpace(dec.ch)) {
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
