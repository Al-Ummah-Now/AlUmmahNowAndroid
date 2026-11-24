package net.alummahnow.core.bridge

/**
 * Public help, contact, privacy, terms and guidelines URLs opened from Viewer and Studio apps
 * (Play UGC and User Data policies; App Store 1.2 and 5.1.1(i)).
 */
object SupportLinks {
    private fun locale(language: String): String = if (language.lowercase().startsWith("ar")) "ar" else "en"

    fun helpUrl(language: String): String = "https://videos.alummahnow.net/${locale(language)}/help"

    fun contactUrl(language: String): String = "https://videos.alummahnow.net/${locale(language)}/contact"

    fun communityGuidelinesUrl(language: String): String =
        "https://videos.alummahnow.net/${locale(language)}/community-guidelines"

    fun privacyUrl(language: String): String = "https://www.alummahnow.net/${locale(language)}/privacy/"

    fun termsUrl(language: String): String = "https://www.alummahnow.net/${locale(language)}/terms/"
}
