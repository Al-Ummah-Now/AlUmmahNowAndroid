package net.alummahnow.design.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.alummahnow.design.R
import net.alummahnow.design.theme.FanousTokens

/** True when the user turned animations off (Settings → Accessibility → Remove animations). */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/** "Lantern glow" loader (spec §3 motion motif). Static glow when reduced motion is on. */
@Composable
fun LanternLoader(modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val reduced = rememberReducedMotion()
    val glow = if (reduced) {
        0.75f
    } else {
        val transition = rememberInfiniteTransition(label = "lantern")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "glow"
        ).value
    }
    val cd = stringResource(R.string.aun_loading)
    Canvas(modifier.size(size).semantics { contentDescription = cd }) {
        val r = this.size.minDimension / 2
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    FanousTokens.Glow.copy(alpha = glow),
                    FanousTokens.Lantern.copy(alpha = glow * 0.5f),
                    FanousTokens.Lantern.copy(alpha = 0f)
                ),
                center = Offset(r, r),
                radius = r
            ),
            radius = r
        )
        drawCircle(color = FanousTokens.Glow, radius = r * 0.18f)
    }
}

@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LanternLoader() }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(FanousTokens.Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = FanousTokens.Spacing.lg)) {
            Text(stringResource(R.string.aun_retry))
        }
    }
}
