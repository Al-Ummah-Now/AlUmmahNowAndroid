package net.alummahnow.player.offline

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
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

/**
 * Local encrypted download metadata. Never persists signed URLs (neither media URLs nor signed
 * thumbnail URLs); downloaded artwork is saved to disk and referenced by [thumbnailPath].
 */
data class OfflineDownloadRecord(
    val userId: String,
    val licenseId: String,
    val videoId: String,
    val title: String,
    val titleAr: String? = null,
    val channelName: String? = null,
    val thumbnailPath: String? = null,
    val durationSeconds: Long? = null,
    val quality: OfflineDownloadQuality,
    val fileName: String,
    val bytes: Long,
    val expiresAtMs: Long,
    val downloadedAtMs: Long,
    val lastAccessedAtMs: Long,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.READY
) {
    fun isPlayable(effectiveNowMs: Long): Boolean =
        status == OfflineDownloadStatus.READY && expiresAtMs > effectiveNowMs
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

class StorageBudgetExceededException(
    val usedBytes: Long,
    val incomingBytes: Long,
    val maxStorageBytes: Long
) : IOException("storage_budget_exceeded: used=$usedBytes incoming=$incomingBytes cap=$maxStorageBytes")

/** Releases server download license slots (`DELETE /api/v1/downloads/:licenseId` and `revoke-device`). */
interface LicenseSlotReleaser {
    fun releaseLicense(licenseId: String): Boolean
    fun revokeDevice(deviceId: String): Boolean = true
}

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
 * Shared `AUNDL01` chunked AES-256-GCM binary format matching `AlUmmahNowCore` (`crates/aun-downloads`):
 * - Header: `MAGIC("AUNDL01\0")` (8 B) + random base nonce (8 B) + `u16 BE videoIdLen` + `videoId`
 *   + `u16 BE licenseIdLen` + `licenseId`
 * - Fixed-stride chunks: non-final chunks are 64 KiB plaintext + 16 B GCM tag (65552 B); final chunk
 *   is `0..=65536` B plaintext + 16 B tag.
 * - Chunk `i` nonce: `base_nonce (8 B) || i (u32 BE)`.
 * - Chunk `i` AAD: `MAGIC || u16 BE videoIdLen || videoId || u16 BE licenseIdLen || licenseId || i (u32 BE) || isFinal (u8)`.
 *
 * Opening [AunDl01RandomAccessReader] verifies header binding and authenticates the final chunk
 * immediately so truncation or file swapping fails before playback begins.
 */
object AunDl01Format {
    val MAGIC: ByteArray = byteArrayOf('A'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 'L'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), 0)
    const val CHUNK_SIZE: Int = 64 * 1024
    const val TAG_LEN: Int = 16
    const val CHUNK_STRIDE: Int = CHUNK_SIZE + TAG_LEN
    const val BASE_NONCE_LEN: Int = 8
    private const val MAX_ID_LEN: Int = 512
    private const val TAG_BITS: Int = 128

    internal fun validateBinding(videoId: String, licenseId: String) {
        if (videoId.isEmpty() || videoId.toByteArray(Charsets.UTF_8).size > MAX_ID_LEN) {
            throw IOException("invalid video_id for AAD binding")
        }
        if (licenseId.isEmpty() || licenseId.toByteArray(Charsets.UTF_8).size > MAX_ID_LEN) {
            throw IOException("invalid license_id for AAD binding")
        }
    }

    internal fun chunkNonce(prefix: ByteArray, index: Int): ByteArray {
        val out = ByteArray(12)
        System.arraycopy(prefix, 0, out, 0, BASE_NONCE_LEN)
        ByteBuffer.wrap(out, BASE_NONCE_LEN, 4).putInt(index)
        return out
    }

    internal fun buildAad(videoId: String, licenseId: String, index: Int, isFinal: Boolean): ByteArray {
        val vid = videoId.toByteArray(Charsets.UTF_8)
        val lic = licenseId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(MAGIC.size + 2 + vid.size + 2 + lic.size + 4 + 1)
        buf.put(MAGIC)
        buf.putShort(vid.size.toShort())
        buf.put(vid)
        buf.putShort(lic.size.toShort())
        buf.put(lic)
        buf.putInt(index)
        buf.put(if (isFinal) 1.toByte() else 0.toByte())
        return buf.array()
    }

    internal fun sealChunk(
        key: SecretKey,
        prefix: ByteArray,
        videoId: String,
        licenseId: String,
        index: Int,
        isFinal: Boolean,
        plain: ByteArray,
        len: Int
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkNonce(prefix, index)))
        cipher.updateAAD(buildAad(videoId, licenseId, index, isFinal))
        return cipher.doFinal(plain, 0, len)
    }

    internal fun openChunk(
        key: SecretKey,
        prefix: ByteArray,
        videoId: String,
        licenseId: String,
        index: Int,
        isFinal: Boolean,
        frame: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkNonce(prefix, index)))
        cipher.updateAAD(buildAad(videoId, licenseId, index, isFinal))
        return cipher.doFinal(frame)
    }
}

/** Single-pass streaming `AUNDL01` encryptor holding at most ~64 KiB of plaintext in memory. */
class AunDl01StreamWriter(
    private val key: SecretKey,
    private val dest: File,
    private val videoId: String,
    private val licenseId: String
) : Closeable {
    private val prefix = ByteArray(AunDl01Format.BASE_NONCE_LEN).also { SecureRandom().nextBytes(it) }
    private val tmpFile = File(dest.parentFile, "${dest.name}.aun.tmp")
    private val out: FileOutputStream
    private var pending = ByteArray(AunDl01Format.CHUNK_SIZE + 1)
    private var pendingLen = 0
    private var chunkIndex = 0
    private var finished = false

    init {
        AunDl01Format.validateBinding(videoId, licenseId)
        dest.parentFile?.mkdirs()
        out = FileOutputStream(tmpFile)
        val vid = videoId.toByteArray(Charsets.UTF_8)
        val lic = licenseId.toByteArray(Charsets.UTF_8)
        val hdr = ByteBuffer.allocate(AunDl01Format.MAGIC.size + AunDl01Format.BASE_NONCE_LEN + 2 + vid.size + 2 + lic.size)
        hdr.put(AunDl01Format.MAGIC)
        hdr.put(prefix)
        hdr.putShort(vid.size.toShort())
        hdr.put(vid)
        hdr.putShort(lic.size.toShort())
        hdr.put(lic)
        out.write(hdr.array())
    }

    fun write(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        check(!finished) { "writer already finished" }
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val space = pending.size - pendingLen
            val take = remaining.coerceAtMost(space)
            System.arraycopy(data, pos, pending, pendingLen, take)
            pendingLen += take
            pos += take
            remaining -= take
            if (pendingLen > AunDl01Format.CHUNK_SIZE) {
                val frame = AunDl01Format.sealChunk(
                    key, prefix, videoId, licenseId, chunkIndex, isFinal = false,
                    plain = pending, len = AunDl01Format.CHUNK_SIZE
                )
                out.write(frame)
                chunkIndex += 1
                val leftover = pendingLen - AunDl01Format.CHUNK_SIZE
                System.arraycopy(pending, AunDl01Format.CHUNK_SIZE, pending, 0, leftover)
                pendingLen = leftover
            }
        }
    }

    fun finish(): Long {
        check(!finished) { "writer already finished" }
        val finalFrame = AunDl01Format.sealChunk(
            key, prefix, videoId, licenseId, chunkIndex, isFinal = true,
            plain = pending, len = pendingLen
        )
        out.write(finalFrame)
        out.flush()
        out.close()
        pending.fill(0)
        pendingLen = 0
        if (!tmpFile.renameTo(dest)) {
            tmpFile.copyTo(dest, overwrite = true)
            tmpFile.delete()
        }
        finished = true
        return dest.length()
    }

    override fun close() {
        if (!finished) {
            runCatching { out.close() }
            pending.fill(0)
            tmpFile.delete()
            finished = true
        }
    }

    companion object {
        fun encryptStream(
            key: SecretKey,
            videoId: String,
            licenseId: String,
            input: InputStream,
            dest: File
        ): Long {
            val buf = ByteArray(AunDl01Format.CHUNK_SIZE)
            AunDl01StreamWriter(key, dest, videoId, licenseId).use { writer ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) writer.write(buf, 0, n)
                }
                return writer.finish()
            }
        }
    }
}

/**
 * O(1) random-access chunked AES-256-GCM decrypt-on-read reader for `AUNDL01` files.
 * Decrypts only the 64 KiB chunks needed for each range read; never allocates the full file in RAM.
 */
class AunDl01RandomAccessReader private constructor(
    private val key: SecretKey,
    private val raf: RandomAccessFile,
    private val prefix: ByteArray,
    val videoId: String,
    val licenseId: String,
    private val dataOffset: Long,
    val plainLen: Long,
    private val numChunks: Int
) : Closeable {
    private var cachedIndex: Int = -1
    private var cachedPlain: ByteArray = ByteArray(0)

    fun readRange(offset: Long, length: Int): ByteArray {
        if (offset >= plainLen || length <= 0) return ByteArray(0)
        val toRead = (plainLen - offset).coerceAtMost(length.toLong()).toInt()
        val out = ByteArray(toRead)
        var outPos = 0
        var filePos = offset
        while (outPos < toRead) {
            val idx = (filePos / AunDl01Format.CHUNK_SIZE).toInt()
            val inChunk = (filePos % AunDl01Format.CHUNK_SIZE).toInt()
            val chunk = loadChunk(idx)
            val take = (chunk.size - inChunk).coerceAtMost(toRead - outPos)
            System.arraycopy(chunk, inChunk, out, outPos, take)
            outPos += take
            filePos += take
        }
        return out
    }

    private fun loadChunk(index: Int): ByteArray {
        if (cachedIndex == index) return cachedPlain
        if (index < 0 || index >= numChunks) throw IOException("chunk index out of bounds")
        val isFinal = index == numChunks - 1
        val chunkPlainLen = if (isFinal) {
            (plainLen - index.toLong() * AunDl01Format.CHUNK_SIZE).toInt()
        } else {
            AunDl01Format.CHUNK_SIZE
        }
        val frameLen = chunkPlainLen + AunDl01Format.TAG_LEN
        raf.seek(dataOffset + index.toLong() * AunDl01Format.CHUNK_STRIDE)
        val frame = ByteArray(frameLen)
        raf.readFully(frame)
        val plain = AunDl01Format.openChunk(key, prefix, videoId, licenseId, index, isFinal, frame)
        cachedPlain.fill(0)
        cachedPlain = plain
        cachedIndex = index
        return cachedPlain
    }

    override fun close() {
        cachedPlain.fill(0)
        cachedIndex = -1
        raf.close()
    }

    companion object {
        fun open(
            key: SecretKey,
            source: File,
            expectedVideoId: String,
            expectedLicenseId: String
        ): AunDl01RandomAccessReader {
            AunDl01Format.validateBinding(expectedVideoId, expectedLicenseId)
            val raf = RandomAccessFile(source, "r")
            try {
                val fileLen = raf.length()
                if (fileLen < 16) throw IOException("truncated header")
                val fixed = ByteArray(16)
                raf.readFully(fixed)
                if (!fixed.copyOfRange(0, 8).contentEquals(AunDl01Format.MAGIC)) {
                    throw IOException("invalid envelope magic")
                }
                val prefix = fixed.copyOfRange(8, 16)
                val vidLen = raf.readUnsignedShort()
                val vidBytes = ByteArray(vidLen)
                raf.readFully(vidBytes)
                val headerVideoId = String(vidBytes, Charsets.UTF_8)
                val licLen = raf.readUnsignedShort()
                val licBytes = ByteArray(licLen)
                raf.readFully(licBytes)
                val headerLicenseId = String(licBytes, Charsets.UTF_8)
                if (headerVideoId != expectedVideoId || headerLicenseId != expectedLicenseId) {
                    throw IOException("file binding does not match expected videoId/licenseId")
                }
                val dataOffset = raf.filePointer
                if (fileLen < dataOffset + AunDl01Format.TAG_LEN) throw IOException("truncated payload")
                val payloadLen = fileLen - dataOffset
                val stride = AunDl01Format.CHUNK_STRIDE.toLong()
                val numChunksLong = ((payloadLen - 1L) / stride) + 1L
                val lastFrameLen = payloadLen - (numChunksLong - 1L) * stride
                if (lastFrameLen < AunDl01Format.TAG_LEN || lastFrameLen > stride) {
                    throw IOException("truncated final chunk")
                }
                if (numChunksLong > 1L && lastFrameLen == AunDl01Format.TAG_LEN.toLong()) {
                    throw IOException("invalid empty trailing chunk")
                }
                val plainLen = (numChunksLong - 1L) * AunDl01Format.CHUNK_SIZE + (lastFrameLen - AunDl01Format.TAG_LEN)
                val reader = AunDl01RandomAccessReader(
                    key = key,
                    raf = raf,
                    prefix = prefix,
                    videoId = headerVideoId,
                    licenseId = headerLicenseId,
                    dataOffset = dataOffset,
                    plainLen = plainLen,
                    numChunks = numChunksLong.toInt()
                )
                // Immediately authenticate final chunk to detect truncation or wrong key on open.
                reader.loadChunk(reader.numChunks - 1)
                return reader
            } catch (e: Exception) {
                runCatching { raf.close() }
                if (e is IOException) throw e
                throw IOException("failed to open encrypted download: ${e.message}", e)
            }
        }
    }
}

/**
 * Media3 [DataSource] streaming decrypted 64 KiB chunks directly from an encrypted `AUNDL01` file
 * for `aun-offline://<videoId>` URIs. Zero plaintext temp files and zero whole-file memory buffers.
 */
@UnstableApi
class EncryptedOfflineDataSource(
    private val store: OfflineDownloadStore,
    private val activeUserId: () -> String
) : BaseDataSource(/* isNetwork = */ false) {
    private var currentUri: Uri? = null
    private var reader: AunDl01RandomAccessReader? = null
    private var position: Long = 0L
    private var bytesRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.scheme != SCHEME) throw IOException("unsupported scheme: ${uri.scheme}")
        val videoId = uri.host ?: uri.path?.trimStart('/')
            ?: throw IOException("missing videoId in offline uri")
        transferInitializing(dataSpec)
        val r = store.openReader(activeUserId(), videoId)
        val start = dataSpec.position
        if (start < 0 || start > r.plainLen) {
            r.close()
            throw IOException("invalid seek position: $start")
        }
        val available = r.plainLen - start
        val toRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length.coerceAtMost(available)
        } else {
            available
        }
        currentUri = uri
        reader = r
        position = start
        bytesRemaining = toRead
        transferStarted(dataSpec)
        return toRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("data source not open")
        val want = length.toLong().coerceAtMost(bytesRemaining).toInt()
        val chunk = r.readRange(position, want)
        if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT
        System.arraycopy(chunk, 0, buffer, offset, chunk.size)
        position += chunk.size
        bytesRemaining -= chunk.size
        bytesTransferred(chunk.size)
        return chunk.size
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        val wasOpen = reader != null
        reader?.close()
        reader = null
        currentUri = null
        bytesRemaining = 0L
        if (wasOpen) transferEnded()
    }

    class Factory(
        private val store: OfflineDownloadStore,
        private val activeUserId: () -> String
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = EncryptedOfflineDataSource(store, activeUserId)
    }

    companion object {
        const val SCHEME = "aun-offline"
        fun uriFor(videoId: String): Uri = Uri.parse("$SCHEME://$videoId")
    }
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
 * Device-local encrypted offline download vault (E14-S8):
 * - Files live under `noBackupFilesDir/aun_downloads` (excluded from Android Auto Backup) in the
 *   shared `AUNDL01` chunked format bound to `(videoId, licenseId)`.
 * - Records are keyed by `(userId, videoId)` so multiple accounts on one device never clobber files.
 * - Never persists signed URLs (thumbnails are local files only).
 * - Never trusts a rolled-back device clock (combines server time, monotonic uptime, and high-water mark).
 * - Releases server license slots on delete/wipe/revocation and never silently evicts active downloads.
 */
class OfflineDownloadStore(
    private val rootDir: File,
    private val keyVault: DownloadKeyVault,
    @Volatile var offlineDownloadsEnabled: Boolean = false,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val monotonicClockMs: () -> Long = SystemClock::elapsedRealtime,
    private val slotReleaser: LicenseSlotReleaser? = null,
    private val deviceId: String = "android-device"
) {
    private val manifestFile = File(rootDir, "manifest.json")
    private val records = mutableMapOf<String, OfflineDownloadRecord>()
    private val pendingSlotReleases = mutableSetOf<String>()
    private var highWaterNowMs: Long = 0L
    private var serverClockAnchor: Pair<Long, Long>? = null
    var settings: OfflineDownloadSettings = OfflineDownloadSettings()
        private set

    init {
        rootDir.mkdirs()
        loadManifest()
    }

    private fun recordKey(userId: String, videoId: String): String = "$userId\u001F$videoId"

    @Synchronized
    fun observeServerTime(serverTimeMs: Long): Long {
        val mono = runCatching { monotonicClockMs() }.getOrDefault(0L)
        serverClockAnchor = serverTimeMs to mono
        if (serverTimeMs > highWaterNowMs) {
            highWaterNowMs = serverTimeMs
            persistManifestLocked()
        }
        return highWaterNowMs
    }

    private fun effectiveNowMsLocked(): Long {
        var effective = maxOf(wallClockMs(), highWaterNowMs)
        val anchor = serverClockAnchor
        if (anchor != null) {
            val mono = runCatching { monotonicClockMs() }.getOrDefault(-1L)
            if (mono >= anchor.second) {
                effective = maxOf(effective, anchor.first + (mono - anchor.second))
            }
        }
        if (effective > highWaterNowMs) {
            highWaterNowMs = effective
            persistManifestLocked()
        }
        return effective
    }

    @Synchronized
    fun updateSettings(newSettings: OfflineDownloadSettings) {
        settings = newSettings
        purgeInactiveLocked()
        persistManifestLocked()
    }

    @Synchronized
    fun saveFromStream(
        userId: String,
        licenseId: String,
        videoId: String,
        title: String,
        titleAr: String? = null,
        channelName: String? = null,
        thumbnailPath: String? = null,
        durationSeconds: Long? = null,
        quality: OfflineDownloadQuality,
        serverTimeMs: Long,
        expiresAtMs: Long,
        expectedBytes: Long = 0L,
        input: InputStream
    ): OfflineDownloadRecord {
        check(offlineDownloadsEnabled) { "offline_downloads_feature_off" }
        require(thumbnailPath == null || (!thumbnailPath.startsWith("http://") && !thumbnailPath.startsWith("https://"))) {
            "signed_url_persistence_forbidden"
        }
        observeServerTime(serverTimeMs)
        // Only purge expired/revoked items; never silently evict active user-chosen downloads!
        purgeInactiveLocked()
        val cap = settings.maxStorageBytes
        val used = records.values.sumOf { it.bytes }
        if (cap > 0L && expectedBytes > 0L && used + expectedBytes > cap) {
            throw StorageBudgetExceededException(used, expectedBytes, cap)
        }

        val safeName = "${"${userId}_${licenseId}".replace(Regex("[^A-Za-z0-9_-]"), "_")}.aun"
        val dest = File(rootDir, safeName)
        val encryptedSize = AunDl01StreamWriter.encryptStream(
            key = keyVault.secretKey(),
            videoId = videoId,
            licenseId = licenseId,
            input = input,
            dest = dest
        )
        if (cap > 0L && used + encryptedSize > cap) {
            dest.delete()
            throw StorageBudgetExceededException(used, encryptedSize, cap)
        }
        val key = recordKey(userId, videoId)
        records[key]?.let { prev ->
            if (prev.fileName != safeName) File(rootDir, prev.fileName).delete()
            if (prev.licenseId != licenseId) releaseServerSlotLocked(prev.licenseId)
        }
        val now = effectiveNowMsLocked()
        val record = OfflineDownloadRecord(
            userId = userId,
            licenseId = licenseId,
            videoId = videoId,
            title = title,
            titleAr = titleAr,
            channelName = channelName,
            thumbnailPath = thumbnailPath,
            durationSeconds = durationSeconds,
            quality = quality,
            fileName = safeName,
            bytes = encryptedSize,
            expiresAtMs = expiresAtMs,
            downloadedAtMs = now,
            lastAccessedAtMs = now,
            status = OfflineDownloadStatus.READY
        )
        records[key] = record
        persistManifestLocked()
        return record
    }

    /** Opens a streaming chunked random-access reader for playback (no full-file RAM buffer). */
    @Synchronized
    fun openReader(userId: String, videoId: String): AunDl01RandomAccessReader {
        val key = recordKey(userId, videoId)
        val record = records[key] ?: throw IOException("download not found: $videoId")
        val now = effectiveNowMsLocked()
        if (!record.isPlayable(now)) {
            throw IllegalStateException("download license expired or revoked")
        }
        val reader = AunDl01RandomAccessReader.open(
            key = keyVault.secretKey(),
            source = File(rootDir, record.fileName),
            expectedVideoId = record.videoId,
            expectedLicenseId = record.licenseId
        )
        records[key] = record.copy(lastAccessedAtMs = now)
        persistManifestLocked()
        return reader
    }

    @Synchronized
    fun playableDownloads(userId: String): List<OfflineDownloadRecord> {
        if (!offlineDownloadsEnabled) return emptyList()
        val now = effectiveNowMsLocked()
        return records.values
            .filter { it.userId == userId && it.isPlayable(now) }
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
        val now = effectiveNowMsLocked()
        val rec = records[recordKey(userId, videoId)] ?: return null
        return if (rec.isPlayable(now)) rec else null
    }

    @Synchronized
    fun totalUsedBytes(): Long = records.values.sumOf { it.bytes }

    @Synchronized
    fun pendingServerSlotReleases(): Set<String> = pendingSlotReleases.toSet()

    @Synchronized
    fun remove(userId: String, videoId: String): Boolean {
        val removed = records.remove(recordKey(userId, videoId)) ?: return false
        File(rootDir, removed.fileName).delete()
        releaseServerSlotLocked(removed.licenseId)
        persistManifestLocked()
        return true
    }

    /**
     * Applies batch license refresh decisions from `POST /api/v1/downloads/refresh`.
     * Updates the server clock anchor and deletes revoked/expired encrypted files immediately.
     */
    @Synchronized
    fun applyLicenseRefresh(decisions: List<LicenseRefreshDecision>, serverTimeMs: Long): Int {
        observeServerTime(serverTimeMs)
        var deleted = 0
        for (decision in decisions) {
            val entry = records.entries.firstOrNull { it.value.licenseId == decision.licenseId } ?: continue
            if (decision.status == "active" && decision.expiresAtMs != null) {
                records[entry.key] = entry.value.copy(
                    expiresAtMs = decision.expiresAtMs,
                    status = OfflineDownloadStatus.READY
                )
            } else {
                records.remove(entry.key)
                File(rootDir, entry.value.fileName).delete()
                releaseServerSlotLocked(entry.value.licenseId)
                deleted += 1
            }
        }
        flushPendingSlotReleasesLocked()
        persistManifestLocked()
        return deleted
    }

    /** Retries any queued server slot releases once back online. */
    @Synchronized
    fun flushPendingSlotReleases(): Int {
        val flushed = flushPendingSlotReleasesLocked()
        if (flushed > 0) persistManifestLocked()
        return flushed
    }

    /** Called on sign-out for one account: wipes that user's encrypted downloads and revokes device slots. */
    @Synchronized
    fun clearForUser(userId: String): Int {
        val keys = records.filterValues { it.userId == userId }.keys.toList()
        for (k in keys) {
            val rec = records.remove(k) ?: continue
            File(rootDir, rec.fileName).delete()
            releaseServerSlotLocked(rec.licenseId)
        }
        runCatching { slotReleaser?.revokeDevice(deviceId) }
        persistManifestLocked()
        return keys.size
    }

    /** Called on full device reset: wipes every local encrypted download and revokes device slots. */
    @Synchronized
    fun wipeAll() {
        for (rec in records.values) {
            File(rootDir, rec.fileName).delete()
            releaseServerSlotLocked(rec.licenseId)
        }
        records.clear()
        runCatching { slotReleaser?.revokeDevice(deviceId) }
        if (pendingSlotReleases.isEmpty()) {
            manifestFile.delete()
        } else {
            persistManifestLocked()
        }
    }

    private fun releaseServerSlotLocked(licenseId: String) {
        val released = runCatching { slotReleaser?.releaseLicense(licenseId) == true }.getOrDefault(false)
        if (!released && slotReleaser != null) {
            pendingSlotReleases.add(licenseId)
        }
    }

    private fun flushPendingSlotReleasesLocked(): Int {
        val releaser = slotReleaser ?: return 0
        val iter = pendingSlotReleases.iterator()
        var count = 0
        while (iter.hasNext()) {
            val id = iter.next()
            if (runCatching { releaser.releaseLicense(id) }.getOrDefault(false)) {
                iter.remove()
                count += 1
            }
        }
        return count
    }

    private fun purgeInactiveLocked() {
        val now = effectiveNowMsLocked()
        val keys = records.filterValues { !it.isPlayable(now) }.keys.toList()
        for (k in keys) {
            val rec = records.remove(k) ?: continue
            File(rootDir, rec.fileName).delete()
            releaseServerSlotLocked(rec.licenseId)
        }
    }

    private fun loadManifest() {
        if (!manifestFile.exists()) return
        runCatching {
            val root = JSONObject(manifestFile.readText())
            highWaterNowMs = root.optLong("highWaterNowMs", 0L)
            val pending = root.optJSONArray("pendingSlotReleases") ?: JSONArray()
            for (i in 0 until pending.length()) {
                pending.optString(i).takeIf { it.isNotEmpty() }?.let { pendingSlotReleases.add(it) }
            }
            val arr = root.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rec = OfflineDownloadRecord(
                    userId = o.optString("userId", "default"),
                    licenseId = o.getString("licenseId"),
                    videoId = o.getString("videoId"),
                    title = o.getString("title"),
                    titleAr = o.optString("titleAr").takeIf { it.isNotEmpty() },
                    channelName = o.optString("channelName").takeIf { it.isNotEmpty() },
                    thumbnailPath = o.optString("thumbnailPath").takeIf { it.isNotEmpty() },
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
                    records[recordKey(rec.userId, rec.videoId)] = rec
                    highWaterNowMs = maxOf(highWaterNowMs, rec.downloadedAtMs)
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
                    put("licenseId", rec.licenseId)
                    put("videoId", rec.videoId)
                    put("title", rec.title)
                    rec.titleAr?.let { put("titleAr", it) }
                    rec.channelName?.let { put("channelName", it) }
                    rec.thumbnailPath?.let { put("thumbnailPath", it) }
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
        val pendingArr = JSONArray()
        for (id in pendingSlotReleases) pendingArr.put(id)
        val payload = JSONObject()
            .put("version", 2)
            .put("highWaterNowMs", highWaterNowMs)
            .put("pendingSlotReleases", pendingArr)
            .put("items", arr)
        val tmp = File(rootDir, "manifest.json.tmp")
        tmp.writeText(payload.toString())
        if (!tmp.renameTo(manifestFile)) {
            tmp.copyTo(manifestFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        fun createDefault(
            context: Context,
            offlineDownloadsEnabled: Boolean = false,
            slotReleaser: LicenseSlotReleaser? = null
        ): OfflineDownloadStore {
            val dir = File(context.noBackupFilesDir, "aun_downloads")
            return OfflineDownloadStore(
                rootDir = dir,
                keyVault = AndroidKeystoreDownloadKeyVault(),
                offlineDownloadsEnabled = offlineDownloadsEnabled,
                slotReleaser = slotReleaser
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
