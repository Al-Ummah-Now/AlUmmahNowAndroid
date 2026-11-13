package net.alummahnow.core.bridge.admin

import android.util.Base64
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.alummahnow.core.bridge.CoreError

/**
 * The review queue in memory, for UI tests, screenshots and unit tests: no network, no real pushes, no AI.
 * The same items as the Apple app's `FixtureReviewService`.
 *
 * `fx-conflict` is always decided first by "Amina" (a version conflict); `fx-minor` only exists for seniors, as on
 * the server. Decisions move items to History and can be undone while `undoUntil` has not passed.
 */
class FixtureReviewService(
    private val role: Role = Role.REVIEWER,
    private val now: () -> Instant = Instant::now
) : ReviewService,
    PushRegistrar {
    enum class Role { REVIEWER, SENIOR, VIEWER }

    private val lock = Mutex()
    private val open = mutableListOf<ReviewItem>()
    private val decided = mutableListOf<ReviewItem>()
    val reveals = mutableListOf<String>()
    val claims = mutableListOf<String>()
    val captures = mutableListOf<CaptureEvent>()
    val registeredInstalls = mutableMapOf<String, String>()

    @Volatile var offline = false

    init {
        val start = now()
        fun due(minutes: Long): Instant = start.plus(Duration.ofMinutes(minutes))
        open += listOf(
            ReviewItem(
                id = "fx-nsfw", subjectId = "v-nsfw", title = "Evening market walk", channelId = "c-1",
                channelName = "Old City Walks", channelTrust = "new", risk = 0.82, nsfw = true, holdCause = HoldCause.NSFW,
                topCategory = "sexual", slaDueAt = due(3), version = 1
            ),
            ReviewItem(
                id = "fx-risk", subjectId = "v-risk", title = "Charity drive update", channelId = "c-2",
                channelName = "Hope Relief", channelTrust = "trusted", risk = 0.41, holdCause = HoldCause.RISK,
                topCategory = "charity_scam", slaDueAt = due(12), version = 1
            ),
            ReviewItem(
                id = "fx-conflict", subjectId = "v-conflict", title = "Ramadan recipes", channelId = "c-3",
                channelName = "Family Kitchen", channelTrust = "trusted", risk = 0.33, holdCause = HoldCause.RISK,
                topCategory = "misleading_metadata", slaDueAt = due(12), version = 1
            ),
            ReviewItem(
                id = "fx-appeal", subjectId = "v-appeal", status = "appealed", title = "Debate night", channelId = "c-4",
                channelName = "Campus Voices", channelTrust = "warned", risk = 0.55, holdCause = HoldCause.APPEAL,
                topCategory = "harassment", slaDueAt = due(25), version = 1
            )
        )
        if (role == Role.SENIOR) {
            open.add(
                0,
                ReviewItem(
                    id = "fx-minor", subjectId = "v-minor", title = "Swimming lesson", channelId = "c-5",
                    channelName = "Sports Club", channelTrust = "new", risk = 0.7, escalate = true, minor = true,
                    holdCause = HoldCause.ESCALATION, topCategory = "minor_present", slaDueAt = due(5), version = 1
                )
            )
        }
        decided += ReviewItem(
            id = "fx-old", subjectId = "v-old", status = "approved", title = "Morning reminder", channelId = "c-2",
            channelName = "Hope Relief", risk = 0.2, holdCause = HoldCause.RISK, topCategory = "spam", slaDueAt = due(-60),
            decision = "approve", decidedAt = due(-50), decidedByName = "Fixture Reviewer", version = 2
        )
    }

    private fun online() {
        if (offline) throw ReviewError.Offline()
    }

    private val me: String get() = if (role == Role.SENIOR) "fx-senior" else "fx-reviewer"
    private val myName: String get() = if (role == Role.SENIOR) "Fixture Senior" else "Fixture Reviewer"

    override suspend fun profile(): StaffProfile {
        online()
        return when (role) {
            Role.VIEWER -> throw ReviewError.NotStaff()
            Role.REVIEWER -> StaffProfile("fx-reviewer", "Fixture Reviewer", listOf("content_reviewer"))
            Role.SENIOR -> StaffProfile("fx-senior", "Fixture Senior", listOf("senior_reviewer"))
        }
    }

    override suspend fun queue(tab: ReviewTab, query: String?, cursor: String?): ReviewPage = lock.withLock {
        online()
        val source = if (tab == ReviewTab.ACTION_NEEDED) open.toList() else decided.sortedByDescending { it.decidedAt }
        val q = query?.trim()?.lowercase().orEmpty()
        val items = if (q.isEmpty()) {
            source
        } else {
            source.filter { item ->
                listOfNotNull(item.title, item.channelName, item.topCategory, item.holdCause?.wire).any { it.lowercase().contains(q) }
            }
        }
        ReviewPage(items)
    }

    override suspend fun detail(id: String): ReviewDetail = lock.withLock {
        online()
        val item = (open + decided).firstOrNull { it.id == id } ?: throw CoreError.NotFound("review")
        val findings = listOf(
            ReviewFinding(
                id = "$id-f1",
                source = "frame",
                category = item.topCategory ?: "other",
                score = item.risk ?: 0.5,
                atMs = 42_000,
                mediaKey = "review/${item.subjectId}/r1/frame-042.jpg",
                rationale = "A frame scored above the screen threshold."
            ),
            ReviewFinding(id = "$id-f2", source = "metadata", category = "spam", score = 0.21, text = "Donate now, limited time")
        )
        val manifest = (0 until 4).map { ReviewFrame("review/${item.subjectId}/r1/frame-0${it}0.jpg", it * 10_000L, false) }
        ReviewDetail(
            item = item,
            findings = findings,
            history = listOf(ReviewHistoryEntry("$id-h1", "approved", "approve", decidedAt = now().minus(Duration.ofDays(7)))),
            subject = ReviewSubject(
                item.subjectId,
                item.title.orEmpty(),
                "A short description from the creator.",
                listOf("community"),
                durationSeconds = 312.0
            ),
            frames = ReviewDetail.frames(findings, manifest)
        )
    }

    override suspend fun claim(id: String) = lock.withLock {
        online()
        claims += id
        val i = open.indexOfFirst { it.id == id }
        if (i >= 0) open[i] = open[i].copy(assignedTo = me, assignedToName = myName)
    }

    override suspend fun reveal(id: String, target: String) = lock.withLock {
        online()
        reveals += "$id:$target"
        Unit
    }

    override suspend fun decide(id: String, decision: ReviewDecision, reason: ReviewReason?, note: String?, version: Int?): ReviewItem =
        lock.withLock {
            online()
            if (decision == ReviewDecision.UNDO) return@withLock undo(id)
            val index = open.indexOfFirst { it.id == id }
            if (index < 0) throw ReviewError.AlreadyDecided(null, null, null)
            if (id == "fx-conflict") throw ReviewError.AlreadyDecided("approve", "Amina", now().minusSeconds(CONFLICT_AGO_S))
            if (decision == ReviewDecision.ESCALATE) {
                val item = open[index].copy(escalate = true, holdCause = HoldCause.ESCALATION)
                if (role == Role.SENIOR) open[index] = item else open.removeAt(index)
                return@withLock item
            }
            val removed = open.removeAt(index)
            val item = removed.copy(
                status = when (decision) {
                    ReviewDecision.APPROVE -> "approved"
                    ReviewDecision.REQUEST_CHANGES -> "changes_requested"
                    else -> "rejected"
                },
                decision = decision.wire,
                decidedAt = now(),
                decidedBy = me,
                decidedByName = myName,
                undoUntil = now().plus(Duration.ofMinutes(UNDO_MINUTES)),
                version = (removed.version ?: 1) + 1
            )
            decided += item
            item
        }

    private fun undo(id: String): ReviewItem {
        val index = decided.indexOfFirst { it.id == id }
        if (index < 0) throw ReviewError.UndoExpired()
        val until = decided[index].undoUntil
        if (until == null || !until.isAfter(now())) throw ReviewError.UndoExpired()
        val removed = decided.removeAt(index)
        val item = removed.copy(
            status = if (removed.holdCause == HoldCause.APPEAL) "appealed" else "in_review",
            decision = null,
            decidedAt = null,
            decidedBy = null,
            decidedByName = null,
            undoUntil = null,
            version = (removed.version ?: 1) + 1
        )
        open += item
        open.sortBy { it.slaDueAt ?: Instant.MAX }
        return item
    }

    override suspend fun media(id: String, key: String): ByteArray {
        online()
        return Base64.decode(FRAME_PNG, Base64.DEFAULT)
    }

    override suspend fun recordCapture(event: CaptureEvent, reviewId: String?, installId: String) {
        captures += event
    }

    override suspend fun register(installId: String, token: String, locale: String, appVersion: String) {
        registeredInstalls[installId] = token
    }

    override suspend fun unregister(installId: String) {
        registeredInstalls.remove(installId)
    }

    companion object {
        /** A 2×2 grey PNG: decoded in memory like a real frame. */
        const val FRAME_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAAAAABX3VL4AAAADklEQVR4nGNoaGBoaAAABgYCASzBUNcAAAAASUVORK5CYII="
        private const val CONFLICT_AGO_S = 20L
        private const val UNDO_MINUTES = 10L
    }
}
