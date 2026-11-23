package net.alummahnow.player.offline

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

enum class OfflineDownloadQuality(val wireName: String) {
    AUDIO("audio"),
    Q360P("360p"),
    Q480P("480p"),
    Q720P("720p"),
    Q1080P("1080p");

    fun fileExtension(): String = if (this == AUDIO) "m4a" else "mp4"

    companion object {
        fun fromWire(value: String): OfflineDownloadQuality =
            entries.firstOrNull { it.wireName == value } ?: Q480P
    }
}

enum class OfflineDownloadStatus { READY, UNAVAILABLE }

/**
 * Plain progressive offline download metadata (E14-D1: no encryption, no licenses).
 * Never persists signed URLs (neither media URLs nor signed thumbnail URLs); downloaded
 * artwork is saved to disk and referenced by [thumbnailPath].
 */
data class OfflineDownloadRecord(
    val userId: String,
    val videoId: String,
    val title: String,
    val titleAr: String? = null,
    val channelName: String? = null,
    val thumbnailPath: String? = null,
    val durationSeconds: Long? = null,
    val quality: OfflineDownloadQuality,
    val fileName: String,
    val bytes: Long,
    val downloadedAtMs: Long,
    val lastAccessedAtMs: Long,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.READY
) {
    val isPlayable: Boolean get() = status == OfflineDownloadStatus.READY
}

data class OfflineDownloadSettings(
    val wifiOnly: Boolean = true,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val defaultQuality: OfflineDownloadQuality = OfflineDownloadQuality.Q480P,
    val autoDownloadEnabled: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_STORAGE_BYTES: Long = 2L * 1024L * 1024L * 1024L
    }
}

/** Availability decision returned by `POST /api/v1/downloads/sync`. */
data class AvailabilitySyncDecision(
    val videoId: String,
    val available: Boolean,
    val reason: String? = null
)

class StorageBudgetExceededException(
    val usedBytes: Long,
    val incomingBytes: Long,
    val maxStorageBytes: Long
) : IOException("storage_budget_exceeded: used=$usedBytes incoming=$incomingBytes cap=$maxStorageBytes")

enum class NetworkTransferAllowance { ALLOWED, BLOCKED_FEATURE_OFF, BLOCKED_WIFI_ONLY, BLOCKED_OFFLINE }

object OfflineDownloadPolicy {
    fun canDownload(
        featureEnabled: Boolean,
        settings: OfflineDownloadSettings,
        isConnected: Boolean,
        isUnmeteredWifi: Boolean
    ): NetworkTransferAllowance = when {
        !featureEnabled -> NetworkTransferAllowance.BLOCKED_FEATURE_OFF
        !isConnected -> NetworkTransferAllowance.BLOCKED_OFFLINE
        settings.wifiOnly && !isUnmeteredWifi -> NetworkTransferAllowance.BLOCKED_WIFI_ONLY
        else -> NetworkTransferAllowance.ALLOWED
    }
}

/**
 * Device-local plain offline download store (E14-S8, updated for E14-D1 & E14-D5):
 * - Plain progressive R2 `.mp4` (or existing audio rendition) files live under `noBackupFilesDir/aun_downloads` (excluded
 *   from Android Auto Backup) and play directly with Media3 via `file://` URIs.
 * - Records are keyed by `(userId, videoId)` so multiple accounts on one device never clobber files.
 * - Never persists signed URLs (thumbnails are local files only).
 * - Enforces the user's local storage budget (`maxStorageBytes`) without ever
 *   silently evicting active user-chosen downloads.
 * - Removes local copies on sync when a video goes private, is deleted, or creator disables downloads.
 */
class OfflineDownloadStore(
    private val rootDir: File,
    @Volatile var offlineDownloadsEnabled: Boolean = false,
    private val wallClockMs: () -> Long = System::currentTimeMillis
) {
    private val manifestFile = File(rootDir, "manifest.json")
    private val records = mutableMapOf<String, OfflineDownloadRecord>()
    var settings: OfflineDownloadSettings = OfflineDownloadSettings()
        private set

    init {
        rootDir.mkdirs()
        loadManifest()
    }

    private fun recordKey(userId: String, videoId: String): String = "$userId\u001F$videoId"

    private fun checkCapsBeforeWriteLocked(
        usedBytes: Long,
        expectedBytes: Long
    ) {
        val cap = settings.maxStorageBytes
        if (cap > 0L && expectedBytes > 0L && usedBytes + expectedBytes > cap) {
            throw StorageBudgetExceededException(usedBytes, expectedBytes, cap)
        }
    }

    @Synchronized
    fun updateSettings(newSettings: OfflineDownloadSettings) {
        settings = newSettings
        persistManifestLocked()
    }

    @Synchronized
    fun saveFromStream(
        userId: String,
        videoId: String,
        title: String,
        titleAr: String? = null,
        channelName: String? = null,
        thumbnailPath: String? = null,
        durationSeconds: Long? = null,
        quality: OfflineDownloadQuality,
        expectedBytes: Long = 0L,
        input: InputStream
    ): OfflineDownloadRecord {
        check(offlineDownloadsEnabled) { "offline_downloads_feature_off" }
        require(thumbnailPath == null || (!thumbnailPath.startsWith("http://") && !thumbnailPath.startsWith("https://"))) {
            "signed_url_persistence_forbidden"
        }

        val key = recordKey(userId, videoId)
        val existing = records[key]
        val existingBytes = existing?.bytes ?: 0L
        val used = records.values.sumOf { it.bytes } - existingBytes
        checkCapsBeforeWriteLocked(used, expectedBytes)

        val safeBase = "${userId}_$videoId".replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeName = "$safeBase.${quality.fileExtension()}"
        val dest = File(rootDir, safeName)
        val writtenBytes = writeAtomic(dest, input)
        val cap = settings.maxStorageBytes
        if (cap > 0L && used + writtenBytes > cap) {
            dest.delete()
            throw StorageBudgetExceededException(used, writtenBytes, cap)
        }
        if (existing != null && existing.fileName != safeName) {
            File(rootDir, existing.fileName).delete()
        }

        val now = wallClockMs()
        val record = OfflineDownloadRecord(
            userId = userId,
            videoId = videoId,
            title = title,
            titleAr = titleAr,
            channelName = channelName,
            thumbnailPath = thumbnailPath,
            durationSeconds = durationSeconds,
            quality = quality,
            fileName = safeName,
            bytes = writtenBytes,
            downloadedAtMs = now,
            lastAccessedAtMs = now,
            status = OfflineDownloadStatus.READY
        )
        records[key] = record
        persistManifestLocked()
        return record
    }

    /** Returns the plain local media file for direct Media3 `file://` playback. */
    @Synchronized
    fun playableFile(userId: String, videoId: String): File? {
        val key = recordKey(userId, videoId)
        val record = if (offlineDownloadsEnabled) records[key]?.takeIf { it.isPlayable } else null
        val file = record?.let { File(rootDir, it.fileName) }?.takeIf { it.exists() }
        if (record != null && file != null) {
            records[key] = record.copy(lastAccessedAtMs = wallClockMs())
            persistManifestLocked()
        }
        return file
    }

    @Synchronized
    fun playableDownloads(userId: String): List<OfflineDownloadRecord> {
        if (!offlineDownloadsEnabled) return emptyList()
        return records.values
            .filter { it.userId == userId && it.isPlayable && File(rootDir, it.fileName).exists() }
            .sortedByDescending { it.downloadedAtMs }
    }

    @Synchronized
    fun allDownloads(userId: String? = null): List<OfflineDownloadRecord> =
        records.values
            .filter { userId == null || it.userId == userId }
            .sortedByDescending { it.downloadedAtMs }

    @Synchronized
    fun findPlayableByVideoId(userId: String, videoId: String): OfflineDownloadRecord? {
        if (!offlineDownloadsEnabled) return null
        val rec = records[recordKey(userId, videoId)] ?: return null
        return if (rec.isPlayable && File(rootDir, rec.fileName).exists()) rec else null
    }

    @Synchronized
    fun totalUsedBytes(): Long = records.values.sumOf { it.bytes }

    @Synchronized
    fun remove(userId: String, videoId: String): Boolean {
        val removed = records.remove(recordKey(userId, videoId)) ?: return false
        File(rootDir, removed.fileName).delete()
        persistManifestLocked()
        return true
    }

    /**
     * Applies availability decisions from `POST /api/v1/downloads/sync`.
     * Removes local copies whenever a video is deleted, made private, or the creator disables downloads.
     */
    @Synchronized
    fun applyAvailabilitySync(decisions: List<AvailabilitySyncDecision>): Int {
        val unavailableIds = decisions.filter { !it.available }.map { it.videoId }.toSet()
        if (unavailableIds.isEmpty()) return 0
        val keys = records.filterValues { it.videoId in unavailableIds }.keys.toList()
        var deleted = 0
        for (k in keys) {
            val rec = records.remove(k) ?: continue
            File(rootDir, rec.fileName).delete()
            deleted += 1
        }
        if (deleted > 0) persistManifestLocked()
        return deleted
    }

    /** Called on sign-out for one account: removes that user's local downloads. */
    @Synchronized
    fun clearForUser(userId: String): Int {
        val keys = records.filterValues { it.userId == userId }.keys.toList()
        for (k in keys) {
            val rec = records.remove(k) ?: continue
            File(rootDir, rec.fileName).delete()
        }
        persistManifestLocked()
        return keys.size
    }

    /** Called on full device reset: removes every local download. */
    @Synchronized
    fun wipeAll() {
        for (rec in records.values) {
            File(rootDir, rec.fileName).delete()
        }
        records.clear()
        manifestFile.delete()
    }

    private fun writeAtomic(dest: File, input: InputStream): Long {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        var total = 0L
        try {
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        total += n
                    }
                }
                out.flush()
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return total
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun loadManifest() {
        if (!manifestFile.exists()) return
        runCatching {
            val root = JSONObject(manifestFile.readText())
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = OfflineDownloadRecord(
                    userId = o.optString("userId", "default"),
                    videoId = o.getString("videoId"),
                    title = o.getString("title"),
                    titleAr = o.optString("titleAr").takeIf { it.isNotEmpty() },
                    channelName = o.optString("channelName").takeIf { it.isNotEmpty() },
                    thumbnailPath = o.optString("thumbnailPath").takeIf { it.isNotEmpty() },
                    durationSeconds = if (o.has("durationSeconds")) o.getLong("durationSeconds") else null,
                    quality = OfflineDownloadQuality.fromWire(o.optString("quality", "audio")),
                    fileName = o.getString("fileName"),
                    bytes = o.getLong("bytes"),
                    downloadedAtMs = o.getLong("downloadedAtMs"),
                    lastAccessedAtMs = o.getLong("lastAccessedAtMs"),
                    status = OfflineDownloadStatus.entries.firstOrNull { it.name == o.optString("status") }
                        ?: OfflineDownloadStatus.READY
                )
                if (File(rootDir, rec.fileName).exists()) {
                    records[recordKey(rec.userId, rec.videoId)] = rec
                }
            }
        }
    }

    private fun persistManifestLocked() {
        val arr = JSONArray()
        for (rec in records.values) {
            arr.put(
                JSONObject().apply {
                    put("userId", rec.userId)
                    put("videoId", rec.videoId)
                    put("title", rec.title)
                    rec.titleAr?.let { put("titleAr", it) }
                    rec.channelName?.let { put("channelName", it) }
                    rec.thumbnailPath?.let { put("thumbnailPath", it) }
                    rec.durationSeconds?.let { put("durationSeconds", it) }
                    put("quality", rec.quality.wireName)
                    put("fileName", rec.fileName)
                    put("bytes", rec.bytes)
                    put("downloadedAtMs", rec.downloadedAtMs)
                    put("lastAccessedAtMs", rec.lastAccessedAtMs)
                    put("status", rec.status.name)
                }
            )
        }
        val payload = JSONObject()
            .put("version", 3)
            .put("items", arr)
        val tmp = File(rootDir, "manifest.json.tmp")
        tmp.writeText(payload.toString())
        if (!tmp.renameTo(manifestFile)) {
            tmp.copyTo(manifestFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        private const val COPY_BUFFER_BYTES = 64 * 1024

        fun createDefault(
            context: Context,
            offlineDownloadsEnabled: Boolean = false
        ): OfflineDownloadStore {
            val dir = File(context.noBackupFilesDir, "aun_downloads")
            return OfflineDownloadStore(
                rootDir = dir,
                offlineDownloadsEnabled = offlineDownloadsEnabled
            )
        }
    }
}

/** Tracks the last played item and resume position for Android Auto playback resumption. */
data class PlaybackResumePoint(val videoId: String, val positionMs: Long)

class PlaybackResumeStore(private val file: File) {
    fun save(videoId: String, positionMs: Long) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(JSONObject().put("videoId", videoId).put("positionMs", positionMs.coerceAtLeast(0L)).toString())
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    fun load(): PlaybackResumePoint? {
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val id = o.optString("videoId")
            if (id.isBlank()) null else PlaybackResumePoint(id, o.optLong("positionMs", 0L))
        }.getOrNull()
    }

    fun clear() {
        file.delete()
    }
}

fun interface DownloadSyncExecutor {
    suspend fun syncAvailability(): Int
}

class DownloadAvailabilitySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val deps = applicationContext as? net.alummahnow.player.PlayerDependencies ?: return Result.success()
        val executor = deps.downloadSyncExecutor ?: return Result.success()
        return runCatching {
            executor.syncAvailability()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "aun_offline_availability_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DownloadAvailabilitySyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
