package net.alummahnow.core.bridge.telemetry

/**
 * JSON delimiter and balanced-brace helpers for [CoreTelemetryAuthScanner].
 */
internal object CoreTelemetryAuthJsonHelpers {
    private const val ASCII_SPACE = 0x20
    private val jsonNumberRegex = Regex("""^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$""")
    private val jsonKeywords = listOf("true", "false", "null")

    class JsonScanCache {
        var braceMap: MutableMap<Int, Int>? = null
        val closeToOpenMap = HashMap<Int, Int>()
        val memberCommaValid = HashMap<Int, Boolean>()
        var failedBraceBack = -1
        var failedBracketBack = -1
    }

    fun isJsonNumber(token: String): Boolean = jsonNumberRegex.matches(token)

    fun isJsonDelimiter(ch: Char): Boolean = ch == ',' || ch == '}' || ch == ']'

    fun isJsonPrimitiveStop(ch: Char): Boolean =
        isHorizSpace(ch) || isNewline(ch) || isJsonDelimiter(ch)

    fun isPrecededByJsonMemberDelimiter(text: String, pos: Int, cache: JsonScanCache): Boolean {
        val start = skipJsonWhitespaceBackward(text, pos - 1)
        if (start < 0) return false
        if (text[start] == '{') return true
        if (text[start] != ',') return false
        return cache.memberCommaValid[start] ?: walkMemberCommasBackward(text, start, cache)
    }

    private fun walkMemberCommasBackward(text: String, startComma: Int, cache: JsonScanCache): Boolean {
        val visited = ArrayList<Int>()
        var p = startComma
        var result = false
        var walking = true
        while (walking && p >= 0 && text[p] == ',') {
            val known = cache.memberCommaValid[p]
            if (known != null) {
                result = known
                walking = false
            } else {
                visited.add(p)
                val beforeKey = stepBackOverJsonMember(text, p, cache)
                if (beforeKey < 0) {
                    walking = false
                } else if (text[beforeKey] == '{') {
                    result = true
                    walking = false
                } else {
                    p = beforeKey
                }
            }
        }
        for (commaPos in visited) cache.memberCommaValid[commaPos] = result
        return result
    }

    private fun stepBackOverJsonMember(text: String, commaPos: Int, cache: JsonScanCache): Int {
        val prev = skipJsonWhitespaceBackward(text, commaPos - 1)
        if (prev < 0) return -1
        val valueStart = findJsonValueStartBackward(text, prev, cache)
        return if (valueStart > 0) findDelimiterBeforeJsonMemberKeyColon(text, valueStart) else -1
    }

    private fun findJsonValueStartBackward(text: String, prev: Int, cache: JsonScanCache): Int {
        val ch = text[prev]
        return when {
            ch == '"' -> scanJsonStringStartBackward(text, prev)
            ch == '}' -> scanBraceStartBackward(text, prev, cache)
            ch == ']' -> scanBracketStartBackward(text, prev, cache)
            ch in '0'..'9' -> findJsonNumberStartBefore(text, prev)
            else -> findJsonKeywordStartEndingAt(text, prev)
        }
    }

    private fun findJsonNumberStartBefore(text: String, prev: Int): Int {
        var s = prev
        while (s >= 0 && !isJsonValuePrefixStop(text[s])) s--
        return if (isJsonNumber(text.substring(s + 1, prev + 1))) s + 1 else -1
    }

    private fun findJsonKeywordStartEndingAt(text: String, endIdx: Int): Int {
        for (keyword in jsonKeywords) {
            val start = endIdx - keyword.length + 1
            if (start > 0 && text.regionMatches(start, keyword, 0, keyword.length)) return start
        }
        return -1
    }

    private fun scanBraceStartBackward(text: String, prev: Int, cache: JsonScanCache): Int {
        val start = scanContainerStartBackward(text, prev, '{', '}', cache.failedBraceBack, cache.closeToOpenMap)
        if (start < 0) cache.failedBraceBack = maxOf(cache.failedBraceBack, prev)
        return start
    }

    private fun scanBracketStartBackward(text: String, prev: Int, cache: JsonScanCache): Int {
        val start = scanContainerStartBackward(text, prev, '[', ']', cache.failedBracketBack, cache.closeToOpenMap)
        if (start < 0) cache.failedBracketBack = maxOf(cache.failedBracketBack, prev)
        return start
    }

    private fun findDelimiterBeforeJsonMemberKeyColon(text: String, valueStart: Int): Int {
        val colonPos = skipJsonWhitespaceBackward(text, valueStart - 1)
        if (colonPos <= 0 || text[colonPos] != ':') return -1
        val keyEnd = skipJsonWhitespaceBackward(text, colonPos - 1)
        if (keyEnd <= 0 || text[keyEnd] != '"') return -1
        val keyStart = scanJsonStringStartBackward(text, keyEnd)
        val beforeKey = if (keyStart > 0) skipJsonWhitespaceBackward(text, keyStart - 1) else -1
        return if (beforeKey >= 0 && (text[beforeKey] == '{' || text[beforeKey] == ',')) beforeKey else -1
    }

    private fun skipJsonWhitespaceBackward(text: String, start: Int): Int {
        var p = start
        while (p >= 0 && isJsonWhitespace(text[p])) p--
        return p
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
            if (ch.code < ASCII_SPACE) return -1
            if (ch == '"' && !isEscapedQuote(text, i)) return i
        }
        return -1
    }

    private fun scanContainerStartBackward(
        text: String,
        closePos: Int,
        openChar: Char,
        closeChar: Char,
        floor: Int,
        closeToOpenMap: MutableMap<Int, Int>
    ): Int {
        closeToOpenMap[closePos]?.let { return it }
        val stack = ArrayDeque<Int>()
        var idx = closePos
        var failed = false
        while (idx > floor && !failed) {
            val ch = text[idx]
            if (ch == '"' && !isEscapedQuote(text, idx)) {
                val strStart = scanJsonStringStartBackward(text, idx)
                if (strStart < 0) failed = true else idx = strStart - 1
            } else if (ch == closeChar) {
                val innerOpen = closeToOpenMap[idx]
                if (innerOpen != null) {
                    if (innerOpen < 0) failed = true else idx = innerOpen - 1
                } else {
                    stack.addLast(idx)
                    idx--
                }
            } else if (ch == openChar && stack.isNotEmpty()) {
                val matchedClose = stack.removeLast()
                closeToOpenMap[matchedClose] = idx
                if (stack.isEmpty()) return idx
                idx--
            } else {
                idx--
            }
        }
        markUnmatched(stack, closeToOpenMap)
        return -1
    }

    private fun isJsonValuePrefixStop(ch: Char): Boolean =
        isJsonWhitespace(ch) || ch == ':' || ch == ',' || ch == '['

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
            if (ch.code < ASCII_SPACE) return -(i + 1)
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
