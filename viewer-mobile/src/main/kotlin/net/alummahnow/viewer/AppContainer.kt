package net.alummahnow.viewer

import android.content.Context
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.live.HttpLiveRepository
import net.alummahnow.core.bridge.live.LiveRepository

/**
 * Manual DI for the viewer. When the aun-core UniFFI AAR is published, swap the `Temporary*` /
 * `GuestOnly*` bindings here for the core-backed implementations; nothing else changes.
 */
class AppContainer(context: Context) {
    val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    val live: LiveRepository = HttpLiveRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = GuestOnlyAuthBridge()
    val displayPrefs = DisplayPrefsStore(context.applicationContext)
}
