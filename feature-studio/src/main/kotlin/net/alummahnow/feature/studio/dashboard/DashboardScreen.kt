package net.alummahnow.feature.studio.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.studioText

@Composable
fun DashboardScreen(state: DashboardUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        DashboardUiState.Loading -> FullScreenLoading(modifier)
        is DashboardUiState.Error -> ErrorState(studioText(state.message), onRetry, modifier)
        is DashboardUiState.Content -> BoxWithConstraints(modifier.fillMaxSize().testTag("dashboard")) {
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
                // Identity lives only in the navigation (the header avatar or the rail), never on the page.
                state.access?.let { a ->
                    FanousCard(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(
                                if (a.canUpload) R.string.studio_dashboard_upload_ok else R.string.studio_dashboard_upload_invite
                            ),
                            color = LocalFanous.current.colors.textPrimary.color
                        )
                    }
                }
                state.counts?.let { c ->
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
            }
        }
    }
}

/** A count: the number large, the status under it, on the card material; `alert` turns the number red. */
@Composable
fun StatTile(label: String, value: Number, modifier: Modifier = Modifier, alert: Boolean = false) {
    val f = LocalFanous.current
    Column(
        modifier
            .fanousMaterial(FanousMaterialKind.Card, RoundedCornerShape(FanousTokens.Radius.lg))
            .semantics(mergeDescendants = true) {}
            .padding(FanousTokens.Spacing.lg)
    ) {
        Text(
            numberLabel(value.toLong()),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = if (alert) f.colors.errorText.color else f.colors.textPrimary.color
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = f.colors.textSecondary.color, maxLines = 1)
    }
}

private val WIDE = 840.dp
private val MEDIUM = 520.dp
private const val STATUSES = 5
