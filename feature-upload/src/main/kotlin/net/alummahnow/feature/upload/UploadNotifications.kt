package net.alummahnow.feature.upload

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.aunString

object UploadNotifications {
    private const val CHANNEL_ID = "uploads"
    internal const val NOTIFICATION_ID = 4101
    private const val DONE_NOTIFICATION_ID = 4102
    internal const val NOTIFY_EVERY_MS = 1_000L
    private const val PERCENT = 100

    internal fun notifyFinished(context: Context, items: List<UploadItem>) {
        val done = items.count { it.phase == UploadPhase.DONE }
        val failed = items.count { it.phase == UploadPhase.FAILED }
        if (done + failed == 0 || !canNotify(context)) return
        val res = context.resources
        val text = if (failed == 0) {
            res.aunPlural(R.plurals.upload_notify_done, done, done)
        } else {
            res.aunString(R.string.upload_notify_partial, done, failed)
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(res.getString(R.string.upload_notify_title))
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission") // canNotify checked above.
        NotificationManagerCompat.from(context).notify(DONE_NOTIFICATION_ID, n)
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
            .setContentTitle(res.aunPlural(R.plurals.upload_notify_running, running.coerceAtLeast(1), running.coerceAtLeast(1)))
            .setContentText(res.aunString(R.string.upload_notify_percent, percent))
            .setProgress(PERCENT, percent, items.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
