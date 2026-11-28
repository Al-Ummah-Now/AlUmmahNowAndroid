package net.alummahnow.core.bridge.telemetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.truncate
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal object CoreTelemetryPropertySanitizer {
    private const val MIN_PHONE_DIGITS = 9
    private const val MIN_SENSITIVE_INT_MAGNITUDE = 100_000_000L

    private val jwtInKeyRegex = Regex(
        """(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?![A-Za-z0-9_-])"""
    )
    private val ipv4InKeyRegex = Regex(
        """(?<![A-Za-z0-9_.])(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}""" +
            """(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?![A-Za-z0-9_.])"""
    )
    private val ipv6InKeyRegex = Regex(
        """(?<![A-Za-z0-9_:])(?:(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|""" +
            """(?:[0-9a-fA-F]{1,4}:){1,7}:(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,5})?|""" +
            """::(?:[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{1,4}){0,6}))(?:%[A-Za-z0-9_.-]+)?(?![A-Za-z0-9_:])"""
    )
    private val phoneInKeyRegex = Regex(
        """(?<![A-Za-z0-9_])\+?\p{Nd}[\p{Nd}\s().-]{7,}\p{Nd}(?![A-Za-z0-9_])"""
    )
    private val opaqueSecretInKeyRegex = Regex(
        """(?<![A-Za-z0-9_])[A-Za-z0-9+/=_-]{32,}(?![A-Za-z0-9_])"""
    )
    private val uuidRegex = Regex(
        """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"""
    )

    private val exactSensitiveKeys = setOf("token", "secret", "jwt", "bearer")
    private val sensitiveKeySuffixes = listOf(
        "_token",
        "-token",
        "_secret",
        "-secret",
        "_password",
        "-password",
        "_credential",
        "-credential"
    )

    fun sanitizeDiagnosticText(text: String): String {
        var safe = CoreTelemetryService.sanitizeLocalPaths(text)
        safe = ipv4InKeyRegex.replace(safe, "[redacted-ip]")
        safe = ipv6InKeyRegex.replace(safe, "[redacted-ip]")
        safe = phoneInKeyRegex.replace(safe) { m ->
            val digits = m.value.count { Character.isDigit(it) }
            if (digits >= MIN_PHONE_DIGITS) "[redacted-phone]" else m.value
        }
        return safe
    }

    fun sanitizePropertyKey(key: String): String {
        var safe = sanitizeDiagnosticText(key)
        safe = jwtInKeyRegex.replace(safe, "[redacted-token]")
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
        if (CoreTelemetryAuthEscapedHelpers.matchCanonicalAuthHeaderName(trimmed) != null) return true
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower in exactSensitiveKeys || sensitiveKeySuffixes.any { lower.endsWith(it) }
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
            else -> put(uniqueKey, JsonPrimitive(sanitizeDiagnosticText(value.toString())))
        }
    }
}
