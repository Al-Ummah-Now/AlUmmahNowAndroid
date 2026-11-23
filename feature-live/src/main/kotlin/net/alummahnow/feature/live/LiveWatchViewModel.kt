package net.alummahnow.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.LiveChatFastFilter
import net.alummahnow.core.bridge.model.LiveChatMessage
import net.alummahnow.core.bridge.model.LiveChatSettings
import net.alummahnow.core.bridge.model.LiveChatStatus
import net.alummahnow.core.bridge.model.LiveChatValidationError
import net.alummahnow.core.bridge.model.LiveModerationAction
import net.alummahnow.core.bridge.model.LiveReminderStatus
import net.alummahnow.core.bridge.model.LiveReportReason
import net.alummahnow.core.bridge.model.LiveStream
import net.alummahnow.core.bridge.model.LiveStreamStatus
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.design.components.ErrorKind

sealed interface LiveWatchUiState {
    data object Loading : LiveWatchUiState
    data class Content(
        val stream: LiveStream,
        val features: UserFeatures,
        val chatMessages: List<LiveChatMessage> = emptyList(),
        val pinnedMessage: LiveChatMessage? = null,
        val chatSettings: LiveChatSettings = LiveChatSettings(),
        val reminder: LiveReminderStatus = LiveReminderStatus(false, 0),
        val draftMessage: String = "",
        val chatError: LiveChatValidationError? = null,
        val sendFailed: Boolean = false,
        val isSendingChat: Boolean = false,
        val isTogglingReminder: Boolean = false,
        val isAtLiveEdge: Boolean = true,
        val playbackPositionMs: Long = 0,
        val canModerate: Boolean = false,
        val pendingRemovalMessage: LiveChatMessage? = null,
        val pendingReportMessage: LiveChatMessage? = null,
        val reportSubmitted: Boolean = false
    ) : LiveWatchUiState {
        /** Visible chat messages: live streams show all published items; ended VOD recordings replay up to [playbackPositionMs]. */
        val visibleChatMessages: List<LiveChatMessage>
            get() {
                val published = chatMessages.filter { it.status == LiveChatStatus.PUBLISHED }
                return if (stream.status == LiveStreamStatus.ENDED) {
                    published.filter { it.offsetMs <= playbackPositionMs }
                } else {
                    published
                }
            }

        /** Whether the chat panel is shown (feature switch ON, chat enabled on stream, and not kids content). */
        val showChatPanel: Boolean
            get() = features.liveChat && !stream.isKidsContent && stream.chatEnabled && chatSettings.featureEnabled
    }

    data class Error(val kind: ErrorKind) : LiveWatchUiState
}

class LiveWatchViewModel(
    private val catalog: CatalogRepository,
    private val streamId: String,
    private val features: UserFeatures = UserFeatures(
        setOf(
            UserFeatures.LIVE,
            UserFeatures.LIVE_CHAT,
            UserFeatures.LIVE_PREMIERES,
            UserFeatures.LIVE_REMINDERS,
            UserFeatures.LIVE_EVENTS
        )
    ),
    private val canModerate: Boolean = false,
    private val viewerId: String = UUID.randomUUID().toString()
) : ViewModel() {
    private val mutableState = MutableStateFlow<LiveWatchUiState>(LiveWatchUiState.Loading)
    val state: StateFlow<LiveWatchUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.value = LiveWatchUiState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                val stream = catalog.liveStream(streamId)
                val stateDto = runCatching { catalog.liveStreamState(streamId) }.getOrNull()
                val merged = if (stateDto != null) {
                    stream.copy(
                        status = stateDto.status,
                        viewerCount = stateDto.viewerCount,
                        peakViewerCount = maxOf(stream.peakViewerCount, stateDto.peakViewerCount),
                        playbackUrl = stateDto.playbackUrl ?: stream.playbackUrl,
                        vodUrl = stateDto.vodUrl ?: stream.vodUrl,
                        chatEnabled = stateDto.chatEnabled,
                        slowModeSeconds = stateDto.slowModeSeconds
                    )
                } else {
                    stream
                }
                val reminder = if (features.liveReminders && merged.status == LiveStreamStatus.SCHEDULED) {
                    runCatching { catalog.liveReminderStatus(streamId) }.getOrDefault(LiveReminderStatus(false, 0))
                } else {
                    LiveReminderStatus(false, 0)
                }
                val chatPage = if (features.liveChat && !merged.isKidsContent && merged.chatEnabled) {
                    runCatching { catalog.liveChatMessages(streamId) }.getOrNull()
                } else {
                    null
                }
                LiveWatchUiState.Content(
                    stream = merged,
                    features = features,
                    chatMessages = chatPage?.items.orEmpty(),
                    pinnedMessage = chatPage?.pinnedMessage,
                    chatSettings = chatPage?.settings ?: LiveChatSettings(
                        chatEnabled = merged.chatEnabled,
                        slowModeSeconds = merged.slowModeSeconds
                    ),
                    reminder = reminder,
                    canModerate = canModerate
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreError) {
                AunLog.w("live", "load failed", "id" to streamId, "error" to e.message)
                LiveWatchUiState.Error(
                    when (e) {
                        is CoreError.Network -> ErrorKind.NETWORK
                        is CoreError.NotFound -> ErrorKind.NOT_FOUND
                        else -> ErrorKind.GENERIC
                    }
                )
            }
        }
    }

    fun beatViewer() {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        if (current.stream.status != LiveStreamStatus.LIVE) return
        viewModelScope.launch {
            runCatching { catalog.sendLiveHeartbeat(streamId, viewerId) }.onSuccess { liveState ->
                updateContent { content ->
                    content.copy(
                        stream = content.stream.copy(
                            status = liveState.status,
                            viewerCount = liveState.viewerCount,
                            peakViewerCount = maxOf(content.stream.peakViewerCount, liveState.peakViewerCount),
                            playbackUrl = liveState.playbackUrl ?: content.stream.playbackUrl,
                            vodUrl = liveState.vodUrl ?: content.stream.vodUrl
                        )
                    )
                }
            }
        }
    }

    fun refreshChat() {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        if (!current.showChatPanel) return
        val lastId = current.chatMessages.lastOrNull()?.id
        viewModelScope.launch {
            runCatching {
                catalog.liveChatMessages(
                    streamId = streamId,
                    afterId = if (current.stream.status == LiveStreamStatus.ENDED) null else lastId
                )
            }.onSuccess { page ->
                updateContent { content ->
                    val merged = if (content.stream.status == LiveStreamStatus.ENDED || lastId == null) {
                        page.items
                    } else {
                        (content.chatMessages + page.items).distinctBy { it.id }
                    }
                    content.copy(
                        chatMessages = merged,
                        pinnedMessage = page.pinnedMessage ?: content.pinnedMessage,
                        chatSettings = page.settings
                    )
                }
            }
        }
    }

    fun onDraftChanged(text: String) {
        updateContent { it.copy(draftMessage = text, chatError = null, sendFailed = false) }
    }

    fun sendChatMessage() {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        if (!current.showChatPanel || current.isSendingChat) return
        val validationError = LiveChatFastFilter.validate(
            current.draftMessage,
            linksAllowed = current.chatSettings.linksAllowed
        )
        if (validationError != null) {
            updateContent { it.copy(chatError = validationError, sendFailed = false) }
            return
        }
        val textToSend = current.draftMessage.trim()
        updateContent { it.copy(isSendingChat = true, chatError = null, sendFailed = false) }
        viewModelScope.launch {
            runCatching {
                catalog.postLiveChatMessage(streamId, textToSend, current.playbackPositionMs)
            }.onSuccess { message ->
                updateContent { content ->
                    content.copy(
                        chatMessages = (content.chatMessages + message).distinctBy { it.id },
                        draftMessage = "",
                        isSendingChat = false
                    )
                }
            }.onFailure {
                updateContent { it.copy(isSendingChat = false, sendFailed = true) }
            }
        }
    }

    fun toggleReminder() {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        if (!current.features.liveReminders || current.isTogglingReminder) return
        val target = !current.reminder.reminded
        updateContent { it.copy(isTogglingReminder = true) }
        viewModelScope.launch {
            runCatching { catalog.setLiveReminder(streamId, target) }
                .onSuccess { status ->
                    updateContent { it.copy(reminder = status, isTogglingReminder = false) }
                }
                .onFailure {
                    updateContent { it.copy(isTogglingReminder = false) }
                }
        }
    }

    fun updatePlaybackState(positionMs: Long, isAtLiveEdge: Boolean) {
        updateContent {
            it.copy(
                playbackPositionMs = positionMs.coerceAtLeast(0L),
                isAtLiveEdge = isAtLiveEdge
            )
        }
    }

    fun requestRemoveMessage(message: LiveChatMessage) {
        updateContent { it.copy(pendingRemovalMessage = message) }
    }

    fun dismissRemoveMessage() {
        updateContent { it.copy(pendingRemovalMessage = null) }
    }

    fun confirmRemoveMessage() {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        val target = current.pendingRemovalMessage ?: return
        updateContent { it.copy(pendingRemovalMessage = null) }
        viewModelScope.launch {
            runCatching {
                catalog.moderateLiveChatMessage(streamId, target.id, LiveModerationAction.REMOVE)
            }.onSuccess { updated ->
                updateContent { content ->
                    content.copy(
                        chatMessages = content.chatMessages.map { if (it.id == updated.id) updated else it },
                        pinnedMessage = if (content.pinnedMessage?.id == updated.id) null else content.pinnedMessage
                    )
                }
            }
        }
    }

    fun togglePinMessage(message: LiveChatMessage) {
        val action = if (message.isPinned) LiveModerationAction.UNPIN else LiveModerationAction.PIN
        viewModelScope.launch {
            runCatching {
                catalog.moderateLiveChatMessage(streamId, message.id, action)
            }.onSuccess { updated ->
                updateContent { content ->
                    content.copy(
                        chatMessages = content.chatMessages.map {
                            if (it.id == updated.id) updated else it.copy(isPinned = false)
                        },
                        pinnedMessage = if (updated.isPinned) updated else null
                    )
                }
            }
        }
    }

    fun requestReportMessage(message: LiveChatMessage) {
        updateContent { it.copy(pendingReportMessage = message, reportSubmitted = false) }
    }

    fun dismissReportMessage() {
        updateContent { it.copy(pendingReportMessage = null) }
    }

    fun confirmReportMessage(reason: LiveReportReason = LiveReportReason.ABUSE) {
        val current = mutableState.value as? LiveWatchUiState.Content ?: return
        val target = current.pendingReportMessage ?: return
        updateContent { it.copy(pendingReportMessage = null) }
        viewModelScope.launch {
            runCatching {
                catalog.reportLiveChatMessage(streamId, target.id, reason)
            }.onSuccess {
                updateContent { it.copy(reportSubmitted = true) }
            }
        }
    }

    private fun updateContent(transform: (LiveWatchUiState.Content) -> LiveWatchUiState.Content) {
        mutableState.update { state ->
            if (state is LiveWatchUiState.Content) transform(state) else state
        }
    }

    class Factory(
        private val catalog: CatalogRepository,
        private val streamId: String,
        private val features: UserFeatures = UserFeatures.NONE
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LiveWatchViewModel(catalog, streamId, features) as T
    }
}
