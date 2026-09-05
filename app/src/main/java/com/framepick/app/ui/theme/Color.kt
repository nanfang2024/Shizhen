package com.framepick.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand palette. Screens consume semantic Material colors or FramePickExtendedColors,
// so raw colors stay centralized here.
val CreamBackground = Color(0xFFFFF8EE)
val WarmSurface = Color(0xFFFFFDF9)
val WarmSurfaceMuted = Color(0xFFF5E8DA)
val WarmSelected = Color(0xFFFFE3CC)
val WarmBrownContainer = Color(0xFFEFE1D5)
val AutumnInk = Color(0xFF2B211C)
val AutumnInkMuted = Color(0xFF6B5A50)
// Slightly darkened from the #C65A1E reference so white button labels meet WCAG AA.
val AutumnOrange = Color(0xFFBF531A)
val AutumnOrangeContainer = Color(0xFFFFE0C5)
val MapleOrange = Color(0xFFE97824)
val MapleRed = Color(0xFFA9432B)
val ScarfBrown = Color(0xFF76503B)
val WarmOutline = Color(0xFFDAC9BA)
val WarmOutlineVariant = Color(0xFFEADDD2)
val SuccessGreen = Color(0xFF2E6B43)
val SuccessContainer = Color(0xFFD7F0DE)
val OnSuccessContainer = Color(0xFF123820)

val NightBackground = Color(0xFF211B18)
val NightSurface = Color(0xFF2C2420)
val NightSurfaceMuted = Color(0xFF392D27)
val NightSelected = Color(0xFF553222)
val NightText = Color(0xFFFFF2E7)
val NightTextMuted = Color(0xFFD7C3B5)
val NightOrange = Color(0xFFF08A45)
val NightOutline = Color(0xFF59473D)
val NightSuccess = Color(0xFF8BD6A2)
val NightSuccessContainer = Color(0xFF183B25)
val NightOnSuccessContainer = Color(0xFFB6F2C7)

@Immutable
data class FramePickExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val brandOrange: Color,
    val decoration: Color,
    val selectedContainer: Color,
)

internal val LightFramePickExtendedColors = FramePickExtendedColors(
    success = SuccessGreen,
    onSuccess = Color.White,
    successContainer = SuccessContainer,
    onSuccessContainer = OnSuccessContainer,
    brandOrange = MapleOrange,
    decoration = ScarfBrown,
    selectedContainer = WarmSelected,
)

internal val DarkFramePickExtendedColors = FramePickExtendedColors(
    success = NightSuccess,
    onSuccess = Color(0xFF0B2916),
    successContainer = NightSuccessContainer,
    onSuccessContainer = NightOnSuccessContainer,
    brandOrange = NightOrange,
    decoration = NightTextMuted,
    selectedContainer = NightSelected,
)

/**
 * Extended colors for Material You dynamic color (Android 12+). Semantic state
 * colors (success) stay fixed green pairs independent of the wallpaper, while
 * brand/decoration accents derive from the dynamic scheme.
 */
internal fun dynamicFramePickExtendedColors(
    scheme: ColorScheme,
    darkTheme: Boolean,
): FramePickExtendedColors = FramePickExtendedColors(
    success = if (darkTheme) NightSuccess else SuccessGreen,
    onSuccess = if (darkTheme) Color(0xFF0B2916) else Color.White,
    successContainer = if (darkTheme) NightSuccessContainer else SuccessContainer,
    onSuccessContainer = if (darkTheme) NightOnSuccessContainer else OnSuccessContainer,
    brandOrange = scheme.primary,
    decoration = scheme.tertiary,
    selectedContainer = scheme.secondaryContainer,
)

internal val LocalFramePickExtendedColors = staticCompositionLocalOf {
    LightFramePickExtendedColors
}
