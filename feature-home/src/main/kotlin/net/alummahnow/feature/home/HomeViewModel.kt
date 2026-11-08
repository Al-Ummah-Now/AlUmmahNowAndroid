package net.alummahnow.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.catalog.CatalogRepository
import net.alummahnow.core.bridge.model.Rail
import net.alummahnow.design.components.ErrorKind

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Content(val rails: List<Rail>, val isRefreshing: Boolean = false) : HomeUiState
    data class Error(val kind: ErrorKind) : HomeUiState
}

class HomeViewModel(private val catalog: CatalogRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    /** Pull-to-refresh keeps the current rails on screen; a first load or retry shows the loader. */
    fun load() {
        val current = mutableState.value
        mutableState.value = if (current is HomeUiState.Content) current.copy(isRefreshing = true) else HomeUiState.Loading
        viewModelScope.launch {
            mutableState.value = try {
                val rails = catalog.homeRails()
                AunLog.i("home", "loaded", "rails" to rails.size)
                HomeUiState.Content(rails)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreError) {
                if (current is HomeUiState.Content) current.copy(isRefreshing = false) else HomeUiState.Error(e.toKind())
            }
        }
    }

    class Factory(private val catalog: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(catalog) as T
    }
}

internal fun CoreError.toKind(): ErrorKind = when (this) {
    is CoreError.Network -> ErrorKind.NETWORK
    is CoreError.NotFound -> ErrorKind.NOT_FOUND
    else -> ErrorKind.GENERIC
}
