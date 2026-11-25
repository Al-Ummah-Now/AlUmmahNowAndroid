package net.alummahnow.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

/** Button kinds of the web (`.b-primary`, `.b-secondary`, `.b-ghost`); gold only for the primary action. */
enum class FanousButtonKind { Primary, Secondary, Ghost, Destructive }

/**
 * A pill button in the theme's materials: gold gradient with its edge for the primary, outlined for the rest.
 * With [wrap] a label wider than its place takes more lines (a long one at a large font) instead of being cut.
 */
@Composable
fun FanousButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FanousButtonKind = FanousButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    wrap: Boolean = false
) {
    val f = LocalFanous.current
    val p = f.parts
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val textColor = when {
        !enabled && kind == FanousButtonKind.Primary -> p.buttonPrimaryDisabledText
        !enabled && kind == FanousButtonKind.Ghost -> p.buttonGhostDisabledText
        !enabled && kind == FanousButtonKind.Destructive -> p.buttonDestructiveDisabledText
        !enabled -> p.buttonSecondaryDisabledText
        kind == FanousButtonKind.Primary -> p.buttonPrimaryText
        kind == FanousButtonKind.Secondary -> p.buttonSecondaryText
        kind == FanousButtonKind.Ghost -> p.buttonGhostText
        else -> f.colors.errorText
    }.color
    val pressedFill = if (pressed && enabled) Modifier.fanousLayers(f.material.hover, CircleShape) else Modifier
    val outline = if (enabled && kind == FanousButtonKind.Destructive) f.colors.errorText else p.buttonSecondaryBorder
    val surface = when {
        !enabled && kind == FanousButtonKind.Primary ->
            Modifier.background(p.buttonPrimaryDisabledBg.color, CircleShape)
        !enabled && kind == FanousButtonKind.Ghost -> Modifier
        !enabled -> {
            val fill = if (kind == FanousButtonKind.Destructive) p.buttonDestructiveDisabledBg else p.buttonSecondaryDisabledBg
            Modifier.background(fill.color, CircleShape).border(1.dp, outline.color, CircleShape)
        }
        kind == FanousButtonKind.Primary -> {
            val fill = if (pressed) f.material.primaryPress else f.material.primary
            Modifier.fanousLayers(fill, CircleShape, shadows = f.material.primaryEdge)
        }
        kind == FanousButtonKind.Ghost -> pressedFill
        else -> pressedFill.border(1.dp, outline.color, CircleShape)
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .then(surface)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (wrap) 8.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = if (wrap) TextAlign.Center else null,
            maxLines = if (wrap) Int.MAX_VALUE else 1
        )
    }
}

/** A filter chip: the selected one in the gold primary material, the others outlined (Apple's StatusChip). */
@Composable
fun FanousChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val f = LocalFanous.current
    val surface = if (selected) {
        Modifier.fanousLayers(f.material.primary, CircleShape, shadows = f.material.primaryEdge)
    } else {
        Modifier.border(1.dp, f.colors.outline.color, CircleShape)
    }
    Row(
        modifier
            .heightIn(min = 40.dp)
            .then(surface)
            .clip(CircleShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (selected) f.parts.buttonPrimaryText.color else f.colors.textSecondary.color,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A row of [FanousChip]s that wraps onto further lines when it is wider than the screen, so every chip is always shown
 * whole (never cut at the edge).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FanousChipRow(modifier: Modifier = Modifier, sidePadding: Dp = FanousTokens.Spacing.lg, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier
            .fillMaxWidth()
            .padding(horizontal = sidePadding, vertical = FanousTokens.Spacing.sm)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
        content = content
    )
}
