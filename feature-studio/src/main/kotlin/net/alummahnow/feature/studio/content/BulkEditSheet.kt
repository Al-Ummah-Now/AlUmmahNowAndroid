package net.alummahnow.feature.studio.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.VideoCategories
import net.alummahnow.core.bridge.studio.VideoEdit
import net.alummahnow.design.format.StudioLabels
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Changes applied to every selected video; untouched fields stay as they are ("No change"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkEditSheet(count: Int, onApply: (BulkChanges) -> Unit, onDismiss: () -> Unit) {
    var visibility by remember { mutableStateOf<Boolean?>(null) }
    var category by remember { mutableStateOf<String?>(null) }
    var addTags by remember { mutableStateOf("") }
    var removeTags by remember { mutableStateOf("") }
    val changes = BulkChanges(isPublic = visibility, category = category, addTags = tags(addTags), removeTags = tags(removeTags))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.testTag("bulk-sheet")) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(FanousTokens.Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)
        ) {
            Text(pluralStringResource(R.plurals.studio_bulk_title, count, count), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.studio_visibility), style = MaterialTheme.typography.labelLarge)
            val options = listOf(null to R.string.studio_no_change, true to R.string.studio_visibility_public, false to R.string.studio_visibility_private)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (value, label) ->
                    SegmentedButton(
                        selected = visibility == value,
                        onClick = { visibility = value },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        modifier = Modifier.testTag("bulk-visibility-$i")
                    ) { Text(stringResource(label)) }
                }
            }
            CategoryPicker(category) { category = it }
            OutlinedTextField(
                value = addTags,
                onValueChange = { addTags = it },
                label = { Text(stringResource(R.string.studio_add_tags)) },
                supportingText = { Text(stringResource(R.string.studio_tags_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bulk-add-tags")
            )
            OutlinedTextField(
                value = removeTags,
                onValueChange = { removeTags = it },
                label = { Text(stringResource(R.string.studio_remove_tags)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("bulk-remove-tags")
            )
            Button(onClick = { onApply(changes) }, enabled = !changes.isEmpty, modifier = Modifier.fillMaxWidth().testTag("bulk-apply")) {
                Text(stringResource(R.string.studio_apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(value: String?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = @Composable { v: String? ->
        when (v) {
            null -> stringResource(R.string.studio_no_change)
            VideoEdit.CLEAR -> stringResource(R.string.studio_category_none)
            else -> StudioLabels.category(v)?.let { stringResource(it) } ?: v
        }
    }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = label(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.studio_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("bulk-category")
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(null, VideoEdit.CLEAR) + VideoCategories.all).forEach { o ->
                DropdownMenuItem(text = { Text(label(o)) }, onClick = {
                    open = false
                    onChange(o)
                })
            }
        }
    }
}

internal fun tags(text: String): List<String> = text.split(',', '،').map { it.trim() }.filter { it.isNotEmpty() }
    .map { it.take(BulkChanges.MAX_TAG_LENGTH) }.distinctBy { it.lowercase() }.take(BulkChanges.MAX_TAGS)
