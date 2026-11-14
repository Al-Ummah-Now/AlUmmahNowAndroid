package net.alummahnow.core.bridge.auth.passkey

/**
 * Native passkeys (AUTH-PASSKEYS, "Native apps"): the app calls the platform passkey API itself (Credential Manager
 * on Android), so Google Password Manager, 1Password and other providers, security keys and a phone by QR all work
 * without a browser. The browser sign-in (Custom Tabs + PKCE) stays as "Sign in another way".
 */

/** A server challenge: [optionsJson] is the WebAuthn options JSON to hand to the platform as-is. */
data class PasskeyChallenge(val challengeId: String, val optionsJson: String)

/** A passkey the server stored for the signed-in account. */
data class RegisteredPasskey(val id: String, val name: String?, val createdAt: String?)

/**
 * The platform passkey API (Credential Manager on Android). A seam: tests pass a fake. Both calls take the
 * server's options JSON and return the platform's response JSON (`AuthenticationResponseJSON` /
 * `RegistrationResponseJSON`). They throw [PasskeyError] ([PasskeyError.Cancelled] when the person closed the sheet).
 */
interface PasskeyAuthenticator {
    /** False where this device can't use passkeys at all; the app then hides the passkey buttons. */
    val isSupported: Boolean

    suspend fun signIn(requestJson: String): String

    suspend fun create(requestJson: String): String
}

/** What can go wrong with a passkey, from the platform or the auth server. Network failures stay [net.alummahnow.core.bridge.CoreError]. */
sealed class PasskeyError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The person closed the OS sheet. The app says nothing. */
    class Cancelled : PasskeyError("cancelled")

    /** No passkey for this app on the device or in any provider. The app offers "Sign in another way". */
    class NoCredential : PasskeyError("no credential")

    /** This device or provider can't use or save passkeys. */
    class Unsupported(cause: Throwable? = null) : PasskeyError("unsupported", cause)

    /** Registration: this provider already holds a passkey for the account (`excludeCredentials`). */
    class AlreadyRegistered : PasskeyError("already registered")

    /** Registration: the account's last sign-in is too old (`auth_time` over 15 minutes). Sign in again, then retry. */
    class StepUpRequired : PasskeyError("step_up_required")

    /** 429. [retryAfterSeconds] from `Retry-After` when the server sent it. */
    class RateLimited(val retryAfterSeconds: Long?) : PasskeyError("rate limited")

    /** The server refused the request or the passkey ([code] is its `error`). */
    class Rejected(val code: String?, val status: Int) : PasskeyError("rejected: $code ($status)")

    /** Anything else from the platform (bad options, provider failure). */
    class Failed(cause: Throwable? = null) : PasskeyError("failed", cause)
}
