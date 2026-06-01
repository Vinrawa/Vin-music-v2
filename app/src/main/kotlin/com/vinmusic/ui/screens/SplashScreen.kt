package com.vinmusic.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vinmusic.R
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // Logo entrance animations
    val scale = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }

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
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black), // Premium solid black background
        contentAlignment = Alignment.Center
    ) {
        // App Logo centered perfectly without any clutter (cleanest minimalist design)
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(180.dp) // Perfect premium size
                .scale(scale.value)
                .alpha(alpha.value)
        )
    }
}
