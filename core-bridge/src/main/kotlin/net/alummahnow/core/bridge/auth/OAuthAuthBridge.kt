package net.alummahnow.core.bridge.auth

import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Native OAuth 2.0 client for the Al Ummah Now issuer. Public client, so PKCE (S256) is mandatory;
 * refresh tokens rotate on every use. Kept in Kotlin until `aun-auth` exposes the flow over FFI; tokens
 * persist through the core's `SecureStorage` adapter already.
 */
class OAuthAuthBridge(
    private val config: OAuthConfig,
    private val tokens: TokenStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) : AuthBridge {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableState = MutableStateFlow(tokens.load()?.let(::stateFor) ?: AuthState.SignedOut)
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()
    override val isSignInAvailable: Boolean = config.clientId.isNotBlank()

    private val refreshLock = Mutex()
    private var pending: PendingPkce? = null

    private data class PendingPkce(val state: String, val verifier: String)

    override suspend fun beginPkceSignIn(): String {
        val redirect = requireNotNull(config.redirectUri) { "PKCE needs a redirect URI" }
        val verifier = Pkce.randomToken()
        val state = Pkce.randomToken(16)
        pending = PendingPkce(state, verifier)
        return config.authorizeEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", config.clientId)
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", config.scopes.joinToString(" "))
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    override suspend fun completePkceSignIn(callbackUri: String): AuthState {
        val expected = pending ?: throw CoreError.Unavailable("no sign-in in progress")
        val code = authorizationCode(callbackUri, expected.state) ?: throw CoreError.Http(HTTP_UNAUTHORIZED)
        pending = null
        val response = postToken(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to requireNotNull(config.redirectUri),
            "code_verifier" to expected.verifier
        )
        return signIn(response)
    }

    /** The `code` from a redirect, or null when the issuer refused or the `state` does not match ours. */
    private fun authorizationCode(callbackUri: String, expectedState: String): String? {
        // HttpUrl only parses http(s); the scheme is irrelevant once the redirect reached us.
        val url = callbackUri.replaceFirst(Regex("^[a-z][a-z0-9+.-]*://"), "https://").toHttpUrl()
        url.queryParameter("error")?.let { AunLog.w(TAG, "authorize refused", "error" to it) }
        return url.queryParameter("code")?.takeIf { url.queryParameter("error") == null && url.queryParameter("state") == expectedState }
    }

    override suspend fun beginDeviceSignIn(): DeviceCode {
        val body = post(config.deviceEndpoint, "client_id" to config.clientId, "scope" to config.scopes.joinToString(" "))
        if (body.status !in 200..299) throw CoreError.Http(body.status)
        val r = decode<DeviceAuthorizationResponse>(body.text)
        return DeviceCode(
            r.userCode,
            r.verificationUri,
            r.verificationUriComplete,
            r.expiresIn,
            r.deviceCode,
            r.interval ?: DEFAULT_INTERVAL_S
        )
    }

    override suspend fun awaitDeviceSignIn(code: DeviceCode): AuthState {
        val deadline = now() + code.expiresInSeconds * MS
        var interval = code.intervalSeconds.coerceAtLeast(1)
        while (now() < deadline) {
            sleep(interval * MS)
            val body = post(
                config.tokenEndpoint,
                "grant_type" to DEVICE_GRANT,
                "device_code" to code.deviceCode,
                "client_id" to config.clientId
            )
            if (body.status in 200..299) return signIn(decode(body.text))
            when (runCatching { decode<OAuthErrorResponse>(body.text).error }.getOrNull()) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += SLOW_DOWN_STEP_S
                "access_denied" -> return AuthState.SignedOut
                "expired_token" -> break
                else -> throw CoreError.Http(body.status)
            }
        }
        throw CoreError.Unavailable("device code expired")
    }

    override suspend fun accessToken(): String? = refreshLock.withLock {
        val current = tokens.load() ?: return@withLock null
        if (current.expiresAtMs - REFRESH_MARGIN_MS > now()) return@withLock current.accessToken
        val refresh = current.refreshToken ?: return@withLock signOutLocked()
        val body = post(config.tokenEndpoint, "grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to config.clientId)
        when {
            body.status in 200..299 -> {
                signIn(decode(body.text))
                tokens.load()?.accessToken
            }
            // A rejected refresh token (revoked, reused, expired) means the session is over.
            body.status in 400..499 -> signOutLocked()
            else -> throw CoreError.Http(body.status)
        }
    }

    override suspend fun signOut() {
        refreshLock.withLock { signOutLocked() }
    }

    private fun signOutLocked(): String? {
        tokens.clear()
        mutableState.value = AuthState.SignedOut
        return null
    }

    private suspend fun postToken(vararg form: Pair<String, String>): TokenResponse {
        val body = post(config.tokenEndpoint, "client_id" to config.clientId, *form)
        if (body.status !in 200..299) throw CoreError.Http(body.status)
        return decode(body.text)
    }

    private fun signIn(response: TokenResponse): AuthState {
        val stored = StoredTokens(response.accessToken, response.refreshToken, now() + response.expiresIn * MS)
        tokens.save(stored)
        return (stateFor(stored) ?: AuthState.SignedOut).also { mutableState.value = it }
    }

    private class Body(val status: Int, val text: String)

    private suspend fun post(url: String, vararg form: Pair<String, String>): Body = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(
            FormBody.Builder().apply {
                form.forEach { add(it.first, it.second) }
            }.build()
        ).build()
        try {
            http.newCall(request).execute().use { Body(it.code, it.body.string()) }
        } catch (e: IOException) {
            throw CoreError.Network(e)
        }
    }

    private inline fun <reified T> decode(text: String): T = try {
        json.decodeFromString<T>(text)
    } catch (e: IllegalArgumentException) {
        throw CoreError.Decode(e)
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Long = DEFAULT_EXPIRY_S
    )

    @Serializable
    private data class DeviceAuthorizationResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
        @SerialName("expires_in") val expiresIn: Int,
        val interval: Int? = null
    )

    @Serializable
    private data class OAuthErrorResponse(val error: String)

    companion object {
        private const val TAG = "auth"
        private const val MS = 1000L
        private const val HTTP_UNAUTHORIZED = 401
        private const val DEFAULT_INTERVAL_S = 5
        private const val SLOW_DOWN_STEP_S = 5
        private const val DEFAULT_EXPIRY_S = 3600L
        private const val REFRESH_MARGIN_MS = 60_000L
        private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** Reads the account from the access token's claims (`properties.id` / `properties.email`, else `sub`). */
        internal fun stateFor(tokens: StoredTokens): AuthState.SignedIn? = runCatching {
            val payload = tokens.accessToken.split('.')[1]
            val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(payload).decodeToString()).jsonObject
            val props = claims["properties"] as? JsonObject
            val id = props?.get("id")?.jsonPrimitive?.contentOrNull ?: claims.getValue("sub").jsonPrimitive.content
            val name = props?.get("email")?.jsonPrimitive?.contentOrNull ?: claims["email"]?.jsonPrimitive?.contentOrNull ?: id
            AuthState.SignedIn(id, name)
        }.getOrNull()
    }
}
