package net.alummahnow.core.bridge.upload

import kotlinx.coroutines.flow.Flow

data class UploadRequest(
    /** Stable id so a resumed upload (after app kill) continues instead of starting over. */
    val localId: String,
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val title: String,
    val channelId: String?
)

sealed interface UploadEvent {
    data class Progress(val uploadedBytes: Long, val totalBytes: Long) : UploadEvent {
        val fraction: Float get() = if (totalBytes <= 0) 0f else (uploadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
    }
    data class Completed(val videoId: String?) : UploadEvent
}

/**
 * Facade for `aun-upload` (resumable TUS). The OS-level background part (WorkManager) stays in the app;
 * this interface is the engine it drives.
 */
interface UploadEngine {
    /** Starts or resumes the upload. Errors are thrown as [net.alummahnow.core.bridge.CoreError]. */
    fun upload(request: UploadRequest): Flow<UploadEvent>
}
