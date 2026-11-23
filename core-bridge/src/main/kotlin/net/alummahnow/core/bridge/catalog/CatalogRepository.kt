package net.alummahnow.core.bridge.catalog

import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.core.bridge.studio.UserFeatures

/**
 * Public catalogue reads. Backed by `aun-api` in the Rust core once the UniFFI AAR ships; until then by
 * [TemporaryCatalogRepository] (OkHttp against the public API).
 */
interface CatalogRepository {
    /** Home rails in display order. Empty rails are dropped. */
    suspend fun homeRails(): List<Rail>

    suspend fun video(id: String): VideoDetail

    /** Audio-only rendition URL (`GET /api/v1/videos/:id/audio`) for Listen mode and Android Auto. */
    suspend fun audioUrl(videoId: String): String? = null

    /** Live viewer feature flags (`GET /api/me/features`); fails closed to [UserFeatures.NONE]. */
    suspend fun viewerFeatures(bearerToken: String? = null): UserFeatures = UserFeatures.NONE

    /** Videos to show next to [video] (same category first, then trending), excluding [video] itself. */
    suspend fun related(video: VideoDetail, limit: Int = 12): List<VideoSummary>

    suspend fun search(query: String, limit: Int = 20): List<VideoSummary>
}
