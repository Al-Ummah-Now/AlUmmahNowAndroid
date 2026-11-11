package net.alummahnow.core.bridge.studio

import kotlinx.coroutines.flow.StateFlow

/** How a browser sign-in starts. */
enum class SignInMode {
    /** Silent when the browser is already signed in to Al Ummah Now, else the form. */
    DEFAULT,

    /** Always the form: "Add another account". */
    ADD_ACCOUNT,

    /** No UI: re-authorize after the refresh token was rejected. */
    SILENT
}

/**
 * Accounts and sign-in (aun-auth: PKCE S256 in Custom Tabs, tokens in the Android Keystore).
 * Multiple accounts can be signed in; one is active.
 */
interface AccountSession {
    val accounts: StateFlow<List<StudioAccount>>
    val active: StateFlow<StudioAccount?>

    /** The authorization URL to open in a Custom Tab. */
    fun beginSignIn(mode: SignInMode): String

    /** Finishes sign-in with the redirect the app received (`alummahnow-studio://auth/callback?...`). */
    suspend fun completeSignIn(redirectUrl: String): StudioAccount

    fun switchTo(userId: String)

    fun setActiveChannel(channelId: String?)

    /**
     * Signs [userId] out on this device and revokes its refresh token at the auth server.
     * Returns the auth server's logout page, which the app opens in the same browser session so
     * the next sign-in is not silent as this account.
     */
    suspend fun signOut(userId: String): String

    /** A fresh access token for the active account, or null when signed out. */
    suspend fun accessToken(): String?
}
