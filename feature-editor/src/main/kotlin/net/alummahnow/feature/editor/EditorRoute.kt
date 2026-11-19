package net.alummahnow.feature.editor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.media.EditDecisionList

/** Reads a picked video's length with the platform retriever, off the main thread. */
class RetrieverDurationProbe(private val context: Context) : DurationProbe {
    override suspend fun durationMs(uri: String): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
}

/** The edit list as player items: each clip is its source, clipped to the clip's range, in timeline order. */
fun EditDecisionList.mediaItems(): List<MediaItem> = clips.map { clip ->
    MediaItem.Builder()
        .setUri(clip.sourceUri)
        .setMediaId(clip.id)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(clip.sourceStartMs)
                .setEndPositionMs(clip.sourceStartMs + clip.durationMs)
                .build()
        )
        .build()
}

/** The editor wired to the Photo Picker and a Media3 player that previews the edit as it stands. */
@Composable
fun EditorRoute(viewModel: EditorViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val player = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // A changed edit rebuilds the playlist and keeps the playhead where it was.
    LaunchedEffect(state.list) {
        val list = state.list
        if (list.clips.isEmpty()) {
            player.clearMediaItems()
            return@LaunchedEffect
        }
        val (index, offset) = list.locate(state.playheadMs) ?: (0 to 0L)
        player.setMediaItems(list.mediaItems(), index, offset)
        player.prepare()
    }
    // While playing, the playhead follows the player.
    LaunchedEffect(playing) {
        while (playing) {
            viewModel.playing(state.list.startOf(player.currentMediaItemIndex) + player.currentPosition)
            delay(PLAYHEAD_TICK_MS)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris.map { it.toString() })
    }
    EditorScreen(
        state = state,
        playing = playing,
        onImport = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        onSelect = viewModel::select,
        onScrub = { t ->
            viewModel.scrub(t)
            state.list.locate(t)?.let { (index, offset) -> player.seekTo(index, offset) }
        },
        onMove = viewModel::move,
        onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
        modifier = modifier,
        preview = { ContentFrame(player = player, modifier = Modifier.fillMaxSize()) }
    )
}

private const val PLAYHEAD_TICK_MS = 100L
