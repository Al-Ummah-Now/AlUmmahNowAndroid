package net.alummahnow.core.bridge.studio.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.alummahnow.core.ApiClient
import net.alummahnow.core.AunException
import net.alummahnow.core.UploadEngine
import net.alummahnow.core.UploadEngineInterface
import net.alummahnow.core.UploadInfo
import net.alummahnow.core.UploadRequest
import net.alummahnow.core.UploadState
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.core.bridge.studio.Visibility
import net.alummahnow.core.uploadConfigDefault

/**
 * [UploadQueue] over aun-upload's resumable TUS engine (one engine per account, state in
 * `files/uploads/<account>`). A picked `content://` video is staged into app storage first because
 * the engine reads files by path and must find them again after the process restarts; the staged
 * copy is deleted when the upload is done, cancelled or removed. A failed upload keeps it: the person can retry.
 *
 * After the engine finishes, the values `upload/initiate` does not take are applied: language
 * (`PUT /api/videos/{id}`), playlists (`/api/playlists/upload-adds`) and the schedule
 * (`POST /api/videos/{id}/schedule`), as the web uploader does.
 *
 * The OS-level part (a job or foreground worker that keeps the process alive and shows the
 * progress notification) lives in `:feature-upload` and calls [process].
 */
class CoreUploadQueue(
    context: Context,
    private val activeUserId: () -> String?,
    private val clientFor: (userId: String) -> ApiClient,
    private val repository: () -> StudioRepository,
    /** Schedules processing of the durable queue. */
    private val onWorkNeeded: () -> Unit,
    newEngine: ((userId: String, dir: File) -> UploadEngineInterface)? = null,
    private val holdTimeoutMs: Long = HOLD_TIMEOUT_MS,
    /** The account may be deleted (the answer to its deletion is missing): nothing of it starts until that is known. */
    private val blocked: (userId: String) -> Boolean = { false },
    transfersAllowed: () -> Boolean = { true }
) : UploadQueue {
    private val app = context.applicationContext
    private val newEngine = newEngine ?: { userId, dir ->
        UploadEngine(clientFor(userId), dir.absolutePath, userId, uploadConfigDefault(), AndroidConnectivity(app, transfersAllowed))
    }
    private val root = File(app.filesDir, "uploads").apply { mkdirs() }
    private val staged = File(app.filesDir, "upload-staging").apply { mkdirs() }
    private val stateFile = File(root, "queue.json")
    private val engines = mutableMapOf<String, UploadEngineInterface>()
    private val mutex = Mutex()

    /** Deleted accounts: nothing of theirs is staged, uploaded or written back. Saved with the queue. */
    private val purgedUsers = mutableSetOf<String>()

    /** Accounts whose uploads wait, and what was paused for it. Saved: a hold outlives the process. */
    private val held = mutableMapOf<String, MutableSet<String>>()
    private val heldFile = File(root, "held.json")

    /** Holds an earlier process left. */
    private val leftHeld = mutableSetOf<String>()

    /** Held while a finished upload's settings are applied. */
    private val settling = Mutex()
    private val json = J.json

    private val mutableItems = MutableStateFlow<List<UploadItem>>(emptyList())
    override val items: StateFlow<List<UploadItem>> = mutableItems.asStateFlow()

    @Serializable
    private data class Pending(
        val localId: String,
        val userId: String,
        val source: String,
        val fileName: String,
        val mimeType: String?,
        val title: String,
        val description: String,
        val channelId: String,
        val isPublic: Boolean,
        val category: String?,
        val language: String?,
        val tags: List<String>,
        val playlistIds: List<String>,
        val scheduleAtIso: String?,
        val timezone: String?,
        val batchId: String,
        val index: Int
    )

    /** What to do after an engine upload finishes (keyed by engine upload id). */
    @Serializable
    private data class FollowUp(val uploadId: String, val stagedPath: String, val draft: Pending, val applied: Boolean = false)

    @Serializable
    private data class State(
        val pending: List<Pending> = emptyList(),
        val followUps: List<FollowUp> = emptyList(),
        val purgedUsers: Set<String> = emptySet()
    )

    init {
        synchronized(purgedUsers) { purgedUsers += load().purgedUsers }
        synchronized(held) {
            runCatching { json.decodeFromString<Map<String, Set<String>>>(heldFile.readText()) }.getOrNull()?.forEach { (user, ids) ->
                held[user] = ids.toMutableSet()
                leftHeld += user
            }
        }
    }

    /** Called under [held]'s lock. */
    private fun saveHeld() {
        runCatching { heldFile.writeText(json.encodeToString<Map<String, Set<String>>>(held)) }
            .onFailure { AunLog.e("upload", "held uploads not saved", it) }
    }

    /** Lets uploads go on that an earlier process held and didn't release, unless their account may be deleted. */
    suspend fun releaseLeft() {
        synchronized(held) { leftHeld.toList() }.forEach { releaseUploads(it, leftOnly = true) }
    }

    private fun isPurged(userId: String): Boolean = synchronized(purgedUsers) { userId in purgedUsers }

    private fun isHeld(userId: String): Boolean = synchronized(held) { userId in held } || blocked(userId)

    private fun load(): State = runCatching { json.decodeFromString<State>(stateFile.readText()) }.getOrDefault(State())

    private fun save(s: State) {
        val tmp = File(root, "queue.json.tmp")
        tmp.writeText(json.encodeToString(s))
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun engineDir(userId: String) = File(root, userId.replace(Regex("[^A-Za-z0-9_-]"), "_"))

    private fun engine(userId: String): UploadEngineInterface = synchronized(engines) {
        if (isPurged(userId)) throw CoreError.NotSignedIn()
        engines.getOrPut(userId) {
            // Opening the engine can start queued uploads, even if the caller only lists them.
            check(!blocked(userId)) { "account deletion outcome is unknown" }
            newEngine(userId, engineDir(userId).apply { mkdirs() })
        }
    }

    override suspend fun enqueue(drafts: List<UploadDraft>) {
        val userId = activeUserId() ?: throw CoreError.NotSignedIn()
        val batch = UUID.randomUUID().toString().take(BATCH_ID_LENGTH)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val s = load()
                val added = drafts.mapIndexed { i, d ->
                    val uri = Uri.parse(d.source)
                    if (uri.scheme == "content") {
                        runCatching {
                            app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    Pending(
                        localId = UUID.randomUUID().toString(),
                        userId = userId,
                        source = d.source,
                        fileName = d.fileName,
                        mimeType = d.mimeType,
                        title = d.title,
                        description = d.description,
                        channelId = d.channelId,
                        // A scheduled video uploads private and goes public at its time.
                        isPublic = d.visibility == Visibility.PUBLIC && d.scheduleAtIso == null,
                        category = d.category,
                        language = d.language,
                        tags = d.tags,
                        playlistIds = d.playlistIds,
                        scheduleAtIso = d.scheduleAtIso,
                        timezone = d.timezone,
                        batchId = batch,
                        index = i
                    )
                }
                // Signed in and uploading: the account is live (again).
                synchronized(purgedUsers) { purgedUsers -= userId }
                save(s.copy(pending = s.pending + added, purgedUsers = s.purgedUsers - userId))
            }
        }
        AunLog.i("upload", "queued", "count" to drafts.size)
        refresh()
        onWorkNeeded()
    }

    /**
     * Runs from the background worker: stages and hands pending files to the engine, then waits
     * until nothing is active, applying follow-ups. [onUpdate] is called about once a second.
     */
    suspend fun process(onUpdate: (List<UploadItem>) -> Unit) {
        while (true) {
            // Every round: a batch queued while another uploads is staged too.
            stagePending()
            applyFollowUps()
            val now = refresh()
            onUpdate(now)
            // The notification follows the selected account, but the job also owns transfers from other accounts.
            val selected = activeUserId()
            val otherTransfers = synchronized(engines) { engines.toMap() }.any { (userId, engine) ->
                userId != selected && !isHeld(userId) && runCatching { engine.list().any { it.state in RUNNING } }.getOrDefault(false)
            }
            val selectedTransfers = now.any { it.isActive && it.phase != UploadPhase.PREPARING }
            if (!otherTransfers && !selectedTransfers && load().pending.none { !isHeld(it.userId) }) {
                // A transfer can finish after the first settling pass and before the last activity check.
                applyFollowUps()
                break
            }
            delay(POLL_MS)
        }
    }

    /** True when the worker has something to do (used at app start to resume after a reboot). */
    fun hasWork(): Boolean {
        val s = load()

        fun waitsForUser(f: FollowUp): Boolean {
            val state = runCatching { engine(f.draft.userId).get(f.uploadId)?.state }.getOrNull()
            return state == UploadState.PAUSED || state == UploadState.FAILED
        }
        return s.pending.any { !isHeld(it.userId) } ||
            s.followUps.any { !it.applied && !isHeld(it.draft.userId) && !waitsForUser(it) } ||
            refresh().any { it.isActive && it.phase != UploadPhase.PREPARING }
    }

    private suspend fun stagePending() = withContext(Dispatchers.IO) {
        while (true) {
            ensureActive()
            val next = mutex.withLock { load().pending.firstOrNull { !isPurged(it.userId) && !isHeld(it.userId) } } ?: break
            val result = runCatching { stage(next) }
            if (result.exceptionOrNull() is CancellationException) result.getOrThrow()
            // Once the engine has accepted a file, persist its mapping even if the job is stopping.
            withContext(NonCancellable) {
                val purged = mutex.withLock {
                    val s = load()
                    // Purged while it was copied: nothing of it is written back.
                    val gone = isPurged(next.userId)
                    val followUps = result.getOrNull()?.takeUnless { gone }?.let { s.followUps + it } ?: s.followUps
                    val waits = result.isSuccess && result.getOrNull() == null && !gone
                    val pending = if (waits) s.pending else s.pending.filterNot { it.localId == next.localId }
                    save(s.copy(pending = pending, followUps = followUps))
                    gone
                }
                if (purged) {
                    result.getOrNull()?.let { File(it.stagedPath).delete() }
                    removeEngine(next.userId)
                }
            }
            result.onFailure { AunLog.e("upload", "could not queue file", it) }
        }
    }

    /** Null when its account's uploads are held: then nothing of it was handed on. */
    private suspend fun stage(p: Pending): FollowUp? {
        val ext = p.fileName.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXT }?.let { ".$it" } ?: ".mp4"
        val target = File(staged, p.localId + ext)
        var queued = false
        val id = try {
            copySource(p.source, target)
            currentCoroutineContext().ensureActive()
            val request = UploadRequest(
                filePath = target.absolutePath,
                title = p.title,
                description = p.description.ifBlank { null },
                mimeType = p.mimeType,
                channelId = p.channelId,
                isPublic = p.isPublic,
                tags = p.tags.joinToString(",").ifBlank { null },
                category = p.category,
                titleLocalized = emptyMap(),
                descriptionLocalized = emptyMap(),
                priority = 0
            )
            // Held while it was copied: it stays in the queue, and is copied again afterwards.
            synchronized(held) { if (isHeld(p.userId)) null else engine(p.userId).enqueue(request) }.also { queued = it != null }
        } catch (e: AunException) {
            throw mapCoreError(e)
        } finally {
            // Whatever stopped it (no space, a purge, a refused file), no copy is left that nothing will send.
            if (!queued) target.delete()
        }
        return id?.let { FollowUp(it, target.absolutePath, p) }
    }

    private suspend fun copySource(path: String, target: File) {
        val uri = Uri.parse(path)
        val source = if (uri.scheme == "content") {
            requireNotNull(app.contentResolver.openInputStream(uri)) { "cannot open the picked file" }
        } else {
            File(uri.path ?: path).inputStream()
        }
        source.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private suspend fun applyFollowUps() {
        val todo = mutex.withLock { load().followUps }.filterNot { it.applied }
        for (f in todo) {
            if (settling.withLock { !isHeld(f.draft.userId) && settle(f) }) {
                mutex.withLock {
                    val s = load()
                    save(s.copy(followUps = s.followUps.map { if (it.uploadId == f.uploadId) it.copy(applied = true) else it }))
                }
            }
        }
    }

    /** True once [f] needs nothing more: its upload ended (settings applied when it succeeded). */
    private suspend fun settle(f: FollowUp): Boolean {
        if (isPurged(f.draft.userId)) {
            File(f.stagedPath).delete()
            return true
        }
        // Not known is not ended: its staged file is still needed.
        val known = runCatching { engine(f.draft.userId).get(f.uploadId) }
        val info = known.getOrNull()
        val state = info?.state
        if (known.isFailure || (state != null && state !in ENDED)) return false
        File(f.stagedPath).delete()
        val videoId = info?.videoId
        if (state != UploadState.DONE || videoId == null) return true
        return runCatching { applyAfterUpload(videoId, f) }
            .onFailure {
                if (it is CancellationException) throw it
                AunLog.e("upload", "post-upload settings failed", it)
            }
            .isSuccess
    }

    private suspend fun applyAfterUpload(videoId: String, f: FollowUp) {
        val d = f.draft
        val repo = repository()
        if (d.language != null) repo.update(videoId, d.channelId, VideoEdit(language = d.language))
        if (d.playlistIds.isNotEmpty()) repo.addToPlaylists(videoId, d.channelId, d.playlistIds, "${d.batchId}-${d.index}")
        if (d.scheduleAtIso != null) repo.schedule(videoId, d.channelId, d.scheduleAtIso, d.timezone ?: "UTC")
        AunLog.i("upload", "post-upload settings applied")
    }

    /** Re-reads the active account's queue (also after an account switch, so no other account's rows stay). */
    override fun refresh(): List<UploadItem> {
        val userId = activeUserId()
        val s = load()
        val preparing = s.pending.filter { it.userId == userId }.map {
            UploadItem(it.localId, it.title, UploadPhase.PREPARING, 0, 0, null, null)
        }
        val fromEngine = if (userId == null) {
            emptyList()
        } else {
            runCatching { engine(userId).list() }.getOrDefault(emptyList()).map { it.toItem() }
        }
        val all = preparing + fromEngine
        mutableItems.value = all
        return all
    }

    private fun withEngine(block: UploadEngineInterface.() -> Unit) {
        val userId = activeUserId() ?: return
        runCatching { engine(userId).block() }.onFailure { AunLog.e("upload", "action failed", it) }
        refresh()
    }

    override fun pause(id: String) {
        activeUserId()?.let { user -> synchronized(held) { if (held[user]?.remove(id) == true) saveHeld() } }
        withEngine { pause(id) }
    }

    override fun resume(id: String) = startAgain(id)
    override fun retry(id: String) = startAgain(id)

    override suspend fun cancel(id: String) {
        val userId = activeUserId() ?: return
        mutex.withLock {
            val s = load()
            if (s.pending.any { it.localId == id }) {
                save(s.copy(pending = s.pending.filterNot { it.localId == id }))
                refresh()
                return
            }
        }
        withContext(Dispatchers.IO) { runCatching { engine(userId).cancel(id) }.onFailure { AunLog.e("upload", "cancel failed", it) } }
        refresh()
    }

    override fun remove(id: String) {
        val userId = activeUserId() ?: return
        withEngine { remove(id) }
        // The engine forgot it, so nothing will retry it: its staged copy and its row go too.
        if (mutableItems.value.none { it.id == id }) {
            val s = load()
            s.followUps.firstOrNull { it.uploadId == id && it.draft.userId == userId }?.let { f ->
                File(f.stagedPath).delete()
                save(s.copy(followUps = s.followUps - f))
            }
        }
    }

    override suspend fun holdUploads(userId: String) {
        // From here on nothing new reaches the engine: [stage], [resume] and [retry] look under the same lock.
        synchronized(held) {
            leftHeld -= userId
            held.getOrPut(userId) { mutableSetOf() }
            saveHeld()
        }
        // Purged: nothing of it is there, and nothing of it starts.
        if (isPurged(userId)) return
        val quiet = withContext(Dispatchers.IO) {
            withTimeoutOrNull(holdTimeoutMs) {
                // A finished upload's settings that are being applied are waited for.
                settling.withLock { }
                var engine = engine(userId)
                do {
                    val running = engine.list().filter { it.state in RUNNING }
                    running.forEach { info ->
                        synchronized(held) { if (held[userId]?.add(info.id) == true) saveHeld() }
                        engine.pause(info.id)
                    }
                    // A paused upload's request may still be out, and its answer may start it again.
                    engine = reopen(userId, engine)
                    val again = engine.list().any { it.state in RUNNING }
                    if (again) delay(HOLD_POLL_MS)
                } while (again)
                true
            }
        }
        refresh()
        // Not a cancellation: the caller is told.
        checkNotNull(quiet) { "uploads still under way" }
    }

    /** Replaces [userId]'s engine. Its store can only be opened again when the last request of the old one has ended. */
    private suspend fun reopen(userId: String, old: UploadEngineInterface): UploadEngineInterface {
        synchronized(engines) {
            engines.remove(userId)
            old.shutdown()
        }
        while (true) {
            val opened = runCatching { engine(userId) }
            opened.getOrNull()?.let { return it }
            if (isPurged(userId)) throw CoreError.NotSignedIn()
            AunLog.i("upload", "waiting for a request to end", "type" to opened.exceptionOrNull()?.javaClass?.simpleName)
            delay(HOLD_POLL_MS)
        }
    }

    override suspend fun releaseUploads(userId: String) = releaseUploads(userId, leftOnly = false)

    private suspend fun releaseUploads(userId: String, leftOnly: Boolean) {
        val released = withContext(Dispatchers.IO) {
            synchronized(held) {
                // A new deletion may have taken over since startup listed the holds. Keep the check and
                // engine resumes under the same lock that acquires a hold.
                if ((leftOnly && userId !in leftHeld) || blocked(userId)) return@synchronized false
                val paused = held.remove(userId) ?: return@synchronized false
                leftHeld -= userId
                saveHeld()
                runCatching { engine(userId) }.getOrNull()?.let { engine ->
                    paused.forEach { id -> runCatching { engine.goOn(id) } }
                }
                true
            }
        }
        if (released) {
            refresh()
            onWorkNeeded()
        }
    }

    private fun UploadEngineInterface.goOn(id: String) = if (get(id)?.state == UploadState.FAILED) retry(id) else resume(id)

    /** Starts [id] again, or later when its account's uploads are held. */
    private fun startAgain(id: String) {
        val userId = activeUserId() ?: return
        synchronized(held) {
            if (isHeld(userId)) {
                held.getOrPut(userId) { mutableSetOf() }.add(id)
                saveHeld()
            } else {
                withEngine { goOn(id) }
            }
        }
        onWorkNeeded()
    }

    override suspend fun purgeAccount(userId: String) {
        synchronized(purgedUsers) { purgedUsers += userId }
        withContext(Dispatchers.IO) {
            val gone = mutex.withLock {
                // A queue that can't be read is not an empty one: saving it would remove the other accounts' uploads.
                val s = if (stateFile.exists()) json.decodeFromString<State>(stateFile.readText()) else State()
                save(
                    s.copy(
                        pending = s.pending.filterNot { it.userId == userId },
                        followUps = s.followUps.filterNot { it.draft.userId == userId },
                        purgedUsers = s.purgedUsers + userId
                    )
                )
                s
            }
            val followUps = gone.followUps.filter { it.draft.userId == userId }
            val sources = gone.pending.filter { it.userId == userId }.map { it.source } + followUps.map { it.draft.source }
            removeEngine(userId)
            followUps.forEach { File(it.stagedPath).delete() }
            val others = gone.pending + gone.followUps.map { it.draft }
            val shared = others.filter { it.userId != userId }.map { it.source }.toSet()
            sources.distinct().filterNot { it in shared }.map(Uri::parse).filter { it.scheme == "content" }.forEach { uri ->
                runCatching {
                    app.contentResolver.releasePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        synchronized(held) {
            leftHeld -= userId
            held.remove(userId)
            saveHeld()
        }
        refresh()
    }

    /** Stops [userId]'s engine and removes what it kept on disk. */
    private suspend fun removeEngine(userId: String) {
        val engine = synchronized(engines) { engines.remove(userId) }
        if (engine != null) {
            runCatching { engine.list() }.getOrDefault(emptyList()).forEach { info -> runCatching { engine.cancel(info.id) } }
            runCatching { engine.shutdown() }
            runCatching { (engine as? AutoCloseable)?.close() }
        }
        engineDir(userId).deleteRecursively()
    }

    private companion object {
        const val POLL_MS = 1_000L

        /** Longer than a request may take, shorter than anyone waits for a button. */
        const val HOLD_TIMEOUT_MS = 60_000L
        const val HOLD_POLL_MS = 200L
        const val BATCH_ID_LENGTH = 12
        const val MAX_EXT = 5
        const val COPY_BUFFER = 1 shl 20

        /** Nothing more can happen to it. A failed upload is not one: it can be retried, with its staged file. */
        val ENDED = setOf(UploadState.DONE, UploadState.CANCELLED)
        val RUNNING = setOf(
            UploadState.QUEUED,
            UploadState.WAITING,
            UploadState.HASHING,
            UploadState.INITIATING,
            UploadState.UPLOADING,
            UploadState.COMPLETING
        )
    }
}

internal fun UploadInfo.toItem(): UploadItem = UploadItem(
    id = id,
    title = title,
    phase = when (state) {
        UploadState.QUEUED -> UploadPhase.QUEUED
        UploadState.WAITING -> UploadPhase.WAITING
        UploadState.HASHING, UploadState.INITIATING, UploadState.UPLOADING -> UploadPhase.UPLOADING
        UploadState.COMPLETING -> UploadPhase.FINISHING
        UploadState.PAUSED -> UploadPhase.PAUSED
        UploadState.DONE -> UploadPhase.DONE
        UploadState.FAILED -> UploadPhase.FAILED
        UploadState.CANCELLED -> UploadPhase.CANCELLED
    },
    sentBytes = sentBytes.toLong(),
    totalBytes = totalBytes.toLong(),
    videoId = videoId,
    error = lastError
)

/** Display name and size of a picked document. */
fun Context.describeDocument(uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "video"
    var size = 0L
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return name to size
}
