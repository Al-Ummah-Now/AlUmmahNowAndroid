package net.alummahnow.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.alummahnow.design.R as DesignR
import net.alummahnow.design.theme.FanousTokens

/**
 * Wraps a gated or signed-out screen with a bottom diagnostics opt-out bar whenever diagnostics
 * are currently active or have an unresolved warning/purge failure, so the user can always opt out
 * or retry purging without having to sign in first. Renders [content] directly when diagnostics
 * are inactive and clean.
 */
@Composable
fun DiagnosticsOptOutScaffold(
    telemetryEnabled: Boolean,
    telemetryWarning: String?,
    lastPurgeFailed: Boolean,
    onDisableTelemetry: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!telemetryEnabled && telemetryWarning.isNullOrEmpty() && !lastPurgeFailed) {
        content()
        return
    }
    Scaffold(
        bottomBar = {
            DiagnosticsOptOutBar(
                telemetryEnabled = telemetryEnabled,
                telemetryWarning = telemetryWarning,
                lastPurgeFailed = lastPurgeFailed,
                onDisableTelemetry = onDisableTelemetry
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            content()
        }
    }
}

@Composable
private fun DiagnosticsOptOutBar(
    telemetryEnabled: Boolean,
    telemetryWarning: String?,
    lastPurgeFailed: Boolean,
    onDisableTelemetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("diagnostics-opt-out-bar"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider()
            val rowModifier = if (telemetryEnabled) {
                Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = true,
                        role = Role.Switch,
                        onValueChange = { if (!it) onDisableTelemetry() }
                    )
                    .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md)
                    .testTag("diagnostics-opt-out-switch")
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md)
            }
            Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = FanousTokens.Spacing.md)) {
                    Text(
                        stringResource(DesignR.string.aun_diagnostics_toggle),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (!telemetryWarning.isNullOrEmpty()) {
                        Text(
                            telemetryWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = FanousTokens.Spacing.xs).testTag("diagnostics-opt-out-warning")
                        )
                    }
                }
                if (telemetryEnabled) {
                    Switch(
                        checked = true,
                        onCheckedChange = null
                    )
                } else if (lastPurgeFailed) {
                    TextButton(
                        onClick = onDisableTelemetry,
                        modifier = Modifier.testTag("diagnostics-opt-out-retry")
                    ) {
                        Text(stringResource(DesignR.string.aun_retry))
                    }
                }
            }
        }
    }
}
