package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.*
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

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun FullPlayerScreen(
    vm: PlayerViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onArtistNameClick: (String) -> Unit,
    onAddToPlaylist: (VideoItem) -> Unit,
    onClose: () -> Unit
) {
    val song = vm.currentSong ?: return
    val ctx = LocalContext.current
    val db = com.vinmusic.data.db.VinDatabase.getInstance(ctx)

    var activePanel      by remember { mutableStateOf<String?>(null) }
    val panelScope = rememberCoroutineScope()
    var creditsDescription by remember(song.videoId) { mutableStateOf(vm.currentSongDescription) }

    LaunchedEffect(song.videoId, vm.currentSongDescription) {
        if (!vm.currentSongDescription.isNullOrBlank()) {
            creditsDescription = vm.currentSongDescription
        }
    }

    fun openCreditsPanel() {
        activePanel = "Credits"
        if (creditsDescription.isNullOrBlank()) {
            panelScope.launch(Dispatchers.IO) {
                val desc = runCatching { InnerTube.getSongDescription(song.videoId) }.getOrNull()
                if (!desc.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        creditsDescription = desc
                    }
                }
            }
        }
    }
    
    // Dynamic Color Harmonization Palette State
    var currentPalette by remember(song.videoId) {
        mutableStateOf(
            ColorExtractor.MusicPalette(
                gradTop = Color(0x336EA8FF),
                gradMid = Color(0x1F6EA8FF),
                gradBottom = Color(0xFF0E0E11),
                accent = Color(0xFF6EA8FF)
            )
        )
    }

    LaunchedEffect(song.thumbnailHd) {
        try {
            val extracted = ColorExtractor.extractColorsFromUrl(ctx, song.thumbnailHd)
            currentPalette = extracted
        } catch (_: Exception) {
            try {
                val extracted = ColorExtractor.extractColorsFromUrl(ctx, song.thumbnail)
                currentPalette = extracted
            } catch (_: Exception) {}
        }
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
    
    // DJ Scratching & Visualizer Customization States
    var isDjMode by remember { mutableStateOf(false) }
    var visualizerStyle by remember { mutableStateOf("Waveform Ripple") }
    var toastMessage by remember { mutableStateOf("") }
    var toastTrigger by remember { mutableStateOf(false) }
    var scratchAngleOffset by remember { mutableFloatStateOf(0f) }
    var isScratching by remember { mutableStateOf(false) }
    var lastAngle by remember { mutableFloatStateOf(0f) }
    var wasPlayingBeforeScratch by remember { mutableStateOf(false) }


    val particles = remember {
        List(18) {
            mapOf(
                "x" to (0.1f + 0.8f * Math.random().toFloat()),
                "y" to Math.random().toFloat(),
                "speed" to (0.005f + 0.012f * Math.random().toFloat()),
                "baseSize" to (3f + 5f * Math.random().toFloat())
            )
        }
    }

    LaunchedEffect(toastTrigger) {
        if (toastMessage.isNotEmpty()) {
            kotlinx.coroutines.delay(1500)
            toastMessage = ""
        }
    }

    var showOptionsSheet by remember { mutableStateOf(false) }
    var showSleepDialog  by remember { mutableStateOf(false) }
    var showAddPlaylist  by remember { mutableStateOf(false) }

    // Animated vinyl rotation — only animates when playing to save CPU, and pauses in place!
    val rotation = remember { Animatable(0f) }
    val isActuallyPlaying = vm.isPlaying && !vm.isLoading
    LaunchedEffect(isActuallyPlaying) {
        if (isActuallyPlaying) {
            val rotationDuration = 16000
            while (true) {
                val target = rotation.value + 360f
                rotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = rotationDuration, easing = LinearEasing)
                )
            }
        }
    }
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

    // Pulsating animations only run when music is actually playing (saves CPU when paused/loading)
    val pulsatingAlpha = animateFloatAsState(
        targetValue = if (isActuallyPlaying) 0.45f else 0.25f,
        animationSpec = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "pulsatingAlpha"
    )
    val pulsatingScale = animateFloatAsState(
        targetValue = if (isActuallyPlaying) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 2500, easing = FastOutSlowInEasing),
        label = "pulsatingScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        // ── 1. Album Art as crisp background ──
        AsyncImage(
            model = song.thumbnailHd,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(alpha = 0.72f)
        )

        // ── 2. Dynamic translucent gradient overlay ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.12f),
                            Color.Black.copy(alpha = 0.50f),
                            VinColors.BgColor.copy(alpha = 0.96f)
                        )
                    )
                )
        )


        val scrollState = rememberScrollState()
        val dragToCloseConnection = remember(isDjMode) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (isDjMode) return Offset.Zero
                    // When dragging DOWN (positive y) and scroll is at top, intercept for dismiss
                    if (available.y > 0f && scrollState.value == 0) {
                        dragY += available.y
                        return Offset(0f, available.y) // consume vertical
                    }
                    // When dragging UP and we have accumulated dragY, reduce it first
                    if (available.y < 0f && dragY > 0f) {
                        val consumed = maxOf(available.y, -dragY)
                        dragY += consumed
                        return Offset(0f, consumed)
                    }
                    return Offset.Zero
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (dragY > 120f) {
                        onClose()
                    }
                    dragY = 0f
                    return androidx.compose.ui.unit.Velocity.Zero
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(dragToCloseConnection)
                .verticalScroll(scrollState, enabled = !isDjMode)
                .graphicsLayer { translationY = (dragY * 0.25f) },
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
                    IconButton(onClick = {
                        isDjMode = !isDjMode
                    }) {
                        Icon(
                            imageVector = Icons.Default.Headset,
                            contentDescription = "DJ Scratch Mode",
                            tint = if (isDjMode) animatedAccent else VinColors.Primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                            CircularProgressIndicator(
                                progress = { 1.0f - vm.progress },
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

            // Centered Album Art with premium drop glow spot shadow and multi-visualizer selection
            val density = androidx.compose.ui.platform.LocalDensity.current
            Box(
                modifier = Modifier
                    .size(if (isDjMode) 370.dp else 320.dp),
                contentAlignment = Alignment.Center
            ) {


                // ── Waveform Ripple Beat-Visualizer ──
                Box(
                    modifier = Modifier
                        .size(320.dp)
                ) {
                    // Only draw visualizer when music is actually playing
                    if (isActuallyPlaying) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (visualizerStyle == "Halo") {
                                    rotationZ = rotation.value * 0.5f
                                }
                                scaleX = pulsatingScale.value
                                scaleY = pulsatingScale.value
                                alpha = pulsatingAlpha.value
                            }
                    ) {
                        val centerPoint = Offset(size.width / 2, size.height / 2)
                        
                        when (visualizerStyle) {
                            "Halo" -> {
                                val innerRadius = 145.dp.toPx()
                                val numBars = 48
                                val angleStep = (2 * Math.PI / numBars).toFloat()
                                
                                for (i in 0 until numBars) {
                                    val angle = i * angleStep
                                    val wave = (kotlin.math.sin((i * 3.14f / 4f).toDouble()).toFloat() + 1f) / 2f
                                    val barLength = 8.dp.toPx() + (12.dp.toPx() * wave) * ((pulsatingScale.value - 1f) * 16.6f)
                                    
                                    val startX = centerPoint.x + kotlin.math.cos(angle.toDouble()).toFloat() * innerRadius
                                    val startY = centerPoint.y + kotlin.math.sin(angle.toDouble()).toFloat() * innerRadius
                                    val endX = centerPoint.x + kotlin.math.cos(angle.toDouble()).toFloat() * (innerRadius + barLength)
                                    val endY = centerPoint.y + kotlin.math.sin(angle.toDouble()).toFloat() * (innerRadius + barLength)
                                    
                                    drawLine(
                                        color = animatedAccent,
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = 3.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                            "Retro Bars" -> {
                                val barWidth = 6.dp.toPx()
                                val barSpacing = 4.dp.toPx()
                                val totalBars = 18
                                val startX = (size.width - (totalBars * (barWidth + barSpacing) - barSpacing)) / 2
                                val startY = size.height * 0.88f
                                
                                for (i in 0 until totalBars) {
                                    val wave = (kotlin.math.sin((i * 0.5f + (rotation.value / 15f)).toDouble()).toFloat() + 1f) / 2f
                                    val barHeight = 8.dp.toPx() + (45.dp.toPx() * wave) * (pulsatingScale.value * 0.9f)
                                    val x = startX + i * (barWidth + barSpacing)
                                    
                                    // Segmented blocks drawing
                                    val numBlocks = 6
                                    val blockHeight = barHeight / numBlocks
                                    for (j in 0 until numBlocks) {
                                        val blockY = startY - j * (blockHeight + 2.dp.toPx())
                                        val blockAlpha = 0.2f + 0.8f * (j.toFloat() / numBlocks)
                                        drawRect(
                                            color = animatedAccent.copy(alpha = blockAlpha),
                                            topLeft = Offset(x, blockY),
                                            size = androidx.compose.ui.geometry.Size(barWidth, blockHeight)
                                        )
                                    }
                                }
                            }
                            "Waveform Ripple" -> {
                                val path = Path()
                                val numPoints = 60
                                val stepX = size.width / numPoints
                                val midY = size.height * 0.85f // aligned bottom
                                
                                path.moveTo(0f, midY)
                                for (i in 0..numPoints) {
                                    val x = i * stepX
                                    val angle1 = (i * 0.18f) + (rotation.value * 0.12f)
                                    val angle2 = (i * 0.35f) - (rotation.value * 0.06f)
                                    val amp = 6.dp.toPx() + (26.dp.toPx() * (pulsatingScale.value - 1f) * 12f)
                                    
                                    val y = midY + (kotlin.math.sin(angle1.toDouble()).toFloat() * amp * 0.7f) + 
                                            (kotlin.math.cos(angle2.toDouble()).toFloat() * amp * 0.3f)
                                    path.lineTo(x, y)
                                }
                                
                                drawPath(
                                    path = path,
                                    color = animatedAccent,
                                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                                
                                // Secondary faint depth wave
                                val path2 = Path()
                                path2.moveTo(0f, midY)
                                for (i in 0..numPoints) {
                                    val x = i * stepX
                                    val angle = (i * 0.22f) - (rotation.value * 0.09f)
                                    val amp = 4.dp.toPx() + (18.dp.toPx() * (pulsatingScale.value - 1f) * 9f)
                                    val y = midY + (kotlin.math.sin(angle.toDouble()).toFloat() * amp)
                                    path2.lineTo(x, y)
                                }
                                
                                drawPath(
                                    path = path2,
                                    color = animatedAccent.copy(alpha = 0.4f),
                                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                            "Star Dust" -> {
                                particles.forEach { p ->
                                    val pX = (p["x"] as? Float ?: 0f) * size.width
                                    val drift = (rotation.value * (p["speed"] as? Float ?: 0f)) % 1f
                                    val baseRealY = (p["y"] as? Float ?: 0f) - drift
                                    val realY = (if (baseRealY < 0f) baseRealY + 1f else baseRealY) * size.height

                                    val sizeMultiplier = 1f + (pulsatingScale.value - 1f) * 8f
                                    val pSize = (p["baseSize"] as? Float ?: 2f) * sizeMultiplier
                                    
                                    drawCircle(
                                        color = animatedAccent.copy(alpha = 0.8f),
                                        radius = pSize / 2,
                                        center = Offset(pX, realY)
                                    )
                                    drawCircle(
                                        color = animatedAccent.copy(alpha = 0.22f),
                                        radius = pSize,
                                        center = Offset(pX, realY)
                                    )
                                }
                            }
                        }
                    }
                    } // end if(isActuallyPlaying) for visualizer Canvas
                }

                // Master Circular Disc with swipe-to-skip or DJ scratch support
                val sizePx = with(density) { 280.dp.toPx() }
                val discCenter = Offset(sizePx / 2, sizePx / 2)
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .shadow(elevation = 24.dp, shape = CircleShape, clip = false, spotColor = animatedAccent)
                        .clip(CircleShape)
                        .border(1.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .graphicsLayer {
                            rotationZ = rotation.value + scratchAngleOffset
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
                    with(sharedTransitionScope) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // Underneath: load the standard low-res thumbnail instantly
                            AsyncImage(
                                model = song.thumbnail,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                            
                            // On top: load the HD thumbnail with shared element transition and error fallback
                            var hdModel by remember(song.videoId) { mutableStateOf<Any>(song.thumbnailHd) }
                            AsyncImage(
                                model = hdModel,
                                contentDescription = null,
                                modifier = Modifier
                                    .sharedElement(
                                        rememberSharedContentState(key = "album_art"),
                                        animatedVisibilityScope = animatedVisibilityScope
                                    )
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                onError = {
                                    if (hdModel == song.thumbnailHd) {
                                        hdModel = song.thumbnail
                                    }
                                }
                            )
                        }
                    }

                    // Concentric Groove Circles Drawing
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val centerPoint = Offset(size.width / 2, size.height / 2)
                        for (i in 1..8) {
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.18f),
                                radius = (size.width / 2) * (0.35f + (i * 0.07f)),
                                center = centerPoint,
                                style = Stroke(width = 1.dp.toPx())
                            )
                        }
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

                // ── Vertical DJ Pitch Fader ──
                if (isDjMode) {
                    val faderProgress = ((vm.playbackSpeed - 0.5f) / 1.0f).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp)
                            .width(36.dp)
                            .height(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.Surface2)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("+50%", fontSize = 8.sp, color = VinColors.Secondary, fontWeight = FontWeight.Bold)

                        val updateSpeedCallback = rememberUpdatedState { speed: Float ->
                            vm.updatePlaybackSpeed(speed)
                        }
                        val updatePitchCallback = rememberUpdatedState { pitch: Float ->
                            vm.updatePlaybackPitch(pitch)
                        }
                        val currentFaderProgress by rememberUpdatedState(faderProgress)

                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .width(24.dp)
                                .pointerInput(Unit) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val heightPx = size.height
                                        if (heightPx > 0) {
                                            val delta = -dragAmount / heightPx
                                            val newProgress = (currentFaderProgress + delta).coerceIn(0f, 1f)
                                            val newSpeed = 0.5f + (newProgress * 1.0f)
                                            updateSpeedCallback.value(newSpeed)
                                            updatePitchCallback.value(newSpeed)
                                        }
                                    }
                                }
                        ) {
                            val fHeight = maxHeight
                            
                            // Tick marks
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val midX = size.width / 2
                                val step = size.height / 10
                                for (i in 0..10) {
                                    val y = i * step
                                    val tickW = if (i == 5) 12.dp.toPx() else 6.dp.toPx()
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.15f),
                                        start = Offset(midX - tickW / 2, y),
                                        end = Offset(midX + tickW / 2, y),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                            }

                            // Track line
                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .fillMaxHeight()
                                    .width(2.dp)
                                    .background(Color.White.copy(alpha = 0.2f))
                            )

                            // Slider Thumb position
                            val thumbHeight = 16.dp
                            val dragRange = fHeight - thumbHeight
                            val yOffset = dragRange * (1f - faderProgress)

                            Box(
                                modifier = Modifier
                                    .offset(y = yOffset)
                                    .size(20.dp, thumbHeight)
                                    .align(Alignment.TopCenter)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFE5E7EB))
                                    .border(1.dp, Color(0xFF374151), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFF1F2937)))
                            }
                        }

                        Text("-50%", fontSize = 8.sp, color = VinColors.Secondary, fontWeight = FontWeight.Bold)
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
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = VinColors.Primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 1500
                        )
                )
                val primaryArtist = remember(song.author) { parseContributors(song.author).firstOrNull() ?: song.author }
                var subscriberCount by remember(primaryArtist) {
                    mutableStateOf(
                        com.vinmusic.data.ArtistDataCache.get(primaryArtist)?.subscriberCount
                            ?.let(::formatMonthlyListenersText)
                            .orEmpty()
                    )
                }
                LaunchedEffect(primaryArtist) {
                    val cached = com.vinmusic.data.ArtistDataCache.get(primaryArtist)
                    if (!cached?.subscriberCount.isNullOrBlank()) {
                        subscriberCount = formatMonthlyListenersText(cached!!.subscriberCount)
                    } else {
                        val resolved = withContext(Dispatchers.IO) {
                            runCatching {
                                val artist = InnerTube.searchAll(primaryArtist).artists
                                    .maxByOrNull { artistSearchScore(primaryArtist, it.name, it.subscriberCount) }
                                artist?.subscriberCount
                                    ?.takeIf { it.isNotBlank() }
                                    ?: InnerTube.fetchChannelData("", primaryArtist).subscriberCount
                            }.getOrDefault("")
                        }
                        subscriberCount = formatMonthlyListenersText(resolved)
                    }
                }
                Text(
                    text = primaryArtist,
                    maxLines = 1,
                    fontSize = 15.sp,
                    color = VinColors.AccentLight,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onArtistNameClick(primaryArtist) }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 2000
                        )
                )
                if (subscriberCount.isNotBlank()) {
                    Text(
                        text = subscriberCount,
                        maxLines = 1,
                        fontSize = 11.sp,
                        color = VinColors.Secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Premium Quick Action Buttons Row (Lyrics, Queue, Remix, Download, Playlist)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    icon = Icons.Default.Equalizer,
                    label = "Equaliser",
                    active = activePanel == "Equaliser",
                    onClick = { activePanel = if (activePanel == "Equaliser") null else "Equaliser" }
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

            PlayerSeekBar(vm = vm, animatedAccent = animatedAccent)

            Spacer(Modifier.height(16.dp))

            // Premium Controls (Radial gradient central play)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    when {
                        !vm.shuffle -> {
                            vm.shuffle = true
                            vm.smartShuffle = false
                        }
                        vm.shuffle && !vm.smartShuffle -> {
                            vm.shuffle = true
                            vm.smartShuffle = true
                        }
                        else -> {
                            vm.shuffle = false
                            vm.smartShuffle = false
                        }
                    }
                }) {
                    Box(modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (vm.shuffle) VinColors.AccentLight else VinColors.Secondary,
                            modifier = Modifier
                                .size(22.dp)
                                .align(Alignment.Center)
                                .alpha(if (vm.shuffle && !vm.smartShuffle) 0.5f else 1.0f)
                        )
                        if (vm.shuffle && vm.smartShuffle) {
                            // Glowing Amber-Gold dot indicator in top-right corner
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEAB308))
                            )
                        }
                    }
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
            CreditsCard(
                author = song.author, 
                description = creditsDescription,
                onArtistClick = onArtistNameClick,
                onOpenCredits = { openCreditsPanel() }
            )

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
                            .fillMaxHeight(if (activePanel == "Credits") 0.96f else 0.75f)
                            .align(Alignment.BottomCenter)
                            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                            .background(VinColors.Surface)
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
                                    if (activePanel != "Credits") {
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
                                    }
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
                                    "Queue"  -> QueuePanel(
                                        vm = vm,
                                        onSaveAsPlaylist = {
                                            panelScope.launch(Dispatchers.IO) {
                                                val playlistId = db.playlistDao().insertPlaylist(
                                                    com.vinmusic.data.db.PlaylistEntity(name = "Queue - ${java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault()).format(java.util.Date())}")
                                                )
                                                val songs = vm.queue.mapIndexed { index, queueSong ->
                                                    com.vinmusic.data.db.PlaylistSongEntity(
                                                        playlistId = playlistId,
                                                        videoId = queueSong.videoId,
                                                        title = queueSong.title,
                                                        author = queueSong.author,
                                                        durationText = queueSong.durationText,
                                                        position = index
                                                    )
                                                }
                                                db.playlistDao().insertSongs(songs)
                                            }
                                        }
                                    )
                                    "Equaliser"  -> RemixPanel(vm)
                                    "Credits" -> FullCreditsPanel(
                                        author = song.author,
                                        description = creditsDescription
                                    )
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

    // Beautiful floating visualizer style badge
    AnimatedVisibility(
        visible = toastMessage.isNotEmpty(),
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp)
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(VinColors.Surface2)
                .border(1.dp, VinColors.GlassBorder, CircleShape)
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                text = toastMessage,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
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

@Composable
private fun PlayerSeekBar(vm: PlayerViewModel, animatedAccent: Color) {
    Slider(
        value = vm.progress,
        onValueChange = { vm.seekTo(it) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(24.dp),
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = VinColors.White20
        )
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatMs(vm.currentTimeMs), fontSize = 12.sp, color = VinColors.Secondary)
        Text(formatMs(vm.durationMs), fontSize = 12.sp, color = VinColors.Secondary)
    }
}

// ── Karaoke Line Composable ─────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun KaraokeLine(
    words: List<com.vinmusic.lyrics.WordTiming>,
    isActive: Boolean,
    activeWordIndex: Int,
    fillFraction: Float,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        words.forEachIndexed { idx, word ->
            val isPast = isActive && idx < activeWordIndex
            val isCurrent = isActive && idx == activeWordIndex
            val wordAlpha = when {
                !isActive -> 0.5f
                isPast -> 1.0f
                isCurrent -> 0.6f + (fillFraction * 0.4f)
                else -> 0.35f
            }
            val wordColor = when {
                !isActive -> Color.White.copy(alpha = 0.5f)
                isPast -> Color.White
                isCurrent -> Color.White.copy(alpha = wordAlpha)
                else -> Color.White.copy(alpha = 0.35f)
            }
            Text(
                text = word.text + if (word.hasTrailingSpace) " " else "",
                fontSize = 18.sp,
                fontWeight = if (isActive && (isPast || isCurrent)) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = wordColor,
                style = androidx.compose.ui.text.TextStyle(
                    shadow = if (isCurrent) Shadow(
                        color = Color.White.copy(alpha = 0.4f),
                        offset = Offset(0f, 0f),
                        blurRadius = 8.dp.value
                    ) else null
                ),
                lineHeight = 24.sp
            )
        }
    }
}

// ── Lyrics Panel ──────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun LyricsPanel(vm: PlayerViewModel) {
    val listState = rememberLazyListState()
    var userInterruptedSync by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Auto-scroll to current lyric index
    LaunchedEffect(vm.currentLyricIndex) {
        if (vm.currentLyricIndex > 1 && !userInterruptedSync) {
            listState.animateScrollToItem((vm.currentLyricIndex - 2).coerceAtLeast(0))
        }
    }

    // Check if user has scrolled away from current lyric - show resync if so
    val isNearCurrentLyric by remember {
        derivedStateOf {
            val firstVisible = listState.firstVisibleItemIndex
            val lastVisible = firstVisible + listState.layoutInfo.visibleItemsInfo.size
            val currentIndex = vm.currentLyricIndex
            // User is "near" current lyric if current index is within visible range ±2
            currentIndex in (firstVisible - 2)..(lastVisible + 2)
        }
    }

    // When user stops scrolling, check if they're out of sync
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress && !isNearCurrentLyric) {
            userInterruptedSync = true
        }
    }

    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editIsSynced by remember { mutableStateOf(false) }
    var showCandidatePicker by remember { mutableStateOf(false) }

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
                    modifier = Modifier.weight(1f),
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
                        color = VinColors.AccentLight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (vm.lyricsResult is LyricsResult.Synced || vm.lyricsResult is LyricsResult.Plain) {
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
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Translate Button
                    IconButton(
                        onClick = { vm.transliterateLyricsToHinglish() },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        if (vm.isTransliterating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = VinColors.AccentLight,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Aa",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight
                            )
                        }
                    }

                    // Source Picker Button
                    IconButton(
                        onClick = {
                            vm.fetchLyricsCandidates()
                            showCandidatePicker = !showCandidatePicker
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (showCandidatePicker) VinColors.Accent.copy(alpha = 0.3f) else VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Lyrics Sources",
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    // Refetch Button
                    IconButton(
                        onClick = { vm.refetchLyrics() },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refetch Lyrics",
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(16.dp)
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
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Lyrics",
                            tint = VinColors.Primary,
                            modifier = Modifier.size(16.dp)
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
                    vm.lyricsResult is LyricsResult.Synced || vm.lyricsResult is LyricsResult.Plain -> {
                        val timelineLines = vm.currentLyricsTimeline()
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            itemsIndexed(timelineLines) { idx, line ->
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
                                        .clickable { vm.seekToMs(line.timeMs - vm.lyricOffsetMs) }
                                        .padding(vertical = 4.dp)
                                ) {
                                    if (line.words != null && line.words.isNotEmpty()) {
                                        KaraokeLine(
                                            words = line.words,
                                            isActive = isActive,
                                            activeWordIndex = if (isActive) vm.currentWordIndex else -1,
                                            fillFraction = if (isActive) vm.wordFillFraction else 0f
                                        )
                                    } else {
                                        Text(
                                            text = line.text,
                                            fontSize = 18.sp,
                                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold,
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

            // Candidate Picker Panel
            if (showCandidatePicker && vm.lyricsCandidates.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = VinColors.Surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Lyrics Sources",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = VinColors.AccentLight,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        vm.lyricsCandidates.forEach { candidate ->
                            val isSelected = candidate.source == vm.selectedLyricsSource
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) VinColors.Accent.copy(alpha = 0.2f) else Color.Transparent)
                                    .clickable {
                                        vm.selectLyricsCandidate(candidate)
                                        showCandidatePicker = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.source,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) VinColors.AccentLight else Color.White
                                    )
                                    Text(
                                        text = "${candidate.lineCount} lines",
                                        fontSize = 10.sp,
                                        color = VinColors.Secondary
                                    )
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (candidate.isSynced) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = VinColors.Accent.copy(alpha = 0.3f)
                                        ) {
                                            Text(
                                                text = "Synced",
                                                fontSize = 9.sp,
                                                color = VinColors.AccentLight,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = VinColors.AccentLight,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Re-sync button - only shows when user has scrolled away from current lyric
        if (userInterruptedSync && !isNearCurrentLyric) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(VinColors.Accent.copy(alpha = 0.9f))
                        .clickable {
                            userInterruptedSync = false
                            // Scroll back to current lyric
                            if (vm.currentLyricIndex > 1) {
                                scope.launch {
                                    listState.animateScrollToItem((vm.currentLyricIndex - 2).coerceAtLeast(0))
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Re-sync",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Re-sync",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
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

@OptIn(UnstableApi::class, ExperimentalFoundationApi::class)
@Composable
fun QueuePanel(vm: PlayerViewModel, onSaveAsPlaylist: (() -> Unit)? = null) {
    var showMoveDialog by remember { mutableStateOf(false) }
    var selectedMoveIndex by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current

    // Move-to-position dialog
    if (showMoveDialog && selectedMoveIndex in vm.queue.indices) {
        MoveToPositionDialog(
            songTitle = vm.queue[selectedMoveIndex].title,
            currentPosition = selectedMoveIndex + 1,
            queueSize = vm.queue.size,
            onMove = { targetPosition ->
                val targetIndex = (targetPosition - 1).coerceIn(0, vm.queue.size - 1)
                vm.moveQueueItem(selectedMoveIndex, targetIndex)
                showMoveDialog = false
                selectedMoveIndex = -1
            },
            onDismiss = {
                showMoveDialog = false
                selectedMoveIndex = -1
            }
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Queue", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)
                Spacer(Modifier.weight(1f))
                if (onSaveAsPlaylist != null && vm.queue.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.White10)
                            .clickable { onSaveAsPlaylist() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.PlaylistAdd,
                                contentDescription = null,
                                tint = VinColors.AccentLight,
                                modifier = Modifier.size(14.dp)
                            )
                            Text("Save", fontSize = 11.sp, color = VinColors.Primary)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                        modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(VinColors.White10)
                        .clickable { vm.smartSortQueueByBPM() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = null,
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("Smart Sort by BPM", fontSize = 11.sp, color = VinColors.Primary)
                    }
                }
            }
        }
        // Queues intentionally allow a track to be added more than once.
        itemsIndexed(vm.queue, key = { index, song -> "queue_${song.videoId}_$index" }) { i, song ->
            Row(modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (i == vm.queueIndex) VinColors.White10 else Color.Transparent)
                .combinedClickable(
                    onClick = { vm.skipToIndex(i) },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selectedMoveIndex = i
                        showMoveDialog = true
                    }
                )
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

// ── Move to Position Dialog ──────────────────────────────────────────────────

@Composable
fun MoveToPositionDialog(
    songTitle: String,
    currentPosition: Int,
    queueSize: Int,
    onMove: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var positionText by remember { mutableStateOf(currentPosition.toString()) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VinColors.Surface2,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        title = {
            Text("Move Song", color = VinColors.Primary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = songTitle,
                    color = VinColors.AccentLight,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Currently at position $currentPosition of $queueSize",
                    color = VinColors.Secondary,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = positionText,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() } && newValue.length <= 4) {
                            positionText = newValue
                            val num = newValue.toIntOrNull()
                            isError = num == null || num < 1 || num > queueSize
                        }
                    },
                    label = { Text("Move to position (1-$queueSize)", color = VinColors.Secondary) },
                    isError = isError,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = VinColors.Primary,
                        unfocusedTextColor = VinColors.Primary,
                        cursorColor = VinColors.Accent,
                        focusedIndicatorColor = VinColors.Accent,
                        unfocusedIndicatorColor = VinColors.GlassBorder,
                        errorIndicatorColor = Color(0xFFFF5555)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = positionText.toIntOrNull()
                    if (target != null && target in 1..queueSize) {
                        onMove(target)
                    }
                },
                enabled = !isError && positionText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
            ) {
                Text("Move", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = VinColors.Secondary)
            }
        }
    )
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
                    active = vm.eqPreset == "Treble+",
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Treble+" }
                        if (preset != null) vm.applyPreset(preset)
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "Slowed + Reverb",
                    icon = "",
                    active = vm.isSlowedReverb,
                    onClick = {
                        vm.toggleSlowedReverb()
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "Concert Hall",
                    icon = "",
                    active = vm.concertHallEnabled,
                    onClick = {
                        val preset = EQ_PRESETS.find { it.name == "Concert Hall" }
                        if (preset != null) vm.applyPreset(preset) else vm.updateConcertHallEnabled(!vm.concertHallEnabled)
                    }
                )
            }
            item {
                SmartEQPresetChip(
                    name = "8D Audio Mode",
                    icon = "",
                    active = vm.is8dEnabled,
                    onClick = {
                        vm.toggle8dAudio()
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
            air = vm.eqAir,
            onSubBassChange = { vm.eqSubBass = it; vm.applyEQ() },
            onBassChange = { vm.eqBass = it; vm.applyEQ() },
            onLowMidChange = { vm.eqLowMid = it; vm.applyEQ() },
            onMidChange = { vm.eqMid = it; vm.applyEQ() },
            onTrebleChange = { vm.eqTreble = it; vm.applyEQ() },
            onAirChange = { vm.eqAir = it; vm.applyEQ() }
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
    val db = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()
    var isDownloaded by remember(song.videoId) { mutableStateOf(false) }

    LaunchedEffect(song.videoId) {
        withContext(Dispatchers.IO) {
            val existing = db.downloadDao().get(song.videoId)
            isDownloaded = existing != null && existing.status == "completed"
        }
    }

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
        val options = mutableListOf(
            Opt(Icons.Default.Favorite,    if (vm.isLiked(song.videoId)) "Unlike" else "Like Song") { vm.toggleLike(song); onDismiss() },
            Opt(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to Playlist") { onAddToPlaylist(); onDismiss() },
        )

        if (isDownloaded) {
            options.add(Opt(Icons.Default.Delete, "Remove Download") {
                scope.launch(Dispatchers.IO) {
                    val download = db.downloadDao().get(song.videoId)
                    if (download != null) {
                        // Remove from Media3 cache
                        try {
                            val downloadCache = com.vinmusic.player.PlayerSingleton.getDownloadCache(ctx)
                            downloadCache?.removeResource(song.videoId)
                        } catch (_: Exception) {}

                        // Delete thumbnail file
                        download.thumbnailPath?.let { path ->
                            try { java.io.File(path).delete() } catch (_: Exception) {}
                        }

                        // Remove from database
                        db.downloadDao().delete(song.videoId)

                        // Update interaction signal
                        val signal = db.interactionSignalDao().get(song.videoId)
                        if (signal != null) {
                            signal.isDownloaded = false
                            db.interactionSignalDao().insert(signal)
                        }

                        withContext(Dispatchers.Main) {
                            isDownloaded = false
                            android.widget.Toast.makeText(ctx, "Download removed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                onDismiss()
            })
        } else {
            options.add(Opt(Icons.Default.Download, "Download Song") {
                val intent = android.content.Intent(ctx, com.vinmusic.download.DownloadService::class.java).apply {
                    action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, song.videoId)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, song.title)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, song.author)
                    putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, song.durationText)
                }
                ctx.startService(intent)
                onDismiss()
            })
        }

        options.add(Opt(Icons.Default.Timer,       "Sleep Timer") { onSleepTimer() })
        options.add(Opt(Icons.Default.Share,       "Share") { onDismiss() })

        options.forEach { opt ->
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
    air: Float,
    onSubBassChange: (Float) -> Unit = {},
    onBassChange: (Float) -> Unit = {},
    onLowMidChange: (Float) -> Unit = {},
    onMidChange: (Float) -> Unit = {},
    onTrebleChange: (Float) -> Unit = {},
    onAirChange: (Float) -> Unit = {}
) {
    val callbacks = listOf(onSubBassChange, onBassChange, onLowMidChange, onMidChange, onTrebleChange, onAirChange)
    var activeDragIndex by remember { mutableIntStateOf(-1) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val width = size.width.toFloat()
                        val stepX = width / 5f
                        val heights = listOf(subBass, bass, lowMid, mid, treble, air)
                        val midY = size.height / 2f
                        var closestIndex = -1
                        var closestDist = Float.MAX_VALUE
                        heights.forEachIndexed { i, db ->
                            val fraction = db / 15f
                            val y = midY - (fraction * (size.height / 2.5f))
                            val x = i * stepX
                            val dx = (offset.x - x).toDouble()
                            val dy = (offset.y - y).toDouble()
                            val dist = kotlin.math.sqrt(dx * dx + dy * dy).toFloat()
                            if (dist < closestDist && dist < 40.dp.toPx()) {
                                closestDist = dist
                                closestIndex = i
                            }
                        }
                        activeDragIndex = closestIndex
                    },
                    onDrag = { change, _ ->
                        if (activeDragIndex in 0..5) {
                            val width = size.width.toFloat()
                            val height = size.height.toFloat()
                            val midY = height / 2f
                            val newY = change.position.y.coerceIn(8.dp.toPx(), height - 8.dp.toPx())
                            val fraction = (midY - newY) / (height / 2.5f)
                            val dbValue = (fraction * 15f).coerceIn(-15f, 15f)
                            callbacks[activeDragIndex](dbValue)
                        }
                    },
                    onDragEnd = { activeDragIndex = -1 },
                    onDragCancel = { activeDragIndex = -1 }
                )
            }
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

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(VinColors.Surface2)
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
                vm.lyricsResult is LyricsResult.Synced || vm.lyricsResult is LyricsResult.Plain -> {
                    val lines = vm.currentLyricsTimeline()
                    val activeIndex = vm.currentLyricIndex
                    
                    val displayLines = remember(activeIndex, lines) {
                        val list = mutableListOf<Pair<Int, String>>()
                        val start = (activeIndex - 1).coerceAtLeast(0)
                        val end = (activeIndex + 3).coerceAtMost(lines.size - 1)
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

// ── About the Artist Card ───────────────────────────────────────────────────
@Composable
fun AboutArtistCard(artistName: String, onArtistNameClick: (String) -> Unit) {
    val cleanName = remember(artistName) {
        parseContributors(artistName).firstOrNull()?.replace("-topic", "", ignoreCase = true)?.replace("- topic", "", ignoreCase = true)?.trim() ?: artistName
    }
    
    val cachedInitial = remember(cleanName) { com.vinmusic.data.ArtistDataCache.get(cleanName) }
    var isFollowing by remember { mutableStateOf(false) }
    var artistImageUrl by remember { mutableStateOf<String?>(cachedInitial?.avatarUrl?.takeIf { it.isNotBlank() }) }
    var bannerImageUrl by remember { mutableStateOf<String?>(cachedInitial?.bannerUrl?.takeIf { it.isNotBlank() }) }
    var officialAudience by remember { mutableStateOf(formatMonthlyListenersText(cachedInitial?.subscriberCount.orEmpty())) }
    var isVerifiedArtist by remember { mutableStateOf(cachedInitial?.isVerified ?: shouldShowVerifiedArtist(cleanName, "", "")) }

    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(cleanName) {
        if (cachedInitial == null || (cachedInitial.avatarUrl.isBlank() && cachedInitial.bannerUrl.isBlank())) {
            artistImageUrl = null
            bannerImageUrl = null
            officialAudience = ""
            isVerifiedArtist = false
        }

        val localBanner = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.vinmusic.download.ArtistBannerCache.bannerPath(ctx, cleanName)
        }
        if (localBanner != null) {
            bannerImageUrl = localBanner
        }

        val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val res = com.vinmusic.innertube.InnerTube.searchAll(cleanName)
                val artist = res.artists.maxByOrNull { artistSearchScore(cleanName, it.name, it.subscriberCount) }
                val channelData = runCatching { com.vinmusic.innertube.InnerTube.fetchChannelData(artist?.channelId.orEmpty(), cleanName) }.getOrNull()

                if (localBanner == null && channelData?.bannerUrl.isNullOrBlank().not()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.vinmusic.download.ArtistBannerCache.downloadBanner(ctx, cleanName, artist?.channelId.orEmpty())
                    }
                }

                Triple(
                    channelData?.avatarUrl.orEmpty().ifBlank { artist?.thumbnail.orEmpty() },
                    channelData?.bannerUrl.orEmpty(),
                    formatMonthlyListenersText(artist?.subscriberCount?.ifBlank { channelData?.subscriberCount.orEmpty() }.orEmpty())
                )
            }.getOrNull()
        }
        if (resolved != null) {
            if (resolved.first.isNotBlank()) artistImageUrl = resolved.first
            if (bannerImageUrl == null && resolved.second.isNotBlank()) {
                bannerImageUrl = resolved.second
            }
            if (resolved.third.isNotBlank()) officialAudience = resolved.third
            isVerifiedArtist = shouldShowVerifiedArtist(cleanName, "", "")
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
    ) {
        val bgModel = bannerImageUrl ?: artistImageUrl
        if (bgModel != null) {
            AsyncImage(
                model = bgModel,
                contentDescription = "Artist Background",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.8f))
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.7f))
                    )
                )
        )
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White, CircleShape)
                        .background(VinColors.White10),
                    contentAlignment = Alignment.Center
                ) {
                    if (artistImageUrl != null) {
                        AsyncImage(
                            model = artistImageUrl,
                            contentDescription = "Artist Image",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = cleanName.take(1).uppercase(),
                            fontSize = 38.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = cleanName,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isVerifiedArtist) {
                            VerifiedArtistBadge()
                        }
                    }
                    if (officialAudience.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = officialAudience,
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.72f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isFollowing) Color.White.copy(alpha = 0.2f) else Color.White)
                        .border(1.dp, if (isFollowing) Color.White else Color.Transparent, RoundedCornerShape(22.dp))
                        .clickable { isFollowing = !isFollowing }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = if (isFollowing) "Following" else "Follow",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isFollowing) Color.White else Color.Black
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistNameClick(cleanName) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
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
            .background(VinColors.Surface2)
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
                .clip(RoundedCornerShape(14.dp))
                .scale(1.3f),
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

//  Credits Card
@Composable
fun CreditsCard(
    author: String,
    description: String?,
    onArtistClick: (String) -> Unit,
    onOpenCredits: () -> Unit
) {
    val contributors = remember(author) { parseContributors(author) }
    val allCredits = remember(author, description) { buildFullSongCredits(author, description) }

    val initialImages = remember(contributors) {
        val map = mutableMapOf<String, String>()
        contributors.forEach { name ->
            val cached = com.vinmusic.data.ArtistDataCache.get(name)
            if (cached != null && cached.avatarUrl.isNotBlank()) {
                map[name] = cached.avatarUrl
            }
        }
        map
    }
    var artistImages by remember(contributors) { mutableStateOf<Map<String, String>>(initialImages) }

    LaunchedEffect(contributors) {
        if (contributors.isEmpty()) return@LaunchedEffect
        val images = mutableMapOf<String, String>().apply { putAll(initialImages) }
        withContext(Dispatchers.IO) {
            coroutineScope {
                val jobs = contributors.filterNot { images.containsKey(it) }.map { name ->
                    async {
                        try {
                            val res = com.vinmusic.innertube.InnerTube.searchAll(name)
                            val artist = res.artists.maxByOrNull { it.subscriberCount.toLongOrNull() ?: 0L }
                            if (artist != null && artist.thumbnail.isNotBlank()) {
                                name to artist.thumbnail
                            } else null
                        } catch (_: Exception) { null }
                    }
                }
                val results = jobs.awaitAll().filterNotNull()
                for ((k, v) in results) {
                    images[k] = v
                }
            }
        }
        artistImages = images
    }

    if (contributors.isEmpty() && allCredits.isEmpty()) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        VinColors.Surface2,
                        VinColors.Surface
                    )
                )
            )
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = VinColors.AccentLight,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "SONG CREDITS",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.AccentLight,
                        letterSpacing = 1.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.dp, VinColors.AccentLight.copy(alpha = 0.65f), RoundedCornerShape(22.dp))
                        .clickable { onOpenCredits() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = VinColors.AccentLight,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Credits",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = VinColors.AccentLight
                        )
                    }
                }
            }

            if (contributors.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Performed by",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.74f),
                        letterSpacing = 0.3.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    CreditRowItem(name = contributors[0], role = "Main Artist", imageUrl = artistImages[contributors[0]]) {
                        onArtistClick(contributors[0])
                    }

                    contributors.drop(1).forEach { name ->
                        CreditRowItem(name = name, role = "Featured Artist", imageUrl = artistImages[name]) {
                            onArtistClick(name)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerifiedArtistBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(VinColors.AccentLight),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Verified",
            tint = Color.Black,
            modifier = Modifier.size(12.dp)
        )
    }
}

private fun artistSearchScore(query: String, name: String, audience: String): Int {
    val cleanQuery = normalizeCreditToken(query)
    val cleanName = normalizeCreditToken(name)
    var score = 0
    if (cleanName == cleanQuery) score += 100
    if (cleanName.contains(cleanQuery) || cleanQuery.contains(cleanName)) score += 40
    score += officialAudienceNumber(audience).coerceAtMost(10_000_000.0).div(100_000).toInt()
    return score
}

private fun shouldShowVerifiedArtist(query: String, name: String, audience: String): Boolean {
    val cleanQuery = normalizeCreditToken(query)
    val cleanName = normalizeCreditToken(name)
    val closeMatch = cleanName == cleanQuery || cleanName.contains(cleanQuery) || cleanQuery.contains(cleanName)
    if (!closeMatch || cleanName.isBlank()) return false
    
    val hasVerifiedMetric = audience.contains("M", ignoreCase = true) || 
                            audience.contains("K", ignoreCase = true) ||
                            audience.contains("subscribers", ignoreCase = true) ||
                            audience.contains("listeners", ignoreCase = true)
    return hasVerifiedMetric || com.vinmusic.innertube.InnerTube.verifiedArtistCache[cleanQuery] == true
}

private fun formatMonthlyListenersText(sourceText: String): String {
    val raw = sourceText.trim()
    if (raw.isBlank()) return ""
    val compact = raw
        .replace(Regex("""@\S+"""), "")
        .replace("subscribers", "", ignoreCase = true)
        .replace("subscriber", "", ignoreCase = true)
        .replace(Regex("""\bartist\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[•|·]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (compact.isBlank()) return ""
    return "$compact Subscribers"
}

private fun officialAudienceNumber(text: String): Double {
    val match = Regex("""([\d,.]+)\s*([KMB])?""", RegexOption.IGNORE_CASE).find(text) ?: return 0.0
    val base = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0.0
    val mult = when (match.groupValues.getOrNull(2)?.uppercase()) {
        "K" -> 1_000.0
        "M" -> 1_000_000.0
        "B" -> 1_000_000_000.0
        else -> 1.0
    }
    return base * mult
}

@Composable
fun FullCreditsPanel(author: String, description: String?) {
    val credits = remember(author, description) { buildFullSongCredits(author, description) }

    if (credits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No credits found.", color = VinColors.Secondary, fontSize = 14.sp)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        items(credits, key = { "${it.first}|${it.second}" }) { (role, name) ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(VinColors.White10)
                    .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = role,
                    fontSize = 12.sp,
                    color = VinColors.AccentLight,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = name,
                    fontSize = 16.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun CreditRowItem(name: String, role: String, imageUrl: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                VinColors.Accent.copy(alpha = 0.2f),
                                VinColors.AccentLight.copy(alpha = 0.1f)
                            )
                        )
                    )
                    .border(1.dp, VinColors.AccentLight.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (imageUrl != null) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = name,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = name.take(1).uppercase(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = VinColors.AccentLight
                    )
                }
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
                    text = role,
                    fontSize = 11.sp,
                    color = VinColors.Secondary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = VinColors.Secondary.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
}

fun buildFullSongCredits(author: String, description: String?): List<Pair<String, String>> {
    val contributors = parseContributors(author)
    val credits = mutableListOf<Pair<String, String>>()
    contributors.forEachIndexed { index, name ->
        credits.add((if (index == 0) "Main Artist" else "Featured Artist") to name)
    }
    credits.addAll(parseDescriptionCredits(description))
    return credits
        .map { cleanCreditRole(it.first) to cleanCreditName(it.second) }
        .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        .distinctBy { "${normalizeCreditToken(it.first)}|${normalizeCreditToken(it.second)}" }
}

fun parseContributors(author: String): List<String> {
    val cleanAuthor = author.replace("-topic", "", ignoreCase = true).replace("- topic", "", ignoreCase = true).trim()
    val separators = Regex("""\s*(?:feat\.?|ft\.?|&|,|and)\s*""", RegexOption.IGNORE_CASE)
    return cleanAuthor.split(separators)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}

private fun cleanCreditRole(value: String): String =
    value
        .replace("Â·", " ")
        .replace("â€¢", " ")
        .replace("℗", "Phonographic copyright")
        .replace("©", "Copyright")
        .trim()
        .trim(':', '-', '–', '—', '.', ',', ';')
        .replace(Regex("""\s+"""), " ")
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun cleanCreditName(value: String): String =
    value
        .replace("Â·", " ")
        .replace("â€¢", " ")
        .trim()
        .trim(':', '-', '–', '—', '.', ',', ';')
        .replace(Regex("""\s+"""), " ")

private fun normalizeCreditToken(value: String): String =
    value.lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

fun parseDescriptionCredits(description: String?): List<Pair<String, String>> {
    if (description.isNullOrEmpty()) return emptyList()
    val lines = description
        .replace("\\n", "\n")
        .replace("\u00A0", " ")
        .lines()
    val credits = mutableListOf<Pair<String, String>>()
    val creditKeywords = listOf(
        "producer", "composer", "lyricist", "lyric", "vocal", "singer", "performer",
        "associated performer", "featured", "featuring", "mixer", "mixing", "mastering",
        "writer", "arranger", "music", "artist", "engineer", "recording", "studio",
        "programmer", "programming", "guitar", "bass", "drum", "keyboard", "piano",
        "synth", "conductor", "brass", "string", "harp", "flute", "percussion",
        "publisher", "label", "released on", "provided by", "a&r"
    )
    val roleSeparators = Regex("""\s*(?::|-|\u2013|\u2014|\u2022|·)\s*""")
    val nameSeparators = Regex("""\s*(?:&|,|;|\band\b)\s*""", RegexOption.IGNORE_CASE)

    fun cleanRole(value: String): String =
        value.trim()
            .removePrefix("•")
            .removePrefix("-")
            .trim()
            .replace(Regex("""\s+"""), " ")

    fun cleanName(value: String): String =
        value.trim()
            .trim('.', ',', ';', '-', '•')
            .replace(Regex("""\s+"""), " ")

    fun addCredit(roleRaw: String, namesRaw: String) {
        val role = cleanRole(roleRaw)
        val names = namesRaw
            .removePrefix(":")
            .trim()
        if (role.isBlank() || names.isBlank()) return
        val isMusicCredit = creditKeywords.any { role.contains(it, ignoreCase = true) }
        if (!isMusicCredit) return

        names.split(nameSeparators)
            .map { cleanName(it) }
            .filter { name ->
                name.isNotEmpty() &&
                    name.length < 90 &&
                    !name.contains("http", ignoreCase = true) &&
                    !name.equals("auto-generated by youtube", ignoreCase = true)
            }
            .forEach { name -> credits.add(role to name) }
    }
    
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isBlank()) continue
        if (trimmed.contains("auto-generated by youtube", ignoreCase = true)) continue

        if (trimmed.startsWith("Provided to YouTube by", ignoreCase = true)) {
            val provider = trimmed.substringAfter("Provided to YouTube by").trim()
            if (provider.isNotEmpty() && provider.length < 90) {
                credits.add("Provided by" to provider)
            }
            continue
        }

        if (trimmed.startsWith("℗") || trimmed.startsWith("©")) {
            val role = if (trimmed.startsWith("℗")) "Phonographic copyright" else "Copyright"
            credits.add(role to trimmed.drop(1).trim())
            continue
        }

        val colonSplit = trimmed.split(":", limit = 2)
        if (colonSplit.size == 2) {
            addCredit(colonSplit[0], colonSplit[1])
            continue
        }

        val looseSplit = roleSeparators.split(trimmed, limit = 2)
        if (looseSplit.size == 2) {
            addCredit(looseSplit[0], looseSplit[1])
            continue
        }

        val releasedOn = Regex("""released\s+on\s+(.+)""", RegexOption.IGNORE_CASE).find(trimmed)
        if (releasedOn != null) {
            releasedOn.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                credits.add("Released on" to it)
            }
        }
    }
    return credits.distinctBy { "${it.first.lowercase()}|${it.second.lowercase()}" }
}

@Composable
fun SmartEQPresetChip(
    name: String,
    icon: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1.0f) }

    Box(
        modifier = Modifier
            .graphicsLayer(scaleX = scale.value, scaleY = scale.value)
            .clip(RoundedCornerShape(20.dp))
            .background(if (active) VinColors.Accent else VinColors.White10)
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
