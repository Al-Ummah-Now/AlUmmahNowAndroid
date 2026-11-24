package net.alummahnow.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.window.core.layout.WindowSizeClass
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.LanternLoader
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.MediaItems
import net.alummahnow.player.ui.AunVideoPlayer

/** Every stream on air and coming, with the event filter while event tags are on. */
@Composable
fun LiveListScreen(
    state: LiveListUiState,
    onOpen: (String) -> Unit,
    onEvent: (LiveEventType?) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            LiveListUiState.Loading -> FullScreenLoading()
            is LiveListUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is LiveListUiState.Content -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)
            ) {
                val list = state.list
                wide { SectionHeader(stringResource(R.string.live_title)) }
                if (list.hasEvents) wide { EventFilter(list.event, onEvent) }
                if (list.all.isEmpty()) wide { EmptyLive() }
                section(R.string.live_on_air, list.onAir, onOpen)
                section(R.string.live_coming, list.coming, onOpen)
            }
        }
    }
}

private fun LazyGridScope.wide(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

private fun LazyGridScope.section(title: Int, streams: List<LiveStream>, onOpen: (String) -> Unit) {
    if (streams.isEmpty()) return
    wide { SectionHeader(stringResource(title)) }
    items(streams, key = { it.id }) { stream ->
        LiveCard(
            stream,
            onClick = { onOpen(stream.id) },
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
        )
    }
}

@Composable
private fun EmptyLive() {
    Box(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.xl), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.live_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One menu, so it takes the same room in every language. */
@Composable
private fun EventFilter(chosen: LiveEventType?, onEvent: (LiveEventType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)) {
        FanousButton(
            stringResource(R.string.live_event_filter, stringResource(eventLabel(chosen))),
            onClick = { open = true },
            kind = FanousButtonKind.Secondary,
            modifier = Modifier.testTag("live-event-filter")
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(null) + LiveEventType.entries).forEach { event ->
                DropdownMenuItem(
                    text = { Text(stringResource(eventLabel(event))) },
                    modifier = Modifier.semantics { selected = event == chosen },
                    onClick = {
                        open = false
                        onEvent(event)
                    }
                )
            }
        }
    }
}

private fun eventLabel(event: LiveEventType?): Int = event?.label() ?: R.string.live_event_all

/**
 * One live stream or premiere. [player] is the shared MediaController (null while connecting, or in screenshot tests).
 */
@Composable
fun LiveWatchScreen(
    state: LiveWatchUiState,
    player: Player?,
    isFullscreen: Boolean,
    isInPictureInPicture: Boolean,
    onToggleFullscreen: () -> Unit,
    onReturnToLive: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            LiveWatchUiState.Loading -> FullScreenLoading()
            is LiveWatchUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is LiveWatchUiState.Content -> {
                val content = state.content
                if (player != null) PlayMediaEffect(player, content)
                val isPlayerShown = content.mode == LiveMode.LIVE || content.mode == LiveMode.RECORDING
                val top: @Composable (Modifier) -> Unit = { m ->
                    if (isPlayerShown) {
                        LivePlayer(player, content, isFullscreen, onToggleFullscreen, controls = !isInPictureInPicture, modifier = m)
                    } else {
                        Box(
                            m.padding(FanousTokens.Spacing.lg).fanousMaterial(FanousMaterialKind.Card, MaterialTheme.shapes.large),
                            contentAlignment = Alignment.Center
                        ) { LiveNotice(content) }
                    }
                }
                if (isPlayerShown && (isFullscreen || isInPictureInPicture)) {
                    top(Modifier.fillMaxSize())
                } else {
                    LiveLayout(content, top, onReturnToLive)
                }
            }
        }
    }
}

@Composable
private fun LiveLayout(content: LiveWatchContent, top: @Composable (Modifier) -> Unit, onReturnToLive: () -> Unit) {
    val details: @Composable () -> Unit = {
        Column(Modifier.padding(FanousTokens.Spacing.lg)) {
            if (content.isBehind) {
                FanousButton(
                    stringResource(R.string.live_return),
                    onClick = onReturnToLive,
                    modifier = Modifier.align(Alignment.End).padding(bottom = FanousTokens.Spacing.sm).testTag("live-return")
                )
            }
            LiveDetails(content)
        }
    }
    val chat: @Composable (Modifier) -> Unit = { m ->
        LiveChat(content, m.fanousMaterial(FanousMaterialKind.Card, MaterialTheme.shapes.large))
    }
    // Upright, even a large tablet has the chat under the player: beside it the video would be small
    val window = LocalWindowInfo.current.containerSize
    val isWide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val twoPane = isWide && window.width > window.height
    if (twoPane && content.hasChat) {
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(Modifier.weight(MAIN_WEIGHT).verticalScroll(rememberScrollState())) {
                top(Modifier.fillMaxWidth().aspectRatio(VIDEO_RATIO))
                details()
            }
            chat(Modifier.weight(1 - MAIN_WEIGHT).fillMaxHeight().padding(FanousTokens.Spacing.md))
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).verticalScroll(rememberScrollState())) {
            top(Modifier.fillMaxWidth().aspectRatio(VIDEO_RATIO))
            details()
            if (content.hasChat) {
                chat(Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg).height(CHAT_HEIGHT))
            }
        }
    }
}

/** Loads the stream, recording or premiere into the shared player once; returning to it keeps the position. */
@Composable
private fun PlayMediaEffect(player: Player, content: LiveWatchContent) {
    val locale = currentLocale()
    val media = content.media ?: return
    val id = mediaIdOf(media)
    LaunchedEffect(player, id) {
        if (player.currentMediaItem?.mediaId == id) return@LaunchedEffect
        when (media) {
            is LiveMedia.Address -> player.setMediaItem(
                MediaItem.Builder()
                    .setMediaId(media.mediaId)
                    .setUri(media.url.toUri())
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(content.stream.title).setArtist(content.stream.channelName).build()
                    )
                    .build()
            )
            is LiveMedia.Premiere -> player.setMediaItem(MediaItems.playable(media.video, locale), media.startMs)
        }
        player.prepare()
        player.playWhenReady = true
    }
}

/** The player's item for this stream: nothing else it plays is followed or counted. */
fun mediaIdOf(media: LiveMedia): String = when (media) {
    is LiveMedia.Address -> media.mediaId
    is LiveMedia.Premiere -> media.video.id
}

/** What the player is doing, when it plays [content]'s media. */
fun readingOf(player: Player, content: LiveWatchContent): PlayerReading? {
    val media = content.media ?: return null
    if (player.currentMediaItem?.mediaId != mediaIdOf(media)) return null
    return PlayerReading(
        positionMs = player.currentPosition,
        isPlaying = player.playWhenReady,
        canKeepUp = player.playbackState == Player.STATE_READY,
        speed = player.playbackParameters.speed,
        behindLiveMs = player.currentLiveOffset.takeIf { player.isCurrentMediaItemLive && it != C.TIME_UNSET }
    )
}

fun applyTo(player: Player, actions: List<FollowAction>) = actions.forEach { action ->
    when (action) {
        is FollowAction.Seek -> player.seekTo(action.positionMs)
        FollowAction.NormalSpeed -> player.setPlaybackSpeed(1f)
    }
}

@Composable
private fun LivePlayer(
    player: Player?,
    content: LiveWatchContent,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    controls: Boolean,
    modifier: Modifier
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player == null) {
            LanternLoader()
        } else {
            AunVideoPlayer(
                player = player,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                controlsInitiallyVisible = controls,
                modifier = Modifier.fillMaxSize(),
                poster = content.stream.thumbnailUrl
            )
        }
    }
}

private const val MAIN_WEIGHT = 0.64f
private const val VIDEO_RATIO = 16f / 9f
private val CHAT_HEIGHT = 420.dp
