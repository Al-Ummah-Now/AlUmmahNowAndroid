package net.alummahnow.viewer

import android.content.Context
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.live.HttpLiveRepository
import net.alummahnow.core.bridge.live.LiveRepository
import net.alummahnow.core.bridge.safety.ViewerBlockStore

/** What the app's screens need. [AppContainer] is the real one; tests hand in fakes. */
interface ViewerContainer {
    val catalog: CatalogRepository
    val live: LiveRepository
    val displayPrefs: DisplayPrefsStore
    val blockStore: ViewerBlockStore
}

/**
 * Manual DI for the viewer. When the aun-core UniFFI AAR is published, swap the `Temporary*` /
 * `GuestOnly*` bindings here for the core-backed implementations; nothing else changes.
 */
class AppContainer(context: Context) : ViewerContainer {
    override val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    override val live: LiveRepository = HttpLiveRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = GuestOnlyAuthBridge()
    override val displayPrefs = DisplayPrefsStore(context.applicationContext)
    override val blockStore = ViewerBlockStore(
        context.applicationContext.getSharedPreferences("aun_viewer_blocks", Context.MODE_PRIVATE)
    )
}
