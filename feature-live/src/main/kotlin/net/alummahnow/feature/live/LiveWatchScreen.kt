package net.alummahnow.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.window.core.layout.WindowSizeClass
import net.alummahnow.core.bridge.model.LiveAuthorRole
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatValidationError
import net.alummahnow.core.bridge.model.LiveEventType
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamKind
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.LanternLoader
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.VideoPoster
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.MediaItems
import net.alummahnow.player.ui.AunVideoPlayer

@Composable
fun LiveWatchScreen(
    state: LiveWatchUiState,
    player: Player?,
    isFullscreen: Boolean,
    isInPictureInPicture: Boolean,
    onToggleFullscreen: () -> Unit,
    onRetry: () -> Unit,
    onToggleReminder: () -> Unit = {},
    onDraftChanged: (String) -> Unit = {},
    onSendChat: () -> Unit = {},
    onSeekToLiveEdge: () -> Unit = {},
    onRequestRemoveMessage: (LiveChatMessage) -> Unit = {},
    onConfirmRemoveMessage: () -> Unit = {},
    onDismissRemoveMessage: () -> Unit = {},
    onTogglePinMessage: (LiveChatMessage) -> Unit = {},
    onRequestReportMessage: (LiveChatMessage) -> Unit = {},
    onConfirmReportMessage: () -> Unit = {},
    onDismissReportMessage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            LiveWatchUiState.Loading -> FullScreenLoading()
            is LiveWatchUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is LiveWatchUiState.Content -> {
                if (player != null && state.stream.status != LiveStreamStatus.SCHEDULED) {
                    PlayLiveEffect(player, state.stream)
                }
                val playerBox: @Composable (Modifier) -> Unit = { m ->
                    LivePlayerArea(
                        stream = state.stream,
                        player = player,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = onToggleFullscreen,
                        controls = !isInPictureInPicture,
                        modifier = m
                    )
                }
                when {
                    isInPictureInPicture || isFullscreen -> playerBox(Modifier.fillMaxSize())
                    else -> LiveWatchLayout(
                        state = state,
                        playerBox = playerBox,
                        onToggleReminder = onToggleReminder,
                        onDraftChanged = onDraftChanged,
                        onSendChat = onSendChat,
                        onSeekToLiveEdge = onSeekToLiveEdge,
                        onRequestRemoveMessage = onRequestRemoveMessage,
                        onTogglePinMessage = onTogglePinMessage,
                        onRequestReportMessage = onRequestReportMessage
                    )
                }
                LiveModerationDialogs(
                    state = state,
                    onConfirmRemoveMessage = onConfirmRemoveMessage,
                    onDismissRemoveMessage = onDismissRemoveMessage,
                    onConfirmReportMessage = onConfirmReportMessage,
                    onDismissReportMessage = onDismissReportMessage
                )
            }
        }
    }
}

@Composable
private fun PlayLiveEffect(player: Player, stream: LiveStream) {
    val locale = currentLocale()
    val playUrl = if (stream.status == LiveStreamStatus.ENDED) stream.vodUrl ?: stream.playbackUrl else stream.playbackUrl
    LaunchedEffect(player, stream.id, playUrl) {
        if (!playUrl.isNullOrBlank() && player.currentMediaItem?.mediaId != stream.id) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(locale.language)
                .build()
            player.setMediaItem(MediaItems.playableLive(stream, playUrl, locale))
            player.prepare()
            player.playWhenReady = true
        }
    }
}

@Composable
private fun LivePlayerArea(
    stream: LiveStream,
    player: Player?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    controls: Boolean,
    modifier: Modifier
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player == null || stream.status == LiveStreamStatus.SCHEDULED) {
            VideoPoster(stream.thumbnailUrl, Modifier.fillMaxSize())
            if (stream.status != LiveStreamStatus.SCHEDULED) {
                LanternLoader()
            }
        } else {
            AunVideoPlayer(
                player = player,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                controlsInitiallyVisible = controls,
                modifier = Modifier.fillMaxSize(),
                poster = stream.thumbnailUrl
            )
        }
    }
}

@Composable
private fun LiveWatchLayout(
    state: LiveWatchUiState.Content,
    playerBox: @Composable (Modifier) -> Unit,
    onToggleReminder: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendChat: () -> Unit,
    onSeekToLiveEdge: () -> Unit,
    onRequestRemoveMessage: (LiveChatMessage) -> Unit,
    onTogglePinMessage: (LiveChatMessage) -> Unit,
    onRequestReportMessage: (LiveChatMessage) -> Unit
) {
    val twoPane = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    if (twoPane) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.weight(0.62f)) {
                playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                LiveStreamSummaryHeader(
                    state = state,
                    onToggleReminder = onToggleReminder,
                    onSeekToLiveEdge = onSeekToLiveEdge
                )
            }
            Column(Modifier.weight(0.38f).padding(horizontal = FanousTokens.Spacing.sm)) {
                LiveChatSection(
                    state = state,
                    onDraftChanged = onDraftChanged,
                    onSendChat = onSendChat,
                    onRequestRemoveMessage = onRequestRemoveMessage,
                    onTogglePinMessage = onTogglePinMessage,
                    onRequestReportMessage = onRequestReportMessage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            LiveStreamSummaryHeader(
                state = state,
                onToggleReminder = onToggleReminder,
                onSeekToLiveEdge = onSeekToLiveEdge
            )
            LiveChatSection(
                state = state,
                onDraftChanged = onDraftChanged,
                onSendChat = onSendChat,
                onRequestRemoveMessage = onRequestRemoveMessage,
                onTogglePinMessage = onTogglePinMessage,
                onRequestReportMessage = onRequestReportMessage,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun LiveStreamSummaryHeader(
    state: LiveWatchUiState.Content,
    onToggleReminder: () -> Unit,
    onSeekToLiveEdge: () -> Unit
) {
    val locale = currentLocale()
    val stream = state.stream
    Column(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.lg)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadge(stream)
            if (state.features.liveEvents && stream.eventType != LiveEventType.NONE) {
                EventBadge(stream.eventType)
            }
            if (stream.status == LiveStreamStatus.LIVE && !state.isAtLiveEdge) {
                FanousButton(
                    text = stringResource(R.string.live_return_to_live),
                    onClick = onSeekToLiveEdge,
                    kind = FanousButtonKind.Secondary,
                    modifier = Modifier.testTag("live-return-edge")
                )
            }
        }
        Text(
            text = stream.title.resolve(locale),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = FanousTokens.Spacing.sm).semantics { heading() }
        )
        val viewerMeta = when (stream.status) {
            LiveStreamStatus.LIVE -> aunString(R.string.live_viewers_watching, numberLabel(stream.viewerCount))
            LiveStreamStatus.SCHEDULED -> stream.scheduledStartAt?.let { aunString(R.string.live_scheduled_for, it) }
            LiveStreamStatus.ENDED -> null
        }
        Text(
            text = joinMeta(stream.channelName, viewerMeta),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FanousTokens.Spacing.xs)
        )
        if (state.features.liveReminders && stream.status == LiveStreamStatus.SCHEDULED) {
            val reminderText = if (state.reminder.reminded) {
                aunString(R.string.live_reminder_set, numberLabel(state.reminder.reminderCount))
            } else {
                stringResource(R.string.live_remind_me)
            }
            FanousButton(
                text = reminderText,
                onClick = onToggleReminder,
                kind = if (state.reminder.reminded) FanousButtonKind.Secondary else FanousButtonKind.Primary,
                enabled = !state.isTogglingReminder,
                modifier = Modifier.padding(top = FanousTokens.Spacing.md).testTag("live-reminder-button")
            )
        }
    }
}

@Composable
private fun StatusBadge(stream: LiveStream) {
    val label = when {
        stream.streamKind == LiveStreamKind.PREMIERE && stream.status == LiveStreamStatus.LIVE ->
            stringResource(R.string.live_badge_premiere)
        stream.status == LiveStreamStatus.LIVE -> stringResource(R.string.live_badge_live)
        stream.status == LiveStreamStatus.SCHEDULED -> stringResource(R.string.live_badge_upcoming)
        else -> stringResource(R.string.live_badge_ended)
    }
    val containerColor = if (stream.status == LiveStreamStatus.LIVE) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (stream.status == LiveStreamStatus.LIVE) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = FanousTokens.Spacing.md, vertical = FanousTokens.Spacing.xs)
    )
}

@Composable
private fun EventBadge(eventType: LiveEventType) {
    val resId = when (eventType) {
        LiveEventType.JUMMAH -> R.string.live_event_jummah
        LiveEventType.TARAWEH -> R.string.live_event_taraweh
        LiveEventType.EID -> R.string.live_event_eid
        LiveEventType.HALQA -> R.string.live_event_halqa
        LiveEventType.NONE -> return
    }
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = FanousTokens.Spacing.md, vertical = FanousTokens.Spacing.xs)
    )
}

@Composable
private fun LiveChatSection(
    state: LiveWatchUiState.Content,
    onDraftChanged: (String) -> Unit,
    onSendChat: () -> Unit,
    onRequestRemoveMessage: (LiveChatMessage) -> Unit,
    onTogglePinMessage: (LiveChatMessage) -> Unit,
    onRequestReportMessage: (LiveChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.features.liveChat) return
    Column(modifier) {
        val headerRes = if (state.stream.status == LiveStreamStatus.ENDED) {
            R.string.live_chat_replay_title
        } else {
            R.string.live_chat_title
        }
        SectionHeader(stringResource(headerRes))
        when {
            state.stream.isKidsContent -> {
                Text(
                    text = stringResource(R.string.live_chat_kids_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(FanousTokens.Spacing.lg)
                )
            }
            !state.stream.chatEnabled || !state.chatSettings.chatEnabled -> {
                Text(
                    text = stringResource(R.string.live_chat_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(FanousTokens.Spacing.lg)
                )
            }
            else -> {
                LiveChatActivePanel(
                    state = state,
                    onDraftChanged = onDraftChanged,
                    onSendChat = onSendChat,
                    onRequestRemoveMessage = onRequestRemoveMessage,
                    onTogglePinMessage = onTogglePinMessage,
                    onRequestReportMessage = onRequestReportMessage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun LiveChatActivePanel(
    state: LiveWatchUiState.Content,
    onDraftChanged: (String) -> Unit,
    onSendChat: () -> Unit,
    onRequestRemoveMessage: (LiveChatMessage) -> Unit,
    onTogglePinMessage: (LiveChatMessage) -> Unit,
    onRequestReportMessage: (LiveChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = currentLocale()
    val messages = state.visibleChatMessages
    Column(modifier) {
        state.pinnedMessage?.let { pinned ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.xs)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(FanousTokens.Spacing.md)
            ) {
                Text(
                    text = stringResource(R.string.live_chat_pinned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = joinMeta(pinned.authorName, pinned.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (state.chatSettings.slowModeSeconds > 0 && state.stream.status == LiveStreamStatus.LIVE) {
            Text(
                text = aunString(R.string.live_chat_slow_mode, state.chatSettings.slowModeSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.xs)
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.live_chat_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(FanousTokens.Spacing.lg)
                    )
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    LiveChatMessageRow(
                        message = msg,
                        showOffset = state.stream.status == LiveStreamStatus.ENDED,
                        offsetLabel = AunFormat.timecode(msg.offsetMs, locale),
                        canModerate = state.canModerate,
                        onRequestRemove = { onRequestRemoveMessage(msg) },
                        onTogglePin = { onTogglePinMessage(msg) },
                        onRequestReport = { onRequestReportMessage(msg) }
                    )
                }
            }
        }
        if (state.stream.status != LiveStreamStatus.ENDED && !state.chatSettings.isReadOnly) {
            HorizontalDivider()
            LiveChatComposer(
                state = state,
                onDraftChanged = onDraftChanged,
                onSendChat = onSendChat
            )
        }
    }
}

@Composable
private fun LiveChatMessageRow(
    message: LiveChatMessage,
    showOffset: Boolean,
    offsetLabel: String,
    canModerate: Boolean,
    onRequestRemove: () -> Unit,
    onTogglePin: () -> Unit,
    onRequestReport: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
    ) {
        val roleLabel = when (message.authorRole) {
            LiveAuthorRole.OWNER -> stringResource(R.string.live_role_owner)
            LiveAuthorRole.MODERATOR -> stringResource(R.string.live_role_moderator)
            LiveAuthorRole.VIEWER -> null
        }
        Text(
            text = joinMeta(if (showOffset) offsetLabel else null, message.authorName, roleLabel),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
            if (canModerate) {
                TextButton(onClick = onTogglePin) {
                    Text(
                        stringResource(
                            if (message.isPinned) R.string.live_chat_mod_unpin else R.string.live_chat_mod_pin
                        )
                    )
                }
                TextButton(onClick = onRequestRemove) {
                    Text(stringResource(R.string.live_chat_mod_remove), color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onRequestReport) {
                Text(stringResource(R.string.live_chat_report))
            }
        }
    }
}

@Composable
private fun LiveChatComposer(
    state: LiveWatchUiState.Content,
    onDraftChanged: (String) -> Unit,
    onSendChat: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.md)) {
        val errorRes = when {
            state.chatError == LiveChatValidationError.EMPTY -> R.string.live_chat_error_empty
            state.chatError == LiveChatValidationError.TOO_LONG -> R.string.live_chat_error_too_long
            state.chatError == LiveChatValidationError.LINKS_NOT_ALLOWED -> R.string.live_chat_error_links
            state.chatError == LiveChatValidationError.BLOCKED_CONTENT -> R.string.live_chat_error_blocked
            state.sendFailed -> R.string.live_chat_error_send
            else -> null
        }
        if (errorRes != null) {
            Text(
                text = stringResource(errorRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = FanousTokens.Spacing.xs)
            )
        }
        if (state.reportSubmitted) {
            Text(
                text = stringResource(R.string.live_chat_report_sent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = FanousTokens.Spacing.xs)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.draftMessage,
                onValueChange = onDraftChanged,
                placeholder = { Text(stringResource(R.string.live_chat_placeholder)) },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("live-chat-input")
            )
            FanousButton(
                text = stringResource(R.string.live_chat_send),
                onClick = onSendChat,
                kind = FanousButtonKind.Primary,
                enabled = !state.isSendingChat,
                modifier = Modifier.testTag("live-chat-send")
            )
        }
    }
}

@Composable
private fun LiveModerationDialogs(
    state: LiveWatchUiState.Content,
    onConfirmRemoveMessage: () -> Unit,
    onDismissRemoveMessage: () -> Unit,
    onConfirmReportMessage: () -> Unit,
    onDismissReportMessage: () -> Unit
) {
    if (state.pendingRemovalMessage != null) {
        ConfirmDialog(
            title = stringResource(R.string.live_chat_mod_remove_title),
            message = stringResource(R.string.live_chat_mod_remove_body),
            confirmLabel = stringResource(R.string.live_chat_mod_remove),
            onConfirm = onConfirmRemoveMessage,
            onDismiss = onDismissRemoveMessage,
            destructive = true
        )
    }
    if (state.pendingReportMessage != null) {
        ConfirmDialog(
            title = stringResource(R.string.live_chat_report_title),
            message = stringResource(R.string.live_chat_report_body),
            confirmLabel = stringResource(R.string.live_chat_report),
            onConfirm = onConfirmReportMessage,
            onDismiss = onDismissReportMessage,
            destructive = false
        )
    }
}
