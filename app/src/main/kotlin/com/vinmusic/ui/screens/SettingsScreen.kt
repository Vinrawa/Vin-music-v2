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
import androidx.compose.ui.draw.scale
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
import com.vinmusic.player.EQ_PRESETS
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

    var showEqDialog by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }



    var skipSilence by remember { mutableStateOf(prefs.getBoolean("skip_silence", false)) }
    var streamingQuality by remember { mutableStateOf(prefs.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)") }

    var showYtCookieDialog by remember { mutableStateOf(false) }
    var ytCookieDraft by remember {
        mutableStateOf(YTMusicSession.getCookie(ctx).orEmpty())
    }
    var ytCookieConnected by remember { mutableStateOf(YTMusicSession.hasCookie(ctx)) }
    var showYtLoginOptionsDialog by remember { mutableStateOf(false) }
    var showYtWebViewLogin by remember { mutableStateOf(false) }

    // YTM login flow has two modes:
    //   LANDING  — native Google account picker card (no password yet)
    //   WEBVIEW  — the cookie-capturing WebView, with optional email pre-fill
    // After picking a Google account via the system AccountManager picker,
    // we open the WebView with &Email={account} pre-filled so the user only
    // enters a password. See docs/superpowers/specs/2026-06-20-native-google-account-picker-design.md
    var ytLoginMode by remember { mutableStateOf(YtLoginMode.LANDING) }
    var ytPrefilledEmail by remember { mutableStateOf<String?>(null) }

    val googleAccountPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val accountName = result.data
                ?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
                ?.trim()
            if (!accountName.isNullOrBlank()) {
                ytPrefilledEmail = accountName
                ytLoginMode = YtLoginMode.WEBVIEW
            }
        }
        // Cancelled picker: stay on LANDING, no state change.
    }

    // Display-only account email + count, refreshed via ytConnectionVersion.
    // Keying the LaunchedEffect on the integer version (not the boolean) is
    // deliberate: switch-account and manual-paste both re-set the cookie while
    // already connected (true -> true), which wouldn't refire a boolean effect.
    var ytAccountEmail by remember { mutableStateOf<String?>(null) }
    var ytAccountCount by remember { mutableStateOf(1) }
    var ytConnectionVersion by remember { mutableStateOf(0) }

    LaunchedEffect(ytConnectionVersion) {
        if (ytCookieConnected) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val info = com.vinmusic.innertube.YTMusicApi.getAccountInfo(ctx)
                ytAccountEmail = info.email
                ytAccountCount = info.count.coerceAtLeast(1)
            }
        } else {
            ytAccountEmail = null
            ytAccountCount = 1
        }
    }

    // Clears Google-domain cookies so the WebView shows the account chooser
    // instead of silently re-logging-in with the cached account.
    //
    // Async-race-safe: removeAllCookies(callback) defers showYtWebViewLogin
    // until the clear completes — calling it synchronously would let the
    // WebView load before the store is cleared (silent re-login, the exact
    // bug this helper exists to fix).
    val switchAccount = {
        val cm = CookieManager.getInstance()
        ytCookieConnected = false
        ytAccountEmail = null
        // Start at the account picker on every new login attempt, so
        // switching accounts doesn't silently resume the cached one.
        ytLoginMode = YtLoginMode.LANDING
        ytPrefilledEmail = null
        // Safety timeout: if removeAllCookies callback never fires (known
        // WebView bug on some OEMs), open the login dialog anyway after 500ms.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!showYtWebViewLogin) showYtWebViewLogin = true
        }, 500)
        cm.removeAllCookies { _ ->
            cm.flush()
            showYtWebViewLogin = true
        }
    }

    var topPlayedSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var totalListeningMinutes by remember { mutableIntStateOf(0) }
    var dailyStreak by remember { mutableIntStateOf(0) }
    var totalSongsPlayed by remember { mutableIntStateOf(0) }

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

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.vinmusic.update.UpdateInfo?>(null) }

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
        scope.launch(Dispatchers.IO) {
            try {
                val signals = db.interactionSignalDao().getAll()
                val history = db.historyDao().getAllHistory()
                
                // Total songs played (unique)
                val songsPlayed = history.size
                
                // Estimate total listening time from play counts and durations
                var totalSeconds = 0L
                for (sig in signals) {
                    val durationParts = sig.durationText.split(":")
                    val durationSecs = when (durationParts.size) {
                        2 -> (durationParts[0].toIntOrNull() ?: 0) * 60 + (durationParts[1].toIntOrNull() ?: 0)
                        3 -> (durationParts[0].toIntOrNull() ?: 0) * 3600 + (durationParts[1].toIntOrNull() ?: 0) * 60 + (durationParts[2].toIntOrNull() ?: 0)
                        else -> 180 // default 3 min
                    }
                    totalSeconds += durationSecs.toLong() * sig.playCount
                }
                val minutes = (totalSeconds / 60).toInt()
                
                // Calculate daily streak from history timestamps
                val cal = java.util.Calendar.getInstance()
                val dayFormat = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
                val activeDays = history.map { dayFormat.format(java.util.Date(it.playedAt)) }.toSet().sorted().reversed()
                
                var streak = 0
                val today = dayFormat.format(java.util.Date())
                var expectedDay = today
                for (day in activeDays) {
                    if (day == expectedDay) {
                        streak++
                        cal.time = dayFormat.parse(day)!!
                        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
                        expectedDay = dayFormat.format(cal.time)
                    } else if (day < expectedDay) {
                        break
                    }
                }
                
                scope.launch(Dispatchers.Main) {
                    totalListeningMinutes = minutes
                    dailyStreak = streak
                    totalSongsPlayed = songsPlayed
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
                                modifier = Modifier.fillMaxSize().scale(1.35f),
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

        // ── Listening Stats ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Listening Time Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VinColors.Surface)
                    .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.AccessTime, null, tint = VinColors.AccentLight, modifier = Modifier.size(20.dp))
                    Text(
                        text = if (totalListeningMinutes >= 60) "${totalListeningMinutes / 60}h ${totalListeningMinutes % 60}m" else "${totalListeningMinutes}m",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = VinColors.Primary
                    )
                    Text("Listening Time", fontSize = 11.sp, color = VinColors.Secondary)
                }
            }
            
            // Daily Streak Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VinColors.Surface)
                    .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.LocalFireDepartment, null, tint = Color(0xFFFF8C42), modifier = Modifier.size(20.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "$dailyStreak",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = VinColors.Primary
                        )
                        Text("days", fontSize = 13.sp, color = VinColors.Secondary, modifier = Modifier.padding(bottom = 2.dp))
                    }
                    Text("Daily Streak", fontSize = 11.sp, color = VinColors.Secondary)
                }
            }
            
            // Songs Played Card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VinColors.Surface)
                    .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.MusicNote, null, tint = VinColors.Accent, modifier = Modifier.size(20.dp))
                    Text(
                        text = "$totalSongsPlayed",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = VinColors.Primary
                    )
                    Text("Songs Played", fontSize = 11.sp, color = VinColors.Secondary)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Connect to YouTube Music (single consolidated card) ─────────────
        // Replaces the old two-option dialog (WebView "Sign In with Google" +
        // "Manual Cookie Setup"). Cloud Sync lives in its own separate card
        // below and is untouched by this.
        var showAdvancedCookie by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF1E1A14), Color(0xFF101010))
                    )
                )
                .border(1.dp, VinColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
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
                            .background(VinColors.Accent.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = VinColors.Accent, modifier = Modifier.size(28.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Connect to YouTube Music",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )
                        val subtitle = if (ytCookieConnected) {
                            val label = ytAccountEmail ?: "YouTube Music connected"
                            if (ytAccountCount > 1 && ytAccountEmail != null) "$label (1 of $ytAccountCount)" else label
                        } else {
                            "Unlock your YTM home, library and liked songs"
                        }
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            lineHeight = 16.sp
                        )
                    }
                }

                if (!ytCookieConnected) {
                    Button(
                        onClick = {
                            ytLoginMode = YtLoginMode.LANDING
                            ytPrefilledEmail = null
                            showYtWebViewLogin = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                // CONSCIOUS DECISION: Disconnect only clears the
                                // app's stored cookie — NOT WebView-level Google
                                // cookies. Distinct from Switch account (which
                                // DOES clear them to force the chooser). Disconnect
                                // = "stop using this session"; reconnecting may
                                // silently resume the same account.
                                YTMusicSession.setCookie(ctx, null)
                                ytCookieConnected = false
                                RecommendationManager.invalidateCache()
                                Toast.makeText(ctx, "Disconnected YouTube Music", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Disconnect", fontSize = 12.sp, color = VinColors.Primary)
                        }
                        Button(
                            onClick = switchAccount,
                            colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Switch account", fontSize = 12.sp, color = VinColors.Primary)
                        }
                    }
                }

                // Advanced disclosure: manual cookie paste (power users).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedCookie = !showAdvancedCookie }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        if (showAdvancedCookie) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        null, tint = VinColors.Secondary, modifier = Modifier.size(18.dp)
                    )
                    Text(
                        if (showAdvancedCookie) "Hide advanced" else "Advanced (paste cookie)",
                        fontSize = 12.sp, color = VinColors.Secondary
                    )
                }
                if (showAdvancedCookie) {
                    OutlinedTextField(
                        value = ytCookieDraft,
                        onValueChange = { ytCookieDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("Paste music.youtube.com cookie...", color = VinColors.Secondary, fontSize = 12.sp)
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = VinColors.Primary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VinColors.Accent,
                            unfocusedBorderColor = VinColors.GlassBorder,
                            focusedContainerColor = VinColors.White10,
                            unfocusedContainerColor = VinColors.White10
                        )
                    )
                    Button(
                        onClick = {
                            YTMusicSession.setCookie(ctx, ytCookieDraft)
                            ytCookieConnected = true
                            ytConnectionVersion++   // refire email fetch (true→true case)
                            RecommendationManager.invalidateCache()
                            Toast.makeText(ctx, "YouTube Music connected", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Save cookie", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ── Google Cloud Sync Card ──
        val currentUser = authVm.currentUser
        val cloudConnected = currentUser != null || authVm.authState is AuthViewModel.AuthState.Authenticated
        val syncState = authVm.syncState
        val lastSyncMessage = authVm.lastSyncMessage
        
        val googleSignInClient = remember(ctx) { authVm.getGoogleSignInClient(ctx) }
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
                    if (account != null) {
                        authVm.signInWithGoogle(account)
                    }
                } catch (e: com.google.android.gms.common.api.ApiException) {
                    val message = "Google Sign-In failed (${e.statusCode}). Please try again."
                    authVm.reportGoogleSignInError(message)
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }
            } else if (result.resultCode != android.app.Activity.RESULT_OK) {
                authVm.reportGoogleSignInError("Google sign-in was cancelled.")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF191612), Color(0xFF0A0A0A))
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
                            text = if (cloudConnected) "Backup linked to ${currentUser?.email ?: "Google account"}"
                                   else "Tap Connect to backup your playlists & likes",
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            lineHeight = 16.sp
                        )
                    }
                    if (!cloudConnected) {
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

                if (cloudConnected) {
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
                    Text(if (vm.eqEnabled) "On" else "Off", fontSize = 13.sp, color = VinColors.Secondary)
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
                    Text(if (vm.sleepTimerMinutes > 0) "${vm.sleepTimerMinutes} mins" else "Off", fontSize = 13.sp, color = VinColors.Secondary)
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
                    "YouTube Music" -> "YouTube Music Only"
                    "Unison" -> "Unison Only"
                    "LrcLib" -> "LRCLIB Only"
                    else -> "Auto (Recommended)"
                },
                options = listOf("Auto (Recommended)", "Unison Only", "YouTube Music Only", "LRCLIB Only")
            ) { selected ->
                val code = when(selected) {
                    "YouTube Music Only" -> "YouTube Music"
                    "Unison Only" -> "Unison"
                    "LRCLIB Only" -> "LrcLib"
                    else -> "Auto"
                }
                lyricsProvider = code
                prefs.edit().putString("lyrics_provider", code).apply()
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Source Priority", value = "Unison → LrcLib → YouTube Music → Genius (when Auto)")
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
            // Check for Updates
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCheckingUpdate) {
                        isCheckingUpdate = true
                        scope.launch {
                            try {
                                val info = com.vinmusic.update.UpdateManager.checkUpdate()
                                if (info != null && info.latestVersionCode > com.vinmusic.BuildConfig.VERSION_CODE) {
                                    updateInfo = info
                                    showUpdateDialog = true
                                } else {
                                    Toast.makeText(ctx, "You're on the latest version ✓", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Couldn't check for updates. Try again later.", Toast.LENGTH_SHORT).show()
                            } finally {
                                isCheckingUpdate = false
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Check for Updates", fontSize = 15.sp, color = VinColors.Primary)
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = VinColors.Accent
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Check for updates",
                        tint = VinColors.Secondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Streaming", value = "Multi-client InnerTube (6 fallbacks)")
            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(vertical = 4.dp))
            SettingsInfo(title = "Built with", value = "Kotlin • Jetpack Compose • ExoPlayer")
        }

        Spacer(Modifier.height(220.dp))
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
                        checked = vm.eqEnabled,
                        onCheckedChange = {
                            vm.eqEnabled = it
                            vm.applyEQ()
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
                    Text("Presets", fontSize = 12.sp, color = VinColors.Secondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EQ_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = vm.eqPreset == preset.name,
                                onClick = { vm.eqEnabled = true; vm.applyPreset(preset) },
                                label = { Text(preset.name, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = VinColors.Accent.copy(alpha = 0.25f),
                                    selectedLabelColor = VinColors.AccentLight,
                                    labelColor = VinColors.Secondary
                                )
                            )
                        }
                    }
                    listOf(
                        "60 Hz" to vm.eqSubBass to { v: Float -> vm.eqSubBass = v; vm.applyEQ() },
                        "230 Hz" to vm.eqBass to { v: Float -> vm.eqBass = v; vm.applyEQ() },
                        "910 Hz" to vm.eqLowMid to { v: Float -> vm.eqLowMid = v; vm.applyEQ() },
                        "4 kHz" to vm.eqMid to { v: Float -> vm.eqMid = v; vm.applyEQ() },
                        "8 kHz" to vm.eqTreble to { v: Float -> vm.eqTreble = v; vm.applyEQ() },
                        "16 kHz" to vm.eqAir to { v: Float -> vm.eqAir = v; vm.applyEQ() }
                    ).forEach { item ->
                        val pair = item.first
                        val onValChange = item.second
                        val label = pair.first
                        val valFloat = pair.second
                        Column(modifier = Modifier.alpha(if (vm.eqEnabled) 1.0f else 0.5f)) {
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
                                enabled = vm.eqEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = VinColors.Accent,
                                    activeTrackColor = VinColors.Accent,
                                    inactiveTrackColor = VinColors.White10
                                )
                            )
                        }
                    }
                    Text("Effects", fontSize = 12.sp, color = VinColors.Secondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = vm.concertHallEnabled,
                            onClick = { vm.updateConcertHallEnabled(!vm.concertHallEnabled) },
                            label = { Text("Concert Hall", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VinColors.Accent.copy(alpha = 0.25f),
                                selectedLabelColor = VinColors.AccentLight,
                                labelColor = VinColors.Secondary
                            )
                        )
                        FilterChip(
                            selected = vm.audioNormalizationEnabled,
                            onClick = {
                                vm.audioNormalizationEnabled = !vm.audioNormalizationEnabled
                                vm.applyEQ()
                            },
                            label = { Text("Loudness", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VinColors.Accent.copy(alpha = 0.25f),
                                selectedLabelColor = VinColors.AccentLight,
                                labelColor = VinColors.Secondary
                            )
                        )
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

    // ── YouTube Music WebView Login ─────────────────────────────────────────
    // (The old showYtCookieDialog + showYtLoginOptionsDialog blocks were removed
    // — manual cookie paste is now inline in the card's "Advanced" disclosure,
    // and the two-option picker is replaced by the single Connect card above.)

    if (showYtWebViewLogin) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showYtWebViewLogin = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = VinColors.BgColor
            ) {
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
                        IconButton(onClick = {
                            // Back from WebView returns to the picker; close only from LANDING.
                            if (ytLoginMode == YtLoginMode.WEBVIEW) {
                                ytLoginMode = YtLoginMode.LANDING
                                ytPrefilledEmail = null
                            } else {
                                showYtWebViewLogin = false
                            }
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }

                        Text(
                            text = "Connect YouTube Music",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )

                        Spacer(Modifier.width(48.dp))
                    }

                    HorizontalDivider(color = VinColors.GlassBorder, thickness = 1.dp)

                    when (ytLoginMode) {
                        YtLoginMode.LANDING -> YtLoginLanding(
                            onPickAccount = {
                                // System-mediated picker — no GET_ACCOUNTS permission needed.
                                // The OS returns only the account the user explicitly selects.
                                // Note: the (Account, List<Account>, String[], boolean, String, String, String[], Bundle)
                                // overload is the deprecated-but-stable cross-SDK variant; the newer
                                // one without `alwaysPromptForAccount` is API 23+ only and changes signature.
                                @Suppress("DEPRECATION")
                                val intent = android.accounts.AccountManager.newChooseAccountIntent(
                                    /* selectedAccount       = */ null,
                                    /* selectableAccounts    = */ null,
                                    /* allowableAccountTypes = */ null,
                                    /* alwaysPromptForAccount= */ false,
                                    /* descriptionOverride   = */ null,
                                    /* addAccountAuthType    = */ null,
                                    /* addAccountFeatures    = */ null,
                                    /* addAccountOptions     = */ null
                                )
                                googleAccountPicker.launch(intent)
                            },
                            onManualEmail = {
                                ytPrefilledEmail = null
                                ytLoginMode = YtLoginMode.WEBVIEW
                            }
                        )
                        YtLoginMode.WEBVIEW -> YtLoginWebView(
                            prefillEmail = ytPrefilledEmail,
                            onConnected = { cookies, context ->
                                YTMusicSession.setCookie(context, cookies)
                                CookieManager.getInstance().flush()
                                ytCookieDraft = cookies
                                ytCookieConnected = true
                                ytConnectionVersion++   // refire email fetch (true→true case)
                                RecommendationManager.invalidateCache()
                                context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                                showYtWebViewLogin = false
                                Toast.makeText(context, "YouTube Music connected", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
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
                        val isSelected = when (minutes) {
                            0 -> vm.sleepTimerMinutes == 0
                            15 -> vm.sleepTimerMinutes in 1..15
                            30 -> vm.sleepTimerMinutes in 16..30
                            45 -> vm.sleepTimerMinutes in 31..45
                            60 -> vm.sleepTimerMinutes in 46..60
                            else -> false
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) VinColors.White10 else Color.Transparent)
                                .clickable {
                                    if (minutes == 0) {
                                        vm.cancelSleepTimer()
                                    } else {
                                        vm.setSleepTimer(minutes)
                                    }
                                    showSleepTimerDialog = false
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = VinColors.Primary, fontSize = 14.sp)
                            if (isSelected) {
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

    // ── Update Available Dialog ──────────────────────────────────────────────────
    val currentUpdateInfo = updateInfo
    if (showUpdateDialog && currentUpdateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = {
                Text(
                    "Update Available",
                    color = VinColors.Primary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "v${currentUpdateInfo.latestVersionName ?: "?"}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = VinColors.Accent
                    )
                    if (!currentUpdateInfo.releaseNotes.isNullOrBlank()) {
                        Text(
                            currentUpdateInfo.releaseNotes!!,
                            fontSize = 13.sp,
                            color = VinColors.Secondary,
                            lineHeight = 18.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    com.vinmusic.update.UpdateManager.downloadAndInstall(ctx, currentUpdateInfo)
                }) {
                    Text("Download", color = VinColors.Accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) {
                    Text("Later", color = VinColors.Secondary)
                }
            },
            containerColor = VinColors.Surface2,
            shape = RoundedCornerShape(20.dp)
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

// ── YouTube Music login: native picker + pre-filled WebView ──────────────────

private enum class YtLoginMode { LANDING, WEBVIEW }

/**
 * Landing card shown when the user first opens the YTM login dialog.
 *
 * Offers the native OS account picker (no password, no email typing) as the
 * primary path, with manual WebView login as a graceful fallback.
 *
 * The OS picker is system-mediated — we never touch the account list, so no
 * GET_ACCOUNTS permission is required (Android 5.0+).
 */
@Composable
private fun YtLoginLanding(
    onPickAccount: () -> Unit,
    onManualEmail: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Google "G" mark — simple vector, no asset dependency.
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text("G", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Sign in to YouTube Music",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = VinColors.Primary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a Google account on your phone to continue. You'll only need to enter your password.",
            fontSize = 13.sp,
            color = VinColors.Secondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(28.dp))

        // Primary: native account picker
        Button(
            onClick = onPickAccount,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue with Google", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.Black)
        }

        Spacer(Modifier.height(14.dp))

        // Fallback: skip the picker, type email in the WebView directly
        TextButton(onClick = onManualEmail) {
            Text("Enter email manually", fontSize = 13.sp, color = VinColors.Secondary)
        }
    }
}

/**
 * Cookie-capturing WebView for YTM login. Opens the ServiceLogin page with the
 * selected Google account's email pre-filled (`&Email=...`) when available, so
 * the user only needs to enter a password. If Google's login flow ignores the
 * param (it sometimes does on the newer flow), login still works — the user
 * just types the email. No worse than the previous behavior.
 *
 * `key`ing the AndroidView on [prefillEmail] ensures a fresh WebView is built
 * (and the correct URL loaded) when switching from manual to a picked account.
 */
@Composable
private fun YtLoginWebView(
    prefillEmail: String?,
    onConnected: (cookies: String, context: android.content.Context) -> Unit
) {
    var webViewLoading by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (webViewLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = VinColors.AccentLight,
                trackColor = VinColors.Surface
            )
        }

        // Re-create the WebView when pre-fill changes so the correct URL loads.
        key(prefillEmail ?: "manual") {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                        // Clean mobile User-Agent to bypass Google's blocked-webview detection.
                        userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"
                    }

                    webViewClient = object : WebViewClient() {
                        // Dedupe cookie capture across onPageStarted + onPageFinished
                        // for the same navigation — otherwise the email fetch fires twice.
                        var captureHandledForUrl: String? = null

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            webViewLoading = true
                            captureConnection(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            webViewLoading = false
                            captureConnection(url)
                        }

                        fun captureConnection(url: String?) {
                            if (url == null || !url.contains("music.youtube.com")) return
                            if (url == captureHandledForUrl) return
                            val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com")
                            if (cookies != null && (cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID") || cookies.contains("__Secure-1PAPISID"))) {
                                captureHandledForUrl = url
                                onConnected(cookies, context)
                            }
                        }
                    }

                    val baseUrl = "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
                    val fullUrl = if (!prefillEmail.isNullOrBlank()) {
                        // Google's login page accepts &Email= for pre-fill. Best-effort:
                        // ignored on some newer flows, but harmless when it is.
                        baseUrl + "&Email=" + java.net.URLEncoder.encode(prefillEmail, "UTF-8")
                    } else baseUrl
                    loadUrl(fullUrl)
                }
            }
            )
        }
    }
}
