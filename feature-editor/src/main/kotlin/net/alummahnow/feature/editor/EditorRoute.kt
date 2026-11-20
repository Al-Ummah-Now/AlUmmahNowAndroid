package net.alummahnow.feature.editor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.media.EditClip
import net.alummahnow.core.bridge.media.EditDecisionList

/** Reads a picked video's length with the platform retriever, off the main thread. */
class RetrieverDurationProbe(private val context: Context) : DurationProbe {
    override suspend fun durationMs(uri: String): Long? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri.toUri())
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

/** Where exports go: one folder in the cache, the only one the editor's file provider shares. */
fun editorExportDir(context: Context): File = File(context.cacheDir, "editor-exports")

/**
 * The editor wired to the Photo Picker, a Media3 player that previews the edit as it stands, and the export's Share
 * and Upload this video (`onSendToUpload(uri, name, sizeBytes)` hands the file to Studio's upload queue).
 */
@OptIn(UnstableApi::class)
@Composable
fun EditorRoute(
    viewModel: EditorViewModel,
    onSendToUpload: ((uri: String, name: String, sizeBytes: Long) -> Unit)?,
    modifier: Modifier = Modifier
) {
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
    val frames = remember { ClipFrames(context.applicationContext) }
    val tick by frames.loaded.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalClipThumbnail provides { clip -> tick.let { frames.frame(clip) } }) {
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
            tools = EditorTools(
                onSplit = viewModel::split,
                onDelete = viewModel::deleteSelected,
                onTrim = viewModel::trim,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onExport = viewModel::export,
                onShare = { state.exportedPath?.let { context.shareVideo(File(it)) } },
                // No Upload action when uploading is off for this person (the Upload tab is hidden too).
                onSendToUpload = onSendToUpload?.let { send ->
                    {
                        state.exportedPath?.let { path ->
                            val file = File(path)
                            send(context.exportUri(file).toString(), file.name, file.length())
                        }
                    }
                }
            ),
            preview = { ContentFrame(player = player, modifier = Modifier.fillMaxSize()) }
        )
    }
}

private const val PLAYHEAD_TICK_MS = 100L

private fun Context.exportUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.editor.exports", file)

/** The system share sheet with the exported video, readable only through this intent. */
private fun Context.shareVideo(file: File) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, exportUri(file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * Frames for the clip list: the source at the clip's start, scaled down, read off the main thread once per source
 * position. `loaded` ticks when one arrives so the list recomposes.
 */
class ClipFrames(private val context: Context) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    private val pending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val tick = kotlinx.coroutines.flow.MutableStateFlow(0)
    val loaded: kotlinx.coroutines.flow.StateFlow<Int> = tick

    fun frame(clip: EditClip): ImageBitmap? {
        val key = "${clip.sourceUri}@${clip.sourceStartMs}"
        cache[key]?.let { return it }
        if (pending.add(key)) {
            scope.launch {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, clip.sourceUri.toUri())
                    val at = clip.sourceStartMs * MICROS_PER_MS
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_WIDTH, FRAME_WIDTH * 9 / 16)
                    } else {
                        // API 26 has no scaled read: take the frame and scale it here.
                        retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                            Bitmap.createScaledBitmap(it, FRAME_WIDTH, FRAME_WIDTH * it.height / it.width.coerceAtLeast(1), true)
                        }
                    }
                    frame?.let { cache[key] = it.asImageBitmap() }
                } catch (e: IllegalArgumentException) {
                    net.alummahnow.core.bridge.AunLog.w("editor", "no frame", "type" to e.javaClass.simpleName)
                } finally {
                    retriever.release()
                    tick.value += 1
                }
            }
        }
        return null
    }

    private companion object {
        const val MICROS_PER_MS = 1000L
        const val FRAME_WIDTH = 320
    }
}
