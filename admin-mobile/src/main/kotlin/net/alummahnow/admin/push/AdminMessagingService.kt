package net.alummahnow.admin.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.alummahnow.admin.AdminApplication
import net.alummahnow.core.bridge.telemetry.TelemetryConsent

/** FCM entry point. The token and the message contents are never logged. */
class AdminMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val container = (application as AdminApplication).container
        if (container.session.active.value == null) return
        scope.launch { AdminPush.register(applicationContext, container.push, container.prefs.installId, container.prefs.language) }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val container = (application as? AdminApplication)?.container
        if (container != null && container.telemetry.consent != TelemetryConsent.OFF && container.telemetry.pendingCount > 0) {
            scope.launch { container.telemetry.flush() }
        }
        AdminNotifications.handle(this, message.data, message.notification?.title, message.notification?.body)
    }
}
