package net.alummahnow.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.ListenModeState
import net.alummahnow.player.QueueEntry
import net.alummahnow.player.R
import net.alummahnow.player.SleepTimerPreset

/**
 * Persistent mini audio bar shown across routes while Listen mode is active.
 * Completely hidden when the `listenMode` feature switch is OFF.
 */
@Composable
fun ListenMiniBar(
    state: ListenModeState,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSwitchToVideo: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!state.listenModeEnabled || !state.isAudioOnly) return
    val current = state.currentEntry ?: return
    val locale = currentLocale()

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Headphones, contentDescription = null, tint = FanousTokens.Lantern)
            Column(Modifier.weight(1f).clickable(onClick = onSwitchToVideo)) {
                Text(
                    current.title.resolve(locale),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                current.channelName?.let { ch ->
                    Text(
                        ch,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onOpenSleepTimer) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = stringResource(R.string.aun_sleep_timer),
                    tint = if (state.sleepTimer.isActive) FanousTokens.Lantern else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.Filled.QueueMusic, contentDescription = stringResource(R.string.aun_queue_title))
            }
            IconButton(onClick = onSwitchToVideo) {
                Icon(Icons.Filled.Videocam, contentDescription = stringResource(R.string.aun_listen_video_mode))
            }
            IconButton(onClick = onTogglePlay) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.aun_player_pause else R.string.aun_player_play),
                    modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.aun_sleep_timer_off))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    current: SleepTimerPreset,
    onSelect: (SleepTimerPreset) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.aun_sleep_timer),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            HorizontalDivider()
            SleepTimerPreset.STANDARD.forEach { preset ->
                val label = when (preset) {
                    SleepTimerPreset.Off -> stringResource(R.string.aun_sleep_timer_off)
                    is SleepTimerPreset.Minutes -> aunString(R.string.aun_sleep_timer_minutes, preset.minutes)
                    SleepTimerPreset.EndOfEpisode -> stringResource(R.string.aun_sleep_timer_end_of_episode)
                }
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { RadioButton(selected = current == preset, onClick = null) },
                    modifier = Modifier.clickable {
                        onSelect(preset)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenQueueSheet(
    queue: List<QueueEntry>,
    onSelect: (QueueEntry) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val locale = currentLocale()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.aun_queue_title), style = MaterialTheme.typography.titleMedium)
                if (queue.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.aun_queue_clear))
                    }
                }
            }
            HorizontalDivider()
            if (queue.isEmpty()) {
                Text(
                    stringResource(R.string.aun_queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )
            } else {
                LazyColumn {
                    items(queue, key = { it.videoId }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(entry.title.resolve(locale), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = entry.channelName?.let { ch -> { Text(ch, maxLines = 1) } },
                            trailingContent = {
                                IconButton(onClick = { onRemove(entry.videoId) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.aun_downloads_remove))
                                }
                            },
                            modifier = Modifier.clickable {
                                onSelect(entry)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}
