package net.alummahnow.design.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

/** Button kinds of the web (`.b-primary`, `.b-secondary`, `.b-ghost`); gold only for the primary action. */
enum class FanousButtonKind { Primary, Secondary, Ghost, Destructive }

/** A pill button in the theme's materials: gold gradient with its edge for the primary, outlined for the rest. */
@Composable
fun FanousButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: FanousButtonKind = FanousButtonKind.Secondary,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val f = LocalFanous.current
    val p = f.parts
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val textColor = when (kind) {
        FanousButtonKind.Primary -> p.buttonPrimaryText
        FanousButtonKind.Secondary -> p.buttonSecondaryText
        FanousButtonKind.Ghost -> p.buttonGhostText
        FanousButtonKind.Destructive -> f.colors.errorText
    }.color
    val pressedFill = if (pressed) Modifier.fanousLayers(f.material.hover, CircleShape) else Modifier
    val outline = if (kind == FanousButtonKind.Destructive) f.colors.errorText else p.buttonSecondaryBorder
    val surface = when (kind) {
        FanousButtonKind.Primary -> {
            val fill = if (pressed) f.material.primaryPress else f.material.primary
            Modifier.fanousLayers(fill, CircleShape, shadows = f.material.primaryEdge)
        }
        FanousButtonKind.Ghost -> pressedFill
        else -> pressedFill.border(1.dp, outline.color, CircleShape)
    }
    Row(
        modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .then(surface)
            .clip(CircleShape)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
        Text(text, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A filter chip: the selected one in the gold primary material, the others outlined (Apple's StatusChip). */
@Composable
fun FanousChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val f = LocalFanous.current
    // In a scrolling row, the chosen chip scrolls fully into view (also when it is chosen from elsewhere).
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) requester.bringIntoView() }
    val surface = if (selected) {
        Modifier.fanousLayers(f.material.primary, CircleShape, shadows = f.material.primaryEdge)
    } else {
        Modifier.border(1.dp, f.colors.outline.color, CircleShape)
    }
    Row(
        modifier
            .bringIntoViewRequester(requester)
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
            maxLines = 1
        )
    }
}

/**
 * A row of [FanousChip]s that scrolls sideways when it is wider than the screen. Both edges fade over the row's side
 * padding (as on Apple), so a chip cut at the edge reads as "more this way"; at rest the first chip is not faded.
 */
@Composable
fun FanousChipRow(modifier: Modifier = Modifier, sidePadding: Dp = FanousTokens.Spacing.lg, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .edgeFade(sidePadding)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = sidePadding, vertical = FanousTokens.Spacing.sm)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(FanousTokens.Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Fades both horizontal edges of the content over [width] (transparent at the edge, opaque inside). */
fun Modifier.edgeFade(width: Dp): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = (width.toPx() / size.width).coerceIn(0f, HALF)
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, fade to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn
        )
    }

/** Lets a full-width row (a chip row) reach past its parent's side padding by [by] on each side. */
fun Modifier.bleedHorizontal(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-by.roundToPx(), 0) }
}

private const val HALF = 0.5f
