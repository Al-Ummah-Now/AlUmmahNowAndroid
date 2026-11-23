package net.alummahnow.viewer

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.auth.AuthBridge
import net.alummahnow.core.bridge.auth.GuestOnlyAuthBridge
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.player.offline.LicenseSlotReleaser
import net.alummahnow.player.offline.OfflineDownloadStore
import net.alummahnow.player.offline.PlaybackResumeStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caches last-known `/api/me/features` flags locally (defaulting OFF for every feature when unseen)
 * so Android Auto and offline download surfaces obey the server's feature switches even offline.
 */
class ViewerFeaturesCache(private val file: File) {
    private val mutableFeatures = MutableStateFlow(loadCached())
    val features: StateFlow<UserFeatures> = mutableFeatures.asStateFlow()

    val listenModeEnabled: Boolean get() = mutableFeatures.value.listenMode
    val offlineDownloadsEnabled: Boolean get() = mutableFeatures.value.offlineDownloads

    fun update(latest: UserFeatures) {
        mutableFeatures.value = latest
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            latest.enabled.forEach { arr.put(it) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(JSONObject().put("enabled", arr).toString())
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun loadCached(): UserFeatures {
        if (!file.exists()) return UserFeatures.NONE
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("enabled") ?: return@runCatching UserFeatures.NONE
            val keys = buildSet {
                for (i in 0 until arr.length()) {
                    arr.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
            UserFeatures(keys)
        }.getOrDefault(UserFeatures.NONE)
    }
}

private class HttpLicenseSlotReleaser(
    private val apiBaseUrl: String,
    private val bearerTokenProvider: () -> String? = { null }
) : LicenseSlotReleaser {
    override fun releaseLicense(licenseId: String): Boolean {
        val token = bearerTokenProvider() ?: return false
        return runCatching {
            val url = URL("${apiBaseUrl.trimEnd('/')}/api/v1/downloads/$licenseId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                conn.responseCode in 200..299 || conn.responseCode == 404
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    override fun revokeDevice(deviceId: String): Boolean {
        val token = bearerTokenProvider() ?: return false
        return runCatching {
            val url = URL("${apiBaseUrl.trimEnd('/')}/api/v1/downloads/revoke-device")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { out ->
                    out.write(JSONObject().put("deviceId", deviceId).toString().toByteArray(Charsets.UTF_8))
                }
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }
}

/**
 * Manual DI for the viewer. When the aun-core UniFFI AAR is published, swap the `Temporary*` /
 * `GuestOnly*` bindings here for the core-backed implementations; nothing else changes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val catalog: CatalogRepository = TemporaryCatalogRepository(BuildConfig.API_BASE_URL)
    val auth: AuthBridge = GuestOnlyAuthBridge()
    val displayPrefs = DisplayPrefsStore(appContext)
    val featuresCache: ViewerFeaturesCache by lazy {
        ViewerFeaturesCache(File(appContext.noBackupFilesDir, "aun_viewer_features.json"))
    }
    val downloads: OfflineDownloadStore by lazy {
        OfflineDownloadStore.createDefault(
            context = appContext,
            offlineDownloadsEnabled = featuresCache.offlineDownloadsEnabled,
            slotReleaser = HttpLicenseSlotReleaser(BuildConfig.API_BASE_URL)
        )
    }
    val resumeStore: PlaybackResumeStore by lazy {
        PlaybackResumeStore(File(appContext.noBackupFilesDir, "aun_playback_resume.json"))
    }

    /** Refreshes `listenMode` & `offlineDownloads` from `GET /api/me/features` and syncs the store. */
    fun refreshFeaturesAsync() {
        scope.launch {
            val latest = catalog.viewerFeatures()
            featuresCache.update(latest)
            downloads.offlineDownloadsEnabled = latest.offlineDownloads
            downloads.flushPendingSlotReleases()
        }
    }
}
