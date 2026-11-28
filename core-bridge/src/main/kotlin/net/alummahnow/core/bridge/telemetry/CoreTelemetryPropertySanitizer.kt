package net.alummahnow.core.bridge.telemetry

import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlin.math.abs
import kotlin.math.truncate
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

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
        """(?<![A-Za-z0-9_])([A-Za-z0-9_.-]{2,64})((?:\\?["'])?\s*[=:]\s*)""" +
            """(?:\[redacted(?:-[a-z]+)?\]|\\"(?:\\\\.|[^"\\])*\\"|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^\s,;}\]"']+)"""
    )

    private fun isSensitiveKeyName(key: String): Boolean {
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

    private fun scrubSensitiveKeyValues(text: String): String =
        keyValueCredentialRegex.replace(text) { m ->
            val key = m.groupValues[1]
            val lower = key.lowercase(Locale.ROOT)
            if (lower == "authorization" || lower == "proxy-authorization") {
                m.value
            } else if (isSensitiveKeyName(key)) {
                key + m.groupValues[2] + "[redacted]"
            } else {
                m.value
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
        val pre = scrubSensitiveKeyValues(text)
        var safe = CoreTelemetryService.sanitizeLocalPaths(pre)
        safe = scrubSensitiveKeyValues(safe)
        safe = ipv6InKeyRegex.replace(safe, "[redacted-ip]")
        safe = ipv4InKeyRegex.replace(safe, "[redacted-ip]")
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
