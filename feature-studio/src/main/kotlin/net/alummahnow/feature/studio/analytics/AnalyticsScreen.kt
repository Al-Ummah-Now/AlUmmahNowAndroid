package net.alummahnow.feature.studio.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToLong
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.format.compactCount
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.dashboard.StatTile

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
                    }, label = { Text(stringResource(label)) }, modifier = Modifier.testTag("range-${r.days}"))
                }
            }
            val data = state.data
            when {
                data == null && state.loading -> Box(width.height(240.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                data == null -> ErrorState(stringResource(state.error ?: net.alummahnow.design.R.string.aun_error_generic), onRetry)
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
        StatTile(stringResource(R.string.studio_stat_watch_hours), (data.watchTimeSeconds / SECONDS_PER_HOUR * 10).roundToLong() / 10.0)
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
        Card(width, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            data.topVideos.forEachIndexed { i, v ->
                Row(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Text(localizedDigits("${i + 1}"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(32.dp))
                    Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(compactCount(v.views), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * Views per day as bars on a 0-based value axis with gridlines, the first, middle and last dates
 * below, and a readout of the tapped (or dragged-to) day. Time and the value axis start at the
 * start edge, so in Arabic the axis is on the right and the days run right to left.
 */
@Composable
private fun ViewsChart(data: ChannelAnalytics, modifier: Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val selectedBar = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.outline
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val days = data.daily
    val ticks = countTicks(days.maxOf { it.views })
    val top = ticks.last().coerceAtLeast(1)
    val empty = days.all { it.views == 0L }
    val locale = currentLocale()
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val dayLabel: (Int) -> String = { i ->
        runCatching { LocalDate.parse(days[i].date.take(DATE_CHARS)).format(dayFormat) }.getOrDefault(days[i].date)
    }
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val peak = days.maxBy { it.views }
    val peakDate = runCatching {
        LocalDate.parse(peak.date.take(DATE_CHARS)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
        .getOrDefault(peak.date)
    val description = stringResource(
        R.string.studio_chart_cd,
        localizedDigits(days.sumOf { it.views }.toString()),
        localizedDigits(peak.views.toString()),
        localizedDigits(peakDate)
    )
    val tickLabels = ticks.map { compactCount(it) }
    val dateLabels = dateLabelIndices(days.size).map { it to localizedDigits(dayLabel(it)) }
    val readout = selected?.let { i -> "${localizedDigits(dayLabel(i))}: ${localizedDigits(days[i].views.toString())}" }
    val pick: (Float, Float) -> Unit = { x, width ->
        val ratio = (x / width).coerceIn(0f, 0.999f)
        val slot = (ratio * days.size).toInt()
        selected = if (rtl) days.size - 1 - slot else slot
    }

    Column(modifier) {
        Text(
            readout ?: "",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth().height(24.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("chart-readout")
        )
        Row(verticalAlignment = Alignment.Top) {
            // Value axis on the start side; labels are centred on the gridlines (the plot is inset by half a label)
            Column(
                Modifier.height(CHART_HEIGHT).padding(end = FanousTokens.Spacing.sm).testTag("chart-y-axis"),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                tickLabels.asReversed().forEach { label ->
                    Box(Modifier.height(TICK_LABEL_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(CHART_HEIGHT)) {
                    Canvas(
                        Modifier.fillMaxSize()
                            .padding(vertical = TICK_LABEL_HEIGHT / 2)
                            .semantics { contentDescription = description }
                            .testTag("views-chart")
                            .pointerInput(days) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
                            .pointerInput(days) {
                                detectHorizontalDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) }
                            }
                    ) {
                        ticks.forEach { tick ->
                            val y = size.height * (1f - tick.toFloat() / top)
                            drawLine(
                                if (tick == 0L) baseline else grid,
                                Offset(0f, y),
                                Offset(size.width, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = if (tick == 0L) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                            )
                        }
                        val n = days.size
                        val slot = size.width / n
                        val gap = (slot * GAP_RATIO).coerceAtMost(GAP_MAX_PX)
                        days.forEachIndexed { i, d ->
                            val index = if (rtl) n - 1 - i else i
                            val h = size.height * d.views / top
                            val left = index * slot + gap / 2
                            drawRoundRect(
                                if (selected == i) selectedBar else bar,
                                Offset(left, size.height - h),
                                Size(slot - gap, h),
                                CornerRadius(BAR_RADIUS, BAR_RADIUS)
                            )
                        }
                    }
                    if (empty) {
                        Text(
                            stringResource(R.string.studio_chart_empty_views),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).testTag("chart-empty")
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().testTag("chart-x-axis"), horizontalArrangement = Arrangement.SpaceBetween) {
                    dateLabels.forEach { (_, label) ->
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val SECONDS_PER_HOUR = 3600.0
private val CHART_HEIGHT = 180.dp
private val TICK_LABEL_HEIGHT = 16.dp
private const val DATE_CHARS = 10
private const val GAP_RATIO = 0.25f
private const val GAP_MAX_PX = 12f
private const val BAR_RADIUS = 6f
