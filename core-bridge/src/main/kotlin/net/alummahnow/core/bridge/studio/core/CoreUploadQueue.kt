package net.alummahnow.core.bridge.studio.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.alummahnow.core.ApiClient
import net.alummahnow.core.AunException
import net.alummahnow.core.UploadEngine
import net.alummahnow.core.UploadInfo
import net.alummahnow.core.UploadRequest
import net.alummahnow.core.UploadState
import net.alummahnow.core.bridge.AunLog
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
 * copy is deleted when the upload ends.
 *
 * After the engine finishes, the values `upload/initiate` does not take are applied: language
 * (`PUT /api/videos/{id}`), playlists (`/api/playlists/upload-adds`) and the schedule
 * (`POST /api/videos/{id}/schedule`), as the web uploader does.
 *
 * The OS-level part (a WorkManager foreground worker that keeps the process alive and shows the
 * progress notification) lives in `:feature-upload` and calls [process].
 */
class CoreUploadQueue(
    context: Context,
    private val activeUserId: () -> String?,
    private val clientFor: (userId: String) -> ApiClient,
    private val repository: () -> StudioRepository,
    /** Starts the background worker. */
    private val onWorkNeeded: () -> Unit
) : UploadQueue {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "uploads").apply { mkdirs() }
    private val staged = File(app.filesDir, "upload-staging").apply { mkdirs() }
    private val stateFile = File(root, "queue.json")
    private val engines = mutableMapOf<String, UploadEngine>()
    private val mutex = Mutex()
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
    private data class State(val pending: List<Pending> = emptyList(), val followUps: List<FollowUp> = emptyList())

    private fun load(): State = runCatching { json.decodeFromString<State>(stateFile.readText()) }.getOrDefault(State())

    private fun save(s: State) {
        val tmp = File(root, "queue.json.tmp")
        tmp.writeText(json.encodeToString(s))
        if (!tmp.renameTo(stateFile)) {
            stateFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun engine(userId: String): UploadEngine = synchronized(engines) {
        engines.getOrPut(userId) {
            val dir = File(root, userId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }
            UploadEngine(clientFor(userId), dir.absolutePath, userId, uploadConfigDefault(), AndroidConnectivity(app))
        }
    }

    override suspend fun enqueue(drafts: List<UploadDraft>) {
        val userId = activeUserId() ?: throw net.alummahnow.core.bridge.CoreError.NotSignedIn()
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
                save(s.copy(pending = s.pending + added))
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
        stagePending()
        while (true) {
            applyFollowUps()
            val now = refresh()
            onUpdate(now)
            if (now.none { it.isActive } && load().pending.isEmpty()) break
            delay(POLL_MS)
        }
    }

    /** True when the worker has something to do (used at app start to resume after a reboot). */
    fun hasWork(): Boolean {
        val s = load()
        return s.pending.isNotEmpty() || s.followUps.any { !it.applied } || refresh().any { it.isActive }
    }

    private suspend fun stagePending() = withContext(Dispatchers.IO) {
        while (true) {
            val next = mutex.withLock { load().pending.firstOrNull() } ?: break
            val result = runCatching { stage(next) }
            mutex.withLock {
                val s = load()
                val followUps = result.getOrNull()?.let { s.followUps + it } ?: s.followUps
                save(s.copy(pending = s.pending.filterNot { it.localId == next.localId }, followUps = followUps))
            }
            result.onFailure { AunLog.e("upload", "could not queue file", it) }
        }
    }

    private fun stage(p: Pending): FollowUp {
        val ext = p.fileName.substringAfterLast('.', "").takeIf { it.length in 1..MAX_EXT }?.let { ".$it" } ?: ".mp4"
        val target = File(staged, p.localId + ext)
        val uri = Uri.parse(p.source)
        if (uri.scheme == "content") {
            app.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open the picked file" }
                target.outputStream().use { input.copyTo(it, COPY_BUFFER) }
            }
        } else {
            File(uri.path ?: p.source).copyTo(target, overwrite = true)
        }
        val id = try {
            engine(p.userId).enqueue(
                UploadRequest(
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
            )
        } catch (e: AunException) {
            target.delete()
            throw mapCoreError(e)
        }
        return FollowUp(id, target.absolutePath, p)
    }

    private suspend fun applyFollowUps() {
        val todo = mutex.withLock { load().followUps }.filterNot { it.applied }
        for (f in todo) {
            if (settle(f)) {
                mutex.withLock {
                    val s = load()
                    save(s.copy(followUps = s.followUps.map { if (it.uploadId == f.uploadId) it.copy(applied = true) else it }))
                }
            }
        }
    }

    /** True once [f] needs nothing more: its upload ended (settings applied when it succeeded). */
    private suspend fun settle(f: FollowUp): Boolean {
        val info = runCatching { engine(f.draft.userId).get(f.uploadId) }.getOrNull()
        val state = info?.state
        if (state != null && state !in TERMINAL) return false
        File(f.stagedPath).delete()
        val videoId = info?.videoId
        if (state != UploadState.DONE || videoId == null) return true
        return runCatching { applyAfterUpload(videoId, f) }
            .onFailure { AunLog.e("upload", "post-upload settings failed", it) }
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

    private fun refresh(): List<UploadItem> {
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

    private fun withEngine(block: UploadEngine.() -> Unit) {
        val userId = activeUserId() ?: return
        runCatching { engine(userId).block() }.onFailure { AunLog.e("upload", "action failed", it) }
        refresh()
    }

    override fun pause(id: String) = withEngine { pause(id) }
    override fun resume(id: String) {
        withEngine { resume(id) }
        onWorkNeeded()
    }
    override fun retry(id: String) {
        withEngine { retry(id) }
        onWorkNeeded()
    }

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

    override fun remove(id: String) = withEngine { remove(id) }

    private companion object {
        const val POLL_MS = 1_000L
        const val BATCH_ID_LENGTH = 12
        const val MAX_EXT = 5
        const val COPY_BUFFER = 1 shl 20
        val TERMINAL = setOf(UploadState.DONE, UploadState.FAILED, UploadState.CANCELLED)
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
