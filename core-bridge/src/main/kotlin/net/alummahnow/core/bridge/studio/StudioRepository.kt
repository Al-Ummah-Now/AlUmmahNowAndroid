package net.alummahnow.core.bridge.studio

/**
 * Creator Studio data for the active account (api-videos through aun-core). Every call throws
 * [net.alummahnow.core.bridge.CoreError] (or [StudioError]) on failure.
 */
interface StudioRepository {
    suspend fun channels(): List<StudioChannel>
    suspend fun creatorAccess(): CreatorAccess
    suspend fun content(query: ContentQuery): ContentPage
    suspend fun counts(channelId: String): ContentCounts?
    suspend fun suggestions(channelId: String, text: String): List<StudioSuggestion>
    suspend fun video(id: String): StudioVideo
    suspend fun update(id: String, channelId: String, edit: VideoEdit): StudioVideo
    suspend fun bulkUpdate(ids: List<String>, changes: BulkChanges): BulkResult

    /** True when deletes are restorable for 30 days on this server (soft delete deployed). */
    suspend fun softDeleteSupported(channelId: String): Boolean
    suspend fun delete(ids: List<String>, channelId: String): BulkResult
    suspend fun restore(ids: List<String>, channelId: String): BulkResult
    suspend fun deletePermanently(id: String, channelId: String)
    suspend fun playlists(channelId: String): List<StudioPlaylist>
    suspend fun schedule(videoId: String, channelId: String, publishAtIso: String, timezone: String)
    suspend fun addToPlaylists(videoId: String, channelId: String, playlistIds: List<String>, orderKey: String)
    suspend fun analytics(channelId: String, range: AnalyticsRange): ChannelAnalytics
}

/** Studio-specific failures the UI explains (beyond the generic [net.alummahnow.core.bridge.CoreError]). */
sealed class StudioError(message: String) : Exception(message) {
    /** The server needs a newer feature (e.g. soft delete) that is not deployed here yet. */
    class NotSupported(what: String) : StudioError("not supported: $what")

    /** 403 for this channel or video. */
    class Forbidden : StudioError("forbidden")

    /** The plan does not allow it (HTTP 402 `upgrade_required`). */
    class UpgradeRequired(val entitlement: String?) : StudioError("upgrade required")

    /** The restore window (30 days) has passed (HTTP 410). */
    class RestoreExpired : StudioError("restore expired")

    /** Validation error with the server's message. */
    class Invalid(val detail: String) : StudioError(detail)
}
