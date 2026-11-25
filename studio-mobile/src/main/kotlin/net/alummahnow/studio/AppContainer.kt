package net.alummahnow.studio

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.alummahnow.core.ApiClient
import net.alummahnow.core.ApiClientConfig
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.attestation.HttpAttestationApi
import net.alummahnow.core.bridge.attestation.PlayIntegrityProofProvider
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.core.CoreAccountSession
import net.alummahnow.core.bridge.studio.core.CoreStudioRepository
import net.alummahnow.core.bridge.studio.core.CoreUploadQueue
import net.alummahnow.core.bridge.studio.core.KeystoreSecureStorage
import net.alummahnow.core.bridge.studio.core.OAuthClientSettings
import net.alummahnow.core.bridge.studio.deletion.AccountDeletion
import net.alummahnow.core.bridge.studio.deletion.DeletionDevice
import net.alummahnow.core.bridge.studio.deletion.DeletionRecords
import net.alummahnow.core.bridge.studio.deletion.HttpDeletionApi
import net.alummahnow.core.bridge.studio.deletion.SecureDeletionRecords
import net.alummahnow.feature.editor.editorExportDir
import net.alummahnow.feature.upload.UploadWorker
import net.alummahnow.integrity.PlayIntegrityTokenSource

/** Manual DI for Studio. Everything network- or token-related goes through aun-core. */
class AppContainer(context: Context) : StudioContainer {
    private val coreSession = CoreAccountSession(
        OAuthClientSettings(issuer = BuildConfig.AUTH_ISSUER, clientId = BuildConfig.AUTH_CLIENT_ID, redirectUri = REDIRECT_URI),
        KeystoreSecureStorage(context)
    )
    override val session: AccountSession = coreSession

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

    override fun forget(userId: String) {
        synchronized(clients) { clients.remove(userId) }?.close()
    }

    private fun activeClient(): ApiClient = clientFor(session.active.value?.userId ?: throw CoreError.NotSignedIn())

    /** Play Integrity proofs for videos API writes; off until the videos admin turns Android attestation on. */
    private val proofs = PlayIntegrityProofProvider(
        HttpAttestationApi(BuildConfig.API_BASE_URL, "AlUmmahNow-Studio-Android/${BuildConfig.VERSION_NAME}"),
        PlayIntegrityTokenSource(context),
        context.packageName
    )

    override val repository: StudioRepository = CoreStudioRepository(::activeClient, proofs)

    override fun repositoryFor(userId: String): StudioRepository = CoreStudioRepository({ clientFor(userId) }, proofs)

    override val exports = PendingExports(editorExportDir(context), File(context.filesDir, "pending-exports.json"))

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { exports.follow(session.accounts) }
    }

    override val uploads: CoreUploadQueue = CoreUploadQueue(
        context = context,
        activeUserId = { session.active.value?.userId },
        clientFor = ::clientFor,
        repository = { repository },
        onWorkNeeded = { UploadWorker.start(context) }
    )

    override val deletionRecords: DeletionRecords = SecureDeletionRecords(KeystoreSecureStorage(context, "aun_deletion"))

    override val deletion = AccountDeletion(
        deletionRecords,
        HttpDeletionApi(
            apiBaseUrl = BuildConfig.API_BASE_URL,
            issuer = BuildConfig.AUTH_ISSUER,
            userAgent = "AlUmmahNow-Studio-Android/${BuildConfig.VERSION_NAME}",
            clientHeader = AunClient.value("studio", BuildConfig.VERSION_NAME),
            proofs = proofs
        ),
        object : DeletionDevice {
            override suspend fun token(userId: String): String? =
                if (session.active.value?.userId == userId) session.accessToken() else null
            override suspend fun holdUploads(userId: String) = uploads.holdUploads(userId)
            override suspend fun releaseUploads(userId: String) = uploads.releaseUploads(userId)
            override suspend fun purgeUploads(userId: String) = uploads.purgeAccount(userId)
            override suspend fun endSession(userId: String) = session.removeLocally(userId)
        }
    )

    companion object {
        const val REDIRECT_URI = "alummahnow-studio://auth/callback"
    }
}
