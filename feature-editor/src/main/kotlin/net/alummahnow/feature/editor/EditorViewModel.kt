package net.alummahnow.feature.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.media.EditClip
import net.alummahnow.core.bridge.media.EditDecisionList
import net.alummahnow.core.bridge.media.SnapshotHistory

/** How long a picked video is, or null when it can't be read (not a video, gone, unreadable). */
fun interface DurationProbe {
    suspend fun durationMs(uri: String): Long?
}

data class EditorUiState(
    val list: EditDecisionList = EditDecisionList(),
    val selectedId: String? = null,
    val playheadMs: Long = 0,
    val importFailed: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
) {
    val selectedIndex: Int get() = list.clips.indexOfFirst { it.id == selectedId }
}

/**
 * The editor's state: the edit list (with undo and redo over whole snapshots), the selected clip and the playhead.
 * Edits are Apple's (`EditDecisionList`); every edit is one undo step, a trim drag included.
 */
class EditorViewModel(private val probe: DurationProbe) : ViewModel() {
    private val mutable = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutable.asStateFlow()
    private var history = SnapshotHistory(EditDecisionList())

    /** The list when the current trim drag began; null when no drag is under way. */
    private var trimBase: EditDecisionList? = null

    /** Makes `next` current (a new undo step, or the same step while amending) and keeps selection and playhead valid. */
    private fun commit(next: EditDecisionList, amend: Boolean = false, select: String? = null) {
        history = if (amend) history.amend(next) else history.record(next)
        publish(select)
    }

    private fun publish(select: String? = null) {
        val list = history.current
        mutable.update { s ->
            val keep = (select ?: s.selectedId)?.takeIf { id -> list.clips.any { it.id == id } }
            s.copy(
                list = list,
                selectedId = keep ?: list.clips.getOrNull(s.selectedIndex.coerceAtMost(list.clips.lastIndex))?.id,
                playheadMs = s.playheadMs.coerceIn(0, (list.durationMs - 1).coerceAtLeast(0)),
                canUndo = history.canUndo,
                canRedo = history.canRedo
            )
        }
    }

    /** Adds picked videos at the end, in the order picked. One that can't be read is skipped and reported. */
    fun import(uris: List<String>) {
        viewModelScope.launch {
            var failed = false
            var list = history.current
            for (uri in uris) {
                val duration = runCatching { probe.durationMs(uri) }
                    .onFailure { AunLog.w("editor", "probe failed", "type" to it.javaClass.simpleName) }
                    .getOrNull()
                if (duration == null || duration < EditDecisionList.MIN_CLIP_MS) {
                    failed = true
                    continue
                }
                list = list.append(EditClip(sourceUri = uri, durationMs = duration))
            }
            // One import is one undo step.
            commit(list, select = state.value.selectedId ?: list.clips.firstOrNull()?.id)
            mutable.update { it.copy(importFailed = failed) }
        }
    }

    fun dismissImportError() = mutable.update { it.copy(importFailed = false) }

    fun select(id: String?) = mutable.update { it.copy(selectedId = id) }

    /** Moves the playhead (clamped to the timeline) and selects the clip under it. */
    fun scrub(timeMs: Long) = mutable.update { s ->
        val t = timeMs.coerceIn(0, (s.list.durationMs - 1).coerceAtLeast(0))
        val index = s.list.locate(t)?.first
        s.copy(playheadMs = t, selectedId = index?.let { s.list.clips[it].id } ?: s.selectedId)
    }

    /** The player reports where it is while playing; the selection follows without an edit. */
    fun playing(timeMs: Long) = mutable.update { it.copy(playheadMs = timeMs.coerceAtLeast(0)) }

    fun move(id: String, by: Int) = commit(history.current.move(id, by))

    /** Splits the clip under the playhead there (ignored too close to a clip's ends). */
    fun split() = commit(history.current.split(state.value.playheadMs))

    /** Removes the selected clip and closes the gap; the clip that takes its place is selected. */
    fun deleteSelected() {
        val id = state.value.selectedId ?: return
        commit(history.current.rippleDelete(id))
    }

    /**
     * A trim drag on clip `id`: `startMs`..`endMs` measured within the clip as it was when the drag began. The whole
     * drag is one undo step; `done` ends it.
     */
    fun trim(id: String, startMs: Long, endMs: Long, done: Boolean) {
        val base = trimBase ?: history.current.also { trimBase = it }
        val dragging = base !== history.current
        commit(base.trim(id, startMs, endMs), amend = dragging, select = id)
        if (done) trimBase = null
    }

    fun undo() {
        history = history.undo()
        publish()
    }

    fun redo() {
        history = history.redo()
        publish()
    }
}
