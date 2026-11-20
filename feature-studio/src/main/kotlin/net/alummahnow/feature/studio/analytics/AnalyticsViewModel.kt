package net.alummahnow.feature.studio.analytics

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.AnalyticsRange
import net.alummahnow.core.bridge.studio.ChannelAnalytics
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.hidesFeature
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

data class AnalyticsUiState(
    val range: AnalyticsRange = AnalyticsRange.LAST_28,
    val loading: Boolean = true,
    val data: ChannelAnalytics? = null,
    @param:StringRes val error: Int? = null,
    /** The server has answered at least once, so the app knows whether Analytics is there. */
    val checked: Boolean = false,
    /** The server said Analytics is not there (404 / `feature_off` / 402): the tab is removed, nothing is said. */
    val hidden: Boolean = false
) {
    /** Whether Studio shows the Analytics tab: only once the server has answered and not said "not here". */
    val visible: Boolean get() = checked && !hidden
}

/** Channel analytics. Studio creates it with the channel, so a "not here" answer removes the tab before anyone opens it. */
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
        val current = viewModelScope.launch {
            attempt("studio", "analytics") { repository.analytics(channelId, range) }.fold(
                onSuccess = { d -> mutableState.update { it.copy(loading = false, data = d, checked = true, hidden = false) } },
                onFailure = { e ->
                    mutableState.update {
                        if (e.hidesFeature() || e is CoreError.NotFound) {
                            it.copy(loading = false, checked = true, hidden = true)
                        } else {
                            it.copy(loading = false, checked = true, error = e.studioMessage())
                        }
                    }
                }
            )
        }
        job = current
        // A load cancelled without a newer one in its place must not leave the spinner up.
        current.invokeOnCompletion { cause ->
            if (cause is CancellationException && job === current && mutableState.value.loading) {
                mutableState.update { it.copy(loading = false, checked = true, error = net.alummahnow.design.R.string.aun_error_generic) }
            }
        }
    }
}
