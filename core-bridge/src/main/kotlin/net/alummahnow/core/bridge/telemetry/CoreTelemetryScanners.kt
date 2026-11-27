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
    private const val PERCENT_ENC_LEN = 3
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
        return if (isPercentEncoded(text, pos)) PERCENT_ENC_LEN else 0
    }

    private fun isPercentEncoded(text: String, pos: Int): Boolean {
        if (text[pos] != '%' || pos + 2 >= text.length) return false
        return isHexDigit(text[pos + 1]) && isHexDigit(text[pos + 2])
    }

    private val urlPathSecretRules: List<Pair<Regex, String>> = listOf(
        Regex("""((?:^|/)webhooks?/[^/\s?#]+/)[^/\s?#]+""", RegexOption.IGNORE_CASE) to "$1[redacted]",
        Regex(
            """(https?://hooks\.slack(?:-gov)?\.com\.?(?::\d+)?/(?:services|workflows|triggers)/)[^\s?#]+""",
            RegexOption.IGNORE_CASE
        ) to "$1[redacted]",
        Regex("""(/bot\d+:)[^/\s?#]+""", RegexOption.IGNORE_CASE) to "$1[redacted]"
    )

    fun redactUrlPathSecrets(url: String): String {
        var result = url
        for ((regex, replacement) in urlPathSecretRules) {
            result = regex.replace(result, replacement)
        }
        return result
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
                val followedByEmailAt = close + 1 < text.length && CoreTelemetryEmailScanner.emailAtLength(text, close + 1) > 0
                val matched = close < text.length && text[close] == '"' && !followedByEmailAt && endsWithFilenameExt(text, i + 1, close)
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
        val hasAt = CoreTelemetryEmailScanner.hasEmailAt(text)
        var sb: StringBuilder? = null
        var copied = 0
        var segStart = 0
        while (segStart < text.length) {
            var segEnd = segStart
            while (segEnd < text.length && !isQuotedSegStop(text[segEnd])) segEnd++
            copied = redactSingleQuotesInSegment(text, segStart, segEnd, copied, hasAt) { open, endPos ->
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, open).append("[redacted-path]")
                copied = endPos
            }
            segStart = maxOf(segEnd + 1, copied)
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun redactSingleQuotesInSegment(
        text: String,
        segStart: Int,
        segEnd: Int,
        initialCopied: Int,
        hasAt: Boolean,
        onMatch: (Int, Int) -> Unit
    ): Int {
        var copied = initialCopied
        var open = -1
        var candidateClose = -1
        for (pos in segStart until segEnd) {
            if (pos < copied || text[pos] != '\'') continue
            val isOpener = pos == segStart || !text[pos - 1].isLetterOrDigit()
            if (isOpener) {
                if (open < 0 || candidateClose >= 0) {
                    open = pos
                    candidateClose = -1
                }
            } else if (open >= 0 && (pos + 1 == segEnd || !text[pos + 1].isLetterOrDigit())) {
                if (endsWithFilenameExt(text, open + 1, pos)) {
                    val emailEnd = if (hasAt) CoreTelemetryEmailScanner.findEmailTailEnd(text, pos + 1) else -1
                    val endPos = maxOf(pos + 1, emailEnd)
                    onMatch(open, endPos)
                    copied = endPos
                    open = -1
                    candidateClose = -1
                } else {
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

    /**
     * Double-quoted file:// URIs terminate at the next unescaped '"'. Unquoted and single-quoted
     * file:// URIs fail closed to the next vaulted URL or line break so unescaped apostrophes
     * inside filenames (e.g. "O' Connor") can never leak suffixes.
     */
    fun fileUriSpanEnd(text: String, schemeEnd: Int, isDoubleQuoted: Boolean, vaultPrefix: String): Pair<Int, Int> {
        var rawEnd = schemeEnd
        var stoppedAtVault = false
        var escapedBackslash = false
        var done = false
        while (rawEnd < text.length && !done) {
            val ch = text[rawEnd]
            val atVault = ch == '\u001E' && text.regionMatches(rawEnd, vaultPrefix, 0, vaultPrefix.length)
            if (ch == '\r' || ch == '\n' || atVault) {
                stoppedAtVault = atVault
                done = true
            } else if (isDoubleQuoted && ch == '"' && !escapedBackslash) {
                done = true
            } else {
                escapedBackslash = ch == '\\' && !escapedBackslash
                rawEnd++
            }
        }
        val trimmedEnd = if (stoppedAtVault) trimFileUriTail(text, schemeEnd, rawEnd) else rawEnd
        return rawEnd to trimmedEnd
    }

    private fun trimFileUriTail(text: String, schemeEnd: Int, rawEnd: Int): Int {
        var trimmedEnd = rawEnd
        while (trimmedEnd > schemeEnd) {
            val prev = text[trimmedEnd - 1]
            if (isOpeningWrapper(prev) || isSpace(prev)) trimmedEnd-- else break
        }
        return trimmedEnd
    }

    fun replaceUnquotedParenPaths(text: String, regex: Regex): String {
        if (text.indexOf('(') < 0) return text
        val state = QuoteCursorState()
        return regex.replace(text) { match ->
            state.advanceTo(text, match.range.first)
            if (state.openQuote != '\u0000') match.value else "([redacted-path])"
        }
    }

    private class QuoteCursorState {
        var cursor = 0
        var openQuote = '\u0000'
        var escapedBackslash = false

        fun advanceTo(text: String, target: Int) {
            while (cursor < target) {
                step(text, text[cursor])
                cursor++
            }
        }

        private fun step(text: String, ch: Char) {
            when {
                ch == '\r' || ch == '\n' -> {
                    openQuote = '\u0000'
                    escapedBackslash = false
                    return
                }
                isClosingQuote(text, ch) -> openQuote = '\u0000'
                isOpeningQuote(text, ch) -> openQuote = ch
            }
            escapedBackslash = ch == '\\' && !escapedBackslash
        }

        private fun isClosingQuote(text: String, ch: Char): Boolean {
            if (escapedBackslash || openQuote == '\u0000' || ch != openQuote) return false
            val hasNeighbors = ch == '\'' && cursor > 0 && cursor + 1 < text.length
            val isInnerApos = hasNeighbors && text[cursor - 1].isLetterOrDigit() && text[cursor + 1].isLetter()
            return !isInnerApos
        }

        private fun isOpeningQuote(text: String, ch: Char): Boolean {
            if (escapedBackslash || openQuote != '\u0000') return false
            return ch == '"' || (ch == '\'' && (cursor == 0 || !text[cursor - 1].isLetterOrDigit()))
        }
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
