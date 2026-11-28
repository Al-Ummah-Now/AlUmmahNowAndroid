package net.alummahnow.admin

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alummahnow.core.bridge.telemetry.TelemetryConsentStore

/**
 * Small per-device settings: the explicit Light/Dark choice, the app language and the push install id. No media, no
 * URLs and no review content are ever written here (or anywhere else on disk).
 */
class AdminPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private val telemetryConsentStore = TelemetryConsentStore(
        prefs = prefs,
        prefKey = KEY_TELEMETRY,
        optInMarkerFile = File(context.applicationContext.noBackupFilesDir, "admin-telemetry-opt-in")
    )
    private val _telemetryEnabled = MutableStateFlow(telemetryConsentStore.isEnabled())
    private val _telemetrySaveUncertain = MutableStateFlow(false)
    private val _telemetryWarningRes = MutableStateFlow<Int?>(null)

    /** `true` dark, `false` light, `null` until the first explicit choice (then the device's setting applies). */
    var dark: Boolean?
        get() = if (prefs.contains(KEY_DARK)) prefs.getBoolean(KEY_DARK, true) else null
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY_DARK) else putBoolean(KEY_DARK, value) }.apply()
        }

    /** `en` (the default) or `ar`. */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, null)?.takeIf { it in LANGUAGES } ?: DEFAULT_LANGUAGE
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value.takeIf { it in LANGUAGES } ?: DEFAULT_LANGUAGE).commit()
        }

    /** This install's id for the push registry (`PUT /api/v1/devices/:installId`). Random; not tied to the person. */
    val installId: String
        get() = prefs.getString(KEY_INSTALL, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_INSTALL, it).apply() }

    /** Opt-in diagnostics & crash reports. Always defaults to `false`. */
    val telemetryEnabledFlow: StateFlow<Boolean> = _telemetryEnabled.asStateFlow()

    /** Process-scoped flag kept true across Activity recreation when a preference commit failed. */
    val telemetrySaveUncertain: StateFlow<Boolean> = _telemetrySaveUncertain.asStateFlow()

    /** Process-scoped warning string resource id preserved across Activity recreation and new launches. */
    val telemetryWarningRes: StateFlow<Int?> = _telemetryWarningRes.asStateFlow()

    var telemetryEnabled: Boolean
        get() = _telemetryEnabled.value || _telemetrySaveUncertain.value
        set(value) {
            setTelemetryEnabled(value)
        }

    /** Persists telemetry preference synchronously and returns whether the write committed. */
    fun setTelemetryEnabled(enabled: Boolean): Boolean {
        val committed = telemetryConsentStore.setEnabled(enabled)
        if (committed) _telemetryEnabled.value = enabled
        return committed
    }

    /**
     * Atomically opts out of telemetry: removes the opt-in marker, invokes [revokeLiveTelemetry]
     * immediately, and commits `false` to preferences last. Returns `(persistedOptOut, purged)`.
     */
    fun disableTelemetry(revokeLiveTelemetry: () -> Boolean): Pair<Boolean, Boolean> {
        val (saved, purged) = telemetryConsentStore.disable(revokeLiveTelemetry)
        if (saved) _telemetryEnabled.value = false
        return Pair(saved, purged)
    }

    fun setTelemetrySaveUncertain(uncertain: Boolean) {
        _telemetrySaveUncertain.value = uncertain
    }

    fun setTelemetryWarningRes(resId: Int?) {
        _telemetryWarningRes.value = resId
    }

    companion object {
        private const val NAME = "aun_admin_prefs"
        private const val KEY_DARK = "dark"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_INSTALL = "install_id"
        private const val KEY_TELEMETRY = "telemetry_enabled"
        const val DEFAULT_LANGUAGE = "en"
        val LANGUAGES = setOf("en", "ar")
    }
}
