package net.alummahnow.core.bridge.studio.core

import kotlinx.serialization.json.JsonObject
import net.alummahnow.core.bridge.studio.BulkItemResult
import net.alummahnow.core.bridge.studio.BulkResult
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.ChannelRole
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.DailyMetric
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoStat

/** JSON → model mapping, kept pure for unit tests. */
object StudioMappers {
    fun channel(o: JsonObject): StudioChannel? {
        val id = o.s("id") ?: return null
        return StudioChannel(
            id = id,
            name = o.s("name") ?: o.s("handle") ?: id,
            nameLocalized = o.strMap("nameLocalized"),
            handle = o.s("handle"),
            avatarUrl = o.s("avatarUrl"),
            role = ChannelRole.from(o.s("role")),
            capabilities = o.strList("capabilities").toSet(),
            isMain = o.b("isMain") == true,
            isSuspended = o.b("isSuspended") == true
        )
    }

    fun channels(body: String): List<StudioChannel> {
        val root = J.parse(body)
        val list = root.unwrap().arr() ?: root.obj()?.a("data") ?: return emptyList()
        return list.mapNotNull { it.obj()?.let(::channel) }
    }

    /** Studio status from a video's fields (same order as the server's `studioStatusOf`). */
    fun deriveStatus(o: JsonObject): ContentStatus {
        val upload = o.s("uploadStatus")
        val processing = o.s("processingStatus")
        val schedule = o.s("scheduledPublishStatus")
        return when {
            upload == "failed" || processing == "failed" || schedule == "failed" -> ContentStatus.FAILED
            upload in setOf("initiated", "uploading", "processing") ||
                processing in setOf("pending", "processing", "transcoding") -> ContentStatus.PROCESSING
            schedule in setOf("scheduled", "publishing") -> ContentStatus.SCHEDULED
            o.b("isDraft") == true -> ContentStatus.DRAFTS
            else -> ContentStatus.PUBLISHED
        }
    }

    fun video(o: JsonObject): StudioVideo? {
        val v = o.o("video") ?: o
        val id = v.s("id") ?: v.s("videoId") ?: return null
        return StudioVideo(
            id = id,
            title = v.s("title").orEmpty(),
            description = v.s("description").orEmpty(),
            thumbnailUrl = v.s("thumbnailUrl"),
            isPublic = v.b("isPublic") ?: true,
            isDraft = v.b("isDraft") == true,
            status = ContentStatus.from(v.s("studioStatus")) ?: deriveStatus(v),
            views = v.l("views"),
            likes = v.l("likesCount"),
            comments = v.l("commentsCount"),
            durationSeconds = v.d("duration"),
            category = v.s("category"),
            language = v.s("language"),
            tags = v.strList("tags"),
            titleLocalized = v.strMap("titleLocalized"),
            descriptionLocalized = v.strMap("descriptionLocalized"),
            createdAt = v.s("dateCreated"),
            deletedAt = o.s("deletedAt") ?: v.s("deletedAt"),
            restorableUntil = o.s("restorableUntil") ?: v.s("restorableUntil"),
            uploadFailureReason = v.s("uploadFailureReason") ?: v.s("processingIssueKind")
        )
    }

    fun bulk(body: String, requested: List<String>): BulkResult {
        val root = J.parse(body).obj() ?: return BulkResult(requested.map { BulkItemResult(it, true) })
        val results = root.a("results").ifEmpty { root.o("result")?.a("results") ?: root.a("items") }
        if (results.isEmpty()) return BulkResult(requested.map { BulkItemResult(it, root.b("success") != false) })
        return BulkResult(
            results.mapNotNull { e ->
                e.obj()?.let { r -> r.s("videoId")?.let { BulkItemResult(it, r.b("success") == true, r.s("error")) } }
            }
        )
    }

    fun playlist(o: JsonObject): StudioPlaylist? {
        val id = o.s("id") ?: return null
        return StudioPlaylist(id, o.s("title").orEmpty(), o.b("isPublic") ?: true)
    }

    fun analytics(o: JsonObject): ChannelAnalytics = ChannelAnalytics(
        totalViews = o.l("totalViews"),
        totalVideos = o.l("totalVideos"),
        totalSubscribers = o.l("totalSubscribers"),
        totalLikes = o.l("totalLikes"),
        totalComments = o.l("totalComments"),
        avgViewsPerVideo = o.d("avgViewsPerVideo") ?: 0.0,
        engagementRate = o.d("avgEngagementRate") ?: o.d("engagementRate") ?: 0.0,
        topVideos = o.a("topPerformingVideos").mapNotNull { e ->
            e.obj()?.let { v ->
                v.s("videoId")?.let {
                    VideoStat(
                        videoId = it,
                        title = v.s("title").orEmpty(),
                        thumbnailUrl = v.s("thumbnailUrl"),
                        views = v.l("views"),
                        likes = v.l("likes"),
                        comments = v.l("comments"),
                        watchTimeSeconds = v.l("watchTime"),
                        avgViewDurationSeconds = v.d("avgViewDuration") ?: 0.0
                    )
                }
            }
        },
        daily = o.a("recentMetrics").mapNotNull { e ->
            e.obj()?.let { m ->
                m.s("date")?.let {
                    DailyMetric(it, m.l("views"), m.l("likes"), m.l("comments"), m.l("subscribers"), m.l("watchTime"))
                }
            }
        }.sortedBy { it.date }
    )
}
