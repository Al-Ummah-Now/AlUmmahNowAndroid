package net.alummahnow.core.bridge.auth.passkey

import java.net.URLEncoder
import net.alummahnow.core.bridge.CoreError
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The native passkey ceremonies on top of the app's existing OAuth session, so there is one session code path:
 *
 * Sign-in: [beginAuthorization] starts an ordinary PKCE sign-in in aun-auth (which keeps the verifier and `state`);
 * its `code_challenge` goes to `/native/passkey/options`, the platform signs the challenge, `/native/passkey/verify`
 * returns an authorization code, and [completeRedirect] finishes it exactly like a browser redirect
 * (`{redirectUri}?code=…&state=…`): aun-auth checks `state` and exchanges the code with the verifier at the token
 * endpoint, then stores the account.
 *
 * Registration: Bearer the active account's access token.
 */
class PasskeyFlow<A>(
    private val api: PasskeyApi,
    private val clientId: String,
    private val redirectUri: String,
    private val beginAuthorization: () -> String,
    private val completeRedirect: suspend (String) -> A,
    private val accessToken: suspend () -> String?
) {
    suspend fun signIn(authenticator: PasskeyAuthenticator): A {
        val authorize = beginAuthorization().toHttpUrl()
        val challenge = authorize.queryParameter("code_challenge") ?: throw CoreError.Unavailable("no code_challenge")
        val state = authorize.queryParameter("state") ?: throw CoreError.Unavailable("no state")
        val options = api.signInOptions(clientId, challenge)
        val response = authenticator.signIn(options.optionsJson)
        val code = api.verifySignIn(options.challengeId, clientId, response)
        return completeRedirect(redirectFor(code, state))
    }

    suspend fun register(authenticator: PasskeyAuthenticator, name: String? = null): RegisteredPasskey {
        val token = accessToken() ?: throw CoreError.NotSignedIn()
        val options = api.registerOptions(token)
        val response = authenticator.create(options.optionsJson)
        return api.registerVerify(token, options.challengeId, response, name)
    }

    /** `{redirectUri}?code=…&state=…`: a custom scheme, so built by hand rather than with HttpUrl. */
    internal fun redirectFor(code: String, state: String): String {
        val sep = if (redirectUri.contains('?')) '&' else '?'
        return "$redirectUri${sep}code=${enc(code)}&state=${enc(state)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8.name())
}
