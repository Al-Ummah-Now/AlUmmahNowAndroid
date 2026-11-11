package net.alummahnow.feature.upload

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.alummahnow.core.bridge.AunLog
import net.alummahnow.core.bridge.studio.StudioPlaylist
import net.alummahnow.core.bridge.studio.StudioRepository
import net.alummahnow.core.bridge.studio.UploadDraft
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.UploadQueue
import net.alummahnow.core.bridge.studio.Visibility

data class PickedFile(val uri: String, val name: String, val sizeBytes: Long, val mimeType: String?, val titleOverride: String? = null)

/** Values applied to every file in the batch ("set for all"). */
data class SetForAll(
    val titlePattern: String = TitlePattern.DEFAULT,
    val description: String = "",
    val category: String? = null,
    val language: String? = null,
    val visibility: Visibility = Visibility.PUBLIC,
    val playlistIds: Set<String> = emptySet(),
    val tagsText: String = "",
    /** Publish at this time (the video uploads private until then). */
    val scheduleAt: ZonedDateTime? = null
)

data class UploadUiState(
    val files: List<PickedFile> = emptyList(),
    val form: SetForAll = SetForAll(),
    val playlists: List<StudioPlaylist> = emptyList(),
    val queue: List<UploadItem> = emptyList(),
    val submitting: Boolean = false,
    @param:StringRes val error: Int? = null,
    /** Shown once after a batch was queued. */
    val queuedCount: Int = 0
)

class UploadViewModel(
    private val queue: UploadQueue,
    private val repository: StudioRepository,
    private val channelId: String,
    private val channelName: String,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Instant = { Instant.now() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) : ViewModel() {
    private val mutableState = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch { queue.items.collect { items -> mutableState.update { it.copy(queue = items) } } }
        viewModelScope.launch {
            runCatching { repository.playlists(channelId) }
                .onSuccess { p -> mutableState.update { it.copy(playlists = p) } }
                .onFailure { if (it is CancellationException) throw it else AunLog.w("upload", "playlists unavailable") }
        }
    }

    /** Titles as they will be sent: the override, else the pattern. */
    fun titles(s: UploadUiState = mutableState.value): List<String> = s.files.mapIndexed { i, f ->
        f.titleOverride?.takeIf { it.isNotBlank() }?.trim()
            ?: TitlePattern.apply(s.form.titlePattern, f.name, i, s.files.size, today(), channelName)
    }

    fun addFiles(files: List<PickedFile>) = mutableState.update { s ->
        val all = (s.files + files).distinctBy { it.uri }
        s.copy(files = all.take(MAX_FILES), error = if (all.size > MAX_FILES) R.string.upload_error_too_many else null)
    }

    fun removeFile(index: Int) = mutableState.update { s -> s.copy(files = s.files.filterIndexed { i, _ -> i != index }) }

    fun setTitle(index: Int, title: String) = mutableState.update { s ->
        s.copy(files = s.files.mapIndexed { i, f -> if (i == index) f.copy(titleOverride = title.take(TitlePattern.MAX_TITLE)) else f })
    }

    fun updateForm(transform: (SetForAll) -> SetForAll) = mutableState.update { it.copy(form = transform(it.form), error = null) }

    fun togglePlaylist(id: String) = updateForm { f -> f.copy(playlistIds = if (id in f.playlistIds) f.playlistIds - id else f.playlistIds + id) }

    fun consumeQueued() = mutableState.update { it.copy(queuedCount = 0) }

    /** Validates and hands the batch to the background queue. */
    fun start() {
        val s = mutableState.value
        val error = validate(s)
        if (error != null) {
            mutableState.update { it.copy(error = error) }
            return
        }
        val titles = titles(s)
        val tags = TitlePattern.tags(s.form.tagsText)
        val schedule = s.form.scheduleAt
        val drafts = s.files.mapIndexed { i, f ->
            UploadDraft(
                source = f.uri,
                fileName = f.name,
                sizeBytes = f.sizeBytes,
                mimeType = f.mimeType,
                title = titles[i],
                description = s.form.description.trim(),
                channelId = channelId,
                visibility = s.form.visibility,
                category = s.form.category,
                language = s.form.language,
                tags = tags,
                playlistIds = s.form.playlistIds.toList(),
                scheduleAtIso = schedule?.toInstant()?.toString(),
                timezone = schedule?.zone?.id
            )
        }
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            runCatching { queue.enqueue(drafts) }.fold(
                onSuccess = {
                    AunLog.i("upload", "batch queued", "count" to drafts.size)
                    mutableState.update { UploadUiState(form = it.form.copy(scheduleAt = null), playlists = it.playlists, queue = it.queue, queuedCount = drafts.size) }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    AunLog.e("upload", "queue failed", e)
                    mutableState.update { it.copy(submitting = false, error = R.string.upload_error_queue) }
                }
            )
        }
    }

    private fun validate(s: UploadUiState): Int? {
        val schedule = s.form.scheduleAt?.toInstant()
        return when {
            s.files.isEmpty() -> R.string.upload_error_no_files
            titles(s).any { it.isBlank() } -> R.string.upload_error_title
            schedule != null && Duration.between(now(), schedule) < MIN_SCHEDULE_AHEAD -> R.string.upload_error_schedule_soon
            schedule != null && Duration.between(now(), schedule) > MAX_SCHEDULE_AHEAD -> R.string.upload_error_schedule_far
            else -> null
        }
    }

    fun defaultScheduleTime(): ZonedDateTime = ZonedDateTime.ofInstant(now(), zone()).plusDays(1).withHour(DEFAULT_HOUR).withMinute(0).withSecond(0).withNano(0)

    fun pause(id: String) = queue.pause(id)
    fun resume(id: String) = queue.resume(id)
    fun retry(id: String) = queue.retry(id)
    fun remove(id: String) = queue.remove(id)
    fun cancel(id: String) {
        viewModelScope.launch { queue.cancel(id) }
    }

    companion object {
        /** The web batch uploader's limit (`/upload/batch/initiate`). */
        const val MAX_FILES = 15
        private const val DEFAULT_HOUR = 9
        private val MIN_SCHEDULE_AHEAD: Duration = Duration.ofMinutes(5)
        private val MAX_SCHEDULE_AHEAD: Duration = Duration.ofDays(365)
    }
}
