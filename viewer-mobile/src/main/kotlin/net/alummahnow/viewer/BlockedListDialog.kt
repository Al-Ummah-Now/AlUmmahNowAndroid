package net.alummahnow.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import net.alummahnow.core.bridge.safety.BlockedEntry
import net.alummahnow.core.bridge.safety.BlockedKind
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.isolate

/**
 * In-app dialog listing device-blocked channels and live chat users so viewers can unblock any entry.
 */
@Composable
fun BlockedListDialog(
    entries: List<BlockedEntry>,
    onUnblock: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.settings_blocked_list)
    val closeFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 420.dp)
            .semantics { paneTitle = title }
            .testTag("blocked-list-dialog"),
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.settings_blocked_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("blocked-list-empty")
                )
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))
                        BlockedRow(entry, onUnblock)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeFocus).testTag("blocked-list-close")
            ) {
                Text(stringResource(R.string.settings_close))
            }
        }
    )
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}

@Composable
private fun BlockedRow(entry: BlockedEntry, onUnblock: (String) -> Unit) {
    val kindLabel = stringResource(
        when (entry.kind) {
            BlockedKind.CHANNEL -> R.string.settings_blocked_kind_channel
            BlockedKind.CHAT_USER -> R.string.settings_blocked_kind_user
        }
    )
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.name.ifBlank { kindLabel },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                kindLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val unblockCd = aunString(R.string.settings_unblock_cd, isolate(entry.name.ifBlank { kindLabel }))
        TextButton(
            onClick = { onUnblock(entry.id) },
            modifier = Modifier
                .semantics { contentDescription = unblockCd }
                .testTag("unblock-${entry.id}")
        ) {
            Text(stringResource(R.string.settings_unblock))
        }
    }
}
