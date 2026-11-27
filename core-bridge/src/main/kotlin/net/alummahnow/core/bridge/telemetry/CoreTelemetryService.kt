package net.alummahnow.core.bridge.telemetry

import android.os.Build
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import net.alummahnow.core.AunException
import net.alummahnow.core.TelemetryAppInfo
import net.alummahnow.core.TelemetryBreadcrumbLevel
import net.alummahnow.core.TelemetryClient
import net.alummahnow.core.TelemetryClientConfig
import net.alummahnow.core.TelemetryConsentState
import net.alummahnow.core.TelemetryCrashReport
import net.alummahnow.core.bridge.AunLog

/**
 * Wraps `aun-core`'s [TelemetryClient] behind [TelemetryService].
 *
 * Privacy and safety invariants:
 * - Defaults to [TelemetryConsent.OFF] (zero disk writes, zero network calls).
 * - Opting out immediately denies collection in the wrapper, cancels active flush waits, revokes
 *   native consent, and purges memory + disk buffer (surfacing purge failures via [lastPurgeFailed]).
 * - Failed consent transitions fail closed in both wrapper and native state.
 * - Ordinary [close] stops new collection and flush waits without setting [revoked], preserving any
 *   retryable persisted buffer across normal shutdown.
 * - Local filesystem paths (including Unicode segments and spaces) and `file://` URIs are redacted
 *   from crash messages, stacks, and breadcrumbs before reaching native storage.
 * - Ingest URLs are built strictly from HTTPS `host[:port]` (rejecting embedded credentials and
 *   stripping path, query, and fragment).
 */
class CoreTelemetryService(
    platform: String,
    appVersion: String,
    osVersion: String,
    deviceClass: String,
    locale: String,
    videosApiBaseUrl: String?,
    userAgent: String,
    clientHeader: String,
    private val bufferPath: String?,
    initialEnabled: Boolean = false
) : TelemetryService {
    private val client: TelemetryClient
    private val gate = Any()
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var _consent: TelemetryConsent = TelemetryConsent.OFF
    private var revoked: Boolean = false
    private var closed: Boolean = false
    private var _lastPurgeFailed: Boolean = false
    private var inFlightFlushes: Int = 0
    private var clientClosed: Boolean = false
    private val flushJobs = mutableSetOf<Job>()

    init {
        val app = TelemetryAppInfo(
            platform = platform,
            appVersion = appVersion,
            osVersion = osVersion,
            deviceClass = deviceClass,
            locale = locale
        )
        val config = TelemetryClientConfig(
            app = app,
            ingestUrl = resolveIngestUrl(videosApiBaseUrl),
            userAgent = userAgent,
            clientHeader = clientHeader,
            bufferPath = bufferPath,
            sampleRate = null,
            maxBatchBytes = null
        )
        client = TelemetryClient(config)
        setConsent(if (initialEnabled) TelemetryConsent.FULL else TelemetryConsent.OFF)
    }

    override val consent: TelemetryConsent
        get() = synchronized(gate) { if (closed) TelemetryConsent.OFF else _consent }

    override val pendingCount: Int
        get() = synchronized(gate) {
            if (closed || clientClosed || _consent == TelemetryConsent.OFF) 0 else client.pendingCount().toInt()
        }

    override val lastPurgeFailed: Boolean
        get() = synchronized(gate) { _lastPurgeFailed }

    override fun setConsent(consent: TelemetryConsent): Boolean {
        val mapped = when (consent) {
            TelemetryConsent.FULL -> TelemetryConsentState.ANALYTICS
            TelemetryConsent.CRASH_ONLY -> TelemetryConsentState.CRASH_ONLY
            TelemetryConsent.OFF -> TelemetryConsentState.DENIED
        }

        val jobsToCancel: List<Job>
        val succeeded: Boolean
        synchronized(gate) {
            if (closed || clientClosed) {
                if (consent == TelemetryConsent.OFF) {
                    revoked = true
                    if (!clientClosed) {
                        runCatching { client.setConsent(TelemetryConsentState.DENIED) }
                    }
                    _lastPurgeFailed = !tryDeleteBufferFallback()
                    return !_lastPurgeFailed
                }
                return false
            }

            if (consent == TelemetryConsent.OFF) {
                _consent = TelemetryConsent.OFF
                revoked = true
                jobsToCancel = flushJobs.toList()
                flushJobs.clear()
                try {
                    client.setConsent(TelemetryConsentState.DENIED)
                    _lastPurgeFailed = !tryDeleteSiblingTempFiles()
                } catch (e: AunException) {
                    _lastPurgeFailed = !tryDeleteBufferFallback()
                    AunLog.e("telemetry", "telemetry disk purge failed on opt-out", e)
                }
                succeeded = !_lastPurgeFailed
            } else {
                var cancelOnFail: List<Job> = emptyList()
                succeeded = try {
                    client.setConsent(mapped)
                    _consent = consent
                    revoked = false
                    _lastPurgeFailed = false
                    true
                } catch (e: AunException) {
                    // Fail closed in both wrapper and native state so an earlier native update
                    // cannot leave collection active for a queued flush.
                    _consent = TelemetryConsent.OFF
                    revoked = true
                    cancelOnFail = flushJobs.toList()
                    flushJobs.clear()
                    try {
                        client.setConsent(TelemetryConsentState.DENIED)
                    } catch (_: AunException) {
                        _lastPurgeFailed = !tryDeleteBufferFallback()
                    }
                    AunLog.w("telemetry", "failed to update telemetry consent; failing closed", "error" to e.message)
                    false
                }
                jobsToCancel = cancelOnFail
            }
        }
        jobsToCancel.forEach { it.cancel() }
        return succeeded
    }

    override fun track(name: String, properties: Map<String, Any?>) {
        if (name.isBlank()) return
        val json = serializeProperties(properties)
        synchronized(gate) {
            if (closed || clientClosed || _consent != TelemetryConsent.FULL) return
            try {
                client.track(name, json)
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry track skipped", "error" to e.message)
            }
        }
    }

    override fun trackError(code: String, properties: Map<String, Any?>) {
        if (code.isBlank()) return
        val json = serializeProperties(properties)
        synchronized(gate) {
            if (closed || clientClosed || _consent != TelemetryConsent.FULL) return
            try {
                client.trackError(code, json)
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry error skipped", "error" to e.message)
            }
        }
    }

    override fun addBreadcrumb(category: String, message: String, level: TelemetryBreadcrumbSeverity) {
        if (category.isBlank()) return
        val mapped = when (level) {
            TelemetryBreadcrumbSeverity.DEBUG -> TelemetryBreadcrumbLevel.DEBUG
            TelemetryBreadcrumbSeverity.INFO -> TelemetryBreadcrumbLevel.INFO
            TelemetryBreadcrumbSeverity.WARNING -> TelemetryBreadcrumbLevel.WARNING
            TelemetryBreadcrumbSeverity.ERROR -> TelemetryBreadcrumbLevel.ERROR
        }
        val safeMessage = sanitizeLocalPaths(message)
        synchronized(gate) {
            if (closed || clientClosed || _consent == TelemetryConsent.OFF) return
            client.addBreadcrumb(category, safeMessage, mapped)
        }
    }

    override fun reportCrash(kind: String, message: String, stack: String?, fromPreviousSession: Boolean) {
        val safeKind = kind.ifBlank { "UnhandledException" }
        val safeMessage = if (message.isBlank()) "Unhandled exception" else sanitizeLocalPaths(message)
        val safeStack = stack?.let(::sanitizeLocalPaths)
        val report = TelemetryCrashReport(
            kind = safeKind,
            message = safeMessage,
            stack = safeStack,
            occurredAtMs = System.currentTimeMillis(),
            fromPreviousSession = fromPreviousSession,
            propertiesJson = null
        )
        synchronized(gate) {
            if (closed || clientClosed || _consent == TelemetryConsent.OFF) return
            try {
                client.reportCrash(report)
                client.persist()
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry crash skipped", "error" to e.message)
            }
        }
    }

    override fun persist() {
        synchronized(gate) {
            if (closed || clientClosed || _consent == TelemetryConsent.OFF) return
            try {
                client.persist()
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry persist failed", "error" to e.message)
            }
        }
    }

    override suspend fun flush() {
        val job: Job
        synchronized(gate) {
            if (closed || clientClosed || _consent == TelemetryConsent.OFF) return
            inFlightFlushes++
            job = flushScope.launch {
                try {
                    client.flush()
                } catch (_: CancellationException) {
                    // Cancelled by opt-out or shutdown.
                } catch (e: AunException) {
                    val isRevoked = synchronized(gate) { revoked || closed }
                    if (!isRevoked) {
                        AunLog.w("telemetry", "telemetry flush skipped", "error" to e.message)
                    }
                } finally {
                    releaseInFlightFlush()
                }
            }
            flushJobs += job
        }
        try {
            job.join()
        } finally {
            synchronized(gate) { flushJobs.remove(job) }
        }
    }

    override fun close() {
        val jobsToCancel: List<Job>
        synchronized(gate) {
            if (closed) return
            closed = true
            _consent = TelemetryConsent.OFF
            jobsToCancel = flushJobs.toList()
            flushJobs.clear()
            if (inFlightFlushes == 0 && !clientClosed) {
                clientClosed = true
                client.close()
            }
        }
        jobsToCancel.forEach { it.cancel() }
    }

    private fun releaseInFlightFlush() {
        synchronized(gate) {
            inFlightFlushes = (inFlightFlushes - 1).coerceAtLeast(0)
            if (revoked && !bufferPath.isNullOrBlank()) {
                // Clean up any remnant if a flush already in flight before revocation completed
                // its final persist rename after setConsent(DENIED) deleted the buffer file.
                // Ordinary close() without revocation leaves revoked == false, keeping retryable buffers.
                if (!tryDeleteBufferFallback()) {
                    _lastPurgeFailed = true
                }
            }
            if (closed && inFlightFlushes == 0 && !clientClosed) {
                clientClosed = true
                client.close()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryDeleteBufferFallback(): Boolean {
        val path = bufferPath?.takeIf { it.isNotBlank() } ?: return true
        val nioPath = runCatching { Paths.get(path) }.getOrNull() ?: return false
        val mainOk = try {
            Files.delete(nioPath)
            true
        } catch (_: NoSuchFileException) {
            true
        } catch (_: Exception) {
            false
        }
        return mainOk && tryDeleteSiblingTempFiles()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryDeleteSiblingTempFiles(): Boolean {
        val path = bufferPath?.takeIf { it.isNotBlank() } ?: return true
        val nioPath = runCatching { Paths.get(path) }.getOrNull() ?: return false
        val parent = nioPath.parent ?: return true
        val name = nioPath.fileName?.toString() ?: return true
        return try {
            var allDeleted = true
            Files.newDirectoryStream(parent, ".$name.*.tmp").use { stream ->
                for (tmp in stream) {
                    try {
                        Files.delete(tmp)
                    } catch (_: NoSuchFileException) {
                        // Already removed.
                    } catch (_: Exception) {
                        allDeleted = false
                    }
                }
            }
            allDeleted
        } catch (_: NoSuchFileException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val doubleQuotedPathRegex = Regex(
            """${'"'}(?:file://|(?:[A-Za-z]:[\\/]|\\\\)|~/(?!\s)|/[^"\r\n/]+)[^"\r\n]*${'"'}""",
            RegexOption.IGNORE_CASE
        )
        private val singleQuotedPathRegex = Regex(
            """'(?:file://|(?:[A-Za-z]:[\\/]|\\\\)|~/(?!\s)|/[^'\r\n/]+)[^'\r\n]*'""",
            RegexOption.IGNORE_CASE
        )
        private val fileUriRegex = Regex("""file://[^\r\n"<>|;]+""", RegexOption.IGNORE_CASE)
        private val windowsPathRegex = Regex("""(?:[A-Za-z]:[\\/]|\\\\)[^\r\n"<>|;]+?(?=(?::line\b|:\d+\b|[\r\n"<>|;]|$))""")
        private val unixPathRegex = Regex(
            """(?<![\p{L}\p{M}\p{N}._:/-])(?:~/|/[^\r\n/"<>|;:]+/|/[^\r\n/"<>|;:]+\.[\p{L}\p{N}]{1,10})""" +
                """[^\r\n"<>|;]*?(?=(?::line\b|:\d+\b|[\r\n"<>|;]|$))"""
        )

        /**
         * Creates a live [CoreTelemetryService], falling back to [NullTelemetryService] when the
         * native JNI library cannot be loaded (e.g. JVM Robolectric unit tests) or init fails.
         */
        @Suppress("TooGenericExceptionCaught")
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
            CoreTelemetryService(
                platform = "android",
                appVersion = appVersion,
                osVersion = "Android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT}",
                deviceClass = deviceClass,
                locale = locale,
                videosApiBaseUrl = videosApiBaseUrl,
                userAgent = userAgent,
                clientHeader = clientHeader,
                bufferPath = bufferPath,
                initialEnabled = initialEnabled
            )
        } catch (e: Throwable) {
            when (e) {
                is UnsatisfiedLinkError, is NoClassDefFoundError, is AunException, is IllegalArgumentException -> {
                    AunLog.w("telemetry", "telemetry unavailable, using no-op service", "error" to e.message)
                    NullTelemetryService
                }
                else -> throw e
            }
        }

        /**
         * Installs a process-wide uncaught exception hook that records sanitized crashes when
         * opted in, then chains to any previously installed handler.
         */
        fun installUncaughtExceptionHandler(service: TelemetryService) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                runCatching {
                    service.reportCrash(
                        kind = throwable.javaClass.name,
                        message = throwable.message ?: throwable.javaClass.simpleName,
                        stack = throwable.stackTraceToString(),
                        fromPreviousSession = false
                    )
                }
                previous?.uncaughtException(thread, throwable)
            }
        }

        /**
         * Constructs `https://<host>[:<port>]/api/v1/telemetry/batches` from a parsed HTTPS URI,
         * rejecting embedded credentials (`userInfo`) and stripping any path, query, or fragment.
         * Returns `null` (no-op sink) for empty, non-HTTPS, or credential-bearing URLs.
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

        /** Scrubs local filesystem paths and `file://` URIs before crash or breadcrumb text enters telemetry. */
        fun sanitizeLocalPaths(text: String): String {
            if (text.isEmpty()) return text
            var scrubbed = doubleQuotedPathRegex.replace(text, "[redacted-path]")
            scrubbed = singleQuotedPathRegex.replace(scrubbed, "[redacted-path]")
            scrubbed = fileUriRegex.replace(scrubbed, "[redacted-path]")
            scrubbed = windowsPathRegex.replace(scrubbed, "[redacted-path]")
            scrubbed = unixPathRegex.replace(scrubbed, "[redacted-path]")
            return scrubbed
        }

        internal fun serializeProperties(properties: Map<String, Any?>): String? {
            if (properties.isEmpty()) return null
            val obj = buildJsonObject {
                for ((key, value) in properties) {
                    if (key.isBlank() || value == null) continue
                    when (value) {
                        is String -> put(key, JsonPrimitive(value))
                        is Boolean -> put(key, JsonPrimitive(value))
                        is Int -> put(key, JsonPrimitive(value))
                        is Long -> put(key, JsonPrimitive(value))
                        is Double -> if (value.isFinite()) put(key, JsonPrimitive(value))
                        is Float -> if (value.isFinite()) put(key, JsonPrimitive(value.toDouble()))
                        else -> put(key, JsonPrimitive(value.toString()))
                    }
                }
            }
            return if (obj.isEmpty()) null else obj.toString()
        }
    }
}
