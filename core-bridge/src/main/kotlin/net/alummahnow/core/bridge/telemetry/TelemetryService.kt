package net.alummahnow.core.bridge.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How much diagnostic telemetry the person has opted into. Defaults to [OFF]. */
enum class TelemetryConsent {
    OFF,
    CRASH_ONLY,
    FULL
}

/** Severity level for diagnostic breadcrumbs attached to crash reports. */
enum class TelemetryBreadcrumbSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

/**
 * Privacy-first opt-in telemetry and crash reporting contract shared across Android apps.
 * Silent by default: zero disk writes and zero network traffic until explicitly enabled.
 */
interface TelemetryService {
    val consent: TelemetryConsent
    val pendingCount: Int
    val lastPurgeFailed: Boolean
    val lastPurgeFailedFlow: StateFlow<Boolean> get() = DEFAULT_PURGE_FAILED

    /**
     * Applies [consent]. Opting out immediately denies collection in the wrapper, cancels in-flight
     * flush waits, revokes native consent, and purges memory + disk buffer. Returns `true` when the
     * requested transition (and purge on opt-out) succeeded.
     */
    fun setConsent(consent: TelemetryConsent): Boolean

    fun track(name: String, properties: Map<String, Any?> = emptyMap())
    fun trackError(code: String, properties: Map<String, Any?> = emptyMap())
    fun addBreadcrumb(
        category: String,
        message: String,
        level: TelemetryBreadcrumbSeverity = TelemetryBreadcrumbSeverity.INFO
    )

    fun reportCrash(
        kind: String,
        message: String,
        stack: String? = null,
        fromPreviousSession: Boolean = false
    )

    fun persist()
    suspend fun flush()
    fun close()

    companion object {
        private val DEFAULT_PURGE_FAILED: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

/** Silent no-op implementation used in tests, previews, and environments without the native library. */
object NullTelemetryService : TelemetryService {
    override val consent: TelemetryConsent = TelemetryConsent.OFF
    override val pendingCount: Int = 0
    override val lastPurgeFailed: Boolean = false

    override fun setConsent(consent: TelemetryConsent): Boolean = consent == TelemetryConsent.OFF
    override fun track(name: String, properties: Map<String, Any?>) = Unit
    override fun trackError(code: String, properties: Map<String, Any?>) = Unit
    override fun addBreadcrumb(category: String, message: String, level: TelemetryBreadcrumbSeverity) = Unit
    override fun reportCrash(kind: String, message: String, stack: String?, fromPreviousSession: Boolean) = Unit
    override fun persist() = Unit
    override suspend fun flush() = Unit
    override fun close() = Unit
}
