package net.alummahnow.studio

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.SignInMode
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.core.bridge.studio.deletion.AccountDeletion
import net.alummahnow.core.bridge.studio.deletion.DeletionAnswer
import net.alummahnow.core.bridge.studio.deletion.DeletionApi
import net.alummahnow.core.bridge.studio.deletion.DeletionDevice
import net.alummahnow.core.bridge.studio.deletion.DeletionRecord
import net.alummahnow.core.bridge.studio.deletion.DeletionRecords
import net.alummahnow.core.bridge.studio.deletion.DeletionToken

/** A `T` whose every call fails: the fakes below answer only what the app's shell needs. */
private inline fun <reified T : Any> unused(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ -> throw CoreError.Unavailable(m.name) } as T

fun studioAccount(id: String) = StudioAccount(id, "Account $id", "$id@example.org", null, null, false)

class FakeSession(vararg ids: String) : AccountSession {
    override val accounts = MutableStateFlow(ids.map(::studioAccount))
    override val active = MutableStateFlow<StudioAccount?>(accounts.value.first())
    var signOutError: Exception? = null

    override fun beginSignIn(mode: SignInMode) = "https://auth.example.org/authorize"
    var signsInAs: String? = null
    override suspend fun completeSignIn(redirectUrl: String): StudioAccount {
        val id = signsInAs ?: throw CoreError.Unavailable("sign-in")
        if (accounts.value.none { it.userId == id }) accounts.value += studioAccount(id)
        switchTo(id)
        authTime = 1_000_000L - 5
        return active.value!!
    }
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
    var authTime = 1_000_000L - 10
    override suspend fun accessToken(): String? = active.value?.let { accessTokenOf(it.userId, authTime) }
    override val logoutUrl = "https://auth.example.org/logout"
    override fun beginStepUp(maxAgeSeconds: Int) = "https://auth.example.org/authorize?max_age=$maxAgeSeconds&state=st-1"
    override suspend fun removeLocally(userId: String) {
        accounts.value = accounts.value.filterNot { it.userId == userId }
        if (active.value?.userId == userId) active.value = accounts.value.firstOrNull()
    }
}

/** One account's server side. */
class FakeStudioRepository(private val channel: StudioChannel) : StudioRepository by unused() {
    var termsAccepted: Boolean? = true
    var termsVersion: String? = "v1"
    var reads = 0
    var accepts = 0
    override suspend fun channels() = listOf(channel)
    override suspend fun features(channelId: String?) = UserFeatures(setOf(UserFeatures.UPLOAD))
    override suspend fun playlists(channelId: String): List<StudioPlaylist> = emptyList()
    override suspend fun creatorAccess(): CreatorAccess {
        reads++
        return CreatorAccess(
            canUpload = true,
            canCreateChannel = true,
            tierName = null,
            termsAccepted = termsAccepted,
            termsVersion = termsVersion
        )
    }
    val acceptedVersions = mutableListOf<String>()
    override suspend fun acceptCreatorTerms(version: String) {
        accepts++
        acceptedVersions += version
        if (version != termsVersion) throw StudioError.TermsChanged()
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
    val held = mutableSetOf<String>()
    val purged = mutableListOf<String>()
    var purgeError: Exception? = null
    override suspend fun holdUploads(userId: String) {
        held += userId
    }
    override suspend fun releaseUploads(userId: String) {
        held -= userId
    }
    override suspend fun purgeAccount(userId: String) {
        purgeError?.let { throw it }
        purged += userId
    }
}

/** Auth's access token as it really is: `sub` is not the account's id. */
fun accessTokenOf(userId: String, authTime: Long): String {
    val enc = java.util.Base64.getUrlEncoder().withoutPadding()
    fun part(json: String) = enc.encodeToString(json.toByteArray())
    return part("""{"alg":"ES256"}""") + "." +
        part("""{"sub":"user:3f2a9c8b10294e1f","properties":{"id":"$userId"},"auth_time":$authTime}""") + ".sig"
}

class FakeDeletionApi : DeletionApi {
    val calls = mutableListOf<String>()
    var now = 1_000_000L
    val erases = ArrayDeque<DeletionAnswer>()
    var account = DeletionAnswer(200, """{"deleted":true}""")
    override suspend fun erase(token: String, resumeToken: String?): DeletionAnswer {
        calls += "erase ${DeletionToken.userIdOf(token)} ${resumeToken ?: "-"}"
        return erases.removeFirstOrNull() ?: DeletionAnswer(200, """{"success":true,"complete":true}""")
    }
    override suspend fun deleteAccount(token: String): DeletionAnswer {
        calls += "account ${DeletionToken.userIdOf(token)}"
        return account
    }
    override suspend fun serverNowSeconds() = now
}

class MemoryRecords : DeletionRecords {
    override val records = MutableStateFlow<List<DeletionRecord>>(emptyList())
    override fun get(userId: String) = records.value.firstOrNull { it.userId == userId }
    override fun put(record: DeletionRecord) {
        records.value = records.value.filterNot { it.userId == record.userId } + record
    }
    override fun remove(userId: String) {
        records.value = records.value.filterNot { it.userId == userId }
    }
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
    override val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val deletionRecords = MemoryRecords()
    val deletionApi = FakeDeletionApi()
    override val deletion = AccountDeletion(
        deletionRecords,
        deletionApi,
        object : DeletionDevice {
            override suspend fun token(userId: String) = if (session.active.value?.userId == userId) session.accessToken() else null
            override suspend fun holdUploads(userId: String) = uploads.holdUploads(userId)
            override suspend fun releaseUploads(userId: String) = uploads.releaseUploads(userId)
            override suspend fun purgeUploads(userId: String) = uploads.purgeAccount(userId)
            override suspend fun endSession(userId: String) = session.removeLocally(userId)
        }
    )
    override fun repositoryFor(userId: String) = repositories.getValue(userId)
    override fun forget(userId: String) {
        forgotten += userId
    }
}
