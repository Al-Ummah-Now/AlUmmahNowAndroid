package net.alummahnow.feature.studio.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import net.alummahnow.design.format.joinMeta
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.format.viewsLabel
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
                FanousChipRow(sidePadding = 0.dp, maxItemsInEachRow = if (wide) ranges.size else 2) {
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
    val watchHours = stringResource(R.string.studio_stat_watch_hours)
    val tiles = listOf(
        stringResource(R.string.studio_stat_views) to data.totalViews,
        watchHours to data.watchTimeSeconds / SECONDS_PER_HOUR,
        stringResource(R.string.studio_stat_subscribers) to data.totalSubscribers,
        stringResource(R.string.studio_stat_likes) to data.totalLikes,
        stringResource(R.string.studio_stat_comments) to data.totalComments,
        stringResource(R.string.studio_stat_videos) to data.totalVideos
    )
    tiles.chunked(columns).forEach { chunk ->
        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
            chunk.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f), exact = label == watchHours) }
            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    // The chart beside the top videos on tablets, one under the other on phones.
    if (wide && data.topVideos.isNotEmpty()) {
        // Both cards as tall as the taller one.
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)) {
            ChartCard(data, Modifier.weight(1.6f).fillMaxHeight())
            TopVideos(data, Modifier.weight(1f).fillMaxHeight())
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
                    // Accent text uses link (readable in every theme), never the button gold.
                    color = f.colors.link.color,
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

/**
 * Views per day as bars on a 0-based value axis with gridlines (round steps, as the web's Studio charts), the first,
 * middle and last dates below, and a readout of the tapped (or dragged-to) day. The value axis and time start at the
 * start edge, so in Arabic the axis is on the right and the days run right to left.
 */
@Composable
private fun ViewsChart(data: ChannelAnalytics, modifier: Modifier) {
    val f = LocalFanous.current
    // Lantern gold in both modes (Material's primary is the link colour in Light).
    val bar = f.colors.primary.color
    val selectedBar = f.colors.primaryHover.color
    val grid = f.colors.divider.color
    val baseline = f.colors.outline.color
    val axisText = f.colors.textSecondary.color
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val days = data.daily
    val ticks = countTicks(days.maxOf { it.views })
    val top = ticks.last().coerceAtLeast(1)
    val empty = days.all { it.views == 0L }
    val locale = currentLocale()
    val date: (Int) -> LocalDate? = { i -> runCatching { LocalDate.parse(days[i].date.take(DATE_CHARS)) }.getOrNull() }
    val dayLabel: (Int) -> String = { i -> date(i)?.let { AunFormat.dayMonth(it, locale) } ?: days[i].date }
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val peakIndex = days.indices.maxBy { days[it].views }
    val description = aunString(
        R.string.studio_chart_cd,
        numberLabel(days.sumOf { it.views }),
        numberLabel(days[peakIndex].views),
        date(peakIndex)?.let { AunFormat.date(it, locale) } ?: days[peakIndex].date
    )
    val tickLabels = ticks.map { compactCount(it) }
    val dateLabels = dateLabelIndices(days.size).map { dayLabel(it) }
    val readout = selected?.let { i -> joinMeta(dayLabel(i), viewsLabel(days[i].views)) }
    val pick: (Float, Float) -> Unit = { x, width ->
        val slot = ((x / width).coerceIn(0f, PICK_MAX) * days.size).toInt()
        selected = if (rtl) days.size - 1 - slot else slot
    }

    Column(modifier) {
        Text(
            readout.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = f.colors.textPrimary.color,
            modifier = Modifier.fillMaxWidth().height(24.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("chart-readout")
        )
        Row(verticalAlignment = Alignment.Top) {
            // The value axis on the start side; labels are centred on the gridlines (the plot is inset by half a label).
            Column(
                Modifier.height(CHART_HEIGHT).padding(end = FanousTokens.Spacing.sm).testTag("chart-y-axis"),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                tickLabels.asReversed().forEach { label ->
                    Box(Modifier.height(TICK_LABEL_HEIGHT), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = axisText)
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
                                pathEffect = if (tick == 0L) null else PathEffect.dashPathEffect(floatArrayOf(DASH, DASH))
                            )
                        }
                        val n = days.size
                        val slot = size.width / n
                        val gap = (slot * GAP_RATIO).coerceAtMost(GAP_MAX_PX)
                        days.forEachIndexed { i, d ->
                            val index = if (rtl) n - 1 - i else i
                            val h = size.height * d.views / top
                            drawRoundRect(
                                if (selected == i) selectedBar else bar,
                                Offset(index * slot + gap / 2, size.height - h),
                                Size(slot - gap, h),
                                CornerRadius(BAR_RADIUS, BAR_RADIUS)
                            )
                        }
                    }
                    if (empty) {
                        Text(
                            stringResource(R.string.studio_chart_empty_views),
                            style = MaterialTheme.typography.bodyMedium,
                            color = axisText,
                            modifier = Modifier.align(Alignment.Center).testTag("chart-empty")
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().testTag("chart-x-axis"), horizontalArrangement = Arrangement.SpaceBetween) {
                    dateLabels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = axisText) }
                }
            }
        }
    }
}

private const val SECONDS_PER_HOUR = 3600.0
private val WIDE = 840.dp
private const val WIDE_TILES = 6
private const val DATE_CHARS = 10
private const val GAP_RATIO = 0.25f
private const val GAP_MAX_PX = 12f
private const val BAR_RADIUS = 6f
private val CHART_HEIGHT = 180.dp
private val TICK_LABEL_HEIGHT = 16.dp
private const val DASH = 6f
private const val PICK_MAX = 0.999f
