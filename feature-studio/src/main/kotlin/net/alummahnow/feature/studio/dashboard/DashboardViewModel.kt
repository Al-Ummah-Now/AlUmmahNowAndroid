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
import net.alummahnow.core.bridge.studio.CommentModeration
import net.alummahnow.core.bridge.studio.CommentReportReason
import net.alummahnow.core.bridge.studio.CommentsSummary
import net.alummahnow.core.bridge.studio.ContentCounts
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.CreatorAccess
import net.alummahnow.core.bridge.studio.HeldComment
import net.alummahnow.core.bridge.studio.StudioError
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.hidesFeature
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

/** One Dashboard section: not there for this person, loaded (possibly empty), or failed to load. */
sealed interface DashboardSection<out T> {
    data object Hidden : DashboardSection<Nothing>
    data class Ready<T>(val value: T) : DashboardSection<T>
    data class Failed(@param:StringRes val message: Int) : DashboardSection<Nothing>
}

sealed interface HeldCommentAction {
    data object Approve : HeldCommentAction
    data object Remove : HeldCommentAction
    data object HideAuthor : HeldCommentAction
    data class Report(val reason: CommentReportReason, val details: String?) : HeldCommentAction
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
        val scheduled: DashboardSection<List<StudioVideo>> = DashboardSection.Hidden,
        val pendingComments: Set<String> = emptySet(),
        val loadingMoreComments: Boolean = false,
        @param:StringRes val commentMessage: Int? = null,
        val commentError: Boolean = false
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

    private val hiddenAuthors = mutableSetOf<String>()
    private val hiddenCommentIds = mutableSetOf<String>()

    init {
        load()
    }

    fun actOnComment(commentId: String, action: HeldCommentAction) {
        val state = commentActionState() ?: return
        val summary = (state.comments as? DashboardSection.Ready)?.value ?: return
        val comment = summary.items.firstOrNull { it.id == commentId } ?: return
        val ids = affectedCommentIds(summary, comment, action)
        if (ids.isEmpty() || ids.any { it in state.pendingComments }) return
        mutableState.value = state.copy(pendingComments = state.pendingComments + ids, commentMessage = null)
        viewModelScope.launch {
            try {
                val outcome = attempt("studio", "held comment action") {
                    submitCommentAction(comment, action)
                }
                applyCommentOutcome(action, ids, outcome)
            } finally {
                val current = mutableState.value as? DashboardUiState.Content
                if (current != null) mutableState.value = current.copy(pendingComments = current.pendingComments - ids)
            }
        }
    }

    private fun applyCommentOutcome(action: HeldCommentAction, ids: Set<String>, outcome: Result<Int>) {
        val current = mutableState.value as? DashboardUiState.Content ?: return
        val summary = (current.comments as? DashboardSection.Ready)?.value ?: return
        val failure = outcome.exceptionOrNull()
        if (failure is StudioError.FeatureOff) {
            mutableState.value = current.copy(comments = DashboardSection.Hidden, commentMessage = null, commentError = false)
            return
        }
        val updated = if (failure == null && action !is HeldCommentAction.Report) {
            if (action == HeldCommentAction.HideAuthor) hiddenCommentIds += ids
            val remaining = summary.items.filterNot { it.id in ids }
            summary.copy(items = remaining, held = summary.held - (summary.items.size - remaining.size))
        } else {
            summary
        }
        mutableState.value = current.copy(
            comments = DashboardSection.Ready(updated),
            commentMessage = failure?.studioMessage() ?: outcome.getOrNull(),
            commentError = failure != null
        )
    }

    private fun commentActionState(): DashboardUiState.Content? =
        (mutableState.value as? DashboardUiState.Content)?.takeUnless { it.loadingMoreComments }

    private fun affectedCommentIds(summary: CommentsSummary, comment: HeldComment, action: HeldCommentAction): Set<String> =
        when (action) {
            HeldCommentAction.Approve, HeldCommentAction.Remove -> if (comment.canModerate) setOf(comment.id) else emptySet()
            HeldCommentAction.HideAuthor -> if (comment.authorId == null) {
                emptySet()
            } else {
                summary.items.filter { it.authorId == comment.authorId }.map(HeldComment::id).toSet()
            }
            is HeldCommentAction.Report -> setOf(comment.id)
        }

    private suspend fun submitCommentAction(comment: HeldComment, action: HeldCommentAction): Int =
        when (action) {
            HeldCommentAction.Approve -> {
                repository.moderateComment(channelId, comment, CommentModeration.APPROVE)
                R.string.studio_held_approved
            }
            HeldCommentAction.Remove -> {
                repository.moderateComment(channelId, comment, CommentModeration.REMOVE)
                R.string.studio_held_removed
            }
            HeldCommentAction.HideAuthor -> {
                val authorId = requireNotNull(comment.authorId)
                repository.hideCommentAuthor(channelId, authorId)
                hiddenAuthors += authorId
                R.string.studio_held_hidden
            }
            is HeldCommentAction.Report -> {
                repository.reportComment(comment.id, action.reason, action.details)
                R.string.studio_held_reported
            }
        }

    fun loadMoreComments() {
        val state = mutableState.value as? DashboardUiState.Content ?: return
        val summary = (state.comments as? DashboardSection.Ready)?.value ?: return
        val cursor = summary.nextCursor ?: return
        if (state.loadingMoreComments || state.pendingComments.isNotEmpty()) return
        mutableState.value = state.copy(loadingMoreComments = true, commentMessage = null)
        viewModelScope.launch {
            try {
                mutableState.value = attempt("studio", "more held comments") {
                    repository.commentsSummary(channelId, cursor)
                }.fold(
                    onSuccess = { page ->
                        state.copy(comments = DashboardSection.Ready(mergeHeldPage(summary.items, page)))
                    },
                    onFailure = { state.copy(commentMessage = it.studioMessage(), commentError = true) }
                )
            } finally {
                val current = mutableState.value as? DashboardUiState.Content
                if (current?.loadingMoreComments == true) mutableState.value = current.copy(loadingMoreComments = false)
            }
        }
    }

    private fun mergeHeldPage(previous: List<HeldComment>, page: CommentsSummary): CommentsSummary {
        val items = visibleHeldComments((previous + page.items).distinctBy(HeldComment::id))
        val held = if (page.nextCursor == null) items.size else (page.held - hiddenCommentIds.size).coerceAtLeast(items.size)
        return page.copy(items = items, held = held)
    }

    private fun visibleHeldComments(items: List<HeldComment>): List<HeldComment> = items.filter { comment ->
        val hidden = comment.authorId in hiddenAuthors
        if (hidden) hiddenCommentIds += comment.id
        !hidden
    }

    private fun commentRequestInFlight(): Boolean {
        val state = mutableState.value as? DashboardUiState.Content ?: return false
        return state.loadingMoreComments || state.pendingComments.isNotEmpty()
    }

    fun load() {
        if (commentRequestInFlight()) return
        job?.cancel()
        mutableState.value = DashboardUiState.Loading
        val current = viewModelScope.launch {
            val access = async { attempt("studio", "creator access") { repository.creatorAccess() }.getOrNull() }
            val counts = async { section("counts") { repository.counts(channelId) } }
            val processing = async { attempt("studio", "processing") { list(ContentStatus.PROCESSING) } }
            val published = async { attempt("studio", "published") { list(ContentStatus.PUBLISHED) } }
            val recent = async {
                section("recent") {
                    // A video that waits for review, or was turned down, is a recent upload too.
                    val reviewed = (counts.await() as? DashboardSection.Ready)?.value
                    val held = if ((reviewed?.inReview ?: 0) > 0) list(ContentStatus.IN_REVIEW) else emptyList()
                    val rejected = if ((reviewed?.notApproved ?: 0) > 0) list(ContentStatus.NOT_APPROVED) else emptyList()
                    (processing.await().getOrThrow() + held + rejected + published.await().getOrThrow())
                        .sortedByDescending { it.createdAt.orEmpty() }
                        .take(SECTION_SIZE)
                }
            }
            val comments = async {
                section("held comments") {
                    val summary = repository.commentsSummary(channelId)
                    hiddenCommentIds.clear()
                    mergeHeldPage(emptyList(), summary)
                }
            }
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
