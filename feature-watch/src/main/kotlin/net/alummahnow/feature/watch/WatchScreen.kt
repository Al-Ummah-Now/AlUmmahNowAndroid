package net.alummahnow.feature.watch

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.window.core.layout.WindowSizeClass
import java.util.Locale
import net.alummahnow.core.bridge.model.Chapter
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.LanternLoader
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.VideoCard
import net.alummahnow.design.components.VideoCardData
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.viewsLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.MediaItems
import net.alummahnow.player.ui.AunVideoPlayer

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
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            WatchUiState.Loading -> FullScreenLoading()
            is WatchUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is WatchUiState.Content -> {
                if (player != null) PlayVideoEffect(player, state.video)
                val playerBox: @Composable (Modifier) -> Unit = { m ->
                    PlayerArea(player, isFullscreen, onToggleFullscreen, controls = !isInPictureInPicture, modifier = m)
                }
                when {
                    isInPictureInPicture || isFullscreen -> playerBox(Modifier.fillMaxSize())
                    else -> WatchLayout(state, player, playerBox, onVideoClick)
                }
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
private fun PlayerArea(player: Player?, isFullscreen: Boolean, onToggleFullscreen: () -> Unit, controls: Boolean, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player == null) {
            LanternLoader()
        } else {
            AunVideoPlayer(
                player = player,
                isFullscreen = isFullscreen,
                onToggleFullscreen = onToggleFullscreen,
                controlsInitiallyVisible = controls,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun WatchLayout(
    state: WatchUiState.Content,
    player: Player?,
    playerBox: @Composable (Modifier) -> Unit,
    onVideoClick: (String) -> Unit
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
                details(state.video, locale, onChapter)
            }
            LazyColumn(Modifier.weight(0.36f).padding(horizontal = FanousTokens.Spacing.sm)) {
                related(state.related, locale, onVideoClick)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            LazyColumn(Modifier.fillMaxSize()) {
                details(state.video, locale, onChapter)
                related(state.related, locale, onVideoClick)
            }
        }
    }
}

private fun LazyListScope.details(video: VideoDetail, locale: Locale, onChapter: (Chapter) -> Unit) {
    item(key = "details") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(FanousTokens.Spacing.lg)) {
            Text(
                video.summary.title.resolve(locale),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                listOfNotNull(video.summary.channelName, viewsLabel(video.summary.views)).joinToString(" • "),
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

private fun LazyListScope.related(videos: List<VideoSummary>, locale: Locale, onVideoClick: (String) -> Unit) {
    if (videos.isEmpty()) return
    item(key = "related-title") { SectionHeader(stringResource(R.string.watch_related)) }
    items(videos, key = { "rel-" + it.id }) { v ->
        VideoCard(
            data = VideoCardData(v.id, v.title.resolve(locale), v.thumbnailUrl, v.durationSeconds, v.channelName, v.views),
            onClick = { onVideoClick(v.id) },
            width = null,
            modifier = Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
        )
    }
}
