package net.alummahnow.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.alummahnow.design.R
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

/**
 * Tabs in one row, never wrapped and never cut (owner, 2026-11-20): those that fit, and the rest under More. The
 * [selected] one is always in the row. [tag] gives each tab's test tag, and More's for null.
 */
@Composable
fun FanousTabRow(
    labels: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    tag: (Int?) -> String,
    modifier: Modifier = Modifier
) {
    val more = stringResource(R.string.aun_more)
    val measurer = rememberTextMeasurer()
    // The selected weight is the widest: no chip changes the row by being chosen.
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    val density = LocalDensity.current
    fun width(text: String): Dp =
        with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() } + CHIP_PADDING + CHIP_PADDING
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.lg, vertical = FanousTokens.Spacing.sm)
    ) {
        val shown = fittingTabs(
            widths = labels.map { width(it).value },
            more = (width(more) + MORE_ICON).value,
            gap = FanousTokens.Spacing.sm.value,
            available = maxWidth.value,
            selected = selected
        )
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm)
        ) {
            shown.forEach { i ->
                FanousChip(
                    labels[i],
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    // Alone it may still be wider than a small screen at a large font: then it ends in an ellipsis.
                    modifier = (if (shown.size == 1) Modifier.weight(1f, fill = false) else Modifier).testTag(tag(i))
                )
            }
            val rest = labels.indices - shown.toSet()
            if (rest.isNotEmpty()) MoreTabs(more, rest.map { it to labels[it] }, onSelect, tag)
        }
    }
}

@Composable
private fun MoreTabs(label: String, rest: List<Pair<Int, String>>, onSelect: (Int) -> Unit, tag: (Int?) -> String) {
    val f = LocalFanous.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .border(1.dp, f.colors.outline.color, CircleShape)
                .clip(CircleShape)
                .clickable(role = Role.DropdownList) { open = true }
                .padding(start = CHIP_PADDING, end = CHIP_PADDING / 2)
                .testTag(tag(null)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = f.colors.textSecondary.color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = f.colors.textSecondary.color,
                modifier = Modifier.size(MORE_ICON)
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            border = BorderStroke(1.dp, f.colors.outline.color)
        ) {
            rest.forEach { (i, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                    modifier = Modifier.testTag(tag(i))
                )
            }
        }
    }
}

/**
 * Which tabs are in the row, in order: as many as fit with More, and always the [selected] one, which takes the place
 * of the last ones when it wouldn't fit after them. All of them, without More, when they fit.
 */
internal fun fittingTabs(widths: List<Float>, more: Float, gap: Float, available: Float, selected: Int): List<Int> {
    fun total(tabs: List<Int>) = tabs.sumOf { widths[it].toDouble() } + gap * (tabs.size - 1).coerceAtLeast(0)
    val all = widths.indices.toList()
    if (total(all) <= available) return all
    val room = available - more - gap
    val first = all.takeWhile { total(all.subList(0, it + 1)) <= room }
    if (selected !in all || selected in first) return first
    val before = first.dropLastWhile { last -> total(first.subList(0, first.indexOf(last) + 1) + selected) > room }
    return before + selected
}

private val CHIP_PADDING = 16.dp
private val MORE_ICON = 18.dp
