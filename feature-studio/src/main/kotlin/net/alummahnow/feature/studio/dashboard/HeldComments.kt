package net.alummahnow.feature.studio.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.studio.CommentReportReason
import net.alummahnow.core.bridge.studio.HeldComment
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.feature.studio.R

private enum class CommentDialog { REMOVE, REPORT, HIDE }

@Composable
internal fun HeldCommentRows(
    comments: List<HeldComment>,
    pending: Set<String>,
    loadingMore: Boolean,
    onAction: (String, HeldCommentAction) -> Unit
) {
    var dialog by remember { mutableStateOf<Pair<String, CommentDialog>?>(null) }
    comments.forEach { comment ->
        val enabled = !loadingMore && comment.id !in pending
        HorizontalDivider()
        Text(
            comment.authorName.ifBlank {
                stringResource(R.string.studio_held_unknown_author)
            },
            style = MaterialTheme.typography.titleSmall
        )
        Text(comment.text)
        if (comment.platformHold) Text(stringResource(R.string.studio_held_platform_hold))
        if (comment.id in pending) Text(stringResource(R.string.studio_held_pending))
        FlowRow {
            if (comment.canModerate) {
                FanousButton(stringResource(R.string.studio_held_approve), {
                    onAction(comment.id, HeldCommentAction.Approve)
                }, kind = FanousButtonKind.Ghost, enabled = enabled)
                FanousButton(stringResource(R.string.studio_held_remove), {
                    dialog = comment.id to CommentDialog.REMOVE
                }, kind = FanousButtonKind.Ghost, enabled = enabled)
            }
            FanousButton(stringResource(R.string.studio_held_report), {
                dialog = comment.id to CommentDialog.REPORT
            }, kind = FanousButtonKind.Ghost, enabled = enabled)
            if (comment.authorId != null) {
                FanousButton(
                    stringResource(R.string.studio_held_hide),
                    {
                        dialog = comment.id to CommentDialog.HIDE
                    },
                    kind = FanousButtonKind.Ghost,
                    enabled = enabled &&
                        comments.none {
                            it.authorId == comment.authorId && it.id in pending
                        },
                    wrap = true
                )
            }
        }
    }
    dialog?.let { (id, choice) ->
        val comment = comments.firstOrNull { it.id == id }
        if (comment != null && id !in pending) {
            val dismiss = { dialog = null }
            if (choice == CommentDialog.REPORT) {
                ReportCommentDialog(dismiss) { action ->
                    dismiss()
                    onAction(id, action)
                }
            } else {
                val hide = choice == CommentDialog.HIDE
                val label = if (hide) R.string.studio_held_hide else R.string.studio_held_remove
                AlertDialog(
                    onDismissRequest = dismiss,
                    title = { Text(stringResource(label)) },
                    text = { Text(stringResource(if (hide) R.string.studio_held_hide_prompt else R.string.studio_held_remove_prompt)) },
                    dismissButton = { CancelCommentDialog(dismiss) },
                    confirmButton = {
                        FanousButton(stringResource(label), {
                            dismiss()
                            onAction(id, if (hide) HeldCommentAction.HideAuthor else HeldCommentAction.Remove)
                        }, kind = FanousButtonKind.Destructive, wrap = true)
                    }
                )
            }
        }
    }
}

@Composable
private fun ReportCommentDialog(onDismiss: () -> Unit, onReport: (HeldCommentAction.Report) -> Unit) {
    var reason by remember { mutableStateOf<CommentReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.studio_held_report)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.studio_held_reason))
                Column(Modifier.selectableGroup()) {
                    CommentReportReason.entries.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = reason == option,
                                role = Role.RadioButton,
                                onClick = { reason = option }
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = reason == option, onClick = null)
                            Text(stringResource(option.label))
                        }
                    }
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.studio_held_details)) },
                    isError = details.length > REPORT_DETAILS_LIMIT,
                    supportingText = if (details.length > REPORT_DETAILS_LIMIT) {
                        { Text(stringResource(R.string.studio_held_details_limit)) }
                    } else {
                        null
                    },
                    minLines = 2,
                    maxLines = 4
                )
            }
        },
        dismissButton = { CancelCommentDialog(onDismiss) },
        confirmButton = {
            FanousButton(stringResource(R.string.studio_held_send), {
                reason?.let { onReport(HeldCommentAction.Report(it, details.trim().ifEmpty { null })) }
            }, kind = FanousButtonKind.Primary, enabled = reason != null && details.length <= REPORT_DETAILS_LIMIT)
        }
    )
}

@Composable
private fun CancelCommentDialog(onDismiss: () -> Unit) {
    FanousButton(stringResource(android.R.string.cancel), onDismiss, kind = FanousButtonKind.Ghost)
}

private const val REPORT_DETAILS_LIMIT = 5_000

private val CommentReportReason.label: Int
    get() = when (this) {
        CommentReportReason.CHILD_SAFETY -> R.string.studio_held_child_safety
        CommentReportReason.INAPPROPRIATE -> R.string.studio_held_inappropriate
        CommentReportReason.SPAM -> R.string.studio_held_spam
        CommentReportReason.HARASSMENT -> R.string.studio_held_harassment
        CommentReportReason.COPYRIGHT -> R.string.studio_held_copyright
        CommentReportReason.MISINFORMATION -> R.string.studio_held_misinformation
        CommentReportReason.OTHER -> R.string.studio_held_other
    }
