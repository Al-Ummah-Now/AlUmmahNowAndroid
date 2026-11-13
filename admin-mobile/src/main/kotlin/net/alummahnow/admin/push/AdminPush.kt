package net.alummahnow.admin.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import net.alummahnow.admin.BuildConfig
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.admin.PushRegistrar

/** What the Settings sheet says about alerts on this device. */
enum class PushState { ON, NOT_CONFIGURED, BLOCKED, FAILED }

/**
 * FCM registration for review alerts (`PUT /api/v1/devices/:installId`, `app: "admin"`, `provider: "fcm"`). Without the
 * owner's Firebase config (`google-services.json`) the build has no Firebase app: push is off and Settings says so.
 * Tests never reach FCM: they use [PushRegistrar] fakes and the server's log-only push provider.
 */
object AdminPush {
    fun available(context: Context): Boolean = BuildConfig.FIREBASE_CONFIGURED && FirebaseApp.getApps(context).isNotEmpty()

    suspend fun register(context: Context, registrar: PushRegistrar, installId: String, locale: String): PushState {
        if (!available(context)) return PushState.NOT_CONFIGURED
        val token = fcmToken() ?: return PushState.FAILED
        return runCatching { registrar.register(installId, token, locale, BuildConfig.VERSION_NAME) }
            .fold(onSuccess = { PushState.ON }, onFailure = {
                AunLog.e("push", "register failed", it)
                PushState.FAILED
            })
    }

    /** Signing out removes this install from the registry and drops the FCM token. */
    suspend fun unregister(context: Context, registrar: PushRegistrar, installId: String) {
        if (!available(context)) return
        runCatching { registrar.unregister(installId) }.onFailure { AunLog.e("push", "unregister failed", it) }
        runCatching { FirebaseMessaging.getInstance().deleteToken() }
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            cont.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
