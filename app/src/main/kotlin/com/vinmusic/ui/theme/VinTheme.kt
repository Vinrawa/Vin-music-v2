package com.vinmusic.ui.theme

import android.os.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp

// ── Vin Music Color Palette ───────────────────────────────────────────────────

object VinColors {
    val BgColor      = Vin.Colors.Background
    val Surface      = Vin.Colors.Surface
    val Surface2     = Vin.Colors.SurfaceElevated
    val Primary      = Vin.Colors.TextPrimary
    val Secondary    = Vin.Colors.TextSecondary
    val Accent       = Vin.Colors.Accent
    val AccentLight  = Vin.Colors.AccentLight
    val AccentGlow   = Vin.Colors.AccentDim
    val Pink         = Vin.Colors.Accent
    val Success      = Vin.Colors.Success

    val White10  = Vin.Colors.Glass
    val White20  = Color(0x22FFFFFF)
    val White40  = Color(0x44FFFFFF)
    val GlassBorder = Vin.Colors.GlassBorder

    // Gradient colors
    val GradTop    = Vin.Colors.GradientTop
    val GradMid    = Vin.Colors.GradientMid
    val GradBottom = Vin.Colors.GradientBottom
}

// ── Typography (using Outfit — geometric, modern) ─────────────────────────────

val AppTypography = Typography(
    displayLarge  = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,     fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineLarge = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Bold,     fontSize = 24.sp, letterSpacing = (-0.3).sp),
    headlineMedium= TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleLarge    = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium   = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Medium,  fontSize = 16.sp),
    bodyLarge     = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Normal,  fontSize = 16.sp),
    bodyMedium    = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Normal,  fontSize = 14.sp),
    bodySmall     = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Normal,  fontSize = 12.sp),
    labelSmall    = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Medium,  fontSize = 11.sp),
    labelMedium   = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.Medium,  fontSize = 12.sp),
    labelLarge    = TextStyle(fontFamily = OutfitFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

// ── Material3 dark color scheme ───────────────────────────────────────────────

private val VinDarkColorScheme = darkColorScheme(
    primary          = Vin.Colors.Accent,
    onPrimary        = Color.White,
    primaryContainer = Vin.Colors.AccentDim,
    secondary        = Vin.Colors.AccentLight,
    onSecondary      = Color.White,
    background       = Vin.Colors.Background,
    surface          = Vin.Colors.Surface,
    surfaceVariant   = Vin.Colors.SurfaceElevated,
    onBackground     = Vin.Colors.TextPrimary,
    onSurface        = Vin.Colors.TextPrimary,
    onSurfaceVariant = Vin.Colors.TextSecondary,
    outline          = Vin.Colors.GlassBorder,
    error            = Vin.Colors.Error
)

// ── Material You (Monet) global state ─────────────────────────────────────────
object MonetState {
    var enabled = mutableStateOf(false)
}

@Composable
fun VinMusicTheme(content: @Composable () -> Unit) {
    val useMonet = MonetState.enabled.value
    val colorScheme = if (useMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        dynamicDarkColorScheme(ctx).copy(
            background = Vin.Colors.Background,
            surface = Vin.Colors.Surface,
            surfaceVariant = Vin.Colors.SurfaceElevated,
            onBackground = Vin.Colors.TextPrimary,
            onSurface = Vin.Colors.TextPrimary,
            onSurfaceVariant = Vin.Colors.TextSecondary,
            outline = Vin.Colors.GlassBorder
        )
    } else {
        VinDarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = AppTypography,
        content     = content
    )
}
