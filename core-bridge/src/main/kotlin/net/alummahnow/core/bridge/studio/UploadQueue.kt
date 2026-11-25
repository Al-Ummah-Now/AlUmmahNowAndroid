package net.alummahnow.core.bridge.studio

import kotlinx.coroutines.flow.StateFlow

/** One file to upload with its video metadata ("set for all" values already applied). */
data class UploadDraft(
    /** `content://` URI from the system picker, or a file path. */
    val source: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val title: String,
    val description: String = "",
    val channelId: String,
    val visibility: Visibility = Visibility.PUBLIC,
    val category: String? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val playlistIds: List<String> = emptyList(),
    /** ISO 8601 UTC: publish then (the video uploads private and is scheduled). */
    val scheduleAtIso: String? = null,
    val timezone: String? = null
)

enum class UploadPhase { PREPARING, QUEUED, WAITING, UPLOADING, FINISHING, PAUSED, DONE, FAILED, CANCELLED }

data class UploadItem(
    val id: String,
    val title: String,
    val phase: UploadPhase,
    val sentBytes: Long,
    val totalBytes: Long,
    val videoId: String?,
    val error: String?
) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (sentBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    val isActive: Boolean get() = phase in
        setOf(UploadPhase.PREPARING, UploadPhase.QUEUED, UploadPhase.WAITING, UploadPhase.UPLOADING, UploadPhase.FINISHING)

    /**
     * Failed because the server refused it (HTTP 403). The core keeps no code for a 403 (AlUmmahNowCore#61), only
     * its error's `forbidden:` prefix; whether the Terms are why is asked of the server, never read from the text.
     */
    val isRefused: Boolean get() = phase == UploadPhase.FAILED && error?.startsWith("forbidden:") == true
    val isFinished: Boolean get() = phase in setOf(UploadPhase.DONE, UploadPhase.FAILED, UploadPhase.CANCELLED)
}

/** Resumable (TUS) uploads for the active account, driven by a WorkManager foreground worker. */
interface UploadQueue {
    val items: StateFlow<List<UploadItem>>

    /** Re-reads the active account's items. */
    fun refresh(): List<UploadItem>
    suspend fun enqueue(drafts: List<UploadDraft>)
    fun pause(id: String)
    fun resume(id: String)
    fun retry(id: String)
    suspend fun cancel(id: String)
    fun remove(id: String)

    /**
     * Pauses [userId]'s uploads and starts none until [releaseUploads], and returns only when no request of theirs is
     * under way. Throws when that can't be reached. After an app restart each can be resumed.
     */
    suspend fun holdUploads(userId: String)

    /** Resumes what [holdUploads] paused. */
    suspend fun releaseUploads(userId: String)

    /**
     * Removes [userId]'s queued and staged uploads for good, for a deleted account. A saved marker comes first, so an
     * upload that is still running can't write back. Throws when it could not be saved.
     */
    suspend fun purgeAccount(userId: String)
}
