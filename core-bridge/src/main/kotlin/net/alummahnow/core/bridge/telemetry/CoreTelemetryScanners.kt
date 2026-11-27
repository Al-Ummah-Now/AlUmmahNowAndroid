package net.alummahnow.core.bridge.telemetry

import java.net.URI

/**
 * Linear O(n) single-pass scanners for URL vaulting, quoted filename redaction, file:// URI
 * redaction, and email redaction — avoiding regex backtracking and JVM regex stack overflow.
 */
internal object CoreTelemetryScanners {
    private const val MAX_EXT_LEN = 16
    private const val MAX_EXT_CHECK = 17
    private const val SCHEME_SEP_LEN = 3
    private const val ENCODED_AT_LEN = 3
    private const val MIN_TLD_LEN = 2
    private const val EMAIL_LOCAL_PUNCT = "._+-"
    private const val URL_HOST_PUNCT = "._~!$&'*+;=-"
    private const val URL_PATH_STOP = "\"<>\\{},?#\r\n"
    private const val URL_END_BOUNDARY = "\"'<>\\{},;()[]"
    private const val IPV6_FORBIDDEN = "[\"'<>\\{}\r\n"
    private const val QUOTED_SEG_STOP = "\r\n/\\\u001E\u001F"
    private const val QUERY_STOP = "\"<>"
    private const val OPENING_WRAPPERS = "([{\"'"
    private const val SCHEME_PUNCT = "+.-"
    private const val FILE_SCHEME_PREFIX = "file://"

    internal data class UrlSpan(val start: Int, val baseEnd: Int, val totalEnd: Int, val hasQf: Boolean)

    fun findNonFileSchemeStart(text: String, sep: Int, minStart: Int): Int? {
        var start = sep
        while (start > minStart && isUrlSchemeChar(text[start - 1])) start--
        val validPrefix = sep - start >= 2 && isAsciiLetter(text[start])
        val prevBlocked = start > 0 && isBlockedBeforeScheme(text[start - 1])
        val isFile = sep - start == 4 && text.regionMatches(start, "file", 0, 4, ignoreCase = true)
        return if (validPrefix && !prevBlocked && !isFile) start else null
    }

    fun scanUrlAfterScheme(text: String, start: Int, sep: Int): UrlSpan? {
        val afterHost = scanUrlHost(text, sep + SCHEME_SEP_LEN) ?: return null
        val afterPort = scanOptionalPort(text, afterHost) ?: return null
        val (baseEnd, truncated) = if (afterPort < text.length && text[afterPort] == '/') {
            scanUrlPath(text, afterPort)
        } else {
            afterPort to false
        }
        val startsQf = !truncated && baseEnd < text.length && (text[baseEnd] == '?' || text[baseEnd] == '#')
        val totalEnd = if (startsQf) scanQueryOrFragment(text, baseEnd) else baseEnd
        val boundaryOk = totalEnd == text.length || isUrlEndBoundary(text, totalEnd)
        return if (boundaryOk) UrlSpan(start, baseEnd, totalEnd, startsQf) else null
    }

    private fun isBlockedBeforeScheme(ch: Char): Boolean =
        isUrlSchemeChar(ch) || ch == '/' || ch == '\\'

    private fun scanUrlHost(text: String, hostStart: Int): Int? {
        if (hostStart >= text.length) return null
        return if (text[hostStart] == '[') scanIpv6Host(text, hostStart) else scanRegHost(text, hostStart)
    }

    private fun scanIpv6Host(text: String, openPos: Int): Int? {
        var close = openPos + 1
        while (close < text.length && text[close] != ']' && !isForbiddenIpv6Char(text[close])) close++
        val validBracket = close < text.length && text[close] == ']' && close > openPos + 1
        if (!validBracket) return null
        val ipv6 = text.substring(openPos + 1, close)
        val validUri = runCatching { URI("http://[$ipv6]") }.isSuccess
        return if (validUri) close + 1 else null
    }

    private fun isForbiddenIpv6Char(ch: Char): Boolean = ch.isWhitespace() || ch in IPV6_FORBIDDEN

    private fun scanRegHost(text: String, hostStart: Int): Int? {
        var p = hostStart
        while (p < text.length) {
            val step = hostCharStep(text, p)
            if (step == 0) break
            p += step
        }
        return if (p > hostStart) p else null
    }

    private fun hostCharStep(text: String, pos: Int): Int {
        val ch = text[pos]
        if (isAsciiAlphaNum(ch) || ch in URL_HOST_PUNCT) return 1
        return if (isPercentEncoded(text, pos)) ENCODED_AT_LEN else 0
    }

    private fun isPercentEncoded(text: String, pos: Int): Boolean {
        if (text[pos] != '%' || pos + 2 >= text.length) return false
        return isHexDigit(text[pos + 1]) && isHexDigit(text[pos + 2])
    }

    private fun scanOptionalPort(text: String, pos: Int): Int? {
        if (pos >= text.length || text[pos] != ':') return pos
        var p = pos + 1
        while (p < text.length && text[p] in '0'..'9') p++
        return if (p > pos + 1) p else null
    }

    private fun scanUrlPath(text: String, start: Int): Pair<Int, Boolean> {
        val openPositions = ArrayList<Int>()
        val openChars = ArrayList<Char>()
        var p = start
        while (p < text.length) {
            val next = stepUrlPathChar(text, p, openPositions, openChars)
            if (next < 0) break
            p = next
        }
        return if (openPositions.isNotEmpty()) openPositions[0] to true else p to false
    }

    private fun stepUrlPathChar(
        text: String,
        p: Int,
        openPositions: ArrayList<Int>,
        openChars: ArrayList<Char>
    ): Int {
        val ch = text[p]
        return when {
            isPathStopChar(text, p, ch) -> -1
            ch == '(' || ch == '[' -> pushGroupOpener(text, p, ch, openPositions, openChars)
            ch == ')' || ch == ']' -> popGroupCloser(p, ch, openPositions, openChars)
            openChars.isNotEmpty() && ch == ':' -> -1
            else -> p + 1
        }
    }

    private fun pushGroupOpener(
        text: String,
        p: Int,
        ch: Char,
        openPositions: ArrayList<Int>,
        openChars: ArrayList<Char>
    ): Int {
        if (startsEmbeddedPathInsideGroup(text, p + 1)) return -1
        openPositions.add(p)
        openChars.add(ch)
        return p + 1
    }

    private fun popGroupCloser(
        p: Int,
        ch: Char,
        openPositions: ArrayList<Int>,
        openChars: ArrayList<Char>
    ): Int {
        val expected = if (ch == ')') '(' else '['
        if (openChars.isEmpty() || openChars[openChars.lastIndex] != expected) return -1
        openPositions.removeAt(openPositions.lastIndex)
        openChars.removeAt(openChars.lastIndex)
        return p + 1
    }

    private fun isPathStopChar(text: String, pos: Int, ch: Char): Boolean {
        val stopChar = ch.isWhitespace() || ch in URL_PATH_STOP
        return stopChar || isFileSchemeAt(text, pos) || isWindowsDriveAt(text, pos)
    }

    private fun startsEmbeddedPathInsideGroup(text: String, nextPos: Int): Boolean {
        if (nextPos >= text.length) return true
        val ch = text[nextPos]
        val directPath = ch == '/' || ch == '\\' || ch == ':'
        val homePath = ch == '~' && nextPos + 1 < text.length && text[nextPos + 1] == '/'
        val schemeOrDrive = isFileSchemeAt(text, nextPos) || isWindowsDriveAt(text, nextPos)
        return directPath || homePath || schemeOrDrive
    }

    private fun scanQueryOrFragment(text: String, start: Int): Int {
        var p = start + 1
        while (p < text.length && !isQueryStopChar(text[p])) p++
        return p
    }

    private fun isQueryStopChar(ch: Char): Boolean = ch.isWhitespace() || ch in QUERY_STOP

    private fun isUrlEndBoundary(text: String, pos: Int): Boolean {
        val ch = text[pos]
        val boundaryChar = ch.isWhitespace() || ch in URL_END_BOUNDARY
        return boundaryChar || isFileSchemeAt(text, pos) || isWindowsDriveAt(text, pos)
    }

    fun redactQuotedFilenames(text: String): String {
        if (text.indexOf('.') < 0) return text
        val afterDouble = if (text.indexOf('"') >= 0) redactDoubleQuotedFilenames(text) else text
        return if (afterDouble.indexOf('\'') >= 0) redactSingleQuotedFilenames(afterDouble) else afterDouble
    }

    private fun redactDoubleQuotedFilenames(text: String): String {
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        while (i < text.length) {
            if (text[i] != '"') {
                i++
            } else {
                val close = scanDoubleQuoteEnd(text, i + 1)
                val matched = close < text.length && text[close] == '"' && endsWithFilenameExt(text, i + 1, close)
                if (matched) {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copied, i).append("[redacted-path]")
                    copied = close + 1
                    i = close + 1
                } else {
                    i = if (close < text.length && text[close] == '"') close else close + 1
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun scanDoubleQuoteEnd(text: String, start: Int): Int {
        var j = start
        while (j < text.length && text[j] != '"' && !isQuotedSegStop(text[j])) j++
        return j
    }

    private fun redactSingleQuotedFilenames(text: String): String {
        var sb: StringBuilder? = null
        var copied = 0
        var segStart = 0
        while (segStart < text.length) {
            var segEnd = segStart
            while (segEnd < text.length && !isQuotedSegStop(text[segEnd])) segEnd++
            copied = redactSingleQuotesInSegment(text, segStart, segEnd, copied) { open, close ->
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, open).append("[redacted-path]")
                copied = close + 1
            }
            segStart = segEnd + 1
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun redactSingleQuotesInSegment(
        text: String,
        segStart: Int,
        segEnd: Int,
        initialCopied: Int,
        onMatch: (Int, Int) -> Unit
    ): Int {
        var copied = initialCopied
        var open = -1
        var candidateClose = -1
        for (pos in segStart until segEnd) {
            if (text[pos] != '\'') continue
            val isOpener = pos == segStart || !text[pos - 1].isLetterOrDigit()
            if (isOpener) {
                if (open < 0 || candidateClose >= 0) {
                    open = pos
                    candidateClose = -1
                }
            } else if (open >= 0) {
                if (endsWithFilenameExt(text, open + 1, pos)) {
                    onMatch(open, pos)
                    copied = pos + 1
                    open = -1
                    candidateClose = -1
                } else if (pos + 1 == segEnd || !text[pos + 1].isLetterOrDigit()) {
                    candidateClose = pos
                }
            }
        }
        return copied
    }

    private fun isQuotedSegStop(ch: Char): Boolean = ch in QUOTED_SEG_STOP

    private fun endsWithFilenameExt(text: String, start: Int, end: Int): Boolean {
        var extStart = end
        while (extStart > start && end - extStart < MAX_EXT_CHECK && isAsciiAlphaNum(text[extStart - 1])) {
            extStart--
        }
        val extLen = end - extStart
        return extLen in 1..MAX_EXT_LEN && extStart > start && text[extStart - 1] == '.'
    }

    fun fileUriSpanEnd(text: String, schemeEnd: Int, quote: Char, vaultPrefix: String): Pair<Int, Int> {
        var rawEnd = schemeEnd
        var stoppedAtVault = false
        var escapedBackslash = false
        var candidateClose = -1
        var done = false
        while (rawEnd < text.length && !done) {
            val ch = text[rawEnd]
            val atVault = ch == '\u001E' && text.regionMatches(rawEnd, vaultPrefix, 0, vaultPrefix.length)
            if (ch == '\r' || ch == '\n' || atVault) {
                stoppedAtVault = atVault
                done = true
            } else {
                if (quote != '\u0000' && ch == quote && !escapedBackslash) {
                    val nextClose = evaluateFileUriQuote(text, rawEnd, schemeEnd, quote, candidateClose)
                    if (nextClose >= 0) candidateClose = nextClose
                    done = nextClose == -2 || quote == '"'
                }
                if (!done) {
                    escapedBackslash = if (ch == '\\') !escapedBackslash else false
                    rawEnd++
                }
            }
        }
        if (candidateClose >= 0) return candidateClose to candidateClose
        val trimmedEnd = if (stoppedAtVault) trimFileUriTail(text, schemeEnd, rawEnd) else rawEnd
        return rawEnd to trimmedEnd
    }

    private fun evaluateFileUriQuote(
        text: String,
        pos: Int,
        schemeEnd: Int,
        quote: Char,
        candidateClose: Int
    ): Int {
        if (quote == '"') return pos
        val opensNewQuote = candidateClose >= 0 && pos > schemeEnd && !text[pos - 1].isLetterOrDigit()
        if (opensNewQuote) return -2
        val canClose = pos + 1 == text.length || !text[pos + 1].isLetterOrDigit()
        return if (canClose) pos else -1
    }

    private fun trimFileUriTail(text: String, schemeEnd: Int, rawEnd: Int): Int {
        var trimmedEnd = rawEnd
        while (trimmedEnd > schemeEnd) {
            val prev = text[trimmedEnd - 1]
            if (isOpeningWrapper(prev) || isSpace(prev)) trimmedEnd-- else break
        }
        return trimmedEnd
    }

    fun redactEmails(text: String): String {
        if (text.indexOf('@') < 0 && text.indexOf("%40") < 0) return text
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        while (i < text.length) {
            val atLen = emailAtLength(text, i)
            if (atLen == 0) {
                i++
            } else {
                val localStart = scanEmailLocalStart(text, i, copied)
                val domEnd = if (localStart < i) findEmailDomainEnd(text, i + atLen) else -1
                if (domEnd > 0) {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copied, localStart).append("[redacted-email]")
                    copied = domEnd
                    i = domEnd
                } else {
                    i += atLen
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun emailAtLength(text: String, pos: Int): Int = when {
        text[pos] == '@' -> 1
        isEncodedAt(text, pos) -> ENCODED_AT_LEN
        else -> 0
    }

    private fun isEncodedAt(text: String, pos: Int): Boolean =
        text.regionMatches(pos, "%40", 0, ENCODED_AT_LEN)

    private fun scanEmailLocalStart(text: String, atPos: Int, minPos: Int): Int {
        var localStart = atPos
        while (localStart > minPos && isEmailLocalChar(text, localStart - 1)) localStart--
        return localStart
    }

    private fun isEmailLocalChar(text: String, pos: Int): Boolean {
        val ch = text[pos]
        if (isAsciiAlphaNum(ch) || ch in EMAIL_LOCAL_PUNCT) return true
        return ch == '%' && !isEncodedAt(text, pos)
    }

    private fun findEmailDomainEnd(text: String, domStart: Int): Int {
        var k = domStart
        var lastDot = -1
        var lettersSinceDot = 0
        var bestEnd = -1
        while (k < text.length) {
            val ch = text[k]
            when {
                isAsciiLetter(ch) -> lettersSinceDot = if (lastDot >= 0) lettersSinceDot + 1 else 0
                ch == '.' -> {
                    lastDot = if (k > domStart) k else -1
                    lettersSinceDot = 0
                }
                ch in '0'..'9' || ch == '-' -> lettersSinceDot = -1
                else -> break
            }
            k++
            val atBoundary = k == text.length || !isAsciiWordChar(text[k])
            if (lastDot > domStart && lettersSinceDot >= MIN_TLD_LEN && atBoundary) bestEnd = k
        }
        return bestEnd
    }

    fun isFileSchemeAt(text: String, pos: Int): Boolean {
        val boundaryOk = pos == 0 || !isUrlSchemeChar(text[pos - 1])
        return boundaryOk && text.regionMatches(pos, FILE_SCHEME_PREFIX, 0, FILE_SCHEME_PREFIX.length, ignoreCase = true)
    }

    fun isWindowsDriveAt(text: String, pos: Int): Boolean {
        if (pos + 2 >= text.length) return false
        val hasDriveSep = text[pos + 1] == ':' && isPathSep(text[pos + 2])
        return isAsciiLetter(text[pos]) && hasDriveSep
    }

    fun isUrlSchemeChar(ch: Char): Boolean = isAsciiAlphaNum(ch) || ch in SCHEME_PUNCT

    fun isOpeningWrapper(ch: Char): Boolean = ch in OPENING_WRAPPERS

    fun isPathSep(ch: Char): Boolean = ch == '/' || ch == '\\'

    fun isSpace(ch: Char): Boolean = ch.isWhitespace() || Character.isSpaceChar(ch)

    fun isAsciiLetter(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z'

    fun isAsciiAlphaNum(ch: Char): Boolean = isAsciiLetter(ch) || ch in '0'..'9'

    fun isAsciiWordChar(ch: Char): Boolean = isAsciiAlphaNum(ch) || ch == '_'

    private fun isHexDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in 'A'..'F' || ch in 'a'..'f'
}
