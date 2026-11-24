package net.alummahnow.core.bridge

/**
 * Public help, contact, privacy, terms and guidelines URLs opened from Viewer and Studio apps
 * (Play UGC and User Data policies; App Store 1.2 and 5.1.1(i)).
 */
object SupportLinks {
    private fun locale(language: String): String = if (language.lowercase().startsWith("ar")) "ar" else "en"

    /** The www host of the same environment. There is no default host: a build with no `videos.` host is a bug. */
    private fun wwwHost(webHost: String): String {
        require(webHost.startsWith(VIDEOS) && webHost.length > VIDEOS.length) { "not a videos host" }
        return "www." + webHost.removePrefix(VIDEOS)
    }

    private const val VIDEOS = "videos."

    fun helpUrl(language: String, webHost: String): String =
        "https://$webHost/${locale(language)}/help"

    fun contactUrl(language: String, webHost: String): String =
        "https://$webHost/${locale(language)}/contact"

    fun communityGuidelinesUrl(language: String, webHost: String): String =
        "https://$webHost/${locale(language)}/community-guidelines"

    fun privacyUrl(language: String, webHost: String): String =
        "https://${wwwHost(webHost)}/${locale(language)}/privacy/"

    fun termsUrl(language: String, webHost: String): String =
        "https://${wwwHost(webHost)}/${locale(language)}/terms/"
}
