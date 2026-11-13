package net.alummahnow.admin.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import java.time.Instant
import net.alummahnow.admin.R
import net.alummahnow.admin.ui.Labels
import net.alummahnow.admin.ui.MinTarget
import net.alummahnow.core.bridge.admin.HoldCause
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewTab
import net.alummahnow.design.theme.FanousTokens

/**
 * Action needed / History. Text only: no thumbnails or frames in the list, so it is safe offline and on a lock screen
 * glance. Every row is a button (no swipe actions).
 */
@Composable
fun QueueScreen(
    state: QueueUiState,
    myUserId: String,
    now: Instant,
    onTab: (ReviewTab) -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (ReviewItem) -> Unit,
    onReverse: (ReviewItem) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
            ReviewTab.entries.forEach { tab ->
                Tab(
                    selected = tab == state.tab,
                    onClick = { onTab(tab) },
                    modifier = Modifier.heightIn(min = MinTarget).testTag("tab-${tab.wire}"),
                    text = {
                        Text(
                            stringResource(
                                if (tab ==
                                    ReviewTab.ACTION_NEEDED
                                ) {
                                    R.string.tab_action_needed
                                } else {
                                    R.string.tab_history
                                }
                            )
                        )
                    }
                )
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.search_label)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = {
                        onQuery("")
                    }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.search_clear)) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = FanousTokens.Spacing.lg,
                vertical = FanousTokens.Spacing.sm
            ).testTag("search")
        )
        if (state.offline) OfflineBanner(state.lastUpdated, now)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(FanousTokens.Spacing.xl), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
                ) {
                    Text(
                        stringResource(
                            when {
                                state.failed -> R.string.error_generic
                                state.query.isNotBlank() -> R.string.queue_no_matches
                                state.tab == ReviewTab.ACTION_NEEDED -> R.string.queue_empty
                                else -> R.string.history_empty
                            }
                        ),
                        modifier = Modifier.testTag("queue-empty")
                    )
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.heightIn(min = MinTarget)) {
                        Text(stringResource(R.string.refresh))
                    }
                }
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().testTag("queue-list")) {
            items(state.items, key = { it.id }) { item ->
                QueueRow(item, myUserId, now, onOpen = { onOpen(item) }, onReverse = { onReverse(item) })
                HorizontalDivider()
            }
            if (state.nextCursor != null) {
                item {
                    TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth().heightIn(min = MinTarget)) {
                        Text(stringResource(R.string.load_more))
                    }
                }
            }
        }
    }
}

@Composable
fun OfflineBanner(lastUpdated: Instant?, now: Instant) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (lastUpdated !=
                null
            ) {
                stringResource(R.string.offline_last_updated, Labels.ago(lastUpdated, now))
            } else {
                stringResource(R.string.error_offline)
            },
            modifier = Modifier
                .padding(FanousTokens.Spacing.md)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("offline-banner")
        )
    }
}

@Composable
private fun QueueRow(item: ReviewItem, myUserId: String, now: Instant, onOpen: () -> Unit, onReverse: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTarget)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.queue_open_item), onClick = onOpen)
            .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md)
            .testTag("row-${item.id}"),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                item.title ?: stringResource(R.string.untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val due = item.slaDueAt
            if (item.isOpen && due != null) {
                Text(
                    Labels.timeLeft(due, now),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (due.isBefore(now)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = FanousTokens.Spacing.sm)
                )
            }
        }
        val channel = listOfNotNull(item.channelName, Labels.trust(item.channelTrust)).joinToString(" · ")
        if (channel.isNotEmpty()) {
            Text(
                channel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
            if (item.escalate) Tag(stringResource(R.string.tag_escalated), MaterialTheme.colorScheme.error)
            if (item.isSensitive) Tag(stringResource(R.string.tag_blurred), MaterialTheme.colorScheme.tertiary)
            // The red Escalated tag already says it; the hold cause would repeat it.
            item.holdCause?.takeUnless { item.escalate && it == HoldCause.ESCALATION }?.let { Tag(stringResource(Labels.holdCause(it))) }
            item.topCategory?.let { Tag(Labels.category(it)) }
        }
        val holder = item.assignedTo
        if (item.isOpen && holder != null && holder != myUserId) {
            Text(
                item.assignedToName?.let { stringResource(R.string.being_reviewed_by, it) } ?: stringResource(R.string.being_reviewed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (!item.isOpen) {
            val by = item.decidedByName
            Text(
                if (by != null) {
                    stringResource(R.string.history_decided_by, Labels.decision(item.decision), by, Labels.ago(item.decidedAt, now))
                } else {
                    stringResource(R.string.history_decided, Labels.decision(item.decision), Labels.ago(item.decidedAt, now))
                },
                style = MaterialTheme.typography.bodySmall
            )
            val until = item.undoUntil
            if (until != null && until.isAfter(now) && item.decidedBy == myUserId) {
                TextButton(onClick = onReverse, modifier = Modifier.heightIn(min = MinTarget).testTag("reverse-${item.id}")) {
                    Text(stringResource(R.string.reverse_decision))
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}
