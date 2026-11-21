package net.alummahnow.design.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import net.alummahnow.design.theme.FanousTokens
import net.alummahnow.design.theme.LocalFanous
import net.alummahnow.design.theme.color
import net.alummahnow.fanous.FanousBoxShadow
import net.alummahnow.fanous.FanousExtent
import net.alummahnow.fanous.FanousFill

/**
 * Paints the registry's CSS layers natively, as the Apple `FanousPaint` does: the same gradient geometry and layer
 * order as the web (`background` lists the top layer first, so layers are painted in reverse).
 */
object FanousPaint {
    /** The CSS gradient line for an angle (0 up, 90 to the right), through the centre, reaching the corners. */
    fun linearPoints(angle: Float, size: Size): Pair<Offset, Offset> {
        val theta = Math.toRadians(angle.toDouble())
        val dx = sin(theta).toFloat()
        val dy = -cos(theta).toFloat()
        val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        return Offset(center.x - dx * half, center.y - dy * half) to Offset(center.x + dx * half, center.y + dy * half)
    }

    private fun radii(fill: FanousFill.Radial, center: Offset, size: Size): Pair<Float, Float> {
        val near = Offset(minOf(center.x, size.width - center.x), minOf(center.y, size.height - center.y))
        val far = Offset(maxOf(center.x, size.width - center.x), maxOf(center.y, size.height - center.y))
        val (rx, ry) = when (fill.extent) {
            FanousExtent.RADII -> size.width * fill.rx to size.height * fill.ry
            FanousExtent.CLOSEST_SIDE -> near.x to near.y
            FanousExtent.FARTHEST_SIDE -> far.x to far.y
            FanousExtent.CLOSEST_CORNER -> near.x * SQRT2 to near.y * SQRT2
            FanousExtent.FARTHEST_CORNER -> far.x * SQRT2 to far.y * SQRT2
        }
        if (!fill.circle) return rx to ry
        val r = if (fill.extent == FanousExtent.RADII) rx else sqrt(rx * rx + ry * ry) / SQRT2
        return r to r
    }

    /** Draws background layers inside `path`. */
    fun DrawScope.fills(fills: List<FanousFill>, path: Path, rtl: Boolean) {
        for (fill in fills.asReversed()) {
            when (fill) {
                is FanousFill.Solid -> drawPath(path, fill.color.color)
                is FanousFill.Linear -> {
                    val (start, end) = linearPoints(if (fill.logical && rtl) -fill.angle else fill.angle, size)
                    val stops = fill.stops.map { it.location to it.color.color }.toTypedArray()
                    drawPath(path, Brush.linearGradient(*stops, start = start, end = end))
                }
                is FanousFill.Radial -> radial(fill, path, rtl)
            }
        }
    }

    private fun DrawScope.radial(fill: FanousFill.Radial, path: Path, rtl: Boolean) {
        val center = Offset(size.width * (if (fill.logicalX && rtl) 1 - fill.cx else fill.cx), size.height * fill.cy)
        val (rx, ry) = radii(fill, center, size)
        if (rx < MIN_RADIUS || ry < MIN_RADIUS) return
        val stops = fill.stops.map { it.location to it.color.color }.toTypedArray()
        clipPath(path) {
            // An elliptical gradient: a circle of radius rx, squashed vertically around its centre.
            scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
                val reach = max(size.width, size.height) * (rx / ry + 1)
                drawRect(
                    Brush.radialGradient(*stops, center = center, radius = rx),
                    topLeft = Offset(-reach, -reach),
                    size = Size(size.width + 2 * reach, size.height + 2 * reach)
                )
            }
        }
    }

    /**
     * Inset 1px lines (`inset 0 1px 0 …`): sheens and inner rings that need no blur. The x offset mirrors in RTL, so
     * an edge line on the content side (the rail's) stays on the content side.
     */
    fun DrawScope.insetLines(shadows: List<FanousBoxShadow>, path: Path, rtl: Boolean) {
        for (s in shadows.asReversed()) {
            if (!s.inset || s.blur > 0f) continue
            val x = if (rtl) -s.x else s.x
            val inner = Path().apply { addPath(path, Offset(x.dp.toPx(), s.y.dp.toPx())) }
            val ring = Path().apply { op(path, inner, PathOperation.Difference) }
            drawPath(ring, s.color.color)
        }
    }

    private const val SQRT2 = 1.4142135f
    private const val MIN_RADIUS = 0.01f
}

private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

/**
 * Draws a registry material behind the content: fill layers, an optional 1dp gradient border, outer shadows (rings
 * and glows via `dropShadow`) and inset lines. Fawanees is opaque (`--m-blur: none`), so nothing is blurred.
 */
fun Modifier.fanousLayers(
    fills: List<FanousFill>,
    shape: Shape,
    border: List<FanousFill> = emptyList(),
    shadows: List<FanousBoxShadow> = emptyList()
): Modifier {
    var out = this
    for (s in shadows.asReversed()) {
        if (s.inset) continue
        out = out.dropShadow(
            shape,
            Shadow(radius = s.blur.dp, color = s.color.color, spread = s.spread.dp, offset = DpOffset(s.x.dp, s.y.dp))
        )
    }
    return out.drawBehind {
        val rtl = layoutDirection == LayoutDirection.Rtl
        val outer = shape.createOutline(size, layoutDirection, this).toPath()
        with(FanousPaint) {
            if (border.isEmpty()) {
                fills(fills, outer, rtl)
            } else {
                fills(border, outer, rtl)
                val inset = 1.dp.toPx()
                val innerSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                val inner = shape.createOutline(innerSize, layoutDirection, this@drawBehind).toPath()
                    .apply { translate(Offset(inset, inset)) }
                fills(fills, inner, rtl)
            }
            insetLines(shadows, outer, rtl)
        }
    }
}

/** The web's material kinds, by the names the web uses (`--m-card`, `--m-raised`, …). */
enum class FanousMaterialKind { Page, Card, Raised, Hero, Primary, Active, Selected }

/** Draws a material of the window's theme behind the content. */
@Composable
fun Modifier.fanousMaterial(kind: FanousMaterialKind, shape: Shape): Modifier {
    val m = LocalFanous.current.material
    return when (kind) {
        FanousMaterialKind.Page -> fanousLayers(m.page, shape)
        FanousMaterialKind.Card -> fanousLayers(m.card, shape, m.border, m.cardEdge)
        FanousMaterialKind.Raised -> fanousLayers(m.raised, shape, shadows = m.raisedEdge)
        FanousMaterialKind.Hero -> fanousLayers(m.hero, shape, m.border, m.cardEdge)
        FanousMaterialKind.Primary -> fanousLayers(m.primary, shape, shadows = m.primaryEdge)
        FanousMaterialKind.Active -> fanousLayers(m.active, shape, shadows = m.activeEdge)
        FanousMaterialKind.Selected -> fanousLayers(m.sel, shape, shadows = m.selEdge + m.selGlow)
    }
}

/** Card corner radius: the theme's `xl` (22dp in Fawanees), as the web's cards. */
val FanousCardShape = RoundedCornerShape(FanousTokens.Radius.xl)

/**
 * A card in the theme's `card` material (Fawanees: gradient surface, gold gradient edge, golden glow). Owner rule: a
 * card's buttons live in its footer at the end; `status` sits at the start of the same row.
 */
@Composable
fun FanousCard(
    modifier: Modifier = Modifier,
    status: (@Composable RowScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalFanous.current
    // Icons and text inside a card default to the card's text colour (without it, icons fell back to black on Dark).
    CompositionLocalProvider(LocalContentColor provides theme.colors.textPrimary.color) {
        Column(modifier.fanousMaterial(FanousMaterialKind.Card, FanousCardShape)) {
            Column(Modifier.fillMaxWidth().padding(FanousTokens.Spacing.lg), content = content)
            if (status != null || actions != null) {
                // Inset to the content's padding, so the divider stops inside the card's border instead of painting over it.
                Box(
                    Modifier.padding(horizontal = FanousTokens.Spacing.lg).fillMaxWidth().height(1.dp)
                        .drawBehind { drawRect(theme.colors.divider.color) }
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = FanousTokens.Spacing.md, vertical = FanousTokens.Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    status?.invoke(this)
                    Spacer(Modifier.weight(1f))
                    actions?.invoke(this)
                }
            }
        }
    }
}
