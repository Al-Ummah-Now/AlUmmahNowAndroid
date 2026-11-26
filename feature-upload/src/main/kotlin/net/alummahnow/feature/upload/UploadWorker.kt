package net.alummahnow.feature.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlin.coroutines.cancellation.CancellationException
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem

/**
 * Keeps uploads running with the app in the background: a WorkManager foreground worker
 * (`dataSync`) with a progress notification. The transfer itself (resumable TUS) is aun-upload's;
 * if the process dies, the next run resumes from the last confirmed offset.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val host get() = applicationContext as UploadHost
    private var lastNotified = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(emptyList())

    override suspend fun doWork(): Result {
        // Work scheduled before an OS update must not start a dataSync service on API 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return Result.success()
        UploadNotifications.ensureChannel(applicationContext)
        runCatching { setForeground(foreground(emptyList())) }
            .onFailure {
                if (it is CancellationException) throw it
                AunLog.w("upload", "foreground not allowed now", "type" to it.javaClass.simpleName)
            }
        val outcome = runCatching {
            host.processUploads { items ->
                val now = System.currentTimeMillis()
                if (now - lastNotified >= UploadNotifications.NOTIFY_EVERY_MS) {
                    lastNotified = now
                    runCatching { setForegroundAsync(foreground(items)) }
                }
                lastItems = items
            }
        }
        outcome.exceptionOrNull()?.let {
            if (it is CancellationException) throw it
            AunLog.e("upload", "worker failed", it)
            return Result.retry()
        }
        UploadNotifications.notifyFinished(applicationContext, lastItems)
        return Result.success()
    }

    private var lastItems: List<UploadItem> = emptyList()

    private fun foreground(items: List<UploadItem>): ForegroundInfo {
        val notification = UploadNotifications.progressNotification(applicationContext, items)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(UploadNotifications.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UploadNotifications.NOTIFICATION_ID, notification)
        }
    }
}
