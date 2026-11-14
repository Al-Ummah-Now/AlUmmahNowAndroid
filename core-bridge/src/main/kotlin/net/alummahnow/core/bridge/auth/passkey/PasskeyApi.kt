package net.alummahnow.core.bridge.auth.passkey

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.admin.PrivateHttp
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.o
import net.alummahnow.core.bridge.studio.core.obj
import net.alummahnow.core.bridge.studio.core.s
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The auth server's native passkey routes (AUTH-PASSKEYS, "Native apps"):
 *
 * - `POST {issuer}/native/passkey/options` {client_id, code_challenge, code_challenge_method} → {challenge_id, publicKey}
 * - `POST {issuer}/native/passkey/verify` {challenge_id, client_id, credential} → {code}, exchanged at the token
 *   endpoint with the PKCE verifier and the client's redirect_uri, like a browser sign-in
 * - `POST {issuer}/native/passkey/register/options` (Bearer) → {challenge_id, publicKey}; 401 `step_up_required` when
 *   the sign-in is over 15 minutes old
 * - `POST {issuer}/native/passkey/register/verify` (Bearer) {challenge_id, credential, name?} → {id, name, created_at}
 *
 * Errors are `{error, error_description}`; 429 carries `Retry-After`. Over [PrivateHttp]: TLS only, nothing cached.
 * Bodies and tokens are never logged.
 */
class PasskeyApi(issuer: String, private val http: OkHttpClient = PrivateHttp.client) {
    private val base = issuer.trimEnd('/')

    suspend fun signInOptions(clientId: String, codeChallenge: String): PasskeyChallenge = challenge(
        post(
            "/native/passkey/options",
            buildJsonObject {
                put("client_id", clientId)
                put("code_challenge", codeChallenge)
                put("code_challenge_method", "S256")
            }
        )
    )

    /** Returns the authorization code. */
    suspend fun verifySignIn(challengeId: String, clientId: String, credentialJson: String): String {
        val res = post(
            "/native/passkey/verify",
            buildJsonObject {
                put("challenge_id", challengeId)
                put("client_id", clientId)
                put("credential", parse(credentialJson))
            }
        )
        return res.s("code") ?: throw CoreError.Decode(IllegalStateException("verify: no code"))
    }

    suspend fun registerOptions(accessToken: String): PasskeyChallenge =
        challenge(post("/native/passkey/register/options", JsonObject(emptyMap()), accessToken))

    suspend fun registerVerify(accessToken: String, challengeId: String, credentialJson: String, name: String?): RegisteredPasskey {
        val res = post(
            "/native/passkey/register/verify",
            buildJsonObject {
                put("challenge_id", challengeId)
                put("credential", parse(credentialJson))
                if (!name.isNullOrBlank()) put("name", name)
            },
            accessToken
        )
        return RegisteredPasskey(
            id = res.s("id") ?: throw CoreError.Decode(IllegalStateException("register: no id")),
            name = res.s("name"),
            createdAt = res.s("created_at")
        )
    }

    private fun challenge(res: JsonObject): PasskeyChallenge = PasskeyChallenge(
        challengeId = res.s("challenge_id") ?: throw CoreError.Decode(IllegalStateException("no challenge_id")),
        optionsJson = (res.o("publicKey") ?: throw CoreError.Decode(IllegalStateException("no publicKey"))).toString()
    )

    private fun parse(json: String): JsonElement = try {
        J.parse(json)
    } catch (e: IllegalArgumentException) {
        throw PasskeyError.Failed(e)
    }

    private suspend fun post(path: String, body: JsonObject, accessToken: String? = null): JsonObject {
        val request = Request.Builder()
            .url(base + path)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (status, text, retryAfter) = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { res -> Triple(res.code, res.body.string(), res.header("Retry-After")) }
            } catch (e: IOException) {
                throw CoreError.Network(e)
            }
        }
        if (status !in HTTP_OK_RANGE) throw failure(status, text, retryAfter)
        return parseObject(text)
    }

    private fun parseObject(text: String): JsonObject {
        val root = try {
            J.parse(text)
        } catch (e: IllegalArgumentException) {
            throw CoreError.Decode(e)
        }
        return root.obj() ?: throw CoreError.Decode(IllegalStateException("not an object"))
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Maps an error response: the server's `error` first, then the status. */
        fun failure(status: Int, text: String, retryAfter: String?): Exception {
            val error = runCatching { J.parse(text).obj()?.s("error") }.getOrNull()
            return when {
                error == "step_up_required" -> PasskeyError.StepUpRequired()
                status == HTTP_TOO_MANY -> PasskeyError.RateLimited(retryAfter?.trim()?.toLongOrNull())
                error == null && status == HTTP_UNAUTHORIZED -> CoreError.NotSignedIn()
                error == null && status == HTTP_NOT_FOUND -> CoreError.Unavailable("native passkeys")
                status >= HTTP_SERVER -> CoreError.Http(status)
                else -> PasskeyError.Rejected(error, status)
            }
        }

        private val HTTP_OK_RANGE = 200..299
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER = 500
    }
}
