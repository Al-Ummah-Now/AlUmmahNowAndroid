package net.alummahnow.admin

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** The app language: English by default, Arabic (RTL) when chosen in Settings. */
object AdminLocale {
    fun wrap(base: Context, language: String): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
