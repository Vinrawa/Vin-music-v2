package com.vinmusic.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.R

// ═══════════════════════════════════════════════════════════════════════════════
//  VIN DESIGN SYSTEM — Single source of truth for all design tokens
// ═══════════════════════════════════════════════════════════════════════════════

// ── Outfit Font Family ────────────────────────────────────────────────────────

val OutfitFamily = FontFamily(
    Font(R.font.outfit_light, FontWeight.Light),
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

// ── Design Token Object ───────────────────────────────────────────────────────

object Vin {

    // ── Colors ────────────────────────────────────────────────────────────────

    object Colors {
        // Backgrounds
        val Background      = Color(0xFF050508)
        val BackgroundAlt   = Color(0xFF0A0A12)
        val Surface         = Color(0xFF0F0F1A)
        val SurfaceElevated = Color(0xFF141420)
        val SurfaceCard     = Color(0xFF16162A)

        // Glassmorphism
        val Glass           = Color(0xFFFFFFFF).copy(alpha = 0.05f)
        val GlassHover      = Color(0xFFFFFFFF).copy(alpha = 0.08f)
        val GlassBorder     = Color(0xFFFFFFFF).copy(alpha = 0.08f)
        val GlassBorderHigh = Color(0xFFFFFFFF).copy(alpha = 0.14f)

        // Accent — hot red → soft pink gradient
        val Accent          = Color(0xFFFF2D55)
        val AccentLight     = Color(0xFFFF6B9D)
        val AccentDim       = Color(0xFFFF2D55).copy(alpha = 0.15f)
        val AccentGlow      = Color(0xFFFF2D55).copy(alpha = 0.35f)
        val AccentSurface   = Color(0xFFFF2D55).copy(alpha = 0.08f)

        // Text hierarchy
        val TextPrimary     = Color(0xFFF2F2FF)
        val TextSecondary   = Color(0xFFAAAACC)
        val TextMuted       = Color(0xFF666688)
        val TextDim         = Color(0xFF444466)

        // Semantic
        val Success         = Color(0xFF10B981)
        val Warning         = Color(0xFFF59E0B)
        val Error           = Color(0xFFEF4444)
        val Info            = Color(0xFF3B82F6)

        // Utility
        val Divider         = Color(0xFFFFFFFF).copy(alpha = 0.06f)
        val Overlay         = Color(0xFF000000).copy(alpha = 0.5f)
        val Scrim           = Color(0xFF000000).copy(alpha = 0.75f)

        // Gradient stops
        val GradientTop     = Color(0xFF120812)
        val GradientMid     = Color(0xFF080410)
        val GradientBottom   = Color(0xFF050508)
    }

    // ── Gradients ─────────────────────────────────────────────────────────────

    object Gradients {
        val accent = Brush.linearGradient(
            colors = listOf(Colors.Accent, Colors.AccentLight)
        )
        val accentVertical = Brush.verticalGradient(
            colors = listOf(Colors.Accent, Colors.AccentLight)
        )
        val surface = Brush.verticalGradient(
            colors = listOf(Colors.Surface, Colors.Background)
        )
        val glass = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.02f)
            )
        )
        val glassBorder = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.04f)
            )
        )
        val background = Brush.verticalGradient(
            colors = listOf(Colors.GradientTop, Colors.GradientMid, Colors.GradientBottom)
        )
        val scrim = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Colors.Scrim)
        )
        val shimmer = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.0f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.0f)
            )
        )
    }

    // ── Spacing ───────────────────────────────────────────────────────────────

    object Spacing {
        val xxs: Dp = 2.dp
        val xs: Dp  = 4.dp
        val sm: Dp  = 8.dp
        val md: Dp  = 12.dp
        val lg: Dp  = 16.dp
        val xl: Dp  = 20.dp
        val xxl: Dp = 24.dp
        val xxxl: Dp = 32.dp
        val huge: Dp = 48.dp
    }

    // ── Corner Radius ─────────────────────────────────────────────────────────

    object Radius {
        val sm: Dp  = 8.dp
        val md: Dp  = 12.dp
        val lg: Dp  = 16.dp
        val xl: Dp  = 20.dp
        val xxl: Dp = 24.dp
        val pill: Dp = 32.dp
        val full: Dp = 100.dp
    }

    // ── Shapes ────────────────────────────────────────────────────────────────

    object Shapes {
        val sm  = RoundedCornerShape(Radius.sm)
        val md  = RoundedCornerShape(Radius.md)
        val lg  = RoundedCornerShape(Radius.lg)
        val xl  = RoundedCornerShape(Radius.xl)
        val xxl = RoundedCornerShape(Radius.xxl)
        val pill = RoundedCornerShape(Radius.pill)
        val full = RoundedCornerShape(Radius.full)
    }

    // ── Typography ────────────────────────────────────────────────────────────

    object Text {
        // Display
        val displayLarge = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 34.sp,
            letterSpacing = (-0.5).sp,
            color = Colors.TextPrimary
        )

        // Headings
        val h1 = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = (-0.3).sp,
            color = Colors.TextPrimary
        )
        val h2 = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.2).sp,
            color = Colors.TextPrimary
        )
        val h3 = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = Colors.TextPrimary
        )

        // Body
        val bodyLarge = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            color = Colors.TextPrimary
        )
        val body = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            color = Colors.TextPrimary
        )
        val bodySmall = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            color = Colors.TextSecondary
        )

        // Labels
        val label = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            color = Colors.TextPrimary
        )
        val labelSmall = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = Colors.TextSecondary
        )

        // Caption
        val caption = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.3.sp,
            color = Colors.TextMuted
        )

        // Overline
        val overline = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 1.2.sp,
            color = Colors.TextMuted
        )

        // Buttons
        val button = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 0.3.sp,
            color = Colors.TextPrimary
        )
        val buttonSmall = TextStyle(
            fontFamily = OutfitFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 0.3.sp,
            color = Colors.TextPrimary
        )
    }

    // ── Elevation / Shadow ────────────────────────────────────────────────────

    object Elevation {
        val none   = 0.dp
        val low    = 2.dp
        val medium = 6.dp
        val high   = 12.dp
    }

    // ── Animation Durations ───────────────────────────────────────────────────

    object Anim {
        const val FAST      = 150
        const val NORMAL    = 300
        const val SLOW      = 500
        const val ENTRANCE  = 400
        const val STAGGER   = 50  // delay between staggered items
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
//  MODIFIER EXTENSIONS — Reusable style modifiers
// ═══════════════════════════════════════════════════════════════════════════════

/** Apply glassmorphic card styling: semi-transparent bg + luminous border */
fun Modifier.glassCard(
    cornerRadius: Dp = Vin.Radius.lg,
    borderAlpha: Float = 0.08f
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(Vin.Colors.Glass)
    .border(
        width = 0.8.dp,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = borderAlpha * 1.5f),
                Color.White.copy(alpha = borderAlpha * 0.5f)
            )
        ),
        shape = RoundedCornerShape(cornerRadius)
    )

/** Apply a subtle glow behind an element using accent color */
fun Modifier.accentGlow(
    color: Color = Vin.Colors.AccentGlow,
    radius: Float = 80f
): Modifier = this.drawBehind {
    drawCircle(
        color = color,
        radius = radius,
        center = Offset(size.width / 2, size.height / 2)
    )
}

/** Shimmer loading effect modifier */
fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    this.drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.0f),
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.0f),
                ),
                start = Offset(translateAnim, 0f),
                end = Offset(translateAnim + 300f, size.height)
            )
        )
    }
}



