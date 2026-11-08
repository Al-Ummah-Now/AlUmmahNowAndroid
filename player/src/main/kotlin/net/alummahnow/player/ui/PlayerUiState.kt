package net.alummahnow.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import kotlinx.coroutines.delay

/** Snapshot of the player that the Compose chrome renders. Updated from listener callbacks + a 500 ms ticker. */
class PlayerUiState internal constructor(private val player: Player) {
    var isPlaying by mutableStateOf(player.isPlaying)
        internal set
    var isBuffering by mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
        internal set
    var positionMs by mutableStateOf(player.currentPosition)
        internal set
    var durationMs by mutableStateOf(player.duration.coerceAtLeast(0))
        internal set
    var bufferedMs by mutableStateOf(player.bufferedPosition)
        internal set
    var speed by mutableStateOf(player.playbackParameters.speed)
        internal set
    var tracks by mutableStateOf(player.currentTracks)
        internal set
    var params by mutableStateOf(player.trackSelectionParameters)
        internal set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        internal set
    var hasError by mutableStateOf(player.playerError != null)
        internal set

    internal fun refresh() {
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        positionMs = player.currentPosition
        durationMs = player.duration.coerceAtLeast(0)
        bufferedMs = player.bufferedPosition
        speed = player.playbackParameters.speed
        params = player.trackSelectionParameters
        hasError = player.playerError != null
    }

    internal val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
        override fun onTracksChanged(tracks: Tracks) {
            this@PlayerUiState.tracks = tracks
        }
        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            cues = cueGroup.cues
        }
    }
}

@Composable
fun rememberPlayerUiState(player: Player): PlayerUiState {
    val state = remember(player) { PlayerUiState(player) }
    DisposableEffect(player) {
        player.addListener(state.listener)
        state.refresh()
        state.tracks = player.currentTracks
        onDispose { player.removeListener(state.listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state.refresh()
            delay(500)
        }
    }
    return state
}
