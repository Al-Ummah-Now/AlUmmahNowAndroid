package net.alummahnow.core.bridge.telemetry

import android.os.Build
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import net.alummahnow.core.TelemetryAppInfo
import net.alummahnow.core.TelemetryClient
import net.alummahnow.core.TelemetryClientConfig
import net.alummahnow.core.TelemetryConsentState
import net.alummahnow.core.TelemetryCrashReport
import net.alummahnow.core.bridge.AunLog

/**
 * [TelemetryService] on aun-core's [TelemetryClient]. The core scrubs every string (e-mails, tokens,
 * URL queries, long secrets), keeps the buffer file, purges it when consent is withdrawn and posts
 * batches to the videos API's `/api/v1/telemetry/batches`. This class maps consent, hands it
 * crashes and asks it to flush. Every call is guarded: reporting must never cause a failure.
 */
@Suppress("TooGenericExceptionCaught")
class CoreTelemetryService internal constructor(private val client: TelemetryClient) : TelemetryService {

    override val consent: TelemetryConsent
        get() = guarded(TelemetryConsent.OFF) {
            when (client.consent()) {
                TelemetryConsentState.ANALYTICS -> TelemetryConsent.FULL
                TelemetryConsentState.CRASH_ONLY -> TelemetryConsent.CRASH_ONLY
                else -> TelemetryConsent.OFF
            }
        }

    override fun setConsent(consent: TelemetryConsent): Boolean = guarded(false) {
        client.setConsent(
            when (consent) {
                TelemetryConsent.FULL -> TelemetryConsentState.ANALYTICS
                TelemetryConsent.CRASH_ONLY -> TelemetryConsentState.CRASH_ONLY
                TelemetryConsent.OFF -> TelemetryConsentState.DENIED
            }
        )
        true
    }

    override fun reportCrash(kind: String, message: String, stack: String?) {
        guarded(Unit) {
            client.reportCrash(TelemetryCrashReport(kind, message, stack, System.currentTimeMillis(), false, null))
            client.persist()
        }
    }

    override fun persist() {
        guarded(Unit) { client.persist() }
    }

    override suspend fun flush() {
        try {
            client.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or refused: the core keeps the batch for the next flush.
            AunLog.d("telemetry", "flush failed", "error" to e.javaClass.simpleName)
        }
    }

    override fun close() {
        guarded(Unit) { client.close() }
    }

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        AunLog.w("telemetry", "telemetry call failed", "error" to e.javaClass.simpleName)
        fallback
    }

    companion object {
        /** The app's telemetry, or [NullTelemetryService] where the native library cannot load. */
        @Suppress("LongParameterList")
        fun createOrNull(
            appVersion: String,
            deviceClass: String,
            videosApiBaseUrl: String?,
            userAgent: String,
            clientHeader: String,
            bufferPath: String?,
            initialEnabled: Boolean,
            locale: String = Locale.getDefault().language.ifBlank { "en" }
        ): TelemetryService = try {
            val client = TelemetryClient(
                TelemetryClientConfig(
                    app = TelemetryAppInfo(
                        platform = "android",
                        appVersion = appVersion,
                        osVersion = "Android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT}",
                        deviceClass = deviceClass,
                        locale = locale
                    ),
                    ingestUrl = resolveIngestUrl(videosApiBaseUrl),
                    userAgent = userAgent,
                    clientHeader = clientHeader,
                    bufferPath = bufferPath
                )
            )
            CoreTelemetryService(client).also { it.setConsent(if (initialEnabled) TelemetryConsent.FULL else TelemetryConsent.OFF) }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            AunLog.w("telemetry", "telemetry unavailable, using no-op service", "error" to e.javaClass.simpleName)
            NullTelemetryService
        }

        /** Records uncaught exceptions, then hands them to the handler that was there before. */
        fun installUncaughtExceptionHandler(service: TelemetryService) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    service.reportCrash(
                        kind = throwable.javaClass.name,
                        message = throwable.message ?: throwable.javaClass.simpleName,
                        stack = throwable.stackTraceToString()
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
        }

        /**
         * `https://<host>[:<port>]/api/v1/telemetry/batches` from an https videos API; `null` (nothing is
         * sent) for anything else, including local http dev servers and URLs with credentials.
         */
        fun resolveIngestUrl(raw: String?): String? {
            val uri = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }
            val host = uri
                ?.takeIf { it.scheme.equals("https", ignoreCase = true) && it.rawUserInfo.isNullOrEmpty() }
                ?.host
                ?.takeIf { it.isNotEmpty() }
                ?: return null
            val formattedHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val portSegment = if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
            return "https://$formattedHost$portSegment/api/v1/telemetry/batches"
        }
    }
}
