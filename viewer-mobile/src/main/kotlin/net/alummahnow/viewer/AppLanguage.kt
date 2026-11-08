package net.alummahnow.viewer

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList

/** In-app language switch (Arabic / English / follow the system) via the Android 13+ per-app locale API. */
object AppLanguage {
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Current per-app language tag, or null when following the system. */
    fun current(context: Context): String? {
        if (!isSupported) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0].language
    }

    fun set(context: Context, languageTag: String?) {
        if (!isSupported) return
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (languageTag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(languageTag)
    }
}
