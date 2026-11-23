package net.alummahnow.viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.links.ShortLinks
import net.alummahnow.core.bridge.notifications.AppPushRegistrar
import net.alummahnow.design.R as DesignR

/**
 * Notification channels for the viewer (`E22` §6.1): `live`, `new_videos`, `comments`, and `account`.
 * Optional categories are suppressed server-side during quiet hours (`E22-D6`); `account` notices always arrive.
 */
object ViewerNotificationChannels {
    const val CHANNEL_LIVE = "live"
    const val CHANNEL_NEW_VIDEOS = "new_videos"
    const val CHANNEL_COMMENTS = "comments"
    const val CHANNEL_ACCOUNT = "account"

    fun ensureAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val specs = listOf(
            Triple(CHANNEL_LIVE, DesignR.string.aun_notif_channel_live, NotificationManager.IMPORTANCE_HIGH),
            Triple(CHANNEL_NEW_VIDEOS, DesignR.string.aun_notif_channel_new_videos, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNEL_COMMENTS, DesignR.string.aun_notif_channel_comments, NotificationManager.IMPORTANCE_DEFAULT),
            Triple(CHANNEL_ACCOUNT, DesignR.string.aun_notif_channel_account, NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannels(
            specs.map { (id, nameRes, importance) ->
                NotificationChannel(id, context.getString(nameRes), importance).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setShowBadge(true)
                }
            }
        )
    }
}

/**
 * FCM push registration for the viewer (`PUT /api/devices/:installId`, `app: "viewer"`, `provider: "fcm"`).
 * Without owner-supplied `google-services.json`, `FIREBASE_CONFIGURED` is false and registration is a clean no-op.
 */
object ViewerPush {
    fun available(context: Context): Boolean =
        BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    suspend fun register(context: Context, registrar: AppPushRegistrar, installId: String, locale: String): Boolean {
        if (!available(context)) return false
        val token = fcmToken() ?: return false
        return runCatching {
            registrar.register("viewer", installId, token, locale, BuildConfig.VERSION_NAME)
        }.onFailure { AunLog.e("push", "viewer push register failed", it) }.isSuccess
    }

    suspend fun unregister(context: Context, registrar: AppPushRegistrar, installId: String) {
        if (!available(context)) return
        runCatching { registrar.unregister(installId) }
            .onFailure { AunLog.e("push", "viewer push unregister failed", it) }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}

/** Launches Android's system share sheet with a canonical `alummahnow.link` short link (`#20`). */
object ShareShortLink {
    fun shareVideo(
        context: Context,
        videoId: String,
        title: String,
        shortLinkHost: String = BuildConfig.SHORT_LINK_HOST,
        startSeconds: Long? = null,
        lang: String? = null
    ) {
        val url = ShortLinks.video(
            id = videoId,
            shortLinkHost = shortLinkHost,
            startSeconds = startSeconds?.takeIf { it > 0 },
            lang = lang
        ) ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        val chooser = Intent.createChooser(send, context.getString(DesignR.string.aun_share))
        runCatching { context.startActivity(chooser) }
            .onFailure { AunLog.w("share", "share chooser failed", "type" to it.javaClass.simpleName) }
    }
}
