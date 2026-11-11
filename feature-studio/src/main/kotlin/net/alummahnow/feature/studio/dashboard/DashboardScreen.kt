package net.alummahnow.feature.studio.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.localizedDecimal
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.channels.ChannelAvatar
import net.alummahnow.feature.studio.channels.roleLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(channel: StudioChannel, state: DashboardUiState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        DashboardUiState.Loading -> FullScreenLoading(modifier)
        is DashboardUiState.Error -> ErrorState(stringResource(state.message), onRetry, modifier)
        is DashboardUiState.Content -> Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg).testTag("dashboard"),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
            Row(
                width,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
            ) {
                ChannelAvatar(channel, 56)
                Column {
                    Text(
                        channel.localizedName(currentLocale().language),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() }
                    )
                    Text(stringResource(roleLabel(channel.role)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.access?.let { a ->
                Card(width, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Text(
                        stringResource(if (a.canUpload) R.string.studio_dashboard_upload_ok else R.string.studio_dashboard_upload_invite),
                        modifier = Modifier.padding(FanousTokens.Spacing.lg)
                    )
                }
            }
            state.counts?.let { c ->
                Text(
                    stringResource(R.string.studio_dashboard_counts),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = width.semantics {
                        heading()
                    }
                )
                FlowRow(
                    width,
                    horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
                ) {
                    StatTile(stringResource(R.string.studio_status_published), c.published)
                    StatTile(stringResource(R.string.studio_status_scheduled), c.scheduled)
                    StatTile(stringResource(R.string.studio_status_drafts), c.drafts)
                    StatTile(stringResource(R.string.studio_status_processing), c.processing)
                    StatTile(stringResource(R.string.studio_status_failed), c.failed)
                }
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: Number, modifier: Modifier = Modifier) {
    Card(
        modifier.widthIn(min = 140.dp).semantics(mergeDescendants = true) {
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(FanousTokens.Spacing.lg)) {
            val text = if (value is Double) localizedDecimal(value.toString()) else localizedDigits(value.toString())
            Text(text, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
