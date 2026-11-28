package net.alummahnow.core.bridge.telemetry

@Suppress("TooManyFunctions")
internal object CoreTelemetryQuotedFilenameRedactor {
    fun replaceBareFilenames(text: String, regex: Regex): String {
        if (text.indexOf('"') < 0) return regex.replace(text, "$1$2[redacted-path]")
        var spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return regex.replace(text, "$1$2[redacted-path]")
        val masked = CoreTelemetryQuotedPathRedactor.buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = applyMaskedBareFilenameRegex(text, masked, regex)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return replaceBareFilenamesInSpans(afterUnquoted, spans, regex)
    }

    private fun applyMaskedBareFilenameRegex(text: String, masked: String, regex: Regex): String {
        var match = regex.find(masked) ?: return text
        val sb = StringBuilder(text.length)
        var copied = 0
        while (true) {
            val g2 = match.groups[2]
            val prefixEnd = if (g2 != null) g2.range.last + 1 else match.range.first
            val end = match.range.last + 1
            sb.append(text, copied, prefixEnd).append("[redacted-path]")
            copied = end
            match = match.next() ?: break
        }
        return sb.append(text, copied, text.length).toString()
    }

    private fun replaceBareFilenamesInSpans(
        current: String,
        spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>,
        regex: Regex
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsDotOrUnicodeEscape(current, innerStart, span.close)) {
                val (nextSb, nextCopied) = replaceBareInSpan(current, span, regex, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    private fun replaceBareInSpan(
        current: String,
        span: CoreTelemetryQuotedPathRedactor.QuotedPathSpan,
        regex: Regex,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val innerStart = span.open + 1
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(current, innerStart, span.close)
        if (decoded.indexOf('.') < 0) return initialSb to initialCopied
        val followedByAt = span.close + 1 < current.length &&
            CoreTelemetryEmailScanner.emailAtLength(current, span.close + 1) > 0
        val wholeFilename = !hasDecodedPathOrQuoteSep(decoded) &&
            !followedByAt &&
            CoreTelemetryScanners.endsWithFilenameExt(decoded, 0, decoded.length)
        if (wholeFilename) {
            val out = initialSb ?: StringBuilder(current.length)
            out.append(current, initialCopied, innerStart).append("[redacted-path]")
            return out to span.close
        }
        return applyBareMatchesInDecoded(current, decoded, rawOffsets, regex, initialSb, initialCopied)
    }

    @Suppress("LongParameterList")
    private fun applyBareMatchesInDecoded(
        current: String,
        decoded: String,
        rawOffsets: IntArray,
        regex: Regex,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        var match = regex.find(decoded) ?: return initialSb to initialCopied
        var sb = initialSb
        var copied = initialCopied
        while (true) {
            val g2 = match.groups[2]
            val rawPrefixEnd = rawOffsets[if (g2 != null) g2.range.last + 1 else match.range.first]
            val rawEnd = rawOffsets[match.range.last + 1]
            val out = sb ?: StringBuilder(current.length).also { sb = it }
            out.append(current, copied, rawPrefixEnd).append("[redacted-path]")
            copied = rawEnd
            match = match.next() ?: break
        }
        return sb to copied
    }

    fun redactQuotedFilenames(text: String): String {
        if (text.indexOf('.') < 0 && !text.contains("\\u")) return text
        val afterDouble = if (text.indexOf('"') >= 0) redactDoubleQuotedFilenamesRespectingQuotes(text) else text
        return if (afterDouble.indexOf('\'') >= 0 || afterDouble.contains("\\u")) {
            redactSingleQuotedFilenamesRespectingQuotes(afterDouble)
        } else {
            afterDouble
        }
    }

    private fun redactDoubleQuotedFilenamesRespectingQuotes(text: String): String {
        var spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return redactDoubleQuotedOnSource(text, text)
        val masked = CoreTelemetryQuotedPathRedactor.buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = redactDoubleQuotedOnSource(text, masked)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return redactDoubleQuotedInSpans(afterUnquoted, spans)
    }

    private fun redactDoubleQuotedOnSource(text: String, scanSource: String): String {
        if (scanSource.indexOf('.') < 0) return text
        var sb: StringBuilder? = null
        var copied = 0
        var i = 0
        while (i < scanSource.length) {
            if (scanSource[i] != '"') {
                i++
            } else {
                val close = scanDoubleQuoteEnd(scanSource, i + 1)
                val followedByAt = close + 1 < scanSource.length &&
                    CoreTelemetryEmailScanner.emailAtLength(scanSource, close + 1) > 0
                val matched = close < scanSource.length &&
                    scanSource[close] == '"' &&
                    !followedByAt &&
                    CoreTelemetryScanners.endsWithFilenameExt(scanSource, i + 1, close)
                if (matched) {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copied, i).append("\"[redacted-path]\"")
                    copied = close + 1
                    i = close + 1
                } else {
                    i = if (close < scanSource.length && scanSource[close] == '"') close else close + 1
                }
            }
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun redactDoubleQuotedInSpans(
        current: String,
        spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsDotOrUnicodeEscape(current, innerStart, span.close)) {
                val (nextSb, nextCopied) = redactDoubleInSpan(current, span, sb, copied)
                sb = nextSb
                copied = nextCopied
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    private fun redactDoubleInSpan(
        current: String,
        span: CoreTelemetryQuotedPathRedactor.QuotedPathSpan,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val innerStart = span.open + 1
        val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(current, innerStart, span.close)
        if (decoded.indexOf('.') < 0) return initialSb to initialCopied
        val followedByAt = span.close + 1 < current.length &&
            CoreTelemetryEmailScanner.emailAtLength(current, span.close + 1) > 0
        val wholeFilename = !hasDecodedSegStop(decoded) &&
            !followedByAt &&
            CoreTelemetryScanners.endsWithFilenameExt(decoded, 0, decoded.length)
        if (wholeFilename) {
            val out = initialSb ?: StringBuilder(current.length)
            out.append(current, initialCopied, innerStart).append("[redacted-path]")
            return out to span.close
        }
        return if (decoded.indexOf('"') >= 0) {
            applyDoubleQuotedDecoded(current, decoded, rawOffsets, initialSb, initialCopied)
        } else {
            initialSb to initialCopied
        }
    }

    private fun applyDoubleQuotedDecoded(
        current: String,
        decoded: String,
        rawOffsets: IntArray,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        var sb = initialSb
        var copied = initialCopied
        var i = 0
        while (i < decoded.length) {
            if (decoded[i] != '"') {
                i++
            } else {
                val close = scanDoubleQuoteEnd(decoded, i + 1)
                val followedByAt = close + 1 < decoded.length &&
                    CoreTelemetryEmailScanner.emailAtLength(decoded, close + 1) > 0
                val matched = close < decoded.length &&
                    decoded[close] == '"' &&
                    !followedByAt &&
                    CoreTelemetryScanners.endsWithFilenameExt(decoded, i + 1, close)
                if (matched) {
                    val out = sb ?: StringBuilder(current.length).also { sb = it }
                    out.append(current, copied, rawOffsets[i + 1]).append("[redacted-path]")
                    copied = rawOffsets[close]
                    i = close + 1
                } else {
                    i = if (close < decoded.length && decoded[close] == '"') close else close + 1
                }
            }
        }
        return sb to copied
    }

    private fun scanDoubleQuoteEnd(text: String, start: Int): Int {
        var j = start
        while (j < text.length && text[j] != '"' && !CoreTelemetryScanners.isQuotedSegStop(text[j])) j++
        return j
    }

    fun redactSingleQuotedFilenamesRespectingQuotes(text: String): String {
        if (text.indexOf('"') < 0) return redactSingleQuotedFilenamesOnSource(text, text)
        var spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) return redactSingleQuotedFilenamesOnSource(text, text)
        val masked = CoreTelemetryQuotedPathRedactor.buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = redactSingleQuotedFilenamesOnSource(text, masked)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return redactSingleQuotedInSpans(afterUnquoted, spans)
    }

    private fun redactSingleQuotedInSpans(
        current: String,
        spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            val hasCandidate = innerStart < span.close &&
                containsSingleQuoteOrUnicodeEscape(current, innerStart, span.close) &&
                containsDotOrUnicodeEscape(current, innerStart, span.close)
            if (hasCandidate) {
                val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(
                    current,
                    innerStart,
                    span.close
                )
                if (decoded.indexOf('\'') >= 0 && decoded.indexOf('.') >= 0) {
                    val (nextSb, nextCopied) = applySingleQuotedDecoded(current, decoded, rawOffsets, sb, copied)
                    sb = nextSb
                    copied = nextCopied
                }
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    private fun redactSingleQuotedFilenamesOnSource(text: String, scanSource: String): String {
        val hasAt = CoreTelemetryEmailScanner.hasEmailAt(scanSource)
        var sb: StringBuilder? = null
        var copied = 0
        var segStart = 0
        while (segStart < scanSource.length) {
            var segEnd = segStart
            while (segEnd < scanSource.length && !CoreTelemetryScanners.isQuotedSegStop(scanSource[segEnd])) segEnd++
            copied = redactSingleQuotesInSegment(scanSource, segStart, segEnd, copied, hasAt) { open, endPos ->
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, open).append("[redacted-path]")
                copied = endPos
            }
            segStart = maxOf(segEnd + 1, copied)
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun applySingleQuotedDecoded(
        text: String,
        decoded: String,
        rawOffsets: IntArray,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val hasAt = CoreTelemetryEmailScanner.hasEmailAt(decoded)
        var sb = initialSb
        var copied = initialCopied
        var decCopied = 0
        var segStart = 0
        while (segStart < decoded.length) {
            var segEnd = segStart
            while (segEnd < decoded.length && !CoreTelemetryScanners.isQuotedSegStop(decoded[segEnd])) segEnd++
            decCopied = redactSingleQuotesInSegment(decoded, segStart, segEnd, decCopied, hasAt) { open, endPos ->
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, rawOffsets[open]).append("[redacted-path]")
                copied = rawOffsets[endPos]
            }
            segStart = maxOf(segEnd + 1, decCopied)
        }
        return sb to copied
    }

    @Suppress("LongParameterList")
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
                if (CoreTelemetryScanners.endsWithFilenameExt(text, open + 1, pos)) {
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

    fun redactKeywordUnquotedFilenamesRespectingQuotes(
        text: String,
        kwRegex: Regex,
        findLastExt: (String, Int, Int) -> Pair<Int, Int>?
    ): String {
        if (text.indexOf('"') < 0) {
            return redactKeywordUnquotedOnSource(text, text, kwRegex, findLastExt)
        }
        var spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
        if (spans.isEmpty()) {
            return redactKeywordUnquotedOnSource(text, text, kwRegex, findLastExt)
        }
        val masked = CoreTelemetryQuotedPathRedactor.buildMaskedDoubleQuotes(text, spans)
        val afterUnquoted = redactKeywordUnquotedOnSource(text, masked, kwRegex, findLastExt, stopAtDoubleQuote = true)
        if (afterUnquoted !== text) {
            if (afterUnquoted.indexOf('"') < 0) return afterUnquoted
            spans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(afterUnquoted)
        }
        return redactKeywordUnquotedInSpans(afterUnquoted, spans, kwRegex, findLastExt)
    }

    private fun redactKeywordUnquotedOnSource(
        text: String,
        scanSource: String,
        kwRegex: Regex,
        findLastExt: (String, Int, Int) -> Pair<Int, Int>?,
        stopAtDoubleQuote: Boolean = false
    ): String {
        if (scanSource.indexOf('.') < 0) return text
        val hasAt = CoreTelemetryEmailScanner.hasEmailAt(scanSource)
        var sb: StringBuilder? = null
        var copied = 0
        var segStart = 0
        while (segStart < scanSource.length) {
            var segEnd = segStart
            while (segEnd < scanSource.length && !isKeywordSegStop(scanSource[segEnd], stopAtDoubleQuote)) segEnd++
            val bounds = findKeywordUnquotedMatch(scanSource, segStart, segEnd, hasAt, kwRegex, findLastExt)
            if (bounds != null) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, bounds.first).append("[redacted-path]")
                copied = bounds.second
            }
            segStart = maxOf(segEnd + 1, copied)
        }
        val out = sb ?: return text
        return out.append(text, copied, text.length).toString()
    }

    private fun isKeywordSegStop(ch: Char, stopAtDoubleQuote: Boolean): Boolean =
        ch in "\r\n\u001E\u001F" || (stopAtDoubleQuote && ch == '"')

    private fun redactKeywordUnquotedInSpans(
        current: String,
        spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>,
        kwRegex: Regex,
        findLastExt: (String, Int, Int) -> Pair<Int, Int>?
    ): String {
        var sb: StringBuilder? = null
        var copied = 0
        for (span in spans) {
            val innerStart = span.open + 1
            if (innerStart < span.close && containsDotOrUnicodeEscape(current, innerStart, span.close)) {
                val (decoded, rawOffsets) = CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(
                    current,
                    innerStart,
                    span.close
                )
                if (decoded.indexOf('.') >= 0) {
                    val (nextSb, nextCopied) = applyKeywordUnquotedDecoded(
                        current,
                        decoded,
                        rawOffsets,
                        kwRegex,
                        findLastExt,
                        sb,
                        copied
                    )
                    sb = nextSb
                    copied = nextCopied
                }
            }
        }
        val out = sb ?: return current
        return out.append(current, copied, current.length).toString()
    }

    @Suppress("LongParameterList")
    private fun applyKeywordUnquotedDecoded(
        current: String,
        decoded: String,
        rawOffsets: IntArray,
        kwRegex: Regex,
        findLastExt: (String, Int, Int) -> Pair<Int, Int>?,
        initialSb: StringBuilder?,
        initialCopied: Int
    ): Pair<StringBuilder?, Int> {
        val hasAt = CoreTelemetryEmailScanner.hasEmailAt(decoded)
        var sb = initialSb
        var copied = initialCopied
        var decCopied = 0
        var segStart = 0
        while (segStart < decoded.length) {
            var segEnd = segStart
            while (segEnd < decoded.length && decoded[segEnd] !in "\r\n\u001E\u001F") segEnd++
            val bounds = findKeywordUnquotedMatch(decoded, segStart, segEnd, hasAt, kwRegex, findLastExt)
            if (bounds != null) {
                val out = sb ?: StringBuilder(current.length).also { sb = it }
                out.append(current, copied, rawOffsets[bounds.first]).append("[redacted-path]")
                copied = rawOffsets[bounds.second]
                decCopied = bounds.second
            }
            segStart = maxOf(segEnd + 1, decCopied)
        }
        return sb to copied
    }

    @Suppress("LongParameterList")
    private fun findKeywordUnquotedMatch(
        source: String,
        segStart: Int,
        segEnd: Int,
        hasAt: Boolean,
        kwRegex: Regex,
        findLastExt: (String, Int, Int) -> Pair<Int, Int>?
    ): Pair<Int, Int>? {
        val extBounds = findLastExt(source, segStart, segEnd) ?: return null
        val kwMatch = kwRegex.find(source.substring(segStart, extBounds.first)) ?: return null
        val kwEnd = segStart + kwMatch.range.last + 1
        val emailEnd = if (hasAt) CoreTelemetryEmailScanner.findEmailTailEnd(source, extBounds.second) else -1
        val isSingleEmail = emailEnd > 0 && !CoreTelemetryEmailScanner.containsWhitespace(source, kwEnd, extBounds.first)
        return if (isSingleEmail) null else kwEnd to maxOf(extBounds.second, emailEnd)
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

    private fun hasDecodedSegStop(decoded: String): Boolean {
        for (i in decoded.indices) {
            if (CoreTelemetryScanners.isQuotedSegStop(decoded[i])) return true
        }
        return false
    }

    private fun hasDecodedPathOrQuoteSep(decoded: String): Boolean {
        for (i in decoded.indices) {
            val ch = decoded[i]
            if (ch == '"' || CoreTelemetryScanners.isQuotedSegStop(ch)) return true
        }
        return false
    }

    private fun containsDotOrUnicodeEscape(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val ch = text[i]
            if (ch == '.') return true
            if (ch == '\\' && i + 1 < end) {
                if (text[i + 1] == 'u' && i + 5 < end) return true
                i += 2
            } else {
                i++
            }
        }
        return false
    }

    private fun containsSingleQuoteOrUnicodeEscape(text: String, start: Int, end: Int): Boolean {
        var i = start
        while (i < end) {
            val ch = text[i]
            if (ch == '\'') return true
            if (ch == '\\' && i + 1 < end) {
                if (text[i + 1] == 'u' && i + 5 < end) return true
                i += 2
            } else {
                i++
            }
        }
        return false
    }
}
