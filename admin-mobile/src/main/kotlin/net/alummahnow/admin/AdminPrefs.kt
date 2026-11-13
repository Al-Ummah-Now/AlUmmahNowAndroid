package net.alummahnow.admin

import android.content.Context
import java.util.UUID

/**
 * Small per-device settings: the explicit Light/Dark choice, the app language and the push install id. No media, no
 * URLs and no review content are ever written here (or anywhere else on disk).
 */
class AdminPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

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

    companion object {
        private const val NAME = "aun_admin_prefs"
        private const val KEY_DARK = "dark"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_INSTALL = "install_id"
        const val DEFAULT_LANGUAGE = "en"
        val LANGUAGES = setOf("en", "ar")
    }
}
