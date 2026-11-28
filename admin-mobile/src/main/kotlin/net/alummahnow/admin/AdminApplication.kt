package net.alummahnow.admin

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.alummahnow.admin.media.PrivateMedia
import net.alummahnow.admin.push.AdminNotifications
import net.alummahnow.admin.security.SecureWindows
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.telemetry.CoreTelemetryService
import net.alummahnow.core.bridge.telemetry.TelemetryConsent
import net.alummahnow.core.bridge.telemetry.TelemetryService
import net.alummahnow.design.R as DesignR

class AdminApplication :
    Application(),
    SingletonImageLoader.Factory {
    val prefs: AdminPrefs by lazy { AdminPrefs(this) }

    private fun telemetryBufferPath(): String =
        File(applicationContext.noBackupFilesDir, "admin-telemetry-buffer.json").absolutePath

    val telemetry: TelemetryService by lazy {
        val initialEnabled = prefs.telemetryEnabled
        CoreTelemetryService.createOrNull(
            appVersion = BuildConfig.VERSION_NAME,
            deviceClass = if (resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone",
            videosApiBaseUrl = BuildConfig.API_BASE_URL,
            userAgent = "AlUmmahNow-Admin-Android/${BuildConfig.VERSION_NAME}",
            clientHeader = AunClient.value("admin", BuildConfig.VERSION_NAME),
            bufferPath = telemetryBufferPath(),
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

    /** Created on first use, so tests that only need the process (and no Rust core) never load it. */
    val container: AdminContainer by lazy { AdminContainer(this, prefs, telemetry) }

    override fun onCreate() {
        super.onCreate()
        AunLog.enabled = BuildConfig.DEBUG
        if (prefs.telemetryEnabled) {
            telemetry
        } else {
            CoreTelemetryService.purgePersistedBuffer(telemetryBufferPath())
        }
        registerActivityLifecycleCallbacks(SecureWindows)
        AdminNotifications.ensureChannel(this)
    }

    /** Any image a shared component loads: memory only (no disk cache), over the private client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = PrivateMedia.imageLoader(context)
}
