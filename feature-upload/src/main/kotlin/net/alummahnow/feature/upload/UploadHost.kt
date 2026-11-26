package net.alummahnow.feature.upload

import net.alummahnow.core.bridge.studio.UploadItem

/**
 * Implemented by the app's `Application`: lets the background upload task drive the upload queue
 * (stage files, hand them to aun-upload, wait, apply post-upload settings).
 */
interface UploadHost {
    fun setTransferRunning(running: Boolean)
    suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit)
}
