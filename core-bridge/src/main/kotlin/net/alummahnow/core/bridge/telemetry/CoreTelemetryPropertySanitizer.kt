package net.alummahnow.core.bridge.telemetry

import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.truncate
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val percentEncodedUrlRegex = Regex(
    """(?<![A-Za-z0-9+.-])(?:[a-zA-Z][a-zA-Z0-9+.-]*(?:%(?:25)*3[aA]%(?:25)*2[fF]%(?:25)*2[fF]))[^\s"'<>]+"""
)

private val multiWordSensitiveSegments = setOf(
    "fullname", "displayname", "firstname", "lastname", "givenname", "familyname",
    "username", "login", "address", "street", "streetaddress", "homeaddress",
    "passphrase", "pass", "password", "passwd", "pwd",
    "phone", "phonenumber", "mobile", "tel", "telephone",
    "card", "creditcard", "iban", "ssn", "dob", "birthdate", "dateofbirth", "passport"
)

private val windowsDriveRelativePathRegex = Regex(
    """(?<![A-Za-z0-9_:])[A-Za-z]:(?![/\\])(?!\d+:\d+)""" +
        """(?:[\p{L}\p{M}_.][\p{L}\p{M}\p{Nd}_.\\/-]+|[^\u0000-\u007F\s,;}\]"'<>()]+)"""
)

private val spaceDelimitedCredentialRegex = Regex(
    """(?<![A-Za-z0-9_])((?:api[_-]?key|api[_-]?token|access[_-]?token|refresh[_-]?token|id[_-]?token|""" +
        """auth[_-]?token|session[_-]?token|security[_-]?token|client[_-]?secret|private[_-]?key|""" +
        """secret[_-]?key|access[_-]?key|verification[_-]?code|auth[_-]?code|recovery[_-]?code|""" +
        """passphrase|password|passwd|passcode|credential|credentials|token|secret|jwt|otp|pin|pwd)[ \t]+)""" +
        """(?!\[redacted(?:-[a-z]+)?\])(?!"\[redacted\]")""" +
        """(?:\\"(?:\\\\.|[^"\\])*\\"|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|""" +
        """\u201C[^\u201D\r\n]*\u201D|\u2018[^\u2019\r\n]*\u2019|[^\s,;}\]"'=:\u201C\u201D\u2018\u2019]+)""",
    RegexOption.IGNORE_CASE
)

private val knownPrefixCredentialRegex = Regex(
    """(?<![A-Za-z0-9_])(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|""" +
        """glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|""" +
        """(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{16,}|sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|""" +
        """(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|""" +
        """hf_[A-Za-z0-9]{20,}|npm_[A-Za-z0-9]{20,}|pypi-[A-Za-z0-9_-]{20,}|""" +
        """SG\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}|sq0(?:atp|csp)-[A-Za-z0-9_-]{20,})(?![A-Za-z0-9_])"""
)

private const val REDACTED_SENTINEL = "__AUN_REDACTED__"

private fun redactPercentEncodedUrls(text: String): String {
    if (text.indexOf('%') < 0) return text
    return percentEncodedUrlRegex.replace(text) { m ->
        val raw = m.value
        if (raw.contains("%25", ignoreCase = true)) {
            "[redacted-url]"
        } else {
            runCatching {
                val decoded = URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8.name())
                CoreTelemetryService.sanitizeLocalPaths(decoded)
            }.getOrDefault("[redacted-url]")
        }
    }
}

private fun isMultiWordSensitiveKey(segments: List<String>): Boolean {
    for (i in segments.indices) {
        if (segments[i] in multiWordSensitiveSegments) return true
        if (i + 1 < segments.size && (segments[i] + segments[i + 1]) in multiWordSensitiveSegments) return true
    }
    return false
}

private val multiWordKeySepRegex = Regex(
    """(?<![A-Za-z0-9_])((?:[A-Za-z0-9_.-]|\\u[0-9A-Fa-f]{4}){2,64})""" +
        """((?:\\?["']|[\u201D\u2019\u00BB\u300D\u300F])?\s*[=:]\s*)"""
)

private val nextKeyValueBoundaryRegex = Regex(
    """^(?:[ \t]+|[,;][ \t]*)(?:[A-Za-z0-9_.-]|\\u[0-9A-Fa-f]{4}){2,48}""" +
        """(?:\\?["']|[\u201D\u2019\u00BB\u300D\u300F])?\s*[=:]"""
)

private fun isMultiWordOpeningQuote(ch: Char): Boolean =
    ch in "\"'\\\u201C\u2018\u00AB\u201E\u300C\u300E"

private fun isMultiWordStopChar(ch: Char): Boolean =
    ch in "\r\n}]\"'\u201D\u2019\u00BB\u300D\u300F"

private fun isMultiWordEndAt(text: String, pos: Int): Boolean {
    val ch = text[pos]
    return isMultiWordStopChar(ch) ||
        (ch in " \t,;" && nextKeyValueBoundaryRegex.containsMatchIn(text.substring(pos)))
}

private fun scanMultiWordValueEnd(text: String, valStart: Int): Int {
    var valEnd = valStart
    while (valEnd < text.length && !isMultiWordEndAt(text, valEnd)) {
        valEnd++
    }
    return valEnd
}

private fun shouldScrubMultiWordKey(
    rawKey: String,
    text: String,
    valStart: Int,
    decode: (String) -> String,
    split: (String) -> List<String>
): Boolean {
    val decoded = decode(rawKey)
    val lower = decoded.trim('"', '\'').lowercase(Locale.ROOT)
    val isAuth = lower == "authorization" || lower == "proxy-authorization"
    val alreadyRedacted = text.startsWith("[redacted", valStart) || text.startsWith(REDACTED_SENTINEL, valStart)
    return !isAuth &&
        isMultiWordSensitiveKey(split(decoded)) &&
        !isMultiWordOpeningQuote(text[valStart]) &&
        !alreadyRedacted
}

private fun scrubUnquotedMultiWordSensitiveValues(
    text: String,
    replacement: String,
    decode: (String) -> String,
    split: (String) -> List<String>
): String {
    var sb: StringBuilder? = null
    var copied = 0
    var match = multiWordKeySepRegex.find(text, 0)
    while (match != null) {
        val valStart = match.range.last + 1
        var nextPos = valStart
        if (valStart < text.length && shouldScrubMultiWordKey(match.groupValues[1], text, valStart, decode, split)) {
            val valEnd = scanMultiWordValueEnd(text, valStart)
            if (valEnd > valStart) {
                val out = sb ?: StringBuilder(text.length).also { sb = it }
                out.append(text, copied, valStart).append(replacement)
                copied = valEnd
                nextPos = valEnd
            }
        }
        match = if (nextPos < text.length) multiWordKeySepRegex.find(text, nextPos) else null
    }
    val out = sb ?: return text
    return out.append(text, copied, text.length).toString()
}

private class ContainerScanState(val open: Char) {
    val close: Char = if (open == '[') ']' else '}'
    var depth: Int = 0
    var inQuote: Char = '\u0000'
}

private fun stepContainerQuote(text: String, pos: Int, state: ContainerScanState): Int {
    val ch = text[pos]
    if (ch == '\\' && pos + 1 < text.length) {
        if (state.inQuote == '\u0001' && text[pos + 1] == '"') state.inQuote = '\u0000'
        return pos + 2
    }
    if ((state.inQuote == '\u0001' && ch == '"') || ch == state.inQuote) state.inQuote = '\u0000'
    return pos + 1
}

private fun stepContainerUnquoted(text: String, pos: Int, state: ContainerScanState): Int {
    val ch = text[pos]
    if (ch == '\\' && pos + 1 < text.length && text[pos + 1] == '"') {
        state.inQuote = '\u0001'
        return pos + 2
    }
    when (ch) {
        '"', '\'' -> state.inQuote = ch
        state.open -> state.depth++
        state.close -> state.depth--
    }
    return pos + 1
}

private fun scanBalancedContainerEnd(text: String, start: Int): Int {
    val state = ContainerScanState(text[start])
    var pos = start
    while (pos < text.length) {
        pos = if (state.inQuote != '\u0000') {
            stepContainerQuote(text, pos, state)
        } else {
            stepContainerUnquoted(text, pos, state)
        }
        if (state.inQuote == '\u0000' && state.depth == 0) return pos
    }
    return text.length
}

private fun shouldScrubContainerKey(
    rawKey: String,
    text: String,
    valStart: Int,
    decode: (String) -> String,
    isSensitive: (String) -> Boolean
): Boolean {
    val open = text[valStart]
    if (open != '[' && open != '{') return false
    if (text.startsWith("[redacted", valStart)) return false
    val decoded = decode(rawKey)
    val lower = decoded.trim('"', '\'').lowercase(Locale.ROOT)
    return lower != "authorization" && lower != "proxy-authorization" && isSensitive(decoded)
}

private fun scrubSensitiveContainerValues(
    text: String,
    replacement: String,
    decode: (String) -> String,
    isSensitive: (String) -> Boolean
): String {
    if (text.indexOf('[') < 0 && text.indexOf('{') < 0) return text
    var sb: StringBuilder? = null
    var copied = 0
    var match = multiWordKeySepRegex.find(text, 0)
    while (match != null) {
        val valStart = match.range.last + 1
        var nextPos = valStart
        val matchKey = match.groupValues[1]
        if (valStart < text.length && shouldScrubContainerKey(matchKey, text, valStart, decode, isSensitive)) {
            val valEnd = scanBalancedContainerEnd(text, valStart)
            val out = sb ?: StringBuilder(text.length).also { sb = it }
            out.append(text, copied, valStart).append('"').append(replacement).append('"')
            copied = valEnd
            nextPos = valEnd
        }
        match = if (nextPos < text.length) multiWordKeySepRegex.find(text, nextPos) else null
    }
    val out = sb ?: return text
    return out.append(text, copied, text.length).toString()
}

private const val MIN_OPAQUE_URL_SEGMENT_LEN = 32
private const val UUID_SEGMENT_LEN = 36

private fun parseUrlHexByte(hi: Char, lo: Char): Int? {
    val h = hi.digitToIntOrNull(16) ?: return null
    val l = lo.digitToIntOrNull(16) ?: return null
    return (h shl 4) or l
}

private fun decodeUrlSegmentFull(raw: String): Pair<String, IntArray> {
    val sb = StringBuilder(raw.length)
    val offsets = IntArray(raw.length + 1)
    var outLen = 0
    var i = 0
    while (i < raw.length) {
        offsets[outLen++] = i
        val decodedByte = if (raw[i] == '%' && i + 2 < raw.length) {
            parseUrlHexByte(raw[i + 1], raw[i + 2])
        } else {
            null
        }
        val decodable = decodedByte != null &&
            decodedByte in 0x21..0x7E &&
            decodedByte != '?'.code &&
            decodedByte != '#'.code
        if (decodable) {
            sb.append(decodedByte!!.toChar())
            i += 3
        } else {
            sb.append(raw[i])
            i++
        }
    }
    offsets[outLen] = raw.length
    return sb.toString() to offsets
}

private fun isUuidSegment(seg: String): Boolean {
    if (seg.length != UUID_SEGMENT_LEN) return false
    for (i in seg.indices) {
        val c = seg[i]
        val isDash = i == 8 || i == 13 || i == 18 || i == 23
        val ok = if (isDash) c == '-' else c.digitToIntOrNull(16) != null
        if (!ok) return false
    }
    return true
}

private fun isOpaqueUrlSecretSegment(seg: String): Boolean {
    if (seg.length < MIN_OPAQUE_URL_SEGMENT_LEN || isUuidSegment(seg)) return false
    var hasLetter = false
    var hasDigit = false
    for (c in seg) {
        when {
            c in 'A'..'Z' || c in 'a'..'z' -> hasLetter = true
            c in '0'..'9' -> hasDigit = true
            c !in "-_+/=" -> return false
        }
    }
    return hasLetter && hasDigit
}

private const val MIN_PHONE_URL_DIGITS = 9
private const val MIN_FORMATTED_PHONE_URL_DIGITS = 8
private val urlIsoDateExactRegex = Regex(
    """^(?:\d{4}[-/.]\d{2}[-/.]\d{2}|\d{2}[-/.]\d{2}[-/.]\d{4})$"""
)

private fun isDecodedPhoneUrlSegment(seg: String): Boolean {
    if (seg.length < MIN_FORMATTED_PHONE_URL_DIGITS) return false
    var digits = 0
    var hasFormatting = false
    for (c in seg) {
        when {
            c in '0'..'9' -> digits++
            c in "+-(). " -> if (c != '.') hasFormatting = true
            else -> return false
        }
    }
    return digits >= MIN_PHONE_URL_DIGITS ||
        (digits == MIN_FORMATTED_PHONE_URL_DIGITS && hasFormatting && !urlIsoDateExactRegex.matches(seg))
}

private fun isDecodedSensitiveUrlSegment(seg: String): Boolean =
    isOpaqueUrlSecretSegment(seg) ||
        isDecodedPhoneUrlSegment(seg) ||
        CoreTelemetryPropertySanitizer.matchesIpLiteral(seg)

private fun collectOpaqueSegmentSpans(rawSeg: String, baseOffset: Int): List<IntRange> {
    val (decoded, offsets) = decodeUrlSegmentFull(rawSeg)
    if (!decoded.startsWith("[redacted") && isDecodedSensitiveUrlSegment(decoded)) {
        return listOf(baseOffset until (baseOffset + rawSeg.length))
    }
    if (decoded.indexOf('/') < 0) return emptyList()
    val spans = ArrayList<IntRange>()
    var subStart = 0
    while (subStart < decoded.length) {
        val slash = decoded.indexOf('/', subStart)
        val subEnd = if (slash < 0) decoded.length else slash
        val sub = decoded.substring(subStart, subEnd)
        if (sub.isNotEmpty() && !sub.startsWith("[redacted") && isDecodedSensitiveUrlSegment(sub)) {
            spans.add((baseOffset + offsets[subStart]) until (baseOffset + offsets[subEnd]))
        }
        subStart = if (slash < 0) decoded.length else slash + 1
    }
    return spans
}

private fun findUrlPathBounds(url: String): IntRange? {
    if (url.indexOf('%') < 0) return null
    val schemeIdx = url.indexOf("://")
    val pathSlash = if (schemeIdx >= 0) url.indexOf('/', schemeIdx + 3) else -1
    if (pathSlash < 0) return null
    val qf = url.indexOfAny(charArrayOf('?', '#'), pathSlash)
    val pathEnd = if (qf < 0) url.length else qf
    return (pathSlash + 1) until pathEnd
}

private fun collectOpaqueSpansInUrl(url: String, pathRange: IntRange): List<IntRange> {
    val spans = ArrayList<IntRange>()
    val pathEnd = pathRange.last + 1
    var segStart = pathRange.first
    while (segStart < pathEnd) {
        val slash = url.indexOf('/', segStart)
        val segEnd = if (slash < 0 || slash > pathEnd) pathEnd else slash
        val semi = url.indexOf(';', segStart)
        val coreEnd = if (semi < 0 || semi > segEnd) segEnd else semi
        val rawSeg = url.substring(segStart, coreEnd)
        if (rawSeg.indexOf('%') >= 0) {
            spans.addAll(collectOpaqueSegmentSpans(rawSeg, segStart))
        }
        segStart = if (segEnd >= pathEnd) pathEnd else segEnd + 1
    }
    return spans
}

internal fun redactEncodedOpaqueSegmentsInUrl(url: String): String {
    val pathRange = findUrlPathBounds(url) ?: return url
    val spans = collectOpaqueSpansInUrl(url, pathRange)
    if (spans.isEmpty()) return url
    val sb = StringBuilder(url.length)
    var copied = 0
    for (span in spans) {
        if (span.first >= copied) {
            sb.append(url, copied, span.first).append("[redacted]")
            copied = span.last + 1
        }
    }
    return sb.append(url, copied, url.length).toString()
}

internal object CoreTelemetryPropertySanitizer {
    private const val MIN_PHONE_DIGITS = 9
    private const val MIN_FORMATTED_PHONE_DIGITS = 8
    private const val MIN_SENSITIVE_INT_MAGNITUDE = 100_000_000L
    private val minSensitiveBigDecimal = BigDecimal.valueOf(MIN_SENSITIVE_INT_MAGNITUDE)
    private val minSensitiveBigInteger = BigInteger.valueOf(MIN_SENSITIVE_INT_MAGNITUDE)

    private const val IPV4_OCTET = """(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""
    private const val IPV4_CORE = """(?:$IPV4_OCTET\.){3}$IPV4_OCTET"""

    private val ipv4InKeyRegex = Regex(
        """(?<![A-Za-z0-9_.])$IPV4_CORE(?![A-Za-z0-9_]|\.\d)"""
    )
    private val ipv6InKeyRegex = Regex(
        """(?<![A-Za-z0-9_])(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|""" +
            """(?:[0-9a-fA-F]{1,4}:){6}$IPV4_CORE|""" +
            """(?:[0-9a-fA-F]{1,4}:){1,7}:(?:(?:[0-9a-fA-F]{1,4}:){0,4}$IPV4_CORE|""" +
            """[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,5})?|""" +
            """::(?:(?:[0-9a-fA-F]{1,4}:){0,5}$IPV4_CORE|""" +
            """[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6}))(?:%[A-Za-z0-9_.-]+)?""" +
            """(?![A-Za-z0-9_]|:[0-9a-fA-F])"""
    )
    private val phoneInKeyRegex = Regex(
        """(?<![A-Za-z0-9])\+?\p{Nd}[\p{Nd}\p{Zs}\t().\p{Pd}\u2212]{6,}\p{Nd}(?![A-Za-z0-9])"""
    )
    private val isoDateExactRegex = Regex(
        """^(?:\d{4}[-/.]\d{2}[-/.]\d{2}|\d{2}[-/.]\d{2}[-/.]\d{4})$"""
    )
    private val opaqueSecretInKeyRegex = Regex(
        """(?<![A-Za-z0-9_])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9_])"""
    )
    private val uuidRegex = Regex(
        """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"""
    )

    private val sensitiveSegments = setOf(
        "token", "secret", "jwt", "bearer", "password", "passwd", "pwd", "pass", "passphrase",
        "credential", "credentials", "otp", "pin", "passcode", "cookie",
        "authorization", "auth", "signature", "sig", "apikey", "accesskey", "key", "privatekey",
        "secretkey", "session", "sessionid", "sessionkey", "codeverifier",
        "verificationcode", "authcode", "recoverycode", "clientsecret",
        "accesstoken", "refreshtoken", "idtoken", "sessiontoken", "securitytoken",
        "apitoken", "authtoken",
        "email", "mail", "phone", "phonenumber", "msisdn", "mobile", "tel", "telephone",
        "ssn", "ip", "ipaddress", "address", "street", "streetaddress", "homeaddress",
        "card", "creditcard", "cvv", "cvc", "iban", "pan",
        "dob", "birthdate", "dateofbirth",
        "firstname", "lastname", "fullname", "displayname", "givenname", "familyname",
        "username", "login", "passport",
        "userid", "accountid", "memberid", "profileid", "customerid",
        "personid", "subscriberid", "deviceid", "uid", "handle"
    )

    private val highValueKeySubstrings = listOf(
        "apikey", "accesskey", "secret", "token", "password", "passwd",
        "credential", "authorization", "privatekey", "cookie", "signature"
    )

    private val keyValueCredentialRegex = Regex(
        """(?<![A-Za-z0-9_])((?:[A-Za-z0-9_.-]|\\u[0-9A-Fa-f]{4}){2,64})""" +
            """((?:\\?["']|[\u201D\u2019\u00BB\u300D\u300F])?\s*[=:]\s*)""" +
            """(\[redacted(?:-[a-z]+)?\]|__AUN_REDACTED__|\"__AUN_REDACTED__\"|\\"(?:\\\\.|[^"\\])*\\"|""" +
            """"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|""" +
            """\u201C[^\u201D\r\n]*\u201D|\u2018[^\u2019\r\n]*\u2019|\u00AB[^\u00BB\r\n]*\u00BB|""" +
            """\u201E[^\u201D\r\n]*\u201D|\u300C[^\u300D\r\n]*\u300D|\u300E[^\u300F\r\n]*\u300F|""" +
            """[^\s,;}\]"'\u201C\u201D\u2018\u2019\u00AB\u00BB\u201E\u300C\u300D\u300E\u300F]+)"""
    )

    internal fun isSensitiveKeyName(key: String): Boolean {
        val squashed = buildString(key.length) {
            for (c in key) {
                if (c.isLetterOrDigit()) append(c.lowercaseChar())
            }
        }
        if (highValueKeySubstrings.any { squashed.contains(it) }) return true
        val segments = splitKeySegments(key)
        for (i in segments.indices) {
            if (segments[i] in sensitiveSegments) return true
            if (i + 1 < segments.size && (segments[i] + segments[i + 1]) in sensitiveSegments) return true
        }
        return false
    }

    private fun decodeJsonUnicodeEscapes(raw: String): String {
        if (!raw.contains("\\u")) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\\' && i + 5 < raw.length && raw[i + 1] == 'u') {
                val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString()
    }

    private fun scrubSensitiveKeyValues(text: String, replacement: String): String =
        keyValueCredentialRegex.replace(text) { m ->
            val key = m.groupValues[1]
            val decoded = decodeJsonUnicodeEscapes(key)
            val lower = decoded.trim('"', '\'').lowercase(Locale.ROOT)
            val valText = m.groupValues[3]
            val skip = lower == "authorization" ||
                lower == "proxy-authorization" ||
                valText.startsWith("[redacted") ||
                valText == "\"[redacted]\"" ||
                valText == REDACTED_SENTINEL ||
                valText == "\"$REDACTED_SENTINEL\""
            if (skip || !isSensitiveKeyName(decoded)) {
                m.value
            } else {
                val preserveQuotes = valText.length >= 2 && valText.startsWith("\"") && valText.endsWith("\"")
                val repl = if (preserveQuotes) "\"$replacement\"" else replacement
                key + m.groupValues[2] + repl
            }
        }

    private fun isRedactedPhoneMatch(match: String): Boolean {
        val digits = match.codePoints().filter { Character.isDigit(it) }.count()
        if (digits >= MIN_PHONE_DIGITS) return true
        if (digits < MIN_FORMATTED_PHONE_DIGITS || isoDateExactRegex.matches(match)) return false
        return match.startsWith("+") || match.codePoints().anyMatch(::isPhoneFormatCodePoint)
    }

    private fun isPhoneFormatCodePoint(cp: Int): Boolean {
        val type = Character.getType(cp)
        return cp == '('.code ||
            cp == ')'.code ||
            cp == '\u2212'.code ||
            type == Character.SPACE_SEPARATOR.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt()
    }

    internal fun matchesIpLiteral(text: String): Boolean =
        ipv4InKeyRegex.containsMatchIn(text) || ipv6InKeyRegex.containsMatchIn(text)

    fun sanitizeDiagnosticText(text: String): String {
        var pre = redactPercentEncodedUrls(text)
        pre = scrubSensitiveContainerValues(pre, REDACTED_SENTINEL, ::decodeJsonUnicodeEscapes, ::isSensitiveKeyName)
        pre = scrubUnquotedMultiWordSensitiveValues(pre, REDACTED_SENTINEL, ::decodeJsonUnicodeEscapes, ::splitKeySegments)
        pre = scrubSensitiveKeyValues(pre, REDACTED_SENTINEL)
        var safe = CoreTelemetryService.sanitizeLocalPaths(pre).replace(REDACTED_SENTINEL, "[redacted]")
        safe = scrubSensitiveContainerValues(safe, "[redacted]", ::decodeJsonUnicodeEscapes, ::isSensitiveKeyName)
        safe = scrubUnquotedMultiWordSensitiveValues(safe, "[redacted]", ::decodeJsonUnicodeEscapes, ::splitKeySegments)
        safe = scrubSensitiveKeyValues(safe, "[redacted]")
        safe = spaceDelimitedCredentialRegex.replace(safe, "$1[redacted]")
        safe = knownPrefixCredentialRegex.replace(safe, "[redacted]")
        safe = ipv6InKeyRegex.replace(safe, "[redacted-ip]")
        safe = ipv4InKeyRegex.replace(safe, "[redacted-ip]")
        safe = windowsDriveRelativePathRegex.replace(safe, "[redacted-path]")
        safe = phoneInKeyRegex.replace(safe) { m ->
            if (isRedactedPhoneMatch(m.value)) "[redacted-phone]" else m.value
        }
        return safe
    }

    fun sanitizePropertyKey(key: String): String {
        var safe = sanitizeDiagnosticText(key)
        safe = CoreTelemetryAuthEscapedHelpers.redactJwtTokens(safe)
        safe = opaqueSecretInKeyRegex.replace(safe) { m ->
            val v = m.value
            val hasLetter = v.any { it in 'a'..'z' || it in 'A'..'Z' }
            val hasDigit = v.any { it in '0'..'9' }
            if (hasLetter && hasDigit && !uuidRegex.matches(v)) "[redacted]" else v
        }
        return safe
    }

    fun serializeProperties(properties: Map<String, Any?>): String? {
        if (properties.isEmpty()) return null
        val usedKeys = HashSet<String>(properties.size)
        val nextSuffixByBase = HashMap<String, Int>()
        val obj = buildJsonObject {
            for ((key, value) in properties) {
                if (key.isNotBlank() && value != null) {
                    putSanitizedProperty(key, value, usedKeys, nextSuffixByBase)
                }
            }
        }
        return if (obj.isEmpty()) null else obj.toString()
    }

    private fun isAuthHeaderPropertyKey(key: String): Boolean {
        val decoded = decodeJsonUnicodeEscapes(key).trim()
        return CoreTelemetryAuthEscapedHelpers.matchCanonicalAuthHeaderName(decoded) != null ||
            isSensitiveKeyName(decoded)
    }

    private fun splitKeySegments(key: String): List<String> {
        val result = ArrayList<String>()
        val cur = StringBuilder()
        for (i in key.indices) {
            val c = key[i]
            if (!c.isLetterOrDigit()) {
                if (cur.isNotEmpty()) {
                    result.add(cur.toString().lowercase(Locale.ROOT))
                    cur.setLength(0)
                }
            } else {
                val boundary = cur.isNotEmpty() && isCamelBoundary(key, i)
                if (boundary) {
                    result.add(cur.toString().lowercase(Locale.ROOT))
                    cur.setLength(0)
                }
                cur.append(c)
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString().lowercase(Locale.ROOT))
        return result
    }

    private fun isCamelBoundary(key: String, idx: Int): Boolean {
        val c = key[idx]
        if (!c.isUpperCase() || idx == 0) return false
        val prev = key[idx - 1]
        val nextLower = idx + 1 < key.length && key[idx + 1].isLowerCase()
        return prev.isLowerCase() || prev.isDigit() || (prev.isUpperCase() && nextLower)
    }

    private fun resolveUniquePropertyKey(
        usedKeys: MutableSet<String>,
        nextSuffixByBase: MutableMap<String, Int>,
        base: String
    ): String {
        if (usedKeys.add(base)) return base
        var suffix = nextSuffixByBase[base] ?: 2
        while (!usedKeys.add("$base#$suffix")) suffix++
        nextSuffixByBase[base] = suffix + 1
        return "$base#$suffix"
    }

    private fun isSensitiveLong(value: Long): Boolean =
        value == Long.MIN_VALUE || abs(value) >= MIN_SENSITIVE_INT_MAGNITUDE

    private fun isSensitiveIntegralDouble(value: Double): Boolean =
        abs(value) >= MIN_SENSITIVE_INT_MAGNITUDE && truncate(value) == value

    private fun JsonObjectBuilder.putSanitizedProperty(
        key: String,
        value: Any,
        usedKeys: MutableSet<String>,
        nextSuffixByBase: MutableMap<String, Int>
    ) {
        val safeKey = sanitizePropertyKey(key)
        if (safeKey.isBlank()) return
        val uniqueKey = resolveUniquePropertyKey(usedKeys, nextSuffixByBase, safeKey)
        if (isAuthHeaderPropertyKey(key)) {
            put(uniqueKey, JsonPrimitive("[redacted]"))
            return
        }
        putSanitizedValue(uniqueKey, value)
    }

    private fun isSensitiveBigDecimal(value: BigDecimal): Boolean {
        val absVal = value.abs()
        if (absVal < minSensitiveBigDecimal) return false
        if (absVal.scale() <= 0) return true
        return absVal.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0
    }

    private fun JsonObjectBuilder.putSanitizedBigDecimal(uniqueKey: String, value: BigDecimal) {
        if (isSensitiveBigDecimal(value)) {
            put(uniqueKey, JsonPrimitive("[redacted-number]"))
            return
        }
        val d = value.toDouble()
        if (d.isFinite()) {
            put(
                uniqueKey,
                if (isSensitiveIntegralDouble(d)) JsonPrimitive("[redacted-number]") else JsonPrimitive(d)
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun safeToString(value: Any): String? = try {
        value.toString()
    } catch (_: Exception) {
        null
    }

    private fun JsonObjectBuilder.putSanitizedValue(uniqueKey: String, value: Any) {
        when (value) {
            is String -> put(uniqueKey, JsonPrimitive(sanitizeDiagnosticText(value)))
            is Boolean -> put(uniqueKey, JsonPrimitive(value))
            is Int -> put(
                uniqueKey,
                if (isSensitiveLong(value.toLong())) JsonPrimitive("[redacted-number]") else JsonPrimitive(value)
            )
            is Long -> put(
                uniqueKey,
                if (isSensitiveLong(value)) JsonPrimitive("[redacted-number]") else JsonPrimitive(value)
            )
            is BigDecimal -> putSanitizedBigDecimal(uniqueKey, value)
            is BigInteger -> put(
                uniqueKey,
                if (value.abs() >= minSensitiveBigInteger) {
                    JsonPrimitive("[redacted-number]")
                } else {
                    JsonPrimitive(value.toLong())
                }
            )
            is Double -> if (value.isFinite()) {
                put(
                    uniqueKey,
                    if (isSensitiveIntegralDouble(value)) JsonPrimitive("[redacted-number]") else JsonPrimitive(value)
                )
            }
            is Float -> if (value.isFinite()) {
                val d = value.toDouble()
                put(
                    uniqueKey,
                    if (isSensitiveIntegralDouble(d)) JsonPrimitive("[redacted-number]") else JsonPrimitive(d)
                )
            }
            else -> safeToString(value)?.let { put(uniqueKey, JsonPrimitive(sanitizeDiagnosticText(it))) }
        }
    }
}
