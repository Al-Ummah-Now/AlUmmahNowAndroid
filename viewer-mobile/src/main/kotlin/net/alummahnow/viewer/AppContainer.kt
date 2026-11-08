package net.alummahnow.viewer

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.OAuthAuthBridge
import net.alummahnow.core.bridge.auth.OAuthConfig
import net.alummahnow.core.bridge.auth.TokenStore
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.platform.KeystoreSecureStorage

/** Manual DI for the viewer. `Temporary*` bindings are swapped for core-backed ones as aun-core grows. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob())
    val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = OAuthAuthBridge(
        OAuthConfig(issuer = BuildConfig.AUTH_ISSUER, clientId = BuildConfig.OAUTH_CLIENT_ID, redirectUri = AUTH_REDIRECT),
        TokenStore(KeystoreSecureStorage(context))
    )
    val displayPrefs = DisplayPrefsStore(context.applicationContext)

    private val mutableSignInFailed = MutableStateFlow(false)

    /** True after the last sign-in attempt failed; the account row shows a retry hint until the next attempt. */
    val signInFailed: StateFlow<Boolean> = mutableSignInFailed.asStateFlow()

    fun markSignInStarted() {
        mutableSignInFailed.value = false
    }

    /** Finishes a PKCE sign-in from the browser redirect. Runs in the app scope so rotation can't cancel it. */
    fun completeSignIn(callbackUri: String) {
        appScope.launch {
            try {
                auth.completePkceSignIn(callbackUri)
            } catch (e: CoreError) {
                AunLog.w("auth", "sign-in failed", "error" to e.message)
                mutableSignInFailed.value = true
            }
        }
    }

    companion object {
        /** Registered for both viewer clients (auth migration 0013_native_clients). */
        const val AUTH_REDIRECT = "alummahnow://auth/callback"
    }
}
