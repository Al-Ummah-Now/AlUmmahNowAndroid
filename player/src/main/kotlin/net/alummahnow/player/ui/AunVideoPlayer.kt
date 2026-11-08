package net.alummahnow.player.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.cast.MediaRouteButton
import androidx.media3.cast.rememberMediaRouteButtonState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.delay
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.player.R
import net.alummahnow.player.SeekGestures

/**
 * Phone/tablet player: video, subtitles drawn in Compose (so Arabic cues get RTL alignment), and an
 * auto-hiding chrome. Double tap on the start/end third seeks ∓10 s, mirrored in RTL (spec 13.2).
 */
@OptIn(UnstableApi::class)
@Composable
fun AunVideoPlayer(
    player: Player,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    showCast: Boolean = true,
    controlsInitiallyVisible: Boolean = true
) {
    val ui = rememberPlayerUiState(player)
    var controlsVisible by remember { mutableStateOf(controlsInitiallyVisible) }
    var settingsOpen by remember { mutableStateOf(false) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(controlsVisible, ui.isPlaying, settingsOpen) {
        if (controlsVisible && ui.isPlaying && !settingsOpen) {
            delay(3_500)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(isRtl) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val delta = SeekGestures.doubleTapDelta(offset.x, size.width.toFloat(), isRtl)
                        if (delta != 0L) player.seekTo(SeekGestures.clampedTarget(player.currentPosition, delta, player.duration))
                    }
                )
            }
    ) {
        ContentFrame(player = player, modifier = Modifier.fillMaxSize())
        CueOverlay(ui, Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 104.dp else 16.dp))
        AnimatedVisibility(visible = controlsVisible || !ui.isPlaying, enter = fadeIn(), exit = fadeOut()) {
            Chrome(
                player = player,
                ui = ui,
                isFullscreen = isFullscreen,
                showCast = showCast,
                onToggleFullscreen = onToggleFullscreen,
                onOpenSettings = { settingsOpen = true }
            )
        }
    }
    if (settingsOpen) {
        PlayerSettingsSheet(player = player, ui = ui, onDismiss = { settingsOpen = false })
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Chrome(
    player: Player,
    ui: PlayerUiState,
    isFullscreen: Boolean,
    showCast: Boolean,
    onToggleFullscreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.65f)))
            )
    ) {
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            if (showCast) {
                MediaRouteButton(modifier = Modifier.size(48.dp), state = rememberMediaRouteButtonState())
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.aun_player_settings), tint = Color.White)
            }
        }
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Row order flips in RTL, so "back" stays on the start side; the icons keep their digits unmirrored.
            IconButton(onClick = { player.seekBack() }) {
                Icon(Icons.Filled.Replay10, stringResource(R.string.aun_player_back10), tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { if (ui.isPlaying) player.pause() else player.play() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (ui.isPlaying) R.string.aun_player_pause else R.string.aun_player_play),
                    tint = FanousTokens.Lantern,
                    modifier = Modifier.size(56.dp)
                )
            }
            IconButton(onClick = { player.seekForward() }) {
                Icon(
                    Icons.Filled.Forward10,
                    stringResource(R.string.aun_player_forward10),
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
            val seekLabel = stringResource(R.string.aun_player_seek)
            Slider(
                value = if (ui.durationMs > 0) ui.positionMs.toFloat() / ui.durationMs else 0f,
                onValueChange = { f -> if (ui.durationMs > 0) player.seekTo((f * ui.durationMs).toLong()) },
                colors = SliderDefaults.colors(thumbColor = FanousTokens.Lantern, activeTrackColor = FanousTokens.Lantern),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = seekLabel }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${durationLabel(ui.positionMs / 1000)} / ${durationLabel(ui.durationMs / 1000)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(
                            if (isFullscreen) R.string.aun_player_exit_fullscreen else R.string.aun_player_fullscreen
                        ),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

/** Renders current cues; text direction follows the cue's own script, so Arabic lines are right-aligned. */
@Composable
fun CueOverlay(ui: PlayerUiState, modifier: Modifier = Modifier) {
    val text = ui.cues.mapNotNull { it.text?.toString() }.joinToString("\n")
    if (text.isBlank()) return
    Text(
        text = text,
        color = Color.White,
        // Cue direction comes from its own script, not the UI: English captions stay LTR in the Arabic UI.
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        textAlign = TextAlign.Center,
        modifier = modifier
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
