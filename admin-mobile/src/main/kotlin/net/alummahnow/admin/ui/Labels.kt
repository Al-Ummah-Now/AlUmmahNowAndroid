package net.alummahnow.admin.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import java.time.Duration
import java.time.Instant
import net.alummahnow.admin.R
import net.alummahnow.core.bridge.admin.HoldCause
import net.alummahnow.core.bridge.admin.ReviewReason
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.currentLocale

/** Text for the server's codes. Unknown codes fall back to the code itself (never blank). */
object Labels {
    @StringRes
    fun holdCause(cause: HoldCause): Int = when (cause) {
        HoldCause.NSFW -> R.string.cause_nsfw
        HoldCause.RISK -> R.string.cause_risk
        HoldCause.ESCALATION -> R.string.cause_escalation
        HoldCause.CHECK_FAILED -> R.string.cause_check_failed
        HoldCause.BUDGET -> R.string.cause_budget
        HoldCause.APPEAL -> R.string.cause_appeal
    }

    @StringRes
    fun reason(reason: ReviewReason): Int = when (reason) {
        ReviewReason.SEXUAL -> R.string.reason_sexual
        ReviewReason.CHILD_SAFETY -> R.string.reason_child_safety
        ReviewReason.GRAPHIC_VIOLENCE -> R.string.reason_graphic_violence
        ReviewReason.SELF_HARM -> R.string.reason_self_harm
        ReviewReason.HATE -> R.string.reason_hate
        ReviewReason.VIOLENT_EXTREMISM -> R.string.reason_violent_extremism
        ReviewReason.HARASSMENT -> R.string.reason_harassment
        ReviewReason.SPAM_SCAM -> R.string.reason_spam_scam
        ReviewReason.IMPERSONATION -> R.string.reason_impersonation
        ReviewReason.PRIVACY -> R.string.reason_privacy
        ReviewReason.COPYRIGHT -> R.string.reason_copyright
        ReviewReason.MISLEADING_METADATA -> R.string.reason_misleading_metadata
        ReviewReason.RELIGIOUS_MISATTRIBUTION -> R.string.reason_religious_misattribution
        ReviewReason.OTHER -> R.string.reason_other
    }

    private val CATEGORIES = mapOf(
        "sexual" to R.string.category_sexual,
        "gore" to R.string.category_gore,
        "disturbing" to R.string.category_disturbing,
        "minor_present" to R.string.category_minor_present,
        "violent_extremism" to R.string.reason_violent_extremism,
        "child_safety" to R.string.reason_child_safety,
        "hate" to R.string.reason_hate,
        "sectarian_hate" to R.string.category_sectarian_hate,
        "harassment" to R.string.reason_harassment,
        "charity_scam" to R.string.category_charity_scam,
        "spam" to R.string.category_spam,
        "impersonation" to R.string.reason_impersonation,
        "misinfo_health" to R.string.category_misinfo_health,
        "self_harm" to R.string.reason_self_harm,
        "sexual_explicit_language" to R.string.category_explicit_language,
        "misleading_metadata" to R.string.reason_misleading_metadata
    )

    private val SOURCES = mapOf(
        "frame" to R.string.source_frame,
        "thumbnail" to R.string.source_thumbnail,
        "metadata" to R.string.source_metadata,
        "transcript" to R.string.source_transcript,
        "image" to R.string.source_image
    )

    private val TRUST = mapOf("trusted" to R.string.trust_trusted, "new" to R.string.trust_new, "warned" to R.string.trust_warned)

    private val DECISIONS = mapOf(
        "approve" to R.string.decided_approve,
        "reject" to R.string.decided_reject,
        "request_changes" to R.string.decided_request_changes,
        "escalate" to R.string.decided_escalate
    )

    @Composable
    fun category(code: String): String = CATEGORIES[code]?.let { stringResource(it) } ?: code

    @Composable
    fun source(code: String): String = SOURCES[code]?.let { stringResource(it) } ?: code

    @Composable
    fun trust(code: String?): String? = code?.let { c -> TRUST[c]?.let { stringResource(it) } ?: c }

    @Composable
    fun decision(code: String?): String =
        code?.let { c -> DECISIONS[c]?.let { stringResource(it) } ?: c } ?: stringResource(R.string.decided_unknown)

    /** "4 min left" / "Overdue 2 min". Updated at most once a minute, so TalkBack is never flooded. */
    @Composable
    fun timeLeft(due: Instant, now: Instant): String {
        val minutes = Duration.between(now, due).toMinutes()
        return if (minutes >= 0) {
            aunPlural(R.plurals.time_left_minutes, minutes.toInt(), minutes.toInt())
        } else {
            val over = (-minutes).toInt()
            aunPlural(R.plurals.time_overdue_minutes, over, over)
        }
    }

    /** "20 s" / "3 min" / "2 h" / "5 days": how long ago, for "Already approved by Name 20 s ago". */
    @Composable
    fun ago(at: Instant?, now: Instant): String {
        val res = LocalResources.current
        val seconds = at?.let { Duration.between(it, now).seconds.coerceAtLeast(0) } ?: 0
        return when {
            seconds < MINUTE -> res.aunPlural(R.plurals.ago_seconds, seconds.toInt(), seconds.toInt())
            seconds < HOUR -> (seconds / MINUTE).toInt().let { res.aunPlural(R.plurals.ago_minutes, it, it) }
            seconds < DAY -> (seconds / HOUR).toInt().let { res.aunPlural(R.plurals.ago_hours, it, it) }
            else -> (seconds / DAY).toInt().let { res.aunPlural(R.plurals.ago_days, it, it) }
        }
    }

    /** "0:42" / "٠:٤٢" for a position in the video. */
    @Composable
    fun position(ms: Long): String = AunFormat.timecode(ms, currentLocale())

    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val DAY = 86_400L
}
