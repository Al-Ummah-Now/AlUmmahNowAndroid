package net.alummahnow.feature.watch

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.window.core.layout.WindowSizeClass
import java.util.Locale
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.LanternLoader
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.VideoCard
import net.alummahnow.design.components.VideoCardData
import net.alummahnow.design.components.VideoPoster
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.viewsLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.MediaItems
import net.alummahnow.player.ui.AunVideoPlayer

/** Report and block actions shown in the watch screen's overflow menu when supplied by the app. */
data class WatchSafetyActions(
    val onReportVideo: (VideoDetail) -> Unit,
    val onReportChannel: (VideoDetail) -> Unit,
    val onBlockChannel: (VideoDetail) -> Unit,
    val onCopyLink: ((VideoDetail) -> Unit)? = null
)

/**
 * Watch page (spec 12.3). [player] is the shared MediaController (null while connecting, or in previews
 * and screenshot tests, where a placeholder is drawn instead).
 */
@Composable
fun WatchScreen(
    state: WatchUiState,
    player: Player?,
    isFullscreen: Boolean,
    isInPictureInPicture: Boolean,
    onToggleFullscreen: () -> Unit,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    safety: WatchSafetyActions? = null,
    isChannelBlocked: Boolean = false,
    onUnblockChannel: (() -> Unit)? = null
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            WatchUiState.Loading -> FullScreenLoading()
            is WatchUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is WatchUiState.Content -> {
                LaunchedEffect(player, isChannelBlocked) {
                    if (isChannelBlocked) {
                        player?.stop()
                        player?.clearMediaItems()
                    }
                }
                if (isChannelBlocked) {
                    BlockedChannelView(
                        channelName = state.video.summary.channelName?.takeIf { it.isNotBlank() },
                        onUnblock = onUnblockChannel
                    )
                } else {
                    if (player != null) PlayVideoEffect(player, state.video)
                    val playerBox: @Composable (Modifier) -> Unit = { m ->
                        PlayerArea(
                            player,
                            state.video.summary.thumbnailUrl,
                            isFullscreen,
                            onToggleFullscreen,
                            controls = !isInPictureInPicture,
                            modifier = m
                        )
                    }
                    when {
                        isInPictureInPicture || isFullscreen -> playerBox(Modifier.fillMaxSize())
                        else -> WatchLayout(state, player, playerBox, onVideoClick, safety)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockedChannelView(channelName: String?, onUnblock: (() -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(FanousTokens.Spacing.xl)
            .testTag("watch-channel-blocked"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            Text(
                if (channelName != null) {
                    aunString(R.string.watch_channel_blocked, isolate(channelName))
                } else {
                    stringResource(R.string.watch_channel_blocked_unnamed)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (onUnblock != null) {
                FanousButton(
                    stringResource(R.string.watch_unblock),
                    onClick = onUnblock,
                    kind = FanousButtonKind.Secondary,
                    modifier = Modifier.testTag("watch-unblock-channel")
                )
            }
        }
    }
}

/** Loads the video into the shared player once; returning to the same video keeps the position. */
@Composable
private fun PlayVideoEffect(player: Player, video: VideoDetail) {
    val locale = currentLocale()
    LaunchedEffect(player, video.id) {
        if (player.currentMediaItem?.mediaId != video.id) {
            // Captions follow the app language when the video has that track (Arabic UI → Arabic captions).
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(locale.language)
                .build()
            player.setMediaItem(MediaItems.playable(video, locale))
            player.prepare()
            player.playWhenReady = true
        }
    }
}

@Composable
private fun PlayerArea(
    player: Player?,
    poster: String?,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    controls: Boolean,
    modifier: Modifier
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player == null) {
            // Until the player is connected: the video's poster with the loader over it, not an empty black box.
            VideoPoster(poster, Modifier.fillMaxSize())
            LanternLoader()
        } else {
            AunVideoPlayer(
                player = player,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                controlsInitiallyVisible = controls,
                modifier = Modifier.fillMaxSize(),
                poster = poster
            )
        }
    }
}

@Composable
private fun WatchLayout(
    state: WatchUiState.Content,
    player: Player?,
    playerBox: @Composable (Modifier) -> Unit,
    onVideoClick: (String) -> Unit,
    safety: WatchSafetyActions?
) {
    val locale = currentLocale()
    var seekRequest by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekRequest) { seekRequest?.let { player?.seekTo(it) } }
    val onChapter: (Chapter) -> Unit = { seekRequest = (it.startSeconds * 1000).toLong() }
    val twoPane = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    if (twoPane) {
        // Tablets, unfolded foldables, large landscape: player + details start, related end (spec 12.12).
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LazyColumn(Modifier.weight(0.64f)) {
                item { playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }
                details(state.video, locale, onChapter, safety)
            }
            LazyColumn(Modifier.weight(0.36f).padding(horizontal = FanousTokens.Spacing.sm)) {
                related(state.related, locale, onVideoClick, twoPane = true)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            LazyColumn(Modifier.fillMaxSize()) {
                details(state.video, locale, onChapter, safety)
                related(state.related, locale, onVideoClick)
            }
        }
    }
}

private fun LazyListScope.details(
    video: VideoDetail,
    locale: Locale,
    onChapter: (Chapter) -> Unit,
    safety: WatchSafetyActions?
) {
    item(key = "details") { WatchDetailsHeader(video, locale, safety) }
    if (video.chapters.isNotEmpty()) {
        item(key = "chapters-title") { SectionHeader(stringResource(R.string.watch_chapters)) }
        items(video.chapters, key = { "ch-" + it.id }) { chapter ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    onChapter(chapter)
                }.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
            ) {
                Text(
                    durationLabel(chapter.startSeconds.toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(56.dp)
                )
                Text(
                    chapter.title.resolve(locale),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        item(key = "chapters-divider") { HorizontalDivider(Modifier.padding(vertical = FanousTokens.Spacing.sm)) }
    }
}

@Composable
private fun WatchDetailsHeader(video: VideoDetail, locale: Locale, safety: WatchSafetyActions?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(FanousTokens.Spacing.lg)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(
                video.summary.title.resolve(locale),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            if (safety != null) WatchSafetyMenu(video, safety)
        }
        Text(
            joinMeta(video.summary.channelName, viewsLabel(video.summary.views)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FanousTokens.Spacing.xs)
        )
        val description = video.description.resolve(locale)
        if (description.isNotBlank()) {
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = FanousTokens.Spacing.md)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { expanded = !expanded }
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(FanousTokens.Spacing.md)
            )
        }
    }
}

@Composable
private fun WatchSafetyMenu(video: VideoDetail, safety: WatchSafetyActions) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val channelName = video.summary.channelName?.takeIf { it.isNotBlank() }
    val hasChannelId = !video.summary.channelId.isNullOrBlank()
    Box {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.testTag("watch-more-actions")
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.watch_more_actions),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            // The card under it can have the same colour: the edge tells them apart.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            val onCopyLink = safety.onCopyLink
            if (onCopyLink != null && !video.isPrivate) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watch_copy_link)) },
                    modifier = Modifier.testTag("watch-copy-link-item"),
                    onClick = {
                        menuOpen = false
                        onCopyLink(video)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.watch_report_video)) },
                onClick = {
                    menuOpen = false
                    safety.onReportVideo(video)
                }
            )
            if (hasChannelId) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watch_report_channel)) },
                    modifier = Modifier.testTag("watch-report-channel-item"),
                    onClick = {
                        menuOpen = false
                        safety.onReportChannel(video)
                    }
                )
            }
            if (channelName != null || hasChannelId) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.watch_block_channel)) },
                    modifier = Modifier.testTag("watch-block-channel-item"),
                    onClick = {
                        menuOpen = false
                        confirmBlock = true
                    }
                )
            }
        }
    }
    if (confirmBlock) {
        ConfirmDialog(
            title = if (channelName != null) {
                aunString(R.string.watch_block_channel_title, isolate(channelName))
            } else {
                stringResource(R.string.watch_block_channel_title_unnamed)
            },
            message = stringResource(R.string.watch_block_channel_body),
            confirmLabel = stringResource(R.string.watch_block_confirm),
            onConfirm = {
                confirmBlock = false
                safety.onBlockChannel(video)
            },
            onDismiss = { confirmBlock = false }
        )
    }
}

private fun LazyListScope.related(
    videos: List<VideoSummary>,
    locale: Locale,
    onVideoClick: (String) -> Unit,
    twoPane: Boolean = false
) {
    if (videos.isEmpty()) return
    item(key = "related-title") {
        SectionHeader(
            stringResource(R.string.watch_related),
            topPadding = if (twoPane) 0.dp else FanousTokens.Spacing.sm
        )
    }
    items(videos, key = { "rel-" + it.id }) { v ->
        VideoCard(
            data = VideoCardData(v.id, v.title.resolve(locale), v.thumbnailUrl, v.durationSeconds, v.channelName, v.views),
            onClick = { onVideoClick(v.id) },
            width = null,
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
        )
    }
}
