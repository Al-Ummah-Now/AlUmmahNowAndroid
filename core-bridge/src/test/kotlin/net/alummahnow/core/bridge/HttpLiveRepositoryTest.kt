package net.alummahnow.core.bridge

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import net.alummahnow.core.bridge.live.HttpLiveRepository
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveFeatures
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpLiveRepositoryTest {
    private val server = MockWebServer()
    private var nowMs = 1_000_000L
    private lateinit var repo: HttpLiveRepository

    @Before fun setUp() {
        AunLog.enabled = false
        server.start()
        repo = HttpLiveRepository(server.url("/").toString()) { nowMs }
    }

    @After fun tearDown() = server.close()

    private fun answer(body: String, code: Int = 200) = server.enqueue(MockResponse(code = code, body = body))

    @Test fun `lists what is on with its tag and skips what it cannot read`() = runTest {
        answer(
            """{"success":true,"result":{"data":[
            {"id":"a","title":"T","status":"live","channelName":"C","currentViewers":4,"eventType":"halqa"},
            {"id":"b","title":"P","status":"paused"},
            {"id":"c","status":"live","kind":"premiere","eventType":"unknown","thumbnailUrl":""}],"cursor":""}}"""
        )
        val list = repo.streams(LiveStatus.LIVE, LiveEventType.HALQA)
        val url = server.takeRequest().url
        assertEquals("live", url.queryParameter("status"))
        assertEquals("halqa", url.queryParameter("eventType"))
        assertEquals(listOf("a", "c"), list.map { it.id })
        assertEquals(LiveEventType.HALQA, list[0].eventType)
        assertEquals(LiveKind.PREMIERE, list[1].kind)
        assertNull(list[1].eventType)
        assertNull(list[1].thumbnailUrl)
    }

    @Test fun `an older server's state has no chat and no premiere`() = runTest {
        answer("""{"result":{"status":"live","sessionStatus":"live","playbackUrl":"https://x.test/p.m3u8"}}""")
        val state = repo.state("s")
        assertEquals(LiveKind.LIVE, state.kind)
        assertFalse(state.chatEnabled || state.chatReplay)
        assertEquals(0, state.clockOffsetMs)
    }

    @Test fun `the server's clock and a premiere's facts`() = runTest {
        answer(
            """{"result":{"status":"live","kind":"premiere","premiereVideoId":"v","durationSeconds":600,
            "startedAt":"2026-11-24T18:00:00.000Z","serverNow":1020000,"chat":{"enabled":true,"replay":false}}}"""
        )
        val state = repo.state("s")
        assertEquals(20_000, state.clockOffsetMs)
        assertEquals("v", state.premiereVideoId)
        assertTrue(state.chatEnabled)
    }

    @Test fun `a private stream is not found and features fail closed`() = runTest {
        answer("""{"code":"not_found"}""", code = 404)
        assertTrue(runCatching { repo.state("s") }.exceptionOrNull() is CoreError.NotFound)
        answer("", code = 503)
        assertEquals(LiveFeatures(), repo.features())
        answer("<html>")
        assertEquals(LiveFeatures(), repo.features())
        answer("""{"version":1,"features":{"liveChat":true,"liveEvents":"yes"}}""")
        assertEquals(LiveFeatures(chat = true), repo.features())
    }

    @Test fun `chat and its replay`() = runTest {
        answer("""{"result":{"messages":[{"id":"m","authorName":"A","authorRole":"moderator","text":"x","offsetMs":5}],"pinned":null}}""")
        assertEquals(LiveChatMessage.Role.MODERATOR, repo.chat("s").messages.single().role)
        server.takeRequest()
        answer("""{"result":{"messages":[{"id":"m","authorName":"A","text":"x","offsetMs":61000}],"truncated":true}}""")
        val page = repo.chatReplay("s", 60_000, 120_000)
        assertTrue(page.truncated)
        assertEquals("fromMs=60000&toMs=120000", server.takeRequest().url.encodedQuery)
    }

    @Test fun `counts the viewer`() = runTest {
        answer("""{"result":{"viewers":7}}""")
        assertEquals(7L, repo.beat("s", "v", leaving = false))
        assertEquals("POST", server.takeRequest().method)
        answer("", code = 500)
        assertTrue(runCatching { repo.beat("s", "v", leaving = true) }.exceptionOrNull() is CoreError.Http)
    }

    @Test fun `reads when to ask again, and a bad value is as if absent`() = runTest {
        answer("""{"result":{"messages":[],"pollAfterMs":30000}}""")
        assertEquals(30_000L, repo.chat("s").pollAfterMs)
        for (bad in listOf("\"30000\"", "null", "{}", "0", "-5", "1e999")) {
            answer("""{"result":{"status":"live","pollAfterMs":$bad}}""")
            assertNull(bad, repo.state("s").pollAfterMs)
        }
        answer("""{"result":{"status":"live","pollAfterMs":12000.5}}""")
        assertEquals(12_000L, repo.state("s").pollAfterMs)
    }

    @Test fun `a refusal says when to ask again`() = runTest {
        suspend fun refused(code: Int, header: String?, date: String? = null): Long? {
            val answer = MockResponse.Builder().code(code)
            if (header != null) answer.addHeader("Retry-After", header)
            if (date != null) answer.addHeader("Date", date)
            server.enqueue(answer.build())
            return (runCatching { repo.chat("s") }.exceptionOrNull() as CoreError.Http).retryAfterMs
        }
        assertEquals(30_000L, refused(429, "30"))
        assertEquals(30_000L, refused(503, " 30\t"))
        assertNull(refused(429, "-1"))
        assertEquals(86_400_000L, refused(503, "999999"))
        assertEquals(86_400_000L, refused(429, "99999999999"))
        // OkHttp itself cannot read this one: refused, not crashed
        assertNull(refused(503, "99999999999"))
        assertNull(refused(503, null))
        assertNull(refused(429, "soon"))
        assertNull(refused(500, "30"))
        // A date, from the answer's own; without one, from the device's clock
        val noon = "Tue, 24 Nov 2026 12:00:00 GMT"
        assertEquals(45_000L, refused(429, "Tue, 24 Nov 2026 12:00:45 GMT", noon))
        assertEquals(45_000L, refused(503, "Tue, 24 Nov 2026 12:00:45 GMT", noon))
        assertEquals(0L, refused(429, "Tue, 24 Nov 2026 11:00:00 GMT", noon))
        assertEquals(86_400_000L, refused(429, "Tue, 24 Nov 2099 12:00:00 GMT", noon))
        nowMs = 1_795_521_570_000
        assertEquals(30_000L, refused(429, noon))
        assertNull(refused(429, "Tue, 24 Nov", noon))
    }
}
