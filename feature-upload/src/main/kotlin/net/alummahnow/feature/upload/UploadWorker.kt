package net.alummahnow.feature.upload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase

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
        ensureChannel(applicationContext)
        runCatching { setForeground(foreground(emptyList())) }
            .onFailure { AunLog.w("upload", "foreground not allowed now", "type" to it.javaClass.simpleName) }
        val outcome = runCatching {
            host.processUploads { items ->
                val now = System.currentTimeMillis()
                if (now - lastNotified >= NOTIFY_EVERY_MS) {
                    lastNotified = now
                    runCatching { setForegroundAsync(foreground(items)) }
                }
                lastItems = items
            }
        }
        outcome.exceptionOrNull()?.let {
            if (it is kotlin.coroutines.cancellation.CancellationException) throw it
            AunLog.e("upload", "worker failed", it)
            return Result.retry()
        }
        notifyFinished(lastItems)
        return Result.success()
    }

    private var lastItems: List<UploadItem> = emptyList()

    private fun foreground(items: List<UploadItem>): ForegroundInfo {
        val notification = progressNotification(applicationContext, items)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyFinished(items: List<UploadItem>) {
        val done = items.count { it.phase == UploadPhase.DONE }
        val failed = items.count { it.phase == UploadPhase.FAILED }
        if (done + failed == 0 || !canNotify(applicationContext)) return
        val res = applicationContext.resources
        val text = if (failed == 0) {
            res.getQuantityString(R.plurals.upload_notify_done, done, done)
        } else {
            res.getString(R.string.upload_notify_partial, done, failed)
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(res.getString(R.string.upload_notify_title))
            .setContentText(text)
            .setContentIntent(openApp(applicationContext))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canNotify checked above.
        NotificationManagerCompat.from(applicationContext).notify(DONE_NOTIFICATION_ID, n)
    }

    companion object {
        const val CHANNEL_ID = "uploads"
        private const val WORK_NAME = "studio-uploads"
        private const val NOTIFICATION_ID = 4101
        private const val DONE_NOTIFICATION_ID = 4102
        private const val NOTIFY_EVERY_MS = 1_000L
        private const val PERCENT = 100

        /** Starts (or queues after the running one) the upload worker. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.upload_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.upload_channel_description)
                }
            )
        }

        private fun openApp(context: Context): PendingIntent? = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        internal fun progressNotification(context: Context, items: List<UploadItem>): android.app.Notification {
            val res = context.resources
            val active = items.filter { it.isActive || it.phase == UploadPhase.DONE }
            val total = active.sumOf { it.totalBytes }.coerceAtLeast(1)
            val sent = active.sumOf { it.sentBytes }
            val running = items.count { it.isActive }
            val percent = (sent * PERCENT / total).toInt()
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(res.getQuantityString(R.plurals.upload_notify_running, running.coerceAtLeast(1), running.coerceAtLeast(1)))
                .setContentText(res.getString(R.string.upload_notify_percent, percent))
                .setProgress(PERCENT, percent, items.isEmpty())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
