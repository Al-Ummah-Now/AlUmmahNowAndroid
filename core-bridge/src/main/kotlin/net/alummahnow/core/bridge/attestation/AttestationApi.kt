package net.alummahnow.core.bridge.attestation

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.b
import net.alummahnow.core.bridge.studio.core.o
import net.alummahnow.core.bridge.studio.core.obj
import net.alummahnow.core.bridge.studio.core.s
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the videos admin configured. Both platforms are off by default. */
data class AttestationConfig(val androidEnabled: Boolean, val cloudProjectNumber: Long?) {
    companion object {
        val OFF = AttestationConfig(androidEnabled = false, cloudProjectNumber = null)
    }
}

/** A server-issued proof, sent as [NATIVE_PROOF_HEADER] until shortly before [expiresAt]. */
data class Proof(val value: String, val expiresAt: Instant)

data class Challenge(val value: String, val expiresAt: Instant?)

/** api-videos' attestation endpoints (`/api/apps/attestation/…`). */
interface AttestationApi {
    /** The config; a server without attestation (`404 attestation_off`, or an older server) means [AttestationConfig.OFF]. */
    suspend fun config(): AttestationConfig
    suspend fun challenge(): Challenge
    suspend fun android(token: String, challenge: String, packageName: String): Proof
}

/** [AttestationApi] over OkHttp. Unauthenticated: attestation proves the app, not the person. */
class HttpAttestationApi(
    private val baseUrl: String,
    private val userAgent: String,
    private val http: OkHttpClient = OkHttpClient()
) : AttestationApi {

    @Suppress("SwallowedException") // a 404 is the documented "attestation off" answer
    override suspend fun config(): AttestationConfig = try {
        val android = call("GET", "config").o("android")
        AttestationConfig(
            androidEnabled = android?.b("enabled") == true,
            cloudProjectNumber = android?.s("cloudProjectNumber")?.toLongOrNull()
        )
    } catch (e: CoreError.NotFound) {
        AttestationConfig.OFF
    }

    override suspend fun challenge(): Challenge {
        val r = call("POST", "challenge", JsonObject(emptyMap()))
        val value = r.s("challenge") ?: throw CoreError.Decode(IllegalStateException("challenge"))
        return Challenge(value, r.s("expiresAt")?.let { runCatching { Instant.parse(it) }.getOrNull() })
    }

    override suspend fun android(token: String, challenge: String, packageName: String): Proof {
        val r = call(
            "POST",
            "android",
            buildJsonObject {
                put("token", token)
                put("challenge", challenge)
                put("packageName", packageName)
            }
        )
        val proof = r.s("proof") ?: throw CoreError.Decode(IllegalStateException("proof"))
        val expires = r.s("expiresAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: throw CoreError.Decode(IllegalStateException("expiresAt"))
        return Proof(proof, expires)
    }

    private suspend fun call(method: String, path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/apps/attestation/$path")
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
            .method(method, body?.toString()?.toRequestBody(JSON))
            .build()
        val text = try {
            http.newCall(request).execute().use { res ->
                val text = res.body.string()
                when {
                    res.code == HTTP_NOT_FOUND -> throw CoreError.NotFound("attestation")
                    !res.isSuccessful -> throw CoreError.Http(res.code)
                }
                text
            }
        } catch (e: IOException) {
            throw CoreError.Network(e)
        }
        val root = runCatching { J.parse(text).obj() }.getOrNull() ?: throw CoreError.Decode(IllegalStateException("json"))
        root.o("result") ?: root
    }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val HTTP_NOT_FOUND = 404
    }
}
