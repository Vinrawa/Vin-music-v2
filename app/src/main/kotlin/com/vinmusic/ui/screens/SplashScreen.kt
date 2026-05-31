package com.vinmusic.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.R
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // Logo entrance animations
    val scale = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }
    
    // Text entrance delay
    val textAlpha = remember { Animatable(0f) }
    
    // Sleek progress bar animation (goes from 0f to 1f over 1500ms)
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Logo spring scale-up and fade-in
        launch {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(1000, easing = EaseOutCubic)
            )
        }
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        
        // Delayed text appearance
        launch {
            kotlinx.coroutines.delay(300)
            textAlpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(800, easing = EaseOutQuad)
            )
        }
        
        // Progress bar filling up smoothly
        launch {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(1500, easing = EaseInOutQuart)
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        VinColors.BgColor,
                        VinColors.GradTop,
                        VinColors.BgColor
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Soft glowing background circle
        Box(
            modifier = Modifier
                .size(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VinColors.AccentGlow,
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxHeight()
        ) {
            Spacer(modifier = Modifier.weight(1f))
            
            // App Logo
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = "App Logo",
                modifier = Modifier
                    .size(160.dp)
                    .scale(scale.value)
                    .alpha(alpha.value)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // App Name with a premium typography
            Text(
                text = "Vin Music",
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.alpha(textAlpha.value)
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            // Modern Tagline
            Text(
                text = "FEEL THE VIBE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = VinColors.AccentLight,
                letterSpacing = 4.sp,
                modifier = Modifier.alpha(textAlpha.value)
            )

            Spacer(modifier = Modifier.weight(1f))
            
            // Sleek Loading Bar at the bottom
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(bottom = 60.dp)
                    .width(180.dp)
                    .alpha(textAlpha.value)
            ) {
                // Outer bar track
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(100.dp))
                ) {
                    // Filled glowing loader
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.value)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(VinColors.Accent, VinColors.AccentLight)
                                ),
                                shape = RoundedCornerShape(100.dp)
                            )
                    )
                }
            }
        }
    }
}
