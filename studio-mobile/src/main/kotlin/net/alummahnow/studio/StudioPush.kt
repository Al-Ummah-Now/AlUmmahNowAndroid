package net.alummahnow.studio

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.notifications.AppPushRegistrar

/**
 * FCM push registration for Studio (`PUT /api/devices/:installId`, `app: "studio"`, `provider: "fcm"`).
 * Without owner-supplied `google-services.json`, `FIREBASE_CONFIGURED` is false and registration is skipped cleanly.
 */
object StudioPush {
    fun available(context: Context): Boolean =
        BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    suspend fun register(context: Context, registrar: AppPushRegistrar, installId: String, locale: String): Boolean {
        if (!available(context)) return false
        val token = fcmToken() ?: return false
        return runCatching {
            registrar.register("studio", installId, token, locale, BuildConfig.VERSION_NAME)
        }.onFailure { AunLog.e("push", "studio push register failed", it) }.isSuccess
    }

    suspend fun unregister(context: Context, registrar: AppPushRegistrar, installId: String) {
        if (!available(context)) return
        runCatching { registrar.unregister(installId) }
            .onFailure { AunLog.e("push", "studio push unregister failed", it) }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
