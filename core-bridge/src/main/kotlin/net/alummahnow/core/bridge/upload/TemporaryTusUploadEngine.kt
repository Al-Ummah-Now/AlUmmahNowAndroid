package net.alummahnow.core.bridge.upload

import io.tus.java.client.ProtocolException
import io.tus.java.client.TusClient
import io.tus.java.client.TusURLStore
import io.tus.java.client.TusUpload
import java.io.File
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError

/**
 * TUS upload against `POST /api/videos/tus` using the TUS Java client, until `aun-upload` ships.
 * Needs a signed-in user: the token comes from [tokenProvider] (AuthBridge), so with the guest-only bridge
 * this fails fast with [CoreError.NotSignedIn].
 */
class TemporaryTusUploadEngine(
    private val endpoint: String,
    private val urlStore: TusURLStore,
    private val tokenProvider: suspend () -> String?,
    private val chunkSize: Int = 5 * 1024 * 1024
) : UploadEngine {

    override fun upload(request: UploadRequest): Flow<UploadEvent> = flow {
        val token = tokenProvider() ?: throw CoreError.NotSignedIn()
        val file = File(request.filePath)
        if (!file.exists()) throw CoreError.NotFound(request.fileName)
        val client = TusClient().apply {
            uploadCreationURL = URL(endpoint)
            enableResuming(urlStore)
            headers = mapOf("Authorization" to "Bearer $token")
        }
        val upload = TusUpload(file).apply {
            fingerprint = request.localId
            metadata = buildMap {
                put("filename", request.fileName)
                put("filetype", request.mimeType)
                put("title", request.title)
                request.channelId?.let { put("channelId", it) }
            }
        }
        try {
            val uploader = client.resumeOrCreateUpload(upload)
            uploader.chunkSize = chunkSize
            AunLog.i("upload", "started", "localId" to request.localId, "offset" to uploader.offset)
            while (uploader.uploadChunk() > -1) {
                currentCoroutineContext().ensureActive()
                emit(UploadEvent.Progress(uploader.offset, upload.size))
            }
            uploader.finish()
            val videoId = uploader.uploadURL?.path?.substringAfterLast('/')
            AunLog.i("upload", "completed", "localId" to request.localId)
            emit(UploadEvent.Completed(videoId))
        } catch (e: ProtocolException) {
            val code = e.causingConnection?.responseCode ?: -1
            throw if (code == 401 || code == 403) CoreError.NotSignedIn() else CoreError.Http(code)
        } catch (e: IOException) {
            throw CoreError.Network(e)
        }
    }.flowOn(Dispatchers.IO)
}
