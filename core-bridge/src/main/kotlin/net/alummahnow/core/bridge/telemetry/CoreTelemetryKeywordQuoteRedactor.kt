package net.alummahnow.core.bridge.telemetry

internal object CoreTelemetryKeywordQuoteRedactor {
    fun replaceKeywordQuotedPaths(text: String, regex: Regex): String {
        if (text.indexOf('"') < 0) return replaceKeywordQuotedOnSource(text, text, regex)
        var spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return replaceKeywordQuotedOnSource(text, text, regex)
        val masked = CoreTelemetryQuotedPathRedactor.buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = replaceKeywordQuotedOnSource(text, masked, regex)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return replaceKeywordQuotedInSpans(afterUnquoted, spans, regex)
    }

    private fun replaceKeywordQuotedOnSource(text: String, scanSource: String, regex: Regex): String {
        var sb: StringBuilder? = null
        var copied = 0
        forEachKeywordQuotedMatch(scanSource, regex) { kwEnd, closeEnd ->
            val out = sb ?: StringBuilder(text.length).also { sb = it }
            out.append(text, copied, kwEnd).append("[redacted-path]")
            copied = closeEnd
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun replaceKeywordQuotedInSpans(
        current: String,
        spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>,
        regex: Regex
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsQuoteOrQuoteEscape(current, innerStart, span.close)) {
                val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(
                    current,
                    innerStart,
                    span.close
                )
                if (decoded.indexOf('\'') >= 0 || decoded.indexOf('"') >= 0) {
                    forEachKeywordQuotedMatch(decoded, regex) { kwEnd, closeEnd ->
                        val out = sb ?: StringBuilder(current.length).also { sb = it }
                        out.append(current, copied, rawOffsets[kwEnd]).append("[redacted-path]")
                        copied = rawOffsets[closeEnd]
                    }
                }
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    private inline fun forEachKeywordQuotedMatch(source: String, regex: Regex, onMatch: (Int, Int) -> Unit) {
        var searchFrom = 0
        val unclosedUntil = intArrayOf(-1, -1)
        while (searchFrom < source.length) {
            val match = regex.find(source, searchFrom) ?: break
            val kwEnd = match.range.last + 1
            val slot = if (source[kwEnd] == '\'') 0 else 1
            searchFrom = stepKeywordQuoteMatch(source, kwEnd, slot, unclosedUntil, onMatch)
        }
    }

    private inline fun stepKeywordQuoteMatch(
        source: String,
        kwEnd: Int,
        slot: Int,
        unclosedUntil: IntArray,
        onMatch: (Int, Int) -> Unit
    ): Int {
        if (kwEnd < unclosedUntil[slot]) return kwEnd + 1
        val packed = findKeywordQuotedEnd(source, kwEnd)
        if (packed >= 0) {
            onMatch(kwEnd, packed)
            return packed
        }
        val stopPos = -packed - 2
        if (stopPos > kwEnd) unclosedUntil[slot] = maxOf(unclosedUntil[slot], stopPos)
        return kwEnd + 1
    }

    private fun findKeywordQuotedEnd(source: String, quotePos: Int): Int {
        if (quotePos + 1 >= source.length || source[quotePos + 1] == '\u001E') return -1
        val quote = source[quotePos]
        var j = quotePos + 1
        while (j < source.length) {
            val ch = source[j]
            if (isKeywordQuoteStop(ch)) return -(j + 2)
            if (ch == quote && !isInnerSingleApostrophe(source, j, quotePos, quote)) {
                return if (j > quotePos + 1) j + 1 else -1
            }
            j++
        }
        return -(source.length + 2)
    }

    private fun isKeywordQuoteStop(ch: Char): Boolean = ch in "\r\n\u001E\u001F"

    private fun isInnerSingleApostrophe(source: String, pos: Int, quotePos: Int, quote: Char): Boolean {
        if (quote != '\'' || pos <= quotePos + 1 || pos + 1 >= source.length) return false
        return source[pos - 1].isLetterOrDigit() && source[pos + 1].isLetter()
    }

    private fun containsQuoteOrQuoteEscape(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val ch = text[i]
            if (ch == '\'') return true
            if (ch == '\\' && i + 1 < end) {
                val next = text[i + 1]
                if (next == '"' || (next == 'u' && i + 5 < end)) return true
                i += 2
            } else {
                i++
            }
        }
        return false
    }
}
