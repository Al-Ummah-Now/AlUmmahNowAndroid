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

/** How long a picked video is, or null when it can't be read (not a video, gone, unreadable). */
fun interface DurationProbe {
    suspend fun durationMs(uri: String): Long?
}

data class EditorUiState(
    val list: EditDecisionList = EditDecisionList(),
    val selectedId: String? = null,
    val playheadMs: Long = 0,
    val importFailed: Boolean = false
) {
    val selectedIndex: Int get() = list.clips.indexOfFirst { it.id == selectedId }
}

/** The editor's state: the edit list, the selected clip and the playhead. Edits are Apple's (`EditDecisionList`). */
class EditorViewModel(private val probe: DurationProbe) : ViewModel() {
    private val mutable = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = mutable.asStateFlow()

    /** Adds picked videos at the end, in the order picked. One that can't be read is skipped and reported. */
    fun import(uris: List<String>) {
        viewModelScope.launch {
            var failed = false
            for (uri in uris) {
                val duration = runCatching { probe.durationMs(uri) }
                    .onFailure { AunLog.w("editor", "probe failed", "type" to it.javaClass.simpleName) }
                    .getOrNull()
                if (duration == null || duration < EditDecisionList.MIN_CLIP_MS) {
                    failed = true
                    continue
                }
                val clip = EditClip(sourceUri = uri, durationMs = duration)
                mutable.update { it.copy(list = it.list.append(clip), selectedId = it.selectedId ?: clip.id) }
            }
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

    fun move(id: String, by: Int) = mutable.update { it.copy(list = it.list.move(id, by)) }
}
