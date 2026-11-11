package net.alummahnow.feature.studio.content

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.BulkResult
import net.alummahnow.core.bridge.studio.ContentQuery
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioSuggestion
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.studioMessage

/** A one-off message for the snackbar. */
sealed interface ContentNotice {
    data class Deleted(val count: Int, val failed: Int) : ContentNotice
    data class Restored(val count: Int, val failed: Int) : ContentNotice
    data class Updated(val count: Int, val failed: Int) : ContentNotice
    data class Failed(@param:StringRes val message: Int) : ContentNotice
}

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
    /** Deletes are restorable for 30 days (soft delete deployed); otherwise they are permanent. */
    val softDelete: Boolean = true,
    val notice: ContentNotice? = null
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

/** Studio Content: status tabs, live search, multi-select bulk edit, delete and restore. */
@OptIn(FlowPreview::class)
class ContentViewModel(private val repository: StudioRepository, private val channelId: String) : ViewModel() {
    private val mutableState = MutableStateFlow(ContentUiState())
    val state: StateFlow<ContentUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var suggestJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            attempt("studio", "soft delete probe") { repository.softDeleteSupported(channelId) }
                .onSuccess { soft -> mutableState.update { it.copy(softDelete = soft) } }
        }
        // Search as you type: suggestions on every pause, the list a little later.
        viewModelScope.launch {
            mutableState.map { it.query }.distinctUntilChanged().drop(1).debounce(SEARCH_DEBOUNCE_MS).collect { load() }
        }
    }

    fun setStatus(status: ContentStatus) {
        if (status == mutableState.value.status) return
        mutableState.update { it.copy(status = status, selected = emptySet(), videos = emptyList()) }
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
        loadJob?.cancel()
        val s = mutableState.value
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            attempt("studio", "content") { repository.content(ContentQuery(channelId, s.status, s.query)) }.fold(
                onSuccess = { page ->
                    mutableState.update {
                        it.copy(
                            videos = page.items,
                            cursor = page.cursor,
                            loading = false,
                            selected = it.selected.intersect(
                                page.items.map { v ->
                                    v.id
                                }.toSet()
                            )
                        )
                    }
                },
                onFailure = { e -> mutableState.update { it.copy(loading = false, error = e.studioMessage()) } }
            )
        }
    }

    fun loadMore() {
        val s = mutableState.value
        val cursor = s.cursor ?: return
        if (s.loadingMore) return
        mutableState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            attempt("studio", "content more") { repository.content(ContentQuery(channelId, s.status, s.query, cursor = cursor)) }.fold(
                onSuccess = { page ->
                    mutableState.update {
                        it.copy(videos = (it.videos + page.items).distinctBy { v -> v.id }, cursor = page.cursor, loadingMore = false)
                    }
                },
                onFailure = { e -> mutableState.update { it.copy(loadingMore = false, notice = ContentNotice.Failed(e.studioMessage())) } }
            )
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
        act("restore", { repository.restore(it, channelId) }, ids) { r -> ContentNotice.Restored(r.succeeded, r.failed) }

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
                onFailure = { e -> mutableState.update { it.copy(busy = false, notice = ContentNotice.Failed(e.studioMessage())) } }
            )
        }
    }

    private fun act(
        what: String,
        call: suspend (List<String>) -> BulkResult,
        ids: Collection<String> = mutableState.value.selected,
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
                onFailure = { e -> mutableState.update { it.copy(busy = false, notice = ContentNotice.Failed(e.studioMessage())) } }
            )
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 400L
        const val SUGGEST_DEBOUNCE_MS = 150L
    }
}
