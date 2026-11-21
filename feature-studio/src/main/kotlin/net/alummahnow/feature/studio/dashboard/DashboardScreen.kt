package net.alummahnow.feature.studio.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.Thumbnail
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.aunPlural
import net.alummahnow.design.format.compactCount
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.content.formatDate
import net.alummahnow.feature.studio.studioText

/**
 * The Dashboard: the upload note (only for someone who can upload), the counts, then recent uploads, comments
 * awaiting review, drafts and scheduled. A section that is off or outside the tier is not there at all; one that
 * failed says so in its own card with Try again.
 */
@Composable
fun DashboardScreen(state: DashboardUiState, onRetry: () -> Unit, modifier: Modifier = Modifier, onOpenVideo: (String) -> Unit = {}) {
    // The page paints its own background in every state (as Content and Analytics), whatever draws behind it.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            DashboardUiState.Loading -> FullScreenLoading()
            is DashboardUiState.Error -> ErrorState(studioText(state.message), onRetry)
            is DashboardUiState.Content -> BoxWithConstraints(Modifier.fillMaxSize().testTag("dashboard")) {
                val wide = maxWidth >= WIDE
                val columns = when {
                    maxWidth >= WIDE -> STATUSES
                    maxWidth >= MEDIUM -> 3
                    else -> 2
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(if (wide) FanousTokens.Spacing.xl else FanousTokens.Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg)
                ) {
                    // Identity lives only in the navigation (the header avatar or the rail), never on the page. No "you can
                    // upload" note: it told nothing (the Upload tab says so), and without uploads there is none either.
                    state.counts?.let { c -> Counts(c, columns) }
                    val recent: @Composable (Modifier) -> Unit = {
                        VideosSection(
                            it,
                            R.string.studio_dashboard_recent,
                            R.string.studio_dashboard_recent_empty,
                            state.recent,
                            onRetry,
                            onOpenVideo,
                            "recent"
                        )
                    }
                    val comments: @Composable (Modifier) -> Unit = { CommentsSection(it, state.comments, onRetry) }
                    // Recent uploads beside the comments on tablets (as Apple's iPad and Mac), one under the other on phones.
                    if (wide && state.recent != DashboardSection.Hidden && state.comments != DashboardSection.Hidden) {
                        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg), verticalAlignment = Alignment.Top) {
                            recent(Modifier.weight(1f))
                            comments(Modifier.weight(1f))
                        }
                    } else {
                        recent(Modifier.fillMaxWidth())
                        comments(Modifier.fillMaxWidth())
                    }
                    VideosSection(
                        Modifier.fillMaxWidth(),
                        R.string.studio_status_drafts,
                        R.string.studio_dashboard_drafts_empty,
                        state.drafts,
                        onRetry,
                        onOpenVideo,
                        "drafts"
                    )
                    VideosSection(
                        Modifier.fillMaxWidth(),
                        R.string.studio_status_scheduled,
                        R.string.studio_dashboard_scheduled_empty,
                        state.scheduled,
                        onRetry,
                        onOpenVideo,
                        "scheduled"
                    )
                }
            }
        }
    }
}

@Composable
private fun Counts(c: ContentCounts, columns: Int) {
    Text(
        stringResource(R.string.studio_dashboard_counts),
        style = MaterialTheme.typography.titleMedium,
        color = LocalFanous.current.colors.textPrimary.color,
        modifier = Modifier.semantics { heading() }
    )
    val tiles = listOf(
        stringResource(R.string.studio_status_published) to c.published,
        stringResource(R.string.studio_status_scheduled) to c.scheduled,
        stringResource(R.string.studio_status_drafts) to c.drafts,
        stringResource(R.string.studio_status_processing) to c.processing,
        stringResource(R.string.studio_status_failed) to c.failed
    )
    // Equal tiles in rows that fill the width: five across on tablets, three or two on phones.
    tiles.chunked(columns).forEachIndexed { row, chunk ->
        Row(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
            chunk.forEachIndexed { i, (label, value) ->
                val failed = row * columns + i == tiles.lastIndex
                StatTile(label, value, Modifier.weight(1f), alert = failed && value.toLong() > 0)
            }
            repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SectionTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = LocalFanous.current.colors.textPrimary.color,
        modifier = Modifier.padding(bottom = FanousTokens.Spacing.sm).semantics { heading() }
    )
}

/** A section card; Try again sits in the footer when it failed (the whole Dashboard reloads). */
@Composable
private fun SectionCard(
    modifier: Modifier,
    @StringRes title: Int,
    failed: Boolean,
    onRetry: () -> Unit,
    body: @Composable () -> Unit
) {
    FanousCard(
        modifier,
        actions = if (failed) {
            { FanousButton(stringResource(net.alummahnow.design.R.string.aun_retry), onRetry, kind = FanousButtonKind.Ghost) }
        } else {
            null
        }
    ) {
        SectionTitle(title)
        body()
    }
}

@Composable
private fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalFanous.current.colors.textSecondary.color, modifier = modifier)
}

@Composable
private fun VideosSection(
    modifier: Modifier,
    @StringRes title: Int,
    @StringRes empty: Int,
    section: DashboardSection<List<StudioVideo>>,
    onRetry: () -> Unit,
    onOpenVideo: (String) -> Unit,
    tag: String
) {
    if (section == DashboardSection.Hidden) return
    SectionCard(modifier.testTag("dashboard-$tag"), title, section is DashboardSection.Failed, onRetry) {
        when (section) {
            is DashboardSection.Failed -> {
                QuietLine(stringResource(R.string.studio_dashboard_section_failed))
                QuietLine(stringResource(section.message))
            }
            is DashboardSection.Ready -> if (section.value.isEmpty()) {
                QuietLine(stringResource(empty), Modifier.testTag("dashboard-$tag-empty"))
            } else {
                section.value.forEach { v -> DashboardVideoRow(v, onOpenVideo) }
            }
            DashboardSection.Hidden -> Unit
        }
    }
}

/**
 * A video in a section: its thumbnail, title, status, and the date on its own line (kept, empty, when a video has no
 * date, so rows match in height); the whole row opens the video's editor.
 */
@Composable
private fun DashboardVideoRow(v: StudioVideo, onOpenVideo: (String) -> Unit) {
    val f = LocalFanous.current
    val title = v.titleLocalized[currentLocale().language]?.takeIf { it.isNotBlank() } ?: v.title
    val narrow = LocalConfiguration.current.screenWidthDp < NARROW_DP
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpenVideo(v.id) }
            .padding(vertical = FanousTokens.Spacing.sm)
            .testTag("dashboard-video-${v.id}"),
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(v.thumbnailUrl, v.durationSeconds?.toLong(), Modifier.width(if (narrow) 80.dp else 96.dp))
        Column(Modifier.weight(1f)) {
            // A tighter line height, so a title that wraps reads as one title.
            Text(
                title,
                color = f.colors.textPrimary.color,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            v.status?.let {
                Text(stringResource(statusLabel(it)), style = MaterialTheme.typography.bodySmall, color = f.colors.textSecondary.color)
            }
            val date = v.createdAt?.let { formatDate(it) }
            Text(
                date.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = f.colors.textMuted.color,
                maxLines = 1,
                modifier = if (date == null) Modifier.clearAndSetSemantics {} else Modifier
            )
        }
    }
}

@StringRes
private fun statusLabel(s: ContentStatus): Int = when (s) {
    ContentStatus.PUBLISHED -> R.string.studio_status_published
    ContentStatus.SCHEDULED -> R.string.studio_status_scheduled
    ContentStatus.DRAFTS -> R.string.studio_draft
    ContentStatus.PROCESSING -> R.string.studio_status_processing
    ContentStatus.FAILED -> R.string.studio_status_failed
    ContentStatus.DELETED -> R.string.studio_status_deleted
}

@Composable
private fun CommentsSection(modifier: Modifier, section: DashboardSection<CommentsSummary>, onRetry: () -> Unit) {
    if (section == DashboardSection.Hidden) return
    SectionCard(modifier.testTag("dashboard-comments"), R.string.studio_dashboard_comments, section is DashboardSection.Failed, onRetry) {
        when (section) {
            is DashboardSection.Failed -> {
                QuietLine(stringResource(R.string.studio_dashboard_section_failed))
                QuietLine(stringResource(section.message))
            }
            is DashboardSection.Ready -> if (section.value.held == 0) {
                QuietLine(stringResource(R.string.studio_dashboard_comments_empty), Modifier.testTag("dashboard-comments-empty"))
            } else {
                Text(
                    aunPlural(R.plurals.studio_dashboard_comments_held, section.value.held, section.value.held),
                    // Accent text uses link (readable in every theme), never the button gold.
                    color = LocalFanous.current.colors.link.color,
                    fontWeight = FontWeight.SemiBold
                )
            }
            DashboardSection.Hidden -> Unit
        }
    }
}

/**
 * A count: the number large, the status under it, on the card material; `alert` turns the number red. A count of 1,000 or
 * more is compact ("4.8K", «٤٫٨ ألف»; owner 2026-11-21); `exact` keeps a measure such as watch hours whole.
 */
@Composable
fun StatTile(label: String, value: Number, modifier: Modifier = Modifier, alert: Boolean = false, exact: Boolean = false) {
    val f = LocalFanous.current
    Column(
        modifier
            .fanousMaterial(FanousMaterialKind.Card, RoundedCornerShape(FanousTokens.Radius.lg))
            .semantics(mergeDescendants = true) {}
            .padding(FanousTokens.Spacing.lg)
    ) {
        // Fixed line heights: the Arabic font's taller metrics otherwise make Arabic tiles taller than English ones.
        Text(
            if (exact || value.toLong() < COMPACT_FROM) numberLabel(value.toLong()) else compactCount(value.toLong()),
            fontSize = 26.sp,
            lineHeight = 32.sp,
            style = LocalTextStyle.current.copy(lineHeightStyle = TIGHT),
            fontWeight = FontWeight.Bold,
            color = if (alert) f.colors.errorText.color else f.colors.textPrimary.color
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp, lineHeightStyle = TIGHT),
            color = f.colors.textSecondary.color,
            maxLines = 1
        )
    }
}

private val WIDE = 840.dp
private val MEDIUM = 520.dp
private const val STATUSES = 5
private const val COMPACT_FROM = 1_000L
private const val NARROW_DP = 400
private val TIGHT = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
