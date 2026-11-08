package net.alummahnow.viewer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.alummahnow.design.theme.ThemeMode

private val Context.dataStore by preferencesDataStore(name = "display")

/** Persists the theme choice. Language uses the platform per-app locale instead (Android 13+). */
class DisplayPrefsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")

    val theme: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { saved -> ThemeMode.entries.firstOrNull { it.name == saved } } ?: ThemeMode.SYSTEM
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }
}
