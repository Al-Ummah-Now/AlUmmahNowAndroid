package net.alummahnow.core.bridge.telemetry

/**
 * URL continuation, single-quote tracking, and query/fragment delimiter helpers for
 * [CoreTelemetryService.sanitizeLocalPaths].
 */
internal object CoreTelemetryUrlContinuation {
    internal class UrlContinuationResult(
        val replaceBase: Boolean,
        val baseUrl: String,
        val keepBaseTail: Boolean,
        val middle: String,
        val qfToken: String,
        val tail: String,
        val endPos: Int
    )

    internal class ContinuationInput(
        val span: CoreTelemetryScanners.UrlSpan,
        val rawUrl: String,
        val baseTail: String,
        val quoteState: Int
    ) {
        val inSingleQuote: Boolean get() = (quoteState and 1) != 0
        val inDoubleQuote: Boolean get() = (quoteState and 2) != 0
    }

    private class ContinuationContext(
        val input: ContinuationInput,
        val webhookProbe: String?,
        val quoteFlags: Int,
        val pathEnd: Int
    )

    @Suppress("LongParameterList")
    fun emitUrlContinuation(
        out: StringBuilder,
        prefix: String,
        vaultedUrls: MutableList<String>,
        baseRedacted: String,
        baseTail: String,
        ext: UrlContinuationResult,
        vaultInner: (String) -> String
    ) {
        if (ext.replaceBase) {
            val idx = vaultedUrls.size
            vaultedUrls += ext.baseUrl
            out.append(prefix).append(idx).append('\u001F').append(ext.tail)
            return
        }
        val baseIdx = vaultedUrls.size
        vaultedUrls += baseRedacted
        out.append(prefix).append(baseIdx).append('\u001F')
        if (ext.keepBaseTail) out.append(baseTail)
        out.append(if (ext.middle.indexOf("://") >= 0) vaultInner(ext.middle) else ext.middle)
        if (ext.qfToken.isNotEmpty()) {
            val qfIdx = vaultedUrls.size
            vaultedUrls += ext.qfToken
            out.append(prefix).append(qfIdx).append('\u001F')
        }
        out.append(ext.tail)
    }

    fun extendUrlContinuation(
        text: String,
        input: ContinuationInput,
        contLimits: IntArray
    ): UrlContinuationResult? {
        val span = input.span
        if (span.hasQf || span.totalEnd >= text.length) return null
        val outerQuote = resolveOuterQuote(text, span.start, input.inDoubleQuote)
        if (!isContinuationStarter(text, span.totalEnd, outerQuote == '"')) return null
        val webhookProbe = probeWebhookPrefix(input.rawUrl)
        if (webhookProbe == null && span.totalEnd < contLimits[0]) return null
        val initial = CoreTelemetryScanners.ContScanState(
            span.totalEnd >= contLimits[1] && outerQuote != '"',
            span.totalEnd >= contLimits[1] && outerQuote == '"',
            span.totalEnd >= contLimits[2],
            outerQuote,
            webhookProbe == null
        )
        val (scanState, pathEnd) = scanWithUnclosedFallback(text, span.totalEnd, webhookProbe, initial, contLimits)
        val quoteFlags = buildQuoteFlags(scanState, input.inSingleQuote)
        val ctx = ContinuationContext(input, webhookProbe, quoteFlags, pathEnd)
        val result = extendUrlContinuationQuery(text, ctx) ?: extendUrlContinuationSecret(text, ctx)
        if (result == null) recordFailedContinuation(scanState, pathEnd, contLimits)
        return result
    }

    private fun isContinuationStarter(text: String, pos: Int, inDoubleQuote: Boolean): Boolean {
        val ch = text[pos]
        if (ch in ",([]){}") return true
        return inDoubleQuote && ch == '\\' && pos + 1 < text.length && text[pos + 1] in "\"\\/"
    }

    private fun resolveOuterQuote(text: String, spanStart: Int, inDoubleQuote: Boolean): Char = when {
        inDoubleQuote || (spanStart > 0 && text[spanStart - 1] == '"') -> '"'
        spanStart > 0 && text[spanStart - 1] == '\'' -> '\''
        else -> '\u0000'
    }

    private fun buildQuoteFlags(state: CoreTelemetryScanners.ContScanState, inSingleQuote: Boolean): Int {
        var flags = 0
        if (state.allowDoubleQuotes && state.quoteChar != '"') flags = flags or 1
        if (state.allowSingleQuotes && state.quoteChar != '\'') flags = flags or 2
        if (inSingleQuote) flags = flags or 4
        if (state.allowEscapedDoubleQuotes && state.quoteChar != '\u0001') flags = flags or 8
        if (state.outerQuote == '"') flags = flags or 16
        return flags
    }

    private fun scanWithUnclosedFallback(
        text: String,
        start: Int,
        webhookProbe: String?,
        state: CoreTelemetryScanners.ContScanState,
        contLimits: IntArray
    ): Pair<CoreTelemetryScanners.ContScanState, Int> {
        val firstEnd = CoreTelemetryScanners.scanUrlContinuation(text, start, state)
        val hitsQf = firstEnd > start && firstEnd < text.length && (text[firstEnd] == '?' || text[firstEnd] == '#')
        if (state.quoteChar == '\u0000' || webhookProbe != null || hitsQf) return state to firstEnd
        recordUnclosedQuote(state.quoteChar, firstEnd, contLimits)
        val nextState = CoreTelemetryScanners.ContScanState(
            if (state.quoteChar == '"') false else state.allowDoubleQuotes,
            if (state.quoteChar == '\u0001') false else state.allowEscapedDoubleQuotes,
            if (state.quoteChar == '\'') false else state.allowSingleQuotes,
            state.outerQuote,
            true
        )
        return nextState to CoreTelemetryScanners.scanUrlContinuation(text, start, nextState)
    }

    private fun probeWebhookPrefix(rawUrl: String): String? {
        val probeInput = "${rawUrl}x"
        val probe = CoreTelemetryScanners.redactUrlPathSecrets(probeInput)
        return if (probe != probeInput && probe.endsWith("[redacted]")) probe else null
    }

    private fun recordUnclosedQuote(quoteChar: Char, pathEnd: Int, contLimits: IntArray) {
        if ((quoteChar == '"' || quoteChar == '\u0001') && pathEnd > contLimits[1]) contLimits[1] = pathEnd
        if (quoteChar == '\'' && pathEnd > contLimits[2]) contLimits[2] = pathEnd
    }

    private fun recordFailedContinuation(
        state: CoreTelemetryScanners.ContScanState,
        pathEnd: Int,
        contLimits: IntArray
    ) {
        recordUnclosedQuote(state.quoteChar, pathEnd, contLimits)
        if (!state.sawInnerQf && pathEnd > contLimits[0]) contLimits[0] = pathEnd
    }

    private fun extendUrlContinuationQuery(text: String, ctx: ContinuationContext): UrlContinuationResult? {
        val span = ctx.input.span
        val hasNext = ctx.pathEnd > span.totalEnd && ctx.pathEnd < text.length
        if (!hasNext || (text[ctx.pathEnd] != '?' && text[ctx.pathEnd] != '#')) return null
        val angleMask = CoreTelemetryScanners.computeQueryAngleStopMask(text, span.start, ctx.input.inDoubleQuote)
        val qfEnd = CoreTelemetryScanners.scanQueryOrFragment(text, ctx.pathEnd, angleMask)
        val (cleanQf, qfTail) = trimQueryOrFragmentDelimiters(
            text.substring(ctx.pathEnd, qfEnd),
            text,
            span.start,
            ctx.input.inSingleQuote
        )
        val qfToken = "${cleanQf.first()}[redacted]"
        if (ctx.webhookProbe != null) {
            return UrlContinuationResult(true, "${ctx.webhookProbe}$qfToken", false, "", "", qfTail, qfEnd)
        }
        val baseRedacted = CoreTelemetryScanners.redactUrlPathSecrets(ctx.input.rawUrl)
        val prefixRedacted = redactOuterContinuationPrefix(text, span, ctx.pathEnd)
        val swallowed = prefixRedacted == baseRedacted || prefixRedacted == "$baseRedacted[redacted]"
        return if (swallowed) {
            UrlContinuationResult(true, "$prefixRedacted$qfToken", false, "", "", qfTail, qfEnd)
        } else {
            val middle = extractContinuationMiddle(prefixRedacted, baseRedacted, text, span.totalEnd, ctx.pathEnd)
            val keepTail = ctx.input.baseTail.isNotEmpty() && baseRedacted.endsWith(ctx.input.baseTail)
            UrlContinuationResult(false, "", keepTail, middle, qfToken, qfTail, qfEnd)
        }
    }

    private fun extendUrlContinuationSecret(text: String, ctx: ContinuationContext): UrlContinuationResult? {
        val span = ctx.input.span
        val end = CoreTelemetryScanners.trimWebhookContinuationEnd(
            text,
            span.start,
            span.totalEnd,
            ctx.pathEnd,
            ctx.quoteFlags
        )
        if (end <= span.totalEnd) return null
        val extTail = text.substring(end, ctx.pathEnd)
        if (ctx.webhookProbe != null) {
            return UrlContinuationResult(true, ctx.webhookProbe, false, "", "", extTail, ctx.pathEnd)
        }
        val baseRedacted = CoreTelemetryScanners.redactUrlPathSecrets(ctx.input.rawUrl)
        val extRedacted = redactOuterContinuationPrefix(text, span, end)
        if (extRedacted == "$baseRedacted${text.substring(span.totalEnd, end)}") return null
        val swallowed = extRedacted == baseRedacted || extRedacted == "$baseRedacted[redacted]"
        return if (swallowed) {
            UrlContinuationResult(true, extRedacted, false, "", "", extTail, ctx.pathEnd)
        } else {
            val middle = extractContinuationMiddle(extRedacted, baseRedacted, text, span.totalEnd, end)
            val keepTail = ctx.input.baseTail.isNotEmpty() && baseRedacted.endsWith(ctx.input.baseTail)
            UrlContinuationResult(false, "", keepTail, middle, "", extTail, ctx.pathEnd)
        }
    }

    private fun redactOuterContinuationPrefix(
        text: String,
        span: CoreTelemetryScanners.UrlSpan,
        sliceEnd: Int
    ): String {
        val nestedStart = findFirstNestedUrlStart(text, span.totalEnd, sliceEnd)
        if (nestedStart < 0) return CoreTelemetryScanners.redactUrlPathSecrets(text.substring(span.start, sliceEnd))
        val prefix = text.substring(span.start, nestedStart)
        val probe = CoreTelemetryScanners.redactUrlPathSecrets("${prefix}x")
        if (probe != "${prefix}x" && probe.endsWith("[redacted]")) {
            return CoreTelemetryScanners.redactUrlPathSecrets(text.substring(span.start, sliceEnd))
        }
        return CoreTelemetryScanners.redactUrlPathSecrets(prefix) + text.substring(nestedStart, sliceEnd)
    }

    private fun findFirstNestedUrlStart(text: String, from: Int, to: Int): Int {
        var search = from
        while (search < to) {
            val sep = text.indexOf("://", search)
            if (sep < 0 || sep >= to) return -1
            val start = CoreTelemetryScanners.findNonFileSchemeStart(text, sep, from)
            if (start != null) return start
            search = sep + 3
        }
        return -1
    }

    private fun extractContinuationMiddle(
        redacted: String,
        baseRedacted: String,
        text: String,
        start: Int,
        end: Int
    ): String = if (redacted.startsWith(baseRedacted)) {
        redacted.substring(baseRedacted.length)
    } else {
        text.substring(start, end)
    }

    fun advanceQuoteState(text: String, from: Int, to: Int, quoteState: Int): Int {
        var state = quoteState
        var i = from
        while (i < to) {
            val ch = text[i]
            val hasEsc = ch == '\\' && i + 1 < to && text[i + 1] !in "\r\n"
            if (hasEsc && state != 0) {
                i += 2
            } else {
                state = stepQuoteState(text, i, state)
                i++
            }
        }
        return state
    }

    private fun stepQuoteState(text: String, i: Int, state: Int): Int {
        val ch = text[i]
        return when {
            ch == '\r' || ch == '\n' -> 0
            ch == '"' && (state and 1) == 0 -> state xor 2
            ch == '\'' && (state and 2) == 0 && !isContractionApostrophe(text, i) -> state xor 1
            else -> state
        }
    }

    fun isContractionApostrophe(text: String, pos: Int): Boolean =
        pos > 0 && pos + 1 < text.length && text[pos - 1].isLetterOrDigit() && text[pos + 1].isLetterOrDigit()

    fun formatVaultedUrl(
        span: CoreTelemetryScanners.UrlSpan,
        text: String,
        inSingleQuote: Boolean
    ): Pair<String, String> {
        val baseUrl = text.substring(span.start, span.baseEnd)
        if (span.hasQf) {
            val qf = text.substring(span.baseEnd, span.totalEnd)
            val (cleanQf, tail) = trimQueryOrFragmentDelimiters(qf, text, span.start, inSingleQuote)
            return "$baseUrl${cleanQf.first()}[redacted]" to tail
        }
        var cleanBase = baseUrl
        var tail = ""
        if (cleanBase.endsWith(';')) {
            cleanBase = cleanBase.dropLast(1)
            tail = ";"
        }
        val quoted = inSingleQuote || (span.start > 0 && text[span.start - 1] == '\'')
        if (quoted && cleanBase.endsWith('\'')) {
            cleanBase = cleanBase.dropLast(1)
            tail = "'$tail"
        }
        return cleanBase to tail
    }

    private fun trimQueryOrFragmentDelimiters(
        qf: String,
        text: String,
        matchStart: Int,
        inSingleQuote: Boolean = false
    ): Pair<String, String> {
        val excess = countExcessClosers(qf)
        var end = qf.length
        val precededByQuote = inSingleQuote || (matchStart > 0 && text[matchStart - 1] == '\'')
        while (end > 1 && shouldTrimCloser(qf[end - 1], precededByQuote, excess)) {
            end--
        }
        return qf.substring(0, end) to qf.substring(end)
    }

    private fun countExcessClosers(qf: String): IntArray {
        val excess = IntArray(3)
        for (ch in qf) {
            when (ch) {
                '(' -> excess[0]--
                ')' -> excess[0]++
                '[' -> excess[1]--
                ']' -> excess[1]++
                '{' -> excess[2]--
                '}' -> excess[2]++
            }
        }
        return excess
    }

    private fun shouldTrimCloser(ch: Char, precededByQuote: Boolean, excess: IntArray): Boolean {
        val slot = when (ch) {
            ')' -> 0
            ']' -> 1
            '}' -> 2
            else -> -1
        }
        if (slot >= 0) {
            if (excess[slot] <= 0) return false
            excess[slot]--
            return true
        }
        return ch == '.' || ch == ';' || ch == ',' || (ch == '\'' && precededByQuote)
    }

    fun isAngleWrappedBefore(text: String, urlStart: Int): Boolean {
        if (urlStart > 0 && text[urlStart - 1] == '<') return true
        if (urlStart < 6) return false
        val matchesEsc = text[urlStart - 6] == '\\' &&
            text[urlStart - 5] == 'u' &&
            text[urlStart - 4] == '0' &&
            text[urlStart - 3] == '0' &&
            text[urlStart - 2] == '3' &&
            (text[urlStart - 1] == 'c' || text[urlStart - 1] == 'C')
        return matchesEsc && !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, urlStart - 6, 0)
    }

    fun isEscapedGreaterAt(text: String, pos: Int): Boolean {
        if (pos + 5 >= text.length) return false
        val matchesEsc = text[pos] == '\\' &&
            text[pos + 1] == 'u' &&
            text[pos + 2] == '0' &&
            text[pos + 3] == '0' &&
            text[pos + 4] == '3' &&
            (text[pos + 5] == 'e' || text[pos + 5] == 'E')
        return matchesEsc && !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos, 0)
    }

    fun stepJsonEscapeBeforeScheme(text: String, start: Int, sep: Int, minStart: Int): Pair<Int, Boolean> {
        var cur = start
        var stepped = false
        while (cur < sep && cur > minStart && isUnescapedBackslashBefore(text, cur, minStart)) {
            val next = stepSingleSchemePrefixEscape(text, cur, sep)
            if (next > cur) {
                cur = next
                stepped = true
            } else {
                break
            }
        }
        return cur to stepped
    }

    private fun isUnescapedBackslashBefore(text: String, pos: Int, minStart: Int): Boolean =
        text[pos - 1] == '\\' && !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos - 1, minStart)

    private fun stepSingleSchemePrefixEscape(text: String, cur: Int, sep: Int): Int {
        val esc = text[cur]
        if (esc in "nrtbf") return cur + 1
        if (esc == 'u' && cur + 5 <= sep) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, cur + 1)
            if (code >= 0) {
                val decoded = code.toChar()
                if (!CoreTelemetryScanners.isUrlSchemeChar(decoded) && decoded != '/' && decoded != '\\') {
                    return cur + 5
                }
            }
        }
        return cur
    }

    fun resolveQuotedSecretUrlTail(
        text: String,
        span: CoreTelemetryScanners.UrlSpan,
        inDoubleQuote: Boolean,
        rawUrl: String,
        redactedUrl: String
    ): Pair<String, Int>? {
        val wrappedInAngle = isAngleWrappedBefore(text, span.start)
        val inContext = inDoubleQuote || wrappedInAngle
        if (span.hasQf || !inContext || span.totalEnd >= text.length) return null
        if (isQuotedUrlTailBoundary(text, span.totalEnd, inDoubleQuote, wrappedInAngle)) return null
        val secretUrl = resolveSecretUrlPrefix(rawUrl, redactedUrl) ?: return null
        val (endPos, stoppedAtAngle) = scanQuotedUrlTailEnd(text, span.totalEnd, inDoubleQuote, wrappedInAngle)
        return if (inDoubleQuote || stoppedAtAngle) secretUrl to endPos else null
    }

    private fun isQuotedUrlTailBoundary(text: String, pos: Int, inDoubleQuote: Boolean, wrappedInAngle: Boolean): Boolean {
        val ch = text[pos]
        if (ch == '\r' || ch == '\n') return true
        if (inDoubleQuote && ch == '"') return true
        return wrappedInAngle && (ch == '>' || isEscapedGreaterAt(text, pos))
    }

    private fun resolveSecretUrlPrefix(rawUrl: String, redactedUrl: String): String? {
        if (redactedUrl != rawUrl && redactedUrl.endsWith("[redacted]")) return redactedUrl
        return probeWebhookPrefix(rawUrl)
    }

    private fun scanQuotedUrlTailEnd(
        text: String,
        start: Int,
        inDoubleQuote: Boolean,
        wrappedInAngle: Boolean
    ): Pair<Int, Boolean> {
        var p = start
        var escaped = false
        var stoppedAtAngle = false
        var done = false
        while (p < text.length && !done) {
            val ch = text[p]
            val atAngle = isAngleCloseAt(text, p, wrappedInAngle, escaped)
            val atEnd = isQuoteOrNewlineStop(ch, inDoubleQuote, escaped)
            if (atEnd || atAngle) {
                stoppedAtAngle = atAngle
                done = true
            } else {
                escaped = inDoubleQuote && ch == '\\' && !escaped
                p++
            }
        }
        return p to stoppedAtAngle
    }

    private fun isAngleCloseAt(text: String, pos: Int, wrappedInAngle: Boolean, escaped: Boolean): Boolean {
        if (!wrappedInAngle || escaped) return false
        return text[pos] == '>' || isEscapedGreaterAt(text, pos)
    }

    private fun isQuoteOrNewlineStop(ch: Char, inDoubleQuote: Boolean, escaped: Boolean): Boolean {
        if (ch == '\r' || ch == '\n') return true
        return inDoubleQuote && ch == '"' && !escaped
    }

    fun extendSpacedQuotedUrlQuery(
        text: String,
        input: ContinuationInput,
        contLimits: IntArray
    ): UrlContinuationResult? {
        val span = input.span
        val inDoubleQuote = input.inDoubleQuote
        val wrappedInAngle = isAngleWrappedBefore(text, span.start)
        val eligible = !span.hasQf &&
            (inDoubleQuote || wrappedInAngle) &&
            span.totalEnd < text.length &&
            span.totalEnd >= contLimits[3] &&
            !isQuotedUrlTailBoundary(text, span.totalEnd, inDoubleQuote, wrappedInAngle)
        if (!eligible) return null
        var p = span.totalEnd
        while (p < text.length) {
            val next = stepSpacedQuotedUrlPos(text, p, span, inDoubleQuote, wrappedInAngle)
            if (next == -1) break
            if (next == -2) return buildSpacedUrlQueryResult(text, input, p)
            p = next
        }
        if (p > contLimits[3]) contLimits[3] = p
        return null
    }

    private fun stepSpacedQuotedUrlPos(
        text: String,
        p: Int,
        span: CoreTelemetryScanners.UrlSpan,
        inDoubleQuote: Boolean,
        wrappedInAngle: Boolean
    ): Int {
        val ch = text[p]
        val stops = isQuotedUrlTailBoundary(text, p, inDoubleQuote, wrappedInAngle) ||
            CoreTelemetryScanners.isFileSchemeAt(text, p) ||
            isDriveBoundary(text, p) ||
            (p > span.totalEnd && isSpacedUrlSchemeStart(text, p))
        if (stops) return -1
        if (inDoubleQuote && ch == '\\' && p + 1 < text.length) {
            return stepSpacedQuotedEscape(text, p, wrappedInAngle)
        }
        val isValidQf = (ch == '?' || ch == '#') &&
            isValidSpacedUrlQueryStart(text, p, span.start, span.totalEnd, inDoubleQuote, wrappedInAngle)
        return if (isValidQf) -2 else p + 1
    }

    private fun isDriveBoundary(text: String, p: Int): Boolean =
        (p == 0 || !CoreTelemetryScanners.isAsciiAlphaNum(text[p - 1])) &&
            CoreTelemetryScanners.isWindowsDriveAt(text, p)

    private fun stepSpacedQuotedEscape(text: String, p: Int, wrappedInAngle: Boolean): Int {
        val esc = text[p + 1]
        if (esc == 'r' || esc == 'n') return -1
        if (esc == 'u' && p + 5 < text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, p + 2)
            if (code >= 0) {
                val ch = code.toChar()
                val isNl = ch == '\r' || ch == '\n'
                val stopEscape = isNl || (wrappedInAngle && ch == '>')
                return if (stopEscape) -1 else p + 6
            }
        }
        return p + 2
    }

    private fun isSpacedUrlSchemeStart(text: String, pos: Int): Boolean {
        val prev = text[pos - 1]
        if (CoreTelemetryScanners.isUrlSchemeChar(prev) || prev == '/' || prev == '\\') return false
        if (!CoreTelemetryScanners.isAsciiLetter(text[pos])) return false
        var i = pos + 1
        while (i < text.length && CoreTelemetryScanners.isUrlSchemeChar(text[i])) i++
        return i - pos >= 2 && text.startsWith("://", i)
    }

    @Suppress("LongParameterList")
    private fun isValidSpacedUrlQueryStart(
        text: String,
        qfPos: Int,
        urlStart: Int,
        minPos: Int,
        inDoubleQuote: Boolean,
        wrappedInAngle: Boolean
    ): Boolean {
        val nextPos = qfPos + 1
        if (nextPos >= text.length || isQuotedUrlTailBoundary(text, nextPos, inDoubleQuote, wrappedInAngle)) {
            return false
        }
        val urlStartsSpan = wrappedInAngle || isUrlAtDoubleQuoteStart(text, urlStart)
        val nextIsWs = CoreTelemetryScanners.isSpace(text[nextPos]) ||
            (inDoubleQuote && isJsonWhitespaceEscapeAt(text, nextPos))
        if (text[qfPos] == '?') return !nextIsWs || urlStartsSpan
        return !nextIsWs && (urlStartsSpan || !isPrecededBySpaceOrEscape(text, qfPos, minPos, inDoubleQuote))
    }

    private fun isUrlAtDoubleQuoteStart(text: String, urlStart: Int): Boolean {
        var p = urlStart
        while (p > 0) {
            val stepBack = horizontalSpaceOrEscapeStepBack(text, p, 0)
            if (stepBack > 0) {
                p -= stepBack
            } else {
                return text[p - 1] == '"' && !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, p - 1, 0)
            }
        }
        return false
    }

    private fun horizontalSpaceOrEscapeStepBack(text: String, pos: Int, minPos: Int): Int {
        val prev = text[pos - 1]
        if (isHorizSpaceChar(prev)) return 1
        if (isTabEscapeEndingAt(text, pos, minPos)) return 2
        if (isUnicodeHorizSpaceEscapeEndingAt(text, pos, minPos)) return 6
        return 0
    }

    private fun isHorizSpaceChar(ch: Char): Boolean =
        CoreTelemetryScanners.isSpace(ch) && ch != '\r' && ch != '\n'

    private fun isTabEscapeEndingAt(text: String, pos: Int, minPos: Int): Boolean {
        if (pos - 2 < minPos || text[pos - 2] != '\\' || text[pos - 1] != 't') return false
        return !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos - 2, minPos)
    }

    private fun isUnicodeHorizSpaceEscapeEndingAt(text: String, pos: Int, minPos: Int): Boolean {
        if (pos - 6 < minPos || text[pos - 6] != '\\' || text[pos - 5] != 'u') return false
        if (CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos - 6, minPos)) return false
        val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos - 4)
        return code >= 0 && isHorizSpaceChar(code.toChar())
    }

    private fun isJsonWhitespaceEscapeAt(text: String, pos: Int): Boolean {
        if (text[pos] != '\\' || pos + 1 >= text.length) return false
        val esc = text[pos + 1]
        if (esc == 't' || esc == 'r' || esc == 'n') return true
        if (esc == 'u' && pos + 5 < text.length) {
            val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, pos + 2)
            return code >= 0 && CoreTelemetryScanners.isSpace(code.toChar())
        }
        return false
    }

    private fun isPrecededBySpaceOrEscape(text: String, pos: Int, minPos: Int, inDoubleQuote: Boolean): Boolean {
        if (pos <= minPos) return false
        if (CoreTelemetryScanners.isSpace(text[pos - 1])) return true
        return inDoubleQuote && horizontalSpaceOrEscapeStepBack(text, pos, minPos) > 0
    }

    private fun buildSpacedUrlQueryResult(text: String, input: ContinuationInput, qfPos: Int): UrlContinuationResult {
        val span = input.span
        val angleMask = CoreTelemetryScanners.computeQueryAngleStopMask(text, span.start, input.inDoubleQuote)
        val qfEnd = CoreTelemetryScanners.scanQueryOrFragment(text, qfPos, angleMask)
        val (cleanQf, qfTail) = trimQueryOrFragmentDelimiters(
            text.substring(qfPos, qfEnd),
            text,
            span.start,
            input.inSingleQuote
        )
        val qfToken = "${cleanQf.first()}[redacted]"
        val secretPrefix = resolveSecretUrlPrefix(input.rawUrl, CoreTelemetryScanners.redactUrlPathSecrets(input.rawUrl))
        if (secretPrefix != null) {
            return UrlContinuationResult(true, "$secretPrefix$qfToken", false, "", "", qfTail, qfEnd)
        }
        return UrlContinuationResult(
            false,
            "",
            input.baseTail.isNotEmpty(),
            text.substring(span.totalEnd, qfPos),
            qfToken,
            qfTail,
            qfEnd
        )
    }
}
