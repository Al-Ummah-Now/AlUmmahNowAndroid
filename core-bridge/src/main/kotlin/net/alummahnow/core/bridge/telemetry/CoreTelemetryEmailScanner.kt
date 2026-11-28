package net.alummahnow.core.bridge.telemetry

import java.net.URI

/**
 * Linear O(n) RFC 5322 / RFC 6531 email scanner.
 */
internal object CoreTelemetryEmailScanner {
    private const val ENCODED_AT_LEN = 3
    private const val UNICODE_AT_LEN = 6
    private const val MAX_IP_LITERAL_LEN = 64
    private const val EMAIL_LOCAL_PUNCT = "._+-/!#$&'*=?^`{|}~"
    private const val IPV6_FORBIDDEN = "[@\"'<>\\{}\r\n"
    private val LEADING_UNICODE_WRAPPER_CATEGORIES = setOf(
        Character.START_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION
    )

    private class EmailQuoteCache {
        var validUntil = -1
        var nextQuote = -1

        fun reset() {
            validUntil = -1
            nextQuote = -1
        }
    }

    private enum class DomainCharKind {
        OTHER,
        LETTER,
        DIGIT,
        DOT,
        HYPHEN
    }

    fun hasEmailAt(text: String): Boolean =
        text.indexOf('@') >= 0 || text.indexOf("%40") >= 0 || text.indexOf("\\u0040") >= 0

    fun containsWhitespace(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (CoreTelemetryScanners.isSpace(text[i])) return true
        }
        return false
    }

    fun emailAtLength(text: String, pos: Int): Int = when {
        text[pos] == '@' -> 1
        isEncodedAt(text, pos) -> ENCODED_AT_LEN
        isUnicodeEscapedAt(text, pos) -> UNICODE_AT_LEN
        else -> 0
    }

    private fun isUnicodeEscapedAt(text: String, pos: Int): Boolean =
        text.regionMatches(pos, "\\u0040", 0, UNICODE_AT_LEN) && !isPrecededByOddBackslashes(text, pos, 0)

    fun findEmailTailEnd(text: String, pos: Int): Int {
        var p = pos
        while (p < text.length && isEmailLocalChar(text, p)) p++
        if (p >= text.length) return -1
        val atLen = emailAtLength(text, p)
        return if (atLen > 0) findEmailDomainEnd(text, p + atLen) else -1
    }

    fun redactQuotedEmails(text: String): String {
        val current = redactQuotedEmailsRaw(text)
        return redactEmailsInEscapedDoubleQuotes(current, quotedOnly = true)
    }

    private fun redactQuotedEmailsRaw(text: String): String {
        if (text.indexOf('"') < 0 || !hasEmailAt(text)) return text
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        val quoteCache = EmailQuoteCache()
        while (i < text.length) {
            val quotedEnd = matchQuotedEmail(text, i, copied, quoteCache)
            if (quotedEnd > 0) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, i).append("[redacted-email]")
                copied = quotedEnd
                i = quotedEnd
                quoteCache.reset()
            } else {
                i++
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    fun redactEmails(text: String): String {
        val current = redactEmailsRaw(text)
        return redactEmailsInEscapedDoubleQuotes(current, quotedOnly = false)
    }

    private fun redactEmailsRaw(text: String): String {
        if (!hasEmailAt(text)) return text
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        val quoteCache = EmailQuoteCache()
        while (i < text.length) {
            val match = matchEmailAt(text, i, copied, quoteCache)
            if (match != null) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, match.first).append("[redacted-email]")
                copied = match.second
                i = match.second
                quoteCache.reset()
            } else {
                val atLen = emailAtLength(text, i)
                i += if (atLen > 0) atLen else 1
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun redactEmailsInEscapedDoubleQuotes(text: String, quotedOnly: Boolean): String {
        val hasCandidate = text.indexOf('"') >= 0 &&
            text.indexOf('\\') >= 0 &&
            (hasEmailAt(text) || text.contains("\\u"))
        if (!hasCandidate) return text
        val spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return text
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && (innerStart until span.close).any { text[it] == '\\' }) {
                val (nextSb, nextCopied) = applyEscapedEmailSpan(text, innerStart, span.close, quotedOnly, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    @Suppress("LongParameterList")
    private fun applyEscapedEmailSpan(
        text: String,
        innerStart: Int,
        close: Int,
        quotedOnly: Boolean,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(text, innerStart, close)
        if (!hasEmailAt(decoded) || (quotedOnly && decoded.indexOf('"') < 0)) return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        for ((mStart, mEnd) in findEmailMatchRanges(decoded, quotedOnly)) {
            val rawStart = rawOffsets[mStart]
            val rawEnd = rawOffsets[mEnd]
            if (rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawStart).append("[redacted-email]")
                copied = rawEnd
            }
        }
        return sb to copied
    }

    private fun findEmailMatchRanges(text: String, quotedOnly: Boolean): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        var copied = 0
        var i = 0
        val quoteCache = EmailQuoteCache()
        while (i < text.length) {
            val match = if (quotedOnly) {
                val qEnd = matchQuotedEmail(text, i, copied, quoteCache)
                if (qEnd > 0) i to qEnd else null
            } else {
                matchEmailAt(text, i, copied, quoteCache)
            }
            if (match != null) {
                ranges += match
                copied = match.second
                i = match.second
                quoteCache.reset()
            } else {
                val atLen = if (quotedOnly) 0 else emailAtLength(text, i)
                i += if (atLen > 0) atLen else 1
            }
        }
        return ranges
    }

    private fun matchEmailAt(text: String, i: Int, copied: Int, quoteCache: EmailQuoteCache): Pair<Int, Int>? {
        val quotedEnd = matchQuotedEmail(text, i, copied, quoteCache)
        if (quotedEnd > 0) return i to quotedEnd
        val atLen = emailAtLength(text, i)
        if (atLen == 0) return null
        val requireDot = i > copied && text[i - 1] == '/'
        val domEnd = findEmailDomainEnd(text, i + atLen, requireDot)
        val localStart = if (domEnd > 0) scanEmailLocalStart(text, i, copied) else i
        return if (domEnd > 0 && localStart < i) localStart to domEnd else null
    }

    private fun matchQuotedEmail(text: String, pos: Int, copied: Int, cache: EmailQuoteCache): Int {
        if (text[pos] != '"' || isPrecededByOddBackslashes(text, pos, copied)) return -1
        val closeQuote = resolveNextEmailClosingQuote(text, pos, cache)
        if (closeQuote <= pos || closeQuote + 1 >= text.length) return -1
        val atPos = closeQuote + 1
        val atLen = emailAtLength(text, atPos)
        return if (atLen > 0) findEmailDomainEnd(text, atPos + atLen) else -1
    }

    private fun resolveNextEmailClosingQuote(text: String, pos: Int, cache: EmailQuoteCache): Int {
        if (pos < cache.validUntil) return cache.nextQuote
        val (found, stopPos) = findNextEmailClosingQuote(text, pos + 1)
        cache.nextQuote = found
        cache.validUntil = if (found >= 0) found else stopPos
        return found
    }

    private fun findNextEmailClosingQuote(text: String, start: Int): Pair<Int, Int> {
        var j = start
        var escaped = false
        while (j < text.length) {
            val ch = text[j]
            if (ch == '\r' || ch == '\n') {
                val fwsNext = advanceFoldingWhitespace(text, j)
                if (fwsNext < 0) return -1 to j
                escaped = false
                j = fwsNext
            } else if (ch == '"' && !escaped) {
                return j to j
            } else {
                escaped = ch == '\\' && !escaped
                j++
            }
        }
        return -1 to text.length
    }

    private fun advanceFoldingWhitespace(text: String, newlinePos: Int): Int {
        val after = if (text[newlinePos] == '\r' && newlinePos + 1 < text.length && text[newlinePos + 1] == '\n') {
            newlinePos + 2
        } else {
            newlinePos + 1
        }
        return if (after < text.length && (text[after] == ' ' || text[after] == '\t')) after + 1 else -1
    }

    private fun isEncodedAt(text: String, pos: Int): Boolean =
        text.regionMatches(pos, "%40", 0, ENCODED_AT_LEN)

    private fun scanEmailLocalStart(text: String, atPos: Int, minPos: Int): Int {
        if (atPos > minPos && text[atPos - 1] == '"' && !isPrecededByOddBackslashes(text, atPos - 1, minPos)) {
            return findQuotedEmailLocalStart(text, atPos, minPos)
        }
        var localStart = atPos
        while (true) {
            while (localStart > minPos && isEmailLocalChar(text, localStart - 1) && !isSchemeSlash(text, localStart - 1)) {
                localStart--
            }
            val adjusted = adjustEmailLocalEscapeStart(text, localStart, atPos, minPos)
            if (adjusted < localStart) {
                localStart = adjusted
            } else {
                localStart = adjusted
                break
            }
        }
        var trimmedStart = localStart
        while (trimmedStart < atPos && isEmailLeadingWrapper(text[trimmedStart])) trimmedStart++
        return if (trimmedStart < atPos) trimmedStart else localStart
    }

    private fun adjustEmailLocalEscapeStart(text: String, localStart: Int, atPos: Int, minPos: Int): Int {
        if (localStart >= atPos || localStart <= minPos || !isPrecededByOddBackslashes(text, localStart, minPos)) {
            return localStart
        }
        val esc = text[localStart]
        if (esc in "nrtbf/") return localStart + 1
        if (esc == 'u' && localStart + 5 <= atPos) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, localStart + 1)
            if (code >= 0) {
                val decoded = code.toChar().toString()
                return if (isEmailLocalChar(decoded, 0)) localStart - 1 else localStart + 5
            }
        }
        return localStart
    }

    private fun findQuotedEmailLocalStart(text: String, atPos: Int, minPos: Int): Int {
        var q = atPos - 2
        while (q >= minPos) {
            val ch = text[q]
            if (ch == '\r' || ch == '\n') return atPos
            if (ch == '"' && !isPrecededByOddBackslashes(text, q, minPos)) return q
            q--
        }
        return atPos
    }

    fun isPrecededByOddBackslashes(text: String, pos: Int, minPos: Int): Boolean {
        var count = 0
        var p = pos - 1
        while (p >= minPos && text[p] == '\\') {
            count++
            p--
        }
        return (count and 1) == 1
    }

    private fun isEmailLeadingWrapper(ch: Char): Boolean {
        if (ch in "'{}") return true
        if (ch.code < 0x80 || ch.isSurrogate()) return false
        return Character.getType(ch).toByte() in LEADING_UNICODE_WRAPPER_CATEGORIES
    }

    private fun isSchemeSlash(text: String, pos: Int): Boolean =
        pos >= 2 && text[pos] == '/' && text[pos - 1] == '/' && text[pos - 2] == ':'

    private fun isEmailDomainDot(ch: Char): Boolean =
        ch == '.' || ch == '\u3002' || ch == '\uFF0E' || ch == '\uFF61'

    private fun isUnicodeCombiningMarkCp(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
    }

    private fun classifyDomainCharAt(text: String, pos: Int): Pair<DomainCharKind, Int> {
        val ch = text[pos]
        if (ch == '\\' && pos + 5 < text.length && text[pos + 1] == 'u') {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            if (code >= 0) return classifyEscapedDomainChar(text, pos, code.toChar())
        }
        if (ch.code < 0x80) return classifyAsciiDomainChar(ch) to 1
        if (isEmailDomainDot(ch)) return DomainCharKind.DOT to 1
        val cp = Character.codePointAt(text, pos)
        val step = Character.charCount(cp)
        return classifyUnicodeCodePoint(cp) to step
    }

    private fun classifyEscapedDomainChar(text: String, pos: Int, ch: Char): Pair<DomainCharKind, Int> {
        val hasNextUnicodeEsc = pos + 11 < text.length && text[pos + 6] == '\\' && text[pos + 7] == 'u'
        if (ch.isHighSurrogate() && hasNextUnicodeEsc) {
            val lowCode = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 8)
            if (lowCode >= 0 && lowCode.toChar().isLowSurrogate()) {
                val cp = Character.toCodePoint(ch, lowCode.toChar())
                return classifyUnicodeCodePoint(cp) to (UNICODE_AT_LEN * 2)
            }
            return DomainCharKind.OTHER to UNICODE_AT_LEN
        }
        if (ch.isSurrogate()) return DomainCharKind.OTHER to UNICODE_AT_LEN
        val kind = when {
            ch.code < 0x80 -> classifyAsciiDomainChar(ch)
            isEmailDomainDot(ch) -> DomainCharKind.DOT
            else -> classifyUnicodeCodePoint(ch.code)
        }
        return kind to UNICODE_AT_LEN
    }

    private fun classifyUnicodeCodePoint(cp: Int): DomainCharKind = when {
        Character.isLetter(cp) || isUnicodeCombiningMarkCp(cp) -> DomainCharKind.LETTER
        Character.isDigit(cp) -> DomainCharKind.DIGIT
        else -> DomainCharKind.OTHER
    }

    private fun classifyAsciiDomainChar(ch: Char): DomainCharKind = when {
        CoreTelemetryScanners.isAsciiLetter(ch) -> DomainCharKind.LETTER
        ch in '0'..'9' -> DomainCharKind.DIGIT
        ch == '.' -> DomainCharKind.DOT
        ch == '-' -> DomainCharKind.HYPHEN
        else -> DomainCharKind.OTHER
    }

    private fun isNonAsciiEmailLocalChar(ch: Char): Boolean {
        if (ch.code < 0x80) return false
        if (ch.isSurrogate()) return true
        return !CoreTelemetryScanners.isSpace(ch) && !Character.isISOControl(ch)
    }

    private fun isEmailLocalChar(text: String, pos: Int): Boolean {
        val ch = text[pos]
        val isWordOrPunct = CoreTelemetryScanners.isAsciiAlphaNum(ch) || ch in EMAIL_LOCAL_PUNCT || isNonAsciiEmailLocalChar(ch)
        return isWordOrPunct || (ch == '%' && !isEncodedAt(text, pos))
    }

    private fun isEmailBoundaryBlockedAt(text: String, pos: Int): Boolean {
        val (kind, _) = classifyDomainCharAt(text, pos)
        val isDomainChar = kind == DomainCharKind.LETTER || kind == DomainCharKind.DIGIT || kind == DomainCharKind.HYPHEN
        return isDomainChar || text[pos] == '_'
    }

    private fun isEmailDomainStartAt(text: String, pos: Int): Boolean {
        val (kind, _) = classifyDomainCharAt(text, pos)
        return kind == DomainCharKind.LETTER || kind == DomainCharKind.DIGIT
    }

    private fun isValidDomainLabelEnd(
        text: String,
        labelStart: Int,
        end: Int,
        labelHasLetter: Boolean,
        endedHyphen: Boolean
    ): Boolean {
        if (end <= labelStart || !labelHasLetter || endedHyphen) return false
        if (end < text.length && isEmailBoundaryBlockedAt(text, end)) return false
        if (end < text.length) {
            val (dotKind, dotStep) = classifyDomainCharAt(text, end)
            if (dotKind == DomainCharKind.DOT && end + dotStep < text.length && isEmailDomainStartAt(text, end + dotStep)) {
                return false
            }
        }
        return true
    }

    private fun findIpLiteralDomainEnd(text: String, domStart: Int): Int {
        var close = domStart + 1
        val maxClose = minOf(text.length, domStart + 1 + MAX_IP_LITERAL_LEN)
        while (close < maxClose && text[close] != ']' && !isForbiddenIpv6Char(text[close])) close++
        if (close >= maxClose || text[close] != ']' || close <= domStart + 1) return -1
        val inner = text.substring(domStart + 1, close)
        val valid = if (inner.startsWith("IPv6:", ignoreCase = true)) {
            runCatching { URI("http://[${inner.substring(5)}]") }.isSuccess
        } else {
            isStrictIpv4(inner)
        }
        return if (valid) close + 1 else -1
    }

    private fun isForbiddenIpv6Char(ch: Char): Boolean = CoreTelemetryScanners.isSpace(ch) || ch in IPV6_FORBIDDEN

    private fun isStrictIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.length in 1..3 && part.all { it in '0'..'9' } && part.toInt() <= 255
        }
    }

    private fun findEmailDomainEnd(text: String, domStart: Int, requireDot: Boolean = false): Int =
        if (domStart < text.length && text[domStart] == '[') {
            findIpLiteralDomainEnd(text, domStart)
        } else {
            findNamedEmailDomainEnd(text, domStart, requireDot)
        }

    private fun findNamedEmailDomainEnd(text: String, domStart: Int, requireDot: Boolean): Int {
        var k = domStart
        var labelStart = domStart
        var labelHasLetter = false
        var hasDot = false
        var lastKind = DomainCharKind.OTHER
        var bestEnd = -1
        var done = false
        while (k < text.length && !done) {
            val (kind, step) = classifyDomainCharAt(text, k)
            when (kind) {
                DomainCharKind.LETTER -> labelHasLetter = true
                DomainCharKind.DOT -> if (k == labelStart || lastKind == DomainCharKind.HYPHEN) {
                    done = true
                } else {
                    labelStart = k + step
                    labelHasLetter = false
                    hasDot = true
                }
                DomainCharKind.DIGIT -> Unit
                DomainCharKind.HYPHEN -> done = k == labelStart
                DomainCharKind.OTHER -> done = true
            }
            if (!done) {
                lastKind = kind
                k += step
                val allowEnd = !requireDot || hasDot
                if (allowEnd && isValidDomainLabelEnd(text, labelStart, k, labelHasLetter, lastKind == DomainCharKind.HYPHEN)) {
                    bestEnd = k
                }
            }
        }
        return bestEnd
    }
}
