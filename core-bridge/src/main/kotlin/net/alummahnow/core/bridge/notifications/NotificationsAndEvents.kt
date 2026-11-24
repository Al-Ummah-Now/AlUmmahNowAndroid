package net.alummahnow.core.bridge.notifications

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.UserFeatures
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** How opening the notification bell affects the badge (`seen` clears unseen count; `read` marks visible items read). */
@Serializable
enum class NotificationMenuOpenMode {
    @SerialName("seen")
    SEEN,

    @SerialName("read")
    READ
}

/** Bell badge counts from `GET /api/notifications/unread-count` and `GET /api/notifications`. */
data class NotificationBadge(
    val unreadCount: Int = 0,
    val unseenCount: Int = 0,
    val menuOpen: NotificationMenuOpenMode = NotificationMenuOpenMode.SEEN
) {
    val visibleCount: Int
        get() = maxOf(0, if (menuOpen == NotificationMenuOpenMode.READ) unreadCount else unseenCount)
}

/** Local-day prayer quiet window in minutes from midnight (`0..1439`). */
@Serializable
data class PrayerQuietWindow(
    val startMinute: Int,
    val endMinute: Int
)

/** Daily quiet window + optional prayer-time quiet windows (`E22-D6`). */
@Serializable
data class NotificationQuietHours(
    val enabled: Boolean = false,
    val timezone: String = "UTC",
    val startMinute: Int = DEFAULT_START_MINUTE,
    val endMinute: Int = DEFAULT_END_MINUTE,
    val prayerQuietEnabled: Boolean = false,
    val prayerWindows: List<PrayerQuietWindow> = emptyList()
) {
    fun containsMinute(minuteOfDay: Int): Boolean {
        val minute = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        if (enabled && windowContains(startMinute, endMinute, minute)) return true
        if (prayerQuietEnabled) {
            return prayerWindows.take(MAX_PRAYER_WINDOWS).any { windowContains(it.startMinute, it.endMinute, minute) }
        }
        return false
    }

    companion object {
        const val DEFAULT_START_MINUTE = 1320
        const val DEFAULT_END_MINUTE = 360
        private const val MINUTES_PER_DAY = 1440
        private const val MAX_PRAYER_WINDOWS = 5

        private fun windowContains(start: Int, end: Int, minute: Int): Boolean {
            if (start == end) return false
            return if (start < end) minute in start until end else minute >= start || minute < end
        }
    }
}

data class NotificationEntry(
    val id: String,
    val category: String,
    val notificationType: String,
    val notificationKey: String,
    val title: String,
    val isUnread: Boolean,
    val createdAtMs: Long,
    val params: Map<String, String> = emptyMap()
)

data class NotificationsPage(
    val items: List<NotificationEntry>,
    val badge: NotificationBadge,
    val nextCursor: String? = null
)

/** Unified online event from `GET /api/events` (`E22-D7`). */
@Serializable
data class CalendarEvent(
    val streamId: String,
    val channelId: String,
    val channelName: String,
    val channelNameAr: String? = null,
    val channelHandle: String? = null,
    val title: String,
    val description: String? = null,
    val kind: String,
    val eventType: String = "live",
    val status: String,
    val scheduledStartAt: String? = null,
    val startedAt: String? = null,
    val watchPath: String
) {
    fun localizedChannelName(language: String): String =
        if (language == "ar" && !channelNameAr.isNullOrBlank()) channelNameAr else channelName
}

interface NotificationsRepository {
    suspend fun badge(): NotificationBadge
    suspend fun list(limit: Int = 20): NotificationsPage
    suspend fun markSeen(ids: List<String>): NotificationBadge
    suspend fun markAllRead(): NotificationBadge
    suspend fun quietHours(): NotificationQuietHours
    suspend fun updateQuietHours(quietHours: NotificationQuietHours): NotificationQuietHours
}

interface EventsRepository {
    suspend fun viewerFeatures(): UserFeatures
    suspend fun upcomingEvents(eventType: String? = null, limit: Int = 24): List<CalendarEvent>
}

interface AppPushRegistrar {
    suspend fun register(app: String, installId: String, token: String, locale: String, appVersion: String)
    suspend fun unregister(installId: String)
}

/** HTTP implementation of [NotificationsRepository] (`api-videos`). */
class HttpNotificationsRepository(
    baseUrl: String,
    private val accessToken: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient()
) : NotificationsRepository {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Serializable
    private data class Envelope<T>(val result: T)

    @Serializable
    private data class CountsDto(
        val unreadCount: Int = 0,
        val unseenCount: Int? = null,
        val menuOpen: NotificationMenuOpenMode = NotificationMenuOpenMode.SEEN
    ) {
        fun toBadge() = NotificationBadge(unreadCount, unseenCount ?: unreadCount, menuOpen)
    }

    @Serializable
    private data class ItemDto(
        val id: String,
        val category: String,
        val notificationType: String,
        val notificationKey: String,
        val params: Map<String, JsonElement>? = null,
        val readAt: Long? = null,
        val createdAt: Long = 0
    )

    @Serializable
    private data class ListDto(
        val items: List<ItemDto> = emptyList(),
        val unreadCount: Int = 0,
        val unseenCount: Int? = null,
        val menuOpen: NotificationMenuOpenMode = NotificationMenuOpenMode.SEEN,
        val nextCursor: String? = null
    )

    @Serializable
    private data class SettingsDto(val quietHours: NotificationQuietHours? = null)

    @Serializable
    private data class SeenBody(val ids: List<String>)

    @Serializable
    private data class SettingsPutBody(val quietHours: NotificationQuietHours)

    override suspend fun badge(): NotificationBadge {
        val token = accessToken() ?: return NotificationBadge()
        val url = base.newBuilder().addPathSegments("api/notifications/unread-count").build()
        return send<Envelope<CountsDto>>("GET", url, token = token).result.toBadge()
    }

    override suspend fun list(limit: Int): NotificationsPage {
        val token = accessToken() ?: return NotificationsPage(emptyList(), NotificationBadge())
        val clamped = limit.coerceIn(1, MAX_PAGE_SIZE)
        val url = base.newBuilder()
            .addPathSegments("api/notifications")
            .addQueryParameter("limit", clamped.toString())
            .build()
        val dto = send<Envelope<ListDto>>("GET", url, token = token).result
        val entries = dto.items.map { item ->
            val stringParams = item.params.orEmpty().mapValues { (_, v) ->
                runCatching { v.jsonPrimitive.content }.getOrDefault("")
            }
            val title = stringParams["videoTitle"]?.takeIf { it.isNotBlank() }
                ?: stringParams["streamTitle"]?.takeIf { it.isNotBlank() }
                ?: stringParams["channelName"]?.takeIf { it.isNotBlank() }
                ?: ""
            NotificationEntry(
                id = item.id,
                category = item.category,
                notificationType = item.notificationType,
                notificationKey = item.notificationKey,
                title = title,
                isUnread = item.readAt == null,
                createdAtMs = item.createdAt,
                params = stringParams
            )
        }
        val badge = NotificationBadge(dto.unreadCount, dto.unseenCount ?: dto.unreadCount, dto.menuOpen)
        return NotificationsPage(entries, badge, dto.nextCursor)
    }

    override suspend fun markSeen(ids: List<String>): NotificationBadge {
        val token = accessToken() ?: return NotificationBadge()
        val url = base.newBuilder().addPathSegments("api/notifications/seen").build()
        val payload = json.encodeToString(SeenBody.serializer(), SeenBody(ids.take(MAX_SEEN_IDS)))
        return send<Envelope<CountsDto>>("POST", url, body = payload, token = token).result.toBadge()
    }

    override suspend fun markAllRead(): NotificationBadge {
        val token = accessToken() ?: return NotificationBadge()
        val url = base.newBuilder().addPathSegments("api/notifications/read-all").build()
        return send<Envelope<CountsDto>>("POST", url, body = "{}", token = token).result.toBadge()
    }

    override suspend fun quietHours(): NotificationQuietHours {
        val token = accessToken() ?: return NotificationQuietHours()
        val url = base.newBuilder().addPathSegments("api/notifications/settings").build()
        return send<Envelope<SettingsDto>>("GET", url, token = token).result.quietHours ?: NotificationQuietHours()
    }

    override suspend fun updateQuietHours(quietHours: NotificationQuietHours): NotificationQuietHours {
        val token = accessToken() ?: throw CoreError.NotSignedIn()
        val url = base.newBuilder().addPathSegments("api/notifications/settings").build()
        val payload = json.encodeToString(SettingsPutBody.serializer(), SettingsPutBody(quietHours))
        return send<Envelope<SettingsDto>>("PUT", url, body = payload, token = token).result.quietHours ?: quietHours
    }

    private suspend inline fun <reified T> send(
        method: String,
        url: HttpUrl,
        body: String? = null,
        token: String? = null
    ): T = executeJson(client, json, method, url, body, token)

    companion object {
        private const val MAX_PAGE_SIZE = 50
        private const val MAX_SEEN_IDS = 100
    }
}

/** HTTP implementation of [EventsRepository] (`GET /api/me/features` and `GET /api/events`). */
class HttpEventsRepository(
    baseUrl: String,
    private val accessToken: suspend () -> String? = { null },
    private val client: OkHttpClient = OkHttpClient()
) : EventsRepository {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Serializable
    private data class FeaturesDto(val features: JsonObject = JsonObject(emptyMap()))

    @Serializable
    private data class EventsPageDto(val items: List<CalendarEvent> = emptyList())

    @Serializable
    private data class EventsEnvelopeDto(val result: EventsPageDto = EventsPageDto())

    override suspend fun viewerFeatures(): UserFeatures {
        val url = base.newBuilder().addPathSegments("api/me/features").build()
        val dto = executeJson<FeaturesDto>(client, json, "GET", url, token = accessToken())
        val enabled = dto.features.entries
            .mapNotNull { (k, v) -> k.takeIf { v.jsonPrimitive.booleanOrNull == true } }
            .toSet()
        return UserFeatures(enabled)
    }

    override suspend fun upcomingEvents(eventType: String?, limit: Int): List<CalendarEvent> {
        val clamped = limit.coerceIn(1, MAX_EVENTS_LIMIT)
        val url = base.newBuilder()
            .addPathSegments("api/events")
            .addQueryParameter("limit", clamped.toString())
            .apply {
                if (!eventType.isNullOrBlank()) addQueryParameter("eventType", eventType)
            }
            .build()
        return executeJson<EventsEnvelopeDto>(client, json, "GET", url, token = accessToken()).result.items
    }

    companion object {
        private const val MAX_EVENTS_LIMIT = 50
    }
}

/** HTTP implementation of [AppPushRegistrar] (`PUT / DELETE /api/devices/:installId`). */
class HttpAppPushRegistrar(
    baseUrl: String,
    private val accessToken: suspend () -> String?,
    private val client: OkHttpClient = OkHttpClient()
) : AppPushRegistrar {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RegisterDto(
        val app: String,
        val provider: String,
        val platform: String,
        val token: String,
        val locale: String,
        val appVersion: String
    )

    override suspend fun register(app: String, installId: String, token: String, locale: String, appVersion: String) {
        if (!SAFE_ID.matches(installId)) throw CoreError.NotFound("device")
        val bearer = accessToken() ?: return
        val url = base.newBuilder().addPathSegments("api/devices").addPathSegment(installId).build()
        val body = json.encodeToString(
            RegisterDto.serializer(),
            RegisterDto(
                app = app,
                provider = "fcm",
                platform = "android",
                token = token,
                locale = locale,
                appVersion = appVersion.take(MAX_VERSION_LEN)
            )
        )
        executeJson<JsonObject>(client, json, "PUT", url, body = body, token = bearer)
    }

    override suspend fun unregister(installId: String) {
        if (!SAFE_ID.matches(installId)) return
        val bearer = accessToken() ?: return
        val url = base.newBuilder().addPathSegments("api/devices").addPathSegment(installId).build()
        executeJson<JsonObject>(client, json, "DELETE", url, token = bearer)
    }

    companion object {
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{8,128}$")
        private const val MAX_VERSION_LEN = 32
    }
}

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private suspend inline fun <reified T> executeJson(
    client: OkHttpClient,
    json: Json,
    method: String,
    url: HttpUrl,
    body: String? = null,
    token: String? = null
): T = withContext(Dispatchers.IO) {
    val reqBody = when {
        body != null -> body.toRequestBody(JSON_MEDIA_TYPE)
        method == "POST" || method == "PUT" -> "{}".toRequestBody(JSON_MEDIA_TYPE)
        else -> null
    }
    val request = Request.Builder()
        .url(url)
        .method(method, reqBody)
        .header("Accept", "application/json")
        .apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }
        .build()
    val raw = try {
        client.newCall(request).execute().use { response ->
            when {
                response.code == 401 -> throw CoreError.NotSignedIn()
                response.code == 404 -> throw CoreError.NotFound(url.encodedPath)
                !response.isSuccessful -> throw CoreError.Http(response.code)
                else -> response.body.string()
            }
        }
    } catch (e: IOException) {
        throw CoreError.Network(e)
    }
    try {
        json.decodeFromString<T>(raw.ifBlank { "{}" })
    } catch (e: SerializationException) {
        throw CoreError.Decode(e)
    }
}
