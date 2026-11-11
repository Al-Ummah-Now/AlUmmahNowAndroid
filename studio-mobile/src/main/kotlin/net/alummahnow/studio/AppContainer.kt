package net.alummahnow.studio

import android.content.Context
import net.alummahnow.core.ApiClient
import net.alummahnow.core.ApiClientConfig
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.core.CoreAccountSession
import net.alummahnow.core.bridge.studio.core.CoreStudioRepository
import net.alummahnow.core.bridge.studio.core.CoreUploadQueue
import net.alummahnow.core.bridge.studio.core.KeystoreSecureStorage
import net.alummahnow.core.bridge.studio.core.OAuthClientSettings
import net.alummahnow.feature.upload.UploadWorker

/** Manual DI for Studio. Everything network- or token-related goes through aun-core. */
class AppContainer(context: Context) {
    private val coreSession = CoreAccountSession(
        OAuthClientSettings(issuer = BuildConfig.AUTH_ISSUER, clientId = BuildConfig.AUTH_CLIENT_ID, redirectUri = REDIRECT_URI),
        KeystoreSecureStorage(context)
    )
    val session: AccountSession = coreSession

    private val clients = mutableMapOf<String, ApiClient>()

    /** One API client per account: it keeps acting as that account and refreshes its token on 401. */
    fun clientFor(userId: String): ApiClient = synchronized(clients) {
        clients.getOrPut(userId) {
            coreSession.manager.apiClient(
                ApiClientConfig(
                    baseUrl = BuildConfig.API_BASE_URL,
                    userAgent = "AlUmmahNow-Studio-Android/${BuildConfig.VERSION_NAME}",
                    locale = null,
                    timeoutMs = null,
                    maxAttempts = null
                ),
                userId
            )
        }
    }

    fun forget(userId: String) = synchronized(clients) { clients.remove(userId)?.close() }

    private fun activeClient(): ApiClient = clientFor(session.active.value?.userId ?: throw CoreError.NotSignedIn())

    val repository: StudioRepository = CoreStudioRepository(::activeClient)

    val uploads: CoreUploadQueue = CoreUploadQueue(
        context = context,
        activeUserId = { session.active.value?.userId },
        clientFor = ::clientFor,
        repository = { repository },
        onWorkNeeded = { UploadWorker.start(context) }
    )

    companion object {
        const val REDIRECT_URI = "alummahnow-studio://auth/callback"
    }
}
