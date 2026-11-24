package net.alummahnow.feature.studio.terms

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.attempt

/**
 * Fail-closed state of Creator Terms acceptance for the active account.
 * Upload stays blocked in [Loading], [Failed], and [Required]; only [Accepted] unlocks Upload.
 */
sealed interface CreatorTermsUiState {
    data object Loading : CreatorTermsUiState
    data object Failed : CreatorTermsUiState
    data class Required(
        val saving: Boolean = false,
        @param:StringRes val errorRes: Int? = null
    ) : CreatorTermsUiState
    data object Accepted : CreatorTermsUiState
}

/** Loads and records Creator Terms acceptance (`GET /api/me/creator` and `POST /api/me/creator/terms`). */
class CreatorTermsViewModel(private val repository: StudioRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<CreatorTermsUiState>(CreatorTermsUiState.Loading)
    val state: StateFlow<CreatorTermsUiState> = mutableState.asStateFlow()
    private var acceptGeneration = 0

    init {
        load()
    }

    /**
     * Reads creator access; fails closed to [CreatorTermsUiState.Failed] on network/API error unless already accepted.
     * An older server that omits `termsAccepted` (`null`) falls through to [CreatorTermsUiState.Accepted] so creators
     * are never locked out before the server PR deploys; only an explicit `false` shows the gate.
     */
    fun load() {
        val current = mutableState.value
        val keepVisible = current == CreatorTermsUiState.Accepted || current is CreatorTermsUiState.Required
        if (!keepVisible) mutableState.value = CreatorTermsUiState.Loading
        val startGen = acceptGeneration
        viewModelScope.launch {
            attempt("studio", "creator-terms-load") { repository.creatorAccess() }.fold(
                onSuccess = { access ->
                    val latest = mutableState.value
                    // Ignore a load started before an accept() completed, or while accept() is saving.
                    if (startGen != acceptGeneration) return@fold
                    if (latest is CreatorTermsUiState.Required && latest.saving) return@fold
                    mutableState.value = if (access.termsAccepted == false) {
                        (latest as? CreatorTermsUiState.Required) ?: CreatorTermsUiState.Required()
                    } else {
                        CreatorTermsUiState.Accepted
                    }
                },
                onFailure = {
                    if (startGen == acceptGeneration && !keepVisible) {
                        mutableState.value = CreatorTermsUiState.Failed
                    }
                }
            )
        }
    }

    /** Re-checks on resume so a server-side version bump or acceptance on another device takes effect. */
    fun refresh() {
        val current = mutableState.value
        if (current is CreatorTermsUiState.Required && current.saving) return
        load()
    }

    /** Reopens the gate when an upload fails with `403 terms_required`. */
    fun requireTerms() {
        if (mutableState.value !is CreatorTermsUiState.Required) {
            mutableState.value = CreatorTermsUiState.Required()
        }
    }

    /** Records acceptance on the server and unlocks Upload only on success. */
    fun accept() {
        val current = mutableState.value as? CreatorTermsUiState.Required ?: return
        if (current.saving) return
        mutableState.value = current.copy(saving = true, errorRes = null)
        viewModelScope.launch {
            attempt("studio", "accept-terms") { repository.acceptCreatorTerms() }.fold(
                onSuccess = {
                    acceptGeneration++
                    mutableState.value = CreatorTermsUiState.Accepted
                },
                onFailure = {
                    mutableState.value = CreatorTermsUiState.Required(
                        saving = false,
                        errorRes = R.string.studio_terms_error
                    )
                }
            )
        }
    }
}
