package net.alummahnow.feature.studio.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FanousChip
import net.alummahnow.design.components.FanousChipRow
import net.alummahnow.design.format.AunFormat
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.compactCount
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.dashboard.StatTile
import net.alummahnow.feature.studio.studioText

private val ranges = listOf(
    AnalyticsRange.LAST_7 to R.string.studio_range_7,
    AnalyticsRange.LAST_28 to R.string.studio_range_28,
    AnalyticsRange.LAST_90 to R.string.studio_range_90,
    AnalyticsRange.LAST_365 to R.string.studio_range_365
)

@Composable
fun AnalyticsScreen(state: AnalyticsUiState, onRange: (AnalyticsRange) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= WIDE
            val pad = if (wide) FanousTokens.Spacing.xl else FanousTokens.Spacing.lg
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(pad)
                    .testTag("analytics"),
                verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)
            ) {
                // The range as chips, the selected one gold (as Apple's).
                // The range chips wrap rather than scroll, so none is cut on a narrow phone.
                FanousChipRow(sidePadding = 0.dp) {
                    ranges.forEach { (r, label) ->
                        FanousChip(
                            aunString(label, r.days),
                            selected = state.range == r,
                            onClick = { onRange(r) },
                            modifier = Modifier.testTag("range-${r.days}")
                        )
                    }
                }
                val data = state.data
                when {
                    data == null && state.loading -> Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    data == null -> ErrorState(studioText(state.error ?: net.alummahnow.design.R.string.aun_error_generic), onRetry)
                    else -> Content(data, wide, columns = if (wide) WIDE_TILES else 2)
                }
            }
        }
    }
}

@Composable
private fun Content(data: ChannelAnalytics, wide: Boolean, columns: Int) {
    val tiles = listOf(
        stringResource(R.string.studio_stat_views) to data.totalViews,
        stringResource(R.string.studio_stat_watch_hours) to data.watchTimeSeconds / SECONDS_PER_HOUR,
        stringResource(R.string.studio_stat_subscribers) to data.totalSubscribers,
        stringResource(R.string.studio_stat_likes) to data.totalLikes,
        stringResource(R.string.studio_stat_comments) to data.totalComments,
        stringResource(R.string.studio_stat_videos) to data.totalVideos
    )
    tiles.chunked(columns).forEach { chunk ->
        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
            chunk.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    // The chart beside the top videos on tablets, one under the other on phones.
    if (wide && data.topVideos.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)) {
            ChartCard(data, Modifier.weight(1.6f))
            TopVideos(data, Modifier.weight(1f))
        }
    } else {
        ChartCard(data, Modifier.fillMaxWidth())
        if (data.topVideos.isNotEmpty()) TopVideos(data, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChartCard(data: ChannelAnalytics, modifier: Modifier) {
    val f = LocalFanous.current
    FanousCard(modifier) {
        Text(
            stringResource(R.string.studio_chart_views),
            style = MaterialTheme.typography.titleMedium,
            color = f.colors.textPrimary.color,
            modifier = Modifier.semantics { heading() }.padding(bottom = FanousTokens.Spacing.sm)
        )
        if (data.daily.isEmpty()) {
            Text(stringResource(R.string.studio_analytics_empty), color = f.colors.textSecondary.color)
        } else {
            ViewsChart(data, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TopVideos(data: ChannelAnalytics, modifier: Modifier) {
    val f = LocalFanous.current
    FanousCard(modifier) {
        Text(
            stringResource(R.string.studio_top_videos),
            style = MaterialTheme.typography.titleMedium,
            color = f.colors.textPrimary.color,
            modifier = Modifier.semantics { heading() }.padding(bottom = FanousTokens.Spacing.sm)
        )
        val language = currentLocale().language
        data.topVideos.forEachIndexed { i, v ->
            val title = v.titleLocalized[language]?.takeIf { it.isNotBlank() } ?: v.title
            Row(Modifier.fillMaxWidth().padding(vertical = FanousTokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    numberLabel(i + 1L),
                    style = MaterialTheme.typography.titleSmall,
                    color = f.colors.primary.color,
                    modifier = Modifier.width(32.dp)
                )
                Text(
                    title,
                    color = f.colors.textPrimary.color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(compactCount(v.views), style = MaterialTheme.typography.labelLarge, color = f.colors.textSecondary.color)
            }
        }
    }
}

/** Views per day as bars; time runs from the start edge, so it reads right-to-left in Arabic. */
@Composable
private fun ViewsChart(data: ChannelAnalytics, modifier: Modifier) {
    // Lantern gold in both modes (Material's primary is the link colour in Light).
    val bar = LocalFanous.current.colors.primary.color
    val track = LocalFanous.current.colors.divider.color
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
private val WIDE = 840.dp
private const val WIDE_TILES = 6
private const val DATE_CHARS = 10
private const val GAP_RATIO = 0.25f
private const val GAP_MAX_PX = 12f
private const val BAR_RADIUS = 6f
