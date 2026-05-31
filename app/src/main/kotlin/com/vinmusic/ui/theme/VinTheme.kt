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
    val BgColor      = Color(0xFF050505)   // Sleek pure black background
    val Surface      = Color(0xFF0D0D11)   // Dark charcoal-black nav/card surface
    val Surface2     = Color(0xFF131317)   // Solid dark charcoal elevated surface
    val Primary      = Color(0xFFFFFFFF)   // white text
    val Secondary    = Color(0xFF9EA3B0)   // Lavender-grey text for stats and descriptions
    val Accent       = Color(0xFFEF4444)   // Vibrant hot red accents and play buttons
    val AccentLight  = Color(0xFFFCA5A5)   // Light bright red active highlights
    val AccentGlow   = Color(0x22EF4444)   // Subtle red glow aura
    val Pink         = Color(0xFFEF4444)   // Favorite / like red heart
    val Success      = Color(0xFF10B981)   // neon green success/offline

    val White10  = Color(0x13FFFFFF)       // translucent white glass card
    val White20  = Color(0x22FFFFFF)
    val White40  = Color(0x44FFFFFF)
    val GlassBorder = Color(0x1FDC2626)    // Soft red glowing borders

    // Gradient colors
    val GradTop    = Color(0xFF1F0808)     // Dusty, premium dark-crimson gradient top
    val GradMid    = Color(0xFF0A0303)     // Transitional dark charcoal gradient mid
    val GradBottom = Color(0xFF050505)     // Deep black gradient bottom
}

// ── Typography (using Nunito — rounded, modern) ───────────────────────────────

val AppTypography = Typography(
    displayLarge  = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,   fontSize = 32.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,   fontSize = 24.sp),
    headlineMedium= TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium   = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyLarge     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall     = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelSmall    = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

// ── Material3 dark color scheme ───────────────────────────────────────────────

private val VinDarkColorScheme = darkColorScheme(
    primary          = VinColors.Accent,
    onPrimary        = Color.White,
    primaryContainer = VinColors.AccentGlow,
    secondary        = VinColors.AccentLight,
    onSecondary      = Color.White,
    background       = VinColors.BgColor,
    surface          = VinColors.Surface,
    surfaceVariant   = VinColors.Surface2,
    onBackground     = VinColors.Primary,
    onSurface        = VinColors.Primary,
    onSurfaceVariant = VinColors.Secondary,
    outline          = VinColors.GlassBorder,
    error            = Color(0xFFFF5252)
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
            background = VinColors.BgColor,
            surface = VinColors.Surface,
            surfaceVariant = VinColors.Surface2,
            onBackground = VinColors.Primary,
            onSurface = VinColors.Primary,
            onSurfaceVariant = VinColors.Secondary,
            outline = VinColors.GlassBorder
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
