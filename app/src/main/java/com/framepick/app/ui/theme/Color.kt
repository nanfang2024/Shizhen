package com.framepick.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand palette. Screens consume semantic Material colors or AutumnExtendedColors,
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
val AutumnOrangePressed = Color(0xFF9F4315)
val AutumnOrangeContainer = Color(0xFFFFE0C5)
val MapleOrange = Color(0xFFE97824)
val MapleRed = Color(0xFFA9432B)
val ScarfBrown = Color(0xFF76503B)
val AutumnGold = Color(0xFFD79B32)
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
data class AutumnExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val brandOrange: Color,
    val mapleRed: Color,
    val gold: Color,
    val pressedPrimary: Color,
    val decoration: Color,
    val selectedContainer: Color,
)

internal val LightAutumnExtendedColors = AutumnExtendedColors(
    success = SuccessGreen,
    onSuccess = Color.White,
    successContainer = SuccessContainer,
    onSuccessContainer = OnSuccessContainer,
    brandOrange = MapleOrange,
    mapleRed = MapleRed,
    gold = AutumnGold,
    pressedPrimary = AutumnOrangePressed,
    decoration = ScarfBrown,
    selectedContainer = WarmSelected,
)

internal val DarkAutumnExtendedColors = AutumnExtendedColors(
    success = NightSuccess,
    onSuccess = Color(0xFF0B2916),
    successContainer = NightSuccessContainer,
    onSuccessContainer = NightOnSuccessContainer,
    brandOrange = NightOrange,
    mapleRed = Color(0xFFFFA58E),
    gold = Color(0xFFF0BF61),
    pressedPrimary = Color(0xFFFFA36A),
    decoration = NightTextMuted,
    selectedContainer = NightSelected,
)

internal val LocalAutumnExtendedColors = staticCompositionLocalOf {
    LightAutumnExtendedColors
}
