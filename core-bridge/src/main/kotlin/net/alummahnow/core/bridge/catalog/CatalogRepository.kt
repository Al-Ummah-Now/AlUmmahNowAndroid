package net.alummahnow.core.bridge.catalog

import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary

/**
 * Public catalogue reads. Backed by `aun-api` in the Rust core once the UniFFI AAR ships; until then by
 * [TemporaryCatalogRepository] (OkHttp against the public API).
 */
interface CatalogRepository {
    /** Home rails in display order. Empty rails are dropped. */
    suspend fun homeRails(): List<Rail>

    suspend fun video(id: String): VideoDetail

    /** Videos to show next to [video] (same category first, then trending), excluding [video] itself. */
    suspend fun related(video: VideoDetail, limit: Int = 12): List<VideoSummary>

    suspend fun search(query: String, limit: Int = 20): List<VideoSummary>
}
