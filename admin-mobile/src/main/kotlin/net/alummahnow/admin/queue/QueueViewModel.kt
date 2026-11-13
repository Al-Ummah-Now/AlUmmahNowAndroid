package net.alummahnow.admin.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.CoreError
import net.alummahnow.core.bridge.admin.ReviewError
import net.alummahnow.core.bridge.admin.ReviewItem
import net.alummahnow.core.bridge.admin.ReviewService
import net.alummahnow.core.bridge.admin.ReviewTab

data class QueueUiState(
    val tab: ReviewTab = ReviewTab.ACTION_NEEDED,
    val query: String = "",
    val items: List<ReviewItem> = emptyList(),
    val loading: Boolean = true,
    /** Offline: the list (text only, in memory) is as of [lastUpdated]. */
    val offline: Boolean = false,
    val failed: Boolean = false,
    val lastUpdated: Instant? = null,
    val nextCursor: String? = null
)

/**
 * The review queue: Action needed (SLA deadline, then risk) and History (newest first), with a live search that opens
 * with results. Nothing is kept on disk; offline shows the last list held in memory.
 */
class QueueViewModel(
    private val review: ReviewService,
    private val isSenior: Boolean,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val mutableState = MutableStateFlow(QueueUiState())
    val state: StateFlow<QueueUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    init {
        load()
    }

    fun setTab(tab: ReviewTab) {
        if (tab == mutableState.value.tab) return
        mutableState.update { it.copy(tab = tab, items = emptyList(), loading = true, nextCursor = null) }
        load()
    }

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
        load(debounceMs = SEARCH_DEBOUNCE_MS)
    }

    fun refresh() = load()

    fun loadMore() {
        val cursor = mutableState.value.nextCursor ?: return
        load(cursor = cursor)
    }

    private fun load(debounceMs: Long = 0, cursor: String? = null) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val s = mutableState.value
            mutableState.update { it.copy(loading = true) }
            try {
                val page = review.queue(s.tab, s.query, cursor)
                val items = sort(s.tab, page.items.filter { isSenior || !it.minor })
                mutableState.update {
                    it.copy(
                        items = if (cursor == null) items else it.items + items,
                        loading = false,
                        offline = false,
                        failed = false,
                        lastUpdated = now(),
                        nextCursor = page.nextCursor
                    )
                }
            } catch (_: ReviewError.Offline) {
                mutableState.update { it.copy(loading = false, offline = true) }
            } catch (e: CoreError) {
                AunLog.e("queue", "load failed", e)
                mutableState.update { it.copy(loading = false, offline = e is CoreError.Network, failed = e !is CoreError.Network) }
            } catch (e: ReviewError) {
                AunLog.e("queue", "load refused", e)
                mutableState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** Applies a decision (or its undo) to the list at once, before the next load. */
    fun replace(item: ReviewItem) {
        mutableState.update { s ->
            val belongs = if (s.tab == ReviewTab.ACTION_NEEDED) item.isOpen else !item.isOpen
            val rest = s.items.filterNot { it.id == item.id }
            s.copy(items = if (belongs) sort(s.tab, rest + item) else rest)
        }
    }

    companion object {
        const val SEARCH_DEBOUNCE_MS = 250L

        /** Escalations first, then the SLA deadline, then risk (highest first): as admin-web. */
        fun sort(tab: ReviewTab, items: List<ReviewItem>): List<ReviewItem> = when (tab) {
            ReviewTab.ACTION_NEEDED -> items.sortedWith(
                compareByDescending<ReviewItem> { it.escalate }
                    .thenBy { it.slaDueAt ?: Instant.MAX }
                    .thenByDescending { it.risk ?: 0.0 }
            )
            ReviewTab.HISTORY -> items.sortedByDescending { it.decidedAt ?: Instant.MIN }
        }
    }
}
