package net.alummahnow.feature.studio.channels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.AccountSession
import net.alummahnow.core.bridge.studio.StudioChannel
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

sealed interface ChannelsUiState {
    data object Loading : ChannelsUiState
    data class Content(val channels: List<StudioChannel>, val selectedId: String?) : ChannelsUiState {
        val selected: StudioChannel? get() = channels.firstOrNull { it.id == selectedId }
    }
    data class Error(@param:StringRes val message: Int) : ChannelsUiState
}

/** The active account's channels and the selected one (remembered per account in aun-auth). */
class ChannelsViewModel(
    private val session: AccountSession,
    private val repository: StudioRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow<ChannelsUiState>(ChannelsUiState.Loading)
    val state: StateFlow<ChannelsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            session.active.distinctUntilChangedBy { it?.userId }.collect { if (it != null) load() }
        }
    }

    fun load() {
        mutableState.value = ChannelsUiState.Loading
        viewModelScope.launch {
            mutableState.value = attempt("studio", "channels") { repository.channels() }.fold(
                onSuccess = { channels ->
                    val remembered = session.active.value?.activeChannelId
                    // The remembered channel; with a single channel, that one; otherwise ask.
                    val selected = channels.firstOrNull { it.id == remembered }?.id
                        ?: if (channels.size == 1) channels.first().id else null
                    if (selected != null && selected != remembered) runCatching { session.setActiveChannel(selected) }
                    AunLog.i("studio", "channels loaded", "count" to channels.size)
                    ChannelsUiState.Content(channels, selected)
                },
                onFailure = { ChannelsUiState.Error(it.studioMessage()) }
            )
        }
    }

    fun select(channelId: String) {
        val s = mutableState.value as? ChannelsUiState.Content ?: return
        runCatching { session.setActiveChannel(channelId) }.onFailure { AunLog.e("studio", "remember channel failed", it) }
        mutableState.value = s.copy(selectedId = channelId)
    }

    /** Back to the picker (keeps the list). */
    fun clearSelection() {
        val s = mutableState.value as? ChannelsUiState.Content ?: return
        mutableState.value = s.copy(selectedId = null)
    }

    class Factory(private val session: AccountSession, private val repository: StudioRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ChannelsViewModel(session, repository) as T
    }
}
