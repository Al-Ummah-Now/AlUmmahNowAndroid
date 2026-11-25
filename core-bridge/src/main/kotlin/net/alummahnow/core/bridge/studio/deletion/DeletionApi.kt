package net.alummahnow.core.bridge.studio.deletion

import java.io.IOException
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.AunClient
import net.alummahnow.core.bridge.admin.PrivateHttp
import net.alummahnow.core.bridge.attestation.NATIVE_PROOF_HEADER
import net.alummahnow.core.bridge.attestation.NATIVE_PROOF_REQUIRED
import net.alummahnow.core.bridge.attestation.NativeProofProvider
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.d
import net.alummahnow.core.bridge.studio.core.o
import net.alummahnow.core.bridge.studio.core.obj
import net.alummahnow.core.bridge.studio.core.s
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A server's answer, as it came: the caller decides what each status means. */
data class DeletionAnswer(val status: Int, val body: String, val retryAfter: String? = null)

/** The calls of an account deletion, and the server's time. Each throws [IOException] when no answer came. */
interface DeletionApi {
    /** `DELETE /api/privacy/data`: one bounded pass. */
    suspend fun erase(token: String, resumeToken: String?): DeletionAnswer

    /** `DELETE /account` on Auth. */
    suspend fun deleteAccount(token: String): DeletionAnswer

    /** `POST /account/deletion-receipt` on Auth, with a recent sign-in. */
    suspend fun deletionReceipt(token: String): DeletionAnswer

    /** `GET /account/status` on Auth: whether the receipt's account is deleted. Takes no access token. */
    suspend fun accountStatus(receipt: String): DeletionAnswer

    /** The server's clock (seconds), or null when it did not say: a phone's clock must not decide what is wiped. */
    suspend fun serverNowSeconds(): Long?
}

/** [DeletionApi] over HTTPS. A request has a limited time, so nothing waits for an answer that never comes. */
class HttpDeletionApi(
    apiBaseUrl: String,
    issuer: String,
    private val userAgent: String,
    private val clientHeader: String,
    private val proofs: NativeProofProvider,
    client: OkHttpClient = PrivateHttp.client
) : DeletionApi {
    private val api = apiBaseUrl.trimEnd('/')
    private val auth = issuer.trimEnd('/')
    private val http = client.newBuilder().callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS).build()

    override suspend fun erase(token: String, resumeToken: String?): DeletionAnswer {
        val body = buildJsonObject {
            put("confirm", "DELETE")
            resumeToken?.let { put("resumeToken", it) }
        }.toString()
        val first = send(eraseRequest(token, body, proofs.proof()))
        if (first.status != HTTP_FORBIDDEN || !first.body.contains(NATIVE_PROOF_REQUIRED)) return first
        proofs.invalidate()
        val fresh = proofs.proof() ?: return first
        return send(eraseRequest(token, body, fresh))
    }

    private fun eraseRequest(token: String, body: String, proof: String?): Request =
        request("$api/api/privacy/data", token).delete(body.toRequestBody(JSON))
            .apply { proof?.let { header(NATIVE_PROOF_HEADER, it) } }
            .build()

    override suspend fun deleteAccount(token: String): DeletionAnswer = send(request("$auth/account", token).delete().build())

    override suspend fun deletionReceipt(token: String): DeletionAnswer =
        send(request("$auth/account/deletion-receipt", token).post(ByteArray(0).toRequestBody()).build())

    override suspend fun accountStatus(receipt: String): DeletionAnswer =
        send(request("$auth/account/status", receipt, scheme = "Deletion-Receipt").get().build())

    override suspend fun serverNowSeconds(): Long? = try {
        withContext(Dispatchers.IO) {
            http.newCall(PrivateHttp.request("$auth/.well-known/oauth-authorization-server").header("User-Agent", userAgent).build())
                .execute().use { res -> res.header("Date")?.let(::httpDateSeconds) }
        }
    } catch (_: IOException) {
        null
    }

    private fun request(url: String, credential: String, scheme: String = "Bearer"): Request.Builder = PrivateHttp.request(url)
        .header("Accept", "application/json")
        .header("User-Agent", userAgent)
        .header(AunClient.HEADER, clientHeader)
        .header("Authorization", "$scheme $credential")

    private suspend fun send(request: Request): DeletionAnswer = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { DeletionAnswer(it.code, it.body.string(), it.header("Retry-After")) }
    }

    private companion object {
        const val CALL_TIMEOUT_S = 60L
        const val HTTP_FORBIDDEN = 403
        val JSON = "application/json".toMediaType()
    }
}

/** Seconds of an HTTP date, or null. */
internal fun httpDateSeconds(value: String): Long? {
    // display-format: not shown (HTTP header)
    val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    val position = ParsePosition(0)
    return format.parse(value.trim(), position)?.time?.let { it / MS_PER_S }
}

private const val MS_PER_S = 1_000L

/** What an OpenAuth access token says. Its signature was checked by the server that sent it; this only reads. */
object DeletionToken {
    private const val MAX_FUTURE_SKEW_S = 60.0

    /**
     * The account: `properties.id`, or null. Never `sub`, which is a hashed subject (`user:<hex>`) and would match
     * nothing, or the wrong thing.
     */
    fun userIdOf(token: String): String? =
        payload(token)?.o("properties")?.s("id")?.takeIf { it.isNotBlank() }

    /** Whether the person signed in within [maxAgeSeconds] of [nowSeconds] (`auth_time`). */
    fun isRecent(token: String, nowSeconds: Long, maxAgeSeconds: Int): Boolean {
        val authTime = payload(token)?.d("auth_time") ?: return false
        val age = nowSeconds.toDouble() - authTime
        return age >= -MAX_FUTURE_SKEW_S && age <= maxAgeSeconds.toDouble()
    }

    /** When it was issued (seconds), or null. */
    fun issuedAt(token: String): Long? = payload(token)?.d("iat")?.toLong()

    private fun payload(token: String) = runCatching {
        val parts = token.split('.')
        require(parts.size == JWT_PARTS)
        J.parse(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).obj()
    }.getOrNull()

    private const val JWT_PARTS = 3
}
