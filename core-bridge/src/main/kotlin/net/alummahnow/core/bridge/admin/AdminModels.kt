package net.alummahnow.core.bridge.admin

import java.time.Instant

// The admin-api review contract (AlUmmahNow docs/specs/VIDEO-REVIEW.md §6, `apps/admin-api/src/routers/review.router.ts`).
// Rows come from the server in snake_case; fields the server adds later are optional here, so older servers decode.
// The Apple Admin app reads the same routes and fields (AlUmmahNowApple `AUNCoreBridge/Admin/ReviewModels.swift`).

/** Who is signed in to the Admin app, from admin-api `GET /api/me` (permissions per role, checked on the server). */
data class StaffProfile(
    val userId: String,
    val displayName: String? = null,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList()
) {
    private val normalizedRoles: Set<String> get() = roles.map(::normalizeRole).toSet()

    /** May work the review queue (`isReviewer` in shared-types roles.ts). The server checks it on every call. */
    val isReviewer: Boolean get() = normalizedRoles.any { it in REVIEWER_ROLES } || permissions.any { it.startsWith("review:") }

    /** Escalations and minor items (VR7, AD13). Admins count. */
    val isSeniorReviewer: Boolean get() = normalizedRoles.any { it in SENIOR_ROLES }

    companion object {
        private val REVIEWER_ROLES = setOf("super_admin", "admin", "moderator", "content_reviewer", "senior_reviewer")
        private val SENIOR_ROLES = setOf("super_admin", "admin", "senior_reviewer")

        /** Role names read like the server does: case, spaces and dashes do not matter. */
        fun normalizeRole(role: String): String {
            val id = role.trim().lowercase().replace('-', '_').replace(' ', '_')
            return if (id == "superadmin") "super_admin" else id
        }
    }
}

/** The queue tabs (Action needed / History). */
enum class ReviewTab(val wire: String) {
    ACTION_NEEDED("action_needed"),
    HISTORY("history")
}

/** Why a video waits for a person (`VideoReviewHoldCause`). */
enum class HoldCause(val wire: String) {
    NSFW("nsfw"),
    RISK("risk"),
    ESCALATION("escalation"),
    CHECK_FAILED("check_failed"),
    BUDGET("budget"),
    APPEAL("appeal");

    companion object {
        fun of(wire: String?): HoldCause? = entries.firstOrNull { it.wire == wire }
    }
}

/** The reasons a reviewer may give (`REVIEW_REASON_CODES`). */
enum class ReviewReason(val wire: String) {
    SEXUAL("sexual"),
    CHILD_SAFETY("child_safety"),
    GRAPHIC_VIOLENCE("graphic_violence"),
    SELF_HARM("self_harm"),
    HATE("hate"),
    VIOLENT_EXTREMISM("violent_extremism"),
    HARASSMENT("harassment"),
    SPAM_SCAM("spam_scam"),
    IMPERSONATION("impersonation"),
    PRIVACY("privacy"),
    COPYRIGHT("copyright"),
    MISLEADING_METADATA("misleading_metadata"),
    RELIGIOUS_MISATTRIBUTION("religious_misattribution"),
    OTHER("other");

    companion object {
        fun of(wire: String?): ReviewReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** A decision a reviewer sends. [REQUEST_CHANGES] keeps the video private and asks the creator to edit. */
enum class ReviewDecision(val wire: String) {
    APPROVE("approve"),
    REJECT("reject"),
    ESCALATE("escalate"),
    REQUEST_CHANGES("request_changes"),
    UNDO("undo")
}

/** One row of the queue (`content_reviews`), text only: no media, so it is safe to show offline. */
data class ReviewItem(
    val id: String,
    val subjectId: String,
    val status: String = "in_review",
    val title: String? = null,
    val channelId: String? = null,
    val channelName: String? = null,
    /** The channel's trust level, as admin-web shows it (`trusted`, `new`, `warned`). */
    val channelTrust: String? = null,
    val risk: Double? = null,
    val nsfw: Boolean = false,
    val escalate: Boolean = false,
    /** "Minor present": the server only ever sends these to senior reviewers (AD13). */
    val minor: Boolean = false,
    val holdCause: HoldCause? = null,
    val topCategory: String? = null,
    val slaDueAt: Instant? = null,
    val createdAt: Instant? = null,
    val assignedTo: String? = null,
    val assignedToName: String? = null,
    val decision: String? = null,
    val decidedAt: Instant? = null,
    val decidedBy: String? = null,
    val decidedByName: String? = null,
    val undoUntil: Instant? = null,
    /** The item's version; decisions send it so a second reviewer gets a conflict, not a double decision. */
    val version: Int? = null
) {
    /** Blurred until a per-item, audited "Show" (NSFW or minor present). */
    val isSensitive: Boolean get() = nsfw || minor || holdCause == HoldCause.NSFW
    val isOpen: Boolean get() = status == "in_review" || status == "appealed"
}

data class ReviewPage(val items: List<ReviewItem>, val nextCursor: String? = null)

/** One flagged observation (`content_review_findings`). */
data class ReviewFinding(
    val id: String,
    val source: String,
    val category: String,
    val score: Double,
    val atMs: Long? = null,
    val endMs: Long? = null,
    /** A frame or thumbnail of the review, read through `GET /api/review/:id/media?key=` (never a stored URL). */
    val mediaKey: String? = null,
    val text: String? = null,
    val quranRef: String? = null,
    val model: String? = null,
    val rationale: String? = null
)

/** A frame of the strip: flagged frames first, then the rest of the manifest in time order. */
data class ReviewFrame(val key: String, val atMs: Long, val flagged: Boolean, val category: String? = null)

/** Earlier reviews of the same video and the channel's record. */
data class ReviewHistoryEntry(
    val id: String,
    val status: String? = null,
    val decision: String? = null,
    val reasonCode: String? = null,
    val decidedAt: Instant? = null,
    val decidedByName: String? = null
)

data class ReviewSubject(
    val id: String,
    val title: String,
    val description: String = "",
    val tags: List<String> = emptyList(),
    val channelId: String? = null,
    val durationSeconds: Double? = null
)

/**
 * A short-lived, per-reviewer stream. Held in memory for the open item only: never stored, logged or pushed.
 * [toString] hides the URL so it cannot reach a log by accident.
 */
class ReviewStream(val url: String, val expiresAt: Instant? = null, val preview: Boolean = false) {
    override fun toString(): String = "ReviewStream(preview=$preview)"
}

/** `GET /api/review/:id`. */
data class ReviewDetail(
    val item: ReviewItem,
    val findings: List<ReviewFinding>,
    val history: List<ReviewHistoryEntry> = emptyList(),
    val subject: ReviewSubject? = null,
    val frames: List<ReviewFrame> = emptyList(),
    val stream: ReviewStream? = null
) {
    companion object {
        /** Flagged frames first (highest score first), then every other frame in time order. */
        fun frames(findings: List<ReviewFinding>, manifest: List<ReviewFrame>): List<ReviewFrame> {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<ReviewFrame>()
            findings.filter { it.mediaKey != null && (it.source == "frame" || it.source == "thumbnail") }
                .sortedByDescending { it.score }
                .forEach { f ->
                    val key = f.mediaKey ?: return@forEach
                    if (seen.add(key)) out += ReviewFrame(key, f.atMs ?: 0, flagged = true, category = f.category)
                }
            manifest.sortedBy { it.atMs }.forEach { if (seen.add(it.key)) out += it.copy(flagged = false) }
            return out
        }
    }
}

/** Review-specific refusals the app explains in its own words. */
sealed class ReviewError(message: String) : Exception(message) {
    /** Another reviewer decided first (`409`): "Already approved by Name 20 s ago". */
    class AlreadyDecided(val decision: String?, val byName: String?, val at: Instant?) : ReviewError("already decided")

    /** Being reviewed by someone else. */
    class ClaimedByOther(val name: String?) : ReviewError("claimed")

    /** The server wants a sign-in within the last 15 minutes (`step_up_required`). */
    class StepUpRequired : ReviewError("step-up required")
    class UndoExpired : ReviewError("undo expired")
    class NotStaff : ReviewError("not staff")
    class Offline : ReviewError("offline")
}

/** Something that happened on the device that the audit log records. Android blocks capture with FLAG_SECURE. */
enum class CaptureEvent(val wire: String) {
    SCREENSHOT("screenshot"),
    RECORDING_STARTED("recording_started"),
    RECORDING_STOPPED("recording_stopped")
}

/** The review queue on admin-api. Every call acts as the signed-in reviewer; the server checks the role each time. */
interface ReviewService {
    suspend fun profile(): StaffProfile
    suspend fun queue(tab: ReviewTab, query: String?, cursor: String?): ReviewPage
    suspend fun detail(id: String): ReviewDetail

    /** Claims (or renews the claim on) an item for 3 minutes. */
    suspend fun claim(id: String)

    /** Audited reveal of one blurred item (`target` names what was shown: `item`, a frame key, `player`). */
    suspend fun reveal(id: String, target: String)
    suspend fun decide(id: String, decision: ReviewDecision, reason: ReviewReason?, note: String?, version: Int?): ReviewItem

    /** A frame or thumbnail of the review, as bytes in memory. */
    suspend fun media(id: String, key: String): ByteArray
    suspend fun recordCapture(event: CaptureEvent, reviewId: String?, installId: String)
}

/** Push registration for the Admin app (`PUT /api/v1/devices/:installId` with `app: "admin"`). */
interface PushRegistrar {
    suspend fun register(installId: String, token: String, locale: String, appVersion: String)
    suspend fun unregister(installId: String)
}
