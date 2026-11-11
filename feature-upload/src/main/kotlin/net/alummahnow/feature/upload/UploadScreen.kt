package net.alummahnow.feature.upload

import android.Manifest
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import net.alummahnow.core.bridge.studio.UploadItem
import net.alummahnow.core.bridge.studio.core.describeDocument
import net.alummahnow.core.bridge.studio.UploadPhase
import net.alummahnow.core.bridge.studio.VideoCategories
import net.alummahnow.core.bridge.studio.VideoLanguages
import net.alummahnow.core.bridge.studio.Visibility
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.format.StudioLabels
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.theme.FanousTokens

/** Callbacks of [UploadScreen]; [onPick] opens the system picker. */
class UploadActions(
    val onPick: () -> Unit,
    val onRemoveFile: (Int) -> Unit,
    val onTitle: (Int, String) -> Unit,
    val onForm: ((SetForAll) -> SetForAll) -> Unit,
    val onTogglePlaylist: (String) -> Unit,
    val onStart: () -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val defaultSchedule: () -> ZonedDateTime
)

/** Wires [UploadViewModel] to the system picker and the notification permission. */
@Composable
fun UploadRoute(viewModel: UploadViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.addFiles(
            uris.map { uri ->
                val (name, size) = context.describeDocument(uri)
                PickedFile(uri.toString(), name, size, context.contentResolver.getType(uri))
            }
        )
    }
    UploadScreen(
        state = state,
        titles = viewModel.titles(state),
        actions = UploadActions(
            onPick = { picker.launch(arrayOf("video/*")) },
            onRemoveFile = viewModel::removeFile,
            onTitle = viewModel::setTitle,
            onForm = viewModel::updateForm,
            onTogglePlaylist = viewModel::togglePlaylist,
            onStart = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !UploadWorker.canNotify(context)) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.start()
            },
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onRetry = viewModel::retry,
            onCancel = viewModel::cancel,
            onRemove = viewModel::remove,
            defaultSchedule = viewModel::defaultScheduleTime
        ),
        modifier = modifier
    )
}

@Composable
fun UploadScreen(state: UploadUiState, titles: List<String>, actions: UploadActions, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TWO_PANE_WIDTH) {
                Row(Modifier.fillMaxSize().padding(FanousTokens.Spacing.lg), horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xl)) {
                    Column(Modifier.weight(3f).verticalScroll(rememberScrollState())) { Form(state, titles, actions) }
                    Column(Modifier.weight(2f).verticalScroll(rememberScrollState())) { QueueList(state.queue, actions) }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg).testTag("upload-scroll")) {
                    Form(state, titles, actions)
                    Spacer(Modifier.height(FanousTokens.Spacing.xl))
                    QueueList(state.queue, actions)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(state: UploadUiState, titles: List<String>, actions: UploadActions) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
        Heading(stringResource(R.string.upload_files))
        OutlinedButton(onClick = actions.onPick, modifier = Modifier.fillMaxWidth().testTag("upload-pick")) {
            Icon(Icons.Filled.VideoLibrary, contentDescription = null)
            Spacer(Modifier.padding(FanousTokens.Spacing.xs))
            Text(stringResource(R.string.upload_pick))
        }
        if (state.files.isEmpty()) {
            Text(stringResource(R.string.upload_no_files, UploadViewModel.MAX_FILES), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.files.forEachIndexed { i, f ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.testTag("upload-file-$i")) {
                Column(Modifier.padding(FanousTokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${isolate(f.name)} · ${localizedDigits(Formatter.formatShortFileSize(context, f.sizeBytes))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { actions.onRemoveFile(i) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.upload_remove_file_cd, f.name))
                        }
                    }
                    OutlinedTextField(
                        value = f.titleOverride ?: titles.getOrElse(i) { "" },
                        onValueChange = { actions.onTitle(i, it) },
                        label = { Text(stringResource(R.string.upload_title_n, i + 1)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("upload-title-$i")
                    )
                }
            }
        }

        Heading(stringResource(R.string.upload_set_for_all))
        OutlinedTextField(
            value = state.form.titlePattern,
            onValueChange = { v -> actions.onForm { it.copy(titlePattern = v) } },
            label = { Text(stringResource(R.string.upload_title_pattern)) },
            supportingText = { Text(stringResource(R.string.upload_title_pattern_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("upload-pattern")
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
            TitlePattern.TOKENS.forEach { token ->
                FilterChip(
                    selected = false,
                    onClick = { actions.onForm { it.copy(titlePattern = (it.titlePattern + " " + token).trim()) } },
                    label = { Text(isolate(token)) },
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.upload_insert_token_cd, token) }
                )
            }
        }
        OutlinedTextField(
            value = state.form.description,
            onValueChange = { v -> actions.onForm { it.copy(description = v) } },
            label = { Text(stringResource(R.string.upload_description)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("upload-description")
        )
        Picker(
            label = stringResource(R.string.upload_category),
            value = state.form.category,
            options = VideoCategories.all,
            labelOf = { v -> v?.let { StudioLabels.category(it) }?.let { stringResource(it) } ?: stringResource(R.string.upload_none) },
            onSelect = { v -> actions.onForm { it.copy(category = v) } },
            tag = "upload-category"
        )
        Picker(
            label = stringResource(R.string.upload_language),
            value = state.form.language,
            options = VideoLanguages.all,
            labelOf = { v -> v?.let { StudioLabels.language(it) }?.let { stringResource(it) } ?: stringResource(R.string.upload_none) },
            onSelect = { v -> actions.onForm { it.copy(language = v) } },
            tag = "upload-language"
        )
        Text(stringResource(R.string.upload_visibility), style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Visibility.entries.forEachIndexed { i, v ->
                SegmentedButton(
                    selected = state.form.visibility == v,
                    onClick = { actions.onForm { it.copy(visibility = v) } },
                    shape = SegmentedButtonDefaults.itemShape(i, Visibility.entries.size),
                    modifier = Modifier.testTag("upload-visibility-${v.name.lowercase()}")
                ) { Text(stringResource(if (v == Visibility.PUBLIC) R.string.upload_public else R.string.upload_private)) }
            }
        }
        if (state.playlists.isNotEmpty()) {
            Text(stringResource(R.string.upload_playlists), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)) {
                state.playlists.forEach { p ->
                    FilterChip(
                        selected = p.id in state.form.playlistIds,
                        onClick = { actions.onTogglePlaylist(p.id) },
                        label = { Text(p.title) },
                        modifier = Modifier.testTag("upload-playlist-${p.id}")
                    )
                }
            }
        }
        OutlinedTextField(
            value = state.form.tagsText,
            onValueChange = { v -> actions.onForm { it.copy(tagsText = v) } },
            label = { Text(stringResource(R.string.upload_tags)) },
            supportingText = { Text(stringResource(R.string.upload_tags_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("upload-tags")
        )
        ScheduleRow(state.form.scheduleAt, actions)

        state.error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("upload-error").semantics { liveRegion = LiveRegionMode.Assertive }
            )
        }
        if (state.queuedCount > 0) {
            Text(
                pluralStringResource(R.plurals.upload_queued, state.queuedCount, state.queuedCount),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Button(
            onClick = actions.onStart,
            enabled = state.files.isNotEmpty() && !state.submitting,
            modifier = Modifier.fillMaxWidth().testTag("upload-start")
        ) {
            Text(
                if (state.files.isEmpty()) {
                    stringResource(R.string.upload_start_none)
                } else {
                    pluralStringResource(R.plurals.upload_start, state.files.size, state.files.size)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleRow(scheduleAt: ZonedDateTime?, actions: UploadActions) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<ZonedDateTime?>(null) }
    val locale = currentLocale()
    val scheduleLabel = stringResource(R.string.upload_schedule)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.upload_schedule), style = MaterialTheme.typography.labelLarge)
            Text(
                scheduleAt?.let { localizedDigits(it.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))) }
                    ?: stringResource(R.string.upload_schedule_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (scheduleAt != null) TextButton(onClick = { pickDate = true }) { Text(stringResource(R.string.upload_schedule_change)) }
        Switch(
            checked = scheduleAt != null,
            onCheckedChange = { on -> if (on) pickDate = true else actions.onForm { it.copy(scheduleAt = null) } },
            modifier = Modifier.testTag("upload-schedule").semantics { contentDescription = scheduleLabel }
        )
    }
    if (pickDate) {
        val base = scheduleAt ?: actions.defaultSchedule()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = base.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickDate = false
                    val day = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: base.toLocalDate()
                    pickTime = base.with(day)
                }) { Text(stringResource(R.string.upload_next)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(net.alummahnow.design.R.string.aun_cancel)) } }
        ) { DatePicker(dateState) }
    }
    pickTime?.let { day ->
        val timeState = rememberTimePickerState(initialHour = day.hour, initialMinute = day.minute)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickTime = null },
            confirmButton = {
                TextButton(onClick = {
                    pickTime = null
                    actions.onForm { it.copy(scheduleAt = day.withHour(timeState.hour).withMinute(timeState.minute)) }
                }) { Text(stringResource(R.string.upload_done)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text(stringResource(net.alummahnow.design.R.string.aun_cancel)) } },
            text = { TimePicker(timeState) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Picker(
    label: String,
    value: String?,
    options: List<String>,
    labelOf: @Composable (String?) -> String,
    onSelect: (String?) -> Unit,
    tag: String
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = labelOf(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag(tag)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<String?>(null) + options).forEach { o ->
                DropdownMenuItem(text = { Text(labelOf(o)) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

@Composable
private fun QueueList(items: List<UploadItem>, actions: UploadActions) {
    var confirmCancel by remember { mutableStateOf<UploadItem?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md), modifier = Modifier.testTag("upload-queue")) {
        Heading(stringResource(R.string.upload_queue))
        if (items.isEmpty()) Text(stringResource(R.string.upload_queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { item -> QueueRow(item, actions, onCancel = { confirmCancel = item }) }
    }
    confirmCancel?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.upload_cancel_title, isolate(item.title)),
            message = stringResource(R.string.upload_cancel_body),
            confirmLabel = stringResource(R.string.upload_cancel_confirm),
            onConfirm = {
                confirmCancel = null
                actions.onCancel(item.id)
            },
            onDismiss = { confirmCancel = null }
        )
    }
}

@Composable
private fun QueueRow(item: UploadItem, actions: UploadActions, onCancel: () -> Unit) {
    val phase = stringResource(phaseLabel(item.phase))
    val percentText = localizedDigits(java.text.NumberFormat.getPercentInstance(currentLocale()).format(item.fraction.toDouble()))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag("upload-item-${item.id}")
    ) {
        Column(Modifier.padding(FanousTokens.Spacing.md), verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.isActive && item.totalBytes > 0) "$phase · $percentText" else phase,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.phase == UploadPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("upload-phase-${item.id}")
                    )
                }
                when (item.phase) {
                    UploadPhase.UPLOADING, UploadPhase.QUEUED, UploadPhase.WAITING -> IconButton(onClick = { actions.onPause(item.id) }) {
                        Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.upload_pause_cd, item.title))
                    }
                    UploadPhase.PAUSED -> IconButton(onClick = { actions.onResume(item.id) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.upload_resume_cd, item.title))
                    }
                    UploadPhase.FAILED -> IconButton(onClick = { actions.onRetry(item.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.upload_retry_cd, item.title))
                    }
                    else -> Unit
                }
                if (item.isFinished) {
                    IconButton(onClick = { actions.onRemove(item.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.upload_dismiss_cd, item.title))
                    }
                } else if (item.phase != UploadPhase.FINISHING) {
                    IconButton(onClick = onCancel, modifier = Modifier.testTag("upload-cancel-${item.id}")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.upload_cancel_cd, item.title))
                    }
                }
            }
            if (item.isActive || item.phase == UploadPhase.PAUSED) {
                LinearProgressIndicator(
                    progress = { item.fraction },
                    modifier = Modifier.fillMaxWidth().semantics { stateDescription = percentText }
                )
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Box(Modifier.padding(top = FanousTokens.Spacing.sm)) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

private fun phaseLabel(p: UploadPhase): Int = when (p) {
    UploadPhase.PREPARING -> R.string.upload_phase_preparing
    UploadPhase.QUEUED -> R.string.upload_phase_queued
    UploadPhase.WAITING -> R.string.upload_phase_waiting
    UploadPhase.UPLOADING -> R.string.upload_phase_uploading
    UploadPhase.FINISHING -> R.string.upload_phase_finishing
    UploadPhase.PAUSED -> R.string.upload_phase_paused
    UploadPhase.DONE -> R.string.upload_phase_done
    UploadPhase.FAILED -> R.string.upload_phase_failed
    UploadPhase.CANCELLED -> R.string.upload_phase_cancelled
}

private val TWO_PANE_WIDTH = 840.dp
