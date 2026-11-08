package net.alummahnow.core.bridge.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 helpers: a 43-char verifier from 32 random bytes and its S256 challenge. */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun randomToken(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(b64::encodeToString)

    fun challenge(verifier: String): String = b64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )
}
