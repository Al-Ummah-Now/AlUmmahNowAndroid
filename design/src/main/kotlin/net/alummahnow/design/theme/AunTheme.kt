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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import net.alummahnow.fanous.FanousMaterial
import net.alummahnow.fanous.FanousModeTokens
import net.alummahnow.fanous.FanousPalette
import net.alummahnow.fanous.FanousParts
import net.alummahnow.fanous.FanousThemeId
import net.alummahnow.fanous.FanousThemeTokens

/**
 * Light or dark, the person's choice. There is no "System" option (owner, 2026-11-13): until a mode is stored the app
 * follows the device (pass `null`), and the app stores the device's mode the first time it starts.
 */
enum class ThemeMode { DARK, LIGHT }

/** The window theme: the site theme (Fawanees) from the generated registry, in the light or dark mode. */
@Immutable
data class FanousTheme(val tokens: FanousThemeTokens, val dark: Boolean) {
    val mode: FanousModeTokens get() = tokens.mode(dark)
    val colors: FanousPalette get() = mode.colors
    val parts: FanousParts get() = mode.parts
    val material: FanousMaterial get() = mode.material
}

/** The window's Fanous theme, for parts Material's colour scheme has no slot for (header, card edge, glow). */
val LocalFanous = staticCompositionLocalOf { FanousTheme(FanousThemeId.FALLBACK.tokens, dark = true) }

/** Material 3 roles from the registry, so stock components can never show a baseline (purple) colour. */
internal fun colorScheme(theme: FanousTheme): ColorScheme {
    val c = theme.colors
    // Gold is too light for text on the cream page; Material uses `primary` for text buttons, so light mode takes the
    // link colour there and keeps gold for containers, as the web does.
    val primary = if (theme.dark) c.primary.color else c.link.color
    val onPrimary = if (theme.dark) c.onPrimary.color else c.background.color
    val base = if (theme.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = c.primary.color,
        onPrimaryContainer = c.onPrimary.color,
        inversePrimary = c.primaryHover.color,
        secondary = c.secondary.color,
        onSecondary = c.onSecondary.color,
        secondaryContainer = c.surfaceRaised.color,
        onSecondaryContainer = c.textPrimary.color,
        tertiary = c.accent.color,
        onTertiary = c.background.color,
        tertiaryContainer = c.heroStart.color,
        onTertiaryContainer = c.textPrimary.color,
        background = c.background.color,
        onBackground = c.textPrimary.color,
        surface = c.background.color,
        onSurface = c.textPrimary.color,
        surfaceVariant = c.surfaceRaised.color,
        onSurfaceVariant = c.textSecondary.color,
        surfaceTint = c.primary.color,
        surfaceBright = c.surfaceRaised.color,
        surfaceDim = c.background.color,
        surfaceContainerLowest = c.background.color,
        surfaceContainerLow = c.surface.color,
        surfaceContainer = c.surface.color,
        surfaceContainerHigh = c.surfaceRaised.color,
        surfaceContainerHighest = c.surfaceRaised.color,
        inverseSurface = c.textPrimary.color,
        inverseOnSurface = c.background.color,
        outline = c.outline.color,
        outlineVariant = c.divider.color,
        error = c.errorText.color,
        onError = c.background.color,
        errorContainer = c.errorFill.color,
        onErrorContainer = c.onErrorFill.color,
        scrim = Color.Black
    )
}

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

/** Applies the Fanous theme. `mode == null` follows the device until the person picks Light or Dark. */
@Composable
fun AunTheme(
    mode: ThemeMode? = null,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        null -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val theme = FanousTheme(FanousThemeId.FALLBACK.tokens, dark)
    CompositionLocalProvider(LocalFanous provides theme) {
        MaterialTheme(
            colorScheme = colorScheme(theme),
            typography = AunTypography,
            shapes = AunShapes,
            content = content
        )
    }
}
