package net.alummahnow.core.bridge.telemetry

/**
 * Iterative O(n) memoized RFC 8259 JSON value and string validator for [CoreTelemetryAuthJsonHelpers].
 */
internal object CoreTelemetryAuthJsonValidator {
    private const val ASCII_SPACE = 0x20
    private const val HEX_ESCAPE_STEP = 6
    private const val SIMPLE_ESCAPE_CHARS = "\"\\/bfnrt"
    private const val STEP_EXPECT_VALUE = 0
    private const val STEP_VALUE_DONE = 1
    private const val STEP_NEXT_VALUE = 2

    private data class Frame(val openPos: Int, val isObject: Boolean)

    private class ParseState(val cache: MutableMap<Int, Int>, var pos: Int, var expectingValue: Boolean = true)

    fun scanJsonStringEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val ch = text[i]
            when {
                ch.code < ASCII_SPACE -> return -(i + 1)
                ch == '"' -> return i + 1
                ch == '\\' -> {
                    val step = jsonEscapeStep(text, i)
                    if (step < 0) return -(i + 2)
                    i += step
                }
                else -> i++
            }
        }
        return -(text.length + 1)
    }

    private fun jsonEscapeStep(text: String, slashPos: Int): Int {
        if (slashPos + 1 >= text.length) return -1
        val esc = text[slashPos + 1]
        return when {
            esc in SIMPLE_ESCAPE_CHARS -> 2
            esc == 'u' && slashPos + HEX_ESCAPE_STEP <= text.length && isHex4(text, slashPos + 2) -> HEX_ESCAPE_STEP
            else -> -1
        }
    }

    private fun isHex4(text: String, pos: Int): Boolean {
        for (offset in 0 until 4) {
            val ch = text[pos + offset]
            val valid = ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            if (!valid) return false
        }
        return true
    }

    fun tryParseJsonValue(text: String, startPos: Int, cache: MutableMap<Int, Int>): Int {
        if (startPos >= text.length) return -1
        cache[startPos]?.let { return it }
        val stack = ArrayDeque<Frame>()
        val state = ParseState(cache, startPos)
        var result = -1
        var running = true
        while (running) {
            state.pos = skipJsonWhitespace(text, state.pos)
            val step = if (state.expectingValue) {
                parseValueStart(text, state, stack)
            } else {
                parseAfterValue(text, state, stack)
            }
            when {
                step < 0 -> {
                    failParse(startPos, stack, cache)
                    running = false
                }
                step == STEP_VALUE_DONE -> {
                    state.expectingValue = false
                    if (stack.isEmpty()) {
                        result = state.pos
                        running = false
                    }
                }
                step == STEP_NEXT_VALUE -> state.expectingValue = true
            }
        }
        return result
    }

    private fun failParse(startPos: Int, stack: ArrayDeque<Frame>, cache: MutableMap<Int, Int>) {
        while (stack.isNotEmpty()) cache[stack.removeLast().openPos] = -1
        cache[startPos] = -1
    }

    private fun parseValueStart(text: String, state: ParseState, stack: ArrayDeque<Frame>): Int {
        if (state.pos >= text.length) return -1
        return when (text[state.pos]) {
            '{' -> openObject(text, state, stack)
            '[' -> openArray(text, state, stack)
            else -> {
                val scalarEnd = scanScalarEnd(text, state.pos)
                if (scalarEnd > state.pos) {
                    state.pos = scalarEnd
                    STEP_VALUE_DONE
                } else {
                    -1
                }
            }
        }
    }

    private fun openObject(text: String, state: ParseState, stack: ArrayDeque<Frame>): Int {
        val openPos = state.pos
        val cached = state.cache[openPos]
        if (cached != null) {
            if (cached > openPos) state.pos = cached
            return if (cached > openPos) STEP_VALUE_DONE else -1
        }
        val next = skipJsonWhitespace(text, openPos + 1)
        if (next < text.length && text[next] == '}') {
            state.cache[openPos] = next + 1
            state.pos = next + 1
            return STEP_VALUE_DONE
        }
        val afterColon = advanceOverObjectKeyColon(text, next)
        if (afterColon >= 0) {
            state.pos = afterColon
            stack.addLast(Frame(openPos, isObject = true))
        }
        return if (afterColon >= 0) STEP_EXPECT_VALUE else -1
    }

    private fun openArray(text: String, state: ParseState, stack: ArrayDeque<Frame>): Int {
        val openPos = state.pos
        state.cache[openPos]?.let { cached ->
            if (cached <= openPos) return -1
            state.pos = cached
            return STEP_VALUE_DONE
        }
        val next = skipJsonWhitespace(text, openPos + 1)
        if (next < text.length && text[next] == ']') {
            state.cache[openPos] = next + 1
            state.pos = next + 1
            return STEP_VALUE_DONE
        }
        state.pos = next
        stack.addLast(Frame(openPos, isObject = false))
        return STEP_EXPECT_VALUE
    }

    private fun parseAfterValue(text: String, state: ParseState, stack: ArrayDeque<Frame>): Int {
        if (stack.isEmpty()) return STEP_VALUE_DONE
        if (state.pos >= text.length) return -1
        val top = stack.last()
        val ch = text[state.pos]
        return if (top.isObject) {
            parseAfterObjectValue(text, state, stack, top, ch)
        } else {
            parseAfterArrayValue(state, stack, top, ch)
        }
    }

    private fun parseAfterObjectValue(
        text: String,
        state: ParseState,
        stack: ArrayDeque<Frame>,
        top: Frame,
        ch: Char
    ): Int {
        if (ch == '}') {
            stack.removeLast()
            state.cache[top.openPos] = state.pos + 1
            state.pos++
            return STEP_VALUE_DONE
        }
        if (ch != ',') return -1
        val afterColon = advanceOverObjectKeyColon(text, skipJsonWhitespace(text, state.pos + 1))
        if (afterColon < 0) return -1
        state.pos = afterColon
        return STEP_NEXT_VALUE
    }

    private fun parseAfterArrayValue(
        state: ParseState,
        stack: ArrayDeque<Frame>,
        top: Frame,
        ch: Char
    ): Int = when (ch) {
        ']' -> {
            stack.removeLast()
            state.cache[top.openPos] = state.pos + 1
            state.pos++
            STEP_VALUE_DONE
        }
        ',' -> {
            state.pos++
            STEP_NEXT_VALUE
        }
        else -> -1
    }

    private fun advanceOverObjectKeyColon(text: String, start: Int): Int {
        if (start >= text.length || text[start] != '"') return -1
        val keyEnd = scanJsonStringEnd(text, start + 1)
        if (keyEnd <= 0) return -1
        val colon = skipJsonWhitespace(text, keyEnd)
        return if (colon < text.length && text[colon] == ':') colon + 1 else -1
    }

    private fun scanScalarEnd(text: String, pos: Int): Int = when (text[pos]) {
        '"' -> scanJsonStringEnd(text, pos + 1)
        't' -> if (text.regionMatches(pos, "true", 0, 4)) pos + 4 else -1
        'f' -> if (text.regionMatches(pos, "false", 0, 5)) pos + 5 else -1
        'n' -> if (text.regionMatches(pos, "null", 0, 4)) pos + 4 else -1
        else -> scanNumberEnd(text, pos)
    }

    private fun scanNumberEnd(text: String, start: Int): Int {
        var i = start
        if (i < text.length && text[i] == '-') i++
        val afterInt = scanIntPart(text, i)
        if (afterInt < 0) return -1
        val afterFrac = scanFractionPart(text, afterInt)
        if (afterFrac < 0) return -1
        return scanExponentPart(text, afterFrac)
    }

    private fun scanIntPart(text: String, pos: Int): Int {
        if (pos >= text.length || text[pos] !in '0'..'9') return -1
        if (text[pos] == '0') return pos + 1
        var i = pos + 1
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    private fun scanFractionPart(text: String, pos: Int): Int {
        if (pos >= text.length || text[pos] != '.') return pos
        var i = pos + 1
        if (i >= text.length || text[i] !in '0'..'9') return -1
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    private fun scanExponentPart(text: String, pos: Int): Int {
        if (pos >= text.length || (text[pos] != 'e' && text[pos] != 'E')) return pos
        var i = pos + 1
        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
        if (i >= text.length || text[i] !in '0'..'9') return -1
        while (i < text.length && text[i] in '0'..'9') i++
        return i
    }

    private fun skipJsonWhitespace(text: String, start: Int): Int {
        var p = start
        while (p < text.length && isJsonWhitespace(text[p])) p++
        return p
    }

    private fun isJsonWhitespace(ch: Char): Boolean = ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'
}
