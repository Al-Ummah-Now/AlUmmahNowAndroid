package net.alummahnow.admin

import android.app.NotificationManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import net.alummahnow.admin.push.AdminNotifications
import net.alummahnow.admin.security.SecureWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationsAndWindowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nm = context.getSystemService(NotificationManager::class.java)

    @Test fun `review alerts use the high-importance review_queue channel, Open and Claim, no image`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AdminNotifications.handle(
            context,
            mapOf(
                "kind" to "review_alert",
                "reviewId" to "r-1",
                "body" to "Video held for review · NSFW · 4 min left"
            )
        )
        val channel = nm.getNotificationChannel(AdminNotifications.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        val posted = shadowOf(nm).allNotifications.single()
        assertEquals(AdminNotifications.CHANNEL_ID, posted.channelId)
        assertEquals(2, posted.actions.size)
        assertNull("no large icon", posted.getLargeIcon())
        assertNull("no picture", posted.extras.get(android.app.Notification.EXTRA_PICTURE))
    }

    @Test fun `a review_clear data message removes the alert, unknown ids are ignored`() {
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AdminNotifications.handle(context, mapOf("kind" to "review_alert", "reviewId" to "r-2"))
        AdminNotifications.handle(context, mapOf("kind" to "review_alert", "reviewId" to "../evil"))
        assertEquals(1, shadowOf(nm).allNotifications.size)
        AdminNotifications.handle(context, mapOf("kind" to "review_clear", "reviewId" to "r-2"))
        assertTrue(shadowOf(nm).allNotifications.isEmpty())
    }

    @Test fun `every activity window of the app gets FLAG_SECURE`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertTrue(SecureWindows.isSecure(activity.window))
    }
}
