package net.alummahnow.core.bridge.studio.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.alummahnow.core.AccountInfo
import net.alummahnow.core.AuthConfig
import net.alummahnow.core.AuthEvent
import net.alummahnow.core.AuthListener
import net.alummahnow.core.AuthManager
import net.alummahnow.core.LoginPrompt
import net.alummahnow.core.SecureStorage
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.auth.passkey.PasskeyApi
import net.alummahnow.core.bridge.auth.passkey.PasskeyAuthenticator
import net.alummahnow.core.bridge.auth.passkey.PasskeyFlow
import net.alummahnow.core.bridge.auth.passkey.RegisteredPasskey
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Native OAuth client settings for one environment (Epic 9.1: one public PKCE client per app). */
data class OAuthClientSettings(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String> = listOf("openid", "profile", "email", "offline_access")
)

/** [AccountSession] over aun-auth's `AuthManager`. */
class CoreAccountSession(
    private val settings: OAuthClientSettings,
    storage: SecureStorage
) : AccountSession {
    val manager: AuthManager = AuthManager(
        AuthConfig(
            issuer = settings.issuer,
            clientId = settings.clientId,
            redirectUri = settings.redirectUri,
            scopes = settings.scopes,
            authorizationEndpoint = null,
            tokenEndpoint = null,
            deviceAuthorizationEndpoint = null
        ),
        storage
    )

    private val mutableAccounts = MutableStateFlow<List<StudioAccount>>(emptyList())
    private val mutableActive = MutableStateFlow<StudioAccount?>(null)
    override val accounts: StateFlow<List<StudioAccount>> = mutableAccounts.asStateFlow()
    override val active: StateFlow<StudioAccount?> = mutableActive.asStateFlow()

    init {
        manager.addListener(
            object : AuthListener {
                override fun onEvent(event: AuthEvent) {
                    if (event is AuthEvent.ReauthRequired) AunLog.w("auth", "reauth required")
                    refresh()
                }
            }
        )
        refresh()
    }

    private fun refresh() {
        runCatching {
            mutableAccounts.value = manager.accounts().map { it.toModel() }
            mutableActive.value = manager.activeAccount()?.toModel()
        }.onFailure { AunLog.e("auth", "account list unreadable", it) }
    }

    override fun beginSignIn(mode: SignInMode): String = coreCall {
        val prompt = when (mode) {
            SignInMode.DEFAULT -> LoginPrompt.DEFAULT
            SignInMode.ADD_ACCOUNT -> LoginPrompt.LOGIN
            SignInMode.SILENT -> LoginPrompt.SILENT
        }
        manager.beginLoginWithPrompt(prompt).url
    }

    override fun beginStepUp(maxAgeSeconds: Int): String = coreCall {
        val url = manager.beginLoginWithPrompt(LoginPrompt.LOGIN).url.toHttpUrl()
        url.newBuilder().removeAllQueryParameters("max_age").addQueryParameter("max_age", maxAgeSeconds.toString()).build().toString()
    }

    override suspend fun completeSignIn(redirectUrl: String): StudioAccount = withContext(Dispatchers.IO) {
        val info = coreCall { manager.completeLogin(redirectUrl) }
        refresh()
        AunLog.i("auth", "signed in", "accounts" to mutableAccounts.value.size)
        info.toModel()
    }

    override fun switchTo(userId: String) {
        coreCall { manager.switchAccount(userId) }
        refresh()
    }

    override fun setActiveChannel(channelId: String?) {
        coreCall { manager.setActiveChannel(channelId) }
        refresh()
    }

    override suspend fun signOut(userId: String): String = withContext(Dispatchers.IO) {
        coreCall { manager.signOutAndRevoke(userId) }
        refresh()
        AunLog.i("auth", "signed out", "remaining" to mutableAccounts.value.size)
        settings.issuer.trimEnd('/') + "/logout"
    }

    override suspend fun accessToken(): String? = withContext(Dispatchers.IO) { coreCall { manager.accessToken() } }

    /** Native passkeys over the same PKCE attempt and token exchange as the browser sign-in. */
    private val passkeys = PasskeyFlow(
        api = PasskeyApi(settings.issuer),
        clientId = settings.clientId,
        redirectUri = settings.redirectUri,
        // `prompt` is irrelevant here: the URL is never opened, only its code_challenge and state are used.
        beginAuthorization = { coreCall { manager.beginLoginWithPrompt(LoginPrompt.LOGIN).url } },
        completeRedirect = ::completeSignIn,
        accessToken = ::accessToken
    )

    override suspend fun signInWithPasskey(authenticator: PasskeyAuthenticator): StudioAccount = passkeys.signIn(authenticator)

    override suspend fun addPasskey(authenticator: PasskeyAuthenticator, name: String?): RegisteredPasskey =
        passkeys.register(authenticator, name).also { AunLog.i("auth", "passkey added") }
}

private fun AccountInfo.toModel() = StudioAccount(
    userId = userId,
    displayName = displayName,
    email = email,
    avatarUrl = avatarUrl,
    activeChannelId = activeChannelId,
    needsReauth = needsReauth
)
