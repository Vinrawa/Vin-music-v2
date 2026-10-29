package com.vinmusic.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vinmusic.ui.utils.ColorExtractor
import kotlin.math.cos
import kotlin.math.sin

/**
 * LiquidAuraMesh: Apple Music MetalShader-inspired dynamic fluid aura mesh background.
 *
 * Runs 3 harmonic liquid color bubbles drifting on sinusoidal orbits using hardware GPU
 * RenderEffect blur (on Android 12+) with graceful radial-falloff on older OS versions.
 * Seamlessly morphs between song palettes with smooth 1400ms color tweening.
 */
@Composable
fun LiquidAuraMesh(
    palette: ColorExtractor.MusicPalette,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = true,
    isScreenActive: Boolean = true
) {
    // ── 1. Color morphing with 1400ms silky transitions ──
    val animDominant by animateColorAsState(
        targetValue = palette.dominant,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "meshDominant"
    )
    val animSecondary by animateColorAsState(
        targetValue = palette.secondary,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "meshSecondary"
    )
    val animAccent by animateColorAsState(
        targetValue = palette.accent,
        animationSpec = tween(1400, easing = FastOutSlowInEasing),
        label = "meshAccent"
    )

    // ── 2. Harmonic physics orbits (pauses/slows when paused to save battery) ──
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_mesh_physics")

    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 16000 else 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshPhase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 22000 else 46000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshPhase2"
    )
    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 28000 else 58000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "meshPhase3"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 6000 else 12000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "meshPulse"
    )

    // ── 3. Hardware GPU RenderEffect Blur Modifier (API 31+) ──
    val gpuBlurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(
                130f, 130f,
                Shader.TileMode.CLAMP
            ).asComposeRenderEffect()
        }
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize()) {
        // ── Canvas with 3 drifting fluid color blooms ──
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(gpuBlurModifier)
        ) {
            val w = size.width
            val h = size.height

            // Bubble 1: Primary Dominant — Upper region drift
            val x1 = w * 0.35f + (w * 0.25f) * cos(phase1)
            val y1 = h * 0.22f + (h * 0.12f) * sin(phase1)
            val r1 = (w * 0.70f) * pulseScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animDominant.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(x1, y1),
                    radius = r1
                ),
                center = Offset(x1, y1),
                radius = r1
            )

            // Bubble 2: Secondary Tone — Mid-lower right drift
            val x2 = w * 0.65f + (w * 0.20f) * cos(phase2 + 1.2f)
            val y2 = h * 0.55f + (h * 0.14f) * sin(phase2 * 1.5f)
            val r2 = (w * 0.65f) * (2f - pulseScale)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animSecondary.copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(x2, y2),
                    radius = r2
                ),
                center = Offset(x2, y2),
                radius = r2
            )

            // Bubble 3: Accent Tone — Lower-left glow
            val x3 = w * 0.30f + (w * 0.22f) * sin(phase3)
            val y3 = h * 0.78f + (h * 0.10f) * cos(phase3)
            val r3 = (w * 0.60f) * pulseScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animAccent.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(x3, y3),
                    radius = r3
                ),
                center = Offset(x3, y3),
                radius = r3
            )
        }

        // ── Luxury Dark Ambient Vignette (Ensures cards & text remain razor-sharp) ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.40f),
                        0.25f to Color.Transparent,
                        0.75f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.75f)
                    )
                )
        )
    }
}
