package net.alummahnow.admin

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.alummahnow.admin.security.AppLock
import net.alummahnow.admin.security.StepUp
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.admin.HttpReviewService
import net.alummahnow.core.bridge.admin.PrivateHttp
import net.alummahnow.core.bridge.admin.PushRegistrar
import net.alummahnow.core.bridge.admin.ReviewService
import net.alummahnow.core.bridge.attestation.HttpAttestationApi
import net.alummahnow.core.bridge.attestation.PlayIntegrityProofProvider
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.core.CoreAccountSession
import net.alummahnow.core.bridge.studio.core.KeystoreSecureStorage
import net.alummahnow.core.bridge.studio.core.OAuthClientSettings
import net.alummahnow.core.bridge.telemetry.CoreTelemetryService
import net.alummahnow.core.bridge.telemetry.NullTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.core.bridge.telemetry.TelemetryService
import net.alummahnow.design.R as DesignR
import net.alummahnow.integrity.PlayIntegrityTokenSource

/** What the Admin UI needs. Tests pass fixtures (no network, no pushes, no AI). */
interface AdminServices {
    val session: AccountSession
    val review: ReviewService
    val push: PushRegistrar
    val prefs: AdminPrefs
    val lock: AppLock
    val stepUp: StepUp
    val telemetry: TelemetryService get() = NullTelemetryService
}

/** Manual DI. Sign-in and tokens go through aun-core (the Studio sign-in); admin-api through the private client. */
class AdminContainer(
    context: Context,
    override val prefs: AdminPrefs = AdminPrefs(context),
    override val telemetry: TelemetryService = run {
        val initialEnabled = prefs.telemetryEnabled
        CoreTelemetryService.createOrNull(
            appVersion = BuildConfig.VERSION_NAME,
            deviceClass = if (context.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone",
            videosApiBaseUrl = BuildConfig.API_BASE_URL,
            userAgent = "AlUmmahNow-Admin-Android/${BuildConfig.VERSION_NAME}",
            clientHeader = AunClient.value("admin", BuildConfig.VERSION_NAME),
            bufferPath = File(context.applicationContext.noBackupFilesDir, "admin-telemetry-buffer.json").absolutePath,
            initialEnabled = initialEnabled
        ).also { service ->
            if (initialEnabled && service.consent == TelemetryConsent.OFF && !prefs.setTelemetryEnabled(false)) {
                prefs.setTelemetrySaveUncertain(true)
                prefs.setTelemetryWarningRes(DesignR.string.aun_diagnostics_disable_error)
            }
            CoreTelemetryService.installUncaughtExceptionHandler(service)
            if (service.consent != TelemetryConsent.OFF) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { service.flush() }
            }
        }
    }
) : AdminServices {
    init {
        PrivateHttp.aunClient = AunClient.value("admin", BuildConfig.VERSION_NAME)
    }
    override val session: AccountSession = CoreAccountSession(
        OAuthClientSettings(issuer = BuildConfig.AUTH_ISSUER, clientId = BuildConfig.AUTH_CLIENT_ID, redirectUri = REDIRECT_URI),
        // Its own key store, separate from Studio's: signing out of one app never touches the other.
        KeystoreSecureStorage(context, name = "aun_admin_secure")
    )
    private val http = HttpReviewService(
        adminApi = BuildConfig.ADMIN_API_BASE_URL,
        videosApi = BuildConfig.API_BASE_URL,
        accessToken = { session.accessToken() },
        proofs = PlayIntegrityProofProvider(
            HttpAttestationApi(BuildConfig.API_BASE_URL, "AlUmmahNow-Admin-Android/${BuildConfig.VERSION_NAME}", PrivateHttp.client),
            PlayIntegrityTokenSource(context),
            context.packageName
        )
    )
    override val review: ReviewService = http
    override val push: PushRegistrar = http
    override val lock = AppLock()
    override val stepUp = StepUp()

    companion object {
        const val REDIRECT_URI = "alummahnow-admin://auth/callback"
        const val SCHEME = "alummahnow-admin"
    }
}
