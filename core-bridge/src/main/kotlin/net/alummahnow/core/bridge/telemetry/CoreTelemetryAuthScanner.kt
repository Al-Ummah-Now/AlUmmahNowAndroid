package net.alummahnow.core.bridge.telemetry

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Linear O(n) auth-header scanner (plain text, JSON escaped keys, arrays) and RFC 7617 Basic / Bearer redactor.
 */
internal object CoreTelemetryAuthScanner {
    private const val MAX_QUOTED_KEY_RAW_LEN = 129
    private const val MAX_AUTH_KEY_LEN = 19
    private const val AUTH_LEN = 13
    private const val PROXY_AUTH_LEN = 19
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
    private val bearerTokenRegex = Regex(
        """\b(Bearer)\s+(?!\[redacted\](?=$|[\s"',;)\]}]))[^\s"',;)\]}]+""",
        RegexOption.IGNORE_CASE
    )
    private val basicTokenRegex = Regex(
        """\b(Basic)\s+([A-Za-z0-9+/_-]{2,}={0,2})(?![A-Za-z0-9+/_=-])""",
        RegexOption.IGNORE_CASE
    )

    private class HeaderBuilder(private val text: String) {
        var sb: StringBuilder? = null
        var copied = 0

        fun appendRedacted(start: Int, end: Int, replacement: String) {
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
        val bearerScrubbed = bearerTokenRegex.replace(headerScrubbed, "$1 [redacted]")
        return basicTokenRegex.replace(bearerScrubbed) { match ->
            if (isBasicCredentialToken(match.groupValues[2])) {
                "${match.groupValues[1]} [redacted]"
            } else {
                match.value
            }
        }
    }

    private data class AuthKeyMatch(val keyStart: Int, val keyEnd: Int, val allowNewlines: Boolean, val normalizedKey: String?)

    private fun redactAuthHeaders(text: String): String {
        if (text.indexOf(':') < 0 && text.indexOf('=') < 0) return text
        val builder = HeaderBuilder(text)
        var i = 0
        while (i < text.length) {
            val keyMatch = matchAuthHeaderKey(text, i)
            if (keyMatch == null) {
                i++
            } else {
                i = advanceAfterHeaderKey(text, keyMatch, builder)
            }
        }
        return builder.finish()
    }

    private fun advanceAfterHeaderKey(
        text: String,
        keyMatch: AuthKeyMatch,
        builder: HeaderBuilder
    ): Int {
        val sep = skipAuthWhitespace(text, keyMatch.keyEnd, keyMatch.allowNewlines)
        if (sep >= text.length || (text[sep] != ':' && text[sep] != '=')) return keyMatch.keyEnd
        val valStart = skipAuthWhitespace(text, sep + 1, keyMatch.allowNewlines)
        if (valStart >= text.length || isNewline(text[valStart])) return sep + 1
        if (keyMatch.normalizedKey != null) {
            val q = text[keyMatch.keyStart]
            builder.appendRedacted(keyMatch.keyStart, keyMatch.keyEnd, "$q${keyMatch.normalizedKey}$q")
        }
        return redactAuthHeaderValue(text, valStart, keyMatch.allowNewlines, builder)
    }

    private fun matchAuthHeaderKey(text: String, pos: Int): AuthKeyMatch? {
        val ch = text[pos]
        if (ch == '"' || ch == '\'') return matchQuotedAuthKey(text, pos, ch)
        if (pos > 0 && isAuthKeyWordChar(text[pos - 1])) return null
        return when {
            matchesUnquotedKey(text, pos, "proxy-authorization", PROXY_AUTH_LEN) ->
                AuthKeyMatch(pos, pos + PROXY_AUTH_LEN, allowNewlines = false, normalizedKey = null)
            matchesUnquotedKey(text, pos, "authorization", AUTH_LEN) ->
                AuthKeyMatch(pos, pos + AUTH_LEN, allowNewlines = false, normalizedKey = null)
            else -> null
        }
    }

    private fun matchesUnquotedKey(text: String, pos: Int, literal: String, len: Int): Boolean {
        val after = pos + len
        if (after > text.length) return false
        val boundaryOk = after == text.length || !isAuthKeyWordChar(text[after])
        return boundaryOk && text.regionMatches(pos, literal, 0, len, ignoreCase = true)
    }

    private fun matchQuotedAuthKey(text: String, pos: Int, quote: Char): AuthKeyMatch? {
        val limit = minOf(text.length, pos + MAX_QUOTED_KEY_RAW_LEN)
        val decoded = StringBuilder(MAX_AUTH_KEY_LEN + 1)
        var j = pos + 1
        var sawEscape = false
        var matched: AuthKeyMatch? = null
        var failed = false
        while (j < limit && matched == null && !failed) {
            val ch = text[j]
            if (isNewline(ch)) {
                failed = true
            } else if (ch == quote) {
                val canonical = canonicalAuthKeyName(decoded.toString())
                if (canonical != null) {
                    matched = AuthKeyMatch(pos, j + 1, allowNewlines = true, normalizedKey = if (sawEscape) canonical else null)
                } else {
                    failed = true
                }
            } else if (decoded.length >= MAX_AUTH_KEY_LEN) {
                failed = true
            } else {
                if (ch == '\\') sawEscape = true
                val step = appendDecodedKeyChar(text, j, decoded)
                if (step < 0) failed = true else j += step
            }
        }
        return matched
    }

    private fun canonicalAuthKeyName(key: String): String? = when {
        key.equals("authorization", ignoreCase = true) -> "Authorization"
        key.equals("proxy-authorization", ignoreCase = true) -> "Proxy-Authorization"
        else -> null
    }

    private fun appendDecodedKeyChar(text: String, pos: Int, out: StringBuilder): Int {
        val ch = text[pos]
        if (ch != '\\') {
            out.append(ch)
            return 1
        }
        if (pos + 1 >= text.length) return -1
        val next = text[pos + 1]
        if (next == 'u' && pos + HEX_ESCAPE_LEN <= text.length) {
            val hex = parseHex4(text, pos + 2)
            if (hex >= 0) {
                out.append(hex.toChar())
                return HEX_ESCAPE_LEN
            }
        }
        out.append(next)
        return 2
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
        allowNewlines: Boolean,
        builder: HeaderBuilder
    ): Int {
        val ch = text[valStart]
        if (ch == '"' || ch == '\'') return redactQuotedAuthString(text, valStart, ch, builder)
        if (ch == '[') return redactAuthArrayValue(text, valStart + 1, allowNewlines, builder)
        val lineEnd = scanPlainAuthValueEnd(text, valStart)
        val redacted = redactAuthValueBody(text, valStart, lineEnd)
        if (redacted != null) builder.appendRedacted(valStart, lineEnd, redacted)
        return lineEnd
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
        allowNewlines: Boolean,
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
                isHorizSpace(ch) || ch == ',' -> k++
                isNewline(ch) -> {
                    val next = if (allowNewlines) k + 1 else advanceObsFold(text, k)
                    if (next > k) k = next else stopped = true
                }
                else -> k = redactUnquotedArrayElement(text, k, builder)
            }
        }
        return k
    }

    private fun redactUnquotedArrayElement(text: String, start: Int, builder: HeaderBuilder): Int {
        var elemEnd = start
        while (elemEnd < text.length && !isArrayElemStop(text[elemEnd])) elemEnd++
        var trimEnd = elemEnd
        while (trimEnd > start && isHorizSpace(text[trimEnd - 1])) trimEnd--
        val isNull = trimEnd - start == BASE64_CHUNK_LEN && text.regionMatches(start, "null", 0, BASE64_CHUNK_LEN)
        if (trimEnd > start && !isNull) {
            val redacted = redactAuthValueBody(text, start, trimEnd)
            if (redacted != null) {
                builder.appendRedacted(start, elemEnd, redacted + text.substring(trimEnd, elemEnd))
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
