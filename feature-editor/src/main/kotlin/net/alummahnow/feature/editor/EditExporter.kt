package net.alummahnow.feature.editor

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.alummahnow.core.bridge.media.EditDecisionList

/** Renders an edit to one MP4. `progress` gets 0..1 while it runs. */
fun interface EditExport {
    suspend fun export(list: EditDecisionList, output: File, progress: (Float) -> Unit)
}

/**
 * Media3 Transformer export, as Apple's AVFoundation export: the clips in timeline order, each clipped to its range,
 * into H.264 MP4 the normal upload takes. Transformer runs on the main looper.
 */
@OptIn(UnstableApi::class)
class TransformerExport(private val context: Context) : EditExport {
    override suspend fun export(list: EditDecisionList, output: File, progress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        val items = list.mediaItems().map { EditedMediaItem.Builder(it).build() }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
        coroutineScope {
            var running: Transformer? = null
            val ticker = launch {
                val holder = ProgressHolder()
                while (true) {
                    val state = running?.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / PERCENT)
                    delay(PROGRESS_TICK_MS)
                }
            }
            try {
                suspendCancellableCoroutine { done ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) = done.resume(Unit)
                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) =
                                done.resumeWithException(exception)
                        })
                        .build()
                    running = transformer
                    done.invokeOnCancellation { transformer.cancel() }
                    transformer.start(composition, output.path)
                }
            } finally {
                ticker.cancel()
            }
        }
        progress(1f)
    }

    private companion object {
        const val PROGRESS_TICK_MS = 250L
        const val PERCENT = 100f
    }
}
