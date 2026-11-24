package net.alummahnow.core.bridge.live

import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The public live endpoints of api-videos, until the Rust core has them. */
class HttpLiveRepository(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis
) : LiveRepository {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun features(): LiveFeatures = try {
        val body = call(url("api/me/features"))
        val flags = json.parseToJsonElement(body).jsonObject["features"] as? JsonObject
        fun on(key: String) = (flags?.get(key) as? JsonPrimitive)?.booleanOrNull == true
        LiveFeatures(on("liveChat"), on("livePremieres"), on("liveReminders"), on("liveEvents"))
    } catch (e: CoreError) {
        AunLog.w("live", "features not read", "error" to e.message)
        LiveFeatures()
    } catch (e: IllegalArgumentException) {
        // Not the JSON it should be
        AunLog.w("live", "features not read", "error" to e.message)
        LiveFeatures()
    }

    override suspend fun streams(status: LiveStatus, event: LiveEventType?): List<LiveStream> {
        val url = url("api/live-streams") {
            addQueryParameter("status", status.name.lowercase())
            addQueryParameter("limit", LIST_LIMIT.toString())
            if (event != null) addQueryParameter("eventType", event.name.lowercase())
        }
        // One stream this app cannot read (a newer status) never takes the list with it
        return get<ListDto>(url).data.mapNotNull { item ->
            runCatching { json.decodeFromJsonElement<StreamDto>(item).toStream() }.getOrNull()
        }
    }

    override suspend fun stream(id: String): LiveStream =
        get<StreamBody>(url("api/live-streams", id)).stream.toStream() ?: throw CoreError.NotFound("live stream $id")

    override suspend fun state(id: String): LiveState {
        val sentAt = nowMs()
        val dto = get<StateDto>(url("api/live-streams", id, "state"))
        // The answer's `serverNow` belongs to the middle of the request
        val offset = dto.serverNow?.let { it.toLong() - (sentAt + nowMs()) / 2 } ?: 0
        return dto.toState(offset) ?: throw CoreError.NotFound("live stream $id")
    }

    override suspend fun chat(id: String): LiveChatPage = get<ChatDto>(url("api/live-streams", id, "chat")).toPage()

    override suspend fun chatReplay(id: String, fromMs: Long, toMs: Long): LiveChatPage = get<ChatDto>(
        url("api/live-streams", id, "chat") {
            addQueryParameter("fromMs", fromMs.toString())
            addQueryParameter("toMs", toMs.toString())
        }
    ).toPage()

    override suspend fun beat(id: String, viewerId: String, leaving: Boolean): Long? {
        val body = buildJsonObject {
            put("viewerId", viewerId)
            put("leaving", leaving)
        }.toString().toRequestBody("text/plain".toMediaType())
        return decode<Viewers>(call(url("api/live-streams", id, "viewers"), body)).viewers
    }

    private fun url(path: String, vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().addPathSegments(path).apply { segments.forEach(::addPathSegment) }.apply(query).build()

    private suspend inline fun <reified T> get(url: HttpUrl): T = decode(call(url))

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<Envelope<T>>(body).result
    } catch (e: SerializationException) {
        AunLog.e("live", "decode failed", e)
        throw CoreError.Decode(e)
    } catch (e: IllegalArgumentException) {
        throw CoreError.Decode(e)
    }

    private suspend fun call(url: HttpUrl, body: okhttp3.RequestBody? = null): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").apply { if (body != null) post(body) }.build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> throw CoreError.NotFound(url.encodedPath)
                    !response.isSuccessful -> throw CoreError.Http(response.code, response.retryAfterMs())
                    else -> response.body.string()
                }
            }
        } catch (e: IOException) {
            AunLog.w("live", "network error", "path" to url.encodedPath, "error" to e.message)
            throw CoreError.Network(e)
        } catch (e: NumberFormatException) {
            // OkHttp reads a 503's Retry-After as an Int
            AunLog.w("live", "unreadable Retry-After", "path" to url.encodedPath, "error" to e.message)
            throw CoreError.Http(HTTP_UNAVAILABLE)
        }
    }

    private companion object {
        const val LIST_LIMIT = 50
        const val HTTP_TOO_MANY = 429
        const val HTTP_UNAVAILABLE = 503
        const val MS = 1_000L

        /** More seconds than any answer means; keeps the arithmetic whole. */
        const val MAX_SECONDS = 86_400L

        fun Response.retryAfterMs(): Long? {
            if (code != HTTP_TOO_MANY && code != HTTP_UNAVAILABLE) return null
            return header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { minOf(it, MAX_SECONDS) * MS }
        }
    }
}

@Serializable
private data class Envelope<T>(val result: T)

@Serializable
private data class ListDto(val data: List<JsonElement> = emptyList())

@Serializable
private data class StreamBody(val stream: StreamDto)

@Serializable
private data class Viewers(val viewers: Long? = null)

@Serializable
private data class StreamDto(
    val id: String,
    val status: String,
    val title: String = "",
    val description: String? = null,
    val kind: String? = null,
    val eventType: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val scheduledStartTime: String? = null,
    val currentViewers: Long? = null
) {
    fun toStream(): LiveStream? = LiveStream(
        id = id,
        title = title,
        status = enumOf<LiveStatus>(status) ?: return null,
        description = description,
        kind = enumOf<LiveKind>(kind) ?: LiveKind.LIVE,
        eventType = enumOf<LiveEventType>(eventType),
        channelName = channelName,
        thumbnailUrl = thumbnailUrl?.ifBlank { null },
        scheduledStart = instant(scheduledStartTime),
        viewers = currentViewers
    )
}

@Serializable
private data class ChatFlags(val enabled: Boolean = false, val replay: Boolean = false)

@Serializable
private data class StateDto(
    val status: String,
    val sessionStatus: String? = null,
    val kind: String? = null,
    val playbackUrl: String? = null,
    val vodUrl: String? = null,
    val startedAt: String? = null,
    val scheduledStartTime: String? = null,
    val premiereVideoId: String? = null,
    val durationSeconds: Double? = null,
    val eventType: String? = null,
    val viewers: Long? = null,
    val serverNow: Double? = null,
    val chat: ChatFlags = ChatFlags(),
    val pollAfterMs: JsonElement? = null
) {
    fun toState(offsetMs: Long): LiveState? = LiveState(
        status = enumOf<LiveStatus>(status) ?: return null,
        sessionStatus = sessionStatus,
        kind = enumOf<LiveKind>(kind) ?: LiveKind.LIVE,
        playbackUrl = playbackUrl,
        vodUrl = vodUrl,
        startedAt = instant(startedAt),
        scheduledStart = instant(scheduledStartTime),
        premiereVideoId = premiereVideoId,
        durationSeconds = durationSeconds,
        eventType = enumOf<LiveEventType>(eventType),
        viewers = viewers,
        chatEnabled = chat.enabled,
        chatReplay = chat.replay,
        clockOffsetMs = offsetMs,
        pollAfterMs = pollAfter(pollAfterMs)
    )
}

@Serializable
private data class MessageDto(
    val id: String,
    val authorName: String,
    val text: String,
    val authorRole: String? = null,
    val offsetMs: Long = 0
) {
    fun toMessage() = LiveChatMessage(id, authorName, text, offsetMs, enumOf<LiveChatMessage.Role>(authorRole))
}

@Serializable
private data class ChatDto(
    val messages: List<MessageDto> = emptyList(),
    val pinned: MessageDto? = null,
    val truncated: Boolean = false,
    val pollAfterMs: JsonElement? = null
) {
    fun toPage() = LiveChatPage(messages.map { it.toMessage() }, pinned?.toMessage(), truncated, pollAfter(pollAfterMs))
}

/** A whole answer is never lost to a bad value here: what is not a number is as if it were absent. */
private fun pollAfter(value: JsonElement?): Long? =
    (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it > 0 }?.toLong()

/** The server's lower-case value, or null when this app does not know it. */
private inline fun <reified E : Enum<E>> enumOf(value: String?): E? =
    enumValues<E>().firstOrNull { it.name.equals(value, ignoreCase = true) }

private fun instant(value: String?): Instant? = try {
    value?.let(Instant::parse)
} catch (_: DateTimeParseException) {
    null
}
