package net.alummahnow.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import net.alummahnow.fanous.FanousThemeId

/**
 * Fixed Fanous values for parts that look the same in both modes (the loader lantern, the player, thumbnail
 * placeholders). Every colour comes from the generated registry (`net.alummahnow.fanous.FanousThemes`, vendored by
 * `scripts/sync-fanous.sh`); screens read the window theme through [LocalFanous] instead.
 */
object FanousTokens {
    private val night = FanousThemeId.FALLBACK.tokens.dark.colors
    private val day = FanousThemeId.FALLBACK.tokens.light.colors

    val Night = night.background.color
    val NightDeep = night.surface.color
    val NightShallow = night.surfaceRaised.color
    val Lantern = night.primary.color
    val Glow = night.primaryHover.color
    val Earth = night.secondary.color
    val Parchment = day.background.color
    val Error = day.errorText.color
    val ErrorOnNight = night.errorText.color
    val NightSurface = night.surface.color
    val NightSurfaceHigh = night.surfaceRaised.color
    val OnNight = night.textPrimary.color
    val OnNightMuted = night.textMuted.color
    val ParchmentSurface = day.surface.color
    val ParchmentSurfaceHigh = day.heroStart.color
    val Ink = day.textPrimary.color
    val InkMuted = day.textMuted.color
    val LanternDeep = day.link.color

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
        val md = 10.dp
        val lg = 14.dp
        val xl = 22.dp
    }
}

/** A registry colour as a Compose colour. */
val net.alummahnow.fanous.FanousRgba.color: Color get() = Color(argb)
