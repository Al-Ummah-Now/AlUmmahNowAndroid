package net.alummahnow.core.bridge.admin

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The only HTTP client the Admin app uses (owner: "never cache or download on phone or native app ever, only
 * encrypted stream"). No disk cache, no cookies, every request and response marked `no-store`, TLS only except a
 * loopback host for tests and the local stack. Response bodies are read into memory and never written to a file;
 * `MediaNeverOnDiskTest` fails if anything it loads lands in the app's cache or files directories.
 */
object PrivateHttp {
    /**
     * TLS only (loopback excepted, for tests and the local stack), checked before every hop connects. OkHttp's own
     * redirects are off; this follows them itself so a redirect to plain HTTP is refused, not fetched. Every request
     * and response is marked `no-store`.
     */
    val noStore = Interceptor { chain ->
        var request = chain.request()
        var hops = 0
        while (true) {
            val url = request.url
            require(url.isHttps || isLoopback(url.host)) { "Admin traffic is TLS only" }
            val response = chain.proceed(request.newBuilder().header("Cache-Control", "no-store").header("Pragma", "no-cache").build())
            val next = response.header("Location")?.takeIf { response.isRedirect }?.let { url.resolve(it) }
            if (next == null || hops++ >= MAX_REDIRECTS) {
                return@Interceptor response.newBuilder().header("Cache-Control", "no-store").removeHeader("ETag").build()
            }
            response.close()
            // A 303, or a 301/302 after POST, becomes a GET as browsers do; 307/308 keep the method and body.
            val keep = response.code == HTTP_TEMP_REDIRECT || response.code == HTTP_PERM_REDIRECT
            request = request.newBuilder().url(next).apply {
                if (!keep && request.method != "GET" && request.method != "HEAD") method("GET", null)
                if (next.host != url.host) removeHeader("Authorization")
            }.build()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "[::1]"

    /** A new private client. Tests and the player share [client]; nothing gives it a [okhttp3.Cache]. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        // Redirects are followed by [noStore], which checks every hop before it connects.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .addInterceptor(noStore)
        .build()

    val client: OkHttpClient by lazy { newClient() }

    /** A request no cache may keep. */
    fun request(url: String): Request.Builder = Request.Builder().url(url).header("Cache-Control", "no-store")

    private const val TIMEOUT_S = 30L
    private const val MAX_REDIRECTS = 5
    private const val HTTP_TEMP_REDIRECT = 307
    private const val HTTP_PERM_REDIRECT = 308
}
