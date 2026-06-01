package com.vinmusic.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import com.vinmusic.ui.theme.VinColors
import com.vinmusic.ui.components.UserAvatar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.vinmusic.player.AuthViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AmbientWaveVisualizer(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val baseLine = height * 0.72f
        
        // Wave 1
        val path1 = Path().apply {
            moveTo(0f, height)
            for (x in 0..width.toInt() step 5) {
                val y = baseLine + 30f * kotlin.math.sin(x * 0.004f + phase)
                lineTo(x.toFloat(), y)
            }
            lineTo(width, height)
            close()
        }
        drawPath(
            path = path1,
            brush = Brush.verticalGradient(
                colors = listOf(
                    VinColors.Accent.copy(alpha = 0.08f),
                    Color.Transparent
                )
            )
        )
        
        // Wave 2
        val path2 = Path().apply {
            moveTo(0f, height)
            for (x in 0..width.toInt() step 5) {
                val y = baseLine + 20f * kotlin.math.cos(x * 0.006f - phase * 0.8f) + 15f
                lineTo(x.toFloat(), y)
            }
            lineTo(width, height)
            close()
        }
        drawPath(
            path = path2,
            brush = Brush.verticalGradient(
                colors = listOf(
                    VinColors.AccentLight.copy(alpha = 0.06f),
                    Color.Transparent
                )
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    authVm: AuthViewModel,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    val currentUser = authVm.currentUser
    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            val name = currentUser.displayName ?: currentUser.email?.substringBefore("@") ?: "Google User"
            prefs.edit()
                .putBoolean("is_logged_in", true)
                .putString("user_name", name)
                .putString("user_email", currentUser.email)
                .apply()
            onLoginSuccess()
        }
    }

    val googleSignInClient = remember(context) { authVm.getGoogleSignInClient(context) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                if (account != null) {
                    authVm.signInWithGoogle(account)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Google Sign-In failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var nameInput by remember { mutableStateOf("") }
    var avatarIndex by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }

    val shakeOffset = remember { Animatable(0f) }
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) VinColors.AccentLight else VinColors.GlassBorder,
        animationSpec = tween(300),
        label = "borderGlow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(VinColors.GradTop, VinColors.GradBottom)))
    ) {
        // Subtle ambient moving visualizer background
        AmbientWaveVisualizer()

        // Glowing background decoration orb
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-50).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            VinColors.Accent.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(40.dp))

            // App Brand Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    VinColors.Accent,
                                    VinColors.AccentLight
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Vin Music",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = "Vin Music",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(36.dp))

            // Name Input Card with Shake animation & Glowing border
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(shakeOffset.value.roundToInt(), 0) },
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = VinColors.Surface.copy(alpha = 0.9f)),
                border = BorderStroke(1.dp, borderColor)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Dynamic greeting preview with beautiful animated transition
                    AnimatedContent(
                        targetState = nameInput.trim(),
                        transitionSpec = {
                            (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.92f, animationSpec = tween(220)))
                                .togetherWith(fadeOut(animationSpec = tween(90)) + scaleOut(targetScale = 0.95f, animationSpec = tween(90)))
                        },
                        label = "greetingAnim"
                    ) { name ->
                        Text(
                            text = if (name.isEmpty()) "Welcome!" else "Hey, $name!",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Predefined avatar customizable selector
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        UserAvatar(
                            avatarIndex = avatarIndex,
                            size = 80.dp,
                            onClick = {
                                avatarIndex = (avatarIndex + 1) % 4
                            }
                        )
                        Text(
                            text = "Tap avatar to change character",
                            fontSize = 11.sp,
                            color = VinColors.Secondary.copy(alpha = 0.8f)
                        )
                    }

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Your Name", color = VinColors.Secondary) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = "Name", tint = VinColors.AccentLight) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VinColors.AccentLight,
                            unfocusedBorderColor = VinColors.GlassBorder,
                            focusedLabelColor = VinColors.AccentLight,
                            unfocusedLabelColor = VinColors.Secondary,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            if (nameInput.trim().isBlank()) {
                                scope.launch {
                                    shakeOffset.animateTo(
                                        targetValue = 0f,
                                        animationSpec = keyframes {
                                            durationMillis = 350
                                            -18f at 50 with LinearEasing
                                            18f at 100 with LinearEasing
                                            -12f at 150 with LinearEasing
                                            12f at 200 with LinearEasing
                                            -6f at 250 with LinearEasing
                                            0f at 350 with LinearEasing
                                        }
                                    )
                                }
                                Toast.makeText(context, "Please enter your name", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            loading = true
                            scope.launch {
                                prefs.edit()
                                    .putBoolean("is_logged_in", true)
                                    .putString("user_name", nameInput.trim())
                                    .putInt("user_avatar_idx", avatarIndex)
                                    .apply()
                                loading = false
                                Toast.makeText(context, "Welcome to Vin Music, ${nameInput.trim()}!", Toast.LENGTH_SHORT).show()
                                onLoginSuccess()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            VinColors.Accent,
                                            VinColors.AccentLight
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Get Started", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    // ── Or Google Sign-In divider & button ──
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = VinColors.GlassBorder.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "OR",
                            color = VinColors.Secondary.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        HorizontalDivider(
                            modifier = Modifier.weight(1f),
                            color = VinColors.GlassBorder.copy(alpha = 0.5f)
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (!authVm.isGoogleConfigured(context)) {
                                Toast.makeText(context, "Google Sign-In is not configured in this build. Please configure Google Auth in your Firebase console first, add your SHA-1 fingerprint, and download the new google-services.json.", Toast.LENGTH_LONG).show()
                            } else {
                                launcher.launch(googleSignInClient.signInIntent)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, VinColors.GlassBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Google Sign In",
                                tint = VinColors.AccentLight,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = "Sign in with Google",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    if (authVm.authState is AuthViewModel.AuthState.Authenticating) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                color = VinColors.Accent,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Connecting with Google...",
                                color = VinColors.Secondary,
                                fontSize = 13.sp
                            )
                        }
                    } else if (authVm.authState is AuthViewModel.AuthState.Error) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = (authVm.authState as AuthViewModel.AuthState.Error).message,
                            color = Color(0xFFFF4D4D),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}
