package net.alummahnow.core.bridge.admin

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.a
import net.alummahnow.core.bridge.studio.core.d
import net.alummahnow.core.bridge.studio.core.o
import net.alummahnow.core.bridge.studio.core.obj
import net.alummahnow.core.bridge.studio.core.s
import net.alummahnow.core.bridge.studio.core.strList
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * admin-api's review routes (and api-videos' device registry) over [PrivateHttp], as the signed-in reviewer.
 * URLs and bodies are never logged: a frame request names the review and a stream URL carries a signature.
 */
class HttpReviewService(
    private val adminApi: String,
    private val videosApi: String,
    private val accessToken: suspend () -> String?,
    private val http: OkHttpClient = PrivateHttp.client
) : ReviewService,
    PushRegistrar {

    // ---- transport ----

    private suspend fun send(
        method: String,
        base: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonObject? = null
    ): ByteArray {
        val token = token()
        val url = base.trimEnd('/').toHttpUrl().newBuilder().addPathSegmentsFrom(path).apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = PrivateHttp.request(url.toString())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET" || method == "DELETE") null else EMPTY)
            .build()
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { res ->
                    val bytes = res.body.bytes()
                    if (!res.isSuccessful) throw failure(res.code, bytes)
                    bytes
                }
            } catch (e: IOException) {
                throw transport(e)
            }
        }
    }

    private suspend fun token(): String = accessToken() ?: throw CoreError.NotSignedIn()

    private fun HttpUrl.Builder.addPathSegmentsFrom(path: String): HttpUrl.Builder =
        apply { path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) } }

    private fun result(bytes: ByteArray): JsonElement {
        val root = try {
            J.parse(bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw CoreError.Decode(e)
        }
        return (root as? JsonObject)?.get("result") ?: root
    }

    // ---- ReviewService ----

    @Suppress("SwallowedException") // a 404 means the server predates `/api/me`
    override suspend fun profile(): StaffProfile = try {
        val me = result(send("GET", adminApi, "/api/me")).obj() ?: throw CoreError.Decode(IllegalStateException("me"))
        val profile = StaffProfile(
            userId = me.s("userId") ?: me.s("id") ?: throw CoreError.Decode(IllegalStateException("userId")),
            displayName = me.s("displayName"),
            roles = me.strList("roles"),
            permissions = me.strList("permissions")
        )
        if (!profile.isReviewer) throw ReviewError.NotStaff()
        profile
    } catch (_: CoreError.NotFound) {
        // A server without `GET /api/me` yet: the queue itself answers whether this account is a reviewer.
        queue(ReviewTab.ACTION_NEEDED, null, null)
        StaffProfile(userId = "", roles = listOf("content_reviewer"))
    }

    override suspend fun queue(tab: ReviewTab, query: String?, cursor: String?): ReviewPage {
        val params = buildMap {
            put("tab", tab.wire)
            put("limit", "50")
            cursor?.let { put("cursor", it) }
            query?.trim()?.takeIf { it.isNotEmpty() }?.let { put("q", it) }
        }
        val page = result(send("GET", adminApi, "/api/review/queue", params)).obj() ?: JsonObject(emptyMap())
        return ReviewPage(page.a("items").mapNotNull { it.obj()?.let(::parseItem) }, page.s("nextCursor"))
    }

    override suspend fun detail(id: String): ReviewDetail {
        val r = result(send("GET", adminApi, "/api/review/$id")).obj() ?: throw CoreError.Decode(IllegalStateException("detail"))
        val subject = r.o("subject")?.let(::parseSubject)
        var item = parseItem(r.o("review") ?: throw CoreError.Decode(IllegalStateException("review")))
        if (item.title == null) item = item.copy(title = subject?.title)
        val findings = r.a("findings").mapNotNull { it.obj()?.let(::parseFinding) }
        val manifest = r.o("manifest")?.let(::parseManifest) ?: r.s("manifestKey")?.let { key ->
            // The manifest is a file of the review, read like a frame (never stored).
            runCatching { parseManifest(J.parse(media(id, key).decodeToString()).obj() ?: JsonObject(emptyMap())) }.getOrNull()
        }.orEmpty()
        return ReviewDetail(
            item = item,
            findings = findings,
            history = r.a("history").mapNotNull { it.obj()?.let(::parseHistory) }.filter { it.id != id },
            subject = subject,
            frames = ReviewDetail.frames(findings, manifest),
            stream = r.o("stream")?.let { s -> s.s("url")?.let { ReviewStream(it, instant(s.s("expiresAt")), s.bool("preview")) } }
        )
    }

    override suspend fun claim(id: String) {
        send("POST", adminApi, "/api/review/$id/claim", body = JsonObject(emptyMap()))
    }

    override suspend fun reveal(id: String, target: String) {
        send("POST", adminApi, "/api/review/$id/reveal", body = buildJsonObject { put("target", target.take(MAX_TARGET)) })
    }

    override suspend fun decide(id: String, decision: ReviewDecision, reason: ReviewReason?, note: String?, version: Int?): ReviewItem {
        val body = buildJsonObject {
            put("decision", decision.wire)
            reason?.let { put("reasonCode", it.wire) }
            note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it.take(MAX_NOTE)) }
            version?.let { put("version", it) }
        }
        val r = result(send("POST", adminApi, "/api/review/$id/decision", body = body)).obj()
        return parseItem(r?.o("review") ?: throw CoreError.Decode(IllegalStateException("decision")))
    }

    override suspend fun media(id: String, key: String): ByteArray = send("GET", adminApi, "/api/review/$id/media", mapOf("key" to key))

    override suspend fun recordCapture(event: CaptureEvent, reviewId: String?, installId: String) {
        val body = buildJsonObject {
            put("event", event.wire)
            put("installId", installId)
            reviewId?.let { put("reviewId", it) }
        }
        send("POST", adminApi, "/api/review/audit/capture", body = body)
    }

    // ---- PushRegistrar ----

    override suspend fun register(installId: String, token: String, locale: String, appVersion: String) {
        val body = buildJsonObject {
            put("app", "admin")
            put("platform", "android")
            put("provider", "fcm")
            put("token", token)
            put("locale", locale)
            put("appVersion", appVersion.take(MAX_VERSION))
        }
        send("PUT", videosApi, "/api/v1/devices/$installId", body = body)
    }

    override suspend fun unregister(installId: String) {
        send("DELETE", videosApi, "/api/v1/devices/$installId")
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private const val MAX_NOTE = 1000
        private const val MAX_TARGET = 300
        private const val MAX_VERSION = 32
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409

        /** No route to the server reads as offline; anything else on the wire is a network error. */
        private fun transport(e: IOException): Exception = when (e) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> ReviewError.Offline()
            else -> CoreError.Network(e)
        }

        /** Maps an error response to what the app explains (the server's `code` first). */
        fun failure(status: Int, bytes: ByteArray): Exception {
            val body = runCatching { J.parse(bytes.decodeToString()).obj() }.getOrNull()
            return when {
                status == HTTP_UNAUTHORIZED -> CoreError.NotSignedIn()
                body?.s("code") == "step_up_required" -> ReviewError.StepUpRequired()
                body?.s("code") in setOf("reviewer_only", "staff_only") -> ReviewError.NotStaff()
                body?.s("code") == "undo_expired" -> ReviewError.UndoExpired()
                body?.s("code") == "claimed" -> ReviewError.ClaimedByOther(body.s("assignedToName"))
                status == HTTP_CONFLICT -> ReviewError.AlreadyDecided(
                    body?.s("decision"),
                    body?.s("decidedByName"),
                    instant(body?.s("decidedAt"))
                )
                status == HTTP_FORBIDDEN -> CoreError.Http(status)
                status == HTTP_NOT_FOUND -> CoreError.NotFound("review")
                else -> CoreError.Http(status)
            }
        }

        internal fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }

        /** D1 returns booleans as 0/1. */
        private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.contentOrNull.let { it == "1" || it == "true" }

        private fun JsonObject.long(key: String): Long? = d(key)?.toLong()

        fun parseItem(o: JsonObject) = ReviewItem(
            id = o.s("id") ?: "",
            subjectId = o.s("subject_id") ?: "",
            status = o.s("status") ?: "in_review",
            title = o.s("title"),
            channelId = o.s("channel_id"),
            channelName = o.s("channel_name"),
            channelTrust = o.s("channel_trust"),
            risk = o.d("risk"),
            nsfw = o.bool("nsfw"),
            escalate = o.bool("escalate"),
            minor = o.bool("minor"),
            holdCause = HoldCause.of(o.s("hold_cause")),
            topCategory = o.s("top_category"),
            slaDueAt = instant(o.s("sla_due_at")),
            createdAt = instant(o.s("created_at")),
            assignedTo = o.s("assigned_to"),
            assignedToName = o.s("assigned_to_name"),
            decision = o.s("decision"),
            decidedAt = instant(o.s("decided_at")),
            decidedBy = o.s("decided_by"),
            decidedByName = o.s("decided_by_name"),
            undoUntil = instant(o.s("undo_until")),
            version = o.d("version")?.toInt()
        )

        fun parseFinding(o: JsonObject) = ReviewFinding(
            id = o.s("id") ?: "",
            source = o.s("source") ?: "",
            category = o.s("category") ?: "other",
            score = o.d("score") ?: 0.0,
            atMs = o.long("at_ms"),
            endMs = o.long("end_ms"),
            mediaKey = o.s("media_key"),
            text = o.s("text"),
            quranRef = o.s("quran_ref"),
            model = o.s("model"),
            rationale = o.s("rationale")
        )

        fun parseHistory(o: JsonObject) = ReviewHistoryEntry(
            id = o.s("id") ?: "",
            status = o.s("status"),
            decision = o.s("decision"),
            reasonCode = o.s("reason_code"),
            decidedAt = instant(o.s("decided_at")),
            decidedByName = o.s("decided_by_name")
        )

        fun parseSubject(o: JsonObject) = ReviewSubject(
            id = o.s("id") ?: "",
            title = o.s("title") ?: "",
            description = o.s("description") ?: "",
            tags = o.strList("tags"),
            channelId = o.s("channelId"),
            durationSeconds = o.d("duration")
        )

        fun parseManifest(o: JsonObject): List<ReviewFrame> =
            o.a("frames").mapNotNull { f -> f.obj()?.let { fr -> fr.s("key")?.let { ReviewFrame(it, fr.long("atMs") ?: 0, false) } } }
    }
}
