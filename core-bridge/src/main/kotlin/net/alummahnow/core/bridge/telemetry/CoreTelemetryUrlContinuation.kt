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

    private class ContinuationContext(
        val span: CoreTelemetryScanners.UrlSpan,
        val rawUrl: String,
        val baseTail: String,
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
        span: CoreTelemetryScanners.UrlSpan,
        rawUrl: String,
        baseTail: String,
        contLimits: IntArray
    ): UrlContinuationResult? {
        val eligible = !span.hasQf && span.totalEnd >= contLimits[0] && span.totalEnd < text.length
        if (!eligible || text[span.totalEnd] !in ",([]){}") return null
        val allowDouble = canOpenContinuationQuote(text, span, contLimits[1], '"')
        val allowSingle = canOpenContinuationQuote(text, span, contLimits[2], '\'')
        val webhookProbe = probeWebhookPrefix(rawUrl)
        val scanState = CoreTelemetryScanners.ContScanState(allowDouble, allowSingle, webhookProbe == null)
        val pathEnd = CoreTelemetryScanners.scanUrlContinuation(text, span.totalEnd, scanState)
        val quoteFlags = (if (allowDouble) 1 else 0) or (if (allowSingle) 2 else 0)
        val ctx = ContinuationContext(span, rawUrl, baseTail, webhookProbe, quoteFlags, pathEnd)
        val result = extendUrlContinuationQuery(text, ctx) ?: extendUrlContinuationSecret(text, ctx)
        if (result == null) recordFailedContinuation(scanState, pathEnd, contLimits)
        return result
    }

    private fun canOpenContinuationQuote(
        text: String,
        span: CoreTelemetryScanners.UrlSpan,
        unclosedLimit: Int,
        quote: Char
    ): Boolean = span.totalEnd >= unclosedLimit && !(span.start > 0 && text[span.start - 1] == quote)

    private fun probeWebhookPrefix(rawUrl: String): String? {
        val probeInput = "${rawUrl}x"
        val probe = CoreTelemetryScanners.redactUrlPathSecrets(probeInput)
        return if (probe != probeInput && probe.endsWith("[redacted]")) probe else null
    }

    private fun recordFailedContinuation(
        state: CoreTelemetryScanners.ContScanState,
        pathEnd: Int,
        contLimits: IntArray
    ) {
        if (state.quoteChar == '"' && pathEnd > contLimits[1]) contLimits[1] = pathEnd
        if (state.quoteChar == '\'' && pathEnd > contLimits[2]) contLimits[2] = pathEnd
        if (!state.sawInnerQf && pathEnd > contLimits[0]) contLimits[0] = pathEnd
    }

    private fun extendUrlContinuationQuery(text: String, ctx: ContinuationContext): UrlContinuationResult? {
        val hasNext = ctx.pathEnd > ctx.span.totalEnd && ctx.pathEnd < text.length
        if (!hasNext || (text[ctx.pathEnd] != '?' && text[ctx.pathEnd] != '#')) return null
        val qfEnd = CoreTelemetryScanners.scanQueryOrFragment(text, ctx.pathEnd)
        val (cleanQf, qfTail) = trimQueryOrFragmentDelimiters(text.substring(ctx.pathEnd, qfEnd), text, ctx.span.start)
        val qfToken = "${cleanQf.first()}[redacted]"
        if (ctx.webhookProbe != null) {
            return UrlContinuationResult(true, "${ctx.webhookProbe}$qfToken", false, "", "", qfTail, qfEnd)
        }
        val baseRedacted = CoreTelemetryScanners.redactUrlPathSecrets(ctx.rawUrl)
        val prefixRedacted = CoreTelemetryScanners.redactUrlPathSecrets(text.substring(ctx.span.start, ctx.pathEnd))
        val swallowed = prefixRedacted == baseRedacted || prefixRedacted == "$baseRedacted[redacted]"
        return if (swallowed) {
            UrlContinuationResult(true, "$prefixRedacted$qfToken", false, "", "", qfTail, qfEnd)
        } else {
            val middle = extractContinuationMiddle(prefixRedacted, baseRedacted, text, ctx.span.totalEnd, ctx.pathEnd)
            val keepTail = ctx.baseTail.isNotEmpty() && baseRedacted.endsWith(ctx.baseTail)
            UrlContinuationResult(false, "", keepTail, middle, qfToken, qfTail, qfEnd)
        }
    }

    private fun extendUrlContinuationSecret(text: String, ctx: ContinuationContext): UrlContinuationResult? {
        val end = CoreTelemetryScanners.trimWebhookContinuationEnd(
            text,
            ctx.span.start,
            ctx.span.totalEnd,
            ctx.pathEnd,
            ctx.quoteFlags
        )
        if (end <= ctx.span.totalEnd) return null
        val extTail = text.substring(end, ctx.pathEnd)
        if (ctx.webhookProbe != null) {
            return UrlContinuationResult(true, ctx.webhookProbe, false, "", "", extTail, ctx.pathEnd)
        }
        val baseRedacted = CoreTelemetryScanners.redactUrlPathSecrets(ctx.rawUrl)
        val extRedacted = CoreTelemetryScanners.redactUrlPathSecrets(text.substring(ctx.span.start, end))
        if (extRedacted == "$baseRedacted${text.substring(ctx.span.totalEnd, end)}") return null
        val swallowed = extRedacted == baseRedacted || extRedacted == "$baseRedacted[redacted]"
        return if (swallowed) {
            UrlContinuationResult(true, extRedacted, false, "", "", extTail, ctx.pathEnd)
        } else {
            val middle = extractContinuationMiddle(extRedacted, baseRedacted, text, ctx.span.totalEnd, end)
            val keepTail = ctx.baseTail.isNotEmpty() && baseRedacted.endsWith(ctx.baseTail)
            UrlContinuationResult(false, "", keepTail, middle, "", extTail, ctx.pathEnd)
        }
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

    private fun isContractionApostrophe(text: String, pos: Int): Boolean =
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
