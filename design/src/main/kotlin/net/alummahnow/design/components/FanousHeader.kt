package net.alummahnow.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import net.alummahnow.design.theme.FanousTheme
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color

/**
 * The web header (`.aun-header` in packages/fanous-tokens/nav/aun-nav.css with the theme's `--m-header` and
 * `--m-header-edge`): the registry's `header` material behind the status bar and the bar, then the `navBorder`
 * line and the header edge (Fawanees: a 1dp gold rule at 66%). Dark in both modes. Content goes in a 64dp row.
 */
@Composable
fun FanousHeaderBar(
    modifier: Modifier = Modifier,
    height: Dp = HeaderHeight,
    insets: WindowInsets = WindowInsets.statusBars,
    content: @Composable RowScope.() -> Unit
) {
    val theme = LocalFanous.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .fanousLayers(theme.material.header, RectangleShape)
                .windowInsetsPadding(insets)
                .heightIn(min = height)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
        FanousHeaderEdge(theme)
    }
}

/** The bottom edge of the header on its own, for bars drawn by someone else (rail header slot, TV). */
@Composable
fun FanousHeaderEdge(theme: FanousTheme = LocalFanous.current) {
    val edge = theme.material.headerEdge.filter { !it.inset }
    val bleed = edge.maxOfOrNull { max(it.y, 0f) + it.blur + max(0f, it.spread) } ?: 0f
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.parts.navBorder.color))
        if (bleed > 0f) {
            Spacer(
                Modifier.fillMaxWidth().height(bleed.dp).drawBehind {
                    for (s in edge.asReversed()) {
                        val reach = (s.y + max(0f, s.spread)).dp.toPx()
                        if (s.blur <= 0f) {
                            drawRect(s.color.color, topLeft = Offset.Zero, size = Size(size.width, reach))
                        } else {
                            drawRect(
                                Brush.verticalGradient(listOf(s.color.color, Color.Transparent), 0f, reach + s.blur.dp.toPx()),
                                size = Size(size.width, reach + s.blur.dp.toPx())
                            )
                        }
                    }
                }
            )
        }
    }
}

/** A screen title on the header, in `navText`. */
@Composable
fun FanousHeaderTitle(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    Text(
        text,
        modifier.semantics { heading() },
        color = LocalFanous.current.parts.navText.color,
        fontSize = if (large) 22.sp else 18.sp,
        fontWeight = if (large) FontWeight.Bold else FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A control on the header, as the web draws it (`.aun-icon-btn`, `.upload-action`): an outlined pill in
 * `navSearchBorder`, `navText` label, `navIcon` icon, `navHover` while pressed. With no label it is a round icon
 * button; `outlined = false` drops the ring, as the web's account button does.
 */
@Composable
fun FanousHeaderButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    outlined: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val parts = LocalFanous.current.parts
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (pressed) parts.navHover.color else Color.Transparent)
            .then(if (outlined) Modifier.border(1.dp, parts.navSearchBorder.color, CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = if (label != null) 12.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        leading?.invoke()
        if (icon != null) Icon(icon, contentDescription = null, tint = parts.navIcon.color, modifier = Modifier.size(20.dp))
        if (label != null) Text(label, color = parts.navText.color, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Height of the header row, as the web header (4rem). */
val HeaderHeight = 64.dp
