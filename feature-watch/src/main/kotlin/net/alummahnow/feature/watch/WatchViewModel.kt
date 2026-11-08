package net.alummahnow.feature.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.VideoDetail
import net.alummahnow.core.bridge.model.VideoSummary
import net.alummahnow.design.components.ErrorKind

sealed interface WatchUiState {
    data object Loading : WatchUiState
    data class Content(val video: VideoDetail, val related: List<VideoSummary>) : WatchUiState
    data class Error(val kind: ErrorKind) : WatchUiState
}

class WatchViewModel(private val catalog: CatalogRepository, private val videoId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<WatchUiState>(WatchUiState.Loading)
    val state: StateFlow<WatchUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.value = WatchUiState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                val video = catalog.video(videoId)
                // Related is best-effort: the watch page still works if it fails.
                val related = async { runCatching { catalog.related(video) }.getOrDefault(emptyList()) }
                when {
                    !video.isReady -> WatchUiState.Error(ErrorKind.NOT_FOUND)
                    else -> WatchUiState.Content(video, related.await())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreError) {
                AunLog.w("watch", "load failed", "id" to videoId, "error" to e.message)
                WatchUiState.Error(
                    when (e) {
                        is CoreError.Network -> ErrorKind.NETWORK
                        is CoreError.NotFound -> ErrorKind.NOT_FOUND
                        else -> ErrorKind.GENERIC
                    }
                )
            }
        }
    }

    class Factory(private val catalog: CatalogRepository, private val videoId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WatchViewModel(catalog, videoId) as T
    }
}
