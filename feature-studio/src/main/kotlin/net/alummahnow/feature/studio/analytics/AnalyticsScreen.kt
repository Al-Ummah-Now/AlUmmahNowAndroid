package net.alummahnow.feature.studio.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.compactCount
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.dashboard.StatTile
import net.alummahnow.feature.studio.studioText

private val ranges = listOf(
    AnalyticsRange.LAST_7 to R.string.studio_range_7,
    AnalyticsRange.LAST_28 to R.string.studio_range_28,
    AnalyticsRange.LAST_90 to R.string.studio_range_90,
    AnalyticsRange.LAST_365 to R.string.studio_range_365
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyticsScreen(state: AnalyticsUiState, onRange: (AnalyticsRange) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg).testTag("analytics"),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val width = Modifier.widthIn(max = 960.dp).fillMaxWidth()
            FlowRow(width, horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                ranges.forEach { (r, label) ->
                    FilterChip(selected = state.range == r, onClick = {
                        onRange(r)
                    }, label = { Text(aunString(label, r.days)) }, modifier = Modifier.testTag("range-${r.days}"))
                }
            }
            val data = state.data
            when {
                data == null && state.loading -> Box(width.height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                data == null -> ErrorState(studioText(state.error ?: net.alummahnow.design.R.string.aun_error_generic), onRetry)
                else -> Content(data, width)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Content(data: ChannelAnalytics, width: Modifier) {
    FlowRow(
        width,
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        StatTile(stringResource(R.string.studio_stat_views), data.totalViews)
        StatTile(stringResource(R.string.studio_stat_watch_hours), data.watchTimeSeconds / SECONDS_PER_HOUR)
        StatTile(stringResource(R.string.studio_stat_subscribers), data.totalSubscribers)
        StatTile(stringResource(R.string.studio_stat_likes), data.totalLikes)
        StatTile(stringResource(R.string.studio_stat_comments), data.totalComments)
        StatTile(stringResource(R.string.studio_stat_videos), data.totalVideos)
    }
    Text(
        stringResource(R.string.studio_chart_views),
        style = MaterialTheme.typography.titleMedium,
        modifier = width.semantics {
            heading()
        }
    )
    if (data.daily.isEmpty()) {
        Text(stringResource(R.string.studio_analytics_empty), modifier = width)
    } else {
        ViewsChart(data, width)
    }
    if (data.topVideos.isNotEmpty()) {
        Text(
            stringResource(R.string.studio_top_videos),
            style = MaterialTheme.typography.titleMedium,
            modifier = width.semantics {
                heading()
            }
        )
        val language = currentLocale().language
        Card(width, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            data.topVideos.forEachIndexed { i, v ->
                val title = v.titleLocalized[language]?.takeIf { it.isNotBlank() } ?: v.title
                Row(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(numberLabel(i + 1L), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(compactCount(v.views), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Views per day as bars; time runs from the start edge, so it reads right-to-left in Arabic. */
@Composable
private fun ViewsChart(data: ChannelAnalytics, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val max = data.daily.maxOf { it.views }.coerceAtLeast(1)
    val peak = data.daily.maxBy { it.views }
    val locale = currentLocale()
    val peakDate = runCatching { AunFormat.date(LocalDate.parse(peak.date.take(DATE_CHARS)), locale) }
        .getOrDefault(peak.date)
    val description = aunString(
        R.string.studio_chart_cd,
        numberLabel(data.daily.sumOf { it.views }),
        numberLabel(peak.views),
        peakDate
    )
    Canvas(modifier.height(180.dp).semantics { contentDescription = description }.testTag("views-chart")) {
        val n = data.daily.size
        val slot = size.width / n
        val gap = (slot * GAP_RATIO).coerceAtMost(GAP_MAX_PX)
        data.daily.forEachIndexed { i, d ->
            val index = if (rtl) n - 1 - i else i
            val h = size.height * d.views / max
            val left = index * slot + gap / 2
            drawRoundRect(track, Offset(left, 0f), Size(slot - gap, size.height), CornerRadius(BAR_RADIUS, BAR_RADIUS))
            drawRoundRect(bar, Offset(left, size.height - h), Size(slot - gap, h), CornerRadius(BAR_RADIUS, BAR_RADIUS))
        }
    }
}

private const val SECONDS_PER_HOUR = 3600L
private const val DATE_CHARS = 10
private const val GAP_RATIO = 0.25f
private const val GAP_MAX_PX = 12f
private const val BAR_RADIUS = 6f
