package net.alummahnow.core.bridge.catalog

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.internal.ListEnvelope
import net.alummahnow.core.bridge.internal.VideoDto
import net.alummahnow.core.bridge.internal.toDetail
import net.alummahnow.core.bridge.internal.toSummary
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Stand-in for `aun-api` until the Rust core AAR is published. Reads only public endpoints.
 * Replace by binding [CatalogRepository] to the UniFFI implementation in each app's container.
 */
class TemporaryCatalogRepository(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : CatalogRepository {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun homeRails(): List<Rail> = coroutineScope {
        val sections = listOf(
            RailKind.TRENDING to "trending",
            RailKind.RECOMMENDED to "recommended",
            RailKind.POPULAR to "popular",
            RailKind.RECENT to "recent"
        )
        val results = sections.map { (kind, path) ->
            async { kind to runCatching { list("api/videos/sections/$path", "limit" to "20") } }
        }.awaitAll()
        // A single failing rail must not blank the whole home; but if every rail fails, surface the error.
        if (results.all { it.second.isFailure }) throw results.first().second.exceptionOrNull()!!
        results.mapNotNull { (kind, result) ->
            result.onFailure { AunLog.w("catalog", "rail failed", "kind" to kind, "error" to it.message) }
            result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { Rail(kind, it) }
        }
    }

    override suspend fun video(id: String): VideoDetail {
        val dto = get<VideoDto>(base.newBuilder().addPathSegments("api/videos").addPathSegment(id).build(), what = "video $id")
        return dto.toDetail()
    }

    override suspend fun related(video: VideoDetail, limit: Int): List<VideoSummary> {
        val category = video.summary.category
        val sameCategory = if (category != null) {
            runCatching { list("api/videos/sections/by-category/$category", "limit" to "${limit + 1}") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val trending = if (sameCategory.size <= limit) {
            runCatching { list("api/videos/sections/trending", "limit" to "${limit + 1}") }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return (sameCategory + trending).distinctBy { it.id }.filterNot { it.id == video.id }.take(limit)
    }

    override suspend fun search(query: String, limit: Int): List<VideoSummary> =
        list("api/videos/search", "q" to query, "limit" to "$limit")

    private suspend fun list(path: String, vararg query: Pair<String, String>): List<VideoSummary> {
        val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return get<ListEnvelope>(url, what = path).data.map { it.toSummary() }
    }

    private suspend inline fun <reified T> get(url: HttpUrl, what: String): T = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val body = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw CoreError.NotFound(what)
                    !response.isSuccessful -> throw CoreError.Http(response.code)
                    else -> response.body.string()
                }
            }
        } catch (e: IOException) {
            AunLog.w("catalog", "network error", "path" to url.encodedPath, "error" to e.message)
            throw CoreError.Network(e)
        }
        try {
            json.decodeFromString<T>(body)
        } catch (e: SerializationException) {
            AunLog.e("catalog", "decode failed", e, "path" to url.encodedPath)
            throw CoreError.Decode(e)
        } catch (e: IllegalArgumentException) {
            throw CoreError.Decode(e)
        }
    }
}
