package net.alummahnow.core.bridge.catalog

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.internal.ListEnvelope
import net.alummahnow.core.bridge.internal.LiveChatPageEnvelope
import net.alummahnow.core.bridge.internal.LiveChatSingleEnvelope
import net.alummahnow.core.bridge.internal.LiveReminderEnvelope
import net.alummahnow.core.bridge.internal.LiveStreamDto
import net.alummahnow.core.bridge.internal.LiveStreamEnvelope
import net.alummahnow.core.bridge.internal.LiveStreamStateDto
import net.alummahnow.core.bridge.internal.LiveStreamStateEnvelope
import net.alummahnow.core.bridge.internal.LiveStreamsEnvelope
import net.alummahnow.core.bridge.internal.VideoDto
import net.alummahnow.core.bridge.internal.toDetail
import net.alummahnow.core.bridge.internal.toDomain
import net.alummahnow.core.bridge.internal.toSummary
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatPage
import net.alummahnow.core.bridge.model.LiveEventType
import net.alummahnow.core.bridge.model.LiveModerationAction
import net.alummahnow.core.bridge.model.LiveReminderStatus
import net.alummahnow.core.bridge.model.LiveReportReason
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamState
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

    override suspend fun liveStreams(
        status: LiveStreamStatus?,
        eventType: LiveEventType?,
        limit: Int
    ): List<LiveStream> {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addQueryParameter("limit", "$limit")
            .apply {
                if (status != null) addQueryParameter("status", status.apiValue)
                if (eventType != null && eventType != LiveEventType.NONE) addQueryParameter("eventType", eventType.apiValue)
            }
            .build()
        return get<LiveStreamsEnvelope>(url, what = "live streams").data.map { it.toDomain() }
    }

    override suspend fun liveStream(id: String): LiveStream {
        val url = base.newBuilder().addPathSegments("api/live/streams").addPathSegment(id).build()
        val body = getRaw(url, what = "live $id")
        val dto = runCatching { json.decodeFromString<LiveStreamEnvelope>(body).data }.getOrNull()
            ?: decode<LiveStreamDto>(body, url)
        return dto.toDomain()
    }

    override suspend fun liveStreamState(id: String): LiveStreamState {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(id)
            .addPathSegment("state")
            .build()
        val body = getRaw(url, what = "live state $id")
        val dto = runCatching { json.decodeFromString<LiveStreamStateEnvelope>(body).data }.getOrNull()
            ?: decode<LiveStreamStateDto>(body, url)
        return dto.toDomain(fallbackId = id)
    }

    override suspend fun sendLiveHeartbeat(id: String, viewerId: String): LiveStreamState {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(id)
            .addPathSegment("heartbeat")
            .build()
        val payload = buildJsonObject { put("viewerId", viewerId) }.toString()
        val body = sendRaw("POST", url, payload, what = "live heartbeat $id")
        val dto = runCatching { json.decodeFromString<LiveStreamStateEnvelope>(body).data }.getOrNull()
            ?: decode<LiveStreamStateDto>(body, url)
        return dto.toDomain(fallbackId = id)
    }

    override suspend fun liveChatMessages(
        streamId: String,
        afterId: String?,
        maxOffsetMs: Long?,
        limit: Int
    ): LiveChatPage {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("chat")
            .addQueryParameter("limit", "$limit")
            .apply {
                if (afterId != null) addQueryParameter("afterId", afterId)
                if (maxOffsetMs != null) addQueryParameter("maxOffsetMs", "$maxOffsetMs")
            }
            .build()
        return get<LiveChatPageEnvelope>(url, what = "live chat $streamId").toDomain()
    }

    override suspend fun postLiveChatMessage(streamId: String, text: String, offsetMs: Long?): LiveChatMessage {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("chat")
            .build()
        val payload = buildJsonObject {
            put("text", text)
            if (offsetMs != null) put("offsetMs", offsetMs)
        }.toString()
        val body = sendRaw("POST", url, payload, what = "live chat $streamId")
        return decode<LiveChatSingleEnvelope>(body, url).data.toDomain()
    }

    override suspend fun moderateLiveChatMessage(
        streamId: String,
        messageId: String,
        action: LiveModerationAction
    ): LiveChatMessage {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("chat")
            .addPathSegment(messageId)
            .addPathSegment("moderate")
            .build()
        val payload = buildJsonObject { put("action", action.apiValue) }.toString()
        val body = sendRaw("POST", url, payload, what = "moderate chat $messageId")
        return decode<LiveChatSingleEnvelope>(body, url).data.toDomain()
    }

    override suspend fun reportLiveChatMessage(
        streamId: String,
        messageId: String,
        reason: LiveReportReason,
        details: String?
    ) {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("chat")
            .addPathSegment(messageId)
            .addPathSegment("report")
            .build()
        val payload = buildJsonObject {
            put("reason", reason.apiValue)
            if (!details.isNullOrBlank()) put("details", details)
        }.toString()
        sendRaw("POST", url, payload, what = "report chat $messageId")
    }

    override suspend fun liveReminderStatus(streamId: String): LiveReminderStatus {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("reminders")
            .build()
        return get<LiveReminderEnvelope>(url, what = "live reminder $streamId").toDomain()
    }

    override suspend fun setLiveReminder(streamId: String, remind: Boolean): LiveReminderStatus {
        val url = base.newBuilder()
            .addPathSegments("api/live/streams")
            .addPathSegment(streamId)
            .addPathSegment("reminders")
            .build()
        val method = if (remind) "POST" else "DELETE"
        val body = sendRaw(method, url, if (remind) "{}" else null, what = "live reminder $streamId")
        return decode<LiveReminderEnvelope>(body, url).toDomain()
    }

    private suspend fun list(path: String, vararg query: Pair<String, String>): List<VideoSummary> {
        val url = base.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        return get<ListEnvelope>(url, what = path).data.map { it.toSummary() }
    }

    private suspend inline fun <reified T> get(url: HttpUrl, what: String): T {
        val body = getRaw(url, what)
        return decode(body, url)
    }

    private suspend fun getRaw(url: HttpUrl, what: String): String =
        sendRaw("GET", url, jsonBody = null, what = what)

    private suspend fun sendRaw(method: String, url: HttpUrl, jsonBody: String?, what: String): String =
        withContext(Dispatchers.IO) {
            val reqBody = jsonBody?.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .method(method, reqBody)
                .build()
            try {
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
        }

    private inline fun <reified T> decode(body: String, url: HttpUrl): T = try {
        json.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        AunLog.e("catalog", "decode failed", e, "path" to url.encodedPath)
        throw CoreError.Decode(e)
    } catch (e: IllegalArgumentException) {
        throw CoreError.Decode(e)
    }
}
