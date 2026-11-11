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
    val isFinished: Boolean get() = phase in setOf(UploadPhase.DONE, UploadPhase.FAILED, UploadPhase.CANCELLED)
}

/** Resumable (TUS) uploads for the active account, driven by a WorkManager foreground worker. */
interface UploadQueue {
    val items: StateFlow<List<UploadItem>>
    suspend fun enqueue(drafts: List<UploadDraft>)
    fun pause(id: String)
    fun resume(id: String)
    fun retry(id: String)
    suspend fun cancel(id: String)
    fun remove(id: String)
}
