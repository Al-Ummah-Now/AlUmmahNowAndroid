package net.alummahnow.feature.studio.edit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.alummahnow.core.bridge.studio.VideoCategories
import net.alummahnow.core.bridge.studio.VideoLanguages
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.format.StudioLabels
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Single-video edit. Leaving with unsaved changes asks first (Cancel focused). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoEditScreen(
    state: VideoEditUiState,
    onChange: ((EditForm) -> EditForm) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    val back = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = back)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.studio_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("edit-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.studio_back_cd))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                // The app bar above already handles the status bar.
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.original == null -> ErrorState(
                    stringResource(state.error ?: net.alummahnow.design.R.string.aun_error_generic),
                    onRetry
                )
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(
                            max = 720.dp
                        ).fillMaxWidth().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
                    ) { Fields(state, onChange, onSave) }
                }
            }
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.studio_discard_title),
            message = stringResource(R.string.studio_discard_body),
            confirmLabel = stringResource(R.string.studio_discard),
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun Fields(state: VideoEditUiState, onChange: ((EditForm) -> EditForm) -> Unit, onSave: () -> Unit) {
    val f = state.form
    OutlinedTextField(
        value = f.title,
        onValueChange = { v -> onChange { it.copy(title = v) } },
        label = { Text(stringResource(R.string.studio_field_title)) },
        isError = f.title.isBlank(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("edit-title")
    )
    OutlinedTextField(
        value = f.description,
        onValueChange = { v -> onChange { it.copy(description = v) } },
        label = { Text(stringResource(R.string.studio_field_description)) },
        minLines = 4,
        modifier = Modifier.fillMaxWidth().testTag("edit-description")
    )
    Text(stringResource(R.string.studio_visibility), style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(true to R.string.studio_visibility_public, false to R.string.studio_visibility_private).forEachIndexed { i, (pub, label) ->
            SegmentedButton(
                selected = f.isPublic == pub,
                onClick = { onChange { it.copy(isPublic = pub) } },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
                modifier = Modifier.testTag("edit-public-$pub")
            ) { Text(stringResource(label)) }
        }
    }
    Choice(stringResource(R.string.studio_category), f.category, VideoCategories.all, {
        StudioLabels.category(it)
    }, "edit-category") { v -> onChange { it.copy(category = v) } }
    Choice(stringResource(R.string.studio_field_language), f.language, VideoLanguages.all, {
        StudioLabels.language(it)
    }, "edit-language") { v -> onChange { it.copy(language = v) } }
    OutlinedTextField(
        value = f.tagsText,
        onValueChange = { v -> onChange { it.copy(tagsText = v) } },
        label = { Text(stringResource(R.string.studio_field_tags)) },
        supportingText = { Text(stringResource(R.string.studio_tags_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("edit-tags")
    )
    state.error?.let {
        Text(
            stringResource(it),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics {
                liveRegion =
                    LiveRegionMode.Assertive
            }
        )
    }
    if (state.saved) {
        Text(
            stringResource(R.string.studio_saved),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("edit-saved").semantics {
                liveRegion =
                    LiveRegionMode.Polite
            }
        )
    }
    Button(onClick = onSave, enabled = state.dirty && !state.saving, modifier = Modifier.fillMaxWidth().testTag("edit-save")) {
        Text(stringResource(R.string.studio_save))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Choice(
    label: String,
    value: String?,
    options: List<String>,
    labelRes: (String?) -> Int?,
    tag: String,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val text = @Composable { v: String? -> labelRes(v)?.let { stringResource(it) } ?: v ?: stringResource(R.string.studio_category_none) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = text(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag(tag)
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<String?>(null) + options).forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}
