package net.alummahnow.core.bridge.telemetry

/**
 * JSON delimiter and backward-container helpers for [CoreTelemetryAuthScanner].
 */
internal object CoreTelemetryAuthJsonHelpers {
    private const val ASCII_SPACE = 0x20
    private val jsonNumberRegex = Regex("""^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$""")
    private val jsonKeywords = listOf("true", "false", "null")

    class JsonScanCache {
        val validJsonValueEnd = HashMap<Int, Int>()
        val closeToOpenMap = HashMap<Int, Int>()
        val memberCommaOpen = HashMap<Int, Int>()
        val arrayCommaOpen = HashMap<Int, Int>()
        val containerAuthState = HashMap<Int, Int>()
        var failedContainerBack = -1
    }

    private data class CloseFrame(val closeIdx: Int, val expectedOpen: Char)

    fun isJsonNumber(token: String): Boolean = jsonNumberRegex.matches(token)

    fun isJsonDelimiter(ch: Char): Boolean = ch == ',' || ch == '}' || ch == ']'

    fun isJsonPrimitiveStop(ch: Char): Boolean =
        isHorizSpace(ch) || isNewline(ch) || isJsonDelimiter(ch)

    fun isPrecededByBraceOrComma(text: String, pos: Int): Boolean {
        val start = skipJsonWhitespaceBackward(text, pos - 1)
        return start >= 0 && (text[start] == '{' || text[start] == ',')
    }

    fun findEnclosingJsonObjectStart(text: String, pos: Int, cache: JsonScanCache): Int {
        val start = skipJsonWhitespaceBackward(text, pos - 1)
        return when {
            start < 0 -> -1
            text[start] == '{' -> start
            text[start] == ',' -> findEnclosingJsonObjectStartFromComma(text, start, cache)
            else -> -1
        }
    }

    private fun findEnclosingJsonObjectStartFromComma(text: String, startComma: Int, cache: JsonScanCache): Int {
        cache.memberCommaOpen[startComma]?.let { return it }
        val visited = ArrayList<Int>()
        var p = startComma
        var openBrace = -1
        var walking = true
        while (walking && p >= 0 && text[p] == ',') {
            val known = cache.memberCommaOpen[p]
            if (known != null) {
                openBrace = known
                walking = false
            } else {
                visited.add(p)
                val beforeKey = stepBackOverJsonMember(text, p, cache)
                if (beforeKey < 0) {
                    walking = false
                } else if (text[beforeKey] == '{') {
                    openBrace = beforeKey
                    walking = false
                } else {
                    p = beforeKey
                }
            }
        }
        for (commaPos in visited) cache.memberCommaOpen[commaPos] = openBrace
        return openBrace
    }

    private fun findEnclosingJsonArrayStartFromComma(text: String, startComma: Int, cache: JsonScanCache): Int {
        cache.arrayCommaOpen[startComma]?.let { return it }
        val visited = ArrayList<Int>()
        var p = startComma
        var openBracket = -1
        var walking = true
        while (walking && p >= 0 && text[p] == ',') {
            val known = cache.arrayCommaOpen[p]
            if (known != null) {
                openBracket = known
                walking = false
            } else {
                visited.add(p)
                val beforeVal = stepBackOverJsonArrayElement(text, p, cache)
                if (beforeVal < 0) {
                    walking = false
                } else if (text[beforeVal] == '[') {
                    openBracket = beforeVal
                    walking = false
                } else {
                    p = beforeVal
                }
            }
        }
        for (commaPos in visited) cache.arrayCommaOpen[commaPos] = openBracket
        return openBracket
    }

    private fun stepBackOverJsonArrayElement(text: String, commaPos: Int, cache: JsonScanCache): Int {
        val prev = skipJsonWhitespaceBackward(text, commaPos - 1)
        if (prev < 0) return -1
        val valStart = findJsonValueStartBackward(text, prev, cache)
        val beforeVal = if (valStart > 0) skipJsonWhitespaceBackward(text, valStart - 1) else -1
        return if (beforeVal >= 0 && (text[beforeVal] == '[' || text[beforeVal] == ',')) beforeVal else -1
    }

    fun classifyTopLevelJsonContainer(text: String, openPos: Int, cache: JsonScanCache): Int {
        cache.containerAuthState[openPos]?.let { return it }
        val visited = ArrayList<Int>()
        var curOpen = openPos
        var authState = -1
        var walking = true
        while (walking && curOpen >= 0) {
            val step = stepTopLevelJsonContainer(text, curOpen, visited, cache)
            if (step == Int.MIN_VALUE) {
                walking = false
            } else if (step <= -2) {
                curOpen = -step - 2
            } else {
                authState = step
                walking = false
            }
        }
        for (pos in visited) cache.containerAuthState[pos] = authState
        return authState
    }

    private fun stepTopLevelJsonContainer(
        text: String,
        curOpen: Int,
        visited: ArrayList<Int>,
        cache: JsonScanCache
    ): Int {
        cache.containerAuthState[curOpen]?.let { return it }
        visited.add(curOpen)
        val endPos = CoreTelemetryAuthJsonValidator.tryParseJsonValue(text, curOpen, cache.validJsonValueEnd)
        if (endPos <= curOpen) return Int.MIN_VALUE
        val parentOpen = findParentJsonContainerOpen(text, curOpen, cache)
        if (parentOpen >= 0) return -(parentOpen + 2)
        return if (reachesLineOrTextEnd(text, endPos)) 0 else endPos
    }

    private fun reachesLineOrTextEnd(text: String, start: Int): Boolean {
        var p = start
        while (p < text.length && isHorizSpace(text[p])) p++
        return p >= text.length || isNewline(text[p])
    }

    private fun findParentJsonContainerOpen(text: String, valueStart: Int, cache: JsonScanCache): Int {
        val prev = skipJsonWhitespaceBackward(text, valueStart - 1)
        if (prev < 0) return -1
        return when (text[prev]) {
            ':' -> {
                val beforeKey = findDelimiterBeforeJsonMemberKeyColon(text, valueStart)
                when {
                    beforeKey < 0 -> -1
                    text[beforeKey] == '{' -> beforeKey
                    else -> findEnclosingJsonObjectStartFromComma(text, beforeKey, cache)
                }
            }
            '[' -> prev
            ',' -> findEnclosingJsonArrayStartFromComma(text, prev, cache)
            else -> -1
        }
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
            ch == '}' || ch == ']' -> scanContainerStartBackward(text, prev, cache)
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
        var open = -1
        var i = closeQuotePos - 1
        var scanning = true
        while (i >= 0 && scanning) {
            val ch = text[i]
            if (ch.code < ASCII_SPACE) {
                scanning = false
            } else if (ch == '"' && !isEscapedQuote(text, i)) {
                open = i
                scanning = false
            } else {
                i--
            }
        }
        val valid = open >= 0 && CoreTelemetryAuthJsonValidator.scanJsonStringEnd(text, open + 1) == closeQuotePos + 1
        return if (valid) open else -1
    }

    private fun scanContainerStartBackward(text: String, closePos: Int, cache: JsonScanCache): Int {
        cache.closeToOpenMap[closePos]?.let { return it }
        if (closePos <= cache.failedContainerBack) return -1
        val matched = walkContainerBackward(text, closePos, cache)
        if (matched < 0) {
            cache.closeToOpenMap[closePos] = -1
            cache.failedContainerBack = maxOf(cache.failedContainerBack, closePos)
        }
        return matched
    }

    private fun walkContainerBackward(text: String, closePos: Int, cache: JsonScanCache): Int {
        val stack = ArrayDeque<CloseFrame>()
        var idx = closePos
        var foundOpen = -1
        while (idx > cache.failedContainerBack && foundOpen < 0) {
            val step = stepContainerBackward(text, idx, stack, cache)
            if (step == Int.MIN_VALUE) {
                idx = cache.failedContainerBack
            } else if (step >= 0 && stack.isEmpty()) {
                foundOpen = step
            } else {
                idx = if (step < 0) -step - 2 else step - 1
            }
        }
        if (foundOpen >= 0) return foundOpen
        while (stack.isNotEmpty()) cache.closeToOpenMap[stack.removeLast().closeIdx] = -1
        return -1
    }

    private fun stepContainerBackward(
        text: String,
        idx: Int,
        stack: ArrayDeque<CloseFrame>,
        cache: JsonScanCache
    ): Int = when (val ch = text[idx]) {
        '"' -> stepBackwardQuote(text, idx)
        '}', ']' -> stepBackwardCloser(idx, ch, stack, cache)
        '{', '[' -> matchContainerOpener(text, idx, ch, stack, cache)
        else -> -((idx - 1) + 2)
    }

    private fun stepBackwardQuote(text: String, idx: Int): Int {
        val strStart = if (!isEscapedQuote(text, idx)) scanJsonStringStartBackward(text, idx) else -1
        return if (strStart < 0) Int.MIN_VALUE else -((strStart - 1) + 2)
    }

    private fun stepBackwardCloser(
        idx: Int,
        ch: Char,
        stack: ArrayDeque<CloseFrame>,
        cache: JsonScanCache
    ): Int {
        val innerOpen = cache.closeToOpenMap[idx]
        if (innerOpen != null) return if (innerOpen < 0) Int.MIN_VALUE else -((innerOpen - 1) + 2)
        stack.addLast(CloseFrame(idx, if (ch == '}') '{' else '['))
        return -((idx - 1) + 2)
    }

    private fun matchContainerOpener(
        text: String,
        openIdx: Int,
        openChar: Char,
        stack: ArrayDeque<CloseFrame>,
        cache: JsonScanCache
    ): Int {
        if (stack.isEmpty() || stack.last().expectedOpen != openChar) return Int.MIN_VALUE
        val top = stack.removeLast()
        val parsedEnd = CoreTelemetryAuthJsonValidator.tryParseJsonValue(text, openIdx, cache.validJsonValueEnd)
        if (parsedEnd != top.closeIdx + 1) {
            cache.closeToOpenMap[top.closeIdx] = -1
            return Int.MIN_VALUE
        }
        cache.closeToOpenMap[top.closeIdx] = openIdx
        return openIdx
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
        val strEnd = CoreTelemetryAuthJsonValidator.scanJsonStringEnd(text, next + 1)
        val afterKey = if (strEnd > 0) skipJsonWhitespace(text, strEnd) else text.length
        return afterKey < text.length && text[afterKey] == ':'
    }

    fun scanBalancedJsonObjectEnd(text: String, bracePos: Int, cache: JsonScanCache): Int {
        if (bracePos >= text.length || text[bracePos] != '{') return -1
        return CoreTelemetryAuthJsonValidator.tryParseJsonValue(text, bracePos, cache.validJsonValueEnd)
    }

    fun skipJsonWhitespace(text: String, start: Int): Int {
        var p = start
        while (p < text.length && isJsonWhitespace(text[p])) p++
        return p
    }

    private fun isHorizSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'
    private fun isNewline(ch: Char): Boolean = ch == '\r' || ch == '\n'
    private fun isJsonWhitespace(ch: Char): Boolean = isHorizSpace(ch) || isNewline(ch)
}
