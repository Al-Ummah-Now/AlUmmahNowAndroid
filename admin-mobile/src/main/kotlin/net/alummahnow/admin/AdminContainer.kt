package net.alummahnow.admin

import android.content.Context
import net.alummahnow.admin.security.AppLock
import net.alummahnow.admin.security.StepUp
import net.alummahnow.core.bridge.admin.HttpReviewService
import net.alummahnow.core.bridge.admin.PushRegistrar
import net.alummahnow.core.bridge.admin.ReviewService
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.core.CoreAccountSession
import net.alummahnow.core.bridge.studio.core.KeystoreSecureStorage
import net.alummahnow.core.bridge.studio.core.OAuthClientSettings

/** What the Admin UI needs. Tests pass fixtures (no network, no pushes, no AI). */
interface AdminServices {
    val session: AccountSession
    val review: ReviewService
    val push: PushRegistrar
    val prefs: AdminPrefs
    val lock: AppLock
    val stepUp: StepUp
}

/** Manual DI. Sign-in and tokens go through aun-core (the Studio sign-in); admin-api through the private client. */
class AdminContainer(context: Context) : AdminServices {
    override val session: AccountSession = CoreAccountSession(
        OAuthClientSettings(issuer = BuildConfig.AUTH_ISSUER, clientId = BuildConfig.AUTH_CLIENT_ID, redirectUri = REDIRECT_URI),
        // Its own key store, separate from Studio's: signing out of one app never touches the other.
        KeystoreSecureStorage(context, name = "aun_admin_secure")
    )
    private val http = HttpReviewService(
        adminApi = BuildConfig.ADMIN_API_BASE_URL,
        videosApi = BuildConfig.API_BASE_URL,
        accessToken = { session.accessToken() }
    )
    override val review: ReviewService = http
    override val push: PushRegistrar = http
    override val prefs = AdminPrefs(context)
    override val lock = AppLock()
    override val stepUp = StepUp()

    companion object {
        const val REDIRECT_URI = "alummahnow-admin://auth/callback"
        const val SCHEME = "alummahnow-admin"
    }
}
