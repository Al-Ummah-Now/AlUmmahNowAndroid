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
            tryDeleteBufferFallback()
            _lastPurgeFailedFlow.value = true
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
            "mov|mp4|m4v|mkv|webm|avi|ts|m3u8|mpd|mp3|m4a|wav|aac|flac|ogg|opus|" +
                "jpg|jpeg|png|gif|webp|heic|svg|pdf|txt|md|rtf|doc|docx|xls|xlsx|ppt|pptx|" +
                "csv|json|xml|yaml|yml|toml|ini|cfg|conf|log|srt|vtt|ass|ssa|" +
                "zip|tar|gz|tgz|bz2|xz|7z|rar|dmg|pkg|msi|exe|apk|aab|ipa|" +
                "pem|key|crt|cer|p12|pfx|jks|keystore|db|sqlite|sqlite3|bin|dat|tmp|bak|proj|aun"
        private val sourceLineExtSet = setOf(
            "kt", "java", "swift", "cs", "cpp", "cc", "c", "h", "hpp", "rs", "py", "js", "ts", "go", "rb", "m", "mm"
        )
        private val keywordQuotedPathRegex = Regex(
            """(\b(?:file|path|directory|folder|document)[^\S\r\n]+)(['"])(?!\u001E)[^'"\r\n]+\2""",
            RegexOption.IGNORE_CASE
        )
        private val keywordFilenamePrefixRegex = Regex(
            """\b(?:$IO_KEYWORD_VERBS)\b(?:[^\S\r\n]*[:=][^\S\r\n]*|[^\S\r\n]+)""",
            RegexOption.IGNORE_CASE
        )
        private val preKeywordPathRules: List<Pair<Regex, String>> = listOf(
            Regex("""(\()(?:/|~/|[A-Za-z]:[\\/]|\\\\)[^()\r\n\u001E]+(\))""", RegexOption.IGNORE_CASE) to "$1[redacted-path]$2",
            Regex(
                """(\()(?![ \t]*(?:HTTP|TLS|SSL)/\d)(?![ \t]*\d+/\d+[.,;:!?\s]*\))""" +
                    """[^\s/\\"'<>()\[\]{}:,;=\u001E\u001F]+/[^()\r\n\u001E]+(\))""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]$2",
            Regex(
                """(?:[A-Za-z]:[\\/]|\\\\)[^\S\r\n]*[^\r\n\u001E\s]+(?:[^\S\r\n]+[^\r\n\u001E\s]+)*""",
                RegexOption.IGNORE_CASE
            ) to "[redacted-path]",
            Regex(
                """(^|[\s(\[{"'=,:;\u001F])[^\s\\/"'<>()\[{:,;=\u001E\u001F]+\\""" +
                    """[^\r\n\u001E\u001F\s]+(?:[^\S\r\n]+[^\r\n\u001E\u001F\s]+)*"""
            ) to "$1[redacted-path]",
            Regex("""["']/[ \t]+[^\r\n\u001E]+""") to "[redacted-path]",
            Regex(
                """(?<![\p{L}\p{M}\p{N}._/-])(?:~/|//+(?=[ \t]*[^\s/])|/(?=[ \t]*[^\s/]))""" +
                    """[^\S\r\n]*[^\r\n\u001E\s]+(?:[^\S\r\n]+[^\r\n\u001E\s]+)*"""
            ) to "[redacted-path]",
            Regex(
                """(^|[\s(\[{"'=,:;\u001F])(?!(?<!['"])(?:HTTP|TLS|SSL)/\d)""" +
                    """(?!(?<!['"])\d+/\d+(?:$|[\s)\]},;:!?]|\.(?![A-Za-z0-9])))""" +
                    """[^\s/\\"'<>()\[\]{}:,;=\u001E\u001F]+/[^\r\n\u001E\u001F\s]+(?:[^\S\r\n]+[^\r\n\u001E\u001F\s]+)*""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]"
        )
        private val postKeywordPathRules: List<Pair<Regex, String>> = listOf(
            Regex(
                """(\b(?:file|path|filename|filepath)\s*[:=]\s*)(?!\u001E)[^\r\n\u001E\u001F]+""",
                RegexOption.IGNORE_CASE
            ) to "$1[redacted-path]",
            Regex(
                """(^|[\r\n:=;\u001F])([ \t]*)(?=[^\r\n:=;\u001E\u001F \t])[^\r\n:=;\u001E\u001F]*\.""" +
                    """(?!ts:\d+)(?:$BARE_FILENAME_EXTENSIONS)\b(?!\()""",
                RegexOption.IGNORE_CASE
            ) to "$1$2[redacted-path]"
        )
        private val bearerTokenRegex = Regex("""\bBearer\s+\S+""", RegexOption.IGNORE_CASE)

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
            val (escaped, hadSentinels) = escapeSentinels(text)
            val (vaulted, vaultedUrls) = vaultNonFileUrls(escaped, VAULT_PREFIX)
            var scrubbed = redactFileUris(vaulted, VAULT_PREFIX)
            for ((regex, template) in preKeywordPathRules) {
                scrubbed = regex.replace(scrubbed, template)
            }
            scrubbed = CoreTelemetryScanners.redactQuotedFilenames(scrubbed)
            scrubbed = keywordQuotedPathRegex.replace(scrubbed, "$1[redacted-path]")
            scrubbed = redactKeywordUnquotedFilenames(scrubbed)
            for ((regex, template) in postKeywordPathRules) {
                scrubbed = regex.replace(scrubbed, template)
            }
            scrubbed = restoreVaultedUrls(scrubbed, VAULT_PREFIX, vaultedUrls)
            scrubbed = CoreTelemetryScanners.redactEmails(scrubbed)
            scrubbed = bearerTokenRegex.replace(scrubbed, "Bearer [redacted]")
            return if (hadSentinels) unescapeSentinels(scrubbed) else scrubbed
        }

        private fun redactKeywordUnquotedFilenames(text: String): String {
            if (text.indexOf('.') < 0) return text
            var sb: StringBuilder? = null
            var copied = 0
            var segStart = 0
            while (segStart < text.length) {
                var segEnd = segStart
                while (segEnd < text.length && !isSegmentStop(text[segEnd])) segEnd++
                val extBounds = findLastUnquotedFilenameExt(text, segStart, segEnd)
                if (extBounds != null) {
                    val kwMatch = keywordFilenamePrefixRegex.find(text.substring(segStart, extBounds.first))
                    if (kwMatch != null) {
                        val kwEnd = segStart + kwMatch.range.last + 1
                        val out = sb ?: StringBuilder(text.length).also { sb = it }
                        out.append(text, copied, kwEnd).append("[redacted-path]")
                        copied = extBounds.second
                    }
                }
                segStart = segEnd + 1
            }
            val out = sb ?: return text
            return out.append(text, copied, text.length).toString()
        }

        private fun isSegmentStop(ch: Char): Boolean = ch in "\r\n\u001E\u001F"

        private fun findLastUnquotedFilenameExt(text: String, segStart: Int, segEnd: Int): Pair<Int, Int>? {
            for (dot in segEnd - 1 downTo segStart) {
                if (text[dot] != '.') continue
                val extStart = dot + 1
                var extEnd = extStart
                while (extEnd < segEnd && extEnd - extStart < MAX_FILENAME_EXT_CHECK &&
                    CoreTelemetryScanners.isAsciiAlphaNum(text[extEnd])
                ) {
                    extEnd++
                }
                val extLen = extEnd - extStart
                val validEnd = extEnd == text.length ||
                    (!CoreTelemetryScanners.isAsciiWordChar(text[extEnd]) && text[extEnd] != '(')
                if (extLen in 1..MAX_FILENAME_EXT_LEN && validEnd && !isSourceLineReference(text, extStart, extEnd)) {
                    return dot to extEnd
                }
            }
            return null
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
            private var inWrapperRun = false
            private var wsBeforeRun = false

            fun isEmbeddedInPath(text: String, matchStart: Int): Boolean {
                advanceTo(text, matchStart)
                val curTokenIsPath = tokenHasPathSep && !isNonPathSlashToken(text, tokenStart, tokenEnd, openQuote != '\u0000')
                return pathQuote != '\u0000' || curTokenIsPath || (hasPathOnLine && (!wsBeforeRun || inWrapperRun))
            }

            fun onVaultedUrl(matchEnd: Int) {
                cursor = matchEnd
                resetLineState()
            }

            private fun resetLineState() {
                hasPathOnLine = false
                openQuote = '\u0000'
                pathQuote = '\u0000'
                escapedBackslash = false
                tokenStart = -1
                tokenEnd = -1
                tokenHasPathSep = false
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
                if (pathQuote == ch || !hasPathOnLine) {
                    resetLineState()
                } else {
                    openQuote = '\u0000'
                    tokenStart = -1
                    tokenEnd = -1
                    tokenHasPathSep = false
                    inWrapperRun = false
                    wsBeforeRun = false
                    escapedBackslash = false
                }
                return true
            }

            private fun promoteTokenPath(text: String) {
                if (tokenHasPathSep && !isNonPathSlashToken(text, tokenStart, tokenEnd, openQuote != '\u0000')) {
                    hasPathOnLine = true
                    if (pathQuote == '\u0000' && openQuote != '\u0000') pathQuote = openQuote
                }
            }

            private fun updateRunState(text: String, ch: Char) {
                when {
                    ch.isWhitespace() -> if (!inWrapperRun) {
                        promoteTokenPath(text)
                        tokenStart = -1
                        tokenEnd = -1
                        wsBeforeRun = true
                        tokenHasPathSep = false
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
                            tokenStart = cursor
                            tokenHasPathSep = false
                        }
                        tokenEnd = cursor + 1
                        if (isPathSep(ch)) tokenHasPathSep = true
                        inWrapperRun = false
                        wsBeforeRun = false
                    }
                }
            }
        }

        private fun canOpenQuote(text: String, pos: Int, ch: Char): Boolean = when (ch) {
            '"' -> true
            '\'' -> pos == 0 || !text[pos - 1].isLetterOrDigit()
            else -> false
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

        private fun isNonPathSlashToken(text: String, start: Int, end: Int, insideQuote: Boolean): Boolean {
            if (insideQuote || start < 0 || end <= start) return false
            var trimmedEnd = end
            while (trimmedEnd > start && isTrailingSentencePunct(text[trimmedEnd - 1])) trimmedEnd--
            return trimmedEnd > start && isAllowedNonPathSlash(text.substring(start, trimmedEnd))
        }

        private fun isAllowedNonPathSlash(token: String): Boolean {
            val slashIdx = token.indexOf('/')
            val validSplit = slashIdx > 0 && slashIdx + 1 < token.length && !token.contains('\\')
            if (!validSplit || token.indexOf('/', slashIdx + 1) >= 0) return false
            val before = token.substring(0, slashIdx)
            val after = token.substring(slashIdx + 1)
            return isProtocolPair(before, after) || (before.all { it in '0'..'9' } && after.all { it in '0'..'9' })
        }

        private fun isTrailingSentencePunct(ch: Char): Boolean = ch in ".,;:!?)]}"

        private fun isProtocolPair(before: String, after: String): Boolean {
            if (after[0] !in '0'..'9') return false
            val upper = before.uppercase(Locale.ROOT)
            return upper == "HTTP" || upper == "TLS" || upper == "SSL"
        }

        private fun isFileSchemeAt(text: String, pos: Int): Boolean =
            CoreTelemetryScanners.isFileSchemeAt(text, pos)

        private fun isOpeningWrapper(ch: Char): Boolean = CoreTelemetryScanners.isOpeningWrapper(ch)

        private fun isPathSep(ch: Char): Boolean = CoreTelemetryScanners.isPathSep(ch)

        private fun redactFileUris(text: String, vaultPrefix: String): String {
            val schemeLen = 7
            if (text.length < schemeLen) return text
            var sb: StringBuilder? = null
            var copyStart = 0
            var pos = 0
            while (pos < text.length) {
                if (CoreTelemetryScanners.isFileSchemeAt(text, pos)) {
                    val quote = if (pos > 0 && (text[pos - 1] == '"' || text[pos - 1] == '\'')) text[pos - 1] else '\u0000'
                    val (rawEnd, trimmedEnd) = CoreTelemetryScanners.fileUriSpanEnd(text, pos + schemeLen, quote, vaultPrefix)
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

        private fun vaultNonFileUrls(text: String, prefix: String): Pair<String, List<String>> {
            if (text.indexOf("://") < 0) return text to emptyList()
            val vaultedUrls = mutableListOf<String>()
            val tracker = VaultLineTracker()
            var sb: StringBuilder? = null
            var copied = 0
            var searchFrom = 0
            while (searchFrom < text.length) {
                val sep = text.indexOf("://", searchFrom)
                if (sep < 0) break
                val span = CoreTelemetryScanners.scanNonFileUrl(text, sep, copied)
                if (span == null || tracker.isEmbeddedInPath(text, span.start)) {
                    searchFrom = sep + 3
                } else {
                    val (url, tail) = formatVaultedUrl(span, text)
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.append(text, copied, span.start)
                    val idx = vaultedUrls.size
                    vaultedUrls += url
                    tracker.onVaultedUrl(span.totalEnd)
                    out.append(prefix).append(idx).append('\u001F').append(tail)
                    copied = span.totalEnd
                    searchFrom = span.totalEnd
                }
            }
            val out = sb ?: return text to emptyList()
            return out.append(text, copied, text.length).toString() to vaultedUrls
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
                val numEnd = text.indexOf('\u001F', numStart)
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

        private fun formatVaultedUrl(span: CoreTelemetryScanners.UrlSpan, text: String): Pair<String, String> {
            val baseUrl = text.substring(span.start, span.baseEnd)
            if (span.hasQf) {
                val qf = text.substring(span.baseEnd, span.totalEnd)
                val (cleanQf, tail) = trimQueryOrFragmentDelimiters(qf, text, span.start)
                return "$baseUrl${cleanQf.first()}[redacted]" to tail
            }
            var cleanBase = baseUrl
            var tail = ""
            if (cleanBase.endsWith(';')) {
                cleanBase = cleanBase.dropLast(1)
                tail = ";"
            }
            if (span.start > 0 && text[span.start - 1] == '\'' && cleanBase.endsWith('\'')) {
                cleanBase = cleanBase.dropLast(1)
                tail = "'$tail"
            }
            return cleanBase to tail
        }

        private fun trimQueryOrFragmentDelimiters(qf: String, text: String, matchStart: Int): Pair<String, String> {
            val excess = countExcessClosers(qf)
            var end = qf.length
            val precededByQuote = matchStart > 0 && text[matchStart - 1] == '\''
            while (end > 1 && shouldTrimCloser(qf[end - 1], precededByQuote, excess)) {
                end--
            }
            return qf.substring(0, end) to qf.substring(end)
        }

        private fun countExcessClosers(qf: String): IntArray {
            val excess = IntArray(3)
            for (ch in qf) {
                when (ch) {
                    '(' -> excess[0]--
                    ')' -> excess[0]++
                    '[' -> excess[1]--
                    ']' -> excess[1]++
                    '{' -> excess[2]--
                    '}' -> excess[2]++
                }
            }
            return excess
        }

        private fun shouldTrimCloser(ch: Char, precededByQuote: Boolean, excess: IntArray): Boolean {
            val slot = when (ch) {
                ')' -> 0
                ']' -> 1
                '}' -> 2
                else -> -1
            }
            if (slot >= 0) {
                if (excess[slot] <= 0) return false
                excess[slot]--
                return true
            }
            return ch == '.' || ch == ';' || ch == ',' || (ch == '\'' && precededByQuote)
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
