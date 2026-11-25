package net.alummahnow.feature.studio.dashboard

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.hidesFeature
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

/** One Dashboard section: not there for this person, loaded (possibly empty), or failed to load. */
sealed interface DashboardSection<out T> {
    data object Hidden : DashboardSection<Nothing>
    data class Ready<T>(val value: T) : DashboardSection<T>
    data class Failed(@param:StringRes val message: Int) : DashboardSection<Nothing>
}

sealed interface DashboardUiState {
    data object Loading : DashboardUiState

    /**
     * What the Dashboard shows. Every section hides itself when the server says it is not there; a section with no
     * data shows a plain empty state, so the page is never a void.
     */
    data class Content(
        val access: CreatorAccess?,
        val counts: ContentCounts?,
        val recent: DashboardSection<List<StudioVideo>> = DashboardSection.Hidden,
        val comments: DashboardSection<CommentsSummary> = DashboardSection.Hidden,
        val drafts: DashboardSection<List<StudioVideo>> = DashboardSection.Hidden,
        val scheduled: DashboardSection<List<StudioVideo>> = DashboardSection.Hidden
    ) : DashboardUiState {
        /** Nothing is left once every section is hidden: Studio drops the Dashboard and opens Content first. */
        val nothingLeft: Boolean
            get() = access?.canUpload != true &&
                counts == null &&
                listOf(recent, comments, drafts, scheduled).all { it == DashboardSection.Hidden }
    }

    data class Error(@param:StringRes val message: Int) : DashboardUiState
}

/**
 * The channel Dashboard: upload status, counts, recent videos with their status, comments waiting for review,
 * drafts and scheduled videos. Every load ends in content, a hidden section or an error, never an endless spinner:
 * a "not here" answer (404, `feature_off`, 402, 403) hides its section, any other failure says so with a retry, and
 * a cancelled load that nothing replaced ends in an error with a retry.
 */
class DashboardViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val state: StateFlow<DashboardUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun load() {
        job?.cancel()
        mutableState.value = DashboardUiState.Loading
        val current = viewModelScope.launch {
            val access = async { attempt("studio", "creator access") { repository.creatorAccess() }.getOrNull() }
            val counts = async { section("counts") { repository.counts(channelId) } }
            val recent = async {
                section("recent") {
                    // A video that waits for review, or was turned down, is a recent upload too.
                    val reviewed = (counts.await() as? DashboardSection.Ready)?.value
                    val held = if ((reviewed?.inReview ?: 0) > 0) list(ContentStatus.IN_REVIEW) else emptyList()
                    val rejected = if ((reviewed?.notApproved ?: 0) > 0) list(ContentStatus.NOT_APPROVED) else emptyList()
                    (list(ContentStatus.PROCESSING) + held + rejected + list(ContentStatus.PUBLISHED))
                        .sortedByDescending { it.createdAt.orEmpty() }
                        .take(SECTION_SIZE)
                }
            }
            val comments = async { section("comments summary") { repository.commentsSummary(channelId) } }
            val drafts = async { section("drafts") { list(ContentStatus.DRAFTS) } }
            val scheduled = async { section("scheduled") { list(ContentStatus.SCHEDULED) } }
            val countsSection = counts.await().let { if (it is DashboardSection.Ready && it.value == null) DashboardSection.Hidden else it }
            val sections = listOf(countsSection, recent.await(), comments.await(), drafts.await(), scheduled.await())
            val failures = sections.filterIsInstance<DashboardSection.Failed>()
            mutableState.value = if (failures.size == sections.size) {
                DashboardUiState.Error(failures.first().message)
            } else {
                @Suppress("UNCHECKED_CAST")
                DashboardUiState.Content(
                    access = access.await(),
                    counts = (countsSection as? DashboardSection.Ready<ContentCounts?>)?.value,
                    recent = sections[1] as DashboardSection<List<StudioVideo>>,
                    comments = sections[2] as DashboardSection<CommentsSummary>,
                    drafts = sections[3] as DashboardSection<List<StudioVideo>>,
                    scheduled = sections[4] as DashboardSection<List<StudioVideo>>
                )
            }
        }
        job = current
        // A load cancelled without a newer one in its place (a call that timed out by cancellation, for example) must
        // not leave the spinner up.
        current.invokeOnCompletion { cause ->
            if (cause is CancellationException && job === current && mutableState.value == DashboardUiState.Loading) {
                mutableState.value = DashboardUiState.Error(net.alummahnow.design.R.string.aun_error_generic)
            }
        }
    }

    private suspend fun list(status: ContentStatus): List<StudioVideo> =
        repository.content(ContentQuery(channelId, status, limit = SECTION_SIZE)).items

    private suspend fun <T> section(what: String, block: suspend () -> T): DashboardSection<T> =
        attempt("studio", what) { block() }.fold(
            onSuccess = { DashboardSection.Ready(it) },
            onFailure = { e -> if (e.isNotHere()) DashboardSection.Hidden else DashboardSection.Failed(e.studioMessage()) }
        )

    private companion object {
        const val SECTION_SIZE = 5
    }
}

/** "This section is not there for you": off, not deployed, outside the plan, or not your role on this channel. */
private fun Throwable.isNotHere(): Boolean = hidesFeature() || this is StudioError.Forbidden || this is CoreError.NotFound
