package net.alummahnow.feature.studio.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
import net.alummahnow.design.components.aunGlass
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** First run: the account has channels but none is chosen yet. Studio's top bar sits above it. */
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
                        channelSections(state.channels) { ch ->
                            ChannelCard(ch, selected = ch.id == state.selectedId, onClick = { onSelect(ch.id) })
                        }
                    }
                }
            }
        }
    }
}

/** Switching: the Acting as picker as a bottom sheet over Studio; closing it keeps the current channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelPickerSheet(
    channels: List<StudioChannel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("channel-sheet"),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        shape = RectangleShape
    ) {
        ChannelPickerPanel(channels, selectedId, onSelect, onClose = onDismiss)
    }
}

/** The sheet's body: a glass panel headed "Acting as" with a close button, then the channel sections. */
@Composable
fun ChannelPickerPanel(
    channels: List<StudioChannel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    SheetPanel(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = FanousTokens.Spacing.lg, end = FanousTokens.Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.studio_acting_as),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() }
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("channel-sheet-close")) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.studio_close))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).testTag("channel-list")) {
            channelSections(channels, labelPadding = PaddingValues(horizontal = FanousTokens.Spacing.lg)) { ch ->
                ChannelListRow(ch, selected = ch.id == selectedId, onClick = { onSelect(ch.id) })
            }
        }
    }
}

/**
 * A bottom sheet's glass panel: rounded top, a drag handle, at most 640dp wide (centred on tablets)
 * and only as tall as its content.
 */
@Composable
fun SheetPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(topStart = FanousTokens.Radius.lg, topEnd = FanousTokens.Radius.lg)
    Box(modifier.fillMaxWidth().padding(top = FanousTokens.Spacing.lg), contentAlignment = Alignment.BottomCenter) {
        // The sheet's container is transparent (the glass draws it), so set the text colour here.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            SheetColumn(shape, content)
        }
    }
}

@Composable
private fun SheetColumn(shape: Shape, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .widthIn(max = SHEET_MAX_WIDTH)
            .fillMaxWidth()
            .aunGlass(shape)
            .padding(bottom = FanousTokens.Spacing.lg)
    ) {
        Box(
            Modifier
                .padding(vertical = FanousTokens.Spacing.sm)
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = HANDLE_ALPHA))
                .align(Alignment.CenterHorizontally)
        )
        content()
    }
}

private const val HANDLE_ALPHA = 0.4f

internal val SHEET_MAX_WIDTH = 640.dp

/** The web's Acting as order: channels you own, then channels you hold a role on. */
private fun LazyListScope.channelSections(
    channels: List<StudioChannel>,
    labelPadding: PaddingValues = PaddingValues(),
    row: @Composable (StudioChannel) -> Unit
) {
    val (owned, managed) = channels.partition { it.role == ChannelRole.OWNER }
    listOf(R.string.studio_channels_yours to owned, R.string.studio_channels_managed to managed)
        .filter { it.second.isNotEmpty() }
        .forEach { (section, list) ->
            item(key = "section-$section") { SectionLabel(stringResource(section), Modifier.padding(labelPadding)) }
            items(list, key = { it.id }) { row(it) }
        }
}

@Composable
private fun ChannelCard(channel: StudioChannel, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FanousTokens.Radius.md)
    Box(
        Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .aunGlass(shape)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("channel-${channel.id}")
    ) {
        ChannelRowContent(channel, selected, Modifier.padding(FanousTokens.Spacing.lg))
    }
}

@Composable
private fun ChannelListRow(channel: StudioChannel, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("channel-${channel.id}")
    ) {
        ChannelRowContent(channel, selected, Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md))
    }
}

@Composable
private fun ChannelRowContent(channel: StudioChannel, selected: Boolean, modifier: Modifier) {
    val language = currentLocale().language
    Row(
        modifier,
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

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(top = FanousTokens.Spacing.sm, bottom = FanousTokens.Spacing.xs)
            .semantics { heading() }
    )
}

/** The small flat tag beside an identity's name in an Acting as picker: "Channel" (the web's nav-identity-tag). */
@Composable
fun IdentityTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(FanousTokens.Radius.sm))
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
