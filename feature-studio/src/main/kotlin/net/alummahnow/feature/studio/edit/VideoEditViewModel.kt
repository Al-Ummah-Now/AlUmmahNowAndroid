package net.alummahnow.feature.studio.edit

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.feature.studio.R
import net.alummahnow.feature.studio.attempt
import net.alummahnow.feature.studio.content.tags
import net.alummahnow.feature.studio.studioMessage

data class EditForm(
    val title: String = "",
    val description: String = "",
    val isPublic: Boolean = true,
    val category: String? = null,
    val language: String? = null,
    val tagsText: String = ""
) {
    companion object {
        fun of(v: StudioVideo) = EditForm(v.title, v.description, v.isPublic, v.category, v.language, v.tags.joinToString(", "))
    }
}

data class VideoEditUiState(
    val loading: Boolean = true,
    val original: EditForm? = null,
    val form: EditForm = EditForm(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    @param:StringRes val error: Int? = null
) {
    val dirty: Boolean get() = original != null && original != form
}

class VideoEditViewModel(
    private val repository: StudioRepository,
    private val channelId: String,
    private val videoId: String
) : ViewModel() {
    private val mutableState = MutableStateFlow(VideoEditUiState())
    val state: StateFlow<VideoEditUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            attempt("studio", "video") { repository.video(videoId) }.fold(
                onSuccess = { v ->
                    mutableState.update { VideoEditUiState(loading = false, original = EditForm.of(v), form = EditForm.of(v)) }
                },
                onFailure = { e -> mutableState.update { it.copy(loading = false, error = e.studioMessage()) } }
            )
        }
    }

    fun update(transform: (EditForm) -> EditForm) = mutableState.update { it.copy(form = transform(it.form), saved = false, error = null) }

    fun save() {
        val s = mutableState.value
        val o = s.original ?: return
        val f = s.form
        if (f.title.isBlank()) {
            mutableState.update { it.copy(error = R.string.studio_title_required) }
            return
        }
        // Only what changed is sent.
        val edit = VideoEdit(
            title = f.title.trim().takeIf { it != o.title },
            description = f.description.takeIf { it != o.description },
            isPublic = f.isPublic.takeIf { it != o.isPublic },
            category = if (f.category != o.category) f.category ?: VideoEdit.CLEAR else null,
            language = f.language?.takeIf { it != o.language },
            tags = tags(f.tagsText).takeIf { it != tags(o.tagsText) }
        )
        mutableState.update { it.copy(saving = true) }
        viewModelScope.launch {
            attempt("studio", "save video") { repository.update(videoId, channelId, edit) }.fold(
                onSuccess = { v ->
                    mutableState.update {
                        VideoEditUiState(loading = false, original = EditForm.of(v), form = EditForm.of(v), saved = true)
                    }
                },
                onFailure = { e -> mutableState.update { it.copy(saving = false, error = e.studioMessage()) } }
            )
        }
    }
}
