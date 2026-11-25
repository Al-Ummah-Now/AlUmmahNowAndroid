package net.alummahnow.feature.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.Thumbnail
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.dateTimeLabel
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

internal fun LiveEventType.label(): Int = when (this) {
    LiveEventType.JUMUAH -> R.string.live_event_jumuah
    LiveEventType.TARAWEEH -> R.string.live_event_taraweeh
    LiveEventType.CONFERENCE -> R.string.live_event_conference
    LiveEventType.HALQA -> R.string.live_event_halqa
}

@Composable
internal fun startLabel(at: Instant): String = dateTimeLabel(at.atZone(ZoneId.systemDefault()))

/** A small label beside a title or on a card: Live, Premiere, an event tag. */
@Composable
internal fun LiveTag(text: String, isLive: Boolean = false) {
    val f = LocalFanous.current
    val shape = if (isLive) {
        Modifier.background(f.parts.badgeLiveBg.color, CircleShape)
    } else {
        Modifier.border(1.dp, f.colors.outline.color, CircleShape)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (isLive) f.parts.badgeLiveText.color else f.colors.textPrimary.color,
        modifier = Modifier.then(shape).padding(horizontal = 10.dp, vertical = 2.dp)
    )
}

/** What a stream is beside its status. Nothing for a plain stream that is not on air. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveTags(kind: LiveKind, event: LiveEventType?, isLive: Boolean, modifier: Modifier = Modifier) {
    if (!isLive && kind == LiveKind.LIVE && event == null) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isLive) LiveTag(stringResource(R.string.live_now), isLive = true)
        if (kind == LiveKind.PREMIERE) LiveTag(stringResource(R.string.live_premiere))
        if (event != null) LiveTag(stringResource(event.label()))
    }
}

@Composable
internal fun LiveCard(stream: LiveStream, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    val whenText = if (stream.status == LiveStatus.LIVE) {
        stream.viewers?.let { aunString(R.string.live_watching, numberLabel(it)) }
    } else {
        stream.scheduledStart?.let { startLabel(it) }
    }
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clickable(onClick = onClick)
            .testTag("live-card-${stream.id}")
            .padding(bottom = FanousTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)
    ) {
        Thumbnail(stream.thumbnailUrl, null)
        Text(
            stream.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.xs)
        )
        LiveTags(
            stream.kind,
            stream.eventType,
            stream.status == LiveStatus.LIVE,
            Modifier.padding(horizontal = FanousTokens.Spacing.xs)
        )
        val meta = joinMeta(stream.channelName, whenText)
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = FanousTokens.Spacing.xs)
            )
        }
    }
}

/** Home's live rail: on air first, then coming. Nothing when there is none. */
@Composable
fun LiveRail(streams: List<LiveStream>, onOpen: (String) -> Unit, onSeeAll: () -> Unit, cardWidth: Dp, modifier: Modifier = Modifier) {
    if (streams.isEmpty()) return
    Column(modifier.padding(vertical = FanousTokens.Spacing.sm).testTag("rail-live")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeader(stringResource(R.string.live_title), Modifier.weight(1f))
            FanousButton(
                stringResource(R.string.live_see_all),
                onClick = onSeeAll,
                kind = FanousButtonKind.Ghost,
                modifier = Modifier.padding(end = FanousTokens.Spacing.sm).testTag("live-see-all")
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = FanousTokens.Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            items(streams, key = { it.id }) { LiveCard(it, onClick = { onOpen(it.id) }, width = cardWidth) }
        }
    }
}

/** In place of the player: what is coming, over, or cancelled. */
@Composable
internal fun LiveNotice(content: LiveWatchContent, modifier: Modifier = Modifier) {
    val start = content.live.scheduledStart ?: content.stream.scheduledStart
    val premiere = content.isPremiere
    Column(
        modifier.fillMaxWidth().padding(FanousTokens.Spacing.lg).testTag("live-notice"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm, Alignment.CenterVertically)
    ) {
        when (content.mode) {
            LiveMode.SOON -> {
                Countdown(content)
                NoticeText(stringResource(if (premiere) R.string.live_premiere_together else R.string.live_soon_body))
                if (start != null) NoticeText(aunString(R.string.live_scheduled_for, startLabel(start)))
            }
            LiveMode.CANCELLED -> NoticeTitle(stringResource(if (premiere) R.string.live_premiere_cancelled else R.string.live_cancelled))
            else -> {
                NoticeTitle(stringResource(if (premiere) R.string.live_premiere_ended else R.string.live_ended))
                if (!premiere) NoticeText(stringResource(R.string.live_recording_preparing))
            }
        }
    }
}

@Composable
private fun Countdown(content: LiveWatchContent) {
    val left = content.untilStartMs
    when {
        !content.isPremiere -> NoticeTitle(stringResource(R.string.live_soon))
        left == null || left <= 0 -> NoticeTitle(stringResource(R.string.live_premiere_starting))
        left >= DAY_MS -> {
            val days = (left / DAY_MS).toInt()
            NoticeTitle(aunPlural(R.plurals.live_premiere_in_days, days, days))
        }
        else -> {
            NoticeText(stringResource(R.string.live_premiere_in))
            // A clock reads left to right in Arabic too
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    durationLabel((left + MS - 1) / MS),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("live-countdown")
                )
            }
        }
    }
}

@Composable
private fun NoticeTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)

@Composable
private fun NoticeText(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign = TextAlign.Center
)

/** Title, tags, and how many are watching. */
@Composable
internal fun LiveDetails(
    content: LiveWatchContent,
    modifier: Modifier = Modifier,
    safety: LiveSafetyActions? = null
) {
    val watching = content.viewers?.takeIf { content.mode == LiveMode.LIVE }?.let { aunString(R.string.live_watching, numberLabel(it)) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val channelName = content.stream.channelName?.takeIf { it.isNotBlank() }
    val hasChannelId = !content.stream.channelId.isNullOrBlank()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
        LiveTags(content.live.kind, content.live.eventType, content.mode == LiveMode.LIVE)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                content.stream.title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() }.testTag("live-title")
            )
            if (safety != null) {
                Box {
                    IconButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.testTag("live-more-actions")
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.live_more_actions),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // The card under it can have the same colour: the edge tells them apart.
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.live_report_stream)) },
                            onClick = {
                                menuOpen = false
                                safety.onReportStream(content.stream)
                            }
                        )
                        if (hasChannelId) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.live_report_channel)) },
                                modifier = Modifier.testTag("live-report-channel-item"),
                                onClick = {
                                    menuOpen = false
                                    safety.onReportChannel(content.stream)
                                }
                            )
                        }
                        if (channelName != null || hasChannelId) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.live_block_channel)) },
                                modifier = Modifier.testTag("live-block-channel-item"),
                                onClick = {
                                    menuOpen = false
                                    confirmBlock = true
                                }
                            )
                        }
                    }
                }
            }
        }
        val meta = joinMeta(content.stream.channelName, watching)
        if (meta.isNotEmpty()) {
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content.stream.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    if (confirmBlock && safety != null) {
        ConfirmDialog(
            title = if (channelName != null) {
                aunString(R.string.live_block_channel_title, isolate(channelName))
            } else {
                stringResource(R.string.live_block_channel_title_unnamed)
            },
            message = stringResource(R.string.live_block_channel_body),
            confirmLabel = stringResource(R.string.live_block_confirm),
            onConfirm = {
                confirmBlock = false
                safety.onBlockChannel(content.stream)
            },
            onDismiss = { confirmBlock = false }
        )
    }
}

/** The messages beside a stream, or as they were beside its recording. Reading only. */
@Composable
internal fun LiveChat(
    content: LiveWatchContent,
    modifier: Modifier = Modifier,
    safety: LiveSafetyActions? = null
) {
    val replay = content.mode == LiveMode.RECORDING
    val list = rememberLazyListState()
    val last = content.messages.lastOrNull()?.id
    LaunchedEffect(last) { if (last != null) list.scrollToItem(content.messages.lastIndex) }
    Column(modifier.testTag("live-chat")) {
        Text(
            stringResource(if (replay) R.string.live_chat_replay else R.string.live_chat),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(FanousTokens.Spacing.md).semantics { heading() }
        )
        HorizontalDivider()
        content.pinned?.let { pinned ->
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(FanousTokens.Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
            ) {
                Icon(Icons.Outlined.PushPin, contentDescription = stringResource(R.string.live_chat_pinned))
                Message(pinned, showTime = false, stream = content.stream, safety = safety)
            }
        }
        if (content.messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(FanousTokens.Spacing.lg), contentAlignment = Alignment.Center) {
                NoticeText(stringResource(if (replay) R.string.live_chat_replay_empty else R.string.live_chat_empty))
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(FanousTokens.Spacing.md),
                verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
            ) {
                items(content.messages, key = { it.id }) { Message(it, showTime = replay, stream = content.stream, safety = safety) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Message(
    message: LiveChatMessage,
    showTime: Boolean,
    stream: LiveStream,
    safety: LiveSafetyActions?
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("live-chat-message-${message.id}"),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                if (showTime) MessageMeta(durationLabel(message.offsetMs / MS))
                MessageMeta(message.authorName, FontWeight.SemiBold)
                when (message.role) {
                    LiveChatMessage.Role.OWNER -> LiveTag(stringResource(R.string.live_chat_host))
                    LiveChatMessage.Role.MODERATOR -> LiveTag(stringResource(R.string.live_chat_moderator))
                    null -> Unit
                }
            }
            Text(message.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (safety != null) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag("live-chat-more-${message.id}")
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = aunString(R.string.live_more_actions_for, isolate(message.authorName)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // The card under it can have the same colour: the edge tells them apart.
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.live_report_message)) },
                        onClick = {
                            menuOpen = false
                            safety.onReportMessage(stream, message)
                        }
                    )
                    if (!message.userId.isNullOrBlank()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.live_block_user)) },
                            modifier = Modifier.testTag("live-block-user-item"),
                            onClick = {
                                menuOpen = false
                                confirmBlock = true
                            }
                        )
                    }
                }
            }
        }
    }
    if (confirmBlock && safety != null) {
        ConfirmDialog(
            title = aunString(R.string.live_block_user_title, isolate(message.authorName)),
            message = stringResource(R.string.live_block_user_body),
            confirmLabel = stringResource(R.string.live_block_confirm),
            onConfirm = {
                confirmBlock = false
                safety.onBlockUser(message)
            },
            onDismiss = { confirmBlock = false }
        )
    }
}

@Composable
private fun MessageMeta(text: String, weight: FontWeight? = null) =
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = weight, color = MaterialTheme.colorScheme.onSurfaceVariant)

private const val MS = 1_000L
private const val DAY_MS = 86_400_000L
