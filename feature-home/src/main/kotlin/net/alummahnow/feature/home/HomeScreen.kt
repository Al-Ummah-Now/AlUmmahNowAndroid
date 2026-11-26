package net.alummahnow.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import java.util.Locale
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.SectionHeader
import net.alummahnow.design.components.VideoCard
import net.alummahnow.design.components.VideoCardData
import net.alummahnow.design.components.VideoRail
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens

/** Card widths for horizontal rails, including live content. */
@Composable
internal fun railCardWidth(): Dp {
    val size = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        size.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> 320.dp
        size.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> 290.dp
        else -> 260.dp
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Between the header and the rails: what is live (feature-live). Draws nothing when there is none. */
    liveRail: @Composable (cardWidth: Dp) -> Unit = {},
    hasLive: Boolean = false,
    /** There was something to show, and all of it is from channels blocked on this device. */
    allBlocked: Boolean = false
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            HomeUiState.Loading -> FullScreenLoading()
            is HomeUiState.Error -> HomeError(stringResource(state.kind.message), onRetry, onOpenSettings)
            is HomeUiState.Content -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRetry) {
                val locale = currentLocale()
                val cardWidth = railCardWidth()
                BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    val columns = if (maxWidth >= 600.dp) (maxWidth / 360.dp).toInt().coerceAtLeast(2) else 1
                    LazyColumn(Modifier.fillMaxSize()) {
                        item(key = "header") { HomeHeader(onOpenSettings) }
                        item(key = "live") { liveRail(cardWidth) }
                        if (state.rails.isEmpty() && !hasLive) {
                            item(key = "empty") {
                                Box(
                                    Modifier.fillMaxWidth().padding(FanousTokens.Spacing.xl),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(if (allBlocked) R.string.home_all_blocked else R.string.home_empty),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else if (columns > 1) {
                            state.rails.forEach { rail -> tabletRail(rail, locale, columns, onVideoClick) }
                        } else {
                            items(state.rails, key = { it.kind.name }) { rail ->
                                VideoRail(
                                    title = stringResource(rail.kind.titleRes()),
                                    items = rail.videos.map { it.toCard(locale) },
                                    onItemClick = { onVideoClick(it.id) },
                                    cardWidth = cardWidth
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.tabletRail(rail: Rail, locale: Locale, columns: Int, onVideoClick: (String) -> Unit) {
    item(key = "${rail.kind.name}-title") { SectionHeader(stringResource(rail.kind.titleRes())) }
    items(rail.videos.chunked(columns), key = { "${rail.kind.name}-${it.first().id}" }) { row ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            row.forEach { video ->
                VideoCard(video.toCard(locale), { onVideoClick(video.id) }, Modifier.weight(1f), width = null)
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HomeError(message: String, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        HomeHeader(onOpenSettings)
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(FanousTokens.Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            FanousCard(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                actions = { Button(onClick = onRetry) { Text(stringResource(DesignR.string.aun_retry)) } }
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        Image(painterResource(DesignR.drawable.aun_logo), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
        Text(
            stringResource(R.string.home_brand),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).semantics { heading() }
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(R.string.home_settings),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

internal fun RailKind.titleRes(): Int = when (this) {
    RailKind.CONTINUE_WATCHING -> DesignR.string.aun_rail_continue
    RailKind.TRENDING -> DesignR.string.aun_rail_trending
    RailKind.RECOMMENDED -> DesignR.string.aun_rail_recommended
    RailKind.POPULAR -> DesignR.string.aun_rail_popular
    RailKind.RECENT -> DesignR.string.aun_rail_recent
    RailKind.CATEGORY -> DesignR.string.aun_rail_category
}

fun VideoSummary.toCard(locale: Locale) = VideoCardData(
    id = id,
    title = title.resolve(locale),
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    channelName = channelName,
    views = views
)
