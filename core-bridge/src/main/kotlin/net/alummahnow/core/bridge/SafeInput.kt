package net.alummahnow.core.bridge

import java.net.URI
import java.net.URISyntaxException

/** Shared input-validation and log-sanitization helpers used across native Android modules. */
object SafeInput {
    private val HOSTNAME = Regex(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*$"
    )
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val BEARER = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9\\-._~+/]+=*")
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}(?:\\.[A-Za-z0-9_-]*)?")
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val SECRET_PARAM = Regex(
        "(?i)\\b(access_token|refresh_token|id_token|token|code|code_verifier|state|password|" +
            "secret|signature|authorizationsignature)(\"?\\s*[:=]\\s*\"?)([^&\\s\",;]+)"
    )
    private val UNSAFE_LOG_CHARS = setOf('\u007F', '\u2028', '\u2029')

    fun isSafeHostname(host: String): Boolean {
        val lower = host.trim().lowercase()
        return lower.length in 1..MAX_HOST_LEN && HOSTNAME.matches(lower)
    }

    fun isSafeId(value: String): Boolean = SAFE_ID.matches(value)

    fun isSafeFileName(name: String): Boolean =
        name.isNotEmpty() &&
            name != "." &&
            name != ".." &&
            name.none { it == '/' || it == '\\' || it < ' ' || it == '\u007F' }

    /** Validates one dynamic URL path segment (rejects empty, dot-only, separators and control chars). */
    fun requirePathSegment(value: String, name: String): String {
        require(
            value.isNotEmpty() &&
                !value.all { it == '.' } &&
                value.none { it == '/' || it == '\\' || it == '?' || it == '#' || it < ' ' || it == '\u007F' }
        ) { "invalid $name" }
        return value
    }

    /** True for absolute `https://` URLs (or `http://` loopback for local dev/tests), without userinfo or control chars. */
    fun isSafeBrowserUrl(url: String): Boolean {
        val trimmed = url.trim()
        val uri = if (trimmed.isEmpty() || trimmed.any { it < ' ' || it == '\u007F' }) {
            null
        } else {
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                null
            }
        }
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        return uri != null &&
            uri.rawUserInfo == null &&
            !host.isNullOrEmpty() &&
            when (scheme) {
                "https" -> true
                "http" -> isLoopbackHost(host)
                else -> false
            }
    }

    fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h == "127.0.0.1" || h == "10.0.2.2" || h == "[::1]" || h == "::1"
    }

    /** Scrubs secrets/PII and replaces CR/LF and control characters so untrusted text cannot forge log lines. */
    fun sanitizeLogLine(text: String): String = scrubSecrets(text).map { ch ->
        if (ch < ' ' || ch in UNSAFE_LOG_CHARS) ' ' else ch
    }.joinToString("")

    fun scrubSecrets(text: String): String {
        if (text.isEmpty()) return text
        var s = BEARER.replace(text, "$1 [redacted]")
        s = JWT.replace(s, "[redacted]")
        s = SECRET_PARAM.replace(s, "$1$2[redacted]")
        s = EMAIL.replace(s, "[email]")
        return s
    }

    private const val MAX_HOST_LEN = 253
}
