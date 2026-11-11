package net.alummahnow.feature.studio.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Content(val access: CreatorAccess?, val counts: ContentCounts?) : DashboardUiState
    data class Error(@param:StringRes val message: Int) : DashboardUiState
}

class DashboardViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.value = DashboardUiState.Loading
        viewModelScope.launch {
            val access = async { attempt("studio", "creator access") { repository.creatorAccess() }.getOrNull() }
            val counts = attempt("studio", "counts") { repository.counts(channelId) }
            mutableState.value = counts.fold(
                onSuccess = { DashboardUiState.Content(access.await(), it) },
                onFailure = { DashboardUiState.Error(it.studioMessage()) }
            )
        }
    }
}
