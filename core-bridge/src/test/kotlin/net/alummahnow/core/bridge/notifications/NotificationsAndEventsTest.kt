package net.alummahnow.core.bridge.notifications

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationsAndEventsTest {
    @Test fun `quiet hours evaluate overnight and prayer windows cleanly`() {
        val qh = NotificationQuietHours(
            enabled = true,
            timezone = "Asia/Riyadh",
            startMinute = 1320,
            endMinute = 360,
            prayerQuietEnabled = true,
            prayerWindows = listOf(PrayerQuietWindow(startMinute = 735, endMinute = 760))
        )
        assertTrue(qh.containsMinute(1380))
        assertTrue(qh.containsMinute(120))
        assertFalse(qh.containsMinute(600))
        assertTrue(qh.containsMinute(745))
    }

    @Test fun `badge visibleCount respects seen vs read mode`() {
        val seenMode = NotificationBadge(unreadCount = 5, unseenCount = 2, menuOpen = NotificationMenuOpenMode.SEEN)
        val readMode = NotificationBadge(unreadCount = 5, unseenCount = 2, menuOpen = NotificationMenuOpenMode.READ)
        assertEquals(2, seenMode.visibleCount)
        assertEquals(5, readMode.visibleCount)
    }

    @Test fun `http notifications, events, features and push registrar talk to api-videos`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"result":{"unreadCount":4,"unseenCount":2,"menuOpen":"seen"}}"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    "{\"result\":{\"quietHours\":{\"enabled\":true,\"timezone\":\"UTC\"," +
                        "\"startMinute\":1320,\"endMinute\":360,\"prayerQuietEnabled\":true,\"prayerWindows\":[]}}}"
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"version":1,"features":{"eventCalendar":true,"upload":false}}"""
                )
            )
            server.enqueue(
                MockResponse().setBody(
                    "{\"result\":{\"items\":[{\"streamId\":\"str_1\",\"channelId\":\"ch_1\"," +
                        "\"channelName\":\"Al-Manar\",\"channelNameAr\":\"المنار\",\"title\":\"Weekly Halqa\"," +
                        "\"kind\":\"live\",\"eventType\":\"halqa\",\"status\":\"scheduled\"," +
                        "\"scheduledStartAt\":\"2026-11-28T19:00:00Z\",\"watchPath\":\"/live/str_1\"}]}}"
                )
            )
            server.enqueue(MockResponse().setBody("""{"success":true}"""))

            val baseUrl = server.url("/").toString()
            val notifs = HttpNotificationsRepository(baseUrl, accessToken = { "tok_test" })
            val events = HttpEventsRepository(baseUrl, accessToken = { "tok_test" })
            val push = HttpAppPushRegistrar(baseUrl, accessToken = { "tok_test" })

            val badge = notifs.badge()
            assertEquals(2, badge.visibleCount)

            val saved = notifs.updateQuietHours(NotificationQuietHours(enabled = true, prayerQuietEnabled = true))
            assertTrue(saved.enabled)
            assertTrue(saved.prayerQuietEnabled)

            val features = events.viewerFeatures()
            assertTrue(features.eventCalendar)
            assertFalse(features.upload)

            val list = events.upcomingEvents(eventType = "halqa")
            assertEquals(1, list.size)
            assertEquals("المنار", list[0].localizedChannelName("ar"))

            push.register("viewer", "inst_12345678", "fcm_tok", "ar", "0.1.0")
            val req1 = server.takeRequest()
            assertEquals("/api/notifications/unread-count", req1.path)
            val req2 = server.takeRequest()
            assertEquals("PUT", req2.method)
            assertEquals("/api/notifications/settings", req2.path)
            val req3 = server.takeRequest()
            assertEquals("/api/me/features", req3.path)
            val req4 = server.takeRequest()
            assertEquals("/api/events?limit=24&eventType=halqa", req4.path)
            val req5 = server.takeRequest()
            assertEquals("PUT", req5.method)
            assertEquals("/api/devices/inst_12345678", req5.path)
        }
    }
}
