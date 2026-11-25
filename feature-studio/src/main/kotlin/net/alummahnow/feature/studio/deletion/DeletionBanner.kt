package net.alummahnow.feature.studio.deletion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import net.alummahnow.core.bridge.studio.deletion.DeletionRecord
import net.alummahnow.core.bridge.studio.deletion.DeletionStage
import net.alummahnow.core.bridge.studio.deletion.Leftover
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Says, on every screen, that a deletion of this account started and isn't finished. It can't be dismissed. */
@Composable
fun DeletionBanner(record: DeletionRecord, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val erased = record.stage == DeletionStage.CONTENT_ERASED
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().testTag("deletion-banner").semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Column(
            Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
        ) {
            Text(
                stringResource(
                    when {
                        erased && record.leftover == Leftover.CHANNEL -> R.string.studio_delete_open_channel
                        erased && record.leftover == null -> R.string.studio_delete_open_content_erased
                        else -> R.string.studio_delete_open
                    }
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            FanousButton(
                stringResource(R.string.studio_delete_finish),
                onFinish,
                kind = FanousButtonKind.Secondary,
                modifier = Modifier.align(Alignment.End).testTag("deletion-finish")
            )
        }
    }
}
