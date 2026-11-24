package net.alummahnow.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.notifications.CalendarEvent
import net.alummahnow.core.bridge.notifications.EventsRepository
import net.alummahnow.core.bridge.notifications.NotificationEntry
import net.alummahnow.core.bridge.notifications.NotificationQuietHours
import net.alummahnow.core.bridge.notifications.NotificationsRepository
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.minuteOfDayLabel
import net.alummahnow.design.theme.FanousTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSheet(
    repository: NotificationsRepository,
    quietHoursEnabled: Boolean,
    onBadgeChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<NotificationEntry>>(emptyList()) }
    var quietHours by remember { mutableStateOf(NotificationQuietHours()) }

    LaunchedEffect(repository, quietHoursEnabled) {
        runCatching {
            val page = repository.list()
            items = page.items
            val updated = if (page.items.isNotEmpty()) repository.markSeen(page.items.map { it.id }) else page.badge
            onBadgeChange(updated.visibleCount)
            if (quietHoursEnabled) {
                quietHours = repository.quietHours()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            item(key = "header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(DesignR.string.aun_notifications),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                    if (items.any { it.isUnread }) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val badge = repository.markAllRead()
                                    onBadgeChange(badge.visibleCount)
                                    items = items.map { it.copy(isUnread = false) }
                                }
                            }
                        }) {
                            Text(stringResource(DesignR.string.aun_notifications_mark_all_read))
                        }
                    }
                }
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(DesignR.string.aun_notifications_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(items, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().padding(vertical = FanousTokens.Spacing.xs)) {
                        Text(notificationTitle(item), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (quietHoursEnabled) {
                item(key = "divider") { HorizontalDivider() }
                item(key = "quiet-hours") {
                    QuietHoursSection(
                        quietHours = quietHours,
                        onUpdate = { next ->
                            quietHours = next
                            scope.launch { runCatching { quietHours = repository.updateQuietHours(next) } }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun msgOrFallback(arg: String, msgRes: Int, fallbackRes: Int): String =
    if (arg.isNotBlank()) aunString(msgRes, arg) else stringResource(fallbackRes)

@Composable
private fun msgOrFallback2(first: String, second: String, msgRes: Int, fallbackRes: Int): String =
    if (first.isNotBlank() && second.isNotBlank()) {
        aunString(msgRes, first, second)
    } else {
        stringResource(fallbackRes)
    }

@Composable
@Suppress("CyclomaticComplexMethod")
internal fun notificationTitle(item: NotificationEntry): String {
    val isAr = currentLocale().language == "ar"
    val video = item.params["videoTitle"].orEmpty()
    val stream = item.params["streamTitle"].orEmpty().ifBlank { video }
    val channel = item.params["channelName"].orEmpty().ifBlank { item.params["channel"].orEmpty() }
    val tierLocalized = if (isAr) item.params["tier_ar"] else item.params["tier_en"]
    val tier = tierLocalized.orEmpty().ifBlank { item.params["tier"].orEmpty() }
    val inviter = item.params["inviter"].orEmpty()
    val member = item.params["member"].orEmpty()
    val actor = item.params["actorName"].orEmpty()

    return when (item.notificationKey) {
        "newVideo" -> msgOrFallback2(
            channel,
            video,
            DesignR.string.aun_notif_new_video_msg,
            DesignR.string.aun_notif_new_video
        )
        "liveStarted" -> msgOrFallback2(
            channel,
            stream,
            DesignR.string.aun_notif_live_started_msg,
            DesignR.string.aun_notif_live_started
        )
        "commentReply" -> msgOrFallback(
            actor,
            DesignR.string.aun_notif_comment_reply_msg,
            DesignR.string.aun_notif_comment_reply
        )
        "newComment" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_new_comment_msg,
            DesignR.string.aun_notif_new_comment
        )
        "newSubscriber" -> msgOrFallback(
            channel,
            DesignR.string.aun_notif_new_subscriber_msg,
            DesignR.string.aun_notif_new_subscriber
        )
        "uploadReady" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_upload_ready_msg,
            DesignR.string.aun_notif_upload_ready
        )
        "uploadFailed" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_upload_failed_msg,
            DesignR.string.aun_notif_upload_failed
        )
        "accountWarning" -> stringResource(DesignR.string.aun_notif_account_warning)
        "reviewApproved" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_review_approved_msg,
            DesignR.string.aun_notif_review_approved
        )
        "reviewRejected" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_review_rejected_msg,
            DesignR.string.aun_notif_review_rejected
        )
        "reviewNeeded" -> msgOrFallback(
            video,
            DesignR.string.aun_notif_review_needed_msg,
            DesignR.string.aun_notif_review_needed
        )
        "youtubeImportFinished" -> stringResource(DesignR.string.aun_notif_youtube_import)
        "channelInviteReceived" -> msgOrFallback2(
            inviter,
            channel,
            DesignR.string.aun_notif_channel_invite_msg,
            DesignR.string.aun_notif_channel_invite
        )
        "channelInviteAccepted" -> msgOrFallback2(
            member,
            channel,
            DesignR.string.aun_notif_channel_invite_accepted_msg,
            DesignR.string.aun_notif_channel_invite_accepted
        )
        "channelRoleChanged" -> stringResource(DesignR.string.aun_notif_channel_role_changed)
        "channelMemberRemoved" -> stringResource(DesignR.string.aun_notif_channel_member_removed)
        "channelMemberLeft" -> stringResource(DesignR.string.aun_notif_channel_member_left)
        "tierRequestApproved" -> msgOrFallback(
            tier,
            DesignR.string.aun_notif_tier_approved_msg,
            DesignR.string.aun_notif_tier_approved
        )
        "tierRequestDeclined" -> msgOrFallback(
            tier,
            DesignR.string.aun_notif_tier_declined_msg,
            DesignR.string.aun_notif_tier_declined
        )
        "tierRequestReceived" -> msgOrFallback(
            tier,
            DesignR.string.aun_notif_tier_received_msg,
            DesignR.string.aun_notif_tier_received
        )
        "tierChanged" -> stringResource(DesignR.string.aun_notif_tier_changed)
        else -> item.title.ifBlank { stringResource(DesignR.string.aun_notif_default) }
    }
}

@Composable
private fun QuietHoursSection(
    quietHours: NotificationQuietHours,
    onUpdate: (NotificationQuietHours) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
        Text(
            stringResource(DesignR.string.aun_quiet_hours),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(DesignR.string.aun_quiet_hours_daily), style = MaterialTheme.typography.bodyLarge)
                Text(
                    aunString(
                        DesignR.string.aun_quiet_hours_window,
                        isolate(minuteOfDayLabel(quietHours.startMinute)),
                        isolate(minuteOfDayLabel(quietHours.endMinute)),
                        isolate(quietHours.timezone)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = quietHours.enabled,
                onCheckedChange = { onUpdate(quietHours.copy(enabled = it)) }
            )
        }
        // Hidden until Epic 21.1 supplies non-empty locally computed prayer windows on this device.
        if (quietHours.prayerWindows.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(DesignR.string.aun_quiet_hours_prayer),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = quietHours.prayerQuietEnabled,
                    onCheckedChange = { onUpdate(quietHours.copy(prayerQuietEnabled = it)) }
                )
            }
        }
        Text(
            stringResource(DesignR.string.aun_quiet_hours_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsCalendarSheet(
    repository: EventsRepository,
    onOpenEvent: (CalendarEvent) -> Unit,
    onDismiss: () -> Unit
) {
    val locale = currentLocale()
    var filter by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }

    LaunchedEffect(repository, filter) {
        runCatching { events = repository.upcomingEvents(eventType = filter) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
                    Text(
                        stringResource(DesignR.string.aun_events),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(
                        stringResource(DesignR.string.aun_events_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item(key = "filters") {
                val options = listOf(
                    null to DesignR.string.aun_events_filter_all,
                    "live" to DesignR.string.aun_events_filter_live,
                    "premiere" to DesignR.string.aun_events_filter_premiere,
                    "halqa" to DesignR.string.aun_events_filter_halqa
                )
                Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                    options.forEach { (key, labelRes) ->
                        FilterChip(
                            selected = filter == key,
                            onClick = { filter = key },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }
            }
            if (events.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(DesignR.string.aun_events_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(events, key = { it.streamId }) { event ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenEvent(event) }
                            .padding(vertical = FanousTokens.Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)
                    ) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            joinMeta(event.localizedChannelName(locale.language), event.scheduledStartAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
