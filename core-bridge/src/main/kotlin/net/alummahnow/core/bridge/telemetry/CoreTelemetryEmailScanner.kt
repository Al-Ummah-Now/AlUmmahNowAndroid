package net.alummahnow.core.bridge.telemetry

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Linear O(n) RFC 5322 / RFC 6531 email scanner and auth-credential redactor.
 */
internal object CoreTelemetryEmailScanner {
    private const val ENCODED_AT_LEN = 3
    private const val MIN_BASIC_TOKEN_FALLBACK_LEN = 8
    private const val BASE64_CHUNK_LEN = 4
    private const val BASE64_PAD_TWO = 2
    private const val BASE64_PAD_THREE = 3
    private const val ASCII_SPACE = 0x20
    private const val ASCII_DEL = 0x7F
    private const val EMAIL_LOCAL_PUNCT = "._+-/!#$&'*=?^`{|}~"
    private const val IPV6_FORBIDDEN = "[\"'<>\\{}\r\n"
    private const val KNOWN_AUTH_SCHEMES =
        "Bearer|Basic|Digest|Token|Api-?Key|OAuth|Negotiate|Mutual|HOBA|Vapid|SCRAM-SHA-256|AWS4-HMAC-SHA256"
    private val authHeaderDoubleQuotedRegex = Regex(
        """(["']?\b(?:Proxy-)?Authorization["']?\s*[:=]\s*"\s*(?:(?:$KNOWN_AUTH_SCHEMES)\s+)?)(?:\\.|[^\\"\r\n])+""",
        RegexOption.IGNORE_CASE
    )
    private val authHeaderSingleQuotedRegex = Regex(
        """(["']?\b(?:Proxy-)?Authorization["']?\s*[:=]\s*'\s*(?:(?:$KNOWN_AUTH_SCHEMES)\s+)?)(?:\\.|[^\\'\r\n])+""",
        RegexOption.IGNORE_CASE
    )
    private val authHeaderPlainRegex = Regex(
        """(\b(?:Proxy-)?Authorization\s*[:=]\s*(?!['"])(?:(?:$KNOWN_AUTH_SCHEMES)\s+)?)[^\r\n]+""",
        RegexOption.IGNORE_CASE
    )
    private val bearerTokenRegex = Regex("""\bBearer\s+\S+""", RegexOption.IGNORE_CASE)
    private val basicTokenRegex = Regex(
        """\b(Basic)\s+([A-Za-z0-9+/_-]{4,}={0,2})(?=$|[\s"',;)\]}])""",
        RegexOption.IGNORE_CASE
    )
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

    fun redactAuthCredentials(text: String): String {
        var result = authHeaderDoubleQuotedRegex.replace(text, "$1[redacted]")
        result = authHeaderSingleQuotedRegex.replace(result, "$1[redacted]")
        result = authHeaderPlainRegex.replace(result, "$1[redacted]")
        result = bearerTokenRegex.replace(result, "Bearer [redacted]")
        return basicTokenRegex.replace(result) { match ->
            if (isBasicCredentialToken(match.groupValues[2])) {
                "${match.groupValues[1]} [redacted]"
            } else {
                match.value
            }
        }
    }

    private fun hasBase64Symbol(token: String): Boolean = token.any { it == '+' || it == '/' || it == '=' }

    private fun isBasicCredentialToken(token: String): Boolean {
        if (token.length >= MIN_BASIC_TOKEN_FALLBACK_LEN && hasBase64Symbol(token)) return true
        val bytes = decodeBase64Strict(token)
        val decoded = if (bytes != null && bytes.any { it == ':'.code.toByte() }) decodeUtf8Strict(bytes) else null
        return decoded != null && decoded.none { (it.code < ASCII_SPACE && it != '\t') || it.code == ASCII_DEL }
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun decodeBase64Strict(token: String): ByteArray? {
        val unpadded = token.trimEnd('=')
        val rem = unpadded.length % BASE64_CHUNK_LEN
        if (rem == 1) return null
        val outLen = (unpadded.length / BASE64_CHUNK_LEN) * 3 + when (rem) {
            BASE64_PAD_TWO -> 1
            BASE64_PAD_THREE -> 2
            else -> 0
        }
        val out = ByteArray(outLen)
        var inIdx = 0
        var outIdx = 0
        var valid = true
        while (inIdx < unpadded.length && valid) {
            val written = decodeBase64Chunk(unpadded, inIdx, out, outIdx)
            if (written < 0) {
                valid = false
            } else {
                outIdx += written
                inIdx += BASE64_CHUNK_LEN
            }
        }
        return if (valid) out else null
    }

    private fun decodeBase64Chunk(src: String, inIdx: Int, out: ByteArray, outIdx: Int): Int {
        val c0 = base64Value(src[inIdx])
        val c1 = if (inIdx + 1 < src.length) base64Value(src[inIdx + 1]) else -1
        val c2 = if (inIdx + 2 < src.length) base64Value(src[inIdx + 2]) else -2
        val c3 = if (inIdx + 3 < src.length) base64Value(src[inIdx + 3]) else -2
        val invalidPair = c0 < 0 || c1 < 0
        val invalidTail = c2 == -1 || c3 == -1
        if (invalidPair || invalidTail) return -1
        out[outIdx] = ((c0 shl 2) or (c1 shr 4)).toByte()
        if (c2 < 0) return 1
        out[outIdx + 1] = (((c1 and 0x0F) shl 4) or (c2 shr 2)).toByte()
        if (c3 < 0) return 2
        out[outIdx + 2] = (((c2 and 0x03) shl 6) or c3).toByte()
        return BASE64_PAD_THREE
    }

    private fun base64Value(ch: Char): Int = when {
        ch in 'A'..'Z' -> ch - 'A'
        ch in 'a'..'z' -> ch - 'a' + 26
        ch in '0'..'9' -> ch - '0' + 52
        ch == '+' || ch == '-' -> 62
        ch == '/' || ch == '_' -> 63
        else -> -1
    }

    fun hasEmailAt(text: String): Boolean = text.indexOf('@') >= 0 || text.indexOf("%40") >= 0

    fun containsWhitespace(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (CoreTelemetryScanners.isSpace(text[i])) return true
        }
        return false
    }

    fun emailAtLength(text: String, pos: Int): Int = when {
        text[pos] == '@' -> 1
        isEncodedAt(text, pos) -> ENCODED_AT_LEN
        else -> 0
    }

    fun findEmailTailEnd(text: String, pos: Int): Int {
        var p = pos
        while (p < text.length && isEmailLocalChar(text, p)) p++
        if (p >= text.length) return -1
        val atLen = emailAtLength(text, p)
        return if (atLen > 0) findEmailDomainEnd(text, p + atLen) else -1
    }

    fun redactQuotedEmails(text: String): String {
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

    private fun matchEmailAt(text: String, i: Int, copied: Int, quoteCache: EmailQuoteCache): Pair<Int, Int>? {
        val quotedEnd = matchQuotedEmail(text, i, copied, quoteCache)
        if (quotedEnd > 0) return i to quotedEnd
        val atLen = emailAtLength(text, i)
        if (atLen == 0) return null
        val domEnd = findEmailDomainEnd(text, i + atLen)
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
        while (localStart > minPos && isEmailLocalChar(text, localStart - 1) && !isSchemeSlash(text, localStart - 1)) {
            localStart--
        }
        var trimmedStart = localStart
        while (trimmedStart < atPos && isEmailLeadingWrapper(text[trimmedStart])) trimmedStart++
        return if (trimmedStart < atPos) trimmedStart else localStart
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

    private fun isPrecededByOddBackslashes(text: String, pos: Int, minPos: Int): Boolean {
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
        if (ch.code < 0x80) return classifyAsciiDomainChar(ch) to 1
        if (isEmailDomainDot(ch)) return DomainCharKind.DOT to 1
        val cp = Character.codePointAt(text, pos)
        val step = Character.charCount(cp)
        val kind = when {
            Character.isLetter(cp) || isUnicodeCombiningMarkCp(cp) -> DomainCharKind.LETTER
            Character.isDigit(cp) -> DomainCharKind.DIGIT
            else -> DomainCharKind.OTHER
        }
        return kind to step
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

    private fun isValidDomainLabelEnd(text: String, labelStart: Int, end: Int, labelHasLetter: Boolean): Boolean {
        if (end <= labelStart || !labelHasLetter || text[end - 1] == '-') return false
        if (end < text.length && isEmailBoundaryBlockedAt(text, end)) return false
        val continuesDomain = end + 1 < text.length && isEmailDomainDot(text[end]) && isEmailDomainStartAt(text, end + 1)
        return !continuesDomain
    }

    private fun findIpLiteralDomainEnd(text: String, domStart: Int): Int {
        var close = domStart + 1
        while (close < text.length && text[close] != ']' && !isForbiddenIpv6Char(text[close])) close++
        if (close >= text.length || text[close] != ']' || close <= domStart + 1) return -1
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

    private fun findEmailDomainEnd(text: String, domStart: Int): Int =
        if (domStart < text.length && text[domStart] == '[') {
            findIpLiteralDomainEnd(text, domStart)
        } else {
            findNamedEmailDomainEnd(text, domStart)
        }

    private fun findNamedEmailDomainEnd(text: String, domStart: Int): Int {
        var k = domStart
        var labelStart = domStart
        var labelHasLetter = false
        var bestEnd = -1
        var done = false
        while (k < text.length && !done) {
            val (kind, step) = classifyDomainCharAt(text, k)
            when (kind) {
                DomainCharKind.LETTER -> labelHasLetter = true
                DomainCharKind.DOT -> if (k == labelStart || text[k - 1] == '-') {
                    done = true
                } else {
                    labelStart = k + 1
                    labelHasLetter = false
                }
                DomainCharKind.DIGIT -> Unit
                DomainCharKind.HYPHEN -> done = k == labelStart
                DomainCharKind.OTHER -> done = true
            }
            if (!done) {
                k += step
                if (isValidDomainLabelEnd(text, labelStart, k, labelHasLetter)) bestEnd = k
            }
        }
        return bestEnd
    }
}
