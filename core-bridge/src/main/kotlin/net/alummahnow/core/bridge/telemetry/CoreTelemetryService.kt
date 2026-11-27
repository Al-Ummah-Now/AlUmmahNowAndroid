package net.alummahnow.core.bridge.telemetry

import android.os.Build
import java.net.URI
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _lastPurgeFailedFlow = MutableStateFlow(false)
    private var inFlightFlushes: Int = 0
    private var consentEpoch: Long = 0L
    private var revokedFlushesRemaining: Int = 0
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
        get() = _lastPurgeFailedFlow.value

    override val lastPurgeFailedFlow: StateFlow<Boolean> = _lastPurgeFailedFlow.asStateFlow()

    override fun setConsent(consent: TelemetryConsent): Boolean {
        val mapped = when (consent) {
            TelemetryConsent.FULL -> TelemetryConsentState.ANALYTICS
            TelemetryConsent.CRASH_ONLY -> TelemetryConsentState.CRASH_ONLY
            TelemetryConsent.OFF -> TelemetryConsentState.DENIED
        }

        val jobsToCancel: List<Job>
        val succeeded: Boolean
        synchronized(gate) {
            if (consent == TelemetryConsent.OFF) {
                val (purged, cancelList) = revokeLocked("telemetry disk purge failed on opt-out")
                jobsToCancel = cancelList
                succeeded = purged
            } else if (closed || clientClosed) {
                return false
            } else {
                var cancelOnFail: List<Job> = emptyList()
                succeeded = if (revokedFlushesRemaining > 0 || (_lastPurgeFailedFlow.value && !tryDeleteBufferFallback())) {
                    false
                } else {
                    try {
                        _lastPurgeFailedFlow.value = false
                        client.setConsent(mapped)
                        _consent = consent
                        revoked = false
                        true
                    } catch (e: AunException) {
                        // Fail closed in both wrapper and native state so an earlier native update
                        // cannot leave collection active for a queued flush.
                        val (_, cancelList) = revokeLocked("telemetry rollback purge failed")
                        cancelOnFail = cancelList
                        AunLog.w("telemetry", "failed to update telemetry consent; failing closed", "error" to e.message)
                        false
                    }
                }
                jobsToCancel = cancelOnFail
            }
        }
        jobsToCancel.forEach { it.cancel() }
        return succeeded
    }

    /** Must be called while holding [gate]. */
    private fun revokeLocked(failureMessage: String): Pair<Boolean, List<Job>> {
        _consent = TelemetryConsent.OFF
        revoked = true
        consentEpoch++
        revokedFlushesRemaining = inFlightFlushes
        val jobsToCancel = flushJobs.toList()
        flushJobs.clear()
        if (clientClosed) {
            _lastPurgeFailedFlow.value = !tryDeleteBufferFallback()
            return Pair(!_lastPurgeFailedFlow.value, jobsToCancel)
        }
        try {
            client.setConsent(TelemetryConsentState.DENIED)
            _lastPurgeFailedFlow.value = !tryDeleteSiblingTempFiles()
        } catch (e: AunException) {
            _lastPurgeFailedFlow.value = !tryDeleteBufferFallback()
            AunLog.e("telemetry", failureMessage, e)
        }
        return Pair(!_lastPurgeFailedFlow.value, jobsToCancel)
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
            val flushEpoch = consentEpoch
            job = flushScope.launch(start = CoroutineStart.LAZY) {
                try {
                    client.flush()
                } catch (_: CancellationException) {
                    // Cancelled by opt-out or shutdown.
                } catch (e: AunException) {
                    val isRevoked = synchronized(gate) { revoked || closed }
                    if (!isRevoked) {
                        AunLog.w("telemetry", "telemetry flush skipped", "error" to e.message)
                    }
                }
            }
            // Register completion cleanup before start so even a pre-dispatch cancel releases inFlightFlushes.
            job.invokeOnCompletion { releaseInFlightFlush(job, flushEpoch) }
            flushJobs += job
        }
        job.start()
        job.join()
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

    private fun releaseInFlightFlush(job: Job, flushEpoch: Long) {
        synchronized(gate) {
            flushJobs.remove(job)
            inFlightFlushes = (inFlightFlushes - 1).coerceAtLeast(0)
            if (flushEpoch < consentEpoch) {
                revokedFlushesRemaining = (revokedFlushesRemaining - 1).coerceAtLeast(0)
                if (!bufferPath.isNullOrBlank()) {
                    // Clean up any remnant if a flush already in flight before revocation completed
                    // its final persist rename after setConsent(DENIED) deleted the buffer file.
                    // Ordinary close() without revocation does not advance consentEpoch, keeping retryable buffers.
                    _lastPurgeFailedFlow.value = !tryDeleteBufferFallback()
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
        val tempsOk = tryDeleteSiblingTempFiles()
        return mainOk && tempsOk
    }

    @Suppress("TooGenericExceptionCaught")
    private fun tryDeleteSiblingTempFiles(): Boolean {
        val path = bufferPath?.takeIf { it.isNotBlank() } ?: return true
        val nioPath = runCatching { Paths.get(path) }.getOrNull()
        val parent = nioPath?.parent
        val name = nioPath?.fileName?.toString()
        return when {
            nioPath == null -> false
            parent == null || name == null -> true
            else -> deleteMatchingTempFiles(parent, ".$name.*.tmp")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun deleteMatchingTempFiles(parent: Path, glob: String): Boolean = try {
        var allDeleted = true
        Files.newDirectoryStream(parent, glob).use { stream ->
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

    companion object {
        private val crashHandlerLock = Any()

        @Volatile
        private var crashHandlerInstalled = false

        @Volatile
        private var activeCrashService: TelemetryService? = null

        private val urlVaultRegex = Regex(
            """(?<![A-Za-z0-9+.-])[A-Za-z][A-Za-z0-9+.-]+://(?!\S*\\)[^\s"'<>\\{}\r\n]+""",
            RegexOption.IGNORE_CASE
        )
        private val ioKeywordVerbs =
            "open|opening|read|reading|write|writing|delete|deleting|remove|removing|rename|stat|" +
                "create|creating|load|loading|save|saving|access|accessing|unlink|" +
                "file|path|directory|folder|document|filename|filepath"
        private val bareFilenameExtensions =
            "mov|mp4|m4v|mkv|webm|avi|ts|m3u8|mpd|mp3|m4a|wav|aac|flac|ogg|opus|" +
                "jpg|jpeg|png|gif|webp|heic|svg|pdf|txt|md|rtf|doc|docx|xls|xlsx|ppt|pptx|" +
                "csv|json|xml|yaml|yml|toml|ini|cfg|conf|log|srt|vtt|ass|ssa|" +
                "zip|tar|gz|tgz|bz2|xz|7z|rar|dmg|pkg|msi|exe|apk|aab|ipa|" +
                "pem|key|crt|cer|p12|pfx|jks|keystore|db|sqlite|sqlite3|bin|dat|tmp|bak|proj|aun"
        private val pathRedactionRules: List<Pair<Regex, String>> = listOf(
            Regex("""(?<![A-Za-z0-9+.-])file://[^\r\n]+""", RegexOption.IGNORE_CASE) to "[redacted-path]",
            Regex("""(?:[A-Za-z]:[\\/]|\\\\)[^\r\n]+""", RegexOption.IGNORE_CASE) to "[redacted-path]",
            Regex("""(^|[\s(\[{"'=,:;])[^\s\\/"'<>]+\\[^\r\n]+""") to "$1[redacted-path]",
            Regex("""["']/[ \t]+[^\r\n]+""") to "[redacted-path]",
            Regex("""(?<![\p{L}\p{M}\p{N}._/-])(?:~/|//+(?=[ \t]*[^\s/])|/(?=[ \t]*[^\s/]))[^\r\n]+""") to "[redacted-path]",
            Regex(
                """(^|[\s(\[{"'=,:;])(?!HTTP/\d)(?!TLS/\d)(?!SSL/\d)[^\s/\\"'<>()\[\]{}:]+/[^\r\n]+""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]",
            Regex(""""[^"\r\n/\\]*\.[A-Za-z0-9]{1,16}"""") to "[redacted-path]",
            Regex("""'(?:[^'\r\n/\\]|'(?=\p{L}))*\.[A-Za-z0-9]{1,16}'""") to "[redacted-path]",
            Regex(
                """(\b(?:file|path|directory|folder|document)\s+)(['"])(?!\u001E)[^'"\r\n]+\2""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]",
            Regex(
                """(\b(?:$ioKeywordVerbs)\b(?:\s*[:=]\s*|\s+))(?!\u001E)(?=[^\r\n]*\.[A-Za-z][A-Za-z0-9]{0,15}\b(?!\s*\())[^\r\n]+""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]",
            Regex(
                """(\b(?:file|path|filename|filepath)\s*[:=]\s*)(?!\u001E)[^\r\n]+""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]",
            Regex(
                """(?<![\p{L}\p{N}._-])[\p{L}\p{N}_-]+\.(?:$bareFilenameExtensions)\b(?!\s*\()""",
                RegexOption.IGNORE_CASE
            ) to "[redacted-path]"
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
         * Installs a single process-wide uncaught exception hook that records sanitized crashes
         * through the latest registered [TelemetryService] when opted in, then chains to any
         * previously installed handler. Subsequent calls swap the active service without chaining
         * stale instances.
         */
        fun installUncaughtExceptionHandler(service: TelemetryService) {
            synchronized(crashHandlerLock) {
                activeCrashService = service
                if (crashHandlerInstalled) return
                crashHandlerInstalled = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    runCatching {
                        activeCrashService?.reportCrash(
                            kind = throwable.javaClass.name,
                            message = throwable.message ?: throwable.javaClass.simpleName,
                            stack = throwable.stackTraceToString(),
                            fromPreviousSession = false
                        )
                    }
                    previous?.uncaughtException(thread, throwable)
                }
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

        /** Scrubs local paths, relative paths, bare filenames, and `file://` URIs while preserving non-`file` URLs. */
        fun sanitizeLocalPaths(text: String): String {
            if (text.isEmpty()) return text
            var prefix = "\u001EAUNURL"
            while (text.contains(prefix)) {
                prefix += "X"
            }
            val vaultedUrls = mutableListOf<String>()
            var scrubbed = urlVaultRegex.replace(text) { match ->
                if (match.value.startsWith("file://", ignoreCase = true)) {
                    match.value
                } else {
                    val (url, tail) = splitUnmatchedUrlClosers(match.value)
                    val idx = vaultedUrls.size
                    vaultedUrls += url
                    "$prefix$idx\u001F$tail"
                }
            }
            for ((regex, template) in pathRedactionRules) {
                scrubbed = regex.replace(scrubbed, template)
            }
            for (idx in vaultedUrls.indices) {
                scrubbed = scrubbed.replace("$prefix$idx\u001F", vaultedUrls[idx])
            }
            return scrubbed
        }

        private fun splitUnmatchedUrlClosers(raw: String): Pair<String, String> {
            var openParens = 0
            var closeParens = 0
            var openBrackets = 0
            var closeBrackets = 0
            for (ch in raw) {
                when (ch) {
                    '(' -> openParens++
                    ')' -> closeParens++
                    '[' -> openBrackets++
                    ']' -> closeBrackets++
                }
            }
            var end = raw.length
            while (end > 0) {
                when {
                    raw[end - 1] == ')' && closeParens > openParens -> {
                        closeParens--
                        end--
                    }
                    raw[end - 1] == ']' && closeBrackets > openBrackets -> {
                        closeBrackets--
                        end--
                    }
                    else -> break
                }
            }
            return raw.substring(0, end) to raw.substring(end)
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
