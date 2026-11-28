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

    /**
     * Atomically opts out without leaving an upload window during a slow [SharedPreferences]
     * commit or a restart window after revoking live consent:
     * 1. Remove the opt-in marker file first (fail-closed across restarts, even under `ENOSPC`).
     * 2. Immediately invoke [revokeLiveTelemetry] so active native flushes stop before the commit.
     * 3. Persist `false` to [SharedPreferences] last.
     *
     * Returns `(persistedOptOut, liveRevokeSucceeded)`.
     */
    fun disable(revokeLiveTelemetry: () -> Boolean): Pair<Boolean, Boolean> {
        val markerRemoved = removeMarkerFile()
        val liveRevoked = revokeLiveTelemetry()
        val prefSaved = prefs.edit().putBoolean(prefKey, false).commit()
        return Pair(markerRemoved || prefSaved, liveRevoked)
    }

    private fun enable(): Boolean {
        if (!createMarkerFile()) return false
        if (prefs.edit().putBoolean(prefKey, true).commit()) return true
        removeMarkerFile()
        prefs.edit().putBoolean(prefKey, false).commit()
        return false
    }

    private fun disable(): Boolean = disable { true }.first

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
