package net.alummahnow.core.bridge.telemetry

/**
 * JSON delimiter and balanced-brace helpers for [CoreTelemetryAuthScanner].
 */
internal object CoreTelemetryAuthJsonHelpers {
    private val jsonNumberRegex = Regex("""^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$""")

    class JsonScanCache {
        var braceMap: MutableMap<Int, Int>? = null
        var failedBraceBack = -1
        var failedBracketBack = -1
    }

    fun isJsonNumber(token: String): Boolean = jsonNumberRegex.matches(token)

    fun isJsonDelimiter(ch: Char): Boolean = ch == ',' || ch == '}' || ch == ']'

    fun isJsonPrimitiveStop(ch: Char): Boolean =
        isHorizSpace(ch) || isNewline(ch) || isJsonDelimiter(ch)

    fun isPrecededByJsonMemberDelimiter(text: String, pos: Int, cache: JsonScanCache): Boolean {
        var idx = pos - 1
        while (idx >= 0 && isJsonWhitespace(text[idx])) idx--
        if (idx < 0) return false
        return text[idx] == '{' || (text[idx] == ',' && isPrecededByJsonValue(text, idx, cache))
    }

    private fun isPrecededByJsonValue(text: String, commaPos: Int, cache: JsonScanCache): Boolean {
        var prev = commaPos - 1
        while (prev >= 0 && isJsonWhitespace(text[prev])) prev--
        if (prev < 0) return false
        val ch = text[prev]
        return when {
            ch == '"' -> isPrecededByColon(text, scanJsonStringStartBackward(text, prev))
            ch == '}' -> isPrecededByColon(text, scanBraceStartBackward(text, prev, cache))
            ch == ']' -> isPrecededByColon(text, scanBracketStartBackward(text, prev, cache))
            ch in '0'..'9' -> matchesJsonNumberBefore(text, prev)
            else -> matchesJsonKeywordEndingAt(text, prev)
        }
    }

    private fun matchesJsonNumberBefore(text: String, prev: Int): Boolean {
        var s = prev
        while (s >= 0 && !isJsonValuePrefixStop(text[s])) s--
        return isJsonNumber(text.substring(s + 1, prev + 1)) && isPrecededByColon(text, s + 1)
    }

    private fun scanBraceStartBackward(text: String, prev: Int, cache: JsonScanCache): Int {
        val start = scanContainerStartBackward(text, prev, '{', '}', cache.failedBraceBack)
        if (start < 0) cache.failedBraceBack = maxOf(cache.failedBraceBack, prev)
        return start
    }

    private fun scanBracketStartBackward(text: String, prev: Int, cache: JsonScanCache): Int {
        val start = scanContainerStartBackward(text, prev, '[', ']', cache.failedBracketBack)
        if (start < 0) cache.failedBracketBack = maxOf(cache.failedBracketBack, prev)
        return start
    }

    private fun isPrecededByColon(text: String, valueStart: Int): Boolean {
        if (valueStart <= 0) return false
        var p = valueStart - 1
        while (p >= 0 && isJsonWhitespace(text[p])) p--
        return p >= 0 && text[p] == ':'
    }

    private fun isEscapedQuote(text: String, quotePos: Int): Boolean {
        var backslashes = 0
        var i = quotePos - 1
        while (i >= 0 && text[i] == '\\') {
            backslashes++
            i--
        }
        return (backslashes and 1) == 1
    }

    private fun scanJsonStringStartBackward(text: String, closeQuotePos: Int): Int {
        for (i in closeQuotePos - 1 downTo 0) {
            val ch = text[i]
            if (isNewline(ch)) return -1
            if (ch == '"' && !isEscapedQuote(text, i)) return i
        }
        return -1
    }

    private fun scanContainerStartBackward(
        text: String,
        closePos: Int,
        openChar: Char,
        closeChar: Char,
        floor: Int
    ): Int {
        var depth = 0
        var idx = closePos
        while (idx > floor) {
            val ch = text[idx]
            if (ch == '"' && !isEscapedQuote(text, idx)) {
                val strStart = scanJsonStringStartBackward(text, idx)
                if (strStart < 0) return -1
                idx = strStart - 1
            } else {
                if (ch == closeChar) {
                    depth++
                } else if (ch == openChar) {
                    depth--
                    if (depth == 0) return idx
                }
                idx--
            }
        }
        return -1
    }

    private fun isJsonValuePrefixStop(ch: Char): Boolean =
        isJsonWhitespace(ch) || ch == ':' || ch == ',' || ch == '['

    private fun matchesJsonKeywordEndingAt(text: String, endIdx: Int): Boolean =
        matchesSingleKeyword(text, endIdx, "true") ||
            matchesSingleKeyword(text, endIdx, "false") ||
            matchesSingleKeyword(text, endIdx, "null")

    private fun matchesSingleKeyword(text: String, endIdx: Int, keyword: String): Boolean {
        val start = endIdx - keyword.length + 1
        if (start <= 0 || !text.regionMatches(start, keyword, 0, keyword.length)) return false
        return isPrecededByColon(text, start)
    }

    fun isJsonMemberContinuationDelimiter(text: String, pos: Int): Boolean = when (text[pos]) {
        '}' -> pos + 1 >= text.length || isClosingBraceFollower(text[pos + 1])
        ',' -> isFollowedByJsonMemberKey(text, pos + 1)
        else -> false
    }

    private fun isClosingBraceFollower(ch: Char): Boolean =
        isJsonPrimitiveStop(ch) || ch == ')' || ch == ';'

    private fun isFollowedByJsonMemberKey(text: String, afterComma: Int): Boolean {
        val next = skipJsonWhitespace(text, afterComma)
        if (next >= text.length || text[next] != '"') return false
        val strEnd = scanJsonStringEnd(text, next + 1)
        val afterKey = if (strEnd > 0) skipJsonWhitespace(text, strEnd) else text.length
        return afterKey < text.length && text[afterKey] == ':'
    }

    fun scanBalancedJsonObjectEnd(text: String, bracePos: Int, cache: JsonScanCache): Int {
        if (bracePos >= text.length || text[bracePos] != '{') return -1
        val map = cache.braceMap ?: HashMap<Int, Int>().also { cache.braceMap = it }
        return map[bracePos] ?: scanAndMemoizeBracesFrom(text, bracePos, map)
    }

    private fun scanAndMemoizeBracesFrom(text: String, bracePos: Int, map: MutableMap<Int, Int>): Int {
        val stack = ArrayDeque<Int>()
        var noCloseQuoteBefore = -1
        var idx = bracePos
        while (idx < text.length) {
            val ch = text[idx]
            if (ch == '"' && idx >= noCloseQuoteBefore) {
                val strEnd = scanJsonStringEnd(text, idx + 1)
                if (strEnd > 0) {
                    idx = strEnd
                } else {
                    noCloseQuoteBefore = -strEnd - 1
                    idx++
                }
            } else if (ch == '{') {
                val known = map[idx]
                if (known != null) {
                    if (known > idx) {
                        idx = known
                    } else {
                        markUnmatched(stack, map)
                        return -1
                    }
                } else {
                    stack.addLast(idx)
                    idx++
                }
            } else if (ch == '}' && stack.isNotEmpty()) {
                val open = stack.removeLast()
                map[open] = idx + 1
                if (stack.isEmpty()) return idx + 1
                idx++
            } else {
                idx++
            }
        }
        markUnmatched(stack, map)
        return -1
    }

    private fun markUnmatched(stack: ArrayDeque<Int>, map: MutableMap<Int, Int>) {
        while (stack.isNotEmpty()) map[stack.removeLast()] = -1
    }

    private fun scanJsonStringEnd(text: String, start: Int): Int {
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (isNewline(ch)) return -(i + 1)
            if (ch == '"' && !escaped) return i + 1
            escaped = ch == '\\' && !escaped
        }
        return -(text.length + 1)
    }

    private fun skipJsonWhitespace(text: String, start: Int): Int {
        var p = start
        while (p < text.length && isJsonWhitespace(text[p])) p++
        return p
    }

    private fun isHorizSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'
    private fun isNewline(ch: Char): Boolean = ch == '\r' || ch == '\n'
    private fun isJsonWhitespace(ch: Char): Boolean = isHorizSpace(ch) || isNewline(ch)
}
