package net.alummahnow.admin

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import java.io.File
import kotlinx.coroutines.runBlocking
import net.alummahnow.admin.media.PrivateMedia
import net.alummahnow.core.bridge.admin.FixtureReviewService
import net.alummahnow.core.bridge.admin.HttpReviewService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Owner rule: "never cache or download on phone or native app ever, only encrypted stream." Loads a frame, an image
 * and a stretch of a stream the way the app does, then fails if any of it (or any media-looking file) is on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(UnstableApi::class)
class MediaNeverOnDiskTest {
    private val server = MockWebServer()
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** A marker inside every media body, so a copy is found whatever name it was saved under. */
    private val marker = "AUN-MEDIA-MARKER-7f3c".toByteArray()

    /** Frames and images are a real PNG (decoded like a frame); a saved copy is found by its bytes. */
    private val png = android.util.Base64.decode(FixtureReviewService.FRAME_PNG, android.util.Base64.DEFAULT)

    private fun body(prefix: ByteArray) = Buffer().write(prefix).write(marker).write(
        ByteArray(64 * 1024) {
            (it % 251).toByte()
        }
    ).write(marker)

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    @Test fun `frames, images and streamed media never reach the cache or files dirs`() = runBlocking {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                request.path!!.startsWith(
                    "/stream"
                ) -> MockResponse().setHeader("Content-Type", "video/mp4").setBody(body("....ftypisom".toByteArray()))
                else -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(png))
            }
        }

        // 1. A review frame over admin-api, decoded in memory.
        val base = server.url("/").toString()
        val bytes = HttpReviewService(base, base, accessToken = { "tok" }).media("r1", "review/v1/r1/frame-042.jpg")
        assertNotNull(PrivateMedia.decode(bytes, blurred = true))

        // 2. An image through the app's singleton image loader (as shared design components load them).
        val loader = SingletonImageLoader.get(context)
        assertNull("no disk cache", loader.diskCache)
        loader.execute(ImageRequest.Builder(context).data(server.url("/thumb.png").toString()).build())
        loader.execute(ImageRequest.Builder(context).data(server.url("/thumb.png").toString()).build())

        // 3. The player's data source: read the whole stream as ExoPlayer would.
        val source = PrivateMedia.dataSourceFactory().createDataSource()
        source.open(DataSpec(Uri.parse(server.url("/stream.mp4").toString())))
        val buf = ByteArray(8192)
        while (source.read(buf, 0, buf.size) != -1) Unit
        source.close()

        val offenders = mediaOnDisk()
        assertTrue("media reached disk: $offenders", offenders.isEmpty())
        // Frame, image (the second load is a memory hit), stream.
        assertEquals(3, server.requestCount)
    }

    @Test fun `the app's image loader is memory only`() {
        val loader = PrivateMedia.imageLoader(context)
        assertNull(loader.diskCache)
    }

    private fun mediaOnDisk(): List<String> {
        val roots = listOfNotNull(
            context.cacheDir,
            context.filesDir,
            context.codeCacheDir,
            context.noBackupFilesDir,
            context.externalCacheDir,
            context.getExternalFilesDir(null),
            File(context.applicationInfo.dataDir)
        )
        val media = Regex("(?i).*\\.(mp4|m4s|ts|m3u8|mpd|webm|mkv|jpg|jpeg|png|webp|gif|exo|bin|tmp)$")
        return roots.flatMap { root -> root.walkTopDown().filter { it.isFile }.toList() }.distinct().filter { f ->
            media.matches(f.name) || f.readBytes().let { contains(it, marker) || contains(it, png) }
        }.map { it.absolutePath }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
