package net.alummahnow.feature.studio.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Accounts (switch, add, sign out) and the current channel (switch). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    accounts: List<StudioAccount>,
    activeUserId: String?,
    channel: StudioChannel?,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (String) -> Unit,
    onSwitchChannel: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmSignOut by remember { mutableStateOf<StudioAccount?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("account-sheet"),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = FanousTokens.Spacing.lg)) {
            if (channel != null) {
                SheetRow(
                    icon = { ChannelAvatar(channel, 40) },
                    title = channel.localizedName(currentLocale().language),
                    subtitle = stringResource(R.string.studio_channel_switch),
                    modifier = Modifier.clickable(role = Role.Button, onClick = onSwitchChannel).testTag("switch-channel"),
                    trailing = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) }
                )
                HorizontalDivider()
            }
            Text(
                stringResource(R.string.studio_accounts),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(FanousTokens.Spacing.lg).semantics { heading() }
            )
            accounts.forEach { a ->
                val active = a.userId == activeUserId
                SheetRow(
                    icon = { Initial(a.label) },
                    title = a.label,
                    subtitle = when {
                        a.needsReauth -> stringResource(R.string.studio_account_reauth)
                        active -> stringResource(R.string.studio_account_active)
                        else -> a.email.orEmpty()
                    },
                    modifier = Modifier
                        .semantics { selected = active }
                        .clickable(enabled = !active, role = Role.Button) { onSwitchAccount(a.userId) }
                        .testTag("account-${a.userId}"),
                    trailing = {
                        IconButton(onClick = { confirmSignOut = a }, modifier = Modifier.testTag("sign-out-${a.userId}")) {
                            Icon(
                                Icons.AutoMirrored.Filled.Logout,
                                contentDescription = stringResource(R.string.studio_sign_out_cd, isolate(a.label))
                            )
                        }
                    }
                )
            }
            SheetRow(
                icon = { Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp)) },
                title = stringResource(R.string.studio_signin_add),
                subtitle = null,
                modifier = Modifier.clickable(role = Role.Button, onClick = onAddAccount).testTag("add-account")
            )
        }
    }
    confirmSignOut?.let { a ->
        ConfirmDialog(
            title = stringResource(R.string.studio_sign_out_title, isolate(a.label)),
            message = stringResource(R.string.studio_sign_out_body),
            confirmLabel = stringResource(R.string.studio_sign_out),
            onConfirm = {
                confirmSignOut = null
                onSignOut(a.userId)
            },
            onDismiss = { confirmSignOut = null }
        )
    }
}

@Composable
private fun SheetRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
private fun Initial(label: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center
    ) {
        Text(label.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onTertiary)
    }
}
