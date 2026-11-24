package net.alummahnow.studio

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.alummahnow.core.ApiClient
import net.alummahnow.core.ApiClientConfig
import net.alummahnow.core.PathCall
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.attestation.HttpAttestationApi
import net.alummahnow.core.bridge.attestation.PlayIntegrityProofProvider
import net.alummahnow.core.bridge.notifications.AppPushRegistrar
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.core.CoreAccountSession
import net.alummahnow.core.bridge.studio.core.CoreStudioRepository
import net.alummahnow.core.bridge.studio.core.CoreUploadQueue
import net.alummahnow.core.bridge.studio.core.KeystoreSecureStorage
import net.alummahnow.core.bridge.studio.core.OAuthClientSettings
import net.alummahnow.feature.upload.UploadWorker
import net.alummahnow.integrity.PlayIntegrityTokenSource
import org.json.JSONObject

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
                    maxAttempts = null,
                    client = AunClient.value("studio", BuildConfig.VERSION_NAME)
                ),
                userId
            )
        }
    }

    fun forget(userId: String) = synchronized(clients) { clients.remove(userId)?.close() }

    private fun activeClient(): ApiClient = clientFor(session.active.value?.userId ?: throw CoreError.NotSignedIn())

    /** Play Integrity proofs for videos API writes; off until the videos admin turns Android attestation on. */
    private val proofs = PlayIntegrityProofProvider(
        HttpAttestationApi(BuildConfig.API_BASE_URL, "AlUmmahNow-Studio-Android/${BuildConfig.VERSION_NAME}"),
        PlayIntegrityTokenSource(context),
        context.packageName
    )

    val repository: StudioRepository = CoreStudioRepository(::activeClient, proofs)

    val uploads: CoreUploadQueue = CoreUploadQueue(
        context = context,
        activeUserId = { session.active.value?.userId },
        clientFor = ::clientFor,
        repository = { repository },
        onWorkNeeded = { UploadWorker.start(context) }
    )

    val installId: String by lazy {
        val prefs = context.applicationContext.getSharedPreferences("studio_push", Context.MODE_PRIVATE)
        prefs.getString("install_id", null) ?: ("inst_" + UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("install_id", it).apply()
        }
    }

    val pushRegistrar: AppPushRegistrar = object : AppPushRegistrar {
        override suspend fun register(app: String, installId: String, token: String, locale: String, appVersion: String) {
            val payload = JSONObject()
                .put("app", app)
                .put("provider", "fcm")
                .put("platform", "android")
                .put("token", token)
                .put("locale", locale)
                .put("appVersion", appVersion.take(MAX_VERSION_LEN))
                .toString()
            withContext(Dispatchers.IO) {
                activeClient().requestPath(
                    PathCall(
                        method = "PUT",
                        pathTemplate = "/api/devices/{installId}",
                        pathParams = mapOf("installId" to installId),
                        query = emptyMap(),
                        headers = emptyMap(),
                        bodyJson = payload,
                        idempotencyKey = UUID.randomUUID().toString(),
                        authRequired = true
                    )
                )
            }
        }

        override suspend fun unregister(installId: String) {
            withContext(Dispatchers.IO) {
                activeClient().requestPath(
                    PathCall(
                        method = "DELETE",
                        pathTemplate = "/api/devices/{installId}",
                        pathParams = mapOf("installId" to installId),
                        query = emptyMap(),
                        headers = emptyMap(),
                        bodyJson = null,
                        idempotencyKey = UUID.randomUUID().toString(),
                        authRequired = true
                    )
                )
            }
        }
    }

    companion object {
        const val REDIRECT_URI = "alummahnow-studio://auth/callback"
        private const val MAX_VERSION_LEN = 32
    }
}
