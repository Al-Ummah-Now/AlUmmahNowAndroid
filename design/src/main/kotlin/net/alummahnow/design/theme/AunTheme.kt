package net.alummahnow.design.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** User's theme choice. Dark ("night") is the brand default; SYSTEM follows the device. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

internal val NightColors: ColorScheme = darkColorScheme(
    primary = FanousTokens.Lantern,
    onPrimary = FanousTokens.Night,
    primaryContainer = FanousTokens.LanternDeep,
    onPrimaryContainer = FanousTokens.Glow,
    secondary = FanousTokens.Earth,
    onSecondary = FanousTokens.OnNight,
    // Every container role is set, so no Material baseline (purple) colour can leak into the brand.
    secondaryContainer = FanousTokens.LanternDeep,
    onSecondaryContainer = FanousTokens.Glow,
    tertiary = FanousTokens.Glow,
    onTertiary = FanousTokens.Night,
    tertiaryContainer = FanousTokens.NightSurfaceHigh,
    onTertiaryContainer = FanousTokens.Glow,
    background = FanousTokens.Night,
    onBackground = FanousTokens.OnNight,
    surface = FanousTokens.Night,
    onSurface = FanousTokens.OnNight,
    surfaceVariant = FanousTokens.NightSurface,
    onSurfaceVariant = FanousTokens.OnNightMuted,
    surfaceContainer = FanousTokens.NightSurface,
    surfaceContainerLowest = FanousTokens.Night,
    surfaceContainerLow = FanousTokens.NightSurface,
    surfaceContainerHigh = FanousTokens.NightSurfaceHigh,
    surfaceContainerHighest = FanousTokens.NightSurfaceHigh,
    surfaceTint = FanousTokens.Lantern,
    outlineVariant = FanousTokens.NightSurfaceHigh,
    outline = FanousTokens.Earth,
    error = FanousTokens.ErrorOnNight,
    onError = FanousTokens.Night
)

internal val ParchmentColors: ColorScheme = lightColorScheme(
    primary = FanousTokens.LanternDeep,
    onPrimary = FanousTokens.ParchmentSurface,
    primaryContainer = FanousTokens.Lantern,
    onPrimaryContainer = FanousTokens.Night,
    secondary = FanousTokens.Earth,
    onSecondary = FanousTokens.ParchmentSurface,
    secondaryContainer = FanousTokens.Glow,
    onSecondaryContainer = FanousTokens.Ink,
    tertiary = FanousTokens.Night,
    onTertiary = FanousTokens.Parchment,
    tertiaryContainer = FanousTokens.ParchmentSurfaceHigh,
    onTertiaryContainer = FanousTokens.Night,
    background = FanousTokens.Parchment,
    onBackground = FanousTokens.Ink,
    surface = FanousTokens.Parchment,
    onSurface = FanousTokens.Ink,
    surfaceVariant = FanousTokens.ParchmentSurfaceHigh,
    onSurfaceVariant = FanousTokens.InkMuted,
    surfaceContainer = FanousTokens.ParchmentSurface,
    surfaceContainerLowest = FanousTokens.ParchmentSurface,
    surfaceContainerLow = FanousTokens.Parchment,
    surfaceContainerHigh = FanousTokens.ParchmentSurfaceHigh,
    surfaceContainerHighest = FanousTokens.ParchmentSurfaceHigh,
    surfaceTint = FanousTokens.LanternDeep,
    outlineVariant = FanousTokens.ParchmentSurfaceHigh,
    outline = FanousTokens.Earth,
    error = FanousTokens.Error
)

/**
 * Type scale. Font family is the platform default (Roboto / Noto Naskh Arabic) until decision D6 picks
 * the open-licensed pair; Arabic needs extra line height, which these values already allow for.
 */
internal val AunTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
)

internal val AunShapes = Shapes(
    small = RoundedCornerShape(FanousTokens.Radius.sm),
    medium = RoundedCornerShape(FanousTokens.Radius.md),
    large = RoundedCornerShape(FanousTokens.Radius.lg)
)

/** Display preferences that components read (Eastern Arabic digits is a user option, spec §3). */
data class AunDisplayPrefs(val easternArabicDigits: Boolean = true)

val LocalAunDisplayPrefs = staticCompositionLocalOf { AunDisplayPrefs() }

@Composable
fun AunTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    displayPrefs: AunDisplayPrefs = AunDisplayPrefs(),
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    CompositionLocalProvider(LocalAunDisplayPrefs provides displayPrefs) {
        MaterialTheme(
            colorScheme = if (dark) NightColors else ParchmentColors,
            typography = AunTypography,
            shapes = AunShapes,
            content = content
        )
    }
}
