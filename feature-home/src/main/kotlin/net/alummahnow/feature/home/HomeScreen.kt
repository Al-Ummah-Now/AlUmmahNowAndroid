package net.alummahnow.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import java.util.Locale
import net.alummahnow.core.bridge.model.RailKind
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.VideoCardData
import net.alummahnow.design.components.VideoRail
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens

/** Card width grows with the window so tablets and unfolded foldables show more per row. */
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
    modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            HomeUiState.Loading -> FullScreenLoading()
            is HomeUiState.Error -> ErrorState(stringResource(state.kind.message), onRetry)
            is HomeUiState.Content -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRetry) {
                val locale = currentLocale()
                val cardWidth = railCardWidth()
                LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                    item(key = "header") { HomeHeader(onOpenSettings) }
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
