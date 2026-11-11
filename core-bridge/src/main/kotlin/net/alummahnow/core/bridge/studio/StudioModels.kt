package net.alummahnow.core.bridge.studio

/** A signed-in account on this device (no secrets). */
data class StudioAccount(
    val userId: String,
    val displayName: String?,
    val email: String?,
    val avatarUrl: String?,
    val activeChannelId: String?,
    /** The refresh token was rejected: the account must sign in again. */
    val needsReauth: Boolean
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: email ?: userId
}

/** A channel the active account owns or holds a role on (`GET /api/channels/access`). */
data class StudioChannel(
    val id: String,
    val name: String,
    val nameLocalized: Map<String, String> = emptyMap(),
    val handle: String? = null,
    val avatarUrl: String? = null,
    val role: ChannelRole = ChannelRole.OWNER,
    val capabilities: Set<String> = emptySet(),
    val isMain: Boolean = false,
    val isSuspended: Boolean = false
) {
    fun localizedName(language: String): String = nameLocalized[language]?.takeIf { it.isNotBlank() } ?: name
    fun can(capability: String): Boolean = role == ChannelRole.OWNER || capability in capabilities

    companion object {
        const val VIDEO_UPLOAD = "video.upload"
        const val VIDEO_EDIT = "video.edit"
        const val VIDEO_DELETE = "video.delete"
        const val ANALYTICS_VIEW = "analytics.view"
    }
}

enum class ChannelRole(val apiValue: String) {
    OWNER("owner"),
    ADMIN("admin"),
    EDITOR("editor"),
    MODERATOR("moderator");

    companion object {
        fun from(value: String?): ChannelRole = entries.firstOrNull { it.apiValue == value } ?: OWNER
    }
}

/** Studio content tabs. [DELETED] lists soft-deleted videos that can still be restored. */
enum class ContentStatus(val apiValue: String) {
    PUBLISHED("published"),
    SCHEDULED("scheduled"),
    DRAFTS("drafts"),
    PROCESSING("processing"),
    FAILED("failed"),
    DELETED("deleted");

    companion object {
        fun from(value: String?): ContentStatus? = entries.firstOrNull { it.apiValue == value }
    }
}

enum class VisibilityFilter(val apiValue: String) { ALL("all"), PUBLIC("public"), PRIVATE("private") }

enum class ContentSort(val apiValue: String) { NEWEST("newest"), OLDEST("oldest"), VIEWS("views"), TITLE("title") }

data class ContentQuery(
    val channelId: String,
    val status: ContentStatus = ContentStatus.PUBLISHED,
    val text: String = "",
    val visibility: VisibilityFilter = VisibilityFilter.ALL,
    val sort: ContentSort = ContentSort.NEWEST,
    val cursor: String? = null,
    val limit: Int = 30
)

/** Video visibility as the API models it: `isPublic` (+ `isDraft` once drafts ship). */
enum class Visibility { PUBLIC, PRIVATE }

data class StudioVideo(
    val id: String,
    val title: String,
    val description: String = "",
    val thumbnailUrl: String? = null,
    val isPublic: Boolean = true,
    val isDraft: Boolean = false,
    val status: ContentStatus? = null,
    val views: Long = 0,
    val likes: Long = 0,
    val comments: Long = 0,
    val durationSeconds: Double? = null,
    val category: String? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val titleLocalized: Map<String, String> = emptyMap(),
    val descriptionLocalized: Map<String, String> = emptyMap(),
    /** ISO 8601. */
    val createdAt: String? = null,
    /** ISO 8601, for deleted videos. */
    val deletedAt: String? = null,
    /** ISO 8601: restore is possible until then (30 days after delete). */
    val restorableUntil: String? = null,
    val uploadFailureReason: String? = null
)

data class ContentPage(val items: List<StudioVideo>, val cursor: String?)

data class ContentCounts(
    val published: Int = 0,
    val scheduled: Int = 0,
    val drafts: Int = 0,
    val processing: Int = 0,
    val failed: Int = 0
)

/** A single-video edit (`PUT /api/videos/{id}`): only non-null fields change. */
data class VideoEdit(
    val title: String? = null,
    val description: String? = null,
    val titleLocalized: Map<String, String>? = null,
    val descriptionLocalized: Map<String, String>? = null,
    val isPublic: Boolean? = null,
    /** A category value, or [CLEAR] to remove it. */
    val category: String? = null,
    val language: String? = null,
    val tags: List<String>? = null
) {
    companion object {
        const val CLEAR = "\u0000clear"
    }
}

/** `POST /api/videos/bulk-update` changes (≤ [MAX_BULK] videos). */
data class BulkChanges(
    val isPublic: Boolean? = null,
    /** A category value, or [VideoEdit.CLEAR]. */
    val category: String? = null,
    val addTags: List<String> = emptyList(),
    val removeTags: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = isPublic == null && category == null && addTags.isEmpty() && removeTags.isEmpty()

    companion object {
        const val MAX_BULK = 50
        const val MAX_TAGS = 30
        const val MAX_TAG_LENGTH = 50
    }
}

data class BulkItemResult(val videoId: String, val success: Boolean, val error: String? = null)

data class BulkResult(val items: List<BulkItemResult>) {
    val succeeded: Int get() = items.count { it.success }
    val failed: Int get() = items.count { !it.success }
    val failedIds: List<String> get() = items.filterNot { it.success }.map { it.videoId }
}

data class StudioPlaylist(val id: String, val title: String, val isPublic: Boolean)

data class CreatorAccess(val canUpload: Boolean, val canCreateChannel: Boolean, val tierName: String?)

/** Analytics window. [days] null = all time. */
enum class AnalyticsRange(val days: Int?) { LAST_7(7), LAST_28(28), LAST_90(90), LAST_365(365) }

data class DailyMetric(
    val date: String,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val subscribers: Long,
    val watchTimeSeconds: Long
)

data class VideoStat(
    val videoId: String,
    val title: String,
    val thumbnailUrl: String?,
    val views: Long,
    val likes: Long,
    val comments: Long,
    val watchTimeSeconds: Long,
    val avgViewDurationSeconds: Double
)

data class ChannelAnalytics(
    val totalViews: Long,
    val totalVideos: Long,
    val totalSubscribers: Long,
    val totalLikes: Long,
    val totalComments: Long,
    val avgViewsPerVideo: Double,
    val engagementRate: Double,
    val topVideos: List<VideoStat>,
    val daily: List<DailyMetric>
) {
    val watchTimeSeconds: Long get() = daily.sumOf { it.watchTimeSeconds }
}

/** Search-as-you-type suggestion from aun-search (`GET /api/search/suggestions`). */
data class StudioSuggestion(val text: String, val videoId: String?, val subtitle: String?)

/** The values the API accepts. Labels live in `:design` resources (`aun_category_*`). */
object VideoCategories {
    val all: List<String> = listOf(
        "quran_recitation", "quran_tafsir", "hadith_studies", "islamic_history", "fiqh", "aqeedah", "dawah",
        "khutbah", "islamic_lectures", "islamic_nasheed", "islamic_kids", "islamic_news", "hajj_umrah",
        "ramadan", "islamic_lifestyle"
    )
}

/** `language` values `PUT /api/videos/{id}` accepts. */
object VideoLanguages {
    val all: List<String> = listOf("ar", "en", "ur", "fr")
}
