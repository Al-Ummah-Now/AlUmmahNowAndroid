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
    OTHER
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
    val leftover: Set<Leftover> = emptySet(),
    val startedAtMs: Long = 0,
    /** The server asked to wait until then. */
    val notBeforeMs: Long = 0,
    /** Auth's receipt for this account: for 30 days it answers whether the account is deleted, and opens nothing. */
    val receipt: String? = null,
    /** `DELETE /account` was sent and its answer isn't known, so the account may be gone. */
    val unsure: Boolean = false,
    /** The receipt answers until then. */
    val receiptExpiresAtMs: Long = 0,
    /** When the token of that `DELETE /account` was issued (seconds). */
    val sentIssuedAt: Long = 0,
    /** How often the app asked by itself, and when last. */
    val asks: Int = 0,
    val lastAskMs: Long = 0,
    /** The person doesn't want to be told any more that it isn't known. */
    val dismissed: Boolean = false
)

/** The deletions that started on this device, by account. */
interface DeletionRecords {
    val records: StateFlow<List<DeletionRecord>>

    /** Whether [userId]'s account may be deleted already, including when its records are unreadable. */
    fun isUnsure(userId: String): Boolean = records.value.any { it.userId == userId && it.unsure }

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

    // Null means there is no verified snapshot for the upload gate.
    @Volatile private var knownRecords: List<DeletionRecord>? = null
    private val mutable = MutableStateFlow(
        runCatching { read() }
            .onSuccess { knownRecords = it }
            .onFailure { AunLog.e("auth", "deletion records unreadable", null, "type" to it.javaClass.simpleName) }
            .getOrDefault(emptyList())
    )
    override val records: StateFlow<List<DeletionRecord>> = mutable.asStateFlow()

    override fun isUnsure(userId: String): Boolean =
        knownRecords?.any { it.userId == userId && it.unsure } ?: true

    override fun get(userId: String): DeletionRecord? = synchronized(lock) {
        read().also(::publish).firstOrNull { it.userId == userId }
    }

    override fun put(record: DeletionRecord) = synchronized(lock) {
        write(read().filterNot { it.userId == record.userId } + record)
    }

    override fun remove(userId: String) = synchronized(lock) { write(read().filterNot { it.userId == userId }) }

    private fun read(): List<DeletionRecord> = runCatching {
        storage.get(KEY)?.let { json.decodeFromString<List<DeletionRecord>>(String(it, Charsets.UTF_8)) }.orEmpty()
    }.onFailure { knownRecords = null }.getOrThrow()

    private fun write(next: List<DeletionRecord>) {
        storage.set(KEY, json.encodeToString(next).toByteArray(Charsets.UTF_8))
        publish(next)
    }

    private fun publish(next: List<DeletionRecord>) {
        knownRecords = next
        mutable.value = next
    }

    private companion object {
        const val KEY = "account-deletions"
    }
}
