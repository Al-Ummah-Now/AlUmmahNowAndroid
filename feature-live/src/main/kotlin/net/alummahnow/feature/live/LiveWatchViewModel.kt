package net.alummahnow.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.live.LiveChatMessage
import net.alummahnow.core.bridge.live.LiveChatPage
import net.alummahnow.core.bridge.live.LiveKind
import net.alummahnow.core.bridge.live.LiveRepository
import net.alummahnow.core.bridge.live.LiveState
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.design.components.ErrorKind

enum class LiveMode { LIVE, RECORDING, SOON, ENDED, CANCELLED }

internal fun modeOf(state: LiveState): LiveMode = when {
    state.status == LiveStatus.CANCELLED -> LiveMode.CANCELLED
    state.kind == LiveKind.PREMIERE -> premiereMode(state)
    state.status == LiveStatus.ENDED || state.sessionStatus == "ended" ->
        if (state.vodUrl == null) LiveMode.ENDED else LiveMode.RECORDING
    (state.status == LiveStatus.LIVE || state.sessionStatus == "live") && state.playbackUrl != null -> LiveMode.LIVE
    else -> LiveMode.SOON
}

private fun premiereMode(state: LiveState): LiveMode = when {
    state.status == LiveStatus.LIVE && state.premiereVideoId != null -> LiveMode.LIVE
    state.status == LiveStatus.ENDED -> if (state.premiereVideoId == null) LiveMode.ENDED else LiveMode.RECORDING
    else -> LiveMode.SOON
}

/** What the player is given: a stream's or recording's address, or a premiere's video. */
sealed interface LiveMedia {
    data class Address(val url: String, val mediaId: String) : LiveMedia

    data class Premiere(val video: VideoDetail, val startMs: Long) : LiveMedia
}

data class LiveWatchContent(
    val stream: LiveStream,
    val live: LiveState,
    val mode: LiveMode,
    val media: LiveMedia? = null,
    val viewers: Long? = null,
    val messages: List<LiveChatMessage> = emptyList(),
    val pinned: LiveChatMessage? = null,
    /** The viewer went back or paused; the stream or premiere goes on without them. */
    val isBehind: Boolean = false,
    /** Milliseconds to a premiere's start; null when it has no countdown. */
    val untilStartMs: Long? = null
) {
    val isPremiere: Boolean get() = live.kind == LiveKind.PREMIERE

    val hasChat: Boolean
        get() = when (mode) {
            LiveMode.LIVE, LiveMode.SOON -> live.chatEnabled
            LiveMode.RECORDING -> live.chatReplay
            LiveMode.ENDED, LiveMode.CANCELLED -> false
        }
}

sealed interface LiveWatchUiState {
    data object Loading : LiveWatchUiState
    data class Content(val content: LiveWatchContent) : LiveWatchUiState
    data class Error(val kind: ErrorKind) : LiveWatchUiState
}

/** One live stream or premiere: the countdown, the stream, its recording, and the chat beside it. */
class LiveWatchViewModel(
    private val streamId: String,
    private val live: LiveRepository,
    private val catalog: CatalogRepository,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val mutableState = MutableStateFlow<LiveWatchUiState>(LiveWatchUiState.Loading)
    val state: StateFlow<LiveWatchUiState> = mutableState.asStateFlow()

    private val viewerId = UUID.randomUUID().toString()
    private val follower = PremiereFollower()
    private var premiere: PremiereClock? = null
    private var isGone = false
    private var stateAt = Instant.EPOCH
    private var chatDue = Instant.EPOCH
    private var beatAt = Instant.EPOCH
    private val replay = mutableMapOf<Long, List<LiveChatMessage>>()

    private val content: LiveWatchContent? get() = (mutableState.value as? LiveWatchUiState.Content)?.content

    init {
        load()
    }

    fun load() {
        mutableState.value = LiveWatchUiState.Loading
        viewModelScope.launch {
            try {
                coroutineScope {
                    val stream = async { live.stream(streamId) }
                    val liveState = async { live.state(streamId) }
                    show(LiveWatchContent(stream.await(), liveState.await(), LiveMode.SOON))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreError) {
                AunLog.w("live", "watch failed", "id" to streamId, "error" to e.message)
                mutableState.value = LiveWatchUiState.Error(e.toKind())
            }
        }
    }

    /**
     * Runs while the screen is there. [reading]: the player now, null while it is not this stream's. Following does not
     * wait for the network: a late answer must not leave it with where the player was before.
     */
    suspend fun run(reading: () -> PlayerReading?, apply: (List<FollowAction>) -> Unit) {
        try {
            coroutineScope {
                launch {
                    while (isActive) {
                        tick(reading()?.positionMs)
                        delay(TICK_MS)
                    }
                }
                while (isActive) {
                    reading()?.let { apply(follow(it)) }
                    delay(TICK_MS)
                }
            }
        } finally {
            // Counted at once on coming back
            beatAt = Instant.EPOCH
            if (content?.mode == LiveMode.LIVE) withContext(NonCancellable) { live.beat(streamId, viewerId, leaving = true) }
        }
    }

    /** Where "Return to live" goes: a premiere's moment; null for a stream (its live edge). */
    fun returnToLive(): Long? {
        follower.returned()
        update { it.copy(isBehind = false) }
        return premiere?.position(now())
    }

    internal fun follow(reading: PlayerReading): List<FollowAction> {
        val clock = premiere
        if (clock == null) {
            update { it.copy(isBehind = it.mode == LiveMode.LIVE && (reading.behindLiveMs ?: 0) > BEHIND_LIVE_MS) }
            return emptyList()
        }
        val moment = now()
        if (content?.mode != LiveMode.LIVE || clock.isOver(moment)) {
            update { it.copy(isBehind = false) }
            return emptyList()
        }
        val actions = follower.tick(clock.position(moment), reading)
        update { it.copy(isBehind = follower.isBehind) }
        return actions
    }

    /** One second: the clock and whatever is due. */
    internal suspend fun tick(positionMs: Long?) {
        val before = content ?: return
        update { it.copy(untilStartMs = countdown(it)) }
        val moment = now()
        if (Duration.between(stateAt, moment) >= stateInterval(content ?: before)) refreshState()
        val current = content ?: return
        refreshChat(current, moment, positionMs)
        if (current.mode == LiveMode.LIVE && Duration.between(beatAt, moment) >= BEAT) {
            beatAt = moment
            live.beat(streamId, viewerId, leaving = false)?.let { count -> update { it.copy(viewers = count) } }
        }
    }

    private fun stateInterval(content: LiveWatchContent): Duration = when (content.mode) {
        LiveMode.LIVE -> if (content.media == null) DUE_POLL else LIVE_POLL
        // A premiere's time has come: it starts within the minute
        LiveMode.SOON -> if ((content.untilStartMs ?: 1) <= 0) DUE_POLL else SOON_POLL
        LiveMode.ENDED -> if (isGone) FOREVER else SOON_POLL
        LiveMode.RECORDING, LiveMode.CANCELLED -> FOREVER
    }

    private suspend fun refreshState() {
        stateAt = now()
        val liveState = quiet { live.state(streamId) } ?: return
        content?.let { show(it.copy(live = liveState)) }
    }

    private suspend fun show(next: LiveWatchContent) {
        stateAt = now()
        val found = modeOf(next.live)
        val mode = if (isGone && found == LiveMode.RECORDING) LiveMode.ENDED else found
        val chatGone = if (mode == LiveMode.RECORDING) !next.live.chatReplay else !next.live.chatEnabled
        val shown = next.copy(
            mode = mode,
            viewers = next.live.viewers ?: next.viewers,
            messages = if (chatGone) emptyList() else next.messages,
            pinned = if (chatGone) null else next.pinned
        )
        val startedAt = next.live.startedAt
        premiere = if (shown.isPremiere && mode == LiveMode.LIVE && startedAt != null) {
            PremiereClock(startedAt, next.live.clockOffsetMs, next.live.durationSeconds?.let { (it * MS).toLong() })
        } else {
            null
        }
        mutableState.value = LiveWatchUiState.Content(shown.copy(untilStartMs = countdown(shown), media = mediaOf(shown)))
        if (shown.isPremiere) loadPremiere(shown)
    }

    private fun mediaOf(content: LiveWatchContent): LiveMedia? {
        if (content.isPremiere) return content.media
        val url = when (content.mode) {
            LiveMode.LIVE -> content.live.playbackUrl
            LiveMode.RECORDING -> content.live.vodUrl
            else -> null
        } ?: return null
        return LiveMedia.Address(url, "live:$streamId:${content.mode.name.lowercase()}")
    }

    /** The video is released a moment after the premiere starts: asked for again in 5 seconds until it is there. */
    private suspend fun loadPremiere(content: LiveWatchContent) {
        val id = content.live.premiereVideoId ?: return
        if (content.media != null || (content.mode != LiveMode.LIVE && content.mode != LiveMode.RECORDING)) return
        val video = quiet { catalog.video(id) }?.takeIf { it.isReady && it.playUrl != null }
        when {
            video != null -> update { it.copy(media = LiveMedia.Premiere(video, premiere?.position(now()) ?: 0)) }
            content.mode == LiveMode.RECORDING -> {
                isGone = true
                update { it.copy(mode = LiveMode.ENDED) }
            }
        }
    }

    private fun countdown(content: LiveWatchContent): Long? {
        if (content.mode != LiveMode.SOON || !content.isPremiere) return null
        val start = content.live.scheduledStart ?: content.stream.scheduledStart ?: return null
        return Duration.between(now(), start).toMillis() - content.live.clockOffsetMs
    }

    private suspend fun refreshChat(content: LiveWatchContent, moment: Instant, positionMs: Long?) {
        if (!content.hasChat) return
        if (content.mode == LiveMode.RECORDING) {
            refreshReplay(positionMs ?: 0)
            return
        }
        if (moment < chatDue) return
        val page = quiet { live.chat(streamId) }
        chatDue = moment + when {
            page == null -> CHAT_BACKOFF
            content.mode == LiveMode.LIVE -> CHAT_POLL
            else -> CHAT_SOON_POLL
        }
        if (page != null) update { it.copy(messages = page.messages, pinned = page.pinned) }
    }

    /** What had been written by the moment being watched, read in windows as playback reaches them. */
    private suspend fun refreshReplay(positionMs: Long) {
        val window = positionMs / REPLAY_WINDOW_MS
        // The one before, so a seek does not open an empty chat, and the next, so it is there in time
        for (n in listOf(window, window + 1, window - 1)) {
            if (n < 0 || n in replay || now() < chatDue) continue
            val list = readWindow(n)
            if (list == null) chatDue = now() + CHAT_BACKOFF else replay[n] = list
        }
        var shown = emptyList<LiveChatMessage>()
        var n = window
        while (shown.size < SHOWN) {
            val list = replay[n] ?: break
            shown = list.filter { it.offsetMs <= positionMs } + shown
            n--
        }
        update { it.copy(messages = shown.takeLast(SHOWN)) }
    }

    /** null when it could not be read; asked for again at the next tick. */
    private suspend fun readWindow(n: Long): List<LiveChatMessage>? {
        var from = n * REPLAY_WINDOW_MS
        val to = from + REPLAY_WINDOW_MS
        val all = linkedMapOf<String, LiveChatMessage>()
        repeat(REPLAY_PAGES) {
            val page: LiveChatPage = quiet { live.chatReplay(streamId, from, to) } ?: return null
            page.messages.filter { it.offsetMs < to }.forEach { all[it.id] = it }
            val last = page.messages.lastOrNull()
            if (!page.truncated || last == null || last.offsetMs <= from) return sorted(all)
            from = last.offsetMs
        }
        return sorted(all)
    }

    private fun sorted(all: Map<String, LiveChatMessage>) = all.values.sortedWith(compareBy({ it.offsetMs }, { it.id }))

    private suspend fun <T> quiet(block: suspend () -> T): T? = try {
        block()
    } catch (e: CoreError) {
        AunLog.i("live", "not read", "id" to streamId, "error" to e.message)
        null
    }

    private fun update(change: (LiveWatchContent) -> LiveWatchContent) = mutableState.update {
        if (it is LiveWatchUiState.Content) it.copy(content = change(it.content)) else it
    }

    class Factory(private val streamId: String, private val live: LiveRepository, private val catalog: CatalogRepository) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveWatchViewModel(streamId, live, catalog) as T
    }

    private companion object {
        const val MS = 1_000
        const val TICK_MS = 1_000L
        const val REPLAY_WINDOW_MS = 60_000L
        const val REPLAY_PAGES = 5
        const val SHOWN = 100

        /** Behind the newest moment of a stream by more than this is behind (a stream's delay is not). */
        const val BEHIND_LIVE_MS = 30_000L
        val LIVE_POLL: Duration = Duration.ofSeconds(60)
        val SOON_POLL: Duration = Duration.ofSeconds(15)
        val DUE_POLL: Duration = Duration.ofSeconds(5)
        val CHAT_POLL: Duration = Duration.ofSeconds(10)
        val CHAT_SOON_POLL: Duration = Duration.ofSeconds(30)
        val CHAT_BACKOFF: Duration = Duration.ofSeconds(60)
        val BEAT: Duration = Duration.ofSeconds(20)
        val FOREVER: Duration = Duration.ofDays(36_500)
    }
}
