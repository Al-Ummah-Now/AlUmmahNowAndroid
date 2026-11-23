package net.alummahnow.player.offline

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

enum class OfflineDownloadQuality(val wireName: String) {
    AUDIO("audio"),
    Q360P("360p"),
    Q480P("480p"),
    Q720P("720p"),
    Q1080P("1080p");

    companion object {
        fun fromWire(value: String): OfflineDownloadQuality =
            entries.firstOrNull { it.wireName == value } ?: AUDIO
    }
}

enum class OfflineDownloadStatus { READY, EXPIRED, REVOKED }

data class OfflineDownloadRecord(
    val licenseId: String,
    val videoId: String,
    val title: String,
    val titleAr: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val quality: OfflineDownloadQuality,
    val fileName: String,
    val bytes: Long,
    val expiresAtMs: Long,
    val downloadedAtMs: Long,
    val lastAccessedAtMs: Long,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.READY
) {
    fun isPlayable(nowMs: Long): Boolean =
        status == OfflineDownloadStatus.READY && expiresAtMs > nowMs
}

data class OfflineDownloadSettings(
    val wifiOnly: Boolean = true,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val defaultQuality: OfflineDownloadQuality = OfflineDownloadQuality.AUDIO,
    val autoDownloadEnabled: Boolean = false
) {
    companion object {
        const val DEFAULT_MAX_STORAGE_BYTES: Long = 2L * 1024L * 1024L * 1024L
    }
}

data class LicenseRefreshDecision(
    val licenseId: String,
    val status: String,
    val expiresAtMs: Long? = null,
    val reason: String? = null
)

/** Abstraction for the 256-bit AES device-bound key (Android Keystore in production, in-memory in JVM tests). */
interface DownloadKeyVault {
    fun secretKey(): SecretKey
}

class InMemoryDownloadKeyVault(rawKey: ByteArray? = null) : DownloadKeyVault {
    private val key: SecretKey = SecretKeySpec(
        rawKey ?: ByteArray(32).also { SecureRandom().nextBytes(it) },
        "AES"
    )

    override fun secretKey(): SecretKey = key
}

class AndroidKeystoreDownloadKeyVault(
    private val alias: String = KEY_ALIAS
) : DownloadKeyVault {
    override fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aun_offline_download_aes256"
    }
}

/**
 * Chunked AES-256-GCM encryption for offline media files at rest.
 * Format: MAGIC("AUN1") + repeated [u32 chunkLen][12-byte random IV][ciphertext + 16-byte GCM tag].
 * Each chunk binds AAD = "$videoId|$licenseId|$chunkIndex" to prevent cross-file or chunk-swap tampering.
 */
object ChunkedAesCipher {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), '1'.code.toByte())
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CHUNK_BYTES = 64 * 1024

    fun encrypt(
        key: SecretKey,
        videoId: String,
        licenseId: String,
        plaintext: ByteArray,
        dest: File
    ): Long {
        val rng = SecureRandom()
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        tmp.outputStream().use { out ->
            out.write(MAGIC)
            var offset = 0
            var chunkIndex = 0
            while (offset < plaintext.size || (plaintext.isEmpty() && chunkIndex == 0)) {
                val len = (plaintext.size - offset).coerceAtMost(CHUNK_BYTES)
                val iv = ByteArray(NONCE_BYTES).also { rng.nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aad(videoId, licenseId, chunkIndex))
                val sealed = cipher.doFinal(plaintext, offset, len)
                out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
                out.write(iv)
                out.write(sealed)
                offset += len
                chunkIndex += 1
                if (plaintext.isEmpty()) break
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return dest.length()
    }

    fun decrypt(
        key: SecretKey,
        videoId: String,
        licenseId: String,
        source: File
    ): ByteArray {
        val bytes = source.readBytes()
        checkFormat(bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC), "invalid encrypted download header")
        val out = ByteArrayOutputStream()
        var cursor = MAGIC.size
        var chunkIndex = 0
        while (cursor < bytes.size) {
            checkFormat(cursor + 4 + NONCE_BYTES <= bytes.size, "truncated encrypted download")
            val sealedLen = ByteBuffer.wrap(bytes, cursor, 4).int
            cursor += 4
            checkFormat(sealedLen > 0 && cursor + NONCE_BYTES + sealedLen <= bytes.size, "invalid encrypted chunk length")
            val iv = bytes.copyOfRange(cursor, cursor + NONCE_BYTES)
            cursor += NONCE_BYTES
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(videoId, licenseId, chunkIndex))
            val plain = cipher.doFinal(bytes, cursor, sealedLen)
            out.write(plain)
            cursor += sealedLen
            chunkIndex += 1
        }
        return out.toByteArray()
    }

    private fun checkFormat(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun aad(videoId: String, licenseId: String, chunkIndex: Int): ByteArray =
        "$videoId|$licenseId|$chunkIndex".toByteArray(Charsets.UTF_8)
}

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
 * Device-local encrypted offline download vault (E14-S8).
 * Files live under `noBackupFilesDir/aun_downloads` (excluded from Android Auto Backup),
 * encrypted with [DownloadKeyVault], and guarded by [offlineDownloadsEnabled] (defaults OFF).
 */
class OfflineDownloadStore(
    private val rootDir: File,
    private val keyVault: DownloadKeyVault,
    @Volatile var offlineDownloadsEnabled: Boolean = false,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private val manifestFile = File(rootDir, "manifest.json")
    private val records = mutableMapOf<String, OfflineDownloadRecord>()
    var settings: OfflineDownloadSettings = OfflineDownloadSettings()
        private set

    init {
        rootDir.mkdirs()
        loadManifest()
    }

    @Synchronized
    fun updateSettings(newSettings: OfflineDownloadSettings) {
        settings = newSettings
        evictToCapLocked()
        persistManifestLocked()
    }

    @Synchronized
    fun saveEncrypted(
        licenseId: String,
        videoId: String,
        title: String,
        titleAr: String? = null,
        channelName: String? = null,
        thumbnailUrl: String? = null,
        durationSeconds: Long? = null,
        quality: OfflineDownloadQuality,
        expiresAtMs: Long,
        plaintextBytes: ByteArray
    ): OfflineDownloadRecord {
        check(offlineDownloadsEnabled) { "offline_downloads_feature_off" }
        val safeName = "${licenseId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.aun"
        val dest = File(rootDir, safeName)
        val encryptedSize = ChunkedAesCipher.encrypt(
            key = keyVault.secretKey(),
            videoId = videoId,
            licenseId = licenseId,
            plaintext = plaintextBytes,
            dest = dest
        )
        val now = clockMs()
        val record = OfflineDownloadRecord(
            licenseId = licenseId,
            videoId = videoId,
            title = title,
            titleAr = titleAr,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            quality = quality,
            fileName = safeName,
            bytes = encryptedSize,
            expiresAtMs = expiresAtMs,
            downloadedAtMs = now,
            lastAccessedAtMs = now,
            status = OfflineDownloadStatus.READY
        )
        records[licenseId] = record
        evictToCapLocked()
        persistManifestLocked()
        return record
    }

    @Synchronized
    fun readDecrypted(licenseId: String): ByteArray {
        val record = records[licenseId] ?: throw IOException("download not found: $licenseId")
        val now = clockMs()
        if (!record.isPlayable(now)) {
            throw IllegalStateException("download license expired or revoked")
        }
        val file = File(rootDir, record.fileName)
        val plain = ChunkedAesCipher.decrypt(keyVault.secretKey(), record.videoId, record.licenseId, file)
        records[licenseId] = record.copy(lastAccessedAtMs = now)
        persistManifestLocked()
        return plain
    }

    @Synchronized
    fun playableDownloads(): List<OfflineDownloadRecord> {
        if (!offlineDownloadsEnabled) return emptyList()
        val now = clockMs()
        return records.values
            .filter { it.isPlayable(now) }
            .sortedByDescending { it.downloadedAtMs }
    }

    @Synchronized
    fun allDownloads(): List<OfflineDownloadRecord> =
        records.values.sortedByDescending { it.downloadedAtMs }

    @Synchronized
    fun findPlayableByVideoId(videoId: String): OfflineDownloadRecord? {
        if (!offlineDownloadsEnabled) return null
        val now = clockMs()
        return records.values.firstOrNull { it.videoId == videoId && it.isPlayable(now) }
    }

    @Synchronized
    fun totalUsedBytes(): Long = records.values.sumOf { it.bytes }

    @Synchronized
    fun remove(licenseId: String): Boolean {
        val removed = records.remove(licenseId) ?: return false
        File(rootDir, removed.fileName).delete()
        persistManifestLocked()
        return true
    }

    /**
     * Applies batch license refresh decisions from `POST /api/v1/downloads/refresh`.
     * Any revoked or expired item has its encrypted file deleted from disk immediately.
     */
    @Synchronized
    fun applyLicenseRefresh(decisions: List<LicenseRefreshDecision>): Int {
        var deleted = 0
        for (decision in decisions) {
            val existing = records[decision.licenseId] ?: continue
            if (decision.status == "active" && decision.expiresAtMs != null) {
                records[decision.licenseId] = existing.copy(
                    expiresAtMs = decision.expiresAtMs,
                    status = OfflineDownloadStatus.READY
                )
            } else {
                records.remove(decision.licenseId)
                File(rootDir, existing.fileName).delete()
                deleted += 1
            }
        }
        persistManifestLocked()
        return deleted
    }

    /** Called on sign-out or account deletion: wipes every local encrypted download and manifest. */
    @Synchronized
    fun wipeAll() {
        for (rec in records.values) {
            File(rootDir, rec.fileName).delete()
        }
        records.clear()
        manifestFile.delete()
    }

    private fun evictToCapLocked() {
        val cap = settings.maxStorageBytes
        if (cap <= 0L) return
        var used = records.values.sumOf { it.bytes }
        if (used <= cap) return
        val byLru = records.values.sortedBy { it.lastAccessedAtMs }
        for (victim in byLru) {
            if (used <= cap) break
            records.remove(victim.licenseId)
            File(rootDir, victim.fileName).delete()
            used -= victim.bytes
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
                    licenseId = o.getString("licenseId"),
                    videoId = o.getString("videoId"),
                    title = o.getString("title"),
                    titleAr = o.optString("titleAr").takeIf { it.isNotEmpty() },
                    channelName = o.optString("channelName").takeIf { it.isNotEmpty() },
                    thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotEmpty() },
                    durationSeconds = if (o.has("durationSeconds")) o.getLong("durationSeconds") else null,
                    quality = OfflineDownloadQuality.fromWire(o.optString("quality", "audio")),
                    fileName = o.getString("fileName"),
                    bytes = o.getLong("bytes"),
                    expiresAtMs = o.getLong("expiresAtMs"),
                    downloadedAtMs = o.getLong("downloadedAtMs"),
                    lastAccessedAtMs = o.getLong("lastAccessedAtMs"),
                    status = OfflineDownloadStatus.entries.firstOrNull { it.name == o.optString("status") }
                        ?: OfflineDownloadStatus.READY
                )
                if (File(rootDir, rec.fileName).exists()) {
                    records[rec.licenseId] = rec
                }
            }
        }
    }

    private fun persistManifestLocked() {
        val arr = JSONArray()
        for (rec in records.values) {
            arr.put(
                JSONObject().apply {
                    put("licenseId", rec.licenseId)
                    put("videoId", rec.videoId)
                    put("title", rec.title)
                    rec.titleAr?.let { put("titleAr", it) }
                    rec.channelName?.let { put("channelName", it) }
                    rec.thumbnailUrl?.let { put("thumbnailUrl", it) }
                    rec.durationSeconds?.let { put("durationSeconds", it) }
                    put("quality", rec.quality.wireName)
                    put("fileName", rec.fileName)
                    put("bytes", rec.bytes)
                    put("expiresAtMs", rec.expiresAtMs)
                    put("downloadedAtMs", rec.downloadedAtMs)
                    put("lastAccessedAtMs", rec.lastAccessedAtMs)
                    put("status", rec.status.name)
                }
            )
        }
        val tmp = File(rootDir, "manifest.json.tmp")
        tmp.writeText(JSONObject().put("items", arr).toString())
        if (!tmp.renameTo(manifestFile)) {
            tmp.copyTo(manifestFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        fun createDefault(context: Context, offlineDownloadsEnabled: Boolean = false): OfflineDownloadStore {
            val dir = File(context.noBackupFilesDir, "aun_downloads")
            return OfflineDownloadStore(
                rootDir = dir,
                keyVault = AndroidKeystoreDownloadKeyVault(),
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

/**
 * Periodic WorkManager task that refreshes offline download licenses whenever the device is online.
 * Hosts inject a [LicenseRefreshExecutor] via `Application` (implementing [net.alummahnow.player.PlayerDependencies]).
 */
fun interface LicenseRefreshExecutor {
    suspend fun refreshAll(): Int
}

class LicenseRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val deps = applicationContext as? net.alummahnow.player.PlayerDependencies ?: return Result.success()
        val executor = deps.licenseRefreshExecutor ?: return Result.success()
        return runCatching {
            executor.refreshAll()
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "aun_offline_license_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LicenseRefreshWorker>(12, TimeUnit.HOURS)
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
