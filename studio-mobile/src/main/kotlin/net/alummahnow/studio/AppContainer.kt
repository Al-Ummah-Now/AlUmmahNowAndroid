package net.alummahnow.studio

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import net.alummahnow.core.bridge.telemetry.CoreTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.core.bridge.telemetry.TelemetryService
import net.alummahnow.design.R as DesignR
import net.alummahnow.feature.editor.editorExportDir
import net.alummahnow.feature.upload.UploadScheduler
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

    private val telemetryPrefs = context.applicationContext.getSharedPreferences("aun_studio_telemetry", Context.MODE_PRIVATE)

    override val telemetry: TelemetryService = CoreTelemetryService.createOrNull(
        appVersion = BuildConfig.VERSION_NAME,
        deviceClass = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone",
        videosApiBaseUrl = BuildConfig.API_BASE_URL,
        userAgent = "AlUmmahNow-Studio-Android/${BuildConfig.VERSION_NAME}",
        clientHeader = AunClient.value("studio", BuildConfig.VERSION_NAME),
        bufferPath = File(context.applicationContext.noBackupFilesDir, "studio-telemetry-buffer.json").absolutePath,
        initialEnabled = telemetryPrefs.getBoolean(KEY_TELEMETRY_ENABLED, false)
    )

    private val _telemetryEnabled = MutableStateFlow(telemetryPrefs.getBoolean(KEY_TELEMETRY_ENABLED, false))
    private val _telemetrySaveFailed = MutableStateFlow(false)
    private val _telemetryWarningRes = MutableStateFlow<Int?>(null)

    override val telemetryEnabledFlow: StateFlow<Boolean> = _telemetryEnabled.asStateFlow()
    override val telemetrySaveFailedFlow: StateFlow<Boolean> = _telemetrySaveFailed.asStateFlow()
    override val telemetryWarningResFlow: StateFlow<Int?> = _telemetryWarningRes.asStateFlow()

    override fun isTelemetryEnabled(): Boolean = _telemetryEnabled.value || _telemetrySaveFailed.value

    override fun isTelemetryPreferenceSaveFailed(): Boolean = _telemetrySaveFailed.value

    override fun setTelemetryEnabled(enabled: Boolean): Boolean =
        if (enabled) enableTelemetry() else disableTelemetry()

    private fun enableTelemetry(): Boolean {
        if (!telemetry.setConsent(TelemetryConsent.FULL)) {
            telemetry.setConsent(TelemetryConsent.OFF)
            _telemetryWarningRes.value = DesignR.string.aun_diagnostics_enable_error
            return false
        }
        if (!telemetryPrefs.edit().putBoolean(KEY_TELEMETRY_ENABLED, true).commit()) {
            telemetry.setConsent(TelemetryConsent.OFF)
            val rolledBack = telemetryPrefs.edit().putBoolean(KEY_TELEMETRY_ENABLED, false).commit()
            if (rolledBack) _telemetryEnabled.value = false
            _telemetrySaveFailed.value = !rolledBack
            _telemetryWarningRes.value = if (rolledBack) {
                DesignR.string.aun_diagnostics_enable_error
            } else {
                DesignR.string.aun_diagnostics_disable_error
            }
            return false
        }
        _telemetryEnabled.value = true
        _telemetrySaveFailed.value = false
        _telemetryWarningRes.value = null
        return true
    }

    private fun disableTelemetry(): Boolean {
        val purged = telemetry.setConsent(TelemetryConsent.OFF)
        val saved = telemetryPrefs.edit().putBoolean(KEY_TELEMETRY_ENABLED, false).commit()
        if (saved) _telemetryEnabled.value = false
        _telemetrySaveFailed.value = !saved
        _telemetryWarningRes.value = when {
            !saved -> DesignR.string.aun_diagnostics_disable_error
            !purged -> DesignR.string.aun_diagnostics_purge_warning
            else -> null
        }
        return saved
    }

    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { exports.follow(session.accounts) }
    }

    override val deletionRecords: DeletionRecords =
        SecureDeletionRecords(KeystoreSecureStorage(context, "aun_deletion", discardUnreadableValues = false))

    @Volatile
    var transferRunning = false

    override val uploads: CoreUploadQueue = CoreUploadQueue(
        context = context,
        activeUserId = { session.active.value?.userId },
        clientFor = ::clientFor,
        repository = { repository },
        onWorkNeeded = { UploadScheduler.start(context) },
        blocked = { deletionRecords.isUnsure(it) },
        transfersAllowed = { Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || transferRunning }
    )

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
            override suspend fun purgeUploads(userId: String) {
                uploads.purgeAccount(userId)
                withContext(Dispatchers.IO) { exports.remove(userId) }
            }
            override suspend fun endSession(userId: String) = session.removeLocally(userId)
        }
    )

    companion object {
        const val REDIRECT_URI = "alummahnow-studio://auth/callback"
        private const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"
    }
}
