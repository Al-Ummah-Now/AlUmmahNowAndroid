package net.alummahnow.core.bridge.studio.deletion

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.a
import net.alummahnow.core.bridge.studio.core.b
import net.alummahnow.core.bridge.studio.core.obj
import net.alummahnow.core.bridge.studio.core.s
import net.alummahnow.core.bridge.studio.core.unwrap

/** What may be said about the content when a deletion stopped. */
enum class ErasedSoFar {
    /** This attempt removed nothing, and none started before. */
    NOTHING,

    /** Some content may be gone. */
    SOME,

    /** The content is erased; the sign-in account is left. */
    CONTENT
}

sealed interface DeletionOutcome {
    /** Both steps succeeded. [leftover]: what the person was told stays behind. */
    data class Deleted(val leftover: Set<Leftover>) : DeletionOutcome

    /** A recent sign-in of the same person is needed. Nothing more is removed until they confirm again. */
    data class NeedsSignIn(val maxAgeSeconds: Int, val erased: ErasedSoFar) : DeletionOutcome

    /** The signed-in account is not the one the person confirmed. */
    data class OtherAccount(val erased: ErasedSoFar) : DeletionOutcome

    /** The erase finished and left something: the person decides before the account goes. */
    data class LeftBehind(val leftover: Set<Leftover>) : DeletionOutcome

    /** The server asked to wait. */
    data class Wait(val untilMs: Long, val erased: ErasedSoFar) : DeletionOutcome

    /** 50 passes, or the person stopped: they confirm again to go on. */
    data class Paused(val stopped: Boolean, val erased: ErasedSoFar) : DeletionOutcome

    /** It stopped on an error. */
    data class Failed(val erased: ErasedSoFar) : DeletionOutcome

    /** The last answer of step 2 was lost: it may even be finished. */
    data object Unknown : DeletionOutcome

    /** The record could not be read or saved, so nothing was called. */
    data class NoRecord(val erased: ErasedSoFar) : DeletionOutcome
}

/** What the device keeps of an account, for the end of its deletion. */
interface DeletionDevice {
    /** A current access token of [userId]; null when it is not the active account or has no session. */
    suspend fun token(userId: String): String?

    /** Holds the account's uploads until nothing of them is under way. Throws when that can't be reached. */
    suspend fun holdUploads(userId: String)

    suspend fun releaseUploads(userId: String)

    /** Removes queued and staged uploads, with a saved marker so a running one can't write back. Throws on failure. */
    suspend fun purgeUploads(userId: String)

    /** Removes the session from this device. Works without the server: the account is gone there. */
    suspend fun endSession(userId: String)
}

/**
 * Deletes an account (Play account deletion policy; wiki guides/data-lifecycle.md, the rules for every client):
 * the Videos data, pass by pass, then the sign-in account, then this device's own copy. Every attempt starts with the
 * erase again: the account was live in between. The record is saved before the first call and removed last.
 */
// Each return is one row of the shared rules; a failing store, device or key must end in an outcome, never in a crash.
@Suppress("ReturnCount", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
class AccountDeletion(
    private val records: DeletionRecords,
    private val api: DeletionApi,
    private val device: DeletionDevice,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    /**
     * One confirmed attempt for [userId]. [acceptLeftover]: what the person was told stays and went on with.
     * [onPass] after each pass that left more; [stopRequested] is asked between passes.
     */
    suspend fun run(
        userId: String,
        label: String,
        acceptLeftover: Set<Leftover> = emptySet(),
        stopRequested: () -> Boolean = { false },
        onPass: (pass: Int, restarted: Boolean) -> Unit = { _, _ -> }
    ): DeletionOutcome {
        // A record of a finished deletion whose account is live again (restored by staff) belongs to the past.
        val saved = try {
            records.get(userId)?.takeIf { it.stage != DeletionStage.DELETED }
        } catch (e: Exception) {
            AunLog.e("auth", "deletion record unreadable", e)
            return DeletionOutcome.NoRecord(ErasedSoFar.SOME)
        }
        val before = if (saved == null) ErasedSoFar.NOTHING else ErasedSoFar.SOME
        if (saved != null && saved.notBeforeMs > nowMs()) return DeletionOutcome.Wait(saved.notBeforeMs, before)
        val maxAge = if (saved == null) FIRST_MAX_AGE_S else CONTINUE_MAX_AGE_S
        when (val ready = pinnedToken(userId)) {
            Pinned.Other -> return DeletionOutcome.OtherAccount(before)
            Pinned.NoAnswer -> return DeletionOutcome.Failed(before)
            is Pinned.Token -> {
                val now = api.serverNowSeconds() ?: (nowMs() / MS_PER_S)
                if (!DeletionToken.isRecent(ready.value, now, maxAge)) return DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, before)
            }
        }
        // Only a run that is not finished has a token to go on with; after a finished one the erase starts again.
        val resume = saved?.takeIf { it.resumeToken != null }
        val record = DeletionRecord(
            userId,
            label,
            resumeToken = resume?.resumeToken,
            leftover = resume?.leftover.orEmpty(),
            startedAtMs = saved?.startedAtMs ?: nowMs()
        )
        var outcome: DeletionOutcome? = null
        try {
            // An upload that went on would bring content back after the erase: without the hold, nothing is called.
            val held = runCatching { device.holdUploads(userId) }.onFailure {
                if (it is CancellationException) throw it
                AunLog.e("auth", "uploads not held, so no deletion", it)
            }
            if (held.isFailure) return DeletionOutcome.Failed(before)
            if (!save(record)) return DeletionOutcome.NoRecord(before)
            return Attempt(record, startedBefore = saved != null, acceptLeftover, stopRequested, onPass).run().also { outcome = it }
        } finally {
            if (outcome !is DeletionOutcome.Deleted) {
                withContext(NonCancellable) { runCatching { device.releaseUploads(userId) } }
            }
        }
    }

    /** Finishes what a killed app left undone after both steps had succeeded. Called at start. */
    suspend fun finishCleanups() {
        records.records.value.filter { it.stage == DeletionStage.DELETED }.forEach { cleanUp(it.userId) }
    }

    private inner class Attempt(
        private var record: DeletionRecord,
        private var startedBefore: Boolean,
        private val acceptLeftover: Set<Leftover>,
        private val stopRequested: () -> Boolean,
        private val onPass: (Int, Boolean) -> Unit
    ) {
        private val userId = record.userId
        private val erased get() = if (startedBefore) ErasedSoFar.SOME else ErasedSoFar.NOTHING

        suspend fun run(): DeletionOutcome {
            for (pass in 1..MAX_PASSES) {
                if (pass > 1 && stopRequested()) return DeletionOutcome.Paused(stopped = true, erased)
                val token = when (val pinned = pinnedToken(userId)) {
                    Pinned.Other -> return DeletionOutcome.OtherAccount(nothingMore())
                    Pinned.NoAnswer -> return DeletionOutcome.Failed(nothingMore())
                    is Pinned.Token -> pinned.value
                }
                val answer = try {
                    api.erase(token, record.resumeToken)
                } catch (e: IOException) {
                    AunLog.w("auth", "erase pass unanswered", "type" to e.javaClass.simpleName)
                    return DeletionOutcome.Failed(ErasedSoFar.SOME)
                }
                refusal(answer)?.let { return it }
                val body = json(answer.body)
                val complete = body?.b("complete") == true
                val next = body?.s("resumeToken")?.takeIf { it.isNotEmpty() }
                if (!complete && next == null) return DeletionOutcome.Failed(ErasedSoFar.SOME)
                startedBefore = true
                val restarted = body?.b("restarted") == true
                // A run that began again reports again what it cannot remove.
                val leftover = leftoverOf(body) + (if (restarted) emptySet() else record.leftover)
                record = record.copy(
                    stage = if (complete) DeletionStage.CONTENT_ERASED else DeletionStage.STARTED,
                    resumeToken = next.takeUnless { complete },
                    leftover = leftover,
                    notBeforeMs = 0
                )
                // The earlier record still says "started": the next attempt repeats this pass.
                if (!save(record)) return DeletionOutcome.NoRecord(ErasedSoFar.SOME)
                if (complete) return afterErase(leftover)
                onPass(pass, restarted)
            }
            return DeletionOutcome.Paused(stopped = false, erased)
        }

        /** What an answer that removed nothing, or nothing that is known, means; null for a pass that worked. */
        private fun refusal(answer: DeletionAnswer): DeletionOutcome? = when (answer.status) {
            in HTTP_OK -> null
            HTTP_TOO_MANY -> {
                val so = nothingMore()
                waitFor(answer, so)
            }
            HTTP_UNAVAILABLE -> waitFor(answer, ErasedSoFar.SOME)
            in REFUSED -> {
                val so = nothingMore()
                if (needsSignIn(answer)) DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, so) else DeletionOutcome.Failed(so)
            }
            else -> DeletionOutcome.Failed(ErasedSoFar.SOME)
        }

        private fun waitFor(answer: DeletionAnswer, so: ErasedSoFar): DeletionOutcome {
            val until = nowMs() + retryAfterSeconds(answer.retryAfter, nowMs()) * MS_PER_S
            if (so != ErasedSoFar.NOTHING) save(record.copy(notBeforeMs = until))
            return DeletionOutcome.Wait(until, so)
        }

        /** This pass removed nothing: a record made for it would say otherwise, unless something went before. */
        private fun nothingMore(): ErasedSoFar {
            if (startedBefore) return ErasedSoFar.SOME
            return runCatching { records.remove(userId) }.fold({ ErasedSoFar.NOTHING }, { ErasedSoFar.SOME })
        }

        private suspend fun afterErase(leftover: Set<Leftover>): DeletionOutcome {
            // Never more than what they were told about.
            if (!acceptLeftover.containsAll(leftover)) return DeletionOutcome.LeftBehind(leftover)
            // Not stopped half-way: the answer, the marker and the cleanup belong together.
            return withContext(NonCancellable) { deleteAccount(leftover) }
        }

        private suspend fun deleteAccount(leftover: Set<Leftover>): DeletionOutcome {
            val erasedNow = if (leftover.isEmpty()) ErasedSoFar.CONTENT else ErasedSoFar.SOME
            var lost = false
            repeat(2) {
                val token = when (val pinned = pinnedToken(userId)) {
                    Pinned.Other -> return if (lost) DeletionOutcome.Unknown else DeletionOutcome.OtherAccount(erasedNow)
                    Pinned.NoAnswer -> return if (lost) DeletionOutcome.Unknown else DeletionOutcome.Failed(erasedNow)
                    is Pinned.Token -> pinned.value
                }
                val answer = try {
                    api.deleteAccount(token)
                } catch (e: IOException) {
                    AunLog.w("auth", "account deletion unanswered", "type" to e.javaClass.simpleName)
                    null
                }
                when {
                    // Auth may have committed although its answer was lost or says otherwise: ask again, once.
                    answer == null || answer.status >= HTTP_SERVER_ERROR -> lost = true
                    deleted(answer) -> {
                        done()
                        return DeletionOutcome.Deleted(leftover)
                    }
                    lost -> return DeletionOutcome.Unknown
                    needsSignIn(answer) -> return DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, erasedNow)
                    else -> return DeletionOutcome.Failed(erasedNow)
                }
            }
            return DeletionOutcome.Unknown
        }

        private suspend fun done() {
            AunLog.i("auth", "account deleted")
            save(record.copy(stage = DeletionStage.DELETED, resumeToken = null))
            cleanUp(userId)
        }
    }

    /** Uploads, session, record, in that order. What fails is finished at the next start; it is never an error here. */
    private suspend fun cleanUp(userId: String) {
        runCatching {
            device.purgeUploads(userId)
            device.endSession(userId)
            records.remove(userId)
        }.onFailure {
            AunLog.e("auth", "cleanup after a deletion is left for the next start", it)
            // The session must not outlive its account, also when the uploads could not be removed.
            runCatching { device.endSession(userId) }
        }
    }

    private fun save(record: DeletionRecord): Boolean =
        runCatching { records.put(record) }.onFailure { AunLog.e("auth", "deletion record not saved", it) }.isSuccess

    private sealed interface Pinned {
        data class Token(val value: String) : Pinned
        data object Other : Pinned
        data object NoAnswer : Pinned
    }

    /** The account's own token, checked by its `properties.id` before every call (a refresh may have replaced it). */
    private suspend fun pinnedToken(userId: String): Pinned {
        val token = try {
            device.token(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AunLog.w("auth", "no token for the deletion", "type" to e.javaClass.simpleName)
            return Pinned.NoAnswer
        }
        return if (token != null && DeletionToken.userIdOf(token) == userId) Pinned.Token(token) else Pinned.Other
    }

    companion object {
        /** The web app's privacy page uses the same limit; the server has none. */
        const val MAX_PASSES = 50

        /** The first pass starts with a margin inside Auth's 600 s, so the erase fits. */
        const val FIRST_MAX_AGE_S = 300

        /** Once a deletion has started: Auth's limit less 30 s, so a long erase is not stopped half-way. */
        const val CONTINUE_MAX_AGE_S = 570

        private const val DEFAULT_RETRY_S = 300L

        /** Ten years: only so that a number too large for a clock stays one. */
        private const val MAX_RETRY_S = 315_360_000L
        private const val MS_PER_S = 1_000L
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNAVAILABLE = 503
        private const val HTTP_SERVER_ERROR = 500
        private val HTTP_OK = 200..299

        /** Refused before the erase began: the pass removed nothing. */
        private val REFUSED = setOf(400, 401, 403, 404)

        private fun json(body: String): JsonObject? = runCatching { J.parse(body).unwrap().obj() }.getOrNull()

        private fun needsSignIn(answer: DeletionAnswer): Boolean = answer.status == HTTP_UNAUTHORIZED &&
            runCatching { J.parse(answer.body).obj()?.s("error") }.getOrNull() == "insufficient_user_authentication"

        /** `deleted: true`, or no account under the id whose content was erased just now: gone already. */
        private fun deleted(answer: DeletionAnswer): Boolean {
            val body = runCatching { J.parse(answer.body).obj() }.getOrNull()
            return (answer.status in HTTP_OK && body?.b("deleted") == true) ||
                (answer.status == HTTP_NOT_FOUND && body?.s("error") == "not_found")
        }

        /** Each kind that is there: one never hides the other. */
        private fun leftoverOf(body: JsonObject?): Set<Leftover> = body?.a("errors").orEmpty().map { error ->
            val channel = (error as? JsonPrimitive)?.contentOrNull?.startsWith("channel_unreadable:") == true
            if (channel) Leftover.CHANNEL else Leftover.OTHER
        }.toSet()

        /** Seconds of a Retry-After header (a number or a date), never less than it says. */
        internal fun retryAfterSeconds(header: String?, nowMs: Long): Long {
            val value = header?.trim().orEmpty()
            val seconds = if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                value.toLongOrNull() ?: MAX_RETRY_S
            } else {
                httpDateSeconds(value)?.let { it - nowMs / MS_PER_S }
            }
            return if (seconds == null || seconds <= 0) DEFAULT_RETRY_S else minOf(seconds, MAX_RETRY_S)
        }
    }
}
