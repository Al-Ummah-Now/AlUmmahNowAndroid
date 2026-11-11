package net.alummahnow.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Placeholder Fanous tokens from EPICS.md §3. Epic 10 will publish a generated Compose theme; when it
 * does, this file is replaced by the generated one and nothing else should need to change.
 */
object FanousTokens {
    val Night = Color(0xFF093041)
    val NightDeep = Color(0xFF07425A)
    val NightShallow = Color(0xFF083F58)
    val Lantern = Color(0xFFFFA037)
    val Glow = Color(0xFFFFC46B)
    val Earth = Color(0xFF8E6C53)
    val Parchment = Color(0xFFF7F1E8)
    val Verified = Color(0xFF16A34A)
    val Error = Color(0xFFB00020)

    /** Error on night surfaces: [Error] is too dark there (≈2:1); this is ≥ 4.5:1 on Night and NightSurfaceHigh. */
    val ErrorOnNight = Color(0xFFFFB4AB)

    // Derived surfaces (not in the spec table; chosen for AA contrast on night / parchment).
    val NightSurface = Color(0xFF0D3B4F)
    val NightSurfaceHigh = Color(0xFF134A61)
    val OnNight = Color(0xFFF3EEE6)
    val OnNightMuted = Color(0xFFB8C7CE)
    val ParchmentSurface = Color(0xFFFFFBF5)
    val ParchmentSurfaceHigh = Color(0xFFEDE4D6)
    val Ink = Color(0xFF1B2A30)
    val InkMuted = Color(0xFF52626A)
    val LanternDeep = Color(0xFF9A5200)

    object Spacing {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }

    object Radius {
        val sm = 6.dp
        val md = 12.dp
        val lg = 20.dp
    }
}
