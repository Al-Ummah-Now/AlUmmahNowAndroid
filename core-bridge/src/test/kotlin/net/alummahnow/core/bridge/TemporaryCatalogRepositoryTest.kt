package net.alummahnow.core.bridge

import java.util.Locale
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.alummahnow.core.bridge.catalog.TemporaryCatalogRepository
import net.alummahnow.core.bridge.model.RailKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TemporaryCatalogRepositoryTest {
    private val server = MockWebServer()
    private lateinit var repo: TemporaryCatalogRepository

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Before fun setUp() {
        AunLog.enabled = false
        server.start()
        repo = TemporaryCatalogRepository(server.url("/").toString())
    }

    @After fun tearDown() = server.close()

    @Test fun `video detail maps localized title, chapters and ready subtitles`() = runTest {
        server.enqueue(MockResponse(body = fixture("video_detail.json")))
        val video = repo.video("ddd1c77b-4833-4d75-b1ce-105a4d083480")

        assertEquals("/api/videos/ddd1c77b-4833-4d75-b1ce-105a4d083480", server.takeRequest().url.encodedPath)
        assertEquals("محاضرة اختبار ١ — مقدمة في التفسير", video.summary.title.resolve(Locale.forLanguageTag("ar")))
        assertEquals("QA Lecture 1 — Introduction to Tafsir", video.summary.title.resolve(Locale.ENGLISH))
        assertEquals(4, video.chapters.size)
        assertEquals("المقدمة", video.chapters.first().title.resolve(Locale.forLanguageTag("ar")))
        assertEquals(listOf("en", "ar"), video.subtitles.map { it.language })
        assertTrue(video.subtitles.first().isDefault)
        assertTrue(video.isReady)
        assertEquals(75L, video.summary.durationSeconds)
    }

    @Test fun `missing video becomes NotFound`() = runTest {
        server.enqueue(MockResponse(code = 404))
        val error = runCatching { repo.video("nope") }.exceptionOrNull()
        assertTrue(error is CoreError.NotFound)
    }

    @Test fun `server error becomes Http`() = runTest {
        server.enqueue(MockResponse(code = 503))
        val error = runCatching { repo.video("x") }.exceptionOrNull()
        assertTrue(error is CoreError.Http && error.code == 503)
    }

    @Test fun `malformed body becomes Decode`() = runTest {
        server.enqueue(MockResponse(body = "<html>"))
        val error = runCatching { repo.video("x") }.exceptionOrNull()
        assertTrue(error is CoreError.Decode)
    }

    @Test fun `home keeps working rails when one fails, drops empty ones`() = runTest {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.endsWith("/trending") -> MockResponse(body = fixture("section_trending.json"))
                    path.endsWith("/recommended") -> MockResponse(code = 500)
                    path.endsWith("/popular") -> MockResponse(body = """{"data":[]}""")
                    else -> MockResponse(body = fixture("section_trending.json"))
                }
            }
        }
        val rails = repo.homeRails()
        assertEquals(listOf(RailKind.TRENDING, RailKind.RECENT), rails.map { it.kind })
        assertTrue(rails.first().videos.isNotEmpty())
    }

    @Test fun `home throws when every rail fails`() = runTest {
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest) = MockResponse(code = 500)
        }
        assertTrue(runCatching { repo.homeRails() }.exceptionOrNull() is CoreError.Http)
    }
}
