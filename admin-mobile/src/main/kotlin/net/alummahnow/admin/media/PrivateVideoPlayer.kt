package net.alummahnow.admin.media

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import net.alummahnow.admin.R
import net.alummahnow.player.SeekGestures

private val SPEEDS = listOf(1f, 1.5f, 2f, 3f)

/**
 * The review player: streams [url] (short-lived, signed, held only by this composable) through [PrivateMedia]. The
 * player is released when the item closes; nothing is cached or downloaded.
 */
@OptIn(UnstableApi::class)
@Composable
fun PrivateVideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        PrivateMedia.newPlayer(context).apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            ContentFrame(player = player, modifier = Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { player.seekTo((player.currentPosition - SeekGestures.SKIP_MS).coerceAtLeast(0)) }) {
                Icon(Icons.Filled.Replay10, contentDescription = stringResource(R.string.player_back_10))
            }
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.player_pause else R.string.player_play)
                )
            }
            IconButton(onClick = { player.seekTo(player.currentPosition + SeekGestures.SKIP_MS) }) {
                Icon(Icons.Filled.Forward10, contentDescription = stringResource(R.string.player_forward_10))
            }
            TextButton(onClick = { speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size] }) {
                Text(
                    stringResource(R.string.player_speed, speed.toString().removeSuffix(".0")),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
