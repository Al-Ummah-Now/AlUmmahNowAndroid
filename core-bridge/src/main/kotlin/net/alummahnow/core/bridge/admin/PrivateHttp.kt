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
    /** Refuses plain HTTP to anything but loopback, and asks every cache on the way not to store. */
    val noStore = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        require(url.isHttps || isLoopback(url.host)) { "Admin traffic is TLS only" }
        val response = chain.proceed(
            request.newBuilder()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .build()
        )
        response.newBuilder().header("Cache-Control", "no-store").removeHeader("ETag").build()
    }

    fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "[::1]"

    /** A new private client. Tests and the player share [client]; nothing gives it a [okhttp3.Cache]. */
    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .cache(null)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .followSslRedirects(true)
        .followRedirects(true)
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .addInterceptor(noStore)
        .build()

    val client: OkHttpClient by lazy { newClient() }

    /** A request no cache may keep. */
    fun request(url: String): Request.Builder = Request.Builder().url(url).header("Cache-Control", "no-store")

    private const val TIMEOUT_S = 30L
}
