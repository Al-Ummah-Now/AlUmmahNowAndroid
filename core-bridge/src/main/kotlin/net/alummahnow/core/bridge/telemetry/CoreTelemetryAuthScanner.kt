package net.alummahnow.core.bridge.telemetry

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Linear O(n) auth-header scanner (plain text, JSON escaped keys, arrays) and RFC 7617 Basic / Bearer redactor.
 */
internal object CoreTelemetryAuthScanner {
    private const val MAX_AUTH_KEY_LEN = CoreTelemetryAuthEscapedHelpers.MAX_AUTH_KEY_LEN
    private const val HEX_ESCAPE_LEN = 6
    private const val BASE64_CHUNK_LEN = 4
    private const val BASE64_PAD_TWO = 2
    private const val BASE64_PAD_THREE = 3
    private const val ASCII_SPACE = 0x20
    private const val ASCII_DEL = 0x7F
    private const val C1_END = 0x9F
    private const val MASK_LOW_FOUR = 0x0F
    private const val MASK_LOW_TWO = 0x03

    private val knownAuthSchemes = listOf(
        "Bearer", "Basic", "Digest", "Token", "Api-Key", "ApiKey",
        "OAuth", "Negotiate", "Mutual", "HOBA", "Vapid", "SCRAM-SHA-256", "AWS4-HMAC-SHA256"
    )

    private class HeaderBuilder(
        private val text: String,
        val edits: MutableList<Triple<Int, Int, String>>? = null
    ) {
        var sb: StringBuilder? = null
        var copied = 0
        val jsonCache = CoreTelemetryAuthJsonHelpers.JsonScanCache()

        fun appendRedacted(start: Int, end: Int, replacement: String) {
            if (edits != null) {
                edits += Triple(start, end, replacement)
                copied = end
                return
            }
            val out = sb ?: StringBuilder(text.length).also { sb = it }
            out.append(text, copied, start).append(replacement)
            copied = end
        }

        fun finish(): String {
            val out = sb ?: return text
            return out.append(text, copied, text.length).toString()
        }
    }

    fun redactAuthCredentials(text: String): String {
        val headerScrubbed = redactAuthHeaders(text)
        val escapedBasicScrubbed = CoreTelemetryQuotedAuthHelpers.redactEscapedBasicInBalancedQuotes(
            headerScrubbed,
            ::isBasicCredentialToken
        )
        val basicScrubbed = CoreTelemetryQuotedAuthHelpers.redactBasicTokens(
            escapedBasicScrubbed,
            ::isBasicCredentialToken
        )
        val bearerScrubbed = CoreTelemetryQuotedAuthHelpers.redactBearerTokens(basicScrubbed)
        val escapedBearerScrubbed = CoreTelemetryQuotedAuthHelpers.redactEscapedBearerInBalancedQuotes(bearerScrubbed)
        return CoreTelemetryAuthEscapedHelpers.redactJwtTokens(escapedBearerScrubbed)
    }

    private data class AuthValueContext(
        val isJsonMember: Boolean,
        val allowTruncatedObj: Boolean,
        val failedTopEnd: Int
    )

    private data class AuthKeyMatch(
        val keyStart: Int,
        val keyEnd: Int,
        val isJsonCandidate: Boolean,
        val hasJsonPrefix: Boolean,
        val isTupleCandidate: Boolean,
        val failedTopEnd: Int,
        val normalizedKey: String?
    )

    private fun mayContainAuthHeader(text: String): Boolean =
        text.indexOf(':') >= 0 ||
            text.indexOf('=') >= 0 ||
            text.contains("\\u") ||
            (text.indexOf('[') >= 0 && text.indexOf(',') >= 0)

    private fun redactAuthHeaders(text: String, maxDepth: Int = 4): String {
        if (!mayContainAuthHeader(text)) return text
        val builder = HeaderBuilder(text)
        scanAuthHeaders(text, maxDepth, builder)
        return builder.finish()
    }

    private fun scanAuthHeaders(text: String, maxDepth: Int, builder: HeaderBuilder) {
        builder.jsonCache.allowTrailingProse = maxDepth < 4
        var i = 0
        while (i < text.length) {
            val keyMatch = matchAuthHeaderKey(text, i, builder.jsonCache)
            if (keyMatch != null) {
                i = advanceAfterHeaderKey(text, keyMatch, builder)
            } else {
                i = stepUnmatchedAuthPosition(text, i, maxDepth, builder)
            }
        }
    }

    private fun stepUnmatchedAuthPosition(text: String, pos: Int, maxDepth: Int, builder: HeaderBuilder): Int {
        val isEscQuote = text[pos] == '\\' &&
            pos + 1 < text.length &&
            text[pos + 1] == '"' &&
            !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos, builder.copied)
        if (isEscQuote) {
            val escEnd = CoreTelemetryAuthEscapedHelpers.tryRedactEscapedQuotedAuthHeader(
                text,
                pos,
                builder.jsonCache,
                builder::appendRedacted,
                ::redactAuthValueBody
            )
            if (escEnd > pos) return escEnd
        }
        val close = if (maxDepth > 0 && isUnescapedDoubleQuote(text, pos, builder.copied)) {
            CoreTelemetryQuotedAuthHelpers.findDoubleQuotedSpanEnd(text, pos)
        } else {
            -1
        }
        if (close <= pos) return pos + 1
        val changed = redactAuthHeadersInBalancedSpan(text, pos + 1, close, maxDepth - 1, builder)
        val reenterClose = !changed &&
            !CoreTelemetryQuotedAuthHelpers.isJsonStringStartBoundary(text, pos) &&
            CoreTelemetryQuotedAuthHelpers.isJsonStringStartBoundary(text, close)
        return if (reenterClose) close else close + 1
    }

    private fun isUnescapedDoubleQuote(text: String, pos: Int, minPos: Int): Boolean =
        text[pos] == '"' && !CoreTelemetryEmailScanner.isPrecededByOddBackslashes(text, pos, minPos)

    private fun redactAuthHeadersInBalancedSpan(
        text: String,
        innerStart: Int,
        close: Int,
        nextDepth: Int,
        builder: HeaderBuilder
    ): Boolean {
        if (innerStart >= close || !CoreTelemetryQuotedAuthHelpers.containsAuthHeaderCandidate(text, innerStart, close)) {
            return false
        }
        val hasBackslash = (innerStart until close).any { text[it] == '\\' }
        val (decoded, rawOffsets) = if (hasBackslash) {
            CoreTelemetryQuotedPathRedactor.decodeQuotedSpanWithOffsets(text, innerStart, close)
        } else {
            text.substring(innerStart, close) to null
        }
        val hasSep = decoded.indexOf(':') >= 0 ||
            decoded.indexOf('=') >= 0 ||
            (decoded.indexOf('[') >= 0 && decoded.indexOf(',') >= 0)
        if (!hasSep) return false
        val edits = ArrayList<Triple<Int, Int, String>>()
        scanAuthHeaders(decoded, nextDepth, HeaderBuilder(decoded, edits))
        if (edits.isEmpty()) return false
        for ((eStart, eEnd, eRepl) in edits) {
            val rawStart = rawOffsets?.get(eStart) ?: (innerStart + eStart)
            val rawEnd = rawOffsets?.get(eEnd) ?: (innerStart + eEnd)
            builder.appendRedacted(rawStart, rawEnd, CoreTelemetryQuotedAuthHelpers.escapeJsonStringContent(eRepl))
        }
        return true
    }

    private fun advanceAfterHeaderKey(
        text: String,
        keyMatch: AuthKeyMatch,
        builder: HeaderBuilder
    ): Int {
        val allowNl = keyMatch.isJsonCandidate || keyMatch.hasJsonPrefix || keyMatch.isTupleCandidate
        val sep = skipAuthWhitespace(text, keyMatch.keyEnd, allowNl)
        val sepMatch = CoreTelemetryQuotedAuthHelpers.matchAuthSeparator(text, sep)
        if (sepMatch == null) {
            return advanceTupleHeaderKey(text, keyMatch, sep, builder)
        }
        val (sepLen, isColon) = sepMatch
        val ctx = AuthValueContext(
            isJsonMember = keyMatch.isJsonCandidate && isColon && sepLen == 1,
            allowTruncatedObj = keyMatch.hasJsonPrefix && isColon && sepLen == 1,
            failedTopEnd = if (isColon && sepLen == 1) keyMatch.failedTopEnd else -1
        )
        val valStart = skipAuthWhitespace(text, sep + sepLen, ctx.isJsonMember || ctx.allowTruncatedObj)
        if (valStart >= text.length || isNewline(text[valStart])) return sep + sepLen
        if (keyMatch.normalizedKey != null) {
            val q = text[keyMatch.keyStart]
            builder.appendRedacted(keyMatch.keyStart, keyMatch.keyEnd, "$q${keyMatch.normalizedKey}$q")
        }
        return redactAuthHeaderValue(text, valStart, ctx, builder)
    }

    private fun advanceTupleHeaderKey(
        text: String,
        keyMatch: AuthKeyMatch,
        sep: Int,
        builder: HeaderBuilder
    ): Int {
        if (!keyMatch.isTupleCandidate || sep >= text.length || text[sep] != ',') return keyMatch.keyEnd
        val valStart = CoreTelemetryAuthEscapedHelpers.resolveJsonTupleValueStart(text, sep + 1, builder.jsonCache)
        if (valStart < 0) return keyMatch.keyEnd
        if (keyMatch.normalizedKey != null) {
            val q = text[keyMatch.keyStart]
            builder.appendRedacted(keyMatch.keyStart, keyMatch.keyEnd, "$q${keyMatch.normalizedKey}$q")
        }
        return redactAuthHeaderValue(text, valStart, AuthValueContext(true, false, -1), builder)
    }

    private fun matchAuthHeaderKey(
        text: String,
        pos: Int,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): AuthKeyMatch? {
        val ch = text[pos]
        if (ch == '"' || ch == '\'') return matchQuotedAuthKey(text, pos, ch, cache)
        if (pos > 0 && isAuthKeyWordChar(text[pos - 1])) return null
        val matched = CoreTelemetryAuthEscapedHelpers.unquotedAuthHeaders.firstOrNull {
            matchesUnquotedKey(text, pos, it, it.length)
        }
        val customEnd = if (matched == null) CoreTelemetryAuthEscapedHelpers.matchUnquotedCustomXHeaderEnd(text, pos) else -1
        return when {
            matched != null -> AuthKeyMatch(pos, pos + matched.length, false, false, false, -1, null)
            customEnd > pos -> AuthKeyMatch(pos, customEnd, false, false, false, -1, null)
            else -> null
        }
    }

    private fun matchesUnquotedKey(text: String, pos: Int, literal: String, len: Int): Boolean {
        val after = pos + len
        if (after > text.length) return false
        val boundaryOk = after == text.length || !isAuthKeyWordChar(text[after])
        return boundaryOk && text.regionMatches(pos, literal, 0, len, ignoreCase = true)
    }

    private data class QuotedKeyResult(
        val keyEnd: Int,
        val canonical: String,
        val sawEscape: Boolean,
        val hasInvalidJsonEscape: Boolean
    )

    private fun matchQuotedAuthKey(
        text: String,
        pos: Int,
        quote: Char,
        cache: CoreTelemetryAuthJsonHelpers.JsonScanCache
    ): AuthKeyMatch? {
        val parsed = scanQuotedAuthKey(text, pos, quote) ?: return null
        val hasPrefix = quote == '"' && CoreTelemetryAuthJsonHelpers.isPrecededByBraceOrComma(text, pos)
        val allowJson = quote == '"' && !parsed.hasInvalidJsonEscape
        val isTuple = allowJson && isPrecededByOpenBracket(text, pos)
        val openBrace = if (allowJson) CoreTelemetryAuthJsonHelpers.findEnclosingJsonObjectStart(text, pos, cache) else -1
        val authState = if (openBrace >= 0) {
            CoreTelemetryAuthJsonHelpers.classifyTopLevelJsonContainer(text, openBrace, cache)
        } else {
            -1
        }
        return AuthKeyMatch(
            pos,
            parsed.keyEnd,
            isJsonCandidate = authState == 0,
            hasJsonPrefix = hasPrefix,
            isTupleCandidate = isTuple,
            failedTopEnd = if (authState > 0) authState else -1,
            normalizedKey = if (parsed.sawEscape) parsed.canonical else null
        )
    }

    private fun isPrecededByOpenBracket(text: String, pos: Int): Boolean {
        var p = pos - 1
        while (p >= 0 && text[p] in " \t\r\n") p--
        return p >= 0 && text[p] == '['
    }

    private fun scanQuotedAuthKey(text: String, pos: Int, quote: Char): QuotedKeyResult? {
        val core = StringBuilder(MAX_AUTH_KEY_LEN + 1)
        var j = pos + 1
        var sawTrailingPad = false
        var sawPad = false
        var sawEscape = false
        var hasInvalidJsonEscape = false
        while (j < text.length) {
            val ch = text[j]
            if (ch.code < ASCII_SPACE && ch != '\t') return null
            if (ch == quote) {
                return canonicalAuthKeyName(core.toString())?.let {
                    QuotedKeyResult(j + 1, it, sawEscape && !sawPad, hasInvalidJsonEscape)
                }
            }
            val (decodedChar, step, validEsc) = decodeQuotedKeyChar(text, j)
            if (ch == '\\') {
                sawEscape = true
                if (!validEsc) hasInvalidJsonEscape = true
            }
            if (step < 0 || !appendTrimmedKeyChar(decodedChar, core, sawTrailingPad)) return null
            if (isHorizPad(decodedChar)) {
                sawPad = true
                if (core.isNotEmpty()) sawTrailingPad = true
            }
            j += step
        }
        return null
    }

    private fun isHorizPad(ch: Char): Boolean = ch == ' ' || ch == '\t'

    private fun appendTrimmedKeyChar(ch: Char, core: StringBuilder, sawTrailingPad: Boolean): Boolean {
        if (isHorizPad(ch)) return true
        if (sawTrailingPad || core.length >= MAX_AUTH_KEY_LEN) return false
        core.append(ch)
        return true
    }

    private fun canonicalAuthKeyName(key: String): String? =
        CoreTelemetryAuthEscapedHelpers.matchCanonicalAuthHeaderName(key)

    private fun decodeQuotedKeyChar(text: String, pos: Int): Triple<Char, Int, Boolean> {
        val ch = text[pos]
        if (ch != '\\') return Triple(ch, 1, true)
        if (pos + 1 >= text.length) return Triple('\u0000', -1, false)
        val next = text[pos + 1]
        if (next == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val hex = parseHex4(text, pos + 2)
            if (hex >= 0) return Triple(hex.toChar(), HEX_ESCAPE_LEN, true)
        }
        val (decodedChar, validJson) = CoreTelemetryAuthEscapedHelpers.decodeSimpleJsonEscape(next)
        return Triple(decodedChar, 2, validJson)
    }

    private fun parseHex4(text: String, pos: Int): Int {
        var value = 0
        for (offset in 0 until BASE64_CHUNK_LEN) {
            val d = Character.digit(text[pos + offset], 16)
            if (d < 0) return -1
            value = (value shl BASE64_CHUNK_LEN) or d
        }
        return value
    }

    private fun redactAuthHeaderValue(
        text: String,
        valStart: Int,
        ctx: AuthValueContext,
        builder: HeaderBuilder
    ): Int {
        val ch = text[valStart]
        if (ch == '"' || ch == '\'') {
            val afterQuote = redactQuotedAuthString(text, valStart, ch, builder)
            val validJsonStr =
                ctx.isJsonMember &&
                    ch == '"' &&
                    CoreTelemetryAuthJsonValidator.scanJsonStringEnd(text, valStart + 1) == afterQuote
            return redactTrailingPlainAuthContinuation(text, afterQuote, validJsonStr, ctx.failedTopEnd, builder)
        }
        if (ch == '[') {
            val afterArray = redactAuthArrayValue(text, valStart + 1, ctx, builder)
            val validJsonArr =
                ctx.isJsonMember &&
                    CoreTelemetryAuthJsonValidator.tryParseJsonValue(text, valStart, builder.jsonCache.validJsonValueEnd) == afterArray
            return redactTrailingPlainAuthContinuation(text, afterArray, validJsonArr, ctx.failedTopEnd, builder)
        }
        val afterObj = if (ch == '{') tryRedactJsonObjectValue(text, valStart, ctx, builder) else -1
        if (afterObj >= 0) return redactTrailingPlainAuthContinuation(text, afterObj, ctx.isJsonMember, ctx.failedTopEnd, builder)
        val afterPrim = if (ctx.isJsonMember) tryRedactJsonPrimitiveAuthValue(text, valStart, builder) else -1
        return if (afterPrim >= 0) afterPrim else redactPlainAuthSpan(text, valStart, ctx.failedTopEnd, builder)
    }

    private fun tryRedactJsonObjectValue(
        text: String,
        bracePos: Int,
        ctx: AuthValueContext,
        builder: HeaderBuilder
    ): Int {
        val objEnd = CoreTelemetryAuthJsonHelpers.scanBalancedJsonObjectEnd(text, bracePos, builder.jsonCache)
        if (objEnd > bracePos) {
            builder.appendRedacted(bracePos, objEnd, if (ctx.isJsonMember) "\"[redacted]\"" else "[redacted]")
            return objEnd
        }
        if (ctx.isJsonMember || ctx.allowTruncatedObj) {
            builder.appendRedacted(bracePos, text.length, "\"[redacted]\"")
            return text.length
        }
        return -1
    }

    private fun tryRedactJsonPrimitiveAuthValue(text: String, valStart: Int, builder: HeaderBuilder): Int {
        var tokenEnd = valStart
        while (tokenEnd < text.length && !CoreTelemetryAuthJsonHelpers.isJsonPrimitiveStop(text[tokenEnd])) tokenEnd++
        val token = text.substring(valStart, tokenEnd)
        val isNull = token == "null"
        val isPrimitive =
            isNull || token == "true" || token == "false" || CoreTelemetryAuthJsonHelpers.isJsonNumber(token)
        if (!isPrimitive) return -1
        val next = skipAuthWhitespace(text, tokenEnd, allowNewlines = true)
        if (next < text.length &&
            !CoreTelemetryAuthJsonHelpers.isJsonMemberContinuationDelimiter(
                text,
                next,
                builder.jsonCache.allowTrailingProse
            )
        ) {
            return -1
        }
        if (!isNull) builder.appendRedacted(valStart, tokenEnd, "\"[redacted]\"")
        return tokenEnd
    }

    private fun redactPlainAuthSpan(text: String, valStart: Int, minEnd: Int, builder: HeaderBuilder): Int {
        var lineEnd = scanPlainAuthValueEnd(text, valStart)
        if (minEnd > lineEnd) lineEnd = scanPlainAuthValueEnd(text, minEnd)
        val redacted = redactAuthValueBody(text, valStart, lineEnd)
        if (redacted != null) builder.appendRedacted(valStart, lineEnd, redacted)
        return lineEnd
    }

    private fun redactTrailingPlainAuthContinuation(
        text: String,
        afterPos: Int,
        isJsonMember: Boolean,
        failedTopEnd: Int,
        builder: HeaderBuilder
    ): Int {
        val tailStart = findTrailingPlainAuthStart(text, afterPos, isJsonMember, builder.jsonCache.allowTrailingProse)
        if (tailStart >= 0) return redactPlainAuthSpan(text, tailStart, failedTopEnd, builder)
        if (!isJsonMember && failedTopEnd > afterPos) {
            val nextStart = CoreTelemetryAuthJsonHelpers.skipJsonWhitespace(text, afterPos)
            if (nextStart < text.length) return redactPlainAuthSpan(text, nextStart, failedTopEnd, builder)
        }
        return afterPos
    }

    private fun findTrailingPlainAuthStart(
        text: String,
        afterPos: Int,
        isJsonMember: Boolean,
        allowTrailingProse: Boolean
    ): Int {
        var pos = afterPos
        var advancing = true
        while (pos < text.length && advancing) {
            while (pos < text.length && isHorizSpace(text[pos])) pos++
            val fold = advanceObsFold(text, pos)
            if (fold > pos) pos = fold else advancing = false
        }
        val isContinuation = isJsonMember &&
            CoreTelemetryAuthJsonHelpers.isJsonMemberContinuationDelimiter(text, pos, allowTrailingProse)
        val stopsHere = pos >= text.length || isNewline(text[pos]) || isContinuation
        return if (stopsHere) -1 else pos
    }

    private fun scanPlainAuthValueEnd(text: String, start: Int): Int {
        var end = start
        var done = false
        while (end < text.length && !done) {
            if (isNewline(text[end])) {
                val fold = advanceObsFold(text, end)
                if (fold > end) end = fold else done = true
            } else {
                end++
            }
        }
        return end
    }

    private fun redactAuthArrayValue(
        text: String,
        start: Int,
        ctx: AuthValueContext,
        builder: HeaderBuilder
    ): Int {
        var depth = 1
        var k = start
        var stopped = false
        while (k < text.length && depth > 0 && !stopped) {
            val ch = text[k]
            when {
                ch == '[' -> {
                    depth++
                    k++
                }
                ch == ']' -> {
                    depth--
                    k++
                }
                ch == '"' || ch == '\'' -> k = redactQuotedAuthString(text, k, ch, builder)
                ch == '{' -> {
                    val objEnd = tryRedactJsonObjectValue(text, k, ctx, builder)
                    k = if (objEnd > k) objEnd else redactUnquotedArrayElement(text, k, ctx.isJsonMember, builder)
                }
                isHorizSpace(ch) || ch == ',' -> k++
                isNewline(ch) -> {
                    val next = if (ctx.isJsonMember || ctx.allowTruncatedObj) k + 1 else advanceObsFold(text, k)
                    if (next > k) k = next else stopped = true
                }
                else -> k = redactUnquotedArrayElement(text, k, ctx.isJsonMember, builder)
            }
        }
        return k
    }

    private fun redactUnquotedArrayElement(
        text: String,
        start: Int,
        allowNewlines: Boolean,
        builder: HeaderBuilder
    ): Int {
        var elemEnd = start
        while (elemEnd < text.length && !isArrayElemStop(text[elemEnd])) elemEnd++
        var trimEnd = elemEnd
        while (trimEnd > start && isHorizSpace(text[trimEnd - 1])) trimEnd--
        val isNull = trimEnd - start == BASE64_CHUNK_LEN && text.regionMatches(start, "null", 0, BASE64_CHUNK_LEN)
        if (trimEnd > start && !isNull) {
            val redacted = redactAuthValueBody(text, start, trimEnd)
            if (redacted != null) {
                val wrapped = if (allowNewlines) "\"$redacted\"" else redacted
                builder.appendRedacted(start, elemEnd, wrapped + text.substring(trimEnd, elemEnd))
            }
        }
        return elemEnd
    }

    private fun isArrayElemStop(ch: Char): Boolean = ch == ',' || ch == '[' || ch == ']' || isNewline(ch)

    private fun redactQuotedAuthString(text: String, quotePos: Int, quote: Char, builder: HeaderBuilder): Int {
        var j = quotePos + 1
        var escaped = false
        while (j < text.length && !isQuotedStringStop(text[j], quote, escaped)) {
            escaped = text[j] == '\\' && !escaped
            j++
        }
        val innerStart = quotePos + 1
        val redacted = redactAuthValueBody(text, innerStart, j)
        if (redacted != null) builder.appendRedacted(innerStart, j, redacted)
        return if (j < text.length && text[j] == quote) j + 1 else j
    }

    private fun isQuotedStringStop(ch: Char, quote: Char, escaped: Boolean): Boolean =
        isNewline(ch) || (ch == quote && !escaped)

    private fun redactAuthValueBody(text: String, start: Int, end: Int): String? {
        var s = start
        while (s < end && isHorizSpace(text[s])) s++
        if (s >= end) return null
        val schemePrefixEnd = findKnownSchemePrefixEnd(text, s, end)
        val keepEnd = if (schemePrefixEnd > 0) schemePrefixEnd else s
        return text.substring(start, keepEnd) + "[redacted]"
    }

    private fun findKnownSchemePrefixEnd(text: String, start: Int, end: Int): Int {
        for (scheme in knownAuthSchemes) {
            val afterScheme = start + scheme.length
            val schemeMatched = afterScheme < end &&
                text.regionMatches(start, scheme, 0, scheme.length, ignoreCase = true) &&
                isHorizSpace(text[afterScheme])
            if (schemeMatched) {
                var afterSpaces = afterScheme
                while (afterSpaces < end && isHorizSpace(text[afterSpaces])) afterSpaces++
                if (afterSpaces < end) return afterSpaces
            }
        }
        return -1
    }

    private fun isBasicCredentialToken(token: String): Boolean {
        val bytes = decodeCanonicalBase64(token) ?: return false
        if (bytes.none { it == ':'.code.toByte() }) return false
        val decoded = decodeUtf8Strict(bytes)
        if (decoded != null && decoded.none { isControlCode(it.code) }) return true
        return bytes.none { isControlCode(it.toInt() and 0xFF) }
    }

    private fun isControlCode(code: Int): Boolean =
        code < ASCII_SPACE || (code in ASCII_DEL..C1_END)

    private fun decodeUtf8Strict(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun decodeCanonicalBase64(token: String): ByteArray? {
        val unpadded = token.trimEnd('=')
        val pad = token.length - unpadded.length
        val rem = unpadded.length % BASE64_CHUNK_LEN
        if (!hasValidBase64Padding(token.length, pad, rem, unpadded)) return null
        val outLen = (unpadded.length / BASE64_CHUNK_LEN) * 3 + when (rem) {
            BASE64_PAD_TWO -> 1
            BASE64_PAD_THREE -> 2
            else -> 0
        }
        val out = ByteArray(outLen)
        var inIdx = 0
        var outIdx = 0
        var valid = true
        while (inIdx < unpadded.length && valid) {
            val written = decodeBase64Chunk(unpadded, inIdx, out, outIdx)
            if (written < 0) {
                valid = false
            } else {
                outIdx += written
                inIdx += BASE64_CHUNK_LEN
            }
        }
        return if (valid) out else null
    }

    private fun hasValidBase64Padding(totalLen: Int, pad: Int, rem: Int, unpadded: String): Boolean {
        val shapeOk = pad <= BASE64_PAD_TWO && rem != 1 && (pad == 0 || (totalLen and 3 == 0 && rem + pad == BASE64_CHUNK_LEN))
        if (!shapeOk || unpadded.isEmpty()) return false
        val lastVal = base64Value(unpadded[unpadded.length - 1])
        if (lastVal < 0) return false
        return when (rem) {
            BASE64_PAD_TWO -> (lastVal and MASK_LOW_FOUR) == 0
            BASE64_PAD_THREE -> (lastVal and MASK_LOW_TWO) == 0
            else -> true
        }
    }

    private fun decodeBase64Chunk(src: String, inIdx: Int, out: ByteArray, outIdx: Int): Int {
        val c0 = base64Value(src[inIdx])
        val c1 = if (inIdx + 1 < src.length) base64Value(src[inIdx + 1]) else -1
        val c2 = if (inIdx + 2 < src.length) base64Value(src[inIdx + 2]) else -2
        val c3 = if (inIdx + 3 < src.length) base64Value(src[inIdx + 3]) else -2
        val invalidPair = c0 < 0 || c1 < 0
        val invalidTail = c2 == -1 || c3 == -1
        if (invalidPair || invalidTail) return -1
        out[outIdx] = ((c0 shl 2) or (c1 shr BASE64_CHUNK_LEN)).toByte()
        if (c2 < 0) return 1
        out[outIdx + 1] = (((c1 and MASK_LOW_FOUR) shl BASE64_CHUNK_LEN) or (c2 shr 2)).toByte()
        if (c3 < 0) return 2
        out[outIdx + 2] = (((c2 and MASK_LOW_TWO) shl 6) or c3).toByte()
        return BASE64_PAD_THREE
    }

    private fun base64Value(ch: Char): Int = when {
        ch in 'A'..'Z' -> ch - 'A'
        ch in 'a'..'z' -> ch - 'a' + 26
        ch in '0'..'9' -> ch - '0' + 52
        ch == '+' || ch == '-' -> 62
        ch == '/' || ch == '_' -> 63
        else -> -1
    }

    private fun skipAuthWhitespace(text: String, pos: Int, allowNewlines: Boolean): Int {
        var p = pos
        var done = false
        while (p < text.length && !done) {
            val ch = text[p]
            if (isAuthWhitespace(ch, allowNewlines)) {
                p++
            } else if (!allowNewlines && isNewline(ch)) {
                val fold = advanceObsFold(text, p)
                if (fold > p) p = fold else done = true
            } else {
                done = true
            }
        }
        return p
    }

    private fun advanceObsFold(text: String, nlPos: Int): Int {
        if (nlPos >= text.length || !isNewline(text[nlPos])) return -1
        val afterNl = if (text[nlPos] == '\r' && nlPos + 1 < text.length && text[nlPos + 1] == '\n') {
            nlPos + 2
        } else {
            nlPos + 1
        }
        if (afterNl >= text.length || !isHorizSpace(text[afterNl])) return -1
        var p = afterNl
        while (p < text.length && isHorizSpace(text[p])) p++
        return p
    }

    private fun isAuthWhitespace(ch: Char, allowNewlines: Boolean): Boolean =
        isHorizSpace(ch) || (allowNewlines && isNewline(ch))

    private fun isHorizSpace(ch: Char): Boolean = ch == ' ' || ch == '\t'
    private fun isNewline(ch: Char): Boolean = ch == '\r' || ch == '\n'
    private fun isAuthKeyWordChar(ch: Char): Boolean = CoreTelemetryScanners.isAsciiWordChar(ch) || ch == '-'
}
