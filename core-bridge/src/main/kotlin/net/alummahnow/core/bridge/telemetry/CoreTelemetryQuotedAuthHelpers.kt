package net.alummahnow.core.bridge.telemetry

/**
 * Isolated balanced-JSON-string auth header scrubbing and escaped Bearer/Basic token redaction.
 */
internal object CoreTelemetryQuotedAuthHelpers {
    private const val ASCII_CONTROL_LIMIT = 0x20
    private const val HEX_ESCAPE_LEN = 6

    fun findBalancedJsonStringEnd(text: String, open: Int): Int {
        val prev = skipJsonWhitespaceBackward(text, open - 1)
        if (prev >= 0 && text[prev] !in ":=[,{") return -1
        var pos = open + 1
        var escaped = false
        while (pos < text.length) {
            val ch = text[pos]
            if (ch == '\r' || ch == '\n') return -1
            if (ch == '"' && !escaped) {
                return if (isQuotedSpanEndBoundary(text, pos + 1)) pos else -1
            }
            escaped = ch == '\\' && !escaped
            pos++
        }
        return -1
    }

    private fun isQuotedSpanEndBoundary(text: String, afterPos: Int): Boolean {
        if (afterPos >= text.length || CoreTelemetryScanners.isSpace(text[afterPos])) return true
        val after = CoreTelemetryAuthJsonHelpers.skipJsonWhitespace(text, afterPos)
        return after >= text.length || text[after] in ",}]:;)"
    }

    private fun skipJsonWhitespaceBackward(text: String, start: Int): Int {
        var p = start
        while (p >= 0 && text[p] in " \t\r\n") p--
        return p
    }

    fun containsAuthHeaderCandidate(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            val ch = text[i]
            val isUnicodeEsc = ch == '\\' && i + HEX_ESCAPE_LEN <= end && text[i + 1] == 'u'
            if (ch == ':' || ch == '=' || isUnicodeEsc) return true
        }
        return false
    }

    fun escapeJsonStringContent(text: String): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (i in text.indices) {
            val ch = text[i]
            if (ch == '"' || ch == '\\' || ch.code < ASCII_CONTROL_LIMIT) {
                val out = sb ?: StringBuilder(text.length + 8).also { sb = it }
                out.append(text, copied, i).append(escapeSingleJsonChar(ch))
                copied = i + 1
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun escapeSingleJsonChar(ch: Char): String = when (ch) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> "\\u%04x".format(ch.code)
    }

    fun redactEscapedTokensInBalancedQuotes(
        text: String,
        bearerRegex: Regex,
        basicRegex: Regex,
        isValidBasic: (String) -> Boolean
    ): String {
        if (text.indexOf('"') < 0 || text.indexOf('\\') < 0) return text
        val afterBearer = redactEscapedTokenPass(text, bearerRegex, "bearer") { true }
        return redactEscapedTokenPass(afterBearer, basicRegex, "basic", isValidBasic)
    }

    private fun redactEscapedTokenPass(
        text: String,
        regex: Regex,
        keyword: String,
        validator: (String) -> Boolean
    ): String {
        val spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return text
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && hasBackslashInRange(text, innerStart, span.close)) {
                val (nextSb, nextCopied) = applyEscapedTokenSpan(
                    text,
                    innerStart,
                    span.close,
                    regex,
                    keyword,
                    validator,
                    sb,
                    copied
                )
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    @Suppress("LongParameterList")
    private fun applyEscapedTokenSpan(
        text: String,
        innerStart: Int,
        close: Int,
        regex: Regex,
        keyword: String,
        validator: (String) -> Boolean,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(text, innerStart, close)
        if (!decoded.contains(keyword, ignoreCase = true)) return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        var match = regex.find(decoded)
        while (match != null) {
            val tokenValid = match.groupValues.size <= 2 || validator(match.groupValues[2])
            val rawStart = rawOffsets[match.range.first]
            val rawEnd = rawOffsets[match.range.last + 1]
            if (tokenValid && rawStart >= copied) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawStart).append(match.groupValues[1]).append(" [redacted]")
                copied = rawEnd
            }
            match = match.next()
        }
        return sb to copied
    }

    private fun hasBackslashInRange(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (text[i] == '\\') return true
        }
        return false
    }
}
