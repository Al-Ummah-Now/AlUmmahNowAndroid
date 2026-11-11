package net.alummahnow.feature.studio.content

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import net.alummahnow.core.bridge.studio.BulkChanges
import net.alummahnow.core.bridge.studio.ContentStatus
import net.alummahnow.core.bridge.studio.StudioVideo
import net.alummahnow.design.components.ConfirmDialog
import net.alummahnow.design.components.ErrorState
import net.alummahnow.design.components.Thumbnail
import net.alummahnow.design.format.currentLocale
import net.alummahnow.design.format.isolate
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.format.viewsLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.feature.studio.R

/** Wires [ContentViewModel]; [onEdit] opens the single-video editor. */
@Composable
fun ContentRoute(viewModel: ContentViewModel, onEdit: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(state.notice) {
        val n = state.notice ?: return@LaunchedEffect
        val text = when (n) {
            is ContentNotice.Deleted -> partial(resources, R.plurals.studio_deleted, n.count, n.failed)
            is ContentNotice.Restored -> partial(resources, R.plurals.studio_restored, n.count, n.failed)
            is ContentNotice.Updated -> partial(resources, R.plurals.studio_updated, n.count, n.failed)
            is ContentNotice.Failed -> resources.getString(n.message)
        }
        viewModel.consumeNotice()
        snackbar.showSnackbar(text)
    }
    Box(modifier.fillMaxSize()) {
        ContentScreen(state, viewModel.actions(onEdit))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

private fun partial(resources: android.content.res.Resources, plural: Int, ok: Int, failed: Int): String =
    if (failed == 0) resources.getQuantityString(plural, ok, ok) else resources.getString(R.string.studio_bulk_partial, ok, failed)

class ContentActions(
    val onStatus: (ContentStatus) -> Unit,
    val onQuery: (String) -> Unit,
    val onPickSuggestion: (net.alummahnow.core.bridge.studio.StudioSuggestion) -> Unit,
    val onToggle: (String) -> Unit,
    val onSelectAll: () -> Unit,
    val onClearSelection: () -> Unit,
    val onEdit: (String) -> Unit,
    val onBulkEdit: (BulkChanges) -> Unit,
    val onDelete: () -> Unit,
    val onRestore: () -> Unit,
    val onDeleteForever: (String) -> Unit,
    val onRetry: () -> Unit,
    val onLoadMore: () -> Unit
)

fun ContentViewModel.actions(onEdit: (String) -> Unit) = ContentActions(
    onStatus = ::setStatus,
    onQuery = ::setQuery,
    // A video suggestion opens that video; other suggestions search for their text.
    onPickSuggestion = { s ->
        val id = s.videoId
        if (id != null) {
            clearSuggestions()
            onEdit(id)
        } else {
            pickSuggestion(s)
        }
    },
    onToggle = ::toggle,
    onSelectAll = ::selectAll,
    onClearSelection = ::clearSelection,
    onEdit = onEdit,
    onBulkEdit = ::bulkEdit,
    onDelete = { delete() },
    onRestore = { restore() },
    onDeleteForever = ::deleteForever,
    onRetry = ::load,
    onLoadMore = ::loadMore
)

private val tabs = listOf(
    ContentStatus.PUBLISHED to R.string.studio_status_published,
    ContentStatus.SCHEDULED to R.string.studio_status_scheduled,
    ContentStatus.DRAFTS to R.string.studio_status_drafts,
    ContentStatus.PROCESSING to R.string.studio_status_processing,
    ContentStatus.FAILED to R.string.studio_status_failed,
    ContentStatus.DELETED to R.string.studio_status_deleted
)

@Composable
fun ContentScreen(state: ContentUiState, actions: ContentActions, modifier: Modifier = Modifier) {
    var bulkOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmForever by remember { mutableStateOf<StudioVideo?>(null) }
    val deleted = state.status == ContentStatus.DELETED

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = tabs.indexOfFirst { it.first == state.status },
                edgePadding = FanousTokens.Spacing.lg,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                tabs.forEach { (status, label) ->
                    Tab(
                        selected = status == state.status,
                        onClick = { actions.onStatus(status) },
                        text = { Text(stringResource(label)) },
                        modifier = Modifier.testTag("status-${status.apiValue}")
                    )
                }
            }
            if (state.selecting) {
                SelectionBar(state, deleted, onBulkEdit = { bulkOpen = true }, onDelete = { confirmDelete = true }, actions = actions)
            } else {
                SearchBox(state, actions)
            }
            when {
                state.error != null && state.videos.isEmpty() -> ErrorState(stringResource(state.error), actions.onRetry)
                state.loading && state.videos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.videos.isEmpty() -> Box(Modifier.fillMaxSize().padding(FanousTokens.Spacing.xl), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.query.isBlank()) stringResource(R.string.studio_content_empty) else stringResource(R.string.studio_content_empty_search, isolate(state.query)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("content-empty")
                    )
                }
                else -> VideoList(state, actions, onDeleteForever = { confirmForever = it })
            }
        }
    }

    if (bulkOpen) {
        BulkEditSheet(count = state.selected.size, onApply = {
            bulkOpen = false
            actions.onBulkEdit(it)
        }, onDismiss = { bulkOpen = false })
    }
    if (confirmDelete) {
        val n = state.selected.size
        ConfirmDialog(
            title = pluralStringResource(R.plurals.studio_delete_title, n, n),
            message = stringResource(if (state.softDelete) R.string.studio_delete_body_soft else R.string.studio_delete_body_hard),
            confirmLabel = stringResource(R.string.studio_delete),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false }
        )
    }
    confirmForever?.let { v ->
        ConfirmDialog(
            title = stringResource(R.string.studio_delete_forever_title, isolate(v.title)),
            message = stringResource(R.string.studio_delete_body_hard),
            confirmLabel = stringResource(R.string.studio_delete_forever),
            onConfirm = {
                confirmForever = null
                actions.onDeleteForever(v.id)
            },
            onDismiss = { confirmForever = null }
        )
    }
}

@Composable
private fun SearchBox(state: ContentUiState, actions: ContentActions) {
    val resources = LocalResources.current
    Column(Modifier.padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.onQuery,
            placeholder = { Text(stringResource(R.string.studio_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { actions.onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.studio_search_clear_cd)) }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("content-search")
        )
        if (state.suggestions.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth().testTag("content-suggestions").semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = resources.getString(R.string.studio_suggestions_count, state.suggestions.size)
                }
            ) {
                state.suggestions.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    Column(
                        Modifier.fillMaxWidth()
                            .combinedClickable(role = Role.Button, onClick = { actions.onPickSuggestion(s) })
                            .padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.md)
                    ) {
                        Text(s.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionBar(state: ContentUiState, deleted: Boolean, onBulkEdit: () -> Unit, onDelete: () -> Unit, actions: ContentActions) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.sm, vertical = FanousTokens.Spacing.xs).testTag("selection-bar"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = actions.onClearSelection) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.studio_clear_selection)) }
            Text(
                pluralStringResource(R.plurals.studio_selected_count, state.selected.size, state.selected.size),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
            )
            IconButton(onClick = actions.onSelectAll) { Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.studio_select_all)) }
            if (deleted) {
                TextButton(onClick = actions.onRestore, enabled = !state.busy, modifier = Modifier.testTag("bulk-restore")) {
                    Icon(Icons.Filled.Restore, contentDescription = null)
                    Text(stringResource(R.string.studio_restore))
                }
            } else {
                TextButton(onClick = onBulkEdit, enabled = !state.busy, modifier = Modifier.testTag("bulk-edit")) {
                    Icon(Icons.Filled.Edit, contentDescription = null)
                    Text(stringResource(R.string.studio_bulk_edit))
                }
                TextButton(onClick = onDelete, enabled = !state.busy, modifier = Modifier.testTag("bulk-delete")) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text(stringResource(R.string.studio_delete))
                }
            }
        }
    }
}

@Composable
private fun VideoList(state: ContentUiState, actions: ContentActions, onDeleteForever: (StudioVideo) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("content-list"),
        contentPadding = PaddingValues(FanousTokens.Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(state.videos, key = { it.id }) { v ->
            VideoRow(
                v,
                selected = v.id in state.selected,
                selecting = state.selecting,
                deleted = state.status == ContentStatus.DELETED,
                actions = actions,
                onDeleteForever = { onDeleteForever(v) }
            )
        }
        if (state.cursor != null) {
            item {
                TextButton(onClick = actions.onLoadMore, enabled = !state.loadingMore) { Text(stringResource(R.string.studio_load_more)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(v: StudioVideo, selected: Boolean, selecting: Boolean, deleted: Boolean, actions: ContentActions, onDeleteForever: () -> Unit) {
    val language = currentLocale().language
    val title = v.titleLocalized[language]?.takeIf { it.isNotBlank() } ?: v.title
    val selectLabel = stringResource(R.string.studio_selection_cd, isolate(title))
    val selectedState = pluralStringResource(R.plurals.studio_selected_count, 1, 1)
    Card(
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .widthIn(max = 960.dp)
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting || deleted) actions.onToggle(v.id) else actions.onEdit(v.id) },
                onLongClick = { actions.onToggle(v.id) }
            )
            .semantics {
                if (selected) stateDescription = selectedState
                onLongClick(label = selectLabel) {
                    actions.onToggle(v.id)
                    true
                }
            }
            .testTag("video-${v.id}")
    ) {
        Row(Modifier.padding(FanousTokens.Spacing.sm), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.md)) {
            Checkbox(
                checked = selected,
                onCheckedChange = { actions.onToggle(v.id) },
                modifier = Modifier.testTag("select-${v.id}").semantics { contentDescription = selectLabel }
            )
            Thumbnail(v.thumbnailUrl, v.durationSeconds?.toLong(), Modifier.width(120.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(meta(v, deleted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (deleted) {
                IconButton(onClick = onDeleteForever, modifier = Modifier.testTag("forever-${v.id}")) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.studio_delete_forever), tint = MaterialTheme.colorScheme.error)
                }
            } else if (!selecting) {
                IconButton(onClick = { actions.onEdit(v.id) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.studio_video_edit_cd, isolate(title)))
                }
            }
        }
    }
}

@Composable
private fun meta(v: StudioVideo, deleted: Boolean): String {
    val parts = mutableListOf<String>()
    if (deleted) {
        v.restorableUntil?.let { parts += stringResource(R.string.studio_restorable_until, formatDate(it)) }
    } else {
        parts += stringResource(
            when {
                v.isDraft -> R.string.studio_draft
                v.isPublic -> R.string.studio_visibility_public
                else -> R.string.studio_visibility_private
            }
        )
        parts += viewsLabel(v.views)
        v.createdAt?.let { parts += formatDate(it) }
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun formatDate(iso: String): String {
    val locale = currentLocale()
    val text = runCatching {
        Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }.getOrDefault(iso.take(DATE_CHARS))
    return localizedDigits(text)
}

private const val DATE_CHARS = 10
