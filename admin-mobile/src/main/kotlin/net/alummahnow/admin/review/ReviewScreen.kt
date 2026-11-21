package net.alummahnow.admin.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import net.alummahnow.admin.R
import net.alummahnow.admin.media.PrivateMedia
import net.alummahnow.admin.media.PrivateVideoPlayer
import net.alummahnow.admin.ui.Labels
import net.alummahnow.admin.ui.MinTarget
import net.alummahnow.core.bridge.admin.ReviewDecision
import net.alummahnow.core.bridge.admin.ReviewDetail
import net.alummahnow.core.bridge.admin.ReviewFinding
import net.alummahnow.core.bridge.admin.ReviewFrame
import net.alummahnow.core.bridge.admin.ReviewReason
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.listLabel
import net.alummahnow.design.format.percentLabel
import net.alummahnow.design.theme.FanousTokens

/** What the reviewer asked for from the action bar. */
sealed interface DecisionRequest {
    data object Approve : DecisionRequest
    data object Escalate : DecisionRequest
    data class Reject(val reason: ReviewReason, val note: String) : DecisionRequest
    data class RequestChanges(val note: String) : DecisionRequest

    val decision: ReviewDecision
        get() = when (this) {
            Approve -> ReviewDecision.APPROVE
            Escalate -> ReviewDecision.ESCALATE
            is Reject -> ReviewDecision.REJECT
            is RequestChanges -> ReviewDecision.REQUEST_CHANGES
        }
}

/**
 * The review screen: player, frame strip (flagged first), AI reasons and scores, metadata, channel history, and the
 * decisions. Sensitive items (NSFW, minor present) stay blurred until an audited per-item "Show".
 */
@Composable
fun ReviewScreen(
    state: ReviewUiState,
    now: Instant,
    online: Boolean,
    onShow: () -> Unit,
    onDecide: (DecisionRequest) -> Unit,
    onDismissMessage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val detail = state.detail
    if (detail == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MessageText(state.message ?: ReviewMessage.Failed, now)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTarget)) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
        return
    }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val item = detail.item
    val blurred = item.isSensitive && !state.revealed
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = FanousTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            MediaBlock(detail, state, blurred, onShow)
            Column(
                Modifier.padding(horizontal = FanousTokens.Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
            ) {
                Text(
                    item.title ?: detail.subject?.title ?: stringResource(R.string.untitled),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() }
                )
                val channel = joinMeta(item.channelName, Labels.trust(item.channelTrust))
                if (channel.isNotEmpty()) Text(channel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val due = item.slaDueAt
                if (item.isOpen && due != null) Text(Labels.timeLeft(due, now), style = MaterialTheme.typography.labelLarge)
                state.claimedBy?.let {
                    Text(
                        if (it.isNotEmpty()) aunString(R.string.being_reviewed_by, it) else stringResource(R.string.being_reviewed),
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("claimed-by")
                    )
                }
                state.message?.let { MessageBanner(it, now, onDismissMessage) }
            }
            if (detail.frames.isNotEmpty()) FrameStrip(detail.frames, state.frames, blurred)
            Section(stringResource(R.string.section_reasons)) {
                item.holdCause?.let { Text(aunString(R.string.held_because, stringResource(Labels.holdCause(it)))) }
                item.risk?.let { Text(aunString(R.string.overall_risk, percentLabel(it))) }
                detail.findings.forEach { FindingRow(it, blurred) }
            }
            detail.subject?.let { subject ->
                Section(stringResource(R.string.section_metadata)) {
                    if (subject.description.isNotBlank()) {
                        // Creator text in its own direction (like dir="auto"), so its punctuation sits on the right side.
                        Text(subject.description, style = LocalTextStyle.current.copy(textDirection = TextDirection.Content))
                    }
                    if (subject.tags.isNotEmpty()) Text(aunString(R.string.tags, listLabel(subject.tags)))
                    subject.durationSeconds?.let { Text(aunString(R.string.duration, Labels.position((it * 1000).toLong()))) }
                }
            }
            Section(stringResource(R.string.section_history)) {
                if (detail.history.isEmpty()) Text(stringResource(R.string.history_none))
                detail.history.forEach { h ->
                    Text(
                        joinMeta(
                            Labels.decision(h.decision ?: h.status),
                            h.reasonCode?.let { code -> ReviewReason.of(code)?.let { stringResource(Labels.reason(it)) } ?: code },
                            h.decidedByName,
                            h.decidedAt?.let { aunString(R.string.ago, Labels.ago(it, now)) }
                        )
                    )
                }
            }
        }
        if (item.isOpen) {
            ActionBar(item.escalate, online && !state.busy && state.claimedBy == null, online, state.busy, onDecide, onSheet = {
                sheet =
                    it
            })
        }
    }
    when (sheet) {
        SHEET_REJECT -> RejectSheet(onDismiss = { sheet = null }) { reason, note ->
            sheet = null
            onDecide(DecisionRequest.Reject(reason, note))
        }
        SHEET_CHANGES -> ChangesSheet(onDismiss = { sheet = null }) { note ->
            sheet = null
            onDecide(DecisionRequest.RequestChanges(note))
        }
    }
}

private const val SHEET_REJECT = "reject"
private const val SHEET_CHANGES = "changes"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = FanousTokens.Spacing.lg), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics {
                heading()
            }.padding(top = FanousTokens.Spacing.sm)
        )
        content()
    }
}

@Composable
private fun MediaBlock(detail: ReviewDetail, state: ReviewUiState, blurred: Boolean, onShow: () -> Unit) {
    val stream = detail.stream
    when {
        blurred -> Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val first = detail.frames.firstOrNull()?.let { state.frames[it.key] }
            if (first != null) BlurredImage(first, Modifier.fillMaxSize())
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(FanousTokens.Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(if (detail.item.minor) R.string.blurred_minor else R.string.blurred_nsfw),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onShow, modifier = Modifier.heightIn(min = MinTarget).testTag("show")) {
                        Icon(Icons.Filled.Visibility, contentDescription = null)
                        Text(stringResource(R.string.show_item), modifier = Modifier.padding(start = FanousTokens.Spacing.sm))
                    }
                }
            }
        }
        stream != null -> {
            // The poster is the video's opening frame (sharp: this item is not blurred), until the stream draws.
            val opening = detail.frames.minByOrNull { it.atMs }?.let { state.frames[it.key] }
            val poster = remember(opening) { opening?.let { PrivateMedia.decode(it, blurred = false)?.asImageBitmap() } }
            PrivateVideoPlayer(stream.url, poster = poster)
        }
        else -> Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.no_stream), modifier = Modifier.padding(FanousTokens.Spacing.lg))
        }
    }
}

/** Decoded from bytes in memory; blurred frames keep only a few pixels, scaled up with smoothing. */
@Composable
private fun BlurredImage(bytes: ByteArray, modifier: Modifier) {
    val bitmap = remember(bytes) { PrivateMedia.decode(bytes, blurred = true)?.asImageBitmap() } ?: return
    Image(bitmap, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Low)
}

@Composable
private fun FrameStrip(frames: List<ReviewFrame>, bytes: Map<String, ByteArray>, blurred: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
        Text(
            stringResource(R.string.section_frames),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.lg).semantics { heading() }
        )
        // Every frame whole, in rows that fill the width: none is cut at the screen edge.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg).testTag("frames")) {
            val gap = FanousTokens.Spacing.sm
            val perRow = ((maxWidth + gap) / (FRAME_MIN_WIDTH + gap)).toInt().coerceAtLeast(2)
            val width = (maxWidth - gap * (perRow - 1)) / perRow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                maxItemsInEachRow = perRow
            ) {
                frames.forEach { frame -> key(frame.key) { FrameThumb(frame, bytes[frame.key], blurred, width) } }
            }
        }
    }
}

private val FRAME_MIN_WIDTH = 84.dp

@Composable
private fun FrameThumb(frame: ReviewFrame, bytes: ByteArray?, blurred: Boolean, width: Dp) {
    val label = buildString {
        append(aunString(R.string.frame_at, isolate(Labels.position(frame.atMs))))
        if (frame.flagged) append(", ").append(aunString(R.string.frame_flagged, frame.category?.let { Labels.category(it) } ?: ""))
        if (blurred) append(", ").append(stringResource(R.string.frame_blurred))
    }
    val bitmap = remember(bytes, blurred) { bytes?.let { PrivateMedia.decode(it, blurred)?.asImageBitmap() } }
    Column(Modifier.width(width).semantics(mergeDescendants = true) { contentDescription = label }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .let { if (frame.flagged) it.border(BorderStroke(3.dp, MaterialTheme.colorScheme.error)) else it }
        ) {
            if (bitmap != null) {
                Image(
                    bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    filterQuality = if (blurred) FilterQuality.Low else FilterQuality.Medium
                )
            }
        }
        Text(Labels.position(frame.atMs), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FindingRow(finding: ReviewFinding, blurred: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = FanousTokens.Spacing.xs)) {
        Text(
            joinMeta(
                Labels.category(finding.category),
                percentLabel(finding.score),
                // Where it was found, one part: «إطار عند ٣:١٥» / "Frame at 3:15". The time is an isolate after a no-break
                // space, so «عند ٣:١٥» keeps its order and "at 3:15" never wraps apart.
                finding.atMs?.let { aunString(R.string.source_at, Labels.source(finding.source), isolate(Labels.position(it))) }
                    ?: Labels.source(finding.source)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        // Flagged transcript or metadata text can be explicit: hidden with the media until "Show".
        finding.text?.let { if (!blurred) Text(aunString(R.string.quoted, isolate(it)), style = MaterialTheme.typography.bodySmall) }
        finding.quranRef?.let { Text(aunString(R.string.quran_ref, it), style = MaterialTheme.typography.bodySmall) }
        // The model's note is free English text (no code to translate by): labelled as the model's English note, and
        // isolated so its punctuation stays with it.
        finding.rationale?.let {
            // The page's direction, not the note's: an English note must not turn the Arabic label's line left-to-right
            val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
            Text(
                aunString(R.string.model_note, isolate(it)),
                style = MaterialTheme.typography.bodySmall.copy(textDirection = direction),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageText(message: ReviewMessage, now: Instant) {
    Text(
        when (message) {
            is ReviewMessage.Conflict -> conflictText(message, now)
            ReviewMessage.Offline -> stringResource(R.string.error_offline)
            ReviewMessage.Failed -> stringResource(R.string.error_generic)
            ReviewMessage.Gone -> stringResource(R.string.review_gone)
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("review-message")
    )
}

@Composable
private fun conflictText(c: ReviewMessage.Conflict, now: Instant): String {
    val by = c.byName ?: return stringResource(R.string.conflict_someone)
    val ago = Labels.ago(c.at, now)
    return when (c.decision) {
        "approve" -> aunString(R.string.conflict_approved, by, ago)
        "reject" -> aunString(R.string.conflict_rejected, by, ago)
        else -> aunString(R.string.conflict_decided, by, ago)
    }
}

@Composable
private fun MessageBanner(message: ReviewMessage, now: Instant, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(FanousTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { MessageText(message, now) }
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = MinTarget),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)
            ) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun ActionBar(
    escalated: Boolean,
    enabled: Boolean,
    online: Boolean,
    busy: Boolean,
    onDecide: (DecisionRequest) -> Unit,
    onSheet: (String) -> Unit
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(FanousTokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
            if (!online) {
                Text(
                    stringResource(R.string.decisions_offline),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("decisions-offline")
                )
            }
            if (busy) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(
                onClick = { onDecide(DecisionRequest.Approve) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("approve")
            ) { Text(stringResource(R.string.action_approve)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    onSheet(SHEET_REJECT)
                }, enabled = enabled, modifier = Modifier.heightIn(min = MinTarget).testTag("reject")) {
                    Text(stringResource(R.string.action_reject))
                }
                OutlinedButton(onClick = {
                    onSheet(SHEET_CHANGES)
                }, enabled = enabled, modifier = Modifier.heightIn(min = MinTarget).testTag("request-changes")) {
                    Text(stringResource(R.string.action_request_changes))
                }
                if (!escalated) {
                    OutlinedButton(onClick = {
                        onDecide(DecisionRequest.Escalate)
                    }, enabled = enabled, modifier = Modifier.heightIn(min = MinTarget).testTag("escalate")) {
                        Text(stringResource(R.string.action_escalate))
                    }
                }
            }
        }
    }
}

/** Reject with a reason from the fixed list (`REVIEW_REASON_CODES`) and an optional note to the creator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RejectSheet(onDismiss: () -> Unit, onReject: (ReviewReason, String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf<ReviewReason?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(
                rememberScrollState()
            ).padding(horizontal = FanousTokens.Spacing.xl, vertical = FanousTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
        ) {
            Text(
                stringResource(R.string.reject_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                }
            )
            Column(Modifier.selectableGroup()) {
                ReviewReason.entries.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTarget)
                            .selectable(selected = reason == r, onClick = { reason = r }, role = Role.RadioButton)
                            .testTag("reason-${r.wire}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = reason == r, onClick = null)
                        Text(stringResource(Labels.reason(r)), modifier = Modifier.padding(start = FanousTokens.Spacing.md))
                    }
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_MAX) },
                label = { Text(stringResource(R.string.note_label)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { reason?.let { onReject(it, note) } },
                enabled = reason != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("reject-confirm")
            ) { Text(stringResource(R.string.action_reject)) }
        }
    }
}

/** Request changes: the video stays private and the creator is asked to edit (the note is required). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.xl, vertical = FanousTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
        ) {
            Text(
                stringResource(R.string.changes_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    heading()
                }
            )
            Text(stringResource(R.string.changes_body))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(NOTE_MAX) },
                label = { Text(stringResource(R.string.note_label_required)) },
                modifier = Modifier.fillMaxWidth().testTag("changes-note")
            )
            Button(
                onClick = { onSend(note) },
                enabled = note.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget).testTag("changes-confirm")
            ) { Text(stringResource(R.string.changes_send)) }
        }
    }
}

private const val NOTE_MAX = 1000
