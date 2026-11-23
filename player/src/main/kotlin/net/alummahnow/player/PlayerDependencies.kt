package net.alummahnow.player

import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.player.offline.LicenseRefreshExecutor
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore

/**
 * Implemented by each app's Application so [AunPlaybackService] (started by the system, e.g. from
 * Android Auto or a media button) can reach the catalogue without a DI framework.
 */
interface PlayerDependencies {
    val catalog: CatalogRepository

    /** Activity to open when the user taps the media notification. */
    val sessionActivityClass: Class<*>

    /** Phones cast to Chromecast; TV apps are cast targets themselves and Fire TV has no Play services. */
    val castEnabled: Boolean get() = true

    /** Admin feature switches (AUN-FEATURES): both default OFF until enabled by the server. */
    val listenModeEnabled: Boolean get() = false
    val offlineDownloadsEnabled: Boolean get() = false

    val downloadStore: OfflineDownloadStore? get() = null
    val resumeStore: PlaybackResumeStore? get() = null
    val licenseRefreshExecutor: LicenseRefreshExecutor? get() = null
}
