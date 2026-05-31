package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.InnerTube
import com.vinmusic.lyrics.LyricsResult
import com.vinmusic.player.*
import com.vinmusic.ui.theme.VinColors
import com.vinmusic.ui.utils.ColorExtractor
import androidx.compose.animation.core.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    vm: PlayerViewModel,
    onArtistNameClick: (String) -> Unit,
    onAddToPlaylist: (VideoItem) -> Unit,
    onClose: () -> Unit
) {
    val song = vm.currentSong ?: return
    val ctx = LocalContext.current
    val db = com.vinmusic.data.db.VinDatabase.getInstance(ctx)

    var activePanel      by remember { mutableStateOf<String?>(null) }
    
    // Dynamic Color Harmonization Palette State
    var currentPalette by remember(song.videoId) {
        mutableStateOf(
            ColorExtractor.MusicPalette(
                gradTop = Color(0x336338EC),
                gradMid = Color(0x1F6338EC),
                gradBottom = Color(0xFF0E0E11),
                accent = Color(0xFF6338EC)
            )
        )
    }

    LaunchedEffect(song.thumbnailHd) {
        val extracted = ColorExtractor.extractColorsFromUrl(ctx, song.thumbnailHd)
        currentPalette = extracted
    }

    // 1-second ultra-smooth transition crossfades for active gradients/shadows
    val animatedGradTop by animateColorAsState(
        targetValue = currentPalette.gradTop,
        animationSpec = tween(durationMillis = 1000),
        label = "animatedGradTop"
    )
    val animatedGradMid by animateColorAsState(
        targetValue = currentPalette.gradMid,
        animationSpec = tween(durationMillis = 1000),
        label = "animatedGradMid"
    )
    val animatedAccent by animateColorAsState(
        targetValue = currentPalette.accent,
        animationSpec = tween(durationMillis = 1000),
        label = "animatedAccent"
    )
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showSleepDialog  by remember { mutableStateOf(false) }
    var showAddPlaylist  by remember { mutableStateOf(false) }

    // Breathtaking Infinite transition for circular vinyl rotation & ambient breathing glow
    val infiniteTransition = rememberInfiniteTransition(label = "disc_effects")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    var isDownloaded by remember(song.videoId) { mutableStateOf(false) }
    LaunchedEffect(song.videoId) {
        val existing = withContext(Dispatchers.IO) { db.downloadDao().get(song.videoId) }
        isDownloaded = existing != null && existing.status == "completed"
    }

    var similarSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isSimilarSongsLoading by remember { mutableStateOf(false) }

    LaunchedEffect(song.videoId) {
        similarSongs = emptyList()
        isSimilarSongsLoading = true
        withContext(Dispatchers.IO) {
            try {
                val query = "similar to ${song.title} ${song.author}"
                val results = InnerTube.search(query).filter { it.videoId != song.videoId }
                withContext(Dispatchers.Main) {
                    similarSongs = results.take(6)
                }
            } catch (e: Exception) {
                android.util.Log.e("FullPlayerScreen", "Failed to load similar songs: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isSimilarSongsLoading = false
                }
            }
        }
    }

    var dragY by remember { mutableFloatStateOf(0f) }
    var swipeX by remember { mutableFloatStateOf(0f) }

    val currentRotation = if (vm.isPlaying && !vm.isLoading) rotationAngle else 0f

    val targetNeedleAngle = if (vm.isPlaying && !vm.isLoading) -28f else 0f
    val needleAngle by animateFloatAsState(
        targetValue = targetNeedleAngle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "needleAngle"
    )

    val pulsatingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsatingAlpha"
    )
    val pulsatingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulsatingScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        // ── 1. Album Art as blurred background ──
        AsyncImage(
            model = song.thumbnailHd,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .graphicsLayer(alpha = 0.45f)
        )

        // ── 2. Cinematic overlay gradient for text contrast ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.15f),
                            Color.Transparent,
                            VinColors.BgColor.copy(alpha = 0.55f),
                            VinColors.BgColor
                        )
                    )
                )
        )


        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .graphicsLayer { translationY = (dragY * 0.25f) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd   = { if (dragY > 120f) onClose(); dragY = 0f },
                        onVerticalDrag = { c, amt -> c.consume(); if (amt > 0) dragY += amt }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(8.dp))
            // Drag handle indicator
            Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(VinColors.White20))
            Spacer(Modifier.height(8.dp))

            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = VinColors.Primary, modifier = Modifier.size(24.dp))
                }
                Text(
                    text = "${song.title} by ${song.author}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = VinColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    textAlign = TextAlign.Center
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shareScope = rememberCoroutineScope()
                    IconButton(onClick = {
                        shareScope.launch {
                            try {
                                com.vinmusic.ui.utils.ShareCardGenerator.generateAndShare(
                                    context = ctx,
                                    songTitle = song.title,
                                    artistName = song.author,
                                    thumbnailUrl = song.thumbnailHd,
                                    duration = song.durationText
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("ShareCard", "Error triggering share", e)
                                android.widget.Toast.makeText(ctx, "Failed to share: ${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share Song Card",
                            tint = VinColors.Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { vm.toggleLike(song) }) {
                        Icon(
                            if (vm.isLiked(song.videoId)) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Like",
                            tint = if (vm.isLiked(song.videoId)) VinColors.Pink else VinColors.Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // ── Sleep Timer Countdown Capsule ──
            if (vm.sleepTimerMinutes > 0 || vm.sleepTimerMode == PlayerViewModel.SleepTimerMode.END_OF_SONG) {
                val initialMinutes = remember(vm.sleepTimerMinutes) {
                    if (vm.sleepTimerMinutes > 0) vm.sleepTimerMinutes else 1
                }
                val isEndOfSong = vm.sleepTimerMode == PlayerViewModel.SleepTimerMode.END_OF_SONG

                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(VinColors.Surface2.copy(alpha = 0.85f))
                        .border(1.dp, VinColors.GlassBorder, CircleShape)
                        .clickable { showSleepDialog = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isEndOfSong) {
                            val songProgress = 1.0f - vm.progress
                            CircularProgressIndicator(
                                progress = { songProgress },
                                color = VinColors.Pink,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            val progressFraction = if (initialMinutes > 0) {
                                vm.sleepTimerMinutes.toFloat() / initialMinutes
                            } else 1.0f
                            CircularProgressIndicator(
                                progress = { progressFraction.coerceIn(0f, 1f) },
                                color = VinColors.AccentLight,
                                trackColor = Color.White.copy(alpha = 0.1f),
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        Text(
                            text = if (isEndOfSong) "End of Song" else "${vm.sleepTimerMinutes}m left",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Centered Album Art with premium drop glow spot shadow
            // Breathtaking Ambient Breathing Glow Spot Shadow
            Box(
                modifier = Modifier.size(280.dp),
                contentAlignment = Alignment.Center
            ) {
                // Pulsating glowing backdrop (warm golden glow)
                Box(
                    modifier = Modifier
                        .size(268.dp)
                        .graphicsLayer {
                            scaleX = pulsatingScale
                            scaleY = pulsatingScale
                        }
                        .alpha(pulsatingAlpha)
                        .background(Color(0xFFFFB800), CircleShape)
                        .blur(36.dp)
                )

                // Master Circular Disc with Swipe gesture listener
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .shadow(elevation = 24.dp, shape = CircleShape, clip = false, spotColor = Color(0xFFFFB800))
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .graphicsLayer {
                            rotationZ = currentRotation
                            translationX = swipeX * 0.08f
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when { swipeX < -80f -> vm.playNext(); swipeX > 80f -> vm.playPrev() }
                                    swipeX = 0f
                                },
                                onHorizontalDrag = { c, amt -> c.consume(); swipeX += amt }
                            )
                        }
                ) {
                    // Beautiful Circular Artwork
                    AsyncImage(
                        model = song.thumbnailHd,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )

                    // Concentric Groove Circles Drawing
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerPoint = Offset(size.width / 2, size.height / 2)
                        // concentric vinyl grooves
                        for (i in 1..8) {
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.18f),
                                radius = (size.width / 2) * (0.35f + (i * 0.07f)),
                                center = centerPoint,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
                        // Center label boundary
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.35f),
                            radius = (size.width / 2) * 0.28f,
                            center = centerPoint,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }

                    // Polished Center Metallic Spindle Hole
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center)
                            .background(Color(0xFF0C0C0E), CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    )
                }



                if (vm.isLoading) {
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .clip(CircleShape)
                            .background(Color(0x80000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = VinColors.AccentLight, modifier = Modifier.size(36.dp))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Song Info (Title + Artist Centered)
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VinColors.Primary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = song.author,
                    maxLines = 1,
                    fontSize = 15.sp,
                    color = VinColors.AccentLight,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onArtistNameClick(song.author) }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            // Premium Quick Action Buttons Row (Lyrics, Queue, Remix, Download, Playlist)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassActionButton(
                    icon = Icons.Default.Lyrics,
                    label = "Lyrics",
                    active = activePanel == "Lyrics",
                    onClick = { activePanel = if (activePanel == "Lyrics") null else "Lyrics"; if (activePanel == "Lyrics") vm.loadLyrics() }
                )
                
                GlassActionButton(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    label = "Queue",
                    active = activePanel == "Queue",
                    onClick = { activePanel = if (activePanel == "Queue") null else "Queue" }
                )

                GlassActionButton(
                    icon = Icons.Default.Tune,
                    label = "Remix",
                    active = activePanel == "Remix",
                    onClick = { activePanel = if (activePanel == "Remix") null else "Remix" }
                )

                GlassActionButton(
                    icon = if (isDownloaded) Icons.Default.OfflinePin else Icons.Default.Download,
                    label = if (isDownloaded) "Offline" else "Download",
                    active = isDownloaded,
                    onClick = {
                        if (!isDownloaded) {
                            val intent = android.content.Intent(ctx, com.vinmusic.download.DownloadService::class.java).apply {
                                action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                                putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                                putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, song.title)
                                putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, song.author)
                                putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, song.durationText)
                            }
                            ctx.startService(intent)
                            android.widget.Toast.makeText(ctx, "Starting download caching...", android.widget.Toast.LENGTH_SHORT).show()
                            isDownloaded = true
                        } else {
                            android.widget.Toast.makeText(ctx, "Song already saved offline!", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                GlassActionButton(
                    icon = Icons.Default.PlaylistAdd,
                    label = "Playlist",
                    active = false,
                    onClick = { onAddToPlaylist(song) }
                )
            }

            vm.errorMessage?.let { err ->
                Text(err, fontSize = 12.sp, color = Color(0xFFFF5252),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp))
            }

            Spacer(Modifier.height(16.dp))

            // High fidelity Seek bar
            Slider(
                value = vm.progress,
                onValueChange = { vm.seekTo(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = animatedAccent,
                    inactiveTrackColor = VinColors.White20
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(vm.currentTimeMs), fontSize = 12.sp, color = VinColors.Secondary)
                Text(formatMs(vm.durationMs),    fontSize = 12.sp, color = VinColors.Secondary)
            }

            Spacer(Modifier.height(16.dp))

            // Premium Controls (Radial gradient central play)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.shuffle = !vm.shuffle }) {
                    Icon(Icons.Default.Shuffle, "Shuffle",
                        tint = if (vm.shuffle) VinColors.AccentLight else VinColors.Secondary, modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = { vm.playPrev() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous", tint = VinColors.Primary, modifier = Modifier.size(36.dp))
                }
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    VinColors.AccentLight,
                                    VinColors.Accent
                                )
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                        .clickable { if (!vm.isLoading) vm.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    if (vm.isLoading) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                IconButton(onClick = { vm.playNext() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Next", tint = VinColors.Primary, modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { vm.repeat = !vm.repeat }) {
                    Icon(Icons.Default.Repeat, "Repeat",
                        tint = if (vm.repeat) VinColors.AccentLight else VinColors.Secondary, modifier = Modifier.size(22.dp))
                }
            }

            Spacer(Modifier.height(24.dp))

            // 1. Synced Lyrics Preview Card
            LyricsPreviewCard(vm = vm, onExpand = {
                activePanel = "Lyrics"
                vm.loadLyrics()
            })

            Spacer(Modifier.height(12.dp))

            // 2. About the Artist Card
            AboutArtistCard(artistName = song.author, onArtistNameClick = onArtistNameClick)

            Spacer(Modifier.height(12.dp))

            // 3. Explore Similar Tracks Card
            ExploreSimilarCard(
                songTitle = song.title,
                similarSongs = similarSongs,
                isLoading = isSimilarSongsLoading,
                onSongClick = { selectedSong, songList ->
                    vm.setQueue(songList, songList.indexOf(selectedSong))
                }
            )

            Spacer(Modifier.height(12.dp))

            // 4. Credits Card
            CreditsCard(author = song.author, onArtistClick = onArtistNameClick)

            Spacer(Modifier.height(48.dp)) // Extra padding at bottom for beautiful scroll scroll space
        }

        // ── Floating Active Panel Slide-up Overlay ──
        AnimatedVisibility(
            visible = activePanel != null,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            if (activePanel != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { activePanel = null }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f)
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(VinColors.Surface.copy(alpha = 0.98f))
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .clickable(enabled = false) {}
                            .padding(20.dp)
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = when (activePanel) {
                                            "Lyrics" -> Icons.Default.Lyrics
                                            "Queue"  -> Icons.AutoMirrored.Filled.QueueMusic
                                            else     -> Icons.Default.Tune
                                        },
                                        contentDescription = null,
                                        tint = VinColors.AccentLight,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = activePanel ?: "",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = VinColors.Primary
                                    )
                                }
                                
                                IconButton(
                                    onClick = { activePanel = null },
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(VinColors.White10)
                                ) {
                                    Icon(Icons.Default.Close, null, tint = VinColors.Primary, modifier = Modifier.size(18.dp))
                                }
                            }
                            
                            HorizontalDivider(color = VinColors.GlassBorder)
                            
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (activePanel) {
                                    "Lyrics" -> LyricsPanel(vm)
                                    "Queue"  -> QueuePanel(vm)
                                    "Remix"  -> RemixPanel(vm)
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showOptionsSheet) {
        ModalBottomSheet(onDismissRequest = { showOptionsSheet = false }, containerColor = VinColors.Surface) {
            OptionsSheetV2(song, vm,
                onSleepTimer    = { showOptionsSheet = false; showSleepDialog = true },
                onAddToPlaylist = { showOptionsSheet = false; showAddPlaylist = true },
                onDismiss       = { showOptionsSheet = false })
        }
    }
    if (showSleepDialog) {
        SleepTimerDialog(current = vm.sleepTimerMinutes,
            onSet = { vm.setSleepTimer(it); showSleepDialog = false },
            onEndOfSong = { vm.setSleepTimerEndOfSong(); showSleepDialog = false },
            onDismiss = { showSleepDialog = false })
    }
}
}

// ── Sub-components for FullPlayerScreen ────────────────────────────────────────

@Composable
fun GlassActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) VinColors.Accent else VinColors.White10)
            .border(1.dp, if (active) Color.Transparent else VinColors.GlassBorder, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = if (active) Color.White else VinColors.Secondary, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 12.sp, color = if (active) Color.White else VinColors.Primary, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}

// ── Lyrics Panel ──────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun LyricsPanel(vm: PlayerViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.currentLyricIndex) {
        if (vm.currentLyricIndex > 1) {
            listState.animateScrollToItem((vm.currentLyricIndex - 2).coerceAtLeast(0))
        }
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editIsSynced by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header Controls Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Source and Offset Tuner
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val sourceText = when (val res = vm.lyricsResult) {
                        is LyricsResult.Synced -> "Source: ${res.source}"
                        is LyricsResult.Plain -> "Source: ${res.source}"
                        else -> "No lyrics"
                    }
                    Text(
                        text = sourceText,
                        fontSize = 10.sp,
                        color = VinColors.AccentLight
                    )

                    if (vm.lyricsResult is LyricsResult.Synced) {
                        // Offset capsule
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(VinColors.White10)
                                .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { vm.lyricOffsetMs -= 100L },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Remove,
                                    contentDescription = "Decrease offset",
                                    tint = VinColors.Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = "${if (vm.lyricOffsetMs >= 0) "+" else ""}${vm.lyricOffsetMs}ms",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.Primary,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            IconButton(
                                onClick = { vm.lyricOffsetMs += 100L },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Increase offset",
                                    tint = VinColors.Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            if (vm.lyricOffsetMs != 0L) {
                                IconButton(
                                    onClick = { vm.lyricOffsetMs = 0L },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Reset offset",
                                        tint = VinColors.AccentLight,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Right: Refetch + Edit Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Refetch Button
                    IconButton(
                        onClick = { vm.refetchLyrics() },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refetch Lyrics",
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Edit Button
                    IconButton(
                        onClick = {
                            val currentResult = vm.lyricsResult
                            editIsSynced = currentResult is LyricsResult.Synced
                            editText = when (currentResult) {
                                is LyricsResult.Synced -> {
                                    currentResult.lines.joinToString("\n") { line ->
                                        val ms = line.timeMs
                                        val min = ms / 60000
                                        val sec = (ms % 60000) / 1000
                                        val hundredths = (ms % 1000) / 10
                                        String.format(java.util.Locale.US, "[%02d:%02d.%02d] %s", min, sec, hundredths, line.text)
                                    }
                                }
                                is LyricsResult.Plain -> currentResult.text
                                else -> ""
                            }
                            showEditDialog = true
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Lyrics",
                            tint = VinColors.Primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = VinColors.GlassBorder, modifier = Modifier.padding(bottom = 12.dp))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.isLyricsLoading -> CircularProgressIndicator(
                        color = VinColors.AccentLight,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    vm.lyricsResult is LyricsResult.Synced -> {
                        val synced = vm.lyricsResult as LyricsResult.Synced
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(synced.lines) { idx, line ->
                                val isActive = idx == vm.currentLyricIndex
                                
                                val scale by animateFloatAsState(
                                    targetValue = if (isActive) 1.08f else 0.92f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                                    label = "lyricScale"
                                )
                                val alpha by animateFloatAsState(
                                    targetValue = if (isActive) 1.0f else 0.35f,
                                    animationSpec = tween(durationMillis = 350),
                                    label = "lyricAlpha"
                                )
                                val color by animateColorAsState(
                                    targetValue = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                                    animationSpec = tween(durationMillis = 350),
                                    label = "lyricColor"
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            transformOrigin = TransformOrigin(0f, 0.5f)
                                        }
                                        .alpha(alpha)
                                        .clickable { vm.seekToMs(line.timeMs) }
                                        .padding(vertical = 4.dp)
                                ) {
                                    if (isActive) {
                                        val words = remember(line.text) { 
                                            line.text.split(Regex("\\s+")).filter { it.isNotEmpty() } 
                                        }
                                        val nextLineStart = synced.lines.getOrNull(idx + 1)?.timeMs ?: (line.timeMs + 6000L)
                                        val lineDuration = (nextLineStart - line.timeMs).coerceAtLeast(1000L)
                                        val wordDuration = lineDuration / words.size.coerceAtLeast(1)

                                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            words.forEachIndexed { wordIdx, wordText ->
                                                val wordStart = line.timeMs + (wordIdx * wordDuration)
                                                val wordEnd = wordStart + wordDuration
                                                
                                                // Calculate progress of this word being sung
                                                val wordProgress = if (vm.currentTimeMs < wordStart) {
                                                    0f
                                                } else if (vm.currentTimeMs >= wordEnd) {
                                                    1f
                                                } else {
                                                    ((vm.currentTimeMs - wordStart).toFloat() / wordDuration).coerceIn(0f, 1f)
                                                }

                                                // Zoom active word slightly
                                                val isWordActive = vm.currentTimeMs in wordStart until wordEnd
                                                val wordScale by animateFloatAsState(
                                                    targetValue = if (isWordActive) 1.12f else 1.0f,
                                                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                                                    label = "wordScale"
                                                )

                                                // Mix grey text and white text smoothly based on progress
                                                val textAlpha = 0.4f + 0.6f * wordProgress

                                                Text(
                                                    text = wordText,
                                                    fontSize = 18.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White.copy(alpha = textAlpha),
                                                    modifier = Modifier.graphicsLayer {
                                                        scaleX = wordScale
                                                        scaleY = wordScale
                                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                                    },
                                                    style = androidx.compose.ui.text.TextStyle(
                                                        shadow = if (wordProgress > 0.1f) Shadow(
                                                            color = Color.White.copy(alpha = 0.35f * wordProgress),
                                                            offset = Offset(0f, 0f),
                                                            blurRadius = 12.dp.value
                                                        ) else null
                                                    ),
                                                    lineHeight = 24.sp
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = line.text,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = color,
                                            style = androidx.compose.ui.text.TextStyle(
                                                shadow = if (isActive) Shadow(
                                                    color = Color.White.copy(alpha = 0.35f),
                                                    offset = Offset(0f, 0f),
                                                    blurRadius = 12.dp.value
                                                ) else null
                                            ),
                                            lineHeight = 24.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                    vm.lyricsResult is LyricsResult.Plain -> {
                        val plain = vm.lyricsResult as LyricsResult.Plain
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Text(plain.text, color = VinColors.Primary, fontSize = 15.sp, lineHeight = 26.sp)
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("No lyrics found.", color = VinColors.Secondary)
                            Button(
                                onClick = {
                                    editIsSynced = false
                                    editText = ""
                                    showEditDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Lyrics", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Edit Dialog
        if (showEditDialog) {
            AlertDialog(
                onDismissRequest = { showEditDialog = false },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Edit custom lyrics",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.Primary
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Synced", fontSize = 12.sp, color = VinColors.Secondary)
                            Switch(
                                checked = editIsSynced,
                                onCheckedChange = { editIsSynced = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = VinColors.Accent,
                                    uncheckedThumbColor = VinColors.Secondary,
                                    uncheckedTrackColor = VinColors.White10
                                )
                            )
                        }
                    }
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (editIsSynced) {
                            Text(
                                text = "Format: [mm:ss.xx] Lyric text",
                                fontSize = 11.sp,
                                color = VinColors.AccentLight
                            )
                        } else {
                            Text(
                                text = "Format: Plain paragraphs of text",
                                fontSize = 11.sp,
                                color = VinColors.Secondary
                            )
                        }
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = VinColors.Primary),
                            placeholder = { Text("Type or paste lyrics here...", color = VinColors.Secondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = VinColors.Primary,
                                unfocusedTextColor = VinColors.Primary,
                                focusedBorderColor = VinColors.Accent,
                                unfocusedBorderColor = VinColors.GlassBorder,
                                focusedContainerColor = VinColors.White10,
                                unfocusedContainerColor = VinColors.White10
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editIsSynced) {
                                val lines = com.vinmusic.lyrics.LyricsHelper.parseLrc(editText)
                                val json = com.google.gson.Gson().toJson(lines)
                                vm.saveCustomLyrics(json, true)
                            } else {
                                vm.saveCustomLyrics(editText, false)
                            }
                            showEditDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
                    ) {
                        Text("Save", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showEditDialog = false }) {
                        Text("Cancel", color = VinColors.Secondary)
                    }
                },
                containerColor = VinColors.Surface,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

// ── Queue Panel ───────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun QueuePanel(vm: PlayerViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(vm.queue) { i, song ->
            Row(modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (i == vm.queueIndex) VinColors.White10 else Color.Transparent)
                .clickable { vm.setQueue(vm.queue, i) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i+1}", fontSize = 12.sp, color = VinColors.Secondary, modifier = Modifier.width(20.dp))
                AsyncImage(model = song.thumbnail, contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                        color = if (i == vm.queueIndex) VinColors.AccentLight else VinColors.Primary,
                        fontWeight = if (i == vm.queueIndex) FontWeight.Bold else FontWeight.Normal)
                    Text(song.author, maxLines = 1, fontSize = 11.sp, color = VinColors.Secondary)
                }
                if (i == vm.queueIndex) Icon(Icons.AutoMirrored.Filled.VolumeUp, null,
                    tint = VinColors.AccentLight, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ── Remix Panel ───────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun RemixPanel(vm: PlayerViewModel) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        
        Text("Smart Presets", fontSize = 12.sp, color = VinColors.Secondary)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            item {
                SmartEQPresetChip(
                    name = "Bass Booster",
                    icon = "",
                    gradient = Brush.linearGradient(listOf(Color(0xFFDC2626), Color(0xFF7F1D1D))),
                    active = vm.eqPreset == "Bass Boost",
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Bass Boost" }
                        if (preset != null) vm.applyPreset(preset)
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "Lo-Fi Lounge",
                    icon = "",
                    gradient = Brush.linearGradient(listOf(Color(0xFFD97706), Color(0xFF78350F))),
                    active = vm.eqPreset == "Lofi",
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Lofi" }
                        if (preset != null) vm.applyPreset(preset)
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "Vocal Focus",
                    icon = "",
                    gradient = Brush.linearGradient(listOf(Color(0xFF059669), Color(0xFF064E3B))),
                    active = vm.eqPreset == "Vocal",
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Vocal" }
                        if (preset != null) vm.applyPreset(preset)
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "Acoustic Clarity",
                    icon = "",
                    gradient = Brush.linearGradient(listOf(Color(0xFF0D9488), Color(0xFF115E59))),
                    active = vm.eqPreset == "Treble+",
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Treble+" }
                        if (preset != null) vm.applyPreset(preset)
                    }
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Text("All Presets", fontSize = 12.sp, color = VinColors.Secondary)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EQ_PRESETS) { preset ->
                val active = vm.eqPreset == preset.name
                Box(modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (active) VinColors.Accent else VinColors.White10)
                    .clickable { vm.applyPreset(preset) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(preset.name, fontSize = 12.sp, color = if (active) Color.White else VinColors.Secondary)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        
        EQGraph(
            subBass = vm.eqSubBass,
            bass = vm.eqBass,
            lowMid = vm.eqLowMid,
            mid = vm.eqMid,
            treble = vm.eqTreble,
            air = vm.eqAir
        )

        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = VinColors.GlassBorder)
        Text("EQ Bands", fontSize = 12.sp, color = VinColors.Secondary)
        EQSlider("Sub Bass 60Hz",  vm.eqSubBass, -15f, 15f) { vm.eqSubBass = it; vm.applyEQ() }
        EQSlider("Bass 250Hz",     vm.eqBass,    -15f, 15f) { vm.eqBass    = it; vm.applyEQ() }
        EQSlider("Low Mid 1kHz",   vm.eqLowMid,  -15f, 15f) { vm.eqLowMid  = it; vm.applyEQ() }
        EQSlider("Mid 4kHz",       vm.eqMid,     -15f, 15f) { vm.eqMid     = it; vm.applyEQ() }
        EQSlider("Treble 8kHz",    vm.eqTreble,  -15f, 15f) { vm.eqTreble  = it; vm.applyEQ() }
        EQSlider("Air 16kHz",      vm.eqAir,     -15f, 15f) { vm.eqAir     = it; vm.applyEQ() }
        HorizontalDivider(color = VinColors.GlassBorder)
        Text("Effects", fontSize = 12.sp, color = VinColors.Secondary)
        EQSlider("Bass Boost", vm.bassBoostStr, 0f, 1000f) { vm.bassBoostStr = it; vm.applyEQ() }
        EQSlider("Loudness",   vm.loudnessGain, 0f, 1000f) { vm.loudnessGain = it; vm.applyEQ() }

        HorizontalDivider(color = VinColors.GlassBorder)
        Text("Speed & Pitch Controls (DSP)", fontSize = 12.sp, color = VinColors.Secondary)
        RemixSlider("Playback Speed", vm.playbackSpeed, 0.5f, 2.0f, "x") { vm.updatePlaybackSpeed(it) }
        RemixSlider("Playback Pitch", vm.playbackPitch, 0.5f, 2.0f, "x") { vm.updatePlaybackPitch(it) }

        TextButton(onClick = { vm.resetEQ() }) { Text("Reset All", color = VinColors.Secondary) }
    }
}

@Composable
fun EQSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = VinColors.Secondary)
            Text(if (max > 100) "${value.toInt()}" else "${"%.1f".format(value)} dB",
                fontSize = 12.sp, color = VinColors.AccentLight)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = SliderDefaults.colors(thumbColor = VinColors.AccentLight,
                activeTrackColor = VinColors.Accent, inactiveTrackColor = VinColors.White10))
    }
}

@Composable
fun RemixSlider(label: String, value: Float, min: Float, max: Float, formatSuffix: String, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.sp, color = VinColors.Secondary)
            Text("${"%.2f".format(value)}$formatSuffix", fontSize = 12.sp, color = VinColors.AccentLight)
        }
        Slider(value = value, onValueChange = onChange, valueRange = min..max,
            modifier = Modifier.fillMaxWidth().height(32.dp),
            colors = SliderDefaults.colors(thumbColor = VinColors.AccentLight,
                activeTrackColor = VinColors.Accent, inactiveTrackColor = VinColors.White10))
    }
}

// ── Options Sheet ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun OptionsSheetV2(
    song: VideoItem, vm: PlayerViewModel,
    onSleepTimer: () -> Unit, onAddToPlaylist: () -> Unit, onDismiss: () -> Unit
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = Modifier.padding(bottom = 32.dp)) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = song.thumbnail, contentDescription = null,
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary)
                Text(song.author, fontSize = 13.sp, color = VinColors.Secondary)
            }
        }
        HorizontalDivider(color = VinColors.GlassBorder)
        data class Opt(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val action: () -> Unit)
        listOf(
            Opt(Icons.Default.Favorite,    if (vm.isLiked(song.videoId)) "Unlike" else "Like Song") { vm.toggleLike(song); onDismiss() },
            Opt(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Playlist") { onAddToPlaylist(); onDismiss() },
            Opt(Icons.Default.Download,    "Download Song") {
                val intent = android.content.Intent(ctx, com.vinmusic.download.DownloadService::class.java).apply {
                    action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, song.title)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, song.author)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, song.durationText)
                }
                ctx.startService(intent)
                onDismiss()
            },
            Opt(Icons.Default.Timer,       "Sleep Timer") { onSleepTimer() },
            Opt(Icons.Default.Share,       "Share") { onDismiss() },
        ).forEach { opt ->
            Row(modifier = Modifier.fillMaxWidth().clickable { opt.action() }
                .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(opt.icon, null, tint = VinColors.Secondary, modifier = Modifier.size(22.dp))
                Text(opt.label, fontSize = 15.sp, color = VinColors.Primary)
            }
        }
    }
}

// ── Sleep Timer Dialog ────────────────────────────────────────────────────────

@Composable
fun SleepTimerDialog(current: Int, onSet: (Int) -> Unit, onEndOfSong: () -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(if (current > 0) current else 30) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = VinColors.Surface2,
        title = { Text("Sleep Timer", color = VinColors.Primary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (current > 0) Text("Active: $current min remaining", color = VinColors.AccentLight, fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(5,10,15,30,45,60,90)) { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m },
                            label = { Text("${m}m", fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VinColors.Accent, selectedLabelColor = Color.White,
                                containerColor = VinColors.White10, labelColor = VinColors.Primary))
                    }
                }
                OutlinedButton(onClick = { onEndOfSong() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop after current song", color = VinColors.Secondary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSet(minutes) }) { Text("Set", color = VinColors.Accent) } },
        dismissButton = { if (current > 0) TextButton(onClick = { onSet(0) }) { Text("Cancel", color = VinColors.Secondary) } }
    )
}

fun formatMs(ms: Long): String {
    val s = ms / 1000; return "${s / 60}:${"%02d".format(s % 60)}"
}

// ── Interactive Bezier Curve EQ Graph ──────────────────────────────────────────
@Composable
fun EQGraph(
    subBass: Float,
    bass: Float,
    lowMid: Float,
    mid: Float,
    treble: Float,
    air: Float
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
    ) {
        val width = size.width
        val height = size.height
        val midY = height / 2f
        val points = listOf(subBass, bass, lowMid, mid, treble, air)
        val stepX = width / (points.size - 1)

        val path = Path()
        val mappedPoints = points.map { db ->
            val fraction = db / 15f
            midY - (fraction * (height / 2.5f))
        }

        path.moveTo(0f, mappedPoints[0])
        for (i in 0 until points.size - 1) {
            val startX = i * stepX
            val startY = mappedPoints[i]
            val endX = (i + 1) * stepX
            val endY = mappedPoints[i + 1]

            val controlX1 = startX + stepX / 2f
            val controlY1 = startY
            val controlX2 = startX + stepX / 2f
            val controlY2 = endY

            path.cubicTo(controlX1, controlY1, controlX2, controlY2, endX, endY)
        }

        val fillPath = Path().apply {
            addPath(path)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(VinColors.Accent.copy(alpha = 0.35f), Color.Transparent)
            )
        )

        drawPath(
            path = path,
            color = VinColors.AccentLight,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        points.forEachIndexed { i, _ ->
            val x = i * stepX
            val y = mappedPoints[i]
            drawCircle(
                color = VinColors.AccentLight,
                radius = 5.dp.toPx(),
                center = Offset(x, y)
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = Offset(x, y)
            )
        }

        drawLine(
            color = VinColors.White20,
            start = Offset(0f, midY),
            end = Offset(width, midY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
        )
    }
}

// ── Live Animated Music Visualizer Wave ─────────────────────────────────────────
@Composable
fun AnimatedVisualizer(isPlaying: Boolean) {
    val transition = rememberInfiniteTransition(label = "visualizer")
    
    val bar1Height by if (isPlaying) {
        transition.animateFloat(
            initialValue = 4f,
            targetValue = 24f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar1"
        )
    } else {
        remember { mutableStateOf(6f) }
    }

    val bar2Height by if (isPlaying) {
        transition.animateFloat(
            initialValue = 6f,
            targetValue = 20f,
            animationSpec = infiniteRepeatable(
                animation = tween(350, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar2"
        )
    } else {
        remember { mutableStateOf(6f) }
    }

    val bar3Height by if (isPlaying) {
        transition.animateFloat(
            initialValue = 3f,
            targetValue = 28f,
            animationSpec = infiniteRepeatable(
                animation = tween(550, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar3"
        )
    } else {
        remember { mutableStateOf(6f) }
    }

    val bar4Height by if (isPlaying) {
        transition.animateFloat(
            initialValue = 5f,
            targetValue = 18f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar4"
        )
    } else {
        remember { mutableStateOf(6f) }
    }

    Row(
        modifier = Modifier
            .height(28.dp)
            .width(28.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1Height, bar2Height, bar3Height, bar4Height).forEach { height ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(VinColors.AccentLight)
            )
        }
    }
}

// ── Synced Lyrics Preview Card ────────────────────────────────────────────────
@Composable
fun LyricsPreviewCard(vm: PlayerViewModel, onExpand: () -> Unit) {
    val song = vm.currentSong ?: return
    
    LaunchedEffect(song.videoId) {
        vm.loadLyrics()
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(VinColors.Surface2.copy(alpha = 0.8f))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
            .clickable { onExpand() }
            .padding(20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lyrics, null, tint = VinColors.AccentLight, modifier = Modifier.size(18.dp))
                    Text(
                        text = "LYRICS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.AccentLight,
                        letterSpacing = 1.sp
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.OpenInFull,
                    contentDescription = "Expand",
                    tint = VinColors.Secondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Spacer(Modifier.height(4.dp))
            
            when {
                vm.isLyricsLoading -> {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = VinColors.AccentLight, modifier = Modifier.size(24.dp))
                    }
                }
                vm.lyricsResult is LyricsResult.Synced -> {
                    val synced = vm.lyricsResult as LyricsResult.Synced
                    val lines = synced.lines
                    val activeIndex = vm.currentLyricIndex
                    
                    val displayLines = remember(activeIndex, lines) {
                        val list = mutableListOf<Pair<Int, String>>()
                        val start = (activeIndex - 1).coerceAtLeast(0)
                        val end = (activeIndex + 2).coerceAtMost(lines.size - 1)
                        for (i in start..end) {
                            list.add(Pair(i, lines[i].text))
                        }
                        if (list.isEmpty()) {
                            list.add(Pair(-1, "Listening..."))
                        }
                        list
                    }
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        displayLines.forEach { (idx, text) ->
                            val isActive = idx == activeIndex
                            val alpha by animateFloatAsState(if (isActive) 1f else 0.5f, label = "lyric_alpha")
                            val scale by animateFloatAsState(if (isActive) 1.05f else 1.0f, label = "lyric_scale")
                            
                            Text(
                                text = text,
                                fontSize = if (isActive) 18.sp else 16.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                color = if (isActive) Color.White else VinColors.Primary.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .graphicsLayer(scaleX = scale, scaleY = scale, transformOrigin = TransformOrigin(0f, 0.5f))
                                    .alpha(alpha)
                            )
                        }
                    }
                }
                vm.lyricsResult is LyricsResult.Plain -> {
                    val plain = vm.lyricsResult as LyricsResult.Plain
                    val lines = remember(plain.text) { plain.text.lines().filter { it.isNotBlank() }.take(3) }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        lines.forEach { text ->
                            Text(
                                text = text,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.Primary.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = "Lyrics not available for this song.",
                        fontSize = 14.sp,
                        color = VinColors.Secondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ── About the Artist Card ────────────────────────────────────────────────────
@Composable
fun AboutArtistCard(artistName: String, onArtistNameClick: (String) -> Unit) {
    val cleanName = remember(artistName) {
        artistName.replace("-topic", "", ignoreCase = true).replace("- topic", "", ignoreCase = true).trim()
    }
    
    val stats = remember(cleanName) { generateArtistStats(cleanName) }
    val bio = remember(cleanName) { generateArtistBio(cleanName) }
    var isFollowing by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(VinColors.Surface2.copy(alpha = 0.8f))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                VinColors.Accent,
                                VinColors.GradMid,
                                VinColors.GradBottom
                            )
                        )
                    )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0x99000000))
                            )
                        )
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .background(VinColors.White10),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = cleanName.take(1).uppercase(),
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    
                    Column {
                        Text(
                            text = cleanName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stats.first,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }
                
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isFollowing) Color.White.copy(alpha = 0.2f) else Color.White)
                        .border(1.dp, if (isFollowing) Color.White else Color.Transparent, RoundedCornerShape(20.dp))
                        .clickable { isFollowing = !isFollowing }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isFollowing) "Following" else "Follow",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isFollowing) Color.White else Color.Black
                    )
                }
            }
            
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = VinColors.AccentLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stats.second.uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.AccentLight,
                        letterSpacing = 1.sp
                    )
                }
                
                Text(
                    text = bio,
                    fontSize = 13.sp,
                    color = VinColors.Primary.copy(alpha = 0.8f),
                    lineHeight = 18.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                
                HorizontalDivider(color = VinColors.GlassBorder)
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onArtistNameClick(artistName) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "View Artist Profile",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.AccentLight
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        tint = VinColors.AccentLight,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

// ── Explore Similar Tracks Card ────────────────────────────────────────────────
@Composable
fun ExploreSimilarCard(
    songTitle: String,
    similarSongs: List<VideoItem>,
    isLoading: Boolean,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit
) {
    if (!isLoading && similarSongs.isEmpty()) return
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(VinColors.Surface2.copy(alpha = 0.8f))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Explore, null, tint = VinColors.AccentLight, modifier = Modifier.size(18.dp))
                Text(
                    text = "MORE LIKE THIS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VinColors.AccentLight,
                    letterSpacing = 1.sp
                )
            }
            
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VinColors.AccentLight, modifier = Modifier.size(24.dp))
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(similarSongs) { item ->
                        ExploreTrackItem(song = item) {
                            onSongClick(item, similarSongs)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExploreTrackItem(song: VideoItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable { onClick() },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AsyncImage(
            model = song.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop
        )
        Text(
            text = song.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = VinColors.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = song.author,
            fontSize = 10.sp,
            color = VinColors.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Credits Card ─────────────────────────────────────────────────────────────
@Composable
fun CreditsCard(author: String, onArtistClick: (String) -> Unit) {
    val contributors = remember(author) { parseContributors(author) }
    if (contributors.isEmpty()) return
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(VinColors.Surface2.copy(alpha = 0.8f))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = VinColors.AccentLight, modifier = Modifier.size(18.dp))
                Text(
                    text = "CREDITS",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = VinColors.AccentLight,
                    letterSpacing = 1.sp
                )
            }
            
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                contributors.forEach { name ->
                    CreditRowItem(name = name) {
                        onArtistClick(name)
                    }
                }
            }
        }
    }
}

@Composable
fun CreditRowItem(name: String, onClick: () -> Unit) {
    var isFollowing by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(VinColors.White10),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
            
            Column {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = VinColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Main Artist",
                    fontSize = 11.sp,
                    color = VinColors.Secondary
                )
            }
        }
        
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isFollowing) Color.White.copy(alpha = 0.2f) else Color.White)
                .border(1.dp, if (isFollowing) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
                .clickable { isFollowing = !isFollowing }
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isFollowing) "Following" else "Follow",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = if (isFollowing) Color.White else Color.Black
            )
        }
    }
}

// ── Helper functions for bio, stats, and credits ──────────────────────────────
fun generateArtistBio(name: String): String {
    val cleanName = name.replace("-topic", "", ignoreCase = true).replace("- topic", "", ignoreCase = true).trim()
    val hash = cleanName.hashCode().let { if (it < 0) -it else it }
    val genres = listOf("indie pop", "classical fusion", "modern Bollywood", "lo-fi beats", "hip-hop", "contemporary acoustic", "playback singing")
    val genre = genres[hash % genres.size]
    val descriptions = listOf(
        "is a groundbreaking artist known for their soul-stirring melodies and innovative soundscapes, captivating millions worldwide.",
        "has taken the music world by storm with deep emotional depth, fusing traditional notes with contemporary rhythms.",
        "stands as one of the most prominent voices in today's generation, crafting melodies that resonate with listeners across all boundaries.",
        "has pioneered a unique style of music that blends high-energy rhythm with introspective, storytelling lyrics.",
        "continues to redefine musical excellence, weaving beautiful harmonies that capture the essence of life, love, and human connection."
    )
    val desc = descriptions[hash % descriptions.size]
    return "$cleanName $desc Emerging as a prominent figure in the $genre scene, their musical journey represents a perfect blend of passion and absolute artistry."
}

fun generateArtistStats(name: String): Pair<String, String> {
    val cleanName = name.replace("-topic", "", ignoreCase = true).replace("- topic", "", ignoreCase = true).trim()
    val hash = cleanName.hashCode().let { if (it < 0) -it else it }
    
    val listenersCount = ((hash % 85) + 15) / 10.0
    val unit = if (hash % 2 == 0) "M" else "Cr"
    val rank = (hash % 450) + 50
    
    val listenersStr = "${"%.1f".format(listenersCount)}${unit} monthly listeners"
    val rankStr = "#$rank in the world"
    return Pair(listenersStr, rankStr)
}

fun parseContributors(author: String): List<String> {
    val cleanAuthor = author.replace("-topic", "", ignoreCase = true).replace("- topic", "", ignoreCase = true).trim()
    val separators = Regex("""\s*(?:feat\.?|ft\.?|&|,|and)\s*""", RegexOption.IGNORE_CASE)
    return cleanAuthor.split(separators)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

@Composable
fun SmartEQPresetChip(
    name: String,
    icon: String,
    gradient: Brush,
    active: Boolean,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1.0f) }

    Box(
        modifier = Modifier
            .graphicsLayer(scaleX = scale.value, scaleY = scale.value)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) gradient else Brush.linearGradient(listOf(VinColors.White10, VinColors.White10)))
            .border(1.dp, if (active) Color.White.copy(alpha = 0.3f) else VinColors.GlassBorder, RoundedCornerShape(20.dp))
            .clickable {
                scope.launch {
                    scale.animateTo(0.9f, animationSpec = tween(100, easing = FastOutSlowInEasing))
                    scale.animateTo(1.0f, animationSpec = spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessMediumLow))
                }
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (icon.isNotEmpty()) {
                Text(icon, fontSize = 14.sp)
            }
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) Color.White else VinColors.Secondary
            )
        }
    }
}

@Composable
fun AmbientFluidGlowBackground(palette: ColorExtractor.MusicPalette) {
    val infiniteTransition = rememberInfiniteTransition(label = "fluid_glow")

    val t1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t1"
    )
    val t2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t2"
    )
    val t3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 32000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "t3"
    )

    val scale1 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale1"
    )
    val scale2 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale2"
    )

    val animatedGradTop by animateColorAsState(targetValue = palette.gradTop, animationSpec = tween(1200))
    val animatedGradMid by animateColorAsState(targetValue = palette.gradMid, animationSpec = tween(1200))
    val animatedAccent by animateColorAsState(targetValue = palette.accent, animationSpec = tween(1200))

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .blur(100.dp)
    ) {
        val w = size.width
        val h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    animatedGradMid.copy(alpha = 0.4f),
                    Color(0xFF0E0E11)
                )
            ),
            size = size
        )

        val x1 = w * 0.25f + (w * 0.15f) * kotlin.math.cos(t1)
        val y1 = h * 0.3f + (h * 0.1f) * kotlin.math.sin(t1)
        val r1 = (w * 0.5f) * scale1
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedAccent.copy(alpha = 0.5f), Color.Transparent),
                center = Offset(x1, y1),
                radius = r1
            ),
            center = Offset(x1, y1),
            radius = r1
        )

        val x2 = w * 0.7f + (w * 0.12f) * kotlin.math.cos(t2 + 1f)
        val y2 = h * 0.4f + (h * 0.12f) * kotlin.math.sin(t2 * 2f)
        val r2 = (w * 0.55f) * scale2
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedGradTop.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(x2, y2),
                radius = r2
            ),
            center = Offset(x2, y2),
            radius = r2
        )

        val x3 = w * 0.5f + (w * 0.2f) * kotlin.math.sin(t3)
        val y3 = h * 0.65f + (h * 0.05f) * kotlin.math.cos(t3)
        val r3 = w * 0.6f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(animatedGradMid.copy(alpha = 0.4f), Color.Transparent),
                center = Offset(x3, y3),
                radius = r3
            ),
            center = Offset(x3, y3),
            radius = r3
        )
    }
}


