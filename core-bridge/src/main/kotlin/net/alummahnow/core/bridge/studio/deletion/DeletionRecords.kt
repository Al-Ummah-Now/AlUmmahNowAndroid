package net.alummahnow.core.bridge.studio.deletion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.alummahnow.core.SecureStorage
import net.alummahnow.core.bridge.AunLog

/** What the erase reported it could not remove. A channel it could not read stays until an admin removes it. */
enum class Leftover {
    CHANNEL,
    OTHER;

    companion object {
        fun worse(a: Leftover?, b: Leftover?): Leftover? = if (a == CHANNEL || b == CHANNEL) CHANNEL else a ?: b
    }
}

enum class DeletionStage {
    /** Some content may be gone. */
    STARTED,

    /** An erase over everything finished; the sign-in account is left. */
    CONTENT_ERASED,

    /** Both steps succeeded; what is left is this device's own cleanup. */
    DELETED
}

/** A deletion that started on this device. It has no expiry: one from last week still means content may be gone. */
@Serializable
data class DeletionRecord(
    val userId: String,
    /** What the person calls the account, to name it when nobody is signed in. */
    val label: String,
    val stage: DeletionStage = DeletionStage.STARTED,
    /** Opaque, bound to the account by the server; handed back unread. */
    val resumeToken: String? = null,
    val leftover: Leftover? = null,
    val startedAtMs: Long = 0,
    /** The server asked to wait until then. */
    val notBeforeMs: Long = 0
)

/** The deletions that started on this device, by account. */
interface DeletionRecords {
    val records: StateFlow<List<DeletionRecord>>

    /** Throws when it cannot be read: "unknown" must never look like "nothing started". */
    fun get(userId: String): DeletionRecord?

    /** Throws when it could not be saved. */
    fun put(record: DeletionRecord)

    /** Throws when it could not be saved. */
    fun remove(userId: String)
}

/** [DeletionRecords] in the app's Keystore-encrypted storage (app backup is off). */
class SecureDeletionRecords(private val storage: SecureStorage) : DeletionRecords {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val mutable = MutableStateFlow(
        runCatching { read() }.onFailure { AunLog.e("auth", "deletion records unreadable", it) }.getOrDefault(emptyList())
    )
    override val records: StateFlow<List<DeletionRecord>> = mutable.asStateFlow()

    override fun get(userId: String): DeletionRecord? = synchronized(lock) {
        read().also { mutable.value = it }.firstOrNull { it.userId == userId }
    }

    override fun put(record: DeletionRecord) = synchronized(lock) {
        write(read().filterNot { it.userId == record.userId } + record)
    }

    override fun remove(userId: String) = synchronized(lock) { write(read().filterNot { it.userId == userId }) }

    private fun read(): List<DeletionRecord> =
        storage.get(KEY)?.let { json.decodeFromString<List<DeletionRecord>>(String(it, Charsets.UTF_8)) }.orEmpty()

    private fun write(next: List<DeletionRecord>) {
        storage.set(KEY, json.encodeToString(next).toByteArray(Charsets.UTF_8))
        mutable.value = next
    }

    private companion object {
        const val KEY = "account-deletions"
    }
}
