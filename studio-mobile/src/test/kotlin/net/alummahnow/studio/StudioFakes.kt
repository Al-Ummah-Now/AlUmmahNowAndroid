package net.alummahnow.studio

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.UserFeatures

/** A `T` whose every call fails: the fakes below answer only what the app's shell needs. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> throw CoreError.Unavailable(m.name) } as T

fun studioAccount(id: String) = StudioAccount(id, "Account $id", "$id@example.org", null, null, false)

class FakeSession(vararg ids: String) : AccountSession {
    override val accounts = MutableStateFlow(ids.map(::studioAccount))
    override val active = MutableStateFlow<StudioAccount?>(accounts.value.first())
    var signOutError: Exception? = null

    override fun beginSignIn(mode: SignInMode) = "https://auth.example.org/authorize"
    override suspend fun completeSignIn(redirectUrl: String): StudioAccount = throw CoreError.Unavailable("sign-in")
    override fun switchTo(userId: String) {
        active.value = accounts.value.first { it.userId == userId }
    }
    override fun setActiveChannel(channelId: String?) = Unit
    override suspend fun signOut(userId: String): String {
        signOutError?.let { throw it }
        accounts.value = accounts.value.filterNot { it.userId == userId }
        if (active.value?.userId == userId) active.value = accounts.value.firstOrNull()
        return "https://auth.example.org/logout"
    }
    override suspend fun accessToken(): String? = "token"
}

/** One account's server side. */
class FakeStudioRepository(private val channel: StudioChannel) : StudioRepository by unused() {
    var termsAccepted: Boolean? = true
    var reads = 0
    var accepts = 0
    override suspend fun channels() = listOf(channel)
    override suspend fun features(channelId: String?) = UserFeatures(setOf(UserFeatures.UPLOAD))
    override suspend fun playlists(channelId: String): List<StudioPlaylist> = emptyList()
    override suspend fun creatorAccess(): CreatorAccess {
        reads++
        return CreatorAccess(canUpload = true, canCreateChannel = true, tierName = null, termsAccepted = termsAccepted)
    }
    override suspend fun acceptCreatorTerms() {
        accepts++
        termsAccepted = true
    }
}

class FakeUploadQueue(private val session: FakeSession) : UploadQueue {
    val byUser = mutableMapOf<String, List<UploadItem>>()
    override val items = MutableStateFlow<List<UploadItem>>(emptyList())
    val retried = mutableListOf<String>()
    override fun refresh(): List<UploadItem> = byUser[session.active.value?.userId].orEmpty().also { items.value = it }
    override suspend fun enqueue(drafts: List<UploadDraft>) = Unit
    override fun pause(id: String) = Unit
    override fun resume(id: String) = Unit
    override fun retry(id: String) {
        retried += id
    }
    override suspend fun cancel(id: String) = Unit
    override fun remove(id: String) = Unit
}

class FakeContainer(base: File, override val session: FakeSession, private val repositories: Map<String, FakeStudioRepository>) :
    StudioContainer {
    override val repository: StudioRepository = object : StudioRepository by unused() {
        private val active get() = repositories.getValue(session.active.value!!.userId)
        override suspend fun channels() = active.channels()
        override suspend fun features(channelId: String?) = active.features(channelId)
        override suspend fun playlists(channelId: String) = active.playlists(channelId)
        // The Terms are never read or recorded through this one: it acts as whoever is active by then.
    }
    override val uploads = FakeUploadQueue(session)
    override val exports = PendingExports(File(base, "editor-exports"), File(base, "pending.json"))
    val forgotten = mutableListOf<String>()
    override fun repositoryFor(userId: String) = repositories.getValue(userId)
    override fun forget(userId: String) {
        forgotten += userId
    }
}
