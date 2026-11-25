package net.alummahnow.feature.studio.content

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.BulkResult
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioSuggestion
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.hidesFeature
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

/** A one-off message for the snackbar. */
sealed interface ContentNotice {
    data class Deleted(val count: Int, val failed: Int) : ContentNotice
    data class Restored(val count: Int, val failed: Int) : ContentNotice
    data class Updated(val count: Int, val failed: Int) : ContentNotice
    data class Failed(@param:StringRes val message: Int) : ContentNotice

    /** What became of an appeal or a new check. */
    data class Review(@param:StringRes val message: Int) : ContentNotice
}

/** The appeal dialog for [videoId]. */
data class AppealState(val videoId: String, val sending: Boolean = false, val failed: Boolean = false)

data class ContentUiState(
    val status: ContentStatus = ContentStatus.PUBLISHED,
    val query: String = "",
    val suggestions: List<StudioSuggestion> = emptyList(),
    val videos: List<StudioVideo> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    @param:StringRes val error: Int? = null,
    val selected: Set<String> = emptySet(),
    val busy: Boolean = false,
    /**
     * Deletes are restorable for 30 days (soft delete deployed); otherwise they are permanent and "Recently deleted",
     * Restore and Delete forever are not there at all. False until the probe says otherwise (fail closed).
     */
    val softDelete: Boolean = false,
    val notice: ContentNotice? = null,
    /** How long a held video waits for a person now, when that is known. */
    val reviewQueueMinutes: Int? = null,
    val appeal: AppealState? = null,
    /** The review tabs that hold a video. Empty where review is off: its tabs are not there at all. */
    val reviewTabs: Set<ContentStatus> = emptySet()
) {
    val selecting: Boolean get() = selected.isNotEmpty()

    /**
     * The status tabs: "Recently deleted" only where soft delete is deployed, "In review" and "Not approved" only with
     * a video in them (an open one stays until it is left).
     */
    val tabs: List<ContentStatus>
        get() = ContentStatus.entries.filter {
            when (it) {
                ContentStatus.DELETED -> softDelete
                in REVIEW_TABS -> it in reviewTabs || it == status
                else -> true
            }
        }
}

private val REVIEW_TABS = setOf(ContentStatus.IN_REVIEW, ContentStatus.NOT_APPROVED)

/** Studio Content: status tabs, live search, multi-select bulk edit, delete and restore. */
@OptIn(FlowPreview::class)
class ContentViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(ContentUiState())
    val state: StateFlow<ContentUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var countsJob: Job? = null

    /** What the list shows now: more of it is asked for with the same words, whatever was typed since. */
    private var listed = ContentQuery(channelId, ContentStatus.PUBLISHED)
    private var suggestJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            attempt("studio", "soft delete probe") { repository.softDeleteSupported(channelId) }
                .onSuccess { soft -> mutableState.update { it.copy(softDelete = soft) } }
        }
        // Search as you type: suggestions on every pause, the list a little later.
        viewModelScope.launch {
            mutableState.map { it.query }.distinctUntilChanged().drop(1).debounce(SEARCH_DEBOUNCE_MS).collect { loadList() }
        }
    }

    fun setStatus(status: ContentStatus) {
        if (status == mutableState.value.status || status !in mutableState.value.tabs) return
        mutableState.update {
            it.copy(status = status, selected = emptySet(), videos = emptyList(), cursor = null, reviewQueueMinutes = null)
        }
        load()
    }

    fun setQuery(text: String) {
        mutableState.update { it.copy(query = text) }
        suggestJob?.cancel()
        if (text.isBlank()) {
            mutableState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGEST_DEBOUNCE_MS)
            attempt("studio", "suggestions") { repository.suggestions(channelId, text) }
                .onSuccess { s -> mutableState.update { if (it.query == text) it.copy(suggestions = s) else it } }
        }
    }

    /** Picking a suggestion searches for its text. */
    fun pickSuggestion(s: StudioSuggestion) {
        suggestJob?.cancel()
        mutableState.update { it.copy(query = s.text, suggestions = emptyList()) }
    }

    fun clearSuggestions() = mutableState.update { it.copy(suggestions = emptyList()) }

    fun load() {
        loadList()
        loadReviewTabs()
    }

    /** Which review tabs there are now. When that can't be known they stay as they were. */
    private fun loadReviewTabs() {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            attempt("studio", "review tabs") { repository.counts(channelId) }.onSuccess { c ->
                val there = setOfNotNull(
                    ContentStatus.IN_REVIEW.takeIf { (c?.inReview ?: 0) > 0 },
                    ContentStatus.NOT_APPROVED.takeIf { (c?.notApproved ?: 0) > 0 }
                )
                mutableState.update { it.copy(reviewTabs = there) }
            }
        }
    }

    private fun loadList() {
        loadJob?.cancel()
        moreJob?.cancel() // its page belongs to the list this one replaces
        val s = mutableState.value
        val query = ContentQuery(channelId, s.status, s.query)
        listed = query
        mutableState.update { it.copy(loading = true, error = null) }
        val current = viewModelScope.launch {
            attempt("studio", "content") { repository.content(query) }.fold(
                onSuccess = { page ->
                    mutableState.update {
                        it.copy(
                            videos = page.items,
                            cursor = page.cursor,
                            reviewQueueMinutes = page.reviewQueueMinutes,
                            loading = false,
                            selected = it.selected.intersect(
                                page.items.map { v ->
                                    v.id
                                }.toSet()
                            )
                        )
                    }
                },
                onFailure = { e ->
                    if (e.hidesFeature() && s.status == ContentStatus.DELETED) {
                        softDeleteGone()
                    } else {
                        mutableState.update { it.copy(loading = false, error = e.studioMessage()) }
                    }
                }
            )
        }
        loadJob = current
        // A load cancelled without a newer one in its place must not leave the spinner up.
        current.invokeOnCompletion { cause ->
            if (cause is CancellationException && loadJob === current && mutableState.value.loading) {
                mutableState.update { it.copy(loading = false, error = net.alummahnow.design.R.string.aun_error_generic) }
            }
        }
    }

    /**
     * The server answered a soft-delete route with "not here" (404 / `feature_off`): drop "Recently deleted",
     * Restore and Delete forever without a word, and go back to Published if that tab was open.
     */
    private fun softDeleteGone() {
        val wasDeleted = mutableState.value.status == ContentStatus.DELETED
        mutableState.update {
            it.copy(
                softDelete = false,
                busy = false,
                loading = false,
                notice = null,
                status = if (wasDeleted) ContentStatus.PUBLISHED else it.status,
                selected = if (wasDeleted) emptySet() else it.selected,
                videos = if (wasDeleted) emptyList() else it.videos
            )
        }
        if (wasDeleted) load()
    }

    /** A failed action: hidden features vanish quietly, anything else is said in the snackbar. */
    private fun actionFailed(e: Throwable) {
        if (e.hidesFeature()) {
            mutableState.update { it.copy(busy = false, notice = null) }
        } else {
            mutableState.update { it.copy(busy = false, notice = ContentNotice.Failed(e.studioMessage())) }
        }
    }

    fun loadMore() {
        val s = mutableState.value
        val cursor = s.cursor ?: return
        if (s.loadingMore || s.loading) return
        val query = listed.copy(cursor = cursor)
        mutableState.update { it.copy(loadingMore = true) }
        val current = viewModelScope.launch {
            attempt("studio", "content more") { repository.content(query) }.fold(
                onSuccess = { page ->
                    mutableState.update {
                        it.copy(
                            videos = (it.videos + page.items).distinctBy { v -> v.id },
                            cursor = page.cursor,
                            // Only a page with a held video says how long the wait is.
                            reviewQueueMinutes = page.reviewQueueMinutes ?: it.reviewQueueMinutes,
                            loadingMore = false
                        )
                    }
                },
                onFailure = { e ->
                    if (e.hidesFeature() && query.status == ContentStatus.DELETED) {
                        softDeleteGone()
                    } else {
                        mutableState.update { it.copy(loadingMore = false, notice = ContentNotice.Failed(e.studioMessage())) }
                    }
                }
            )
        }
        moreJob = current
        current.invokeOnCompletion { cause ->
            if (cause is CancellationException && moreJob === current) mutableState.update { it.copy(loadingMore = false) }
        }
    }

    fun toggle(id: String) = mutableState.update { s ->
        val next = if (id in s.selected) s.selected - id else s.selected + id
        if (next.size >
            BulkChanges.MAX_BULK
        ) {
            s.copy(notice = ContentNotice.Failed(net.alummahnow.feature.studio.R.string.studio_error_invalid))
        } else {
            s.copy(selected = next)
        }
    }

    fun selectAll() = mutableState.update { s -> s.copy(selected = s.videos.take(BulkChanges.MAX_BULK).map { it.id }.toSet()) }

    fun clearSelection() = mutableState.update { it.copy(selected = emptySet()) }

    fun consumeNotice() = mutableState.update { it.copy(notice = null) }

    fun bulkEdit(changes: BulkChanges) = act("bulk edit", { ids ->
        repository.bulkUpdate(ids, changes)
    }) { r -> ContentNotice.Updated(r.succeeded, r.failed) }

    fun delete(ids: Collection<String> = mutableState.value.selected) =
        act("delete", { repository.delete(it, channelId) }, ids) { r -> ContentNotice.Deleted(r.succeeded, r.failed) }

    fun restore(ids: Collection<String> = mutableState.value.selected) =
        act("restore", { repository.restore(it, channelId) }, ids, softDeleteRoute = true) { r ->
            ContentNotice.Restored(r.succeeded, r.failed)
        }

    /** Asks for a new check of a video that was turned down. */
    fun resubmit(id: String) {
        if (!canReview(id)) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            attempt("studio", "review resubmit") { repository.resubmitReview(id) }.fold(
                onSuccess = { sentToReview(id, R.string.studio_review_resubmitted) },
                onFailure = { e ->
                    val text = if (e.reviewMoved()) R.string.studio_review_changed else R.string.studio_review_resubmit_failed
                    mutableState.update { it.copy(busy = false, notice = ContentNotice.Review(text)) }
                }
            )
            load()
        }
    }

    fun openAppeal(id: String) {
        if (canReview(id)) mutableState.update { it.copy(appeal = AppealState(id)) }
    }

    fun closeAppeal() = mutableState.update { if (it.appeal?.sending == true) it else it.copy(appeal = null) }

    /** Sends the appeal; the dialog (and what was typed) stays when that fails, unless it can't be appealed any more. */
    fun sendAppeal(message: String) {
        val appeal = mutableState.value.appeal ?: return
        if (appeal.sending || mutableState.value.busy || message.isBlank()) return
        mutableState.update { it.copy(busy = true, appeal = appeal.copy(sending = true, failed = false)) }
        viewModelScope.launch {
            attempt("studio", "review appeal") { repository.appealReview(appeal.videoId, message.trim()) }.fold(
                onSuccess = { sentToReview(appeal.videoId, R.string.studio_appeal_sent) },
                onFailure = { e ->
                    mutableState.update {
                        if (e.reviewMoved()) {
                            it.copy(busy = false, appeal = null, notice = ContentNotice.Review(R.string.studio_review_changed))
                        } else {
                            it.copy(busy = false, appeal = it.appeal?.copy(sending = false, failed = true))
                        }
                    }
                }
            )
            load()
        }
    }

    /** One at a time, and only for a video that is still in the list. */
    private fun canReview(id: String) = with(mutableState.value) { !busy && videos.any { it.id == id } }

    /** It left this tab: the row goes now, so nothing more can be sent for it while (or if never) the list reloads. */
    private fun sentToReview(id: String, @StringRes text: Int) = mutableState.update {
        it.copy(
            busy = false,
            appeal = null,
            videos = it.videos.filterNot { v -> v.id == id },
            selected = it.selected - id,
            reviewTabs = it.reviewTabs + ContentStatus.IN_REVIEW,
            notice = ContentNotice.Review(text)
        )
    }

    /** The server's 409: the video is not turned down any more, or was appealed, on another device or by a reviewer. */
    private fun Throwable.reviewMoved() = this is CoreError.Http && code == CONFLICT

    fun deleteForever(id: String) {
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            attempt("studio", "delete forever") { repository.deletePermanently(id, channelId) }.fold(
                onSuccess = {
                    mutableState.update {
                        it.copy(
                            busy = false,
                            videos = it.videos.filterNot { v -> v.id == id },
                            selected =
                            it.selected - id,
                            notice = ContentNotice.Deleted(1, 0)
                        )
                    }
                },
                onFailure = { e -> if (e.hidesFeature()) softDeleteGone() else actionFailed(e) }
            )
        }
    }

    private fun act(
        what: String,
        call: suspend (List<String>) -> BulkResult,
        ids: Collection<String> = mutableState.value.selected,
        softDeleteRoute: Boolean = false,
        notice: (BulkResult) -> ContentNotice
    ) {
        val list = ids.toList().take(BulkChanges.MAX_BULK)
        if (list.isEmpty()) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            attempt("studio", what) { call(list) }.fold(
                onSuccess = { r ->
                    // Keep the ones that failed selected so the creator can retry them.
                    mutableState.update { it.copy(busy = false, selected = r.failedIds.toSet(), notice = notice(r)) }
                    load()
                },
                onFailure = { e -> if (softDeleteRoute && e.hidesFeature()) softDeleteGone() else actionFailed(e) }
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val SUGGEST_DEBOUNCE_MS = 150L
        const val CONFLICT = 409
    }
}
