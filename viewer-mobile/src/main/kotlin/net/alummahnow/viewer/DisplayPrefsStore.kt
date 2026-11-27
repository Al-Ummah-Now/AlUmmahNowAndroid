package net.alummahnow.viewer

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.alummahnow.design.theme.ThemeMode

private val Context.dataStore by preferencesDataStore(name = "display")

/** Persists the theme choice and opt-in telemetry preference. Language uses the platform per-app locale (Android 13+). */
class DisplayPrefsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val telemetryKey = booleanPreferencesKey("telemetry_enabled")

    /** The stored mode, or null until one is stored (then the app follows the device). A stored "SYSTEM" is null. */
    val theme: Flow<ThemeMode?> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
    }

    /** Opt-in diagnostics & crash reports. Always defaults to `false`. */
    val telemetryEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[telemetryKey] ?: false
    }

    /** Stores the device's mode the first time the app starts (owner 2026-11-13: Light or Dark, no System). */
    suspend fun adoptDeviceMode(dark: Boolean) {
        context.dataStore.edit { prefs ->
            val stored = prefs[themeKey]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } }
            if (stored == null) prefs[themeKey] = (if (dark) ThemeMode.DARK else ThemeMode.LIGHT).name
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[telemetryKey] = enabled }
    }
}
