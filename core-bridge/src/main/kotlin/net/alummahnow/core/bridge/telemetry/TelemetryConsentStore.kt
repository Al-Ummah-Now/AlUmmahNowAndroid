package net.alummahnow.core.bridge.telemetry

import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/**
 * Fail-closed opt-in consent persistence backed by both [SharedPreferences] and an explicit
 * opt-in marker file in `noBackupFilesDir`.
 *
 * Opting in requires BOTH the marker file and the preference commit to succeed (rolling back
 * on failure). Opting out deletes the marker file first — which succeeds even when disk space
 * is exhausted (`ENOSPC`) and `SharedPreferences.Editor.commit()` cannot write a replacement XML
 * file — so a failed preference commit on opt-out can never re-enable collection on restart.
 */
class TelemetryConsentStore(
    private val prefs: SharedPreferences,
    private val prefKey: String,
    private val optInMarkerFile: File
) {
    /**
     * Reads the persisted opt-in state fail-closed: collection is enabled only when BOTH the
     * preference and opt-in marker file are present. Self-heals stale `true` preferences if the
     * marker file was removed during a prior opt-out whose preference commit failed.
     */
    fun isEnabled(): Boolean {
        val prefEnabled = prefs.getBoolean(prefKey, false)
        val markerPresent = optInMarkerFile.exists()
        if (prefEnabled && !markerPresent) {
            prefs.edit().putBoolean(prefKey, false).commit()
            return false
        }
        return prefEnabled && markerPresent
    }

    /** Persists opt-in (`true`) or opt-out (`false`) fail-closed and returns whether it succeeded. */
    fun setEnabled(enabled: Boolean): Boolean = if (enabled) enable() else disable()

    private fun enable(): Boolean {
        if (!createMarkerFile()) return false
        if (prefs.edit().putBoolean(prefKey, true).commit()) return true
        removeMarkerFile()
        prefs.edit().putBoolean(prefKey, false).commit()
        return false
    }

    private fun disable(): Boolean {
        val markerRemoved = removeMarkerFile()
        val prefSaved = prefs.edit().putBoolean(prefKey, false).commit()
        return markerRemoved || prefSaved
    }

    private fun createMarkerFile(): Boolean = try {
        optInMarkerFile.parentFile?.mkdirs()
        if (optInMarkerFile.exists()) true else optInMarkerFile.createNewFile()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun removeMarkerFile(): Boolean = try {
        !optInMarkerFile.exists() || optInMarkerFile.delete()
    } catch (_: SecurityException) {
        false
    }
}
