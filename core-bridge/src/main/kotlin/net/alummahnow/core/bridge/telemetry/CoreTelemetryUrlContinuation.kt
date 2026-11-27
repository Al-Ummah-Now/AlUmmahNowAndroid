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
        val inSingleQuote: Boolean
    )

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
        if (span.hasQf || span.totalEnd >= text.length || text[span.totalEnd] !in ",([]){}") return null
        val webhookProbe = probeWebhookPrefix(input.rawUrl)
        if (webhookProbe == null && span.totalEnd < contLimits[0]) return null
        val outerQuote = resolveOuterQuote(text, span.start, input.inSingleQuote)
        val initial = CoreTelemetryScanners.ContScanState(
            span.totalEnd >= contLimits[1],
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

    private fun resolveOuterQuote(text: String, spanStart: Int, inSingleQuote: Boolean): Char = when {
        inSingleQuote || (spanStart > 0 && text[spanStart - 1] == '\'') -> '\''
        spanStart > 0 && text[spanStart - 1] == '"' -> '"'
        else -> '\u0000'
    }

    private fun buildQuoteFlags(state: CoreTelemetryScanners.ContScanState, inSingleQuote: Boolean): Int {
        var flags = 0
        if (state.allowDoubleQuotes) flags = flags or 1
        if (state.allowSingleQuotes) flags = flags or 2
        if (inSingleQuote) flags = flags or 4
        if (state.outerQuote == '"') flags = flags or 8
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
        val retryQuote = when {
            state.quoteChar != '\u0000' && (state.quoteChar == state.outerQuote || (webhookProbe == null && !hitsQf)) ->
                state.quoteChar
            state.sawNestedOuterQuote && state.groupStack.isNotEmpty() -> state.outerQuote
            else -> '\u0000'
        }
        if (retryQuote == '\u0000') return state to firstEnd
        recordUnclosedQuote(retryQuote, firstEnd, contLimits)
        val nextState = CoreTelemetryScanners.ContScanState(
            if (retryQuote == '"') false else state.allowDoubleQuotes,
            if (retryQuote == '\'') false else state.allowSingleQuotes,
            state.outerQuote,
            state.stopAtSchemes
        )
        return nextState to CoreTelemetryScanners.scanUrlContinuation(text, start, nextState)
    }

    private fun probeWebhookPrefix(rawUrl: String): String? {
        val probeInput = "${rawUrl}x"
        val probe = CoreTelemetryScanners.redactUrlPathSecrets(probeInput)
        return if (probe != probeInput && probe.endsWith("[redacted]")) probe else null
    }

    private fun recordUnclosedQuote(quoteChar: Char, pathEnd: Int, contLimits: IntArray) {
        if (quoteChar == '"' && pathEnd > contLimits[1]) contLimits[1] = pathEnd
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
        val qfEnd = CoreTelemetryScanners.scanQueryOrFragment(text, ctx.pathEnd)
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

    fun advanceSingleQuoteState(text: String, from: Int, to: Int, inSingleQuote: Boolean): Boolean {
        var open = inSingleQuote
        var i = from
        while (i < to) {
            val ch = text[i]
            val isEsc = ch == '\\' && open && i + 1 < to && text[i + 1] !in "\r\n"
            if (isEsc) {
                i += 2
            } else {
                if (ch == '\r' || ch == '\n') {
                    open = false
                } else if (ch == '\'' && !isContractionApostrophe(text, i)) {
                    open = !open
                }
                i++
            }
        }
        return open
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
}
