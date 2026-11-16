package net.alummahnow.design.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import net.alummahnow.design.theme.FanousTokens

/**
 * The Fanous frosted-glass material for cards, sheets and menus (the web's aun-surfaces glass): a
 * translucent neutral fill, a thin bright edge and a soft golden glow at rest. Everything else stays
 * flat Material. Android can't blur what is behind an in-window surface, so the fill stays opaque
 * enough to read over anything.
 */
@Composable
fun Modifier.aunGlass(shape: Shape, glow: Boolean = true): Modifier {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE
    val fill = if (dark) FanousTokens.NightSurface.copy(alpha = DARK_FILL) else Color.White.copy(alpha = LIGHT_FILL)
    val edge = Color.White.copy(alpha = if (dark) DARK_EDGE else LIGHT_EDGE)
    val glowColor = FanousTokens.Glow.copy(alpha = if (dark) DARK_GLOW else LIGHT_GLOW)
    val glowRadius = 14.dp
    return this
        .then(
            if (glow) {
                Modifier.drawBehind {
                    val radius = glowRadius.toPx()
                    val paint = Paint().apply { color = glowColor }
                    paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
                    val outline: Outline = shape.createOutline(size, layoutDirection, this)
                    drawIntoCanvas { it.drawOutline(outline, paint) }
                }
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

private const val DARK_LUMINANCE = 0.3f
private const val DARK_FILL = 0.95f
private const val LIGHT_FILL = 0.94f
private const val DARK_EDGE = 0.22f
private const val LIGHT_EDGE = 0.9f
private const val DARK_GLOW = 0.30f
private const val LIGHT_GLOW = 0.45f
