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
        @param:StringRes val errorRes: Int? = null,
        /** The server refused an upload because the Terms are not accepted: the gate says so. */
        val uploadRefused: Boolean = false
    ) : CreatorTermsUiState
    data object Accepted : CreatorTermsUiState
}

/** Loads and records Creator Terms acceptance (`GET /api/me/creator` and `POST /api/me/creator/terms`). */
class CreatorTermsViewModel(private val repository: StudioRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<CreatorTermsUiState>(CreatorTermsUiState.Loading)
    val state: StateFlow<CreatorTermsUiState> = mutableState.asStateFlow()
    private var acceptGeneration = 0
    private var newestLoad = 0
    private val mutableRefused = MutableStateFlow<Set<String>>(emptySet())

    /** Uploads the server refused while it said the Terms were not accepted. The person retries them. */
    val refusedForTerms: StateFlow<Set<String>> = mutableRefused.asStateFlow()

    /** Refused uploads as last reported, and those whose reason is not known yet. */
    private var reported: Set<String> = emptySet()
    private var unexplained: Set<String> = emptySet()

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
        val thisLoad = ++newestLoad
        viewModelScope.launch {
            attempt("studio", "creator-terms-load") { repository.creatorAccess() }.fold(
                onSuccess = { access ->
                    val latest = mutableState.value
                    // Ignore a load started before an accept() completed or while it is saving, or an older load.
                    if (startGen != acceptGeneration || thisLoad != newestLoad) return@fold
                    if (latest is CreatorTermsUiState.Required && latest.saving) return@fold
                    val explained = unexplained
                    unexplained = emptySet()
                    mutableState.value = if (access.termsAccepted == false) {
                        mutableRefused.value += explained
                        val gate = (latest as? CreatorTermsUiState.Required) ?: CreatorTermsUiState.Required()
                        gate.copy(uploadRefused = gate.uploadRefused || explained.isNotEmpty())
                    } else {
                        CreatorTermsUiState.Accepted
                    }
                },
                onFailure = {
                    if (startGen == acceptGeneration && thisLoad == newestLoad && !keepVisible) {
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

    /**
     * [ids] are the uploads the server has refused. A newly refused one may be about the Terms, so the server is
     * asked; only its answer opens the gate. If it can't be asked now, the next resume asks again.
     */
    fun uploadsRefused(ids: Set<String>) {
        val new = ids - reported
        reported = ids
        // One that is retried or removed is no longer refused; if it fails again, it is new again.
        mutableRefused.value = mutableRefused.value intersect ids
        unexplained = (unexplained intersect ids) + new
        if (new.isNotEmpty()) refresh()
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
                        errorRes = R.string.studio_terms_error,
                        uploadRefused = current.uploadRefused
                    )
                }
            )
        }
    }
}
