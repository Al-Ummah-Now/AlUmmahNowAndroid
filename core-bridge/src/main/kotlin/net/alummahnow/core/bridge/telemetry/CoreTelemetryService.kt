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
    private var analyticsEpoch: Long = 0L
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
                        if (_consent == TelemetryConsent.FULL && consent != TelemetryConsent.FULL) {
                            analyticsEpoch++
                        }
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
        analyticsEpoch++
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
            tryDeleteBufferFallback()
            _lastPurgeFailedFlow.value = true
            AunLog.e("telemetry", failureMessage, e)
        }
        return Pair(!_lastPurgeFailedFlow.value, jobsToCancel)
    }

    /** Must be called while holding [gate]. */
    private fun isActiveConsentLocked(requiresFull: Boolean, expectedEpoch: Long? = null): Boolean {
        val open = !closed && !clientClosed
        val allowed = if (requiresFull) _consent == TelemetryConsent.FULL else _consent != TelemetryConsent.OFF
        val activeEpoch = if (requiresFull) analyticsEpoch else consentEpoch
        return open && allowed && (expectedEpoch == null || activeEpoch == expectedEpoch)
    }

    override fun track(name: String, properties: Map<String, Any?>) {
        if (name.isBlank()) return
        val captureEpoch = synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = true)) return
            analyticsEpoch
        }
        val safeName = sanitizeLocalPaths(name)
        if (safeName.isBlank()) return
        val json = serializeProperties(properties)
        synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = true, expectedEpoch = captureEpoch)) return
            try {
                client.track(safeName, json)
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry track skipped", "error" to e.message)
            }
        }
    }

    override fun trackError(code: String, properties: Map<String, Any?>) {
        if (code.isBlank()) return
        val captureEpoch = synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = true)) return
            analyticsEpoch
        }
        val safeCode = sanitizeLocalPaths(code)
        if (safeCode.isBlank()) return
        val json = serializeProperties(properties)
        synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = true, expectedEpoch = captureEpoch)) return
            try {
                client.trackError(safeCode, json)
            } catch (e: AunException) {
                AunLog.w("telemetry", "telemetry error skipped", "error" to e.message)
            }
        }
    }

    override fun addBreadcrumb(category: String, message: String, level: TelemetryBreadcrumbSeverity) {
        if (category.isBlank()) return
        val captureEpoch = synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = false)) return
            consentEpoch
        }
        val mapped = when (level) {
            TelemetryBreadcrumbSeverity.DEBUG -> TelemetryBreadcrumbLevel.DEBUG
            TelemetryBreadcrumbSeverity.INFO -> TelemetryBreadcrumbLevel.INFO
            TelemetryBreadcrumbSeverity.WARNING -> TelemetryBreadcrumbLevel.WARNING
            TelemetryBreadcrumbSeverity.ERROR -> TelemetryBreadcrumbLevel.ERROR
        }
        val safeCategory = sanitizeLocalPaths(category)
        val safeMessage = sanitizeLocalPaths(message)
        synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = false, expectedEpoch = captureEpoch)) return
            client.addBreadcrumb(safeCategory, safeMessage, mapped)
        }
    }

    override fun reportCrash(kind: String, message: String, stack: String?, fromPreviousSession: Boolean) {
        val captureEpoch = synchronized(gate) {
            if (!isActiveConsentLocked(requiresFull = false)) return
            consentEpoch
        }
        val safeKind = if (kind.isBlank()) "UnhandledException" else sanitizeLocalPaths(kind)
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
            if (!isActiveConsentLocked(requiresFull = false, expectedEpoch = captureEpoch)) return
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
                    val deleted = tryDeleteBufferFallback()
                    _lastPurgeFailedFlow.value = _lastPurgeFailedFlow.value || !deleted
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

        private const val VAULT_PREFIX = "\u001EAUNURL"
        private const val MAX_FILENAME_EXT_LEN = 16
        private const val MAX_FILENAME_EXT_CHECK = 17
        private const val IO_KEYWORD_VERBS =
            "open|opening|read|reading|write|writing|delete|deleting|remove|removing|rename|stat|" +
                "create|creating|load|loading|save|saving|access|accessing|unlink|" +
                "file|path|directory|folder|document|filename|filepath"
        private const val BARE_FILENAME_EXTENSIONS =
            "mov|mp4|m4v|mkv|webm|avi|flv|wmv|ogv|3gp|ts|m3u8|mpd|mp3|m4a|wav|aac|flac|ogg|opus|wma|aiff|alac|mid|midi|" +
                "jpg|jpeg|png|gif|webp|heic|svg|tif|tiff|bmp|ico|avif|jxl|raw|cr2|cr3|nef|arw|dng|" +
                "blend|psd|psb|ai|eps|indd|aep|prproj|drp|fcpx|fcpxml|kdenlive|mlt|veg|xcf|kra|fig|sketch|afdesign|afphoto|" +
                "fbx|obj|gltf|glb|stl|usd|usdz|" +
                "pdf|txt|md|rtf|doc|docx|xls|xlsx|ppt|pptx|odt|ods|odp|pages|numbers|keynote|epub|mobi|" +
                "csv|json|xml|yaml|yml|toml|ini|cfg|conf|log|srt|vtt|ass|ssa|parquet|avro|orc|feather|hdf5|h5|" +
                "zip|tar|gz|tgz|bz2|xz|7z|rar|dmg|pkg|msi|exe|apk|aab|ipa|iso|img|vhd|vhdx|vmdk|ova|ovf|" +
                "deb|rpm|appimage|snap|flatpak|cab|jar|war|ear|whl|egg|nupkg|vsix|crx|xpi|" +
                "pem|key|crt|cer|cert|der|p12|pfx|jks|jceks|keystore|kdbx|ovpn|ppk|pub|asc|gpg|pgp|sig|" +
                "db|sqlite|sqlite3|sql|dump|pkl|pickle|joblib|pt|pth|onnx|safetensors|gguf|" +
                "bin|dat|tmp|bak|old|orig|swp|swo|proj|aun|env|har|pcap|pcapng|dmp|hdmp|mdmp|heapsnapshot"
        private val sourceLineExtSet = setOf(
            "kt", "java", "swift", "cs", "cpp", "cc", "c", "h", "hpp", "rs", "py", "js", "ts", "go", "rb", "m", "mm"
        )
        private val keywordQuotedPathRegex = Regex(
            """\b(?:file|path|directory|folder|document)[ \t\p{Zs}]+(?=["'])""",
            RegexOption.IGNORE_CASE
        )
        private val keywordFilenamePrefixRegex = Regex(
            """\b(?:$IO_KEYWORD_VERBS)\b(?:[ \t\p{Zs}]*[:=][ \t\p{Zs}]*|[ \t\p{Zs}]+)""",
            RegexOption.IGNORE_CASE
        )
        private val parenPathRegexes: List<Regex> = listOf(
            Regex("""(\()(?:/|~/|[A-Za-z]:[\\/]|\\\\)[^()\r\n\u001E]+(\))""", RegexOption.IGNORE_CASE),
            Regex(
                """(\()(?![ \t]*(?:HTTP|TLS|SSL)/\d+(?:\.\d+){0,8}[.,;:!?\s]*\))(?![ \t]*\d+/\d+[.,;:!?\s]*\))""" +
                    """[^\s/\\"'<>()\[\]{}:,;=\u001E\u001F]+/[^()\r\n\u001E]+(\))""",
                RegexOption.IGNORE_CASE
            )
        )
        private val preKeywordPathRules: List<Pair<Regex, String>> = listOf(
            Regex(
                """(?:[A-Za-z]:[\\/]|\\\\)[ \t\p{Zs}]*[^\r\n\u001E\s\p{Zs}"]""" +
                    """(?:[^\r\n\u001E]*[^\r\n\u001E\s\p{Zs}])?""",
                RegexOption.IGNORE_CASE
            ) to "[redacted-path]",
            Regex(
                """(^|[\s(\[{"'=,:;<>)\u001F])(?:\\[^\s\\/"'<>()\[{:,;=\u001E\u001F]+|[^\s\\/"'<>()\[{:,;=\u001E\u001F]+)\\""" +
                    """[^\r\n\u001E\u001F\s\p{Zs}"]""" +
                    """(?:[^\r\n\u001E\u001F]*[^\r\n\u001E\u001F\s\p{Zs}])?"""
            ) to "$1[redacted-path]",
            Regex("""["']/[ \t]+[^\r\n\u001E\s\p{Zs}"][^\r\n\u001E]*""") to "[redacted-path]",
            Regex(
                """(?<![\p{L}\p{M}\p{N}._/-])""" +
                    """(?:~/(?=[ \t\p{Zs}]*[^\s\p{Zs}"])|//+(?=[ \t\p{Zs}]*[^\s\p{Zs}/"])|/(?=[ \t\p{Zs}]*[^\s\p{Zs}/"]))""" +
                    """[ \t\p{Zs}]*[^\r\n\u001E\s\p{Zs}]""" +
                    """(?:[^\r\n\u001E]*[^\r\n\u001E\s\p{Zs}])?"""
            ) to "[redacted-path]",
            Regex(
                """(^|[\s(\[{)\]}<>"'=,:;\u001F])""" +
                    """(?!(?<!['"])(?:HTTP|TLS|SSL)/\d+(?:\.\d+){0,8}(?:$|[\s(\[{)\]},;:!?]|\.(?![A-Za-z0-9])))""" +
                    """(?!(?<!['"])\d+/\d+(?:$|[\s)\]},;:!?]|\.(?![A-Za-z0-9])))""" +
                    """[^\s/\\"'<>()\[\]{}:,;=\u001E\u001F]+/[^\r\n\u001E\u001F\s\p{Zs}"]""" +
                    """(?:[^\r\n\u001E\u001F]*[^\r\n\u001E\u001F\s\p{Zs}])?""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]"
        )
        private val keywordAssignedPathRegex = Regex(
            """(\b(?:file|path|filename|filepath)\s*[:=]\s*)(?!\u001E)[^\r\n\u001E\u001F]+""",
            RegexOption.IGNORE_CASE
        )
        private val bareFilenameRegex = Regex(
            """(^|[\r\n:=;\u001F"])([ \t]*)(?=[^\r\n:=;\u001E\u001F" \t])[^\r\n:=;\u001E\u001F"]*\.""" +
                """(?!ts:\d+)(?:$BARE_FILENAME_EXTENSIONS)\b(?!\()""",
            RegexOption.IGNORE_CASE
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
        internal fun formatSanitizedCrashStack(throwable: Throwable): String {
            val sb = StringBuilder()
            val seen = java.util.IdentityHashMap<Throwable, Boolean>()
            var current: Throwable? = throwable
            var depth = 0
            while (current != null && depth < MAX_CRASH_CAUSE_DEPTH && seen.put(current, true) == null) {
                if (depth > 0) sb.append("Caused by: ")
                sb.append(current.javaClass.name).append('\n')
                for (frame in current.stackTrace) {
                    sb.append("\tat ").append(frame.toString()).append('\n')
                }
                current = current.cause
                depth++
            }
            return sb.toString().trimEnd()
        }

        private const val MAX_CRASH_CAUSE_DEPTH = 16

        fun installUncaughtExceptionHandler(service: TelemetryService) {
            synchronized(crashHandlerLock) {
                activeCrashService = service
                if (crashHandlerInstalled) return
                crashHandlerInstalled = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    runCatching {
                        val kind = throwable.javaClass.name
                        val simple = throwable.javaClass.simpleName.ifBlank { kind }
                        activeCrashService?.reportCrash(
                            kind = kind,
                            message = simple,
                            stack = formatSanitizedCrashStack(throwable),
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
            val (escaped, hadSentinels) = escapeSentinels(text)
            val preAuth = CoreTelemetryAuthScanner.redactAuthCredentials(CoreTelemetryEmailScanner.redactQuotedEmails(escaped))
            val (vaulted, vaultedUrls) = vaultNonFileUrls(preAuth, VAULT_PREFIX)
            var scrubbed = CoreTelemetryQuotedPathRedactor.redactDoubleQuotedLocalPaths(
                redactFileUris(vaulted, VAULT_PREFIX)
            )
            for (regex in parenPathRegexes) {
                scrubbed = CoreTelemetryQuotedFilenameRedactor.replaceUnquotedParenPaths(scrubbed, regex)
            }
            scrubbed = CoreTelemetryQuotedPathRedactor.replacePreKeywordPathRules(scrubbed, preKeywordPathRules)
            scrubbed = CoreTelemetryQuotedFilenameRedactor.redactQuotedFilenames(scrubbed)
            scrubbed = CoreTelemetryKeywordQuoteRedactor.replaceKeywordQuotedPaths(scrubbed, keywordQuotedPathRegex)
            scrubbed = redactKeywordUnquotedFilenames(scrubbed)
            scrubbed = CoreTelemetryQuotedPathRedactor.replaceKeywordAssignedPaths(scrubbed, keywordAssignedPathRegex)
            scrubbed = CoreTelemetryEmailScanner.redactEmails(scrubbed)
            if (scrubbed.indexOf('.') >= 0 || scrubbed.contains("\\u")) {
                scrubbed = CoreTelemetryQuotedFilenameRedactor.replaceBareFilenames(scrubbed, bareFilenameRegex)
            }
            scrubbed = restoreVaultedUrls(scrubbed, VAULT_PREFIX, vaultedUrls)
            if (vaultedUrls.isNotEmpty()) {
                scrubbed = CoreTelemetryEmailScanner.redactEmails(scrubbed)
            }
            return if (hadSentinels) unescapeSentinels(scrubbed) else scrubbed
        }

        private fun redactKeywordUnquotedFilenames(text: String): String {
            if (text.indexOf('.') < 0 && !text.contains("\\u")) return text
            return CoreTelemetryQuotedFilenameRedactor.redactKeywordUnquotedFilenamesRespectingQuotes(
                text,
                keywordFilenamePrefixRegex,
                ::findLastUnquotedFilenameExt
            )
        }

        private fun findLastUnquotedFilenameExt(text: String, segStart: Int, segEnd: Int): Pair<Int, Int>? {
            var dot = segEnd - 1
            while (dot >= segStart) {
                if (text[dot] == '.') {
                    val extStart = dot + 1
                    var extEnd = extStart
                    while (
                        extEnd < segEnd &&
                        extEnd - extStart < MAX_FILENAME_EXT_CHECK &&
                        CoreTelemetryScanners.isAsciiAlphaNum(text[extEnd])
                    ) {
                        extEnd++
                    }
                    val extLen = extEnd - extStart
                    val validEnd = extEnd == text.length ||
                        (!CoreTelemetryScanners.isAsciiWordChar(text[extEnd]) && text[extEnd] != '(')
                    val validExt = extLen in 1..MAX_FILENAME_EXT_LEN &&
                        validEnd &&
                        !isSourceLineReference(text, extStart, extEnd)
                    if (validExt) {
                        val skipTo = skipNumericDotRun(text, segStart, dot, extStart, extEnd)
                        if (skipTo < -1) return dot to extEnd
                        dot = skipTo + 1
                    }
                }
                dot--
            }
            return null
        }

        private fun skipNumericDotRun(text: String, segStart: Int, dot: Int, extStart: Int, extEnd: Int): Int {
            for (i in extStart until extEnd) {
                if (text[i] !in '0'..'9') return -2
            }
            var p = dot - 1
            var hasDigitBefore = false
            while (p >= segStart && (text[p] in '0'..'9' || text[p] == '.')) {
                if (text[p] in '0'..'9') hasDigitBefore = true
                p--
            }
            return if (hasDigitBefore && (p < segStart || !CoreTelemetryScanners.isAsciiWordChar(text[p]))) p else -2
        }

        private fun isSourceLineReference(text: String, extStart: Int, extEnd: Int): Boolean {
            val hasLineSuffix = extEnd + 1 < text.length && text[extEnd] == ':' && text[extEnd + 1] in '0'..'9'
            return hasLineSuffix && text.substring(extStart, extEnd).lowercase(Locale.ROOT) in sourceLineExtSet
        }

        private fun escapeSentinels(text: String): Pair<String, Boolean> {
            val first = text.indexOfFirst { it == '\u0018' || it == '\u001E' || it == '\u001F' }
            if (first < 0) return text to false
            val sb = StringBuilder(text.length + 8)
            sb.append(text, 0, first)
            for (i in first until text.length) {
                when (text[i]) {
                    '\u0018' -> sb.append("\u0018\u0018")
                    '\u001E' -> sb.append("\u0018\u0019")
                    '\u001F' -> sb.append("\u0018\u001A")
                    else -> sb.append(text[i])
                }
            }
            return sb.toString() to true
        }

        private fun unescapeSentinels(text: String): String {
            val first = text.indexOf('\u0018')
            if (first < 0) return text
            val sb = StringBuilder(text.length)
            sb.append(text, 0, first)
            var i = first
            while (i < text.length) {
                val decoded = if (text[i] == '\u0018' && i + 1 < text.length) {
                    decodeSentinel(text[i + 1])
                } else {
                    null
                }
                if (decoded != null) {
                    sb.append(decoded)
                    i += 2
                } else {
                    sb.append(text[i++])
                }
            }
            return sb.toString()
        }

        private fun decodeSentinel(next: Char): Char? = when (next) {
            '\u0018' -> '\u0018'
            '\u0019' -> '\u001E'
            '\u001A' -> '\u001F'
            else -> null
        }

        private class VaultLineTracker {
            private var cursor = 0
            private var hasPathOnLine = false
            private var openQuote = '\u0000'
            private var pathQuote = '\u0000'
            private var escapedBackslash = false
            private var tokenStart = -1
            private var tokenEnd = -1
            private var tokenHasPathSep = false
            private var tokenFirstSlash = -1
            private var tokenSlashCount = 0
            private var tokenHasBackslash = false
            private var tokenAllDigitsBeforeSlash = true
            private var tokenHasDigitAfterSlash = false
            private var tokenAllDigitsAfterSlash = true
            private var tokenSawPunctAfterSlash = false
            private var inWrapperRun = false
            private var wsBeforeRun = false

            fun isEmbeddedInPath(text: String, matchStart: Int): Boolean {
                advanceTo(text, matchStart)
                val lineEmbedded = pathQuote != '\u0000' || (hasPathOnLine && (!wsBeforeRun || inWrapperRun))
                return lineEmbedded || isCurrentTokenPath(text)
            }

            fun onVaultedUrl(matchEnd: Int, activeQuote: Char = '\u0000') {
                cursor = matchEnd
                resetLineState()
                openQuote = activeQuote
            }

            private fun resetTokenState() {
                tokenStart = -1
                tokenEnd = -1
                tokenFirstSlash = -1
                tokenSlashCount = 0
                tokenHasBackslash = false
                tokenAllDigitsBeforeSlash = true
                tokenHasDigitAfterSlash = false
                tokenAllDigitsAfterSlash = true
                tokenSawPunctAfterSlash = false
            }

            private fun resetLineState() {
                hasPathOnLine = false
                openQuote = '\u0000'
                pathQuote = '\u0000'
                escapedBackslash = false
                resetTokenState()
                inWrapperRun = false
                wsBeforeRun = false
            }

            private fun advanceTo(text: String, target: Int) {
                while (cursor < target) {
                    val ch = text[cursor]
                    if (ch == '\r' || ch == '\n') {
                        resetLineState()
                    } else {
                        processLineChar(text, ch)
                    }
                    cursor++
                }
            }

            private fun processLineChar(text: String, ch: Char) {
                if (handleClosingQuote(text, ch)) return
                val atTokenStart = tokenStart < 0 || inWrapperRun
                if (isFileSchemeAt(text, cursor) || startsLocalPathAt(text, cursor, atTokenStart)) {
                    hasPathOnLine = true
                    if (pathQuote == '\u0000' && openQuote != '\u0000') pathQuote = openQuote
                }
                updateRunState(text, ch)
                escapedBackslash = if (ch == '\\') !escapedBackslash else false
            }

            private fun handleClosingQuote(text: String, ch: Char): Boolean {
                if (escapedBackslash || openQuote == '\u0000' || ch != openQuote) return false
                if (isInnerApostrophe(text, cursor, ch)) return false
                promoteTokenPath(text)
                if (ch == '\'' && hasPathOnLine) {
                    // Single-quoted paths may contain unescaped apostrophes; fail closed on this line.
                    escapedBackslash = false
                    return true
                }
                if (pathQuote == ch || !hasPathOnLine) {
                    resetLineState()
                } else {
                    openQuote = '\u0000'
                    resetTokenState()
                    inWrapperRun = false
                    wsBeforeRun = false
                    escapedBackslash = false
                }
                return true
            }

            private fun promoteTokenPath(text: String) {
                if (isCurrentTokenPath(text)) {
                    hasPathOnLine = true
                    if (pathQuote == '\u0000' && openQuote != '\u0000') pathQuote = openQuote
                }
            }

            private fun updateRunState(text: String, ch: Char) {
                when {
                    ch.isWhitespace() -> if (!inWrapperRun) {
                        promoteTokenPath(text)
                        resetTokenState()
                        wsBeforeRun = true
                    }
                    isOpeningWrapper(ch) -> {
                        if (!inWrapperRun) promoteTokenPath(text)
                        if (!escapedBackslash && openQuote == '\u0000' && canOpenQuote(text, cursor, ch)) {
                            openQuote = ch
                        }
                        inWrapperRun = true
                    }
                    else -> {
                        if (tokenStart < 0 || inWrapperRun) {
                            resetTokenState()
                            tokenStart = cursor
                        }
                        tokenEnd = cursor + 1
                        trackTokenChar(text, ch)
                        inWrapperRun = false
                        wsBeforeRun = false
                    }
                }
            }

            private fun isNonPathJsonEscape(text: String): Boolean {
                val inDouble = openQuote == '"' && !escapedBackslash
                if (!inDouble || cursor + 1 >= text.length) return false
                val next = text[cursor + 1]
                if (next in "\"nrtbf") return true
                if (next == 'u' && cursor + 5 < text.length) {
                    val code = CoreTelemetryQuotedPathRedactor.parseHex4(text, cursor + 2)
                    return code >= 0 && code != '\\'.code && code != '/'.code
                }
                return false
            }

            private fun trackTokenChar(text: String, ch: Char) {
                when {
                    ch == '\\' -> if (!isNonPathJsonEscape(text)) tokenHasBackslash = true
                    ch == '/' -> {
                        if (tokenSlashCount == 0) tokenFirstSlash = cursor
                        tokenSlashCount++
                    }
                    tokenSlashCount == 0 -> if (ch !in '0'..'9') tokenAllDigitsBeforeSlash = false
                    tokenSlashCount == 1 -> trackAfterSlashChar(ch)
                }
            }

            private fun trackAfterSlashChar(ch: Char) {
                when {
                    isTrailingSentencePunct(ch) -> tokenSawPunctAfterSlash = true
                    ch in '0'..'9' -> if (tokenSawPunctAfterSlash) {
                        tokenAllDigitsAfterSlash = false
                    } else {
                        tokenHasDigitAfterSlash = true
                    }
                    else -> tokenAllDigitsAfterSlash = false
                }
            }

            private fun isCurrentTokenPath(text: String): Boolean {
                if (tokenSlashCount == 0 && !tokenHasBackslash) return false
                val singleUnquotedSlash = openQuote == '\u0000' && tokenSlashCount == 1 && !tokenHasBackslash
                if (!singleUnquotedSlash || tokenStart < 0) return true
                val beforeLen = tokenFirstSlash - tokenStart
                val validBounds = beforeLen > 0 && tokenFirstSlash + 1 < tokenEnd
                val isFraction = tokenAllDigitsBeforeSlash && tokenHasDigitAfterSlash && tokenAllDigitsAfterSlash
                return !validBounds || (!isFraction && !isProtocolPrefix(text, tokenStart, beforeLen, tokenFirstSlash + 1))
            }

            private fun isProtocolPrefix(text: String, start: Int, len: Int, afterSlash: Int): Boolean {
                val hasDigit = (len == 3 || len == 4) && text[afterSlash] in '0'..'9'
                if (!hasDigit) return false
                val isHttp = len == 4 && text.regionMatches(start, "HTTP", 0, 4, ignoreCase = true)
                val isTls = len == 3 && text.regionMatches(start, "TLS", 0, 3, ignoreCase = true)
                val isSsl = len == 3 && text.regionMatches(start, "SSL", 0, 3, ignoreCase = true)
                return isHttp || isTls || isSsl
            }
        }

        private fun canOpenQuote(text: String, pos: Int, ch: Char): Boolean = when (ch) {
            '"' -> true
            '\'' -> canOpenSingleQuote(text, pos)
            else -> false
        }

        private fun canOpenSingleQuote(text: String, pos: Int): Boolean {
            if (pos == 0 || !text[pos - 1].isLetterOrDigit()) return true
            val next = pos + 1
            return next < text.length && (isFileSchemeAt(text, next) || startsLocalPathAt(text, next, atTokenStart = true))
        }

        private fun isInnerApostrophe(text: String, pos: Int, ch: Char): Boolean =
            ch == '\'' && pos > 0 && pos + 1 < text.length && text[pos - 1].isLetterOrDigit() && text[pos + 1].isLetter()

        private fun startsLocalPathAt(text: String, pos: Int, atTokenStart: Boolean): Boolean {
            if (!atTokenStart) return false
            return when (text[pos]) {
                '/' -> true
                '~' -> pos + 1 < text.length && text[pos + 1] == '/'
                '\\' -> pos + 1 < text.length && text[pos + 1] == '\\'
                else -> CoreTelemetryScanners.isWindowsDriveAt(text, pos)
            }
        }

        private fun isTrailingSentencePunct(ch: Char): Boolean = ch in ".,;:!?)]}"

        private fun isFileSchemeAt(text: String, pos: Int): Boolean =
            CoreTelemetryScanners.isFileSchemeAt(text, pos)

        private fun isOpeningWrapper(ch: Char): Boolean = CoreTelemetryScanners.isOpeningWrapper(ch)

        private fun isPathSep(ch: Char): Boolean = CoreTelemetryScanners.isPathSep(ch)

        private fun redactFileUris(text: String, vaultPrefix: String): String {
            if (text.length < 6) return text
            var checkedQuotes = false
            var quoteSpans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>? = null
            val spanCursor = IntArray(1)
            var sb: StringBuilder? = null
            var copyStart = 0
            var pos = 0
            while (pos < text.length) {
                val schemeLen = CoreTelemetryScanners.localUriSchemeLengthAt(text, pos)
                if (schemeLen > 0) {
                    if (!checkedQuotes) {
                        checkedQuotes = true
                        if (text.indexOf('"') >= 0) {
                            quoteSpans = CoreTelemetryQuotedPathRedactor.collectBalancedDoubleQuoteSpans(text)
                        }
                    }
                    val (isDoubleQuoted, scanLimit) = resolveFileUriQuoteBounds(text, pos, quoteSpans, spanCursor)
                    val (rawEnd, trimmedEnd) = CoreTelemetryScanners.fileUriSpanEnd(
                        text,
                        pos + schemeLen,
                        isDoubleQuoted,
                        vaultPrefix,
                        scanLimit
                    )
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copyStart, pos).append("[redacted-path]").append(text, trimmedEnd, rawEnd)
                    copyStart = rawEnd
                    pos = rawEnd
                } else {
                    pos++
                }
            }
            val out = sb ?: return text
            return out.append(text, copyStart, text.length).toString()
        }

        private fun resolveFileUriQuoteBounds(
            text: String,
            pos: Int,
            spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>?,
            spanCursor: IntArray
        ): Pair<Boolean, Int> {
            val inSpan = findEnclosingQuoteSpan(pos, spans, spanCursor)
            if (inSpan != null) {
                val innerClose = CoreTelemetryQuotedPathRedactor.findInnerEscapedQuoteClose(
                    text,
                    pos,
                    inSpan.open + 1,
                    inSpan.close
                )
                return true to (if (innerClose >= 0) innerClose else text.length)
            }
            return (pos > 0 && text[pos - 1] == '"') to text.length
        }

        private fun findEnclosingQuoteSpan(
            pos: Int,
            spans: List<CoreTelemetryQuotedPathRedactor.QuotedPathSpan>?,
            spanCursor: IntArray
        ): CoreTelemetryQuotedPathRedactor.QuotedPathSpan? {
            if (spans == null) return null
            var idx = spanCursor[0]
            while (idx < spans.size && spans[idx].close <= pos) idx++
            spanCursor[0] = idx
            val candidate = if (idx < spans.size) spans[idx] else null
            return if (candidate != null && candidate.open < pos && pos < candidate.close) candidate else null
        }

        private fun vaultNonFileUrls(text: String, prefix: String): Pair<String, List<String>> {
            val vaultedUrls = mutableListOf<String>()
            val withEscaped = CoreTelemetryQuotedPathRedactor.vaultJsonEscapedUrlsInBalancedQuotes(
                text,
                prefix,
                vaultedUrls
            ) { inner, innerPrefix, innerVaulted -> vaultNonFileUrlsInto(inner, innerPrefix, innerVaulted) }
            val scrubbed = vaultNonFileUrlsInto(withEscaped, prefix, vaultedUrls)
            return scrubbed to vaultedUrls
        }

        private fun vaultNonFileUrlsInto(text: String, prefix: String, vaultedUrls: MutableList<String>): String {
            if (text.indexOf("://") < 0) return text
            val tracker = VaultLineTracker()
            val cont = CoreTelemetryUrlContinuation
            var sb: StringBuilder? = null
            var copied = 0
            var searchFrom = 0
            var quoteCursor = 0
            var quoteState = 0
            val contLimits = IntArray(5)
            while (searchFrom < text.length) {
                val sep = text.indexOf("://", searchFrom)
                if (sep < 0) break
                val start = CoreTelemetryScanners.findNonFileSchemeStart(text, sep, copied)
                val span = if (start != null && !tracker.isEmbeddedInPath(text, start)) {
                    if (start > quoteCursor) {
                        quoteState = cont.advanceQuoteState(text, quoteCursor, start, quoteState)
                        quoteCursor = start
                    }
                    val angleMask = CoreTelemetryScanners.computeQueryAngleStopMask(text, start, (quoteState and 2) != 0)
                    CoreTelemetryScanners.scanUrlAfterScheme(text, start, sep, angleMask)
                } else {
                    null
                }
                if (span == null) {
                    searchFrom = sep + 3
                } else {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copied, span.start)
                    val (endPos, finalTail) = emitVaultedUrlMatch(out, text, span, quoteState, prefix, vaultedUrls, contLimits)
                    quoteState = cont.advanceQuoteState(finalTail, 0, finalTail.length, quoteState)
                    tracker.onVaultedUrl(endPos, activeQuoteFromState(quoteState))
                    copied = endPos
                    quoteCursor = endPos
                    searchFrom = endPos
                }
            }
            val out = sb ?: return text
            return out.append(text, copied, text.length).toString()
        }

        @Suppress("LongParameterList")
        private fun emitVaultedUrlMatch(
            out: StringBuilder,
            text: String,
            span: CoreTelemetryScanners.UrlSpan,
            quoteState: Int,
            prefix: String,
            vaultedUrls: MutableList<String>,
            contLimits: IntArray
        ): Pair<Int, String> {
            val cont = CoreTelemetryUrlContinuation
            val (url, baseTail) = cont.formatVaultedUrl(span, text, (quoteState and 1) != 0)
            val baseRedacted = CoreTelemetryScanners.redactUrlPathSecrets(url)
            val rawUrl = text.substring(span.start, span.baseEnd)
            val contInput = CoreTelemetryUrlContinuation.ContinuationInput(span, rawUrl, baseTail, quoteState)
            val extended = cont.extendUrlContinuation(text, contInput, contLimits)
                ?: cont.extendSpacedQuotedUrlQuery(text, contInput, contLimits)
            if (extended != null) {
                cont.emitUrlContinuation(out, prefix, vaultedUrls, baseRedacted, baseTail, extended) { inner ->
                    vaultNonFileUrlsInto(inner, prefix, vaultedUrls)
                }
                return extended.endPos to extended.tail
            }
            val urls = Triple(rawUrl, baseRedacted, baseTail)
            return emitSimpleVaultedUrl(out, text, span, quoteState, urls, prefix, vaultedUrls, contLimits)
        }

        @Suppress("LongParameterList")
        private fun emitSimpleVaultedUrl(
            out: StringBuilder,
            text: String,
            span: CoreTelemetryScanners.UrlSpan,
            quoteState: Int,
            urls: Triple<String, String, String>,
            prefix: String,
            vaultedUrls: MutableList<String>,
            contLimits: IntArray
        ): Pair<Int, String> {
            val quotedSecret = CoreTelemetryScanners.resolveQuotedSecretUrlTail(
                text,
                span,
                (quoteState and 2) != 0,
                urls.first,
                urls.second,
                contLimits
            )
            val idx = vaultedUrls.size
            vaultedUrls += quotedSecret?.first ?: urls.second
            val emitTail = if (quotedSecret != null) "" else urls.third
            out.append(prefix).append(idx).append('\u001F').append(emitTail)
            return (quotedSecret?.second ?: span.totalEnd) to emitTail
        }

        private fun activeQuoteFromState(quoteState: Int): Char = when {
            (quoteState and 2) != 0 -> '"'
            (quoteState and 1) != 0 -> '\''
            else -> '\u0000'
        }

        private fun restoreVaultedUrls(text: String, prefix: String, vaultedUrls: List<String>): String {
            if (vaultedUrls.isEmpty()) return text
            val sb = StringBuilder(text.length)
            var pos = 0
            while (pos < text.length) {
                val idx = text.indexOf(prefix, pos)
                if (idx < 0) {
                    sb.append(text, pos, text.length)
                    break
                }
                sb.append(text, pos, idx)
                val numStart = idx + prefix.length
                val numEnd = CoreTelemetryEscapedUrlVault.scanVaultIndexEnd(text, numStart, text.length)
                val urlIndex = if (numEnd > numStart) text.substring(numStart, numEnd).toIntOrNull() else null
                if (urlIndex != null && urlIndex in vaultedUrls.indices) {
                    sb.append(vaultedUrls[urlIndex])
                    pos = numEnd + 1
                } else {
                    sb.append(prefix)
                    pos = numStart
                }
            }
            return sb.toString()
        }

        private fun isAuthHeaderPropertyKey(key: String): Boolean {
            val trimmed = key.trim()
            if (CoreTelemetryAuthEscapedHelpers.matchCanonicalAuthHeaderName(trimmed) != null) return true
            val lower = trimmed.lowercase(java.util.Locale.ROOT)
            return lower == "token" ||
                lower == "secret" ||
                lower == "jwt" ||
                lower == "bearer" ||
                lower.endsWith("_token") ||
                lower.endsWith("-token") ||
                lower.endsWith("_secret") ||
                lower.endsWith("-secret") ||
                lower.endsWith("_password") ||
                lower.endsWith("-password") ||
                lower.endsWith("_credential") ||
                lower.endsWith("-credential")
        }

        private fun resolveUniquePropertyKey(
            usedKeys: MutableSet<String>,
            nextSuffixByBase: MutableMap<String, Int>,
            base: String
        ): String {
            if (usedKeys.add(base)) return base
            var suffix = nextSuffixByBase[base] ?: 2
            while (!usedKeys.add("$base#$suffix")) suffix++
            nextSuffixByBase[base] = suffix + 1
            return "$base#$suffix"
        }

        internal fun serializeProperties(properties: Map<String, Any?>): String? {
            if (properties.isEmpty()) return null
            val usedKeys = HashSet<String>(properties.size)
            val nextSuffixByBase = HashMap<String, Int>()
            val obj = buildJsonObject {
                for ((key, value) in properties) {
                    if (key.isNotBlank() && value != null) {
                        putSanitizedProperty(key, value, usedKeys, nextSuffixByBase)
                    }
                }
            }
            return if (obj.isEmpty()) null else obj.toString()
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putSanitizedProperty(
            key: String,
            value: Any,
            usedKeys: MutableSet<String>,
            nextSuffixByBase: MutableMap<String, Int>
        ) {
            val safeKey = sanitizeLocalPaths(key)
            if (safeKey.isBlank()) return
            val uniqueKey = resolveUniquePropertyKey(usedKeys, nextSuffixByBase, safeKey)
            if (isAuthHeaderPropertyKey(key)) {
                put(uniqueKey, JsonPrimitive("[redacted]"))
                return
            }
            when (value) {
                is String -> put(uniqueKey, JsonPrimitive(sanitizeLocalPaths(value)))
                is Boolean -> put(uniqueKey, JsonPrimitive(value))
                is Int -> put(uniqueKey, JsonPrimitive(value))
                is Long -> put(uniqueKey, JsonPrimitive(value))
                is Double -> if (value.isFinite()) put(uniqueKey, JsonPrimitive(value))
                is Float -> if (value.isFinite()) put(uniqueKey, JsonPrimitive(value.toDouble()))
                else -> put(uniqueKey, JsonPrimitive(sanitizeLocalPaths(value.toString())))
            }
        }
    }
}
