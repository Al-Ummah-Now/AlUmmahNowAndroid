package net.alummahnow.feature.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import net.alummahnow.design.components.FanousButton
import net.alummahnow.design.components.FanousButtonKind
import net.alummahnow.design.components.FanousCard
import net.alummahnow.design.components.FanousMaterialKind
import net.alummahnow.design.components.fanousMaterial
import net.alummahnow.design.format.durationLabel
import net.alummahnow.design.format.localizedDigits
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

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
    preview: @Composable () -> Unit = {}
) {
    if (state.list.clips.isEmpty()) {
        Box(modifier.fillMaxSize().padding(FanousTokens.Spacing.lg), contentAlignment = Alignment.Center) {
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
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= TwoColumns
        val gap = Arrangement.spacedBy(FanousTokens.Spacing.lg)
        if (wide) {
            Row(Modifier.fillMaxSize().padding(FanousTokens.Spacing.xl), horizontalArrangement = gap) {
                Column(Modifier.weight(1.7f).verticalScroll(rememberScrollState()), verticalArrangement = gap) {
                    Preview(playing, onPlayPause, preview)
                    Timeline(state, onScrub)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Clips(state, onImport, onSelect, onMove) }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(FanousTokens.Spacing.lg),
                verticalArrangement = gap
            ) {
                Preview(playing, onPlayPause, preview)
                Timeline(state, onScrub)
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

/** The clip strip with the gold playhead. Tap a clip to select it and move the playhead; drag to scrub. */
@Composable
private fun Timeline(state: EditorUiState, onScrub: (Long) -> Unit) {
    val f = LocalFanous.current
    val total = state.list.durationMs.coerceAtLeast(1)
    val timecode = localizedDigits(
        "${durationLabel(state.playheadMs / 1000)} / ${durationLabel(state.list.durationMs / 1000)}"
    )
    FanousCard(status = { Text(timecode, color = f.colors.textSecondary.color, fontSize = 13.sp) }) {
        val clipFill = f.colors.surfaceRaised.color
        val clipEdge = f.colors.divider.color
        val gold = f.colors.primary.color
        val description = stringResource(R.string.editor_timeline)
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
                    .pointerInput(total) { detectTapGestures { onScrub((it.x / size.width * total).toLong()) } }
                    .pointerInput(total) {
                        detectHorizontalDragGestures { change, _ -> onScrub((change.position.x / size.width * total).toLong()) }
                    }
            ) {
                val gap = 4.dp.toPx()
                val radius = CornerRadius(10.dp.toPx())
                val top = 8.dp.toPx()
                val height = size.height - 2 * top
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
                    x += width
                }
                val head = state.playheadMs.toFloat() / total * size.width
                drawRect(gold.copy(alpha = 0.35f), Offset(head - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
                drawRect(gold, Offset(head - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
            }
        }
    }
}

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
            val number = localizedDigits("${index + 1}")
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
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.editor_clip, number),
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
