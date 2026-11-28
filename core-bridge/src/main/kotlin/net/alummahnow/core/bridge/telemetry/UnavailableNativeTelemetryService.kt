package net.alummahnow.core.bridge.telemetry

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fail-closed fallback returned by [CoreTelemetryService.createOrNull] when the native JNI
 * telemetry library cannot be loaded or initialized.
 *
 * Unlike [NullTelemetryService], this fallback still purges any cached telemetry buffer and
 * sibling atomic-persist temp files on disk (on startup and whenever [setConsent] is called
 * with [TelemetryConsent.OFF]), surfacing [lastPurgeFailed] so the UI can warn the user and
 * offer a purge retry.
 */
internal class UnavailableNativeTelemetryService(
    private val bufferPath: String?
) : TelemetryService {
    private val lock = Any()
    private val _lastPurgeFailed = MutableStateFlow(false)

    init {
        purgeBuffers()
    }

    override val consent: TelemetryConsent = TelemetryConsent.OFF
    override val pendingCount: Int = 0
    override val lastPurgeFailed: Boolean get() = _lastPurgeFailed.value
    override val lastPurgeFailedFlow: StateFlow<Boolean> = _lastPurgeFailed.asStateFlow()

    override fun setConsent(consent: TelemetryConsent): Boolean {
        if (consent != TelemetryConsent.OFF) return false
        return purgeBuffers()
    }

    private fun purgeBuffers(): Boolean = synchronized(lock) {
        val purged = removeBufferAndTempFiles(bufferPath)
        _lastPurgeFailed.value = !purged
        purged
    }

    override fun track(name: String, properties: Map<String, Any?>) = Unit
    override fun trackError(code: String, properties: Map<String, Any?>) = Unit
    override fun addBreadcrumb(category: String, message: String, level: TelemetryBreadcrumbSeverity) = Unit
    override fun reportCrash(kind: String, message: String, stack: String?, fromPreviousSession: Boolean) = Unit
    override fun persist() = Unit
    override suspend fun flush() = Unit
    override fun close() = Unit

    companion object {
        internal fun removeBufferAndTempFiles(bufferPath: String?): Boolean {
            if (bufferPath.isNullOrBlank()) return true
            val file = File(bufferPath)
            val mainOk = deleteSingleFile(file)
            val dir = file.parentFile ?: return mainOk
            val prefix = ".${file.name}."
            val entries = dir.listFiles() ?: return if (!dir.exists()) mainOk else false
            var allOk = mainOk
            for (entry in entries) {
                if (entry.name.startsWith(prefix) && entry.name.endsWith(".tmp")) {
                    if (!deleteSingleFile(entry)) allOk = false
                }
            }
            return allOk
        }

        private fun deleteSingleFile(file: File): Boolean = try {
            java.nio.file.Files.deleteIfExists(file.toPath())
            true
        } catch (_: java.io.IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
