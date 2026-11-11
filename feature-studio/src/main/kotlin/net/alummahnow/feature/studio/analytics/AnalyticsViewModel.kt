package net.alummahnow.feature.studio.analytics

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

data class AnalyticsUiState(
    val range: AnalyticsRange = AnalyticsRange.LAST_28,
    val loading: Boolean = true,
    val data: ChannelAnalytics? = null,
    @param:StringRes val error: Int? = null
)

class AnalyticsViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(AnalyticsUiState())
    val state: StateFlow<AnalyticsUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun setRange(range: AnalyticsRange) {
        if (range == mutableState.value.range) return
        mutableState.update { it.copy(range = range) }
        load()
    }

    fun load() {
        job?.cancel()
        val range = mutableState.value.range
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            attempt("studio", "analytics") { repository.analytics(channelId, range) }.fold(
                onSuccess = { d -> mutableState.update { it.copy(loading = false, data = d) } },
                onFailure = { e -> mutableState.update { it.copy(loading = false, error = e.studioMessage()) } }
            )
        }
    }
}
