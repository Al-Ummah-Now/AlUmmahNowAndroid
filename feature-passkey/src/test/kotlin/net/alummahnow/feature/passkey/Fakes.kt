package net.alummahnow.feature.passkey

import kotlinx.coroutines.flow.MutableStateFlow
import net.alummahnow.core.bridge.auth.passkey.PasskeyAuthenticator
import net.alummahnow.core.bridge.auth.passkey.RegisteredPasskey
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount

fun account(id: String) = StudioAccount(id, "User $id", null, null, null, needsReauth = false)

/** The Credential Manager seam: scripted results, records what the screen asked for. */
class FakeAuthenticator(override val isSupported: Boolean = true) : PasskeyAuthenticator {
    var onSignIn: () -> String = { """{"id":"cred"}""" }
    var onCreate: () -> String = { """{"id":"new"}""" }
    var signIns = 0
    var creates = 0

    override suspend fun signIn(requestJson: String): String {
        signIns++
        return onSignIn()
    }

    override suspend fun create(requestJson: String): String {
        creates++
        return onCreate()
    }
}

/** An [AccountSession] whose passkey calls go straight to the authenticator, with scripted server errors. */
class FakeSession(signedIn: Boolean = false) : AccountSession {
    override val accounts = MutableStateFlow(if (signedIn) listOf(account("u1")) else emptyList())
    override val active = MutableStateFlow(if (signedIn) account("u1") else null)

    /** Errors the "server" raises, in order, before the authenticator runs. */
    val serverErrors = ArrayDeque<Exception>()
    var signedInAs = "u1"
    var added = 0

    override fun beginSignIn(mode: SignInMode) = "https://auth.example/authorize"

    override suspend fun completeSignIn(redirectUrl: String): StudioAccount = account(signedInAs).also { active.value = it }

    override fun switchTo(userId: String) = Unit

    override fun setActiveChannel(channelId: String?) = Unit

    override suspend fun signOut(userId: String) = "https://auth.example/logout"

    override suspend fun accessToken(): String? = active.value?.let { "tok" }

    override suspend fun signInWithPasskey(authenticator: PasskeyAuthenticator): StudioAccount {
        serverErrors.removeFirstOrNull()?.let { throw it }
        authenticator.signIn("{}")
        return completeSignIn("alummahnow-studio://auth/callback?code=c&state=s")
    }

    override suspend fun addPasskey(authenticator: PasskeyAuthenticator, name: String?): RegisteredPasskey {
        serverErrors.removeFirstOrNull()?.let { throw it }
        authenticator.create("{}")
        added++
        return RegisteredPasskey("pk$added", name, null)
    }
}
