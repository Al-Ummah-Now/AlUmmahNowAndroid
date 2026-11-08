package net.alummahnow.core.bridge.auth

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val accountId: String, val displayName: String) : AuthState
}

/**
 * A pending device-flow sign-in for TVs (RFC 8628): show [userCode] and a QR code for
 * [verificationUriComplete] (falls back to [verificationUri] when the server sends no complete URI).
 */
data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSeconds: Int,
    internal val deviceCode: String = "",
    internal val intervalSeconds: Int = 5
)

/** Facade for sign-in: PKCE on phones (RFC 7636 + RFC 8252), device flow on TV (RFC 8628). */
interface AuthBridge {
    val state: StateFlow<AuthState>

    /** False when this build has no registered native OAuth client. */
    val isSignInAvailable: Boolean

    /** Starts a PKCE sign-in and returns the authorize URL to open in a Custom Tab. */
    suspend fun beginPkceSignIn(): String

    /** Finishes the PKCE sign-in from the redirect URI the browser handed back. */
    suspend fun completePkceSignIn(callbackUri: String): AuthState

    suspend fun beginDeviceSignIn(): DeviceCode

    /** Polls the token endpoint until the user approves [code] on another device, denies it, or it expires. */
    suspend fun awaitDeviceSignIn(code: DeviceCode): AuthState

    /** A valid access token (refreshed when needed), or null when signed out. */
    suspend fun accessToken(): String?
    suspend fun signOut()
}
