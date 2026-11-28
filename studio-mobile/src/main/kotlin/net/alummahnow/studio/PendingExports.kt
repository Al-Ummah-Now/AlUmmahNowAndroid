package net.alummahnow.studio

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.SafeInput
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.feature.upload.PickedFile
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The editor's exports, one folder per account under [root], and the list of those waiting for the Creator Terms
 * ([index]). An account's exports are its own: they stay through a channel switch, an account switch and process
 * death, no other account is handed them, and they are deleted when the account leaves this device.
 */
class PendingExports(private val root: File, private val index: File) {
    /** A waiting export: the [file] for the upload form and the [channelId] it was made in. */
    private data class Row(val userId: String, val channelId: String, val file: PickedFile)

    private val lock = Any()
    private var rows: List<Row> = read()
    private val mutableWaiting = MutableStateFlow(rows.map { it.userId to it.channelId }.toSet())

    /** The (account, channel) pairs with exports waiting. */
    val waiting: StateFlow<Set<Pair<String, String>>> = mutableWaiting.asStateFlow()

    /** Where [userId]'s exports are written. */
    fun dir(userId: String): File {
        val trimmed = userId.trim()
        require(trimmed.isNotEmpty() && trimmed != "." && trimmed != "..") { "invalid userId" }
        val safe = trimmed.replace(Regex("[^A-Za-z0-9_-]"), "_")
        require(safe.any { it.isLetterOrDigit() }) { "invalid userId" }
        return File(root, safe)
    }

    fun add(userId: String, channelId: String, file: PickedFile) = synchronized(lock) {
        if (rows.none { it.userId == userId && it.channelId == channelId && it.file.uri == file.uri }) {
            save(rows + Row(userId, channelId, file))
        }
    }

    /** What was waiting for [userId] in [channelId], taken out of the list. */
    fun take(userId: String, channelId: String): Taken = synchronized(lock) {
        val (mine, others) = rows.partition { it.userId == userId && it.channelId == channelId }
        if (mine.isNotEmpty()) save(others)
        // The cache can be emptied by the system; the person is told instead of being handed a missing file.
        val (there, gone) = mine.partition { SafeInput.isSafeFileName(it.file.name) && File(dir(userId), it.file.name).exists() }
        Taken(there.map { it.file }, gone.size)
    }

    data class Taken(val files: List<PickedFile>, val missing: Int)

    /**
     * Deletes the exports of accounts that left: sign-out, deletion, or a session that ended by itself. An empty
     * list at start proves nothing (the key store may be unreadable), so it deletes nothing; an empty list after
     * one with accounts means the last one left.
     */
    suspend fun follow(accounts: Flow<List<StudioAccount>>) {
        var seen = false
        accounts.collect { list ->
            if (seen || list.isNotEmpty()) keepOnly(list.map { it.userId }.toSet())
            seen = seen || list.isNotEmpty()
        }
    }

    /** Deletes what [userId] exported and never sent, also when its session is gone already. */
    fun remove(userId: String) = synchronized(lock) {
        dir(userId).deleteRecursively()
        if (rows.any { it.userId == userId }) save(rows.filterNot { it.userId == userId })
    }

    internal fun keepOnly(userIds: Set<String>) = synchronized(lock) {
        val keep = userIds.map { dir(it).name }.toSet()
        root.listFiles()?.filterNot { it.name in keep }?.forEach { it.deleteRecursively() }
        if (rows.any { it.userId !in userIds }) save(rows.filter { it.userId in userIds })
    }

    private fun save(next: List<Row>) {
        rows = next
        val out = JSONArray()
        next.forEach { r ->
            out.put(
                JSONObject()
                    .put("userId", r.userId)
                    .put("channelId", r.channelId)
                    .put("uri", r.file.uri)
                    .put("name", r.file.name)
                    .put("size", r.file.sizeBytes)
                    .put("mime", r.file.mimeType ?: JSONObject.NULL)
            )
        }
        val tmp = File(index.path + ".tmp")
        runCatching {
            index.parentFile?.mkdirs()
            tmp.writeText(out.toString())
            check(tmp.renameTo(index)) { "rename" }
        }.onFailure { AunLog.e("editor", "waiting exports not saved", it) }
        mutableWaiting.value = next.map { it.userId to it.channelId }.toSet()
    }

    private fun read(): List<Row> = try {
        if (!index.exists()) {
            emptyList()
        } else {
            val array = JSONArray(index.readText())
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                val mime = if (o.isNull("mime")) null else o.getString("mime")
                Row(
                    o.getString("userId"),
                    o.getString("channelId"),
                    PickedFile(o.getString("uri"), o.getString("name"), o.getLong("size"), mime)
                )
            }
        }
    } catch (e: JSONException) {
        AunLog.e("editor", "waiting exports unreadable", e)
        emptyList()
    }
}
