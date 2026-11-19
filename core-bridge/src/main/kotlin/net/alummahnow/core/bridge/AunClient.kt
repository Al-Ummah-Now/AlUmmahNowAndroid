package net.alummahnow.core.bridge

/**
 * The `X-AUN-Client` header (AlUmmahNow request origin, wiki AUN-ORIGIN): which app, app version and OS sends a
 * request, e.g. `aun-studio/1.4.0 (Android 15)`. The servers count requests by it for errors and analytics. It
 * carries no identifier: no device id, no user, no install.
 */
object AunClient {
    const val HEADER = "X-AUN-Client"

    /** `aun-<app>/<version> (Android <release>)`, reduced to the characters the servers and aun-core accept. */
    fun value(app: String, versionName: String, osRelease: String? = android.os.Build.VERSION.RELEASE): String {
        val version = token(versionName).ifEmpty { "0" }
        val os = token(osRelease.orEmpty())
        return "aun-${token(app)}/$version (Android${if (os.isEmpty()) "" else " $os"})"
    }

    internal fun token(text: String): String = text.filter { (it.code < 128 && it.isLetterOrDigit()) || it in ".-_+" }.take(40)
}
