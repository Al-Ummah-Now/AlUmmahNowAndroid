package net.alummahnow.core.bridge.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alummahnow.core.bridge.CoreError

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val accountId: String, val displayName: String) : AuthState
}

/** A pending device-flow sign-in for TVs (RFC 8628): show [userCode] and a QR code for [verificationUri]. */
data class DeviceCode(val userCode: String, val verificationUri: String, val expiresInSeconds: Int)

/** Facade for `aun-auth` (PKCE on phones, device flow on TV). Tokens never leave the core. */
interface AuthBridge {
    val state: StateFlow<AuthState>

    /** True once a native OAuth client is registered for this app (Epic 9.1 / 9.2). */
    val isSignInAvailable: Boolean

    suspend fun beginPkceSignIn(): String
    suspend fun beginDeviceSignIn(): DeviceCode
    suspend fun accessToken(): String?
    suspend fun signOut()
}

/**
 * Guest-only stand-in. Sign-in stays disabled until the backend registers native PKCE clients and the
 * device flow; there is deliberately no workaround (no web-view login, no shared secrets).
 */
class GuestOnlyAuthBridge : AuthBridge {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val isSignInAvailable: Boolean = false

    override suspend fun beginPkceSignIn(): String = throw CoreError.Unavailable("native OAuth client not registered (Epic 9.1)")
    override suspend fun beginDeviceSignIn(): DeviceCode = throw CoreError.Unavailable("device flow not live (Epic 9.2)")
    override suspend fun accessToken(): String? = null
    override suspend fun signOut() {
        mutableState.value = AuthState.SignedOut
    }
}
