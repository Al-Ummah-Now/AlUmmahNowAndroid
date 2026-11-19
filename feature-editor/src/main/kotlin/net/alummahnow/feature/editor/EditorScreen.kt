package net.alummahnow.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import net.alummahnow.core.bridge.media.EditClip
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.aunString
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.format.numberLabel
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

/** A frame of the clip's source at its start, or null while it loads (the route provides it; tests leave it null). */
val LocalClipThumbnail = staticCompositionLocalOf<(EditClip) -> ImageBitmap?> { { null } }

/** The clip's frame, or the night placeholder with the lantern gold edge while it loads. */
@Composable
private fun ClipThumbnail(clip: EditClip) {
    val f = LocalFanous.current
    val shape = RoundedCornerShape(FanousTokens.Radius.md)
    val frame = LocalClipThumbnail.current(clip)
    Box(
        Modifier
            .width(96.dp)
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(f.colors.surfaceRaised.color)
            .border(1.dp, f.colors.outline.color, shape),
        contentAlignment = Alignment.Center
    ) {
        if (frame != null) {
            Image(frame, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Movie, contentDescription = null, tint = f.colors.primary.color.copy(alpha = 0.6f))
        }
    }
}

/** Width from which the clip list sits beside the preview (tablets, unfolded foldables). */
private val TwoColumns = 840.dp

/**
 * The Studio editor (Lantern Glass): the preview, the timeline with the gold playhead, and the clip list. The
 * timeline runs left to right in every language, as on Apple and in the editors creators already use. `preview`
 * draws the video (the route passes the player's surface; tests pass nothing).
 */
@Composable
fun EditorScreen(
    state: EditorUiState,
    playing: Boolean,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onScrub: (Long) -> Unit,
    onMove: (String, Int) -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    tools: EditorTools = EditorTools(),
    preview: @Composable () -> Unit = {}
) {
    if (state.list.clips.isEmpty()) {
        Box(
            modifier.fillMaxSize().background(LocalFanous.current.colors.background.color).padding(FanousTokens.Spacing.lg),
            contentAlignment = Alignment.Center
        ) {
            FanousCard(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag("editor-empty"),
                actions = {
                    FanousButton(
                        stringResource(R.string.editor_import),
                        onImport,
                        kind = FanousButtonKind.Primary,
                        icon = Icons.Filled.VideoLibrary,
                        modifier = Modifier.testTag("editor-import")
                    )
                }
            ) {
                Text(
                    stringResource(R.string.editor_empty),
                    color = LocalFanous.current.colors.textSecondary.color,
                    modifier = Modifier.fillMaxWidth().padding(vertical = FanousTokens.Spacing.lg)
                )
            }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxSize().background(LocalFanous.current.colors.background.color)) {
        val wide = maxWidth >= TwoColumns
        val gap = Arrangement.spacedBy(FanousTokens.Spacing.lg)
        if (wide) {
            Row(Modifier.fillMaxSize().padding(FanousTokens.Spacing.xl), horizontalArrangement = gap) {
                Column(Modifier.weight(1.7f).verticalScroll(rememberScrollState()), verticalArrangement = gap) {
                    Preview(playing, onPlayPause, preview)
                    Timeline(state, onScrub, tools)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Clips(state, onImport, onSelect, onMove) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg),
                verticalArrangement = gap
            ) {
                Preview(playing, onPlayPause, preview)
                Timeline(state, onScrub, tools)
                Clips(state, onImport, onSelect, onMove)
            }
        }
    }
}

@Composable
private fun Preview(playing: Boolean, onPlayPause: () -> Unit, preview: @Composable () -> Unit) {
    val f = LocalFanous.current
    val shape = RoundedCornerShape(FanousTokens.Radius.xl)
    val label = stringResource(if (playing) R.string.editor_pause else R.string.editor_play)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .fanousMaterial(FanousMaterialKind.Hero, shape)
            .clip(shape)
            .background(Color.Black)
            .border(1.dp, f.colors.primary.color, shape)
            .testTag("editor-preview"),
        contentAlignment = Alignment.Center
    ) {
        preview()
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(f.colors.background.color.copy(alpha = 0.5f))
                .border(1.dp, f.colors.primaryEdge.color, CircleShape)
                .clickable(onClickLabel = label, onClick = onPlayPause)
                .testTag("editor-play"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = label,
                tint = f.colors.primaryHover.color,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

/** The edit actions on the timeline card: split, ripple delete, trim, undo and redo. */
data class EditorTools(
    val onSplit: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onTrim: (id: String, startMs: Long, endMs: Long, done: Boolean) -> Unit = { _, _, _, _ -> },
    val onUndo: () -> Unit = {},
    val onRedo: () -> Unit = {}
)

private enum class Drag { Scrub, TrimStart, TrimEnd }

/**
 * The clip strip with the gold playhead. Tap a clip to select it and move the playhead; drag to scrub; drag the gold
 * handles at the selected clip's ends to trim it. The footer holds undo and redo, Ripple delete and Split.
 */
@Composable
private fun Timeline(state: EditorUiState, onScrub: (Long) -> Unit, tools: EditorTools) {
    val f = LocalFanous.current
    val latest by rememberUpdatedState(state)
    val timecode = localizedDigits(
        "${durationLabel(state.playheadMs / 1000)} / ${durationLabel(state.list.durationMs / 1000)}"
    )
    FanousCard(
        status = {
            IconButton(onClick = tools.onUndo, enabled = state.canUndo, modifier = Modifier.testTag("editor-undo")) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.editor_undo))
            }
            IconButton(onClick = tools.onRedo, enabled = state.canRedo, modifier = Modifier.testTag("editor-redo")) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.editor_redo))
            }
        },
        actions = {
            FanousButton(
                stringResource(R.string.editor_delete),
                tools.onDelete,
                kind = FanousButtonKind.Destructive,
                enabled = state.selectedId != null,
                modifier = Modifier.testTag("editor-delete")
            )
            FanousButton(
                stringResource(R.string.editor_split),
                tools.onSplit,
                kind = FanousButtonKind.Primary,
                icon = Icons.Filled.ContentCut,
                modifier = Modifier.testTag("editor-split")
            )
        }
    ) {
        val clipFill = f.colors.surfaceRaised.color
        val clipEdge = f.colors.outline.color
        val gold = f.colors.primary.color
        val description = stringResource(R.string.editor_timeline)
        Text(
            timecode,
            color = f.colors.textSecondary.color,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = FanousTokens.Spacing.sm)
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .semantics {
                        contentDescription = description
                        stateDescription = timecode
                    }
                    .testTag("editor-timeline")
                    .pointerInput(Unit) {
                        detectTapGestures {
                            val total = latest.list.durationMs.coerceAtLeast(1)
                            onScrub((it.x / size.width * total).toLong())
                        }
                    }
                    .pointerInput(Unit) {
                        var mode = Drag.Scrub
                        var startX = 0f
                        var clipId = ""
                        var clipMs = 0L
                        var total = 1L
                        var last = 0L to 0L
                        detectHorizontalDragGestures(
                            onDragStart = { at ->
                                val s = latest
                                total = s.list.durationMs.coerceAtLeast(1)
                                startX = at.x
                                val index = s.selectedIndex
                                mode = Drag.Scrub
                                if (index >= 0) {
                                    val clip = s.list.clips[index]
                                    val x0 = s.list.startOf(index).toFloat() / total * size.width
                                    val x1 = (s.list.startOf(index) + clip.durationMs).toFloat() / total * size.width
                                    val reach = HANDLE_REACH.toPx()
                                    mode = when {
                                        abs(at.x - x0) <= reach -> Drag.TrimStart
                                        abs(at.x - x1) <= reach -> Drag.TrimEnd
                                        else -> Drag.Scrub
                                    }
                                    clipId = clip.id
                                    clipMs = clip.durationMs
                                    last = 0L to clipMs
                                }
                                if (mode == Drag.Scrub) onScrub((at.x / size.width * total).toLong())
                            },
                            onDragEnd = { if (mode != Drag.Scrub) tools.onTrim(clipId, last.first, last.second, true) },
                            onDragCancel = { if (mode != Drag.Scrub) tools.onTrim(clipId, last.first, last.second, true) }
                        ) { change, _ ->
                            val deltaMs = ((change.position.x - startX) / size.width * total).toLong()
                            when (mode) {
                                Drag.Scrub -> onScrub((change.position.x / size.width * total).toLong())
                                Drag.TrimStart -> {
                                    last = deltaMs to clipMs
                                    tools.onTrim(clipId, deltaMs, clipMs, false)
                                }
                                Drag.TrimEnd -> {
                                    last = 0L to clipMs + deltaMs
                                    tools.onTrim(clipId, 0, clipMs + deltaMs, false)
                                }
                            }
                        }
                    }
            ) {
                val total = state.list.durationMs.coerceAtLeast(1)
                val gap = 4.dp.toPx()
                val radius = CornerRadius(10.dp.toPx())
                val top = 8.dp.toPx()
                val height = size.height - 2 * top
                val handle = 8.dp.toPx()
                var x = 0f
                state.list.clips.forEach { clip ->
                    val width = clip.durationMs.toFloat() / total * size.width
                    val box = Size((width - gap).coerceAtLeast(2f), height)
                    drawRoundRect(clipFill, Offset(x, top), box, radius)
                    val selected = clip.id == state.selectedId
                    drawRoundRect(
                        if (selected) gold else clipEdge,
                        Offset(x, top),
                        box,
                        radius,
                        style = Stroke(if (selected) 2.5.dp.toPx() else 1.dp.toPx())
                    )
                    if (selected && box.width > 3 * handle) {
                        drawRoundRect(gold, Offset(x, top), Size(handle, height), CornerRadius(4.dp.toPx()))
                        drawRoundRect(gold, Offset(x + box.width - handle, top), Size(handle, height), CornerRadius(4.dp.toPx()))
                    }
                    x += width
                }
                val head = state.playheadMs.toFloat() / total * size.width
                drawRect(gold.copy(alpha = 0.35f), Offset(head - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
                drawRect(gold, Offset(head - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
    }
}

/** How far from a trim handle a drag still grabs it. */
private val HANDLE_REACH = 20.dp

@Composable
private fun Clips(state: EditorUiState, onImport: () -> Unit, onSelect: (String) -> Unit, onMove: (String, Int) -> Unit) {
    val f = LocalFanous.current
    FanousCard(
        actions = {
            FanousButton(
                stringResource(R.string.editor_import),
                onImport,
                icon = Icons.Filled.VideoLibrary,
                modifier = Modifier.testTag("editor-import")
            )
        }
    ) {
        Text(
            stringResource(R.string.editor_clips),
            color = f.colors.textPrimary.color,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = FanousTokens.Spacing.sm)
        )
        state.list.clips.forEachIndexed { index, clip ->
            val selected = clip.id == state.selectedId
            val number = numberLabel(index + 1L)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (selected) {
                            Modifier.fanousMaterial(FanousMaterialKind.Active, RoundedCornerShape(FanousTokens.Radius.md))
                        } else {
                            Modifier
                        }
                    )
                    .clip(RoundedCornerShape(FanousTokens.Radius.md))
                    .clickable { onSelect(clip.id) }
                    .padding(horizontal = FanousTokens.Spacing.sm, vertical = FanousTokens.Spacing.xs)
                    .testTag("editor-clip-$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
            ) {
                ClipThumbnail(clip)
                Column(Modifier.weight(1f)) {
                    Text(
                        aunString(R.string.editor_clip, number),
                        color = if (selected) f.parts.navActiveText.color else f.colors.textPrimary.color,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Text(
                        durationLabel(clip.durationMs / 1000),
                        color = f.colors.textSecondary.color,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = { onMove(clip.id, -1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_move_up))
                }
                IconButton(onClick = { onMove(clip.id, 1) }, enabled = index < state.list.clips.lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_move_down))
                }
            }
        }
    }
}
