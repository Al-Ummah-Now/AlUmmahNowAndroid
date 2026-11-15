package net.alummahnow.feature.studio.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.alummahnow.core.bridge.studio.ChannelRole
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.FullScreenLoading
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

@Composable
fun ChannelPickerScreen(
    state: ChannelsUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {}
) {
    androidx.compose.material3.Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            header()
            when (state) {
                ChannelsUiState.Loading -> FullScreenLoading()
                is ChannelsUiState.Error -> ErrorState(stringResource(state.message), onRetry)
                is ChannelsUiState.Content -> if (state.channels.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(FanousTokens.Spacing.xl), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.studio_channels_empty), style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("channel-list"),
                        contentPadding = PaddingValues(FanousTokens.Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        item {
                            Text(
                                stringResource(R.string.studio_acting_as),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().semantics { heading() }
                            )
                        }
                        // The web's Acting as order: channels you own, then channels you hold a role on.
                        val (owned, managed) = state.channels.partition { it.role == ChannelRole.OWNER }
                        listOf(R.string.studio_channels_yours to owned, R.string.studio_channels_managed to managed)
                            .filter { it.second.isNotEmpty() }
                            .forEach { (section, channels) ->
                                item(key = "section-$section") { SectionLabel(stringResource(section)) }
                                items(channels, key = { it.id }) { ch ->
                                    ChannelRow(ch, selected = ch.id == state.selectedId, onClick = { onSelect(ch.id) })
                                }
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: StudioChannel, selected: Boolean, onClick: () -> Unit) {
    val language = currentLocale().language
    Card(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("channel-${channel.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            Modifier.padding(FanousTokens.Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            ChannelAvatar(channel, 48)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                    Text(
                        channel.localizedName(language),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IdentityTag(stringResource(R.string.studio_identity_channel))
                }
                val details = buildList {
                    channel.handle?.let { add(isolate("@$it")) }
                    add(stringResource(roleLabel(channel.role)))
                    if (channel.isMain) add(stringResource(R.string.studio_channel_main))
                    if (channel.isSuspended) add(stringResource(R.string.studio_channel_suspended))
                }
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = FanousTokens.Spacing.sm).semantics { heading() }
    )
}

/** The small tag under an identity's name in an Acting as picker: "Channel" (the web's nav-identity-tag). */
@Composable
fun IdentityTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(FanousTokens.Radius.sm))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("identity-tag")
    )
}

@Composable
fun ChannelAvatar(channel: StudioChannel, sizeDp: Int) {
    val initial = channel.localizedName(currentLocale().language).trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondary)
        if (channel.avatarUrl != null) {
            AsyncImage(
                model = channel.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(sizeDp.dp).clip(CircleShape)
            )
        }
    }
}

fun roleLabel(role: ChannelRole): Int = when (role) {
    ChannelRole.OWNER -> R.string.studio_role_owner
    ChannelRole.ADMIN -> R.string.studio_role_admin
    ChannelRole.EDITOR -> R.string.studio_role_editor
    ChannelRole.MODERATOR -> R.string.studio_role_moderator
}
