package net.alummahnow.feature.studio.features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UserFeatures
import net.alummahnow.feature.studio.attempt

/**
 * What the signed-in person sees in [channelId] (`GET /api/me/features?channelId=`). Studio makes one per account and
 * channel (so an account or channel switch reads it again) and calls [refresh] when the app comes to the foreground.
 *
 * Until the first answer every gated entry is hidden. A failed read keeps the last answer, so a dropped connection
 * never makes a tab appear or vanish.
 */
class StudioFeaturesViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(UserFeatures.NONE)
    val state: StateFlow<UserFeatures> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            attempt("studio", "features") { repository.features(channelId) }.onSuccess { mutableState.value = it }
        }
    }
}
