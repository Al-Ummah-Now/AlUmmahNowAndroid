package net.alummahnow.core.bridge

import kotlinx.coroutines.runBlocking
import net.alummahnow.core.bridge.admin.HttpReviewService
import net.alummahnow.core.bridge.admin.PrivateHttp
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.links.AppLinkParser
import net.alummahnow.core.bridge.links.ShortLinks
import net.alummahnow.core.bridge.live.HttpLiveRepository
import net.alummahnow.core.bridge.safety.ViewerReportUrls
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InjectionAuditTest {
    @Test
    fun safeBrowserUrlRejectsNonHttpSchemesAndUserinfo() {
        assertTrue(SafeInput.isSafeBrowserUrl("https://alummahnow.net/en/video/v1"))
        assertTrue(SafeInput.isSafeBrowserUrl("http://127.0.0.1:8787/callback"))
        assertFalse(SafeInput.isSafeBrowserUrl("javascript:alert(1)"))
        assertFalse(SafeInput.isSafeBrowserUrl("file:///etc/passwd"))
        assertFalse(SafeInput.isSafeBrowserUrl("intent://evil#Intent;end"))
        assertFalse(SafeInput.isSafeBrowserUrl("http://alummahnow.net/en/video/v1"))
        assertFalse(SafeInput.isSafeBrowserUrl("https://user:pass@alummahnow.net/v1"))
        assertFalse(SafeInput.isSafeBrowserUrl("https://alummahnow.net/v1\r\nX: 1"))
    }

    @Test
    fun aunLogSanitizesNewlinesControlCharsAndSecrets() {
        val formatted = AunLog.format(
            "failed\r\nINFO forged line",
            arrayOf("err" to "Bearer secret-tok\nuser@example.com")
        )
        assertFalse(formatted.contains('\n'))
        assertFalse(formatted.contains('\r'))
        assertFalse(formatted.contains("secret-tok"))
        assertFalse(formatted.contains("user@example.com"))
    }

    @Test
    fun appLinkAndShortLinksRejectUserinfoAndHostInjection() {
        assertNull(AppLinkParser.parse("https://evil@alummahnow.net/video/abc", "alummahnow.net"))
        assertNull(AppLinkParser.parse("https://alummahnow.net/@..", "alummahnow.net"))
        assertNull(AppLinkParser.parse("https://alummahnow.net/@a%5Cb", "alummahnow.net"))
        assertNull(ShortLinks.shortLinkOriginFor("evil\r\n.alummahnow.link"))
        assertNull(ShortLinks.shortLinkOriginFor("a b.alummahnow.link"))
        assertThrows(IllegalArgumentException::class.java) {
            SupportLinks.helpUrl("en", "videos.evil.com/path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ViewerReportUrls.videoUrl("v1", "en", "evil.com@alummahnow.net")
        }
    }

    @Test
    fun httpClientsRejectPathTraversalSegmentsBeforeNetwork() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                calls++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        ).build()

        val review = HttpReviewService("https://admin.api.example.net", "https://videos.api.example.net", { "tok" }, client)
        val catalog = TemporaryCatalogRepository("https://videos.api.example.net", client)
        val live = HttpLiveRepository("https://videos.api.example.net", client)

        for (bad in listOf("", ".", "..", "a/b", "../audit/capture", "id?x=1")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { review.claim(bad) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { review.unregister(bad) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.video(bad) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { live.stream(bad) } }
        }
        assertEquals(0, calls)
    }

    @Test
    fun privateHttpStripsAuthAndProofOnCrossPortRedirect() {
        var sawAuthOnSecondHop: String? = "unset"
        var sawProofOnSecondHop: String? = "unset"
        var hop = 0
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(PrivateHttp.noStore)
            .addInterceptor(
                Interceptor { chain ->
                    hop++
                    if (hop == 1) {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Found")
                            .header("Location", "https://admin.api.example.net:8443/other")
                            .body("".toResponseBody(null))
                            .build()
                    } else {
                        sawAuthOnSecondHop = chain.request().header("Authorization")
                        sawProofOnSecondHop = chain.request().header("X-AUN-Native-Proof")
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("{}".toResponseBody(null))
                            .build()
                    }
                }
            )
            .build()

        val req = PrivateHttp.request("https://admin.api.example.net/api/me")
            .header("Authorization", "Bearer secret")
            .header("X-AUN-Native-Proof", "proof-secret")
            .build()
        client.newCall(req).execute().close()
        assertNull(sawAuthOnSecondHop)
        assertNull(sawProofOnSecondHop)
    }
}
