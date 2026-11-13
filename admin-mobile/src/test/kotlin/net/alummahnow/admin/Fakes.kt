package net.alummahnow.admin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import net.alummahnow.admin.security.AppLock
import net.alummahnow.admin.security.StepUp
import net.alummahnow.core.bridge.admin.FixtureReviewService
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount

/** A signed-in (or signed-out) session with no browser and no Rust core. */
class FakeSession(signedIn: Boolean = true, private val userId: String = "fx-reviewer") : AccountSession {
    private val account = StudioAccount(userId, "Fixture Reviewer", "reviewer@example.test", null, null, needsReauth = false)
    override val accounts = MutableStateFlow(if (signedIn) listOf(account) else emptyList())
    override val active = MutableStateFlow(if (signedIn) account else null)
    val stepUps = mutableListOf<Int>()
    var signedOut = false

    override fun beginSignIn(mode: SignInMode) = "https://auth.example.test/authorize?client_id=admin"

    override fun beginStepUp(maxAgeSeconds: Int): String {
        stepUps += maxAgeSeconds
        return "https://auth.example.test/authorize?prompt=login&max_age=$maxAgeSeconds"
    }

    override suspend fun completeSignIn(redirectUrl: String): StudioAccount = account.also { active.value = it }

    override fun switchTo(userId: String) = Unit

    override fun setActiveChannel(channelId: String?) = Unit

    override suspend fun signOut(userId: String): String {
        signedOut = true
        active.value = null
        return "https://auth.example.test/logout"
    }

    override suspend fun accessToken(): String? = active.value?.let { "token" }
}

class FakeServices(
    context: Context,
    val fixture: FixtureReviewService = FixtureReviewService(),
    override val session: FakeSession = FakeSession(),
    unlocked: Boolean = true
) : AdminServices {
    override val review = fixture
    override val push = fixture
    override val prefs = AdminPrefs(context)
    override val lock = AppLock().also { if (unlocked) it.unlocked() }
    override val stepUp = StepUp()
}
