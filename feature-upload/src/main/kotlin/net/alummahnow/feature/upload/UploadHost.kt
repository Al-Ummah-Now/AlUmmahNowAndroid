package net.alummahnow.feature.upload

import net.alummahnow.core.bridge.studio.UploadItem

/**
 * Implemented by the app's `Application`: lets the background worker drive the upload queue
 * (stage files, hand them to aun-upload, wait, apply post-upload settings).
 */
interface UploadHost {
    suspend fun processUploads(onUpdate: (List<UploadItem>) -> Unit)
}
