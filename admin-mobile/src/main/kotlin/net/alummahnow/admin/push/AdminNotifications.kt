package net.alummahnow.admin.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import net.alummahnow.admin.MainActivity
import net.alummahnow.admin.R
import net.alummahnow.core.bridge.AunLog

/**
 * Review alerts (AUN-ADMIN-NATIVE, Alerts). One high-importance channel, `review_queue`. A notification carries no
 * title, channel, thumbnail or image of the video: only "Video held for review · NSFW · 4 min left" as the server
 * wrote it. Actions: Open and Claim (never approve or reject). A data message `review_clear` removes the alert when
 * the item was claimed or decided anywhere; the tag is the review id, as in the FCM sender.
 */
object AdminNotifications {
    const val CHANNEL_ID = "review_queue"
    const val KIND_ALERT = "review_alert"
    const val KIND_CLEAR = "review_clear"
    const val EXTRA_REVIEW_ID = "net.alummahnow.admin.REVIEW_ID"
    const val EXTRA_CLAIM = "net.alummahnow.admin.CLAIM"
    private const val NOTIFICATION_ID = 0
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.push_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.push_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    /** Handles one FCM message (data, plus the notification part when the app was in the foreground). */
    fun handle(context: Context, data: Map<String, String>, title: String? = null, body: String? = null) {
        val reviewId = data["reviewId"]?.takeIf { SAFE_ID.matches(it) } ?: return
        when (data["kind"]) {
            KIND_ALERT -> show(context, reviewId, title ?: data["title"], body ?: data["body"])
            KIND_CLEAR -> clear(context, reviewId)
            else -> AunLog.w("push", "unknown message kind")
        }
    }

    fun clear(context: Context, reviewId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(reviewId, NOTIFICATION_ID)
    }

    fun show(context: Context, reviewId: String, title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            AunLog.w("push", "notifications not allowed")
            return
        }
        ensureChannel(context)
        val heading = title?.take(MAX_TEXT) ?: context.getString(R.string.push_channel_name)
        val text = body?.take(MAX_TEXT) ?: context.getString(R.string.push_body_default)
        val public = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(context.getString(R.string.push_channel_name))
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_review)
            .setContentTitle(heading)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setAutoCancel(true)
            .setContentIntent(open(context, reviewId, claim = false))
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.push_action_open),
                    open(context, reviewId, claim = false)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.push_action_claim),
                    open(context, reviewId, claim = true)
                ).build()
            )
            .build()
        context.getSystemService(NotificationManager::class.java)?.notify(reviewId, NOTIFICATION_ID, notification)
    }

    /** Opens the app on the item (after the device unlock); Claim also claims it. */
    private fun open(context: Context, reviewId: String, claim: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(if (claim) "claim:$reviewId" else "open:$reviewId")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_REVIEW_ID, reviewId)
            .putExtra(EXTRA_CLAIM, claim)
        return PendingIntent.getActivity(
            context,
            (reviewId + claim).hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private const val MAX_TEXT = 120
}
