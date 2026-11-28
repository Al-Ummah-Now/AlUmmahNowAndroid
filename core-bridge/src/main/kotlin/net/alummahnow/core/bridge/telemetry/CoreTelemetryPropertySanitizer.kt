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
    """(?<![A-Za-z0-9+.-])(?:[a-zA-Z][a-zA-Z0-9+.-]*(?:%3[aA]%2[fF]%2[fF]|%253[aA]%252[fF]%252[fF]))[^\s"'<>]+"""
)

private val multiWordSensitiveSegments = setOf(
    "fullname", "displayname", "firstname", "lastname", "givenname", "familyname",
    "username", "login", "address", "street", "streetaddress", "homeaddress",
    "passphrase", "pass", "password", "passwd", "pwd",
    "phone", "phonenumber", "mobile", "tel", "telephone",
    "card", "creditcard", "iban", "ssn", "dob", "birthdate", "dateofbirth", "passport"
)

private val windowsDriveRelativePathRegex = Regex(
    """(?<![A-Za-z0-9_:])[A-Za-z]:(?![/\\])(?!\d+:\d+)[A-Za-z_.][A-Za-z0-9_.\\/-]+"""
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

private val multiWordValueRegex = Regex(
    """(?<![A-Za-z0-9_])((?:[A-Za-z0-9_.-]|\\u[0-9A-Fa-f]{4}){2,64})""" +
        """((?:\\?["']|[\u201D\u2019\u00BB\u300D\u300F])?\s*[=:]\s*)""" +
        """(?![\"'\u201C\u2018\u00AB\u201E\u300C\u300E\\])([^\r\n,;}\]]+)"""
)

private val nextKeyValueBoundaryRegex = Regex("""\s+(?:[A-Za-z0-9_.-]{2,48}\s*[=:])""")

private fun redactPercentEncodedUrls(text: String): String {
    if (text.indexOf('%') < 0) return text
    return percentEncodedUrlRegex.replace(text) { m ->
        val raw = m.value
        if (raw.contains("%253", ignoreCase = true)) {
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

private fun needsQuotedPreRedaction(lowerKey: String, valText: String): Boolean {
    val first = valText.firstOrNull() ?: return false
    return first in "\"'\\\u201C\u2018\u00AB\u201E\u300C\u300E" ||
        lowerKey in CoreTelemetryAuthEscapedHelpers.unquotedAuthHeaders
}

private fun scrubUnquotedMultiWordSensitiveValues(
    text: String,
    prePass: Boolean,
    decode: (String) -> String,
    split: (String) -> List<String>
): String = multiWordValueRegex.replace(text) { m ->
    val key = m.groupValues[1]
    val decoded = decode(key)
    val lower = decoded.trim('"', '\'').lowercase(Locale.ROOT)
    val valText = m.groupValues[3]
    val isAuthHeader = lower == "authorization" || lower == "proxy-authorization"
    if (isAuthHeader || valText.startsWith("[redacted") || !isMultiWordSensitiveKey(split(decoded))) {
        m.value
    } else {
        val boundary = nextKeyValueBoundaryRegex.find(valText)
        val tail = if (boundary != null) valText.substring(boundary.range.first) else ""
        val repl = if (prePass && needsQuotedPreRedaction(lower, valText)) "\"[redacted]\"" else "[redacted]"
        key + m.groupValues[2] + repl + tail
    }
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
        "username", "login", "passport"
    )

    private val highValueKeySubstrings = listOf(
        "apikey", "accesskey", "secret", "token", "password", "passwd",
        "credential", "authorization", "privatekey", "cookie", "signature"
    )

    private val keyValueCredentialRegex = Regex(
        """(?<![A-Za-z0-9_])((?:[A-Za-z0-9_.-]|\\u[0-9A-Fa-f]{4}){2,64})""" +
            """((?:\\?["']|[\u201D\u2019\u00BB\u300D\u300F])?\s*[=:]\s*)""" +
            """(\[redacted(?:-[a-z]+)?\]|\\"(?:\\\\.|[^"\\])*\\"|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|""" +
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

    private fun scrubSensitiveKeyValues(text: String, prePass: Boolean): String =
        keyValueCredentialRegex.replace(text) { m ->
            val key = m.groupValues[1]
            val decoded = decodeJsonUnicodeEscapes(key)
            val lower = decoded.trim('"', '\'').lowercase(Locale.ROOT)
            val valText = m.groupValues[3]
            val skip = lower == "authorization" ||
                lower == "proxy-authorization" ||
                valText.startsWith("[redacted") ||
                valText == "\"[redacted]\""
            if (skip || !isSensitiveKeyName(decoded)) {
                m.value
            } else {
                val repl = if (prePass && needsQuotedPreRedaction(lower, valText)) "\"[redacted]\"" else "[redacted]"
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

    fun sanitizeDiagnosticText(text: String): String {
        var pre = redactPercentEncodedUrls(text)
        pre = scrubUnquotedMultiWordSensitiveValues(pre, true, ::decodeJsonUnicodeEscapes, ::splitKeySegments)
        pre = scrubSensitiveKeyValues(pre, true)
        var safe = CoreTelemetryService.sanitizeLocalPaths(pre)
        safe = scrubUnquotedMultiWordSensitiveValues(safe, false, ::decodeJsonUnicodeEscapes, ::splitKeySegments)
        safe = scrubSensitiveKeyValues(safe, false)
        safe = spaceDelimitedCredentialRegex.replace(safe, "$1[redacted]")
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
        val trimmed = key.trim()
        return CoreTelemetryAuthEscapedHelpers.matchCanonicalAuthHeaderName(trimmed) != null ||
            isSensitiveKeyName(trimmed)
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
