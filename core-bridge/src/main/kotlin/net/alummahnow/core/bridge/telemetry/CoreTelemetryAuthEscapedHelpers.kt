package net.alummahnow.core.bridge.telemetry

/**
 * Helpers for trimmed quoted auth-header keys, 2-element JSON header tuples,
 * and unquoted raw escaped-quote JSON header dumps (`{\"Authorization\":\"...\"}`).
 */
@Suppress("TooManyFunctions")
internal object CoreTelemetryAuthEscapedHelpers {
    private const val MAX_ESCAPED_KEY_RAW_LEN = 256
    private const val MAX_ESCAPED_KEY_DECODED = 64
    private const val HEX_ESCAPE_LEN = 6
    private const val ASCII_SPACE = 0x20

    fun matchCanonicalAuthHeaderName(raw: String): String? {
        val trimmed = raw.trim { it == ' ' || it == '\t' }
        return when {
            trimmed.equals("authorization", ignoreCase = true) -> "Authorization"
            trimmed.equals("proxy-authorization", ignoreCase = true) -> "Proxy-Authorization"
            trimmed.equals("cookie", ignoreCase = true) -> "Cookie"
            trimmed.equals("set-cookie", ignoreCase = true) -> "Set-Cookie"
            else -> null
        }
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
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val keyEnd = matchEscapedQuotedKeyEnd(text, pos)
        if (keyEnd < 0) return -1
        val sep = skipHorizWhitespace(text, keyEnd)
        val sepMatch = matchEscapedAuthSeparator(text, pos, sep) ?: return -1
        val (sepLen, isTuple) = sepMatch
        val valStart = skipHorizWhitespace(text, sep + sepLen)
        if (valStart >= text.length || isNewline(text[valStart])) return -1
        return redactEscapedValue(text, valStart, isTuple, appendRedacted, redactValueBody)
    }

    private fun matchEscapedQuotedKeyEnd(text: String, pos: Int): Int {
        val limit = minOf(text.length, pos + MAX_ESCAPED_KEY_RAW_LEN)
        val decoded = StringBuilder(MAX_ESCAPED_KEY_DECODED)
        var j = pos + 2
        var foundEnd = -1
        var failed = false
        while (j < limit && foundEnd < 0 && !failed) {
            val ch = text[j]
            when {
                ch.code < ASCII_SPACE || ch == '"' -> failed = true
                ch == '\\' && j + 1 < limit && text[j + 1] == '"' -> foundEnd = j + 2
                decoded.length >= MAX_ESCAPED_KEY_DECODED -> failed = true
                ch == '\\' && j + 1 < limit -> j += decodeEscapedKeyStep(text, j, limit, decoded)
                else -> {
                    decoded.append(ch)
                    j++
                }
            }
        }
        return if (foundEnd >= 0 && matchCanonicalAuthHeaderName(decoded.toString()) != null) foundEnd else -1
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

    private fun decodeEscapedKeyStep(text: String, pos: Int, limit: Int, out: StringBuilder): Int {
        val next = text[pos + 1]
        if (next == 'u' && pos + HEX_ESCAPE_LEN <= limit) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            if (code >= 0) {
                out.append(code.toChar())
                return HEX_ESCAPE_LEN
            }
        }
        out.append(decodeSimpleJsonEscape(next).first)
        return 2
    }

    private fun matchEscapedAuthSeparator(text: String, keyStart: Int, sep: Int): Pair<Int, Boolean>? {
        val sepMatch = CoreTelemetryQuotedAuthHelpers.matchAuthSeparator(text, sep)
        if (sepMatch != null) return sepMatch.first to false
        var prev = keyStart - 1
        while (prev >= 0 && text[prev] in " \t\r\n") prev--
        val isTupleComma = prev >= 0 && text[prev] == '[' && sep < text.length && text[sep] == ','
        return if (isTupleComma) 1 to true else null
    }

    private fun redactEscapedValue(
        text: String,
        valStart: Int,
        isTuple: Boolean,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val ch = text[valStart]
        val isEscQuote = ch == '\\' && valStart + 1 < text.length && text[valStart + 1] == '"'
        return when {
            isEscQuote -> redactEscapedStringValue(text, valStart, isTuple, appendRedacted, redactValueBody)
            !isTuple && ch == '[' -> redactEscapedArrayValue(text, valStart + 1, appendRedacted, redactValueBody)
            !isTuple && ch == '{' -> redactEscapedObjectOrPlain(text, valStart, appendRedacted, redactValueBody)
            else -> redactEscapedScalarOrPlain(text, valStart, isTuple, appendRedacted, redactValueBody)
        }
    }

    private fun redactEscapedObjectOrPlain(
        text: String,
        valStart: Int,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val objEnd = scanEscapedJsonObjectEnd(text, valStart)
        if (objEnd > valStart) {
            appendRedacted(valStart, objEnd, "\\\"[redacted]\\\"")
            return objEnd
        }
        return redactPlainEscapedTail(text, valStart, appendRedacted, redactValueBody)
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
        while (tokEnd < text.length && !isEscapedScalarStop(text[tokEnd])) tokEnd++
        if (tokEnd == valStart) return -1
        val token = text.substring(valStart, tokEnd)
        val isNull = token == "null"
        val validPrim = isNull || token == "true" || token == "false" || CoreTelemetryAuthJsonHelpers.isJsonNumber(token)
        if (!validPrim || !isValidEscapedPrimitiveEnd(text, tokEnd, isTuple)) return -1
        if (!isNull) appendRedacted(valStart, tokEnd, "\\\"[redacted]\\\"")
        return tokEnd
    }

    private fun isValidEscapedPrimitiveEnd(text: String, tokEnd: Int, isTuple: Boolean): Boolean {
        val after = skipHorizWhitespace(text, tokEnd)
        if (isTuple) return after < text.length && text[after] == ']'
        return after >= text.length || text[after] in ",}]\r\n"
    }

    private fun isEscapedScalarStop(ch: Char): Boolean = ch in " \t\r\n,}]\""

    private fun scanEscapedJsonObjectEnd(text: String, start: Int): Int {
        var p = start
        var depth = 0
        var result = -1
        var failed = false
        while (p < text.length && result < 0 && !failed) {
            val ch = text[p]
            when {
                isNewline(ch) || ch == '"' -> failed = true
                ch == '\\' && p + 1 < text.length && text[p + 1] == '"' -> {
                    val closeVal = scanEscapedQuotedValueEnd(text, p + 2)
                    val hasClose = closeVal + 1 < text.length && text[closeVal] == '\\' && text[closeVal + 1] == '"'
                    if (hasClose) p = closeVal + 2 else failed = true
                }
                ch == '{' -> {
                    depth++
                    p++
                }
                ch == '}' -> {
                    depth--
                    p++
                    if (depth == 0) result = p
                }
                else -> p++
            }
        }
        return result
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
        if (isTuple) {
            val afterVal = skipHorizWhitespace(text, afterClose)
            if (afterVal >= text.length || text[afterVal] != ']') return -1
        }
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

    private fun redactEscapedArrayValue(
        text: String,
        start: Int,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        var p = start
        var depth = 1
        var done = false
        while (p < text.length && depth > 0 && !done) {
            val ch = text[p]
            when {
                isNewline(ch) || ch == '"' -> done = true
                ch == ' ' || ch == '\t' || ch == ',' -> p++
                ch == '[' -> {
                    depth++
                    p++
                }
                ch == ']' -> {
                    depth--
                    p++
                }
                else -> p = redactEscapedArrayElement(text, p, appendRedacted, redactValueBody)
            }
        }
        return p
    }

    private fun redactEscapedArrayElement(
        text: String,
        pos: Int,
        appendRedacted: (Int, Int, String) -> Unit,
        redactValueBody: (String, Int, Int) -> String?
    ): Int {
        val ch = text[pos]
        if (ch == '\\' && pos + 1 < text.length && text[pos + 1] == '"') {
            return redactEscapedArrayString(text, pos, appendRedacted, redactValueBody)
        }
        if (ch == '{') {
            val objEnd = scanEscapedJsonObjectEnd(text, pos)
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
        while (elemEnd < text.length && !isEscapedScalarStop(text[elemEnd]) && text[elemEnd] != '[') elemEnd++
        if (elemEnd == pos) return pos + 1
        if (text.substring(pos, elemEnd) != "null") appendRedacted(pos, elemEnd, "\\\"[redacted]\\\"")
        return elemEnd
    }

    private fun skipHorizWhitespace(text: String, start: Int): Int {
        var p = start
        while (p < text.length && (text[p] == ' ' || text[p] == '\t')) p++
        return p
    }

    private fun isNewline(ch: Char): Boolean = ch == '\r' || ch == '\n'
}
