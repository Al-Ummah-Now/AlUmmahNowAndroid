package net.alummahnow.feature.studio.content

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import net.alummahnow.core.bridge.studio.ReviewStep
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoReview
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** The line under a video's title while it is in review or after it was turned down: the web Studio's words. */
@Composable
internal fun reviewLine(review: VideoReview?, queueMinutes: Int?): String? = when (review?.step) {
    null -> null
    ReviewStep.CHECKING -> aunPlural(R.plurals.studio_review_checking, review.etaMinutes, review.etaMinutes)
    ReviewStep.HELD -> if (queueMinutes == null) {
        stringResource(R.string.studio_review_held)
    } else {
        aunPlural(R.plurals.studio_review_held_minutes, queueMinutes, queueMinutes)
    }
    ReviewStep.APPEALED -> stringResource(R.string.studio_review_appealed)
    ReviewStep.REJECTED -> aunString(R.string.studio_review_rejected, stringResource(reasonText(review.reason)))
}

/** A reason the app doesn't know is said as the general one. */
@StringRes
internal fun reasonText(code: String?): Int = REASONS[code] ?: R.string.studio_review_reason_other

private val REASONS = mapOf(
    "sexual" to R.string.studio_review_reason_sexual,
    "child_safety" to R.string.studio_review_reason_child_safety,
    "graphic_violence" to R.string.studio_review_reason_graphic_violence,
    "self_harm" to R.string.studio_review_reason_self_harm,
    "hate" to R.string.studio_review_reason_hate,
    "violent_extremism" to R.string.studio_review_reason_violent_extremism,
    "harassment" to R.string.studio_review_reason_harassment,
    "spam_scam" to R.string.studio_review_reason_spam_scam,
    "impersonation" to R.string.studio_review_reason_impersonation,
    "privacy" to R.string.studio_review_reason_privacy,
    "copyright" to R.string.studio_review_reason_copyright,
    "misleading_metadata" to R.string.studio_review_reason_misleading_metadata,
    "religious_misattribution" to R.string.studio_review_reason_religious_misattribution,
    "other" to R.string.studio_review_reason_other
)

/** What a creator can do about a video that was turned down. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewActions(v: StudioVideo, enabled: Boolean, actions: ContentActions) {
    val language = currentLocale().language
    val title = isolate(v.titleLocalized[language]?.takeIf { it.isNotBlank() } ?: v.title)
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(start = FanousTokens.Spacing.md, end = FanousTokens.Spacing.md, bottom = FanousTokens.Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)
    ) {
        val appealLabel = aunString(R.string.studio_review_appeal_cd, title)
        val resubmitLabel = aunString(R.string.studio_review_resubmit_cd, title)
        if (v.review?.appealUsed != true) {
            FanousButton(
                stringResource(R.string.studio_review_appeal),
                { actions.onAppeal(v.id) },
                kind = FanousButtonKind.Ghost,
                enabled = enabled,
                wrap = true,
                modifier = Modifier.testTag("appeal-${v.id}").semantics { contentDescription = appealLabel }
            )
        }
        FanousButton(
            stringResource(R.string.studio_review_resubmit),
            { actions.onResubmit(v.id) },
            enabled = enabled,
            wrap = true,
            modifier = Modifier.testTag("resubmit-${v.id}").semantics { contentDescription = resubmitLabel }
        )
    }
}

@Composable
internal fun AppealDialog(state: AppealState, onSend: (String) -> Unit, onClose: () -> Unit, initialText: String = "") {
    var text by rememberSaveable(state.videoId) { mutableStateOf(initialText) }
    val title = stringResource(R.string.studio_appeal_title)
    val scroll = rememberScrollState()
    // What happened is said at the end: at a large font that is below the fold.
    LaunchedEffect(state.failed, scroll.maxValue) { if (state.failed) scroll.scrollTo(scroll.maxValue) }
    val disabled = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .semantics { paneTitle = title }
            .testTag("appeal-dialog"),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
                Text(stringResource(R.string.studio_appeal_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(MAX_APPEAL) },
                    enabled = !state.sending,
                    minLines = 4,
                    label = { Text(stringResource(R.string.studio_appeal_label)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledLabelColor = disabled,
                        disabledBorderColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("appeal-text")
                )
                if (state.failed) {
                    Text(
                        stringResource(R.string.studio_appeal_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("appeal-failed").semantics { liveRegion = LiveRegionMode.Assertive }
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onClose,
                enabled = !state.sending,
                colors = ButtonDefaults.textButtonColors(disabledContentColor = disabled),
                modifier = Modifier.testTag("appeal-cancel")
            ) { Text(stringResource(net.alummahnow.design.R.string.aun_cancel)) }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(text) },
                enabled = text.isNotBlank() && !state.sending,
                colors = ButtonDefaults.textButtonColors(disabledContentColor = disabled),
                modifier = Modifier.testTag("appeal-send")
            ) {
                Text(
                    stringResource(if (state.sending) R.string.studio_appeal_sending else R.string.studio_appeal_send),
                    modifier = if (state.sending) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
                )
            }
        }
    )
}

/** The server's limit. */
private const val MAX_APPEAL = 2_000
