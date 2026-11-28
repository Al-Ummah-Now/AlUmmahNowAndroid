package net.alummahnow.core.bridge.telemetry

/**
 * Helpers for trimmed quoted auth-header keys, 2-element JSON header tuples,
 * and unquoted raw escaped-quote JSON header dumps (`{\"Authorization\":\"...\"}`).
 */
@Suppress("TooManyFunctions")
internal object CoreTelemetryAuthEscapedHelpers {
    private const val MAX_AUTH_KEY_LEN = 19
    private const val HEX_ESCAPE_LEN = 6
    private const val ASCII_SPACE = 0x20

    private data class ContainerFrame(val openPos: Int, val expectedClose: Char)

    private val canonicalAuthHeaders = setOf(
        "authorization",
        "proxy-authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "x-auth-token",
        "x-access-token",
        "x-api-token"
    )

    fun matchCanonicalAuthHeaderName(raw: String): String? {
        val trimmed = raw.trim { it == ' ' || it == '\t' }
        return if (trimmed.lowercase(java.util.Locale.ROOT) in canonicalAuthHeaders) trimmed else null
    }

    fun decodeSimpleJsonEscape(next: Char): Pair<Char, Boolean> = when (next) {
        't' -> '\t' to true
        'n' -> '\n' to true
        'r' -> '\r' to true
        'b' -> '\b' to true
        'f' -> '\u000C' to true
        '"', '\\', '/' -> next to true
        else -> next to false
    }

    fun resolveJsonTupleValueStart(
        text: String,
        afterComma: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): Int {
        val valStart = CoreTelemetryAuthJsonHelpers.skipJsonWhitespace(text, afterComma)
        if (valStart >= text.length) return -1
        val valEnd = CoreTelemetryAuthJsonValidator.tryParseJsonValue(text, valStart, cache.validJsonValueEnd)
        if (valEnd <= valStart) return -1
        val afterVal = CoreTelemetryAuthJsonHelpers.skipJsonWhitespace(text, valEnd)
        return if (afterVal < text.length && text[afterVal] == ']') valStart else -1
    }

    fun tryRedactEscapedQuotedAuthHeader(
        text: String,
        pos: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val keyEnd = matchEscapedQuotedKeyEnd(text, pos)
        if (keyEnd < 0) return -1
        val prevCh = prevNonJsonWhitespaceChar(text, pos - 1)
        val isEscapedJsonCtx = prevCh == '{' || prevCh == ',' || prevCh == '['
        val sep = skipWhitespace(text, keyEnd, allowNewlines = isEscapedJsonCtx)
        val sepMatch = matchEscapedAuthSeparator(text, prevCh, sep) ?: return -1
        val (sepLen, isTuple) = sepMatch
        val valStart = skipWhitespace(text, sep + sepLen, allowNewlines = isEscapedJsonCtx)
        if (valStart >= text.length || isNewline(text[valStart])) return -1
        return redactEscapedValue(text, valStart, isTuple, cache, appendRedacted, redactValueBody)
    }

    private fun prevNonJsonWhitespaceChar(text: String, start: Int): Char {
        var p = start
        while (p >= 0) {
            val ch = text[p]
            if (ch in " \t\r\n") {
                p--
            } else if (isBackwardEscapedWs(text, p)) {
                p -= 2
            } else {
                return ch
            }
        }
        return '\u0000'
    }

    private fun isBackwardEscapedWs(text: String, pos: Int): Boolean =
        pos >= 1 &&
            text[pos - 1] == '\\' &&
            text[pos] in "tnr" &&
            !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos - 1, 0)

    private fun isEscapedWhitespaceToken(text: String, pos: Int): Boolean =
        pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] in "tnr"

    private fun matchEscapedQuotedKeyEnd(text: String, pos: Int): Int {
        val core = StringBuilder(MAX_AUTH_KEY_LEN + 1)
        var j = pos + 2
        var sawTrailingPad = false
        while (j < text.length) {
            val ch = text[j]
            if (isInvalidEscapedKeyChar(ch)) return -1
            if (isEscapedDoubleQuoteAt(text, j)) {
                return if (matchCanonicalAuthHeaderName(core.toString()) != null) j + 2 else -1
            }
            val (dec, step) = decodeEscapedKeyChar(text, j)
            if (step < 0 || !appendTrimmedKeyChar(dec, core, sawTrailingPad)) return -1
            sawTrailingPad = sawTrailingPad || (isHorizPad(dec) && core.isNotEmpty())
            j += step
        }
        return -1
    }

    private fun isInvalidEscapedKeyChar(ch: Char): Boolean = (ch.code < ASCII_SPACE && ch != '\t') || ch == '"'

    private fun isEscapedDoubleQuoteAt(text: String, pos: Int): Boolean =
        pos + 1 < text.length && text[pos] == '\\' && text[pos + 1] == '"'

    private fun isHorizPad(ch: Char): Boolean = ch == ' ' || ch == '\t'

    private fun appendTrimmedKeyChar(ch: Char, core: StringBuilder, sawTrailingPad: Boolean): Boolean {
        if (isHorizPad(ch)) return true
        if (sawTrailingPad || core.length >= MAX_AUTH_KEY_LEN) return false
        core.append(ch)
        return true
    }

    private fun decodeEscapedKeyChar(text: String, pos: Int): Pair<Char, Int> {
        val ch = text[pos]
        if (ch != '\\') return ch to 1
        if (pos + 1 >= text.length) return '\u0000' to -1
        val next = text[pos + 1]
        if (next == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            if (code >= 0) return code.toChar() to HEX_ESCAPE_LEN
        }
        return decodeSimpleJsonEscape(next).first to 2
    }

    private fun matchEscapedAuthSeparator(text: String, prevCh: Char, sep: Int): Pair<Int, Boolean>? {
        val sepMatch = CoreTelemetryQuotedAuthHelpers.matchAuthSeparator(text, sep)
        if (sepMatch != null) return sepMatch.first to false
        val isTupleComma = prevCh == '[' && sep < text.length && text[sep] == ','
        return if (isTupleComma) 1 to true else null
    }

    @Suppress("LongParameterList")
    private fun redactEscapedValue(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val ch = text[valStart]
        val isEscQuote = ch == '\\' && valStart + 1 < text.length && text[valStart + 1] == '"'
        return when {
            isEscQuote -> redactEscapedStringValue(text, valStart, isTuple, appendRedacted, redactValueBody)
            ch == '[' -> redactEscapedArrayForHeader(text, valStart, isTuple, cache, appendRedacted, redactValueBody)
            ch == '{' -> redactEscapedObjectOrPlain(text, valStart, isTuple, cache, appendRedacted, redactValueBody)
            else -> redactEscapedScalarOrPlain(text, valStart, isTuple, appendRedacted, redactValueBody)
        }
    }

    @Suppress("LongParameterList")
    private fun redactEscapedArrayForHeader(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        if (isTuple) {
            val arrEnd = scanEscapedJsonContainerEnd(text, valStart, cache)
            if (arrEnd <= valStart || !isTupleClosedAt(text, arrEnd)) return -1
        }
        return redactEscapedAuthArrayValue(text, valStart + 1, cache, appendRedacted, redactValueBody)
    }

    @Suppress("LongParameterList")
    private fun redactEscapedObjectOrPlain(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val objEnd = scanEscapedJsonContainerEnd(text, valStart, cache)
        if (objEnd > valStart) {
            if (isTuple && !isTupleClosedAt(text, objEnd)) return -1
            appendRedacted(valStart, objEnd, "\\\"[redacted]\\\"")
            return objEnd
        }
        return if (!isTuple) redactPlainEscapedTail(text, valStart, appendRedacted, redactValueBody) else -1
    }

    private fun redactEscapedScalarOrPlain(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val primEnd = tryRedactEscapedJsonPrimitive(text, valStart, isTuple, appendRedacted)
        return when {
            primEnd > valStart -> primEnd
            !isTuple -> redactPlainEscapedTail(text, valStart, appendRedacted, redactValueBody)
            else -> -1
        }
    }

    private fun isTupleClosedAt(text: String, afterPos: Int): Boolean {
        val after = skipWhitespace(text, afterPos, allowNewlines = true)
        return after < text.length && text[after] == ']'
    }

    private fun redactPlainEscapedTail(
        text: String,
        valStart: Int,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        var end = valStart
        while (end < text.length && !isNewline(text[end])) end++
        redactValueBody(text, valStart, end)?.let { appendRedacted(valStart, end, it) }
        return end
    }

    private fun tryRedactEscapedJsonPrimitive(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        appendRedacted: (Int, Int, String) -> Unit
    ): Int {
        var tokEnd = valStart
        while (tokEnd < text.length && !isEscapedScalarStop(text[tokEnd]) && !isEscapedWhitespaceToken(text, tokEnd)) {
            tokEnd++
        }
        if (tokEnd == valStart) return -1
        val token = text.substring(valStart, tokEnd)
        val isNull = token == "null"
        val validPrim = isNull || token == "true" || token == "false" || CoreTelemetryAuthJsonHelpers.isJsonNumber(token)
        if (!validPrim || !isValidEscapedPrimitiveEnd(text, tokEnd, isTuple)) return -1
        if (!isNull) appendRedacted(valStart, tokEnd, "\\\"[redacted]\\\"")
        return tokEnd
    }

    private fun isValidEscapedPrimitiveEnd(text: String, tokEnd: Int, isTuple: Boolean): Boolean {
        if (isTuple) return isTupleClosedAt(text, tokEnd)
        val after = skipWhitespace(text, tokEnd, allowNewlines = true)
        return after >= text.length || text[after] in ",}]\r\n"
    }

    private fun isEscapedScalarStop(ch: Char): Boolean = ch in " \t\r\n,}]\""

    private fun scanEscapedJsonContainerEnd(
        text: String,
        start: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): Int {
        cache.escapedContainerEnd[start]?.let { return it }
        val stack = ArrayDeque<ContainerFrame>()
        var p = start
        var result = -1
        var failed = false
        while (p < text.length && result < 0 && !failed) {
            val next = stepEscapedContainer(text, p, stack, cache)
            when {
                next == Int.MIN_VALUE -> failed = true
                next >= 0 && stack.isEmpty() -> result = next
                next >= 0 -> p = next
                else -> p = -next - 2
            }
        }
        if (result > start) return result
        while (stack.isNotEmpty()) cache.escapedContainerEnd[stack.removeLast().openPos] = -1
        cache.escapedContainerEnd[start] = -1
        return -1
    }

    private fun stepEscapedContainer(
        text: String,
        pos: Int,
        stack: ArrayDeque<ContainerFrame>,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): Int = when (val ch = text[pos]) {
        '"' -> Int.MIN_VALUE
        '\\' -> stepEscapedContainerBackslash(text, pos)
        '{', '[' -> stepEscapedContainerOpener(pos, ch, stack, cache)
        '}', ']' -> stepEscapedContainerCloser(pos, ch, stack, cache)
        else -> -((pos + 1) + 2)
    }

    private fun stepEscapedContainerBackslash(text: String, pos: Int): Int {
        if (pos + 1 >= text.length || text[pos + 1] != '"') return -((pos + 1) + 2)
        val closeVal = scanEscapedQuotedValueEnd(text, pos + 2)
        val hasClose = closeVal + 1 < text.length && text[closeVal] == '\\' && text[closeVal + 1] == '"'
        return if (hasClose) -((closeVal + 2) + 2) else Int.MIN_VALUE
    }

    private fun stepEscapedContainerOpener(
        pos: Int,
        ch: Char,
        stack: ArrayDeque<ContainerFrame>,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): Int {
        val known = cache.escapedContainerEnd[pos]
        if (known != null) return if (known > pos) known else Int.MIN_VALUE
        stack.addLast(ContainerFrame(pos, if (ch == '{') '}' else ']'))
        return -((pos + 1) + 2)
    }

    private fun stepEscapedContainerCloser(
        pos: Int,
        ch: Char,
        stack: ArrayDeque<ContainerFrame>,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): Int {
        if (stack.isEmpty() || stack.last().expectedClose != ch) return Int.MIN_VALUE
        val top = stack.removeLast()
        cache.escapedContainerEnd[top.openPos] = pos + 1
        return pos + 1
    }

    private fun redactEscapedStringValue(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val closeVal = scanEscapedQuotedValueEnd(text, valStart + 2)
        val hasClose = closeVal + 1 < text.length && text[closeVal] == '\\' && text[closeVal + 1] == '"'
        val afterClose = if (hasClose) closeVal + 2 else closeVal
        if (isTuple && !isTupleClosedAt(text, afterClose)) return -1
        redactValueBody(text, valStart + 2, closeVal)?.let { appendRedacted(valStart + 2, closeVal, it) }
        return afterClose
    }

    private fun scanEscapedQuotedValueEnd(text: String, start: Int): Int {
        var p = start
        var innerEscaped = false
        var done = false
        while (p < text.length && !done) {
            val ch = text[p]
            if (isNewline(ch) || ch == '"') {
                done = true
            } else if (ch == '\\' && p + 1 < text.length) {
                val next = text[p + 1]
                if (next == '"' && !innerEscaped) {
                    done = true
                } else {
                    innerEscaped = if (next == '\\') !innerEscaped else false
                    p += 2
                }
            } else {
                innerEscaped = false
                p++
            }
        }
        return p
    }

    private fun redactEscapedAuthArrayValue(
        text: String,
        start: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        var p = start
        var depth = 1
        var done = false
        while (p < text.length && depth > 0 && !done) {
            val ch = text[p]
            when {
                ch == '"' -> done = true
                ch in " \t\r\n," -> p++
                isEscapedWhitespaceToken(text, p) -> p += 2
                ch == '[' -> {
                    depth++
                    p++
                }
                ch == ']' -> {
                    depth--
                    p++
                }
                else -> p = redactEscapedArrayElement(text, p, cache, appendRedacted, redactValueBody)
            }
        }
        return p
    }

    private fun redactEscapedArrayElement(
        text: String,
        pos: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val ch = text[pos]
        if (ch == '\\' && pos + 1 < text.length && text[pos + 1] == '"') {
            return redactEscapedArrayString(text, pos, appendRedacted, redactValueBody)
        }
        if (ch == '{') {
            val objEnd = scanEscapedJsonContainerEnd(text, pos, cache)
            if (objEnd > pos) {
                appendRedacted(pos, objEnd, "\\\"[redacted]\\\"")
                return objEnd
            }
        }
        return redactEscapedArrayScalar(text, pos, appendRedacted)
    }

    private fun redactEscapedArrayString(
        text: String,
        pos: Int,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val closeVal = scanEscapedQuotedValueEnd(text, pos + 2)
        redactValueBody(text, pos + 2, closeVal)?.let { appendRedacted(pos + 2, closeVal, it) }
        val hasClose = closeVal + 1 < text.length && text[closeVal] == '\\' && text[closeVal + 1] == '"'
        return if (hasClose) closeVal + 2 else closeVal
    }

    private fun redactEscapedArrayScalar(
        text: String,
        pos: Int,
        appendRedacted: (Int, Int, String) -> Unit
    ): Int {
        var elemEnd = pos
        while (elemEnd < text.length && isEscapedArrayScalarChar(text, elemEnd)) elemEnd++
        if (elemEnd == pos) return pos + 1
        if (text.substring(pos, elemEnd) != "null") appendRedacted(pos, elemEnd, "\\\"[redacted]\\\"")
        return elemEnd
    }

    private fun isEscapedArrayScalarChar(text: String, pos: Int): Boolean =
        !isEscapedScalarStop(text[pos]) && text[pos] != '[' && !isEscapedWhitespaceToken(text, pos)

    private fun skipWhitespace(text: String, start: Int, allowNewlines: Boolean): Int {
        var p = start
        while (p < text.length) {
            val step = whitespaceStepAt(text, p, allowNewlines)
            if (step == 0) break
            p += step
        }
        return p
    }

    private fun whitespaceStepAt(text: String, pos: Int, allowNewlines: Boolean): Int {
        val ch = text[pos]
        if (isHorizPad(ch) || (allowNewlines && isNewline(ch))) return 1
        if (ch == '\\' && pos + 1 < text.length) {
            val next = text[pos + 1]
            val isAllowedEsc = next == 't' || (allowNewlines && next in "nr")
            if (isAllowedEsc) return 2
        }
        return 0
    }

    private fun isNewline(ch: Char): Boolean = ch == '\r' || ch == '\n'
}
