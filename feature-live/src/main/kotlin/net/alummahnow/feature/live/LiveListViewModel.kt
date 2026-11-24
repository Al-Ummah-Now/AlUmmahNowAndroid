package net.alummahnow.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.live.LiveEventType
import net.alummahnow.core.bridge.live.LiveRepository
import net.alummahnow.core.bridge.live.LiveStatus
import net.alummahnow.core.bridge.live.LiveStream
import net.alummahnow.design.components.ErrorKind

/** What is on air and what is coming. [hasEvents]: the event filter is offered. */
data class LiveListContent(
    val onAir: List<LiveStream> = emptyList(),
    val coming: List<LiveStream> = emptyList(),
    val hasEvents: Boolean = false,
    val event: LiveEventType? = null
) {
    /** Home's rail: on air first. Nothing on the screen while there is none. */
    val all: List<LiveStream> get() = onAir + coming
}

sealed interface LiveListUiState {
    data object Loading : LiveListUiState
    data class Content(val list: LiveListContent) : LiveListUiState
    data class Error(val kind: ErrorKind) : LiveListUiState
}

class LiveListViewModel(private val live: LiveRepository, private val now: () -> Instant = Instant::now) : ViewModel() {
    private val mutableState = MutableStateFlow<LiveListUiState>(LiveListUiState.Loading)
    val state: StateFlow<LiveListUiState> = mutableState.asStateFlow()
    private var event: LiveEventType? = null

    init {
        load()
    }

    fun choose(event: LiveEventType?) {
        this.event = event
        load()
    }

    fun load() {
        viewModelScope.launch {
            try {
                mutableState.value = LiveListUiState.Content(read())
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreError) {
                AunLog.w("live", "list failed", "error" to e.message)
                // What is shown stays, as after a failed refresh
                if (mutableState.value !is LiveListUiState.Content) mutableState.value = LiveListUiState.Error(e.toKind())
            }
        }
    }

    private suspend fun read(): LiveListContent = coroutineScope {
        val features = live.features()
        if (!features.events) event = null
        val chosen = event
        val onAir = async { live.streams(LiveStatus.LIVE, chosen) }
        val scheduled = async { if (features.hasComing) live.streams(LiveStatus.SCHEDULED, chosen) else emptyList() }
        val moment = now()
        LiveListContent(
            onAir = onAir.await(),
            coming = scheduled.await().filter { it.scheduledStart?.isAfter(moment) == true }.sortedBy { it.scheduledStart },
            hasEvents = features.events,
            event = chosen
        )
    }

    class Factory(private val live: LiveRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LiveListViewModel(live) as T
    }
}

internal fun CoreError.toKind(): ErrorKind = when (this) {
    is CoreError.Network -> ErrorKind.NETWORK
    is CoreError.NotFound -> ErrorKind.NOT_FOUND
    else -> ErrorKind.GENERIC
}
