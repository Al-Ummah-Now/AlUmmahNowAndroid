package net.alummahnow.core.bridge.studio

/**
 * Creator Studio data for the active account (api-videos through aun-core). Every call throws
 * [net.alummahnow.core.bridge.CoreError] (or [StudioError]) on failure.
 */
interface StudioRepository {
    suspend fun channels(): List<StudioChannel>
    suspend fun creatorAccess(): CreatorAccess

    /** What this person sees, evaluated against [channelId]'s owner tier when given (`GET /api/me/features`). */
    suspend fun features(channelId: String?): UserFeatures
    suspend fun content(query: ContentQuery): ContentPage
    suspend fun counts(channelId: String): ContentCounts?

    /**
     * Comments waiting for review on this channel. Throws [StudioError.Forbidden] without `comment.moderate` and
     * [StudioError.NotSupported] where the comments index is not deployed; both mean "no such section".
     */
    suspend fun commentsSummary(channelId: String): CommentsSummary
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

/**
 * True for the failures that mean "this feature is not there for this person": the UI hides the entry point and
 * shows no text ([StudioError.FeatureOff], [StudioError.NotSupported], [StudioError.UpgradeRequired]).
 */
fun Throwable.hidesFeature(): Boolean =
    this is StudioError.FeatureOff || this is StudioError.NotSupported || this is StudioError.UpgradeRequired

/** Studio-specific failures the UI explains (beyond the generic [net.alummahnow.core.bridge.CoreError]). */
sealed class StudioError(message: String) : Exception(message) {
    /** The server predates this feature (its route answers 404). The UI hides the entry point; it never says so. */
    class NotSupported(what: String) : StudioError("not supported: $what")

    /** The feature is switched off for this person (HTTP 404 `feature_off`). The UI hides the entry point, no text. */
    class FeatureOff : StudioError("feature off")

    /** 403 for this channel or video. */
    class Forbidden : StudioError("forbidden")

    /** The plan does not allow it (HTTP 402 `upgrade_required`). The UI hides the entry point, no text. */
    class UpgradeRequired(val entitlement: String?) : StudioError("upgrade required")

    /** The restore window (30 days) has passed (HTTP 410). */
    class RestoreExpired : StudioError("restore expired")

    /** Validation error with the server's message. */
    class Invalid(val detail: String) : StudioError(detail)
}
