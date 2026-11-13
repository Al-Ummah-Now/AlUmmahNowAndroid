package net.alummahnow.admin

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.alummahnow.core.bridge.admin.HttpReviewService
import net.alummahnow.core.bridge.admin.PrivateHttp
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewError
import net.alummahnow.core.bridge.admin.ReviewReason
import net.alummahnow.core.bridge.admin.ReviewTab
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The review contract (VIDEO-REVIEW.md §6) and the device registry, against a local server. */
class HttpReviewServiceTest {
    private val server = MockWebServer()
    private lateinit var service: HttpReviewService

    @Before fun setUp() {
        server.start()
        val base = server.url("/").toString()
        service = HttpReviewService(base, base, accessToken = { "tok" })
    }

    @After fun tearDown() = server.shutdown()

    private fun json(code: Int = 200, body: String) = MockResponse().setResponseCode(
        code
    ).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `queue asks for the tab and query, with the token and no-store`() = runTest {
        server.enqueue(
            json(
                body =
                """{"result":{"items":[{"id":"r1","subject_id":"v1","status":"in_review","nsfw":1,"risk":0.8,""" +
                    """"hold_cause":"nsfw","sla_due_at":"2026-11-13T12:00:00Z","version":3}],"nextCursor":"c2"}}"""
            )
        )
        val page = service.queue(ReviewTab.ACTION_NEEDED, " market ", null)
        val req = server.takeRequest()
        assertEquals("/api/review/queue", req.requestUrl!!.encodedPath)
        assertEquals("action_needed", req.requestUrl!!.queryParameter("tab"))
        assertEquals("market", req.requestUrl!!.queryParameter("q"))
        assertEquals("Bearer tok", req.getHeader("Authorization"))
        assertEquals("no-store", req.getHeader("Cache-Control"))
        val item = page.items.single()
        assertTrue(item.nsfw)
        assertTrue(item.isSensitive)
        assertEquals(3, item.version)
        assertEquals("c2", page.nextCursor)
    }

    @Test fun `a decision sends the reason code, note and version`() = runTest {
        server.enqueue(
            json(
                body =
                """{"result":{"review":{"id":"r1","subject_id":"v1","status":"rejected","decision":"reject",""" +
                    """"undo_until":"2026-11-13T12:10:00Z","version":4}}}"""
            )
        )
        val item = service.decide("r1", ReviewDecision.REJECT, ReviewReason.SPAM_SCAM, " scam ", 3)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/review/r1/decision", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("reject", body["decision"]!!.jsonPrimitive.content)
        assertEquals("spam_scam", body["reasonCode"]!!.jsonPrimitive.content)
        assertEquals("scam", body["note"]!!.jsonPrimitive.content)
        assertEquals(3, body["version"]!!.jsonPrimitive.content.toInt())
        assertEquals("rejected", item.status)
        assertEquals(4, item.version)
    }

    @Test fun `409 is "Already approved by Name"`() = runTest {
        server.enqueue(json(409, """{"code":"conflict","decision":"approve","decidedByName":"Amina","decidedAt":"2026-11-13T12:00:00Z"}"""))
        val e = runCatching { service.decide("r1", ReviewDecision.APPROVE, null, null, 1) }.exceptionOrNull() as ReviewError.AlreadyDecided
        assertEquals("approve", e.decision)
        assertEquals("Amina", e.byName)
    }

    @Test fun `server codes map to what the app explains`() {
        assertTrue(HttpReviewService.failure(403, """{"code":"step_up_required"}""".toByteArray()) is ReviewError.StepUpRequired)
        assertTrue(HttpReviewService.failure(403, """{"code":"reviewer_only"}""".toByteArray()) is ReviewError.NotStaff)
        assertTrue(HttpReviewService.failure(409, """{"code":"undo_expired"}""".toByteArray()) is ReviewError.UndoExpired)
        val claimed = HttpReviewService.failure(409, """{"code":"claimed","assignedToName":"Bilal"}""".toByteArray())
        assertEquals("Bilal", (claimed as ReviewError.ClaimedByOther).name)
    }

    @Test fun `profile refuses non-staff`() = runTest {
        server.enqueue(json(body = """{"result":{"userId":"u1","roles":["user"]}}"""))
        assertTrue(runCatching { service.profile() }.exceptionOrNull() is ReviewError.NotStaff)
        server.enqueue(json(body = """{"result":{"userId":"u2","displayName":"Sara","roles":["Senior Reviewer"]}}"""))
        val p = service.profile()
        assertTrue(p.isReviewer)
        assertTrue(p.isSeniorReviewer)
    }

    @Test fun `push registers this install as the admin app`() = runTest {
        server.enqueue(json(body = "{}"))
        service.register("install-1", "fcm-token", "ar", "0.1.0")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/api/v1/devices/install-1", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("admin", body["app"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("fcm", body["provider"]!!.jsonPrimitive.content)
        assertEquals("ar", body["locale"]!!.jsonPrimitive.content)
    }

    @Test fun `the stream URL never reaches toString`() = runTest {
        server.enqueue(
            json(
                body =
                """{"result":{"review":{"id":"r1","subject_id":"v1"},"findings":[],""" +
                    """"stream":{"url":"https://stream.example/v1?sig=SECRET","preview":true}}}"""
            )
        )
        val detail = service.detail("r1")
        assertFalse(detail.toString().contains("SECRET"))
        assertTrue(detail.stream!!.preview)
    }

    @Test fun `the private client has no cache and refuses plain HTTP off loopback`() {
        assertNull(PrivateHttp.client.cache)
        assertThrows(IllegalArgumentException::class.java) {
            PrivateHttp.client.newCall(PrivateHttp.request("http://example.com/").build()).execute()
        }
    }

    @Test fun `a redirect off loopback to plain HTTP is refused on that hop`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/stolen"))
        val e = runCatching {
            PrivateHttp.client.newCall(PrivateHttp.request(server.url("/r").toString()).build()).execute()
        }.exceptionOrNull()
        assertTrue("refused: $e", e is IllegalArgumentException)
    }
}
