package net.alummahnow.core.bridge.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alummahnow.core.bridge.CoreError

/** For builds with no registered client (and previews/tests): browsing works, sign-in is hidden. */
class GuestOnlyAuthBridge : AuthBridge {
    override val state: StateFlow<AuthState> = MutableStateFlow<AuthState>(AuthState.SignedOut).asStateFlow()
    override val isSignInAvailable: Boolean = false

    override suspend fun beginPkceSignIn(): String = throw CoreError.Unavailable("no native OAuth client in this build")
    override suspend fun completePkceSignIn(
        callbackUri: String
    ): AuthState = throw CoreError.Unavailable("no native OAuth client in this build")
    override suspend fun beginDeviceSignIn(): DeviceCode = throw CoreError.Unavailable("no native OAuth client in this build")
    override suspend fun awaitDeviceSignIn(
        code: DeviceCode
    ): AuthState = throw CoreError.Unavailable("no native OAuth client in this build")
    override suspend fun accessToken(): String? = null
    override suspend fun signOut() = Unit
}
