package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import com.vinmusic.ui.components.UserAvatar
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.YTMusicSession
import com.vinmusic.recommendation.RecommendationManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.vinmusic.player.AuthViewModel



private val SUGGESTED_SONGS = listOf(
    VideoItem("h18s6m6-xCY", "Kahani Suno 2.0", "Kaifi Khalil", "2:53"),
    VideoItem("mW77S9m-wE8", "Pasoori", "Ali Sethi & Shae Gill", "3:44"),
    VideoItem("VU79d2F41u8", "Tu Hai Kahan", "AUR", "4:23"),
    VideoItem("k3g_Wj123fA", "Mi Amor", "Sharn", "3:10")
)

@OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(
    vm: PlayerViewModel, 
    authVm: AuthViewModel,
    onBack: () -> Unit,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit
) {
    val ctx = LocalContext.current
    val db = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
    val prefs = remember(ctx) { ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE) }

    var userName by remember { mutableStateOf(prefs.getString("user_name", "Vin") ?: "Vin") }
    var avatarIndex by remember { mutableIntStateOf(prefs.getInt("user_avatar_idx", 0)) }
    var userEmail by remember { mutableStateOf(prefs.getString("user_email", "vinmusic@gmail.com") ?: "vinmusic@gmail.com") }
    var userPhone by remember { mutableStateOf(prefs.getString("user_phone", "") ?: "") }
    var isLoggedIn by remember { mutableStateOf(prefs.getBoolean("is_logged_in", false)) }

    var showProfileDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }

    // Login dialog states
    var showLoginDialog by remember { mutableStateOf(false) }
    var loginType by remember { mutableStateOf("Email") } // "Email" or "Phone"
    var loginInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var showOtpStage by remember { mutableStateOf(false) }
    var loginLoading by remember { mutableStateOf(false) }

    // Advanced setting dialog states
    var showEqDialog by remember { mutableStateOf(false) }
    var eqEnabled by remember { mutableStateOf(prefs.getBoolean("eq_enabled", false)) }
    var eq60Hz by remember { mutableFloatStateOf(prefs.getFloat("eq_60hz", 0f)) }
    var eq230Hz by remember { mutableFloatStateOf(prefs.getFloat("eq_230hz", 0f)) }
    var eq910Hz by remember { mutableFloatStateOf(prefs.getFloat("eq_910hz", 0f)) }
    var eq4kHz by remember { mutableFloatStateOf(prefs.getFloat("eq_4khz", 0f)) }
    var eq14kHz by remember { mutableFloatStateOf(prefs.getFloat("eq_14khz", 0f)) }

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var sleepTimerMin by remember { mutableIntStateOf(prefs.getInt("sleep_timer_min", 0)) } // 0 means Off

    var skipSilence by remember { mutableStateOf(prefs.getBoolean("skip_silence", false)) }
    var streamingQuality by remember { mutableStateOf(prefs.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)") }

    var showYtCookieDialog by remember { mutableStateOf(false) }
    var ytCookieDraft by remember {
        mutableStateOf(YTMusicSession.getCookie(ctx).orEmpty())
    }
    var ytCookieConnected by remember { mutableStateOf(YTMusicSession.hasCookie(ctx)) }
    var showYtLoginOptionsDialog by remember { mutableStateOf(false) }
    var showYtWebViewLogin by remember { mutableStateOf(false) }

    var topPlayedSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "user_name" -> userName = prefs.getString("user_name", "Vin") ?: "Vin"
                "user_avatar_idx" -> avatarIndex = prefs.getInt("user_avatar_idx", 0)
                "user_email" -> userEmail = prefs.getString("user_email", "vinmusic@gmail.com") ?: "vinmusic@gmail.com"
                "user_phone" -> userPhone = prefs.getString("user_phone", "") ?: ""
                "is_logged_in" -> isLoggedIn = prefs.getBoolean("is_logged_in", false)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var likedSongs by remember { mutableStateOf<List<com.vinmusic.data.db.LikedSong>>(emptyList()) }

    var downloadQuality by remember { mutableStateOf(prefs.getString("download_quality", "High (256 kbps)") ?: "High (256 kbps)") }
    var audioNorm       by remember { mutableStateOf(prefs.getBoolean("audio_normalization", false)) }
    var crossfade       by remember { mutableStateOf(prefs.getBoolean("crossfade", false)) }
    var crossfadeSecs   by remember { mutableIntStateOf(prefs.getInt("crossfade_secs", 3)) }
    var lyricsProvider  by remember { mutableStateOf(prefs.getString("lyrics_provider", "Auto") ?: "Auto") }

    var playbackExpanded by remember { mutableStateOf(true) }
    var downloadsExpanded by remember { mutableStateOf(false) }
    var lyricsExpanded    by remember { mutableStateOf(false) }
    var aboutExpanded     by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            db.likedSongDao().getAllFlow().collect { songs ->
                likedSongs = songs
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val signals = db.interactionSignalDao().getAll()
                val sorted = signals.filter { it.playCount > 0 }
                    .sortedByDescending { it.playCount }
                    .take(5)
                    .map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                scope.launch(Dispatchers.Main) {
                    topPlayedSongs = sorted
                }
            } catch (_: Exception) {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color.Transparent)
    ) {
        // ── Custom Header ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = VinColors.Primary)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Profile & Settings", 
                fontSize = 22.sp, 
                fontWeight = FontWeight.ExtraBold, 
                color = VinColors.Primary
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Frosted User Profile Card ────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(VinColors.Surface)
                .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
                .clickable {
                    editName = userName
                    showProfileDialog = true
                }
                .padding(20.dp)
        ) {
            // Elegant background glowing accent inside the card
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 20.dp, y = (-20).dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                VinColors.Accent.copy(alpha = 0.2f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Avatar Frame
                UserAvatar(
                    avatarIndex = avatarIndex,
                    size = 68.dp,
                    name = userName,
                    onClick = {
                        avatarIndex = (avatarIndex + 1) % 4
                        prefs.edit().putInt("user_avatar_idx", avatarIndex).apply()
                    }
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = userName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Music Enthusiast",
                        fontSize = 12.sp,
                        color = VinColors.Secondary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit Name",
                        tint = VinColors.Secondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                    IconButton(onClick = {
                        isLoggedIn = false
                        prefs.edit()
                            .putBoolean("is_logged_in", false)
                            .putString("user_name", "")
                            .putString("user_email", "")
                            .putString("user_phone", "")
                            .putString("user_dob", "")
                            .putString("user_gender", "")
                            .apply()
                        try {
                            vm.exoPlayer.pause()
                        } catch (_: Exception) {}
                        onBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Reset Profile",
                            tint = Color(0xFFFF4D4D),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))



        // ── Favourite Music / Top 5 Most Played ───────────────────────────────
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                "Top 5 Most Played",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = VinColors.Primary,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(10.dp))
            
            val finalSongs = if (topPlayedSongs.isNotEmpty()) {
                topPlayedSongs
            } else {
                SUGGESTED_SONGS
            }
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(finalSongs) { song ->
                    Row(
                        modifier = Modifier
                            .width(185.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(VinColors.Surface)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                onSongClick(song, finalSongs)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))) {
                            AsyncImage(
                                model = song.thumbnail,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                song.title, 
                                fontSize = 12.sp, 
                                fontWeight = FontWeight.Bold, 
                                color = VinColors.Primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.author, 
                                fontSize = 10.sp, 
                                color = VinColors.Secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── YouTube Music (always visible — Metrolist-style cookie login) ─────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1A0A2E), Color(0xFF2D1B4E))
                    )
                )
                .border(1.dp, VinColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .clickable { showYtLoginOptionsDialog = true }
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(VinColors.Accent.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = VinColors.Accent, modifier = Modifier.size(28.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "YouTube Music login",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.Primary
                    )
                    Text(
                        if (ytCookieConnected) "Connected — personalized recommendations on"
                        else "Tap here to sign in with Google or setup manually",
                        fontSize = 12.sp,
                        color = VinColors.Secondary,
                        lineHeight = 16.sp
                    )
                }
                Icon(Icons.Default.ChevronRight, null, tint = VinColors.Accent, modifier = Modifier.size(24.dp))
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Google Cloud Sync Card ──
        val currentUser = authVm.currentUser
        val syncState = authVm.syncState
        val lastSyncMessage = authVm.lastSyncMessage
        
        val googleSignInClient = remember(ctx) { authVm.getGoogleSignInClient(ctx) }
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
                    Toast.makeText(ctx, "Google Sign-In failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E1A3C), Color(0xFF0F0B1E))
                    )
                )
                .border(1.dp, VinColors.AccentLight.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.AccentLight.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudQueue,
                            contentDescription = null,
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Cloud Sync & Backup",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )
                        Text(
                            text = if (currentUser != null) "Backup linked to ${currentUser.email}"
                                   else "Tap Connect to backup your playlists & likes",
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            lineHeight = 16.sp
                        )
                    }
                    if (currentUser == null) {
                        Button(
                            onClick = {
                                if (!authVm.isGoogleConfigured(ctx)) {
                                    Toast.makeText(ctx, "Google Sign-In is not configured in this build. Please configure Google Auth in your Firebase console first, add your SHA-1 fingerprint, and download the new google-services.json.", Toast.LENGTH_LONG).show()
                                } else {
                                    launcher.launch(googleSignInClient.signInIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }

                if (currentUser != null) {
                    HorizontalDivider(color = VinColors.GlassBorder.copy(alpha = 0.3f))
                    
                    if (lastSyncMessage.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (syncState is AuthViewModel.SyncState.Syncing) {
                                CircularProgressIndicator(
                                    color = VinColors.AccentLight,
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (syncState is AuthViewModel.SyncState.Error) Icons.Default.ErrorOutline else Icons.Default.CheckCircleOutline,
                                    contentDescription = null,
                                    tint = if (syncState is AuthViewModel.SyncState.Error) Color(0xFFFF4D4D) else Color(0xFF4CAF50),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = lastSyncMessage,
                                fontSize = 12.sp,
                                color = VinColors.Secondary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { authVm.backupDataToCloud() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, VinColors.GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Backup", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        
                        OutlinedButton(
                            onClick = { authVm.restoreCloudData() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, VinColors.GlassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Restore", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        IconButton(
                            onClick = { authVm.signOut(ctx) },
                            modifier = Modifier
                                .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Default.ExitToApp, null, tint = Color(0xFFFF4D4D))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // 0. Appearance
        var appearanceExpanded by remember { mutableStateOf(false) }
        var monetEnabled by remember { mutableStateOf(prefs.getBoolean("monet_enabled", false)) }

        CollapsibleSection(
            title = "Appearance",
            icon = Icons.Default.Palette,
            expanded = appearanceExpanded,
            onToggle = { appearanceExpanded = !appearanceExpanded }
        ) {
            SettingsToggle(
                title = "Material You (Monet)",
                subtitle = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    "Dynamic accent colors from your wallpaper"
                else
                    "Requires Android 12 or newer",
                checked = monetEnabled,
                onChanged = {
                    monetEnabled = it
                    prefs.edit().putBoolean("monet_enabled", it).apply()
                    com.vinmusic.ui.theme.MonetState.enabled.value = it
                }
            )
        }

        // 1. Playback Settings
        CollapsibleSection(
            title = "Playback Settings",
            icon = Icons.Default.PlayArrow,
            expanded = playbackExpanded,
            onToggle = { playbackExpanded = !playbackExpanded }
        ) {
            SettingsToggle(
                title = "Audio Normalisation", 
                subtitle = "Equalise volume across songs", 
                checked = audioNorm, 
                onChanged = { 
                    audioNorm = it 
                    prefs.edit().putBoolean("audio_normalization", it).apply()
                }
            )
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsToggle(
                title = "Smart Autoplay", 
                subtitle = "Seamlessly play similar tracks when queue finishes", 
                checked = vm.smartAutoplayEnabled, 
                onChanged = { 
                    vm.setSmartAutoplay(it)
                }
            )
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsToggle(
                title = "Crossfade", 
                subtitle = "Smooth transition between songs", 
                checked = crossfade, 
                onChanged = { 
                    crossfade = it 
                    prefs.edit().putBoolean("crossfade", it).apply()
                }
            )
            if (crossfade) {
                SettingsSliderRow(
                    label = "Crossfade duration: ${crossfadeSecs}s", 
                    min = 1f, 
                    max = 12f, 
                    value = crossfadeSecs.toFloat()
                ) {
                    crossfadeSecs = it.toInt()
                    prefs.edit().putInt("crossfade_secs", it.toInt()).apply()
                }
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsToggle(
                title = "Skip Silence", 
                subtitle = "Automatically skip silent segments", 
                checked = skipSilence, 
                onChanged = { 
                    skipSilence = it 
                    prefs.edit().putBoolean("skip_silence", it).apply()
                }
            )
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsDropdown(
                title = "Streaming Quality", 
                current = streamingQuality, 
                options = listOf("Low (96kbps)", "Normal (160kbps)", "High (256kbps)", "Ultra (320kbps)")
            ) {
                streamingQuality = it
                prefs.edit().putString("streaming_quality", it).apply()
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            // Custom Equaliser row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showEqDialog = true }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Equaliser", fontSize = 15.sp, color = VinColors.Primary)
                    Text("Configure frequency bands", fontSize = 12.sp, color = VinColors.Secondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (eqEnabled) "On" else "Off", fontSize = 13.sp, color = VinColors.Secondary)
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = VinColors.Secondary, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            // Sleep Timer row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showSleepTimerDialog = true }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween, 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Sleep Timer", fontSize = 15.sp, color = VinColors.Primary)
                    Text("Stop music after a set duration", fontSize = 12.sp, color = VinColors.Secondary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (sleepTimerMin > 0) "${sleepTimerMin} mins" else "Off", fontSize = 13.sp, color = VinColors.Secondary)
                    Icon(Icons.Default.KeyboardArrowRight, null, tint = VinColors.Secondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        // 2. Downloads
        CollapsibleSection(
            title = "Downloads Settings",
            icon = Icons.Default.Download,
            expanded = downloadsExpanded,
            onToggle = { downloadsExpanded = !downloadsExpanded }
        ) {
            SettingsDropdown(
                title = "Quality", 
                current = downloadQuality,
                options = listOf("Low (128 kbps)", "High (256 kbps)", "Best (320 kbps)")
            ) {
                downloadQuality = it
                prefs.edit().putString("download_quality", it).apply()
            }
        }

        // 3. Lyrics
        CollapsibleSection(
            title = "Lyrics & Subtitles",
            icon = Icons.Default.Lyrics,
            expanded = lyricsExpanded,
            onToggle = { lyricsExpanded = !lyricsExpanded }
        ) {
            SettingsDropdown(
                title = "Provider Selection",
                current = when(lyricsProvider) {
                    "LrcLib" -> "LRCLIB Only"
                    "Paxsenix" -> "Paxsenix Only"
                    "KuGou" -> "KuGou Only"
                    "SimpMusic" -> "SimpMusic Only"
                    else -> "Auto (Recommended)"
                },
                options = listOf("Auto (Recommended)", "LRCLIB Only", "Paxsenix Only", "KuGou Only", "SimpMusic Only")
            ) { selected ->
                val code = when(selected) {
                    "LRCLIB Only" -> "LrcLib"
                    "Paxsenix" -> "Paxsenix"
                    "KuGou Only" -> "KuGou"
                    "SimpMusic Only" -> "SimpMusic"
                    else -> "Auto"
                }
                lyricsProvider = code
                prefs.edit().putString("lyrics_provider", code).apply()
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Source Priority", value = "SimpMusic → LrcLib → KuGou → Paxsenix (when Auto)")
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Synced Lyrics", value = "Tap any lyric line to seek to that position")
        }

        // 4. About
        CollapsibleSection(
            title = "About VinMusic",
            icon = Icons.Default.Info,
            expanded = aboutExpanded,
            onToggle = { aboutExpanded = !aboutExpanded }
        ) {
            SettingsInfo(title = "Version", value = com.vinmusic.BuildConfig.VERSION_NAME)
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Streaming", value = "Multi-client InnerTube (6 fallbacks)")
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Built with", value = "Kotlin • Jetpack Compose • ExoPlayer")
        }

        Spacer(Modifier.height(48.dp))
    }

    // ── Edit Profile Name Dialog ──────────────────────────────────────────────
    if (showProfileDialog) {
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Edit Profile Name", color = VinColors.Primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Your Name", color = VinColors.Secondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VinColors.Accent, 
                            unfocusedBorderColor = VinColors.GlassBorder,
                            focusedTextColor = VinColors.Primary, 
                            unfocusedTextColor = VinColors.Primary,
                            focusedContainerColor = VinColors.White10, 
                            unfocusedContainerColor = VinColors.White10
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    userName = editName.trim().ifEmpty { "Music Lover" }
                    prefs.edit().putString("user_name", userName).apply()
                    showProfileDialog = false
                }) { Text("Save", color = VinColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showProfileDialog = false }) { Text("Cancel", color = VinColors.Secondary) }
            }
        )
    }

    // ── Email & Phone Login Verification Dialog ───────────────────────────────
    if (showLoginDialog) {
        AlertDialog(
            onDismissRequest = { showLoginDialog = false },
            title = { Text("Sign In with ${loginType}", color = VinColors.Primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!showOtpStage) {
                        Text(
                            "Enter your ${if (loginType == "Email") "email address" else "phone number"} to receive a verification code.",
                            fontSize = 13.sp,
                            color = VinColors.Secondary
                        )
                        OutlinedTextField(
                            value = loginInput,
                            onValueChange = { loginInput = it },
                            label = { Text(if (loginType == "Email") "Email Address" else "Phone Number", color = VinColors.Secondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VinColors.Accent,
                                unfocusedBorderColor = VinColors.GlassBorder,
                                focusedTextColor = VinColors.Primary,
                                unfocusedTextColor = VinColors.Primary,
                                focusedContainerColor = VinColors.White10,
                                unfocusedContainerColor = VinColors.White10
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            "A 4-digit verification code has been sent. Enter code to continue.",
                            fontSize = 13.sp,
                            color = VinColors.Secondary
                        )
                        OutlinedTextField(
                            value = otpInput,
                            onValueChange = { if (it.length <= 4) otpInput = it },
                            label = { Text("Verification OTP Code", color = VinColors.Secondary) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VinColors.Accent,
                                unfocusedBorderColor = VinColors.GlassBorder,
                                focusedTextColor = VinColors.Primary,
                                unfocusedTextColor = VinColors.Primary,
                                focusedContainerColor = VinColors.White10,
                                unfocusedContainerColor = VinColors.White10
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!showOtpStage) {
                        if (loginInput.trim().isNotEmpty()) {
                            showOtpStage = true
                            otpInput = ""
                        }
                    } else {
                        if (otpInput.length == 4) {
                            // Successful Mock OTP verify!
                            isLoggedIn = true
                            userName = if (loginType == "Email") {
                                loginInput.substringBefore("@").replaceFirstChar { it.uppercase() }
                            } else {
                                "User_${loginInput.takeLast(4)}"
                            }
                            userEmail = if (loginType == "Email") loginInput.trim() else ""
                            userPhone = if (loginType == "Phone") loginInput.trim() else ""
                            
                            prefs.edit()
                                .putBoolean("is_logged_in", true)
                                .putString("user_name", userName)
                                .putString("user_email", userEmail)
                                .putString("user_phone", userPhone)
                                .apply()
                            
                            showLoginDialog = false
                        }
                    }
                }) {
                    Text(if (!showOtpStage) "Send Code" else "Verify & Sign In", color = VinColors.AccentLight)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoginDialog = false }) {
                    Text("Cancel", color = VinColors.Secondary)
                }
            }
        )
    }

    // ── Equaliser Settings Dialog ─────────────────────────────────────────────
    if (showEqDialog) {
        AlertDialog(
            onDismissRequest = { showEqDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Equaliser", color = VinColors.Primary)
                    Switch(
                        checked = eqEnabled,
                        onCheckedChange = {
                            eqEnabled = it
                            prefs.edit().putBoolean("eq_enabled", it).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = VinColors.Accent,
                            uncheckedThumbColor = VinColors.Secondary,
                            uncheckedTrackColor = VinColors.White10,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    listOf(
                        "60 Hz" to eq60Hz to { v: Float -> eq60Hz = v; prefs.edit().putFloat("eq_60hz", v).apply() },
                        "230 Hz" to eq230Hz to { v: Float -> eq230Hz = v; prefs.edit().putFloat("eq_230hz", v).apply() },
                        "910 Hz" to eq910Hz to { v: Float -> eq910Hz = v; prefs.edit().putFloat("eq_910hz", v).apply() },
                        "4 kHz" to eq4kHz to { v: Float -> eq4kHz = v; prefs.edit().putFloat("eq_4khz", v).apply() },
                        "14 kHz" to eq14kHz to { v: Float -> eq14kHz = v; prefs.edit().putFloat("eq_14khz", v).apply() }
                    ).forEach { item ->
                        val pair = item.first
                        val onValChange = item.second
                        val label = pair.first
                        val valFloat = pair.second
                        Column(modifier = Modifier.alpha(if (eqEnabled) 1.0f else 0.5f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(label, fontSize = 12.sp, color = VinColors.Primary)
                                Text("${String.format("%.1f", valFloat)} dB", fontSize = 12.sp, color = VinColors.Secondary)
                            }
                            Slider(
                                value = valFloat,
                                onValueChange = onValChange,
                                valueRange = -12f..12f,
                                enabled = eqEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = VinColors.Accent,
                                    activeTrackColor = VinColors.Accent,
                                    inactiveTrackColor = VinColors.White10
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEqDialog = false }) {
                    Text("Done", color = VinColors.AccentLight)
                }
            }
        )
    }

    // ── YouTube Music Connection Dialogs ─────────────────────────────────────
    if (showYtCookieDialog) {
        AlertDialog(
            onDismissRequest = { showYtCookieDialog = false },
            title = { Text("Connect YouTube Music (Manual)", color = VinColors.Primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "1. Chrome mein music.youtube.com kholo aur login karo\n" +
                        "2. F12 → Application → Cookies → music.youtube.com\n" +
                        "3. Saari cookies copy karke neeche paste karo (SAPISID, SID, etc.)",
                        fontSize = 12.sp,
                        color = VinColors.Secondary,
                        lineHeight = 18.sp
                    )
                    OutlinedTextField(
                        value = ytCookieDraft,
                        onValueChange = { ytCookieDraft = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 160.dp),
                        placeholder = { Text("SAPISID=...; SID=...") },
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    YTMusicSession.setCookie(ctx, ytCookieDraft)
                    ytCookieConnected = ytCookieDraft.isNotBlank()
                    RecommendationManager.invalidateCache()
                    ctx.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                    showYtCookieDialog = false
                    android.widget.Toast.makeText(ctx, "Cookie saved. Pull to refresh Home.", android.widget.Toast.LENGTH_LONG).show()
                }) { Text("Save", color = VinColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    YTMusicSession.setCookie(ctx, null)
                    ytCookieDraft = ""
                    ytCookieConnected = false
                    RecommendationManager.invalidateCache()
                    ctx.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                    showYtCookieDialog = false
                }) { Text("Clear", color = VinColors.Secondary) }
            },
            containerColor = VinColors.Surface
        )
    }

    if (showYtLoginOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showYtLoginOptionsDialog = false },
            title = {
                Text(
                    text = "Connect YouTube Music",
                    color = VinColors.Primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Sign in to access your custom mixes, liked songs, and personalized homepage recommendations from YouTube Music.",
                        color = VinColors.Secondary,
                        fontSize = 13.sp
                    )

                    // Option 1: Automatic Web Login (Google Sign-In)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(VinColors.Accent.copy(alpha = 0.15f))
                            .border(1.dp, VinColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable {
                                showYtLoginOptionsDialog = false
                                showYtWebViewLogin = true
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "G",
                                    color = Color(0xFF4285F4),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 18.sp
                                )
                            }
                            Column {
                                Text(
                                    "Sign In with Google",
                                    color = VinColors.Primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    "Recommended, automatic & secure",
                                    color = VinColors.Secondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Option 2: Manual Cookie Paste
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(14.dp))
                            .clickable {
                                showYtLoginOptionsDialog = false
                                showYtCookieDialog = true
                            }
                            .padding(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(VinColors.White10),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = VinColors.Secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column {
                                Text(
                                    "Manual Cookie Setup",
                                    color = VinColors.Primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    "Paste cookies manually from your browser",
                                    color = VinColors.Secondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // If connected, show Logout option
                    if (ytCookieConnected) {
                        Button(
                            onClick = {
                                YTMusicSession.setCookie(ctx, null)
                                ytCookieDraft = ""
                                ytCookieConnected = false
                                RecommendationManager.invalidateCache()
                                ctx.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                                showYtLoginOptionsDialog = false
                                Toast.makeText(ctx, "Disconnected YouTube Music account", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4D4D).copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, Color(0xFFFF4D4D).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disconnect Account", color = Color(0xFFFF4D4D), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showYtLoginOptionsDialog = false }) {
                    Text("Cancel", color = VinColors.Secondary)
                }
            },
            containerColor = VinColors.Surface
        )
    }

    if (showYtWebViewLogin) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showYtWebViewLogin = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = VinColors.BgColor
            ) {
                var webViewLoading by remember { mutableStateOf(true) }
                
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VinColors.Surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { showYtWebViewLogin = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                        
                        Text(
                            text = "Sign In with Google",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )
                        
                        IconButton(onClick = { /* Reload handled by recreation or can be added */ }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = VinColors.Secondary)
                        }
                    }
                    
                    // Loading Progress Indicator
                    if (webViewLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = VinColors.AccentLight,
                            trackColor = VinColors.Surface
                        )
                    } else {
                        HorizontalDivider(color = VinColors.GlassBorder, thickness = 1.dp)
                    }
                    
                    // WebView Component
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    javaScriptCanOpenWindowsAutomatically = true
                                    // Use a clean mobile User-Agent to bypass Google's blocked webview detection
                                    userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
                                }
                                
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        webViewLoading = true
                                        if (url != null && url.contains("music.youtube.com")) {
                                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                                            if (cookies != null && (cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID") || cookies.contains("__Secure-1PAPISID"))) {
                                                YTMusicSession.setCookie(context, cookies)
                                                CookieManager.getInstance().flush()
                                                ytCookieDraft = cookies
                                                ytCookieConnected = true
                                                RecommendationManager.invalidateCache()
                                                context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                                                showYtWebViewLogin = false
                                                Toast.makeText(context, "Google YouTube Music Login Successful!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                    
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        webViewLoading = false
                                        if (url != null && url.contains("music.youtube.com")) {
                                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                                            if (cookies != null && (cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID") || cookies.contains("__Secure-1PAPISID"))) {
                                                YTMusicSession.setCookie(context, cookies)
                                                CookieManager.getInstance().flush()
                                                ytCookieDraft = cookies
                                                ytCookieConnected = true
                                                RecommendationManager.invalidateCache()
                                                context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                                                showYtWebViewLogin = false
                                                Toast.makeText(context, "Google YouTube Music Login Successful!", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                
                                // Load YouTube Music login page
                                loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F")
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer", color = VinColors.Primary) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Turn off playback automatically after the set time.", fontSize = 13.sp, color = VinColors.Secondary)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "Off" to 0,
                        "15 minutes" to 15,
                        "30 minutes" to 30,
                        "45 minutes" to 45,
                        "60 minutes" to 60
                    ).forEach { (label, minutes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sleepTimerMin == minutes) VinColors.White10 else Color.Transparent)
                                .clickable {
                                    sleepTimerMin = minutes
                                    prefs.edit().putInt("sleep_timer_min", minutes).apply()
                                    showSleepTimerDialog = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = VinColors.Primary, fontSize = 14.sp)
                            if (sleepTimerMin == minutes) {
                                Icon(Icons.Default.Check, null, tint = VinColors.AccentLight, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) {
                    Text("Close", color = VinColors.Secondary)
                }
            }
        )
    }
}

// ── Collapsible Section Sub-component ─────────────────────────────────────────
@Composable
fun CollapsibleSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f, 
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chevron_rotation"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(VinColors.Surface)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(VinColors.White10),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = VinColors.AccentLight, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = VinColors.Primary
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = VinColors.Secondary,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation)
            )
        }

        // Body Content
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(bottom = 12.dp))
                content()
            }
        }
    }
}

// ── UI Row Helpers ────────────────────────────────────────────────────────────

@Composable
fun SettingsToggle(
    title: String, 
    subtitle: String, 
    checked: Boolean, 
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(!checked) }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = VinColors.Primary)
            Text(subtitle, fontSize = 12.sp, color = VinColors.Secondary)
        }
        Switch(
            checked = checked, 
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, 
                checkedTrackColor = VinColors.Accent,
                uncheckedThumbColor = VinColors.Secondary,
                uncheckedTrackColor = VinColors.White10,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

@Composable
fun SettingsInfo(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = VinColors.Primary)
        Text(value, fontSize = 13.sp, color = VinColors.Secondary)
    }
}

@Composable
fun SettingsDropdown(
    title: String, 
    current: String, 
    options: List<String>, 
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 15.sp, color = VinColors.Primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current, fontSize = 13.sp, color = VinColors.Secondary)
            Icon(Icons.Default.ArrowDropDown, null, tint = VinColors.Secondary)
        }
        DropdownMenu(
            expanded = expanded, 
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(VinColors.Surface2)
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, color = VinColors.Primary) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

@Composable
fun SettingsSliderRow(
    label: String, 
    min: Float, 
    max: Float, 
    value: Float, 
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = VinColors.Secondary)
        Slider(
            value = value, 
            onValueChange = onChange, 
            valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = VinColors.Accent, 
                activeTrackColor = VinColors.Accent,
                inactiveTrackColor = VinColors.White10
            )
        )
    }
}
