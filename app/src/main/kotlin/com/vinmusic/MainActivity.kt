package com.vinmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.*
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.player.AuthViewModel
import com.vinmusic.ui.screens.*
import com.vinmusic.ui.theme.VinColors
import com.vinmusic.ui.theme.VinMusicTheme
import com.vinmusic.ui.components.BottomNavBar
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.sentry.compose.withSentryObservableEffect
import com.vinmusic.ui.components.MiniPlayer
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.ArtistItem
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.AlbumItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val playerVm: PlayerViewModel by viewModels()

    private var mediaControllerFuture: com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.MediaController>? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onDestroy() {
        // Release the MediaController connection acquired in onCreate.
        mediaControllerFuture?.let { future ->
            androidx.media3.session.MediaController.releaseFuture(future)
        }
        mediaControllerFuture = null
        super.onDestroy()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vinmusic.innertube.YTMusicApi.attachContext(this)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Prompt user once to disable battery optimizations to ensure stable background playback
        val batteryPrefs = getSharedPreferences("vin_music_prefs", MODE_PRIVATE)
        val hasPromptedBattery = batteryPrefs.getBoolean("has_prompted_battery_opt", false)
        if (!hasPromptedBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    batteryPrefs.edit().putBoolean("has_prompted_battery_opt", true).apply()
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("VIN_MAIN", "Failed to launch battery optimization ignore: ${e.message}")
                }
            }
        }
        
        // Connect MediaController to bind VinMusicService and activate media notifications
        try {
            val sessionToken = androidx.media3.session.SessionToken(
                this,
                android.content.ComponentName(this, com.vinmusic.player.VinMusicService::class.java)
            )
            mediaControllerFuture = androidx.media3.session.MediaController.Builder(this, sessionToken).buildAsync()
            // Released in onDestroy() — an unreleased controller leaks its service
            // binding on every Activity recreation.
        } catch (e: Exception) {
            android.util.Log.e("VIN_MAIN", "Failed to connect MediaController: ${e.message}")
        }
        
        enableEdgeToEdge()

        val authVm: AuthViewModel by viewModels()

        setContent {
            // Restore the appearance choice before the first composition.  The
            // settings screen persists Monet, but the theme state is process
            // local; without this initialization it silently reset after every
            // app restart.
            com.vinmusic.ui.theme.MonetState.enabled.value =
                getSharedPreferences("vin_music_prefs", MODE_PRIVATE)
                    .getBoolean("monet_enabled", false)
            VinMusicTheme {
                VinMusicApp(playerVm, authVm)
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun VinMusicApp(vm: PlayerViewModel, authVm: AuthViewModel) {
    val navController     = rememberNavController().withSentryObservableEffect()
    val currentBack       by navController.currentBackStackEntryAsState()
    val currentRoute      = currentBack?.destination?.route ?: "home"


    var showFullPlayer    by remember { mutableStateOf(false) }
    var selectedArtistForProfile by remember { mutableStateOf<ArtistItem?>(null) }
    var selectedAlbumForDetail by remember { mutableStateOf<AlbumItem?>(null) }
    var isArtistProfileLoading by remember { mutableStateOf(false) }
    var showSplashScreen  by remember { mutableStateOf(true) }
    var showExitDialog    by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val prefs = remember(context) { context.getSharedPreferences("vin_music_prefs", android.content.Context.MODE_PRIVATE) }
    val currentUser = authVm.currentUser
    var isLoggedIn by remember { mutableStateOf(prefs.getBoolean("is_logged_in", false) || currentUser != null) }

    LaunchedEffect(currentUser, authVm.authState) {
        if (currentUser != null || authVm.authState is AuthViewModel.AuthState.Authenticated) {
            isLoggedIn = true
        } else {
            isLoggedIn = prefs.getBoolean("is_logged_in", false)
        }
    }

    DisposableEffect(prefs, currentUser) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "is_logged_in") {
                isLoggedIn = prefs.getBoolean("is_logged_in", false) || authVm.currentUser != null
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var updateInfo by remember { mutableStateOf<com.vinmusic.update.UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        // Run update check on startup
        updateInfo = com.vinmusic.update.UpdateManager.checkUpdate()
    }

    var selectedSongForOptions by remember { mutableStateOf<VideoItem?>(null) }
    var selectedSongIsCached by remember { mutableStateOf(false) }
    var cachedListRefreshToken by remember { mutableIntStateOf(0) }
    var selectedPlaylistForOptions by remember { mutableStateOf<com.vinmusic.data.db.PlaylistEntity?>(null) }
    var showAddPlaylistGlobal by remember { mutableStateOf<VideoItem?>(null) }

    var playlistsGlobal by remember { mutableStateOf<List<com.vinmusic.data.db.PlaylistEntity>>(emptyList()) }
    var downloadsGlobal by remember { mutableStateOf<List<com.vinmusic.data.db.DownloadEntity>>(emptyList()) }

    val db = remember(context) { com.vinmusic.data.db.VinDatabase.getInstance(context) }

    LaunchedEffect(db) {
        launch(Dispatchers.IO) { db.playlistDao().getAllFlow().collect { playlistsGlobal = it } }
        launch(Dispatchers.IO) { db.downloadDao().getAllFlow().collect { downloadsGlobal = it } }
        // Pre-load Every Noise genre graph at startup (avoids freeze on first song play)
        launch(Dispatchers.IO) {
            try {
                com.vinmusic.recommendation.RecommendationManager.loadGenreGraph(context)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        showSplashScreen = false
    }

    // Gracefully handle hardware back gestures in the correct visual stack order
    BackHandler(enabled = showFullPlayer || selectedAlbumForDetail != null || selectedArtistForProfile != null) {
        when {
            showFullPlayer -> showFullPlayer = false
            selectedAlbumForDetail != null -> selectedAlbumForDetail = null
            selectedArtistForProfile != null -> selectedArtistForProfile = null
        }
    }

    // Intercept back button at the root level to show an exit confirmation dialog
    BackHandler(enabled = !showFullPlayer && selectedAlbumForDetail == null && selectedArtistForProfile == null && currentRoute == "home") {
        showExitDialog = true
    }

    if (showExitDialog) {
        val activity = context as? android.app.Activity
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = VinColors.Surface2,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
            title = {
                Text(text = "Exit Vin Music?", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            },
            text = {
                Text(text = "Do you really want to leave?", fontSize = 16.sp)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showExitDialog = false
                        activity?.finish()
                    }
                ) {
                    Text("Yes", color = VinColors.Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { showExitDialog = false }
                ) {
                    Text("No", color = Color.White, fontSize = 16.sp)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(com.vinmusic.ui.theme.Vin.Gradients.background)
    ) {
        // Shared blur state: NavHost content below is the "source", the mini
        // player + nav bar pills are the "effect" — this is what makes them
        // real frosted glass (blurring whatever's actually scrolling behind
        // them) instead of a flat translucent color pretending to be glass.
        val hazeState = remember { HazeState() }

        SharedTransitionLayout {
            Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                if (currentRoute != "discover") {
                    Column {
                        // Mini player above nav bar
                        if (vm.currentSong != null && !showFullPlayer) {
                            AnimatedVisibility(
                                visible = true,
                            ) {
                                MiniPlayer(
                                    vm = vm,
                                    animatedVisibilityScope = this@AnimatedVisibility,
                                    sharedTransitionScope = this@SharedTransitionLayout,
                                    hazeState = hazeState,
                                    onClick = { showFullPlayer = true }
                                )
                            }
                        }
                        // Bottom navigation
                        BottomNavBar(
                            currentRoute = currentRoute,
                            hazeState = hazeState,
                            onNavigate   = { route ->
                                if (route == "home") {
                                    // Single-click: collapse all overlays and bring user straight back to the clean home screen
                                    showFullPlayer = false
                                    selectedArtistForProfile = null
                                    selectedAlbumForDetail = null
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    navController.navigate(route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState    = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController    = navController,
                startDestination = "home",
                modifier         = Modifier
                    .padding(
                        top = padding.calculateTopPadding(),
                        bottom = 0.dp
                    )
                    .hazeSource(state = hazeState),
                enterTransition  = { fadeIn() + slideInHorizontally { it / 4 } },
                exitTransition   = { fadeOut() }
            ) {
                composable("home") {
                    HomeScreen(
                        vm = vm,
                        onSongClick = { song, songs ->
                            // Home playback must replace the queue with the current
                            // Home shelf, just like Search and Downloads do.
                            vm.setQueue(songs, songs.indexOf(song).coerceAtLeast(0))
                            showFullPlayer = true
                        },
                        onPlayQueue = { song, songs ->
                            vm.setQueue(songs, songs.indexOf(song))
                            showFullPlayer = true
                        },
                        onSearchClick = {
                            navController.navigate("search") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        onSettingsClick = {
                            navController.navigate("settings") {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState    = true
                            }
                        },
                        onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false },
                        onAlbumClick = { selectedAlbumForDetail = it },
                        onDiscoverClick = {
                            navController.navigate("discover") {
                                launchSingleTop = true
                            }
                        },
                        isPlayerOpen = showFullPlayer
                    )
                }
                composable("search") {
                    SearchScreen(
                        vm = vm,
                        onSongClick = { song, _ ->
                            vm.recordSearchClick(song)
                            vm.playSongWithRadio(song)
                            showFullPlayer = true
                        },
                        onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false },
                        onAlbumClick = { selectedAlbumForDetail = it },
                        isPlayerOpen = showFullPlayer
                    )
                }
                composable("library") {
                    LibraryScreen(
                        vm = vm,
                        onSongClick = { song, songs ->
                            vm.setQueue(songs, songs.indexOf(song))
                            showFullPlayer = true
                        },
                        onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false },
                        onPlaylistMore = { selectedPlaylistForOptions = it },
                        onPlaylistClick = { playlistId ->
                            navController.navigate("playlist_detail/$playlistId") {
                                launchSingleTop = true
                            }
                        },
                        onArtistClick = { selectedArtistForProfile = it },
                        onDnaClick = {
                            navController.navigate("stats") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
                composable("downloads") {
                    DownloadsScreen(
                        vm = vm,
                        onSongClick = { song, songs ->
                            vm.setQueue(songs, songs.indexOf(song))
                            showFullPlayer = true
                        },
                        onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false },
                        onCachedSongMore = { selectedSongForOptions = it; selectedSongIsCached = true },
                        cacheRefreshToken = cachedListRefreshToken
                    )
                }
                composable("settings") {
                    SettingsScreen(
                        vm = vm,
                        authVm = authVm,
                        onBack = { navController.popBackStack() },
                        onSongClick = { song, songs ->
                            vm.setQueue(songs, songs.indexOf(song))
                            showFullPlayer = true
                        }
                    )
                }
                composable("stats") {
                    MusicDnaScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("discover") {
                    DiscoverScreen(
                        vm = vm,
                        onBack = { navController.popBackStack() },
                        onSongClick = { song, _ ->
                            vm.playSongWithRadio(song)
                            showFullPlayer = true
                        }
                    )
                }
                composable("playlist_detail/{playlistId}") { backStackEntry ->
                    val playlistId = backStackEntry.arguments?.getString("playlistId")?.toLongOrNull()
                    if (playlistId != null) {
                        PlaylistDetailScreen(
                            playlistId = playlistId,
                            vm = vm,
                            onBack = { navController.popBackStack() },
                            onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false }
                        )
                    }
                }
            }
        }

        // Full-screen player overlay
        AnimatedVisibility(
            visible = showFullPlayer && vm.currentSong != null,
            enter   = slideInVertically { it },
            exit    = slideOutVertically { it }
        ) {
            FullPlayerScreen(
                vm = vm,
                animatedVisibilityScope = this@AnimatedVisibility,
                sharedTransitionScope = this@SharedTransitionLayout,
                onArtistNameClick = { artistName ->
                    // Close the player sheet
                    showFullPlayer = false
                    // Start loading artist profile
                    isArtistProfileLoading = true
                    scope.launch(Dispatchers.IO) {
                        try {
                            val cleanArtist = artistName.replace("-topic", "", ignoreCase = true)
                                .replace("- topic", "", ignoreCase = true).trim()
                            val primaryArtist = cleanArtist.split(Regex("""\s*(?:feat\.?|ft\.?|&|,|and)\s*""", RegexOption.IGNORE_CASE))
                                .map { it.trim() }.filter { it.isNotEmpty() }.firstOrNull() ?: cleanArtist

                            val results = InnerTube.searchAll(primaryArtist)
                            val matchedArtist = results.artists.firstOrNull {
                                it.name.equals(primaryArtist, ignoreCase = true)
                            } ?: results.artists.firstOrNull()
                              ?: ArtistItem(channelId = "", name = primaryArtist, thumbnail = "")

                            withContext(Dispatchers.Main) {
                                selectedArtistForProfile = matchedArtist
                                isArtistProfileLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Error resolving artist profile", android.widget.Toast.LENGTH_SHORT).show()
                                isArtistProfileLoading = false
                            }
                        }
                    }
                },
                onAddToPlaylist = { showAddPlaylistGlobal = it },
                onClose = { showFullPlayer = false }
            )
        }

        } // End of SharedTransitionLayout

        // Global slide-over ArtistProfileScreen overlay
        AnimatedVisibility(
            visible = selectedArtistForProfile != null,
            enter   = slideInHorizontally { it },
            exit    = slideOutHorizontally { it }
        ) {
            selectedArtistForProfile?.let { artist ->
                ArtistProfileScreen(
                    artist = artist,
                    vm = vm,
                    onBack = { selectedArtistForProfile = null },
                    onSongClick = { song, _ ->
                        vm.playSongWithRadio(song)
                        showFullPlayer = true
                    },
                    onAlbumClick = { selectedAlbumForDetail = it }
                )
            }
        }

        // Global slide-over AlbumDetailScreen overlay
        AnimatedVisibility(
            visible = selectedAlbumForDetail != null,
            enter   = slideInHorizontally { it },
            exit    = slideOutHorizontally { it }
        ) {
            selectedAlbumForDetail?.let { album ->
                AlbumDetailScreen(
                    album = album,
                    vm = vm,
                    onBack = { selectedAlbumForDetail = null },
                    onSongClick = { song, songs ->
                        vm.setQueue(songs, songs.indexOf(song))
                        showFullPlayer = true
                    },
                    onSongMore = { selectedSongForOptions = it; selectedSongIsCached = false }
                )
            }
        }

        // Frosted loading overlay for artist profile resolution
        if (isArtistProfileLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = true,
                        onClick = {}
                    ),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.padding(32.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = VinColors.Surface2),
                    border = BorderStroke(1.dp, VinColors.GlassBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = VinColors.Accent,
                            modifier = Modifier.size(44.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "Resolving Artist Profile...",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── Global Song Options Sheet ──────────────────────────────────────────
        selectedSongForOptions?.let { song ->
            val isLiked = song.videoId in vm.likedSongs
            val isDownloaded = !selectedSongIsCached && downloadsGlobal.any { it.videoId == song.videoId && it.status == "completed" }

            com.vinmusic.ui.components.SongOptionsSheet(
                song = song,
                isLiked = isLiked,
                isDownloaded = isDownloaded,
                onLikeToggle = { vm.toggleLike(song) },
                onAddToPlaylist = { showAddPlaylistGlobal = song },
                onPlayNext = { vm.playNextInQueue(song) },
                onAddToQueue = { vm.addToEndOfQueue(song) },
                onRemoveCache = if (selectedSongIsCached) {
                    {
                        scope.launch(Dispatchers.IO) {
                            try {
                                com.vinmusic.player.PlayerSingleton.getCache(context)?.removeResource(song.videoId)
                            } finally {
                                withContext(Dispatchers.Main) {
                                    cachedListRefreshToken++
                                    android.widget.Toast.makeText(context, "Cache removed", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } else null,
                onDownloadToggle = {
                    if (isDownloaded) {
                        scope.launch(Dispatchers.IO) {
                            val intent = android.content.Intent(context, com.vinmusic.download.DownloadService::class.java).apply {
                                action = com.vinmusic.download.DownloadService.ACTION_CANCEL
                                putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                            }
                            context.startService(intent)

                            val cache = com.vinmusic.player.PlayerSingleton.getCache(context)
                            cache?.removeResource(song.videoId)
                            val downloadCache = com.vinmusic.player.PlayerSingleton.getDownloadCache(context)
                            downloadCache?.removeResource(song.videoId)

                            // Clean up thumbnail to save space
                            try {
                                val dlEntity = db.downloadDao().get(song.videoId)
                                dlEntity?.thumbnailPath?.let { path ->
                                    val file = java.io.File(path)
                                    if (file.exists()) file.delete()
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("VIN_MAIN", "Failed to delete offline thumbnail: ${e.message}")
                            }

                            db.downloadDao().delete(song.videoId)
                            db.interactionSignalDao().updateDownloaded(song.videoId, false)
                        }
                    } else {
                        val intent = android.content.Intent(context, com.vinmusic.download.DownloadService::class.java).apply {
                            action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                            putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                            putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, song.title)
                            putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, song.author)
                            putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, song.durationText)
                            putExtra(com.vinmusic.download.DownloadService.EXTRA_THUMBNAIL, song.thumbnail)
                        }
                        context.startService(intent)
                    }
                },
                onShare = {
                    val shareIntent = android.content.Intent().apply {
                        action = android.content.Intent.ACTION_SEND
                        putExtra(android.content.Intent.EXTRA_TEXT, "Listen to '${song.title}' by ${song.author}: https://www.youtube.com/watch?v=${song.videoId}")
                        type = "text/plain"
                    }
                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Song"))
                },
                onDismiss = {
                    selectedSongForOptions = null
                    selectedSongIsCached = false
                }
            )
        }

        // ── Global Playlist Options Sheet ──────────────────────────────────────
        selectedPlaylistForOptions?.let { pl ->
            com.vinmusic.ui.components.PlaylistOptionsSheet(
                playlistName = pl.name,
                isPinned = pl.isPinned,
                onTogglePin = {
                    scope.launch(Dispatchers.IO) {
                        db.playlistDao().togglePin(pl.id)
                    }
                },
                onDownloadPlaylist = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val songs = db.playlistDao().getSongsFlow(pl.id).first()
                            songs.forEach { song ->
                                val intent = android.content.Intent(context, com.vinmusic.download.DownloadService::class.java).apply {
                                    action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                                    putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                                    putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, song.title)
                                    putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, song.author)
                                    putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, song.durationText)
                                    putExtra(com.vinmusic.download.DownloadService.EXTRA_THUMBNAIL, "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg")
                                }
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("VIN_MAIN", "Error downloading playlist: ${e.message}")
                        }
                    }
                },
                onDeletePlaylist = {
                    scope.launch(Dispatchers.IO) {
                        db.playlistDao().deletePlaylistWithSongs(pl.id)
                    }
                },
                onDismiss = { selectedPlaylistForOptions = null }
            )
        }

        // ── Global Add to Playlist Dialog ──────────────────────────────────────
        showAddPlaylistGlobal?.let { song ->
            com.vinmusic.ui.components.AddToPlaylistDialog(
                playlists = playlistsGlobal,
                onCreatePlaylist = { name ->
                    scope.launch(Dispatchers.IO) {
                        val newId = db.playlistDao().insertPlaylist(
                            com.vinmusic.data.db.PlaylistEntity(name = name)
                        )
                        db.playlistDao().insertSong(
                            com.vinmusic.data.db.PlaylistSongEntity(
                                playlistId = newId,
                                videoId = song.videoId,
                                title = song.title,
                                author = song.author,
                                durationText = song.durationText
                            )
                        )
                    }
                    showAddPlaylistGlobal = null
                },
                onPlaylistSelected = { pl ->
                    scope.launch(Dispatchers.IO) {
                        db.playlistDao().insertSong(
                            com.vinmusic.data.db.PlaylistSongEntity(
                                playlistId = pl.id,
                                videoId = song.videoId,
                                title = song.title,
                                author = song.author,
                                durationText = song.durationText
                            )
                        )
                    }
                    showAddPlaylistGlobal = null
                },
                onDismiss = { showAddPlaylistGlobal = null }
            )
        }
        
        // Update Dialog
        updateInfo?.let { info ->
            if (info.latestVersionCode > BuildConfig.VERSION_CODE) {
                com.vinmusic.ui.components.UpdateDialog(
                    updateInfo = info,
                    onUpdateClick = {
                        com.vinmusic.update.UpdateManager.downloadAndInstall(context, info)
                        if (!info.forceUpdate) {
                            updateInfo = null
                        }
                    },
                    onLaterClick = {
                        updateInfo = null
                    }
                )
            }
        }

        // Premium Auth Screen Gate covering the app for guest users
        AnimatedVisibility(
            visible = !showSplashScreen && !isLoggedIn,
            enter = fadeIn(animationSpec = tween(500)),
            exit = fadeOut(animationSpec = tween(500))
        ) {
            AuthScreen(
                authVm = authVm,
                onLoginSuccess = {
                    prefs.edit().putBoolean("is_logged_in", true).apply()
                }
            )
        }

        // Premium Splash Screen Overlay with smooth scale & fade out exit animation
        AnimatedVisibility(
            visible = showSplashScreen,
            enter = fadeIn(),
            exit = fadeOut(animationSpec = tween(600, easing = FastOutSlowInEasing)) +
                   scaleOut(targetScale = 1.12f, animationSpec = tween(600, easing = FastOutSlowInEasing))
        ) {
            SplashScreen()
        }
    }
}
