package net.alummahnow.core.bridge.studio.deletion

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.core.J
import net.alummahnow.core.bridge.studio.core.a
import net.alummahnow.core.bridge.studio.core.b
import net.alummahnow.core.bridge.studio.core.l
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
    /**
     * Both steps succeeded. [leftover]: what the person was told stays behind. [elsewhere]: Auth says the account is
     * deleted, but not by this device after its erase, so nothing is known of the content.
     */
    data class Deleted(val leftover: Set<Leftover>, val elsewhere: Boolean = false) : DeletionOutcome

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
 * A deleted account can't start anything again (its erase is refused, its session is gone), so after a lost answer the
 * first question is always Auth's `GET /account/status`, with the receipt that every attempt asks for and saves first.
 */
// Each return is one row of the shared rules; a failing store, device or key must end in an outcome, never in a crash.
@Suppress("ReturnCount", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
class AccountDeletion(
    private val records: DeletionRecords,
    private val api: DeletionApi,
    private val device: DeletionDevice,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

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
    ): DeletionOutcome = mutex.withLock { attempt(userId, label, acceptLeftover, stopRequested, onPass) }

    private suspend fun attempt(
        userId: String,
        label: String,
        acceptLeftover: Set<Leftover>,
        stopRequested: () -> Boolean,
        onPass: (Int, Boolean) -> Unit
    ): DeletionOutcome {
        // A record of a finished deletion whose account is live again (restored by staff) belongs to the past.
        val read = try {
            records.get(userId)?.takeIf { it.stage != DeletionStage.DELETED }
        } catch (e: Exception) {
            AunLog.e("auth", "deletion record unreadable", e)
            return DeletionOutcome.NoRecord(ErasedSoFar.SOME)
        }
        val saved = when (val now = read?.let { settle(it) }) {
            null -> null
            is Settled.Gone -> return now.outcome
            is Settled.NoAnswer -> if (now.record.unsure) return DeletionOutcome.Unknown else now.record
            is Settled.Open -> now.record
        }
        val before = if (saved == null) ErasedSoFar.NOTHING else ErasedSoFar.SOME
        if (saved != null && saved.notBeforeMs > nowMs()) return DeletionOutcome.Wait(saved.notBeforeMs, before)
        val maxAge = if (saved == null) FIRST_MAX_AGE_S else CONTINUE_MAX_AGE_S
        when (val ready = pinnedToken(userId)) {
            Pinned.Other -> return DeletionOutcome.OtherAccount(before)
            Pinned.NoAnswer -> return if (saved?.unsure == true) DeletionOutcome.Unknown else DeletionOutcome.Failed(before)
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
            startedAtMs = saved?.startedAtMs ?: nowMs(),
            unsure = saved?.unsure == true
        )
        var outcome: DeletionOutcome? = null
        try {
            // An upload that went on would bring content back after the erase: without the hold, nothing is called.
            val held = runCatching { device.holdUploads(userId) }.onFailure {
                if (it is CancellationException) throw it
                AunLog.e("auth", "uploads not held, so no deletion", it)
            }
            if (held.isFailure) return DeletionOutcome.Failed(before)
            // Before anything is removed: without it nobody could say later what became of the account.
            val started = when (val asked = askReceipt(userId, saved)) {
                is Receipt.Got -> record.copy(receipt = asked.value, receiptExpiresAtMs = asked.expiresAtMs)
                Receipt.NoRoute -> record.copy(receipt = saved?.receipt, receiptExpiresAtMs = saved?.receiptExpiresAtMs ?: 0)
                Receipt.Gone -> return finish(saved ?: record).also { outcome = it }
                is Receipt.Stop -> return asked.outcome
            }
            if (!save(started)) return DeletionOutcome.NoRecord(before)
            return Attempt(started, saved, acceptLeftover, stopRequested, onPass).run().also { outcome = it }
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

    /**
     * Asks what became of deletions whose answer was lost, and finishes the cleanup of those that worked: their person
     * can't sign in to come back to them. The accounts that are deleted. Does nothing while an attempt runs, and asks
     * a limited number of times for each attempt.
     */
    suspend fun settleLost(): Map<String, DeletionOutcome.Deleted> {
        if (!mutex.tryLock()) return emptyMap()
        return try {
            records.records.value
                .filter { it.stage != DeletionStage.DELETED && it.receipt != null && it.asks < MAX_ASKS }
                .filter { nowMs() - it.lastAskMs !in 0 until ASK_AGAIN_MS }
                .mapNotNull { record ->
                    // Counted first: a call that kills the app counts too.
                    val asked = record.copy(asks = record.asks + 1, lastAskMs = nowMs())
                    if (save(asked)) (settle(asked) as? Settled.Gone)?.let { record.userId to it.outcome } else null
                }
                .toMap()
        } finally {
            mutex.unlock()
        }
    }

    /** Stops saying that it isn't known what became of [userId]'s deletion. It is said again when that changes. */
    fun dismiss(userId: String) {
        runCatching { records.get(userId)?.takeIf { it.unsure }?.let { records.put(it.copy(dismissed = true)) } }
            .onFailure { AunLog.e("auth", "deletion notice not dismissed", it) }
    }

    private sealed interface Settled {
        data class Gone(val outcome: DeletionOutcome.Deleted) : Settled
        data class NoAnswer(val record: DeletionRecord) : Settled
        data class Open(val record: DeletionRecord) : Settled
    }

    /** What became of the account of this record, if that can be asked. */
    private suspend fun settle(record: DeletionRecord): Settled {
        if (record.receipt == null) return Settled.Open(record)
        return when (standing(record)) {
            Standing.DELETED -> Settled.Gone(finish(record))
            Standing.NO_ANSWER -> Settled.NoAnswer(record)
            // The receipt is kept: a request that was lost may still be under way.
            Standing.LIVE -> Settled.Open(record.copy(unsure = false, dismissed = false).also { if (it != record) save(it) })
            Standing.NO_ROUTE -> Settled.Open(record)
            // Nothing more can be asked with it.
            Standing.EXPIRED -> Settled.Open(record.copy(receipt = null, receiptExpiresAtMs = 0).also { save(it) })
        }
    }

    private sealed interface Receipt {
        data class Got(val value: String, val expiresAtMs: Long) : Receipt

        /** Auth's own answer for the verified token's account: it is deleted, or purged. */
        data object Gone : Receipt

        /** A server without receipts. */
        data object NoRoute : Receipt
        data class Stop(val outcome: DeletionOutcome) : Receipt
    }

    private suspend fun askReceipt(userId: String, saved: DeletionRecord?): Receipt {
        val before = if (saved == null) ErasedSoFar.NOTHING else ErasedSoFar.SOME
        val failed = Receipt.Stop(if (saved?.unsure == true) DeletionOutcome.Unknown else DeletionOutcome.Failed(before))
        val token = when (val pinned = pinnedToken(userId)) {
            Pinned.Other -> return Receipt.Stop(DeletionOutcome.OtherAccount(before))
            Pinned.NoAnswer -> return failed
            is Pinned.Token -> pinned.value
        }
        val answer = try {
            api.deletionReceipt(token)
        } catch (e: IOException) {
            AunLog.w("auth", "deletion receipt unanswered", "type" to e.javaClass.simpleName)
            return failed
        }
        val body = runCatching { J.parse(answer.body).obj() }.getOrNull()
        val receipt = body?.s("receipt")?.takeIf { RECEIPT.matches(it) }
        val expiresAt = body?.l("expiresAt") ?: 0
        return when {
            answer.status in HTTP_OK -> if (receipt != null && expiresAt > 0) Receipt.Got(receipt, expiresAt) else failed
            answer.status == HTTP_NOT_FOUND -> if (body?.s("error") == "not_found") Receipt.Gone else Receipt.NoRoute
            needsSignIn(answer) -> Receipt.Stop(DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, before))
            answer.status == HTTP_TOO_MANY || answer.status == HTTP_UNAVAILABLE -> Receipt.Stop(waitFor(answer, before, saved))
            else -> failed
        }
    }

    /** The server asked to wait; remembered where there is a record. */
    private fun waitFor(answer: DeletionAnswer, so: ErasedSoFar, record: DeletionRecord?): DeletionOutcome {
        val until = nowMs() + retryAfterSeconds(answer.retryAfter, nowMs()) * MS_PER_S
        record?.let { save(it.copy(notBeforeMs = until)) }
        return DeletionOutcome.Wait(until, so)
    }

    /** The account is deleted: the marker and the cleanup belong together. */
    private suspend fun finish(record: DeletionRecord): DeletionOutcome.Deleted = withContext(NonCancellable) {
        AunLog.i("auth", "account deleted")
        save(record.copy(stage = DeletionStage.DELETED, resumeToken = null, receipt = null, unsure = false, dismissed = false))
        cleanUp(record.userId)
        // Step 2 is sent only after an erase over everything.
        val ours = record.stage == DeletionStage.CONTENT_ERASED
        DeletionOutcome.Deleted(if (ours) record.leftover else emptySet(), elsewhere = !ours)
    }

    private enum class Standing { DELETED, LIVE, NO_ANSWER, NO_ROUTE, EXPIRED }

    /** What Auth says of the account of [record], which its receipt was saved with. */
    private suspend fun standing(record: DeletionRecord): Standing {
        val receipt = record.receipt ?: return Standing.EXPIRED
        // The phone's clock alone must not end it.
        if (record.receiptExpiresAtMs <= nowMs() && record.receiptExpiresAtMs <= (api.serverNowSeconds()?.times(MS_PER_S) ?: nowMs())) {
            return Standing.EXPIRED
        }
        val answer = try {
            api.accountStatus(receipt)
        } catch (e: IOException) {
            AunLog.w("auth", "account status unanswered", "type" to e.javaClass.simpleName)
            return Standing.NO_ANSWER
        }
        val deleted = runCatching { J.parse(answer.body).obj()?.b("deleted") }.getOrNull()
        return when (answer.status) {
            in HTTP_OK -> when (deleted) {
                true -> Standing.DELETED
                false -> Standing.LIVE
                null -> Standing.NO_ANSWER
            }
            HTTP_UNAUTHORIZED -> Standing.EXPIRED
            HTTP_NOT_FOUND -> Standing.NO_ROUTE
            else -> Standing.NO_ANSWER
        }
    }

    private inner class Attempt(
        private var record: DeletionRecord,
        private val previous: DeletionRecord?,
        private val acceptLeftover: Set<Leftover>,
        private val stopRequested: () -> Boolean,
        private val onPass: (Int, Boolean) -> Unit
    ) {
        private val userId = record.userId
        private var startedBefore = previous != null
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
                if (answer.status == HTTP_UNAUTHORIZED && !needsSignIn(answer)) {
                    // Also how a deleted account's erase is refused.
                    when (standing(record)) {
                        Standing.DELETED -> return finish(if (pass == 1 && previous != null) previous else record)
                        Standing.LIVE -> Unit
                        else -> if (record.unsure) return DeletionOutcome.Unknown
                    }
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
                waitFor(answer, so, record.takeIf { so != ErasedSoFar.NOTHING })
            }
            HTTP_UNAVAILABLE -> waitFor(answer, ErasedSoFar.SOME, record)
            in REFUSED -> {
                val so = nothingMore()
                if (needsSignIn(answer)) DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, so) else DeletionOutcome.Failed(so)
            }
            else -> DeletionOutcome.Failed(ErasedSoFar.SOME)
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
                    Pinned.Other -> return if (lost) lost() else DeletionOutcome.OtherAccount(erasedNow)
                    Pinned.NoAnswer -> return if (lost) lost() else DeletionOutcome.Failed(erasedNow)
                    is Pinned.Token -> pinned.value
                }
                // Saved first: killed during the call, the next start must know to ask what became of it.
                val sent = record.copy(unsure = true)
                if (!save(sent)) return if (lost) lost() else DeletionOutcome.NoRecord(erasedNow)
                record = sent
                val answer = try {
                    api.deleteAccount(token)
                } catch (e: IOException) {
                    AunLog.w("auth", "account deletion unanswered", "type" to e.javaClass.simpleName)
                    null
                }
                when {
                    // Auth may have committed although its answer was lost or says otherwise: ask again, once.
                    answer == null || answer.status >= HTTP_SERVER_ERROR -> lost = true
                    deleted(answer) -> return finish(record)
                    lost -> return lost()
                    else -> return refused(answer, erasedNow)
                }
            }
            return lost()
        }

        /** An answer to step 2 was lost; a session that ended since says nothing about it, Auth's record does. */
        private suspend fun lost(): DeletionOutcome =
            if (standing(record) == Standing.DELETED) finish(record) else DeletionOutcome.Unknown

        /** Step 2 was answered with a no: the account is there, unless it was deleted elsewhere meanwhile. */
        private suspend fun refused(answer: DeletionAnswer, erasedNow: ErasedSoFar): DeletionOutcome {
            val signIn = needsSignIn(answer)
            if (answer.status == HTTP_UNAUTHORIZED && !signIn && standing(record) == Standing.DELETED) return finish(record)
            record = record.copy(unsure = false)
            save(record)
            return if (signIn) DeletionOutcome.NeedsSignIn(FIRST_MAX_AGE_S, erasedNow) else DeletionOutcome.Failed(erasedNow)
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

        /** How often the app asks by itself about one attempt, and how soon again. */
        const val MAX_ASKS = 30
        const val ASK_AGAIN_MS = 60_000L

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

        /** What may go into a header as it is. */
        private val RECEIPT = Regex("[A-Za-z0-9._~+/=-]{1,1024}")

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
