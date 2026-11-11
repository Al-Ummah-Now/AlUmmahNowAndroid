package net.alummahnow.core.bridge.studio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.alummahnow.core.bridge.studio.core.StudioMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMappersTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `channels from access envelope and from the paginated list`() {
        val access = """{"success":true,"data":[
            {"id":"c1","userId":"u","name":"Lectures","nameLocalized":{"ar":"محاضرات"},"isMain":true,"role":"owner","capabilities":[]},
            {"id":"c2","userId":"x","name":"Shared","handle":"shared","role":"editor","capabilities":["video.upload","video.edit"]},
            {"name":"no id"}]}"""
        val list = StudioMappers.channels(access)
        assertEquals(listOf("c1", "c2"), list.map { it.id })
        assertEquals("محاضرات", list[0].localizedName("ar"))
        assertEquals("Lectures", list[0].localizedName("fr"))
        assertTrue(list[0].isMain)
        assertEquals(ChannelRole.EDITOR, list[1].role)
        assertTrue(list[1].can(StudioChannel.VIDEO_EDIT))
        assertFalse(list[1].can(StudioChannel.VIDEO_DELETE))
        assertTrue(list[0].can(StudioChannel.VIDEO_DELETE))

        val paged = StudioMappers.channels("""{"cursor":"","limit":10,"data":[{"id":"c3","name":"Mine"}]}""")
        assertEquals("c3", paged.single().id)
    }

    @Test
    fun `studio status follows the server order`() {
        assertEquals(ContentStatus.FAILED, StudioMappers.deriveStatus(obj("""{"uploadStatus":"failed","isDraft":true}""")))
        assertEquals(ContentStatus.PROCESSING, StudioMappers.deriveStatus(obj("""{"uploadStatus":"uploading"}""")))
        assertEquals(ContentStatus.PROCESSING, StudioMappers.deriveStatus(obj("""{"processingStatus":"transcoding"}""")))
        assertEquals(ContentStatus.SCHEDULED, StudioMappers.deriveStatus(obj("""{"scheduledPublishStatus":"scheduled"}""")))
        assertEquals(ContentStatus.DRAFTS, StudioMappers.deriveStatus(obj("""{"isDraft":true}""")))
        assertEquals(ContentStatus.PUBLISHED, StudioMappers.deriveStatus(obj("""{"isPublic":false}""")))
    }

    @Test
    fun `video maps view fields and prefers the server studio status`() {
        val v = StudioMappers.video(
            obj(
                """{"id":"v1","title":"T","isPublic":false,"views":12,"likesCount":3,"commentsCount":1,"duration":61.5,
                "tags":"a, b","category":"fiqh","language":"ar","studioStatus":"drafts","titleLocalized":{"ar":"ع"}}"""
            )
        )!!
        assertEquals(ContentStatus.DRAFTS, v.status)
        assertEquals(listOf("a", "b"), v.tags)
        assertEquals(12L, v.views)
        assertEquals(3L, v.likes)
        assertFalse(v.isPublic)
        assertEquals("ع", v.titleLocalized["ar"])
        assertNull(StudioMappers.video(obj("""{"title":"no id"}""")))
        val wrapped = StudioMappers.video(
            obj("""{"video":{"id":"v2","title":"x"},"deletedAt":"2026-11-10T00:00:00Z","restorableUntil":"2026-12-10T00:00:00Z"}""")
        )!!
        assertEquals("v2", wrapped.id)
        assertEquals("2026-12-10T00:00:00Z", wrapped.restorableUntil)
    }

    @Test
    fun `bulk results per item`() {
        val r = StudioMappers.bulk(
            """{"success":true,"results":[{"videoId":"a","success":true,"changed":true},""" +
                """{"videoId":"b","success":false,"error":"Forbidden"}],"summary":{"total":2,"succeeded":1,"failed":1}}""",
            listOf("a", "b")
        )
        assertEquals(1, r.succeeded)
        assertEquals(listOf("b"), r.failedIds)
        assertEquals("Forbidden", r.items[1].error)
        assertEquals(2, StudioMappers.bulk("""{"success":true}""", listOf("x", "y")).succeeded)
    }

    @Test
    fun `channel analytics with daily metrics in date order`() {
        val a = StudioMappers.analytics(
            obj(
                """{"channelId":"c","totalViews":100,"totalVideos":4,"totalSubscribers":7,"totalLikes":9,"totalComments":2,
                "avgViewsPerVideo":25,"avgEngagementRate":11.5,
                "topPerformingVideos":[{"videoId":"v","title":"Top","views":60,"likes":5,"comments":1,"watchTime":3600,"avgViewDuration":60}],
                "recentMetrics":[{"date":"2026-11-02","views":5,"watchTime":60},{"date":"2026-11-01","views":3,"watchTime":30}]}"""
            )
        )
        assertEquals(100L, a.totalViews)
        assertEquals(11.5, a.engagementRate, 0.0)
        assertEquals("v", a.topVideos.single().videoId)
        assertEquals(listOf("2026-11-01", "2026-11-02"), a.daily.map { it.date })
        assertEquals(90L, a.watchTimeSeconds)
    }

    @Test
    fun `bulk changes emptiness`() {
        assertTrue(BulkChanges().isEmpty)
        assertFalse(BulkChanges(addTags = listOf("x")).isEmpty)
    }
}
