package net.alummahnow.core.bridge.studio.core

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.ApiClient
import net.alummahnow.core.AunException
import net.alummahnow.core.PathCall
import net.alummahnow.core.SuggestionKind
import net.alummahnow.core.SuggestionQuery
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.BulkResult
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentPage
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioSuggestion
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.core.bridge.studio.VisibilityFilter

/**
 * [StudioRepository] over aun-core's `ApiClient` (auth, refresh on 401, retries). Studio routes that
 * are not in Core's pinned OpenAPI document go through `request_path`.
 *
 * [client] returns the client bound to the active account (a new one after an account switch).
 *
 * Several calls catch a 404 on purpose: it means the server predates a Studio route (for example
 * `/content` from AlUmmahNow#483), and the call falls back to an older route or reports
 * [StudioError.NotSupported]. That is why swallowed exceptions are allowed here.
 */
@Suppress("SwallowedException")
class CoreStudioRepository(private val client: () -> ApiClient) : StudioRepository {

    private suspend fun call(
        method: String,
        path: String,
        params: Map<String, String> = emptyMap(),
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null,
        channelId: String? = null,
        idempotent: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        coreCall {
            client().requestPath(
                PathCall(
                    method = method,
                    pathTemplate = path,
                    pathParams = params,
                    query = query.filterValues { it.isNotEmpty() },
                    headers = channelId?.let { mapOf("channelId" to it) }.orEmpty(),
                    bodyJson = body?.toString(),
                    idempotencyKey = if (idempotent) UUID.randomUUID().toString() else null,
                    authRequired = true
                )
            ).body
        }
    }

    /** Like [call], but a 404 for the route itself means the server predates this feature. */
    private suspend fun callNew(feature: String, block: suspend () -> String): String = try {
        block()
    } catch (e: CoreError.NotFound) {
        AunLog.w("studio", "route missing", "feature" to feature)
        throw StudioError.NotSupported(feature)
    }

    override suspend fun channels(): List<StudioChannel> = try {
        StudioMappers.channels(call("GET", "/api/channels/access"))
    } catch (e: CoreError.NotFound) {
        StudioMappers.channels(call("GET", "/api/channels", query = mapOf("scope" to "mine", "limit" to "100")))
    }

    override suspend fun creatorAccess(): CreatorAccess {
        val r = J.parse(call("GET", "/api/me/creator")).unwrap().obj() ?: JsonObject(emptyMap())
        return CreatorAccess(r.b("canUpload") == true, r.b("canCreateChannel") == true, r.o("tier")?.s("name"))
    }

    override suspend fun content(query: ContentQuery): ContentPage {
        if (query.status == ContentStatus.DELETED) return deleted(query)
        return try {
            val body = call(
                "GET",
                "/api/channels/{id}/content",
                mapOf("id" to query.channelId),
                buildMap {
                    put("status", query.status.apiValue)
                    put("q", query.text.trim())
                    if (query.visibility != VisibilityFilter.ALL) put("visibility", query.visibility.apiValue)
                    put("sort", query.sort.apiValue)
                    put("limit", query.limit.toString())
                    query.cursor?.let { put("cursor", it) }
                }
            )
            val r = J.parse(body).unwrap().obj() ?: return ContentPage(emptyList(), null)
            ContentPage(r.a("items").mapNotNull { it.obj()?.let(StudioMappers::video) }, r.s("cursor")?.ifEmpty { null })
        } catch (e: CoreError.NotFound) {
            ownerSearch(query)
        }
    }

    /** Fallback while `/content` (AlUmmahNow#483) is not deployed: owner search + client-side status. */
    private suspend fun ownerSearch(query: ContentQuery): ContentPage {
        val body = call(
            "GET",
            "/api/videos/search",
            query = buildMap {
                put("isOwner", "true")
                put("channelIds", query.channelId)
                put("q", query.text.trim())
                put("limit", query.limit.toString())
                put("filter", query.visibility.apiValue)
                put(
                    "sort",
                    when (query.sort) {
                        net.alummahnow.core.bridge.studio.ContentSort.VIEWS -> "views"
                        net.alummahnow.core.bridge.studio.ContentSort.TITLE -> "title"
                        else -> "date"
                    }
                )
                if (query.status == ContentStatus.SCHEDULED) put("scheduleFilter", "scheduled")
                query.cursor?.let { put("cursor", it) }
            },
            channelId = query.channelId
        )
        val root = J.parse(body).obj() ?: return ContentPage(emptyList(), null)
        val items = root.a("data").mapNotNull { it.obj()?.let(StudioMappers::video) }
            .filter { query.status == ContentStatus.SCHEDULED || it.status == query.status }
        return ContentPage(items, root.s("cursor")?.ifEmpty { null })
    }

    private suspend fun deleted(query: ContentQuery): ContentPage {
        val body = callNew("deleted-videos") {
            call(
                "GET",
                "/api/channels/{id}/deleted-videos",
                mapOf("id" to query.channelId),
                buildMap {
                    put("limit", query.limit.toString())
                    query.cursor?.let { put("cursor", it) }
                }
            )
        }
        val r = J.parse(body).unwrap().obj() ?: return ContentPage(emptyList(), null)
        val text = query.text.trim()
        val items = r.a("items").mapNotNull { it.obj()?.let(StudioMappers::video) }
            .map { it.copy(status = ContentStatus.DELETED) }
            .filter { text.isEmpty() || it.title.contains(text, ignoreCase = true) }
        return ContentPage(items, r.s("cursor")?.ifEmpty { null })
    }

    override suspend fun counts(channelId: String): ContentCounts? = try {
        val r = J.parse(call("GET", "/api/channels/{id}/content/summary", mapOf("id" to channelId))).unwrap().obj()?.o("counts")
        r?.let {
            ContentCounts(
                it.l("published").toInt(),
                it.l("scheduled").toInt(),
                it.l("drafts").toInt(),
                it.l("processing").toInt(),
                it.l("failed").toInt()
            )
        }
    } catch (e: CoreError.NotFound) {
        null
    }

    override suspend fun suggestions(channelId: String, text: String): List<StudioSuggestion> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList()
        try {
            client().searchSuggestions(
                SuggestionQuery(text = text, limit = SUGGESTION_LIMIT.toUInt(), kinds = listOf(SuggestionKind.VIDEO), channelId = channelId)
            ).map { StudioSuggestion(it.text, it.id, it.subtitle) }
        } catch (e: AunException.RateLimited) {
            emptyList()
        } catch (e: AunException) {
            throw mapCoreError(e)
        }
    }

    override suspend fun video(id: String): StudioVideo {
        val body = call("GET", "/api/videos/{id}", mapOf("id" to id))
        return J.parse(body).unwrap().obj()?.let(StudioMappers::video) ?: throw CoreError.NotFound(id)
    }

    override suspend fun update(id: String, channelId: String, edit: VideoEdit): StudioVideo {
        val body = buildJsonObject {
            edit.title?.let { put("title", it) }
            edit.description?.let { put("description", it) }
            edit.titleLocalized?.let { m -> put("titleLocalized", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
            edit.descriptionLocalized?.let { m -> put("descriptionLocalized", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
            edit.isPublic?.let { put("isPublic", it) }
            edit.category?.let { if (it == VideoEdit.CLEAR) put("category", JsonNull) else put("category", it) }
            edit.language?.let { put("language", it) }
            edit.tags?.let { t -> put("tags", JsonArray(t.map(::JsonPrimitive))) }
        }
        val res = call("PUT", "/api/videos/{id}", mapOf("id" to id), body = body, channelId = channelId)
        return J.parse(res).unwrap().obj()?.let(StudioMappers::video) ?: video(id)
    }

    override suspend fun bulkUpdate(ids: List<String>, changes: BulkChanges): BulkResult {
        require(ids.size <= BulkChanges.MAX_BULK) { "at most ${BulkChanges.MAX_BULK} videos" }
        val body = buildJsonObject {
            put("videoIds", JsonArray(ids.map(::JsonPrimitive)))
            put(
                "changes",
                buildJsonObject {
                    changes.isPublic?.let { put("isPublic", it) }
                    changes.category?.let { if (it == VideoEdit.CLEAR) put("category", JsonNull) else put("category", it) }
                    if (changes.addTags.isNotEmpty()) put("addTags", JsonArray(changes.addTags.map(::JsonPrimitive)))
                    if (changes.removeTags.isNotEmpty()) put("removeTags", JsonArray(changes.removeTags.map(::JsonPrimitive)))
                }
            )
        }
        return StudioMappers.bulk(call("POST", "/api/videos/bulk-update", body = body, idempotent = true), ids)
    }

    @Volatile private var softDelete: Boolean? = null

    override suspend fun softDeleteSupported(channelId: String): Boolean = softDelete ?: try {
        call("GET", "/api/channels/{id}/deleted-videos", mapOf("id" to channelId), mapOf("limit" to "1"))
        true
    } catch (e: CoreError.NotFound) {
        false
    }.also { softDelete = it }

    override suspend fun delete(ids: List<String>, channelId: String): BulkResult {
        val body = buildJsonObject { put("videoIds", JsonArray(ids.map(::JsonPrimitive))) }
        return StudioMappers.bulk(call("POST", "/api/videos/bulk-delete", body = body, channelId = channelId, idempotent = true), ids)
    }

    override suspend fun restore(ids: List<String>, channelId: String): BulkResult {
        val body = buildJsonObject { put("videoIds", JsonArray(ids.map(::JsonPrimitive))) }
        val res = callNew("restore") { call("POST", "/api/videos/bulk-restore", body = body, channelId = channelId, idempotent = true) }
        return StudioMappers.bulk(res, ids)
    }

    override suspend fun deletePermanently(id: String, channelId: String) {
        callNew("permanent-delete") { call("DELETE", "/api/videos/{id}/permanent", mapOf("id" to id), channelId = channelId) }
    }

    override suspend fun playlists(channelId: String): List<StudioPlaylist> {
        val body = call("GET", "/api/playlists", query = mapOf("channelId" to channelId, "limit" to "100"))
        val root = J.parse(body)
        val list = root.obj()?.a("data")?.takeIf { it.isNotEmpty() } ?: root.unwrap().arr() ?: JsonArray(emptyList())
        return list.mapNotNull { it.obj()?.let(StudioMappers::playlist) }
    }

    override suspend fun schedule(videoId: String, channelId: String, publishAtIso: String, timezone: String) {
        val body = buildJsonObject {
            put("scheduledPublishDate", publishAtIso)
            put("timezone", timezone)
            put("notifyOnPublish", true)
        }
        call("POST", "/api/videos/{id}/schedule", mapOf("id" to videoId), body = body, channelId = channelId, idempotent = true)
    }

    override suspend fun addToPlaylists(videoId: String, channelId: String, playlistIds: List<String>, orderKey: String) {
        if (playlistIds.isEmpty()) return
        val body = buildJsonObject {
            put("videoId", videoId)
            put("playlistIds", JsonArray(playlistIds.take(MAX_UPLOAD_PLAYLISTS).map(::JsonPrimitive)))
            put("orderKey", orderKey)
        }
        call("POST", "/api/playlists/upload-adds", body = body, channelId = channelId, idempotent = true)
    }

    override suspend fun analytics(channelId: String, range: AnalyticsRange): ChannelAnalytics {
        val now = Instant.now()
        val from = range.days?.let { now.minus(it.toLong(), ChronoUnit.DAYS) }
        val body = call(
            "GET",
            "/api/analytics/channels/{channelId}",
            mapOf("channelId" to channelId),
            buildMap {
                from?.let { put("dateFrom", it.toString()) }
                put("dateTo", now.toString())
            }
        )
        return StudioMappers.analytics(J.parse(body).unwrap().obj() ?: JsonObject(emptyMap()))
    }

    private companion object {
        const val SUGGESTION_LIMIT = 6
        const val MAX_UPLOAD_PLAYLISTS = 20
    }
}
