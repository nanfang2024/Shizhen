package com.framepick.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = AutumnOrange,
    onPrimary = Color.White,
    primaryContainer = AutumnOrangeContainer,
    onPrimaryContainer = Color(0xFF4A2108),
    inversePrimary = Color(0xFFFFB783),
    secondary = ScarfBrown,
    onSecondary = Color.White,
    secondaryContainer = WarmBrownContainer,
    onSecondaryContainer = AutumnInk,
    tertiary = MapleRed,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD2),
    onTertiaryContainer = Color(0xFF3C0802),
    background = CreamBackground,
    onBackground = AutumnInk,
    surface = WarmSurface,
    onSurface = AutumnInk,
    surfaceVariant = WarmSurfaceMuted,
    onSurfaceVariant = AutumnInkMuted,
    surfaceTint = AutumnOrange,
    inverseSurface = Color(0xFF382E29),
    inverseOnSurface = Color(0xFFFFEDE2),
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    scrim = Color.Black,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceBright = WarmSurface,
    surfaceDim = Color(0xFFE7D8CD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = WarmSurface,
    surfaceContainer = WarmSurfaceMuted,
    surfaceContainerHigh = WarmBrownContainer,
    surfaceContainerHighest = Color(0xFFE8D7C9),
)

private val DarkColors = darkColorScheme(
    primary = NightOrange,
    onPrimary = Color(0xFF4A2007),
    primaryContainer = NightSelected,
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = AutumnOrange,
    secondary = Color(0xFFE7BFA8),
    onSecondary = Color(0xFF442A1D),
    secondaryContainer = Color(0xFF5B3E2F),
    onSecondaryContainer = Color(0xFFFFDBCA),
    tertiary = Color(0xFFFFB4A2),
    onTertiary = Color(0xFF5F160A),
    tertiaryContainer = Color(0xFF7F2C1B),
    onTertiaryContainer = Color(0xFFFFDAD2),
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightSurfaceMuted,
    onSurfaceVariant = NightTextMuted,
    surfaceTint = NightOrange,
    inverseSurface = NightText,
    inverseOnSurface = NightBackground,
    outline = NightOutline,
    outlineVariant = Color(0xFF443630),
    scrim = Color.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceBright = Color(0xFF453A35),
    surfaceDim = NightBackground,
    surfaceContainerLowest = Color(0xFF1A1512),
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurfaceMuted,
    surfaceContainerHigh = Color(0xFF41342E),
    surfaceContainerHighest = Color(0xFF4B3D36),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

val MaterialTheme.autumnColors: AutumnExtendedColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAutumnExtendedColors.current

@Composable
fun MediaExtractorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val extendedColors = if (darkTheme) DarkAutumnExtendedColors else LightAutumnExtendedColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.surface.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalAutumnExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
