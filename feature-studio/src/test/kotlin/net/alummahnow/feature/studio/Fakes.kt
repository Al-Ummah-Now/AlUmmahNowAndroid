package net.alummahnow.feature.studio

import kotlinx.coroutines.flow.MutableStateFlow
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.BulkItemResult
import net.alummahnow.core.bridge.studio.BulkResult
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.ChannelRole
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentPage
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.DailyMetric
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioSuggestion
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.core.bridge.studio.VideoStat

val sampleAccount = StudioAccount("u1", "Creator", "creator@example.com", null, null, false)

val sampleChannels = listOf(
    StudioChannel("c1", "Lectures", mapOf("ar" to "محاضرات"), handle = "lectures", isMain = true),
    StudioChannel("c2", "Nasheeds", mapOf("ar" to "أناشيد"), role = ChannelRole.EDITOR, capabilities = setOf("video.upload", "video.edit"))
)

val sampleVideos = listOf(
    StudioVideo(
        "v1",
        "Introduction to Tafsir",
        titleLocalized = mapOf("ar" to "مقدمة في التفسير"),
        views = 1520,
        durationSeconds = 1830.0,
        category = "quran_tafsir",
        status = ContentStatus.PUBLISHED,
        tags = listOf("tafsir")
    ),
    StudioVideo(
        "v2",
        "Seerah overview",
        titleLocalized = mapOf("ar" to "نظرة عامة على السيرة"),
        isPublic = false,
        views = 87,
        durationSeconds = 600.0,
        status = ContentStatus.PUBLISHED
    ),
    StudioVideo("v3", "Ramadan reminders", views = 12000, durationSeconds = 45.0, category = "ramadan", status = ContentStatus.PUBLISHED)
)

class FakeSession(signedIn: Boolean = true) : AccountSession {
    override val accounts = MutableStateFlow(if (signedIn) listOf(sampleAccount) else emptyList())
    override val active = MutableStateFlow(if (signedIn) sampleAccount else null)
    var completeError: Exception? = null
    var lastMode: SignInMode? = null
    val signedOut = mutableListOf<String>()

    override fun beginSignIn(mode: SignInMode): String {
        lastMode = mode
        return "https://auth.example/authorize?mode=$mode"
    }

    override suspend fun completeSignIn(redirectUrl: String): StudioAccount {
        completeError?.let { throw it }
        accounts.value = listOf(sampleAccount)
        active.value = sampleAccount
        return sampleAccount
    }

    override fun switchTo(userId: String) {
        active.value = accounts.value.first { it.userId == userId }
    }

    override fun setActiveChannel(channelId: String?) {
        active.value = active.value?.copy(activeChannelId = channelId)
    }

    override suspend fun signOut(userId: String): String {
        signedOut += userId
        accounts.value = accounts.value.filterNot { it.userId == userId }
        active.value = accounts.value.firstOrNull()
        return "https://auth.example/logout"
    }

    override suspend fun accessToken(): String? = if (active.value != null) "token" else null
}

class FakeRepository : StudioRepository {
    var channels = sampleChannels
    var channelsError: Exception? = null
    val videos = sampleVideos.toMutableList()
    val deleted = mutableListOf<StudioVideo>()
    var softDelete = true
    val bulkCalls = mutableListOf<Pair<List<String>, BulkChanges>>()
    val queries = mutableListOf<ContentQuery>()

    override suspend fun channels(): List<StudioChannel> {
        channelsError?.let { throw it }
        return channels
    }

    override suspend fun creatorAccess() = CreatorAccess(canUpload = true, canCreateChannel = false, tierName = "Creator")

    override suspend fun content(query: ContentQuery): ContentPage {
        queries += query
        val source = if (query.status == ContentStatus.DELETED) deleted else videos.filter { it.status == query.status }
        return ContentPage(source.filter { query.text.isBlank() || it.title.contains(query.text, ignoreCase = true) }, null)
    }

    override suspend fun counts(channelId: String) = ContentCounts(
        published = videos.size,
        scheduled = 1,
        drafts = 2,
        processing = 0,
        failed = 1
    )

    override suspend fun suggestions(channelId: String, text: String) =
        videos.filter { it.title.contains(text, ignoreCase = true) }.map { StudioSuggestion(it.title, it.id, null) }

    override suspend fun video(id: String) = (videos + deleted).firstOrNull { it.id == id } ?: throw CoreError.NotFound(id)

    override suspend fun update(id: String, channelId: String, edit: VideoEdit): StudioVideo {
        val i = videos.indexOfFirst { it.id == id }
        val v = videos[i]
        val updated = v.copy(
            title = edit.title ?: v.title,
            description = edit.description ?: v.description,
            isPublic = edit.isPublic ?: v.isPublic,
            category = if (edit.category == VideoEdit.CLEAR) null else edit.category ?: v.category,
            language = edit.language ?: v.language,
            tags = edit.tags ?: v.tags
        )
        videos[i] = updated
        return updated
    }

    override suspend fun bulkUpdate(ids: List<String>, changes: BulkChanges): BulkResult {
        bulkCalls += ids to changes
        ids.forEach { id ->
            val i = videos.indexOfFirst { it.id == id }
            if (i >= 0) {
                val v = videos[i]
                videos[i] = v.copy(
                    isPublic = changes.isPublic ?: v.isPublic,
                    tags = (v.tags + changes.addTags).distinct() - changes.removeTags.toSet()
                )
            }
        }
        return BulkResult(ids.map { BulkItemResult(it, videos.any { v -> v.id == it }) })
    }

    override suspend fun softDeleteSupported(channelId: String) = softDelete

    override suspend fun delete(ids: List<String>, channelId: String): BulkResult {
        val gone = videos.filter { it.id in ids }
        videos.removeAll(gone)
        deleted += gone.map { it.copy(status = ContentStatus.DELETED, restorableUntil = "2026-12-10T00:00:00Z") }
        return BulkResult(ids.map { BulkItemResult(it, true) })
    }

    override suspend fun restore(ids: List<String>, channelId: String): BulkResult {
        val back = deleted.filter { it.id in ids }
        deleted.removeAll(back)
        videos += back.map { it.copy(status = ContentStatus.PUBLISHED, restorableUntil = null) }
        return BulkResult(ids.map { BulkItemResult(it, true) })
    }

    override suspend fun deletePermanently(id: String, channelId: String) {
        deleted.removeAll { it.id == id }
    }

    override suspend fun playlists(channelId: String) = listOf(
        StudioPlaylist("p1", "Tafsir series", true),
        StudioPlaylist("p2", "Khutbahs", false)
    )

    override suspend fun schedule(videoId: String, channelId: String, publishAtIso: String, timezone: String) = Unit

    override suspend fun addToPlaylists(videoId: String, channelId: String, playlistIds: List<String>, orderKey: String) = Unit

    override suspend fun analytics(channelId: String, range: AnalyticsRange) = sampleAnalytics
}

val sampleAnalytics = ChannelAnalytics(
    totalViews = 13607,
    totalVideos = 3,
    totalSubscribers = 412,
    totalLikes = 980,
    totalComments = 133,
    avgViewsPerVideo = 4535.7,
    engagementRate = 8.2,
    topVideos = listOf(
        VideoStat("v3", "Ramadan reminders", null, 12000, 800, 90, 360000, 30.0),
        VideoStat("v1", "Introduction to Tafsir", null, 1520, 150, 40, 540000, 355.0)
    ),
    daily = (1..28).map { d -> DailyMetric("2026-10-%02d".format(d), (d * 37L) % 500 + 40, 5, 1, d % 3L, 3600L * (d % 5)) }
)
