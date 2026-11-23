package net.alummahnow.viewer

import android.content.Context
import java.io.File
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore

/**
 * Manual DI for the viewer. When the aun-core UniFFI AAR is published, swap the `Temporary*` /
 * `GuestOnly*` bindings here for the core-backed implementations; nothing else changes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = GuestOnlyAuthBridge()
    val displayPrefs = DisplayPrefsStore(appContext)
    val downloads: OfflineDownloadStore = OfflineDownloadStore.createDefault(appContext, offlineDownloadsEnabled = false)
    val resumeStore: PlaybackResumeStore = PlaybackResumeStore(File(appContext.noBackupFilesDir, "aun_playback_resume.json"))
}
