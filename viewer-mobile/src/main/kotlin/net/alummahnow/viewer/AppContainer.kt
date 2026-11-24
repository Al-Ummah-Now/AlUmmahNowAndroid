package net.alummahnow.viewer

import android.content.Context
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.notifications.AppPushRegistrar
import net.alummahnow.core.bridge.notifications.EventsRepository
import net.alummahnow.core.bridge.notifications.HttpAppPushRegistrar
import net.alummahnow.core.bridge.notifications.HttpEventsRepository
import net.alummahnow.core.bridge.notifications.HttpNotificationsRepository
import net.alummahnow.core.bridge.notifications.NotificationsRepository

/**
 * Manual DI for the viewer. When the aun-core UniFFI AAR is published, swap the `Temporary*` /
 * `GuestOnly*` / `Http*` bindings here for the core-backed implementations; nothing else changes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = GuestOnlyAuthBridge()
    val notifications: NotificationsRepository = HttpNotificationsRepository(BuildConfig.API_BASE_URL, accessToken = auth::accessToken)
    val events: EventsRepository = HttpEventsRepository(BuildConfig.API_BASE_URL, accessToken = auth::accessToken)
    val pushRegistrar: AppPushRegistrar = HttpAppPushRegistrar(BuildConfig.API_BASE_URL, accessToken = auth::accessToken)
    val displayPrefs = DisplayPrefsStore(appContext)
    val installId: String by lazy {
        val prefs = appContext.getSharedPreferences("viewer_push", Context.MODE_PRIVATE)
        prefs.getString("install_id", null) ?: ("inst_" + java.util.UUID.randomUUID().toString().replace("-", "")).also {
            prefs.edit().putString("install_id", it).apply()
        }
    }
}
