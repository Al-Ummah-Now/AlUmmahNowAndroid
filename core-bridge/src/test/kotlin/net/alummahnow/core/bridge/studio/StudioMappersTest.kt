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
            {"id":"c1","userId":"u","name":"Seerah","nameLocalized":{"ar":"السيرة"},"isMain":true,"role":"owner","capabilities":[]},
            {"id":"c2","userId":"x","name":"Shared","handle":"shared","role":"editor","capabilities":["video.upload","video.edit"]},
            {"name":"no id"}]}"""
        val list = StudioMappers.channels(access)
        assertEquals(listOf("c1", "c2"), list.map { it.id })
        assertEquals("السيرة", list[0].localizedName("ar"))
        assertEquals("Seerah", list[0].localizedName("fr"))
        assertTrue(list[0].isMain)
        assertEquals(ChannelRole.EDITOR, list[1].role)
        assertTrue(list[1].can(StudioChannel.VIDEO_EDIT))
        assertFalse(list[1].can(StudioChannel.VIDEO_DELETE))
        assertTrue(list[0].can(StudioChannel.VIDEO_DELETE))

        val paged = StudioMappers.channels("""{"cursor":"","limit":10,"data":[{"id":"c3","name":"Mine"}]}""")
        assertEquals("c3", paged.single().id)
    }

    @Test
    fun `playlist text uses trimmed translations and falls back to original text`() {
        val playlist = requireNotNull(
            StudioMappers.playlist(
                obj(
                    """{"id":"p1","title":"Original title","description":"Original description","isPublic":false,
                        "titleLocalized":{"en":" English ","ar":" العربية "},
                        "descriptionLocalized":{"en":" English description ","ar":"  ","fr":123,"de":null}}"""
                )
            )
        )

        assertFalse(playlist.isPublic)
        assertEquals(mapOf("en" to " English ", "ar" to " العربية "), playlist.titleLocalized)
        assertEquals(mapOf("en" to " English description ", "ar" to "  "), playlist.descriptionLocalized)
        assertEquals("English", playlist.localizedTitle("en-US"))
        assertEquals("العربية", playlist.localizedTitle("ar_SA"))
        assertEquals("Original title", playlist.localizedTitle("fr"))
        assertEquals("English description", playlist.localizedDescription("en"))
        assertEquals("Original description", playlist.localizedDescription("ar-SA"))
        assertEquals("Original description", playlist.localizedDescription("fr"))
        assertEquals("Original title", playlist.copy(titleLocalized = mapOf("ar" to " ")).localizedTitle("ar"))
    }

    @Test
    fun `legacy playlists retain their original text in both languages`() {
        val playlist = requireNotNull(StudioMappers.playlist(obj("""{"id":"p1","title":" Original "}""")))
        assertEquals(emptyMap<String, String>(), playlist.titleLocalized)
        assertEquals(emptyMap<String, String>(), playlist.descriptionLocalized)
        assertEquals(" Original ", playlist.localizedTitle("en"))
        assertEquals(" Original ", playlist.localizedTitle("ar"))
        assertEquals("", playlist.localizedDescription("ar"))
        assertNull(StudioMappers.playlist(obj("""{"title":"Missing ID"}""")))
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
    fun `a video in review has its own status, after processing and before scheduled`() {
        for (step in listOf("checking", "in_review", "appealed")) {
            assertEquals(step, ContentStatus.IN_REVIEW, StudioMappers.deriveStatus(obj("""{"review":{"status":"$step"}}""")))
        }
        assertEquals(ContentStatus.NOT_APPROVED, StudioMappers.deriveStatus(obj("""{"review":{"status":"rejected"},"isDraft":true}""")))
        assertEquals(
            ContentStatus.IN_REVIEW,
            StudioMappers.deriveStatus(obj("""{"review":{"status":"checking"},"scheduledPublishStatus":"scheduled"}"""))
        )
        assertEquals(
            ContentStatus.PROCESSING,
            StudioMappers.deriveStatus(obj("""{"review":{"status":"checking"},"processingStatus":"processing"}"""))
        )
        for (step in listOf("approved", "post_review", "something_new")) {
            assertEquals(step, ContentStatus.PUBLISHED, StudioMappers.deriveStatus(obj("""{"review":{"status":"$step"}}""")))
        }
    }

    @Test
    fun `a review is read whole, and what it leaves out has a default`() {
        fun review(json: String) = StudioMappers.video(obj("""{"id":"v1","review":$json}"""))!!.review
        assertEquals(
            VideoReview(ReviewStep.CHECKING, etaMinutes = 12),
            review("""{"status":"checking","since":"2026-11-25T10:00:00Z","etaAt":"2026-11-25T10:12:10Z"}""")
        )
        assertEquals(VideoReview(ReviewStep.CHECKING, etaMinutes = 5), review("""{"status":"checking","since":"2026-11-25T10:00:00Z"}"""))
        assertEquals(
            VideoReview(ReviewStep.REJECTED, reason = "copyright", appealUsed = true),
            review("""{"status":"rejected","since":"x","reasonCode":"copyright","appealUsed":true}""")
        )
        assertNull(review("""{"status":"approved","since":"2026-11-25T10:00:00Z"}"""))
        assertNull(StudioMappers.video(obj("""{"id":"v1"}"""))!!.review)
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
                "topPerformingVideos":[{"videoId":"v","title":"Top","titleLocalized":{"ar":"الأعلى"},"views":60,"likes":5,"comments":1,
                "watchTime":3600,"avgViewDuration":60}],
                "recentMetrics":[{"date":"2026-11-02","views":5,"watchTime":60},{"date":"2026-11-01","views":3,"watchTime":30}]}"""
            )
        )
        assertEquals(100L, a.totalViews)
        assertEquals(11.5, a.engagementRate, 0.0)
        assertEquals("v", a.topVideos.single().videoId)
        assertEquals("الأعلى", a.topVideos.single().titleLocalized["ar"])
        assertEquals(listOf("2026-11-01", "2026-11-02"), a.daily.map { it.date })
        assertEquals(90L, a.watchTimeSeconds)
    }

    @Test
    fun `bulk changes emptiness`() {
        assertTrue(BulkChanges().isEmpty)
        assertFalse(BulkChanges(addTags = listOf("x")).isEmpty)
    }

    @Test
    fun `creatorAccess decodes termsAccepted true false and omitted`() {
        val accepted = StudioMappers.creatorAccess(
            """{"success":true,"result":{"canUpload":true,"canCreateChannel":true,"tier":{"name":"Creator"},"termsAccepted":true}}"""
        )
        assertTrue(accepted.canUpload)
        assertTrue(accepted.canCreateChannel)
        assertEquals("Creator", accepted.tierName)
        assertEquals(true, accepted.termsAccepted)

        val required = StudioMappers.creatorAccess(
            """{"success":true,"result":{"canUpload":true,"canCreateChannel":false,"termsAccepted":false}}"""
        )
        assertEquals(false, required.termsAccepted)

        val legacy = StudioMappers.creatorAccess(
            """{"success":true,"result":{"canUpload":true,"canCreateChannel":true}}"""
        )
        assertNull(legacy.termsAccepted)

        val explicitNull = StudioMappers.creatorAccess(
            """{"success":true,"result":{"canUpload":true,"termsAccepted":null}}"""
        )
        assertNull(explicitNull.termsAccepted)
    }

    @Test
    fun `termsVersion is the server's text, or nothing`() {
        fun version(json: String) = StudioMappers.creatorAccess("""{"success":true,"result":{"termsAccepted":false$json}}""").termsVersion
        assertEquals("2026-11-24", version(""","termsVersion":" 2026-11-24 """"))
        listOf("", ""","termsVersion":null""", ""","termsVersion":"" """, ""","termsVersion":20261124""", ""","termsVersion":{}""")
            .forEach { assertNull(it, version(it)) }
    }

    @Test
    fun `a malformed termsAccepted counts as absent, the server enforces the Terms`() {
        listOf("\"true\"", "\"false\"", "\"yes\"", "\"\"", "0", "1", "{}", "[]", "[false]", "{\"accepted\":false}").forEach { value ->
            val access = StudioMappers.creatorAccess("""{"success":true,"result":{"canUpload":true,"termsAccepted":$value}}""")
            assertNull("termsAccepted: $value", access.termsAccepted)
            assertTrue(access.canUpload)
        }
    }
}
