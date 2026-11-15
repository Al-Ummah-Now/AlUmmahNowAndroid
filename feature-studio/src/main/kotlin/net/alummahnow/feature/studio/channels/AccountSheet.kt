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
import androidx.compose.material.icons.filled.Key
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.alummahnow.core.bridge.studio.StudioAccount
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Accounts (switch, add, sign out) and the channel Studio is acting as (switch). */
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
    onDismiss: () -> Unit,
    onAddPasskey: (() -> Unit)? = null
) {
    var confirmSignOut by remember { mutableStateOf<StudioAccount?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("account-sheet"),
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ) {
        AccountSheetContent(
            accounts = accounts,
            activeUserId = activeUserId,
            channel = channel,
            onSwitchAccount = onSwitchAccount,
            onAddAccount = onAddAccount,
            onSignOut = { confirmSignOut = it },
            onSwitchChannel = onSwitchChannel,
            onAddPasskey = onAddPasskey
        )
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

/**
 * The sheet's body. "Acting as" shows the channel Studio is working in (its logo, name and the tag
 * "Channel"); Studio always acts as a channel, so there is no personal identity to pick here.
 * Accounts show the person's picture and name.
 */
@Composable
internal fun AccountSheetContent(
    accounts: List<StudioAccount>,
    activeUserId: String?,
    channel: StudioChannel?,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: (StudioAccount) -> Unit,
    onSwitchChannel: () -> Unit,
    onAddPasskey: (() -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = FanousTokens.Spacing.lg)) {
        if (channel != null) {
            SheetHeading(stringResource(R.string.studio_acting_as))
            SheetRow(
                icon = { ChannelAvatar(channel, 40) },
                title = channel.localizedName(currentLocale().language),
                tag = stringResource(R.string.studio_identity_channel),
                subtitle = stringResource(R.string.studio_channel_switch),
                modifier = Modifier.clickable(role = Role.Button, onClick = onSwitchChannel).testTag("switch-channel"),
                trailing = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) }
            )
            HorizontalDivider()
        }
        SheetHeading(stringResource(R.string.studio_accounts))
        accounts.forEach { a ->
            val active = a.userId == activeUserId
            SheetRow(
                icon = { AccountAvatar(a) },
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
                    IconButton(onClick = { onSignOut(a) }, modifier = Modifier.testTag("sign-out-${a.userId}")) {
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
        if (onAddPasskey != null) {
            SheetRow(
                icon = { Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(40.dp).padding(8.dp)) },
                title = stringResource(net.alummahnow.feature.passkey.R.string.passkey_add),
                subtitle = stringResource(net.alummahnow.feature.passkey.R.string.passkey_explainer),
                modifier = Modifier.clickable(role = Role.Button, onClick = onAddPasskey).testTag("add-passkey")
            )
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(FanousTokens.Spacing.lg).semantics { heading() }
    )
}

@Composable
private fun SheetRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    tag: String? = null,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (tag != null) IdentityTag(tag)
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

/** The person's picture, over their initial while it loads (or when they have none). */
@Composable
private fun AccountAvatar(account: StudioAccount) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center
    ) {
        Text(account.label.trim().firstOrNull()?.uppercase() ?: "?", color = MaterialTheme.colorScheme.onTertiary)
        if (account.avatarUrl != null) {
            AsyncImage(
                model = account.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape)
            )
        }
    }
}
