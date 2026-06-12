package com.vinmusic.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import com.vinmusic.ui.utils.ColorExtractor

// ── Mini Player ───────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun MiniPlayer(
    vm: PlayerViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onClick: () -> Unit
) {
    val song = vm.currentSong ?: return

    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // Dynamic dominant color extraction for progress bar
    var dominantColor by remember(song.videoId) { mutableStateOf(VinColors.Accent) }
    LaunchedEffect(song.thumbnail) {
        try {
            val palette = ColorExtractor.extractColorsFromUrl(ctx, song.thumbnail)
            dominantColor = palette.accent
        } catch (_: Exception) {}
    }

    // Animated wave phase — only animates when playing to save CPU
    val isPlaying = vm.isPlaying && !vm.isLoading
    val wavePhase = remember { Animatable(0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Continuously animate wave phase while playing
            while (true) {
                wavePhase.animateTo(
                    targetValue = wavePhase.value + (2 * Math.PI).toFloat(),
                    animationSpec = tween(durationMillis = 3500, easing = LinearEasing)
                )
            }
        }
        // When paused, animation simply stops — no CPU usage
    }

    val amplitudeMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.15f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "amplitudeMultiplier"
    )
    val speedMultiplier by animateFloatAsState(
        targetValue = if (isPlaying) 1.0f else 0.05f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "speedMultiplier"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
    ) {
        // The actual capsule
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(VinColors.Surface2)
                .border(
                    BorderStroke(
                        0.8.dp,
                        VinColors.GlassBorder
                    ),
                    RoundedCornerShape(20.dp)
                )
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value > 150f) {
                                    vm.playPrev()
                                } else if (offsetX.value < -150f) {
                                    vm.playNext()
                                }
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onHorizontalDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo(offsetX.value + dragAmount)
                            }
                        }
                    )
                }
                .clickable { onClick() }
        ) {
            // Multi-layered Fluid Wave Canvas Visualizer aligned to bottom (optimized: 6px steps)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.BottomStart)
            ) {
                val width = size.width
                val height = size.height
                val step = 6 // Draw every 6px instead of every pixel for 6x performance boost

                // Wave 1: Deep Accent Base wave
                val path1 = Path().apply {
                    moveTo(0f, height)
                    var x = 0
                    while (x <= width.toInt()) {
                        val angle = 2 * Math.PI * 1.0f * (x / width) + (wavePhase.value * speedMultiplier)
                        val y = height - 6.dp.toPx() - (6.dp.toPx() * amplitudeMultiplier * Math.sin(angle)).toFloat()
                        lineTo(x.toFloat(), y)
                        x += step
                    }
                    lineTo(width, height)
                    close()
                }
                drawPath(path1, color = dominantColor.copy(alpha = 0.2f))

                // Wave 2: Middle Accent wave (flowing opposite)
                val path2 = Path().apply {
                    moveTo(0f, height)
                    var x = 0
                    while (x <= width.toInt()) {
                        val angle = 2 * Math.PI * 1.6f * (x / width) - (wavePhase.value * 1.2f * speedMultiplier) + 2.0f
                        val y = height - 5.dp.toPx() - (4.dp.toPx() * amplitudeMultiplier * Math.sin(angle)).toFloat()
                        lineTo(x.toFloat(), y)
                        x += step
                    }
                    lineTo(width, height)
                    close()
                }
                drawPath(path2, color = dominantColor.copy(alpha = 0.35f))

                // Wave 3: Top Glowing Sparkle wave
                val path3 = Path().apply {
                    moveTo(0f, height)
                    var x = 0
                    while (x <= width.toInt()) {
                        val angle = 2 * Math.PI * 2.2f * (x / width) + (wavePhase.value * 0.8f * speedMultiplier) + 4.0f
                        val y = height - 4.dp.toPx() - (3.dp.toPx() * amplitudeMultiplier * Math.sin(angle)).toFloat()
                        lineTo(x.toFloat(), y)
                        x += step
                    }
                    lineTo(width, height)
                    close()
                }
                drawPath(path3, color = dominantColor.copy(alpha = 0.5f))
            }

            // Progress bar at bottom
            LinearProgressIndicator(
                progress = { vm.progress },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomStart),
                color    = dominantColor,
                trackColor = VinColors.Surface
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art with ambient glow
                Box(
                    modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp))
                ) {
                    with(sharedTransitionScope) {
                        AsyncImage(
                            model = song.thumbnail, contentDescription = null,
                            modifier = Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "album_art"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
                                .scale(1.35f), 
                            contentScale = ContentScale.Crop
                        )
                    }
                    if (vm.isLoading) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0x80000000)),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = dominantColor,
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

            // Song info
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)
                Text(song.author, maxLines = 1,
                    fontSize = 12.sp, color = VinColors.Secondary)
            }

            Spacer(Modifier.width(4.dp))

            // Controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.playPrev() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipPrevious, null, tint = VinColors.Secondary, modifier = Modifier.size(22.dp))
                }
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(VinColors.Accent)
                        .clickable { vm.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null, tint = Color.White, modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { vm.playNext() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipNext, null, tint = VinColors.Secondary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
}

// ── Bottom Nav Bar ────────────────────────────────────────────────────────────

data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

val NAV_ITEMS = listOf(
    NavItem("home",      "Home",      Icons.Default.Home),
    NavItem("search",    "Search",    Icons.Default.Search),
    NavItem("library",   "Library",   Icons.Default.LibraryMusic),
    NavItem("downloads", "Downloads", Icons.Default.Download),
    NavItem("settings",  "Profile",   Icons.Default.Person)
)

@Composable
fun BottomNavBar(currentRoute: String, onNavigate: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp) // premium floating margin
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp) // sleek, premium capsule height
                .clip(CircleShape)
                .background(VinColors.Surface2)
                .border(
                    BorderStroke(1.dp, VinColors.GlassBorder),
                    CircleShape
                )
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NAV_ITEMS.forEach { item ->
                val selected = currentRoute == item.route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onNavigate(item.route) }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val scale by animateFloatAsState(
                        targetValue = if (selected) 1.15f else 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        ),
                        label = "active_nav_scale"
                    )

                    Column(
                        modifier = Modifier
                            .scale(scale)
                            .clip(CircleShape)
                            .background(
                                if (selected) VinColors.Surface else Color.Transparent
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) VinColors.GlassBorder else Color.Transparent,
                                shape = CircleShape
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = item.label,
                            tint = if (selected) VinColors.AccentLight else Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Song list item ─────────────────────────────────────────────────────────────

@Composable
fun SongListItem(
    song: VideoItem,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "song_card_press"
    )

    val isOfficial = remember(song) {
        val titleLower = song.title.lowercase()
        val authorLower = song.author.lowercase()
        titleLower.contains("official audio") ||
        titleLower.contains("official video") ||
        titleLower.contains("music video") ||
        titleLower.contains("lyric video") ||
        authorLower.endsWith(" - topic") ||
        authorLower.endsWith("-topic") ||
        authorLower.contains("vevo")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPlaying) VinColors.AccentGlow else VinColors.White10)
            .border(1.dp, if (isPlaying) VinColors.Accent.copy(alpha = 0.4f) else VinColors.GlassBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Album art
            Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = song.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize().scale(1.35f), contentScale = ContentScale.Crop
                )
                if (isPlaying) {
                    Box(Modifier.fillMaxSize().background(Color(0x60000000)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null,
                            tint = VinColors.AccentLight, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (isPlaying) VinColors.AccentLight else VinColors.Primary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(song.author, maxLines = 1, fontSize = 13.sp, color = VinColors.Secondary, modifier = Modifier.weight(1f, fill = false))
                    if (song.durationText.isNotEmpty()) {
                        Text("• ${song.durationText}", fontSize = 13.sp, color = VinColors.Secondary)
                    }
                    if (isOfficial) {
                        Spacer(Modifier.width(2.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(VinColors.Accent.copy(alpha = 0.15f))
                                .border(0.5.dp, VinColors.Accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = VinColors.AccentLight,
                                modifier = Modifier.size(9.dp)
                            )
                            Text(
                                text = "OFFICIAL",
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight
                            )
                        }
                    }
                }
            }

            if (onMore != null) {
                IconButton(
                    onClick = onMore,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = VinColors.Secondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

// ── Track card (horizontal scroll) ───────────────────────────────────────────

@Composable
fun TrackCard(song: VideoItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "track_card_scale"
    )

    Column(
        modifier = Modifier
            .width(160.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(20.dp))
            .background(VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(140.dp).clip(RoundedCornerShape(14.dp))) {
            AsyncImage(
                model = song.thumbnail, contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.35f), contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x60000000)))
            ))
        }
        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(song.author, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, color = VinColors.Secondary, fontWeight = FontWeight.Medium)
    }
}

// ── Song Options bottom sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongOptionsSheet(
    song: VideoItem,
    isLiked: Boolean,
    isDownloaded: Boolean,
    onLikeToggle: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownloadToggle: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VinColors.Surface2.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = VinColors.Secondary.copy(alpha = 0.4f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = VinColors.Primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = song.author,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        color = VinColors.Secondary
                    )
                }
            }

            HorizontalDivider(color = VinColors.GlassBorder, thickness = 1.dp)

            // Options List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OptionRow(
                    icon = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    iconTint = if (isLiked) VinColors.Pink else VinColors.Secondary,
                    text = if (isLiked) "Remove from Liked Songs" else "Like Song",
                    onClick = {
                        onLikeToggle()
                        onDismiss()
                    }
                )

                OptionRow(
                    icon = Icons.Default.PlaylistAdd,
                    text = "Add to Playlist",
                    onClick = {
                        onAddToPlaylist()
                        onDismiss()
                    }
                )

                OptionRow(
                    icon = if (isDownloaded) Icons.Default.Delete else Icons.Default.Download,
                    iconTint = if (isDownloaded) VinColors.Pink else VinColors.Secondary,
                    text = if (isDownloaded) "Remove Download" else "Download Offline",
                    onClick = {
                        onDownloadToggle()
                        onDismiss()
                    }
                )

                OptionRow(
                    icon = Icons.Default.Share,
                    text = "Share Song",
                    onClick = {
                        onShare()
                        onDismiss()
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = VinColors.Secondary,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = text,
            color = VinColors.Primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Playlist Options bottom sheet ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistOptionsSheet(
    playlistName: String,
    isPinned: Boolean = false,
    onTogglePin: () -> Unit,
    onDownloadPlaylist: () -> Unit,
    onDeletePlaylist: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VinColors.Surface2.copy(alpha = 0.95f),
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = VinColors.Secondary.copy(alpha = 0.4f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Header info
            Text(
                text = playlistName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = VinColors.Primary,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            HorizontalDivider(color = VinColors.GlassBorder, thickness = 1.dp)

            // Options List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OptionRow(
                    icon = Icons.Default.PushPin,
                    iconTint = if (isPinned) VinColors.AccentLight else VinColors.Primary,
                    text = if (isPinned) "Unpin Playlist" else "Pin Playlist",
                    onClick = {
                        onTogglePin()
                        onDismiss()
                    }
                )

                OptionRow(
                    icon = Icons.Default.Download,
                    text = "Download Playlist Songs",
                    onClick = {
                        onDownloadPlaylist()
                        onDismiss()
                    }
                )

                OptionRow(
                    icon = Icons.Default.Delete,
                    iconTint = VinColors.Pink,
                    text = "Delete Playlist",
                    onClick = {
                        onDeletePlaylist()
                        onDismiss()
                    }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Add to Playlist Dialog ─────────────────────────────────────────────────────

@Composable
fun AddToPlaylistDialog(
    playlists: List<com.vinmusic.data.db.PlaylistEntity>,
    onCreatePlaylist: (String) -> Unit,
    onPlaylistSelected: (com.vinmusic.data.db.PlaylistEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var newPlaylistName by remember { mutableStateOf("") }
    var showCreateField by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VinColors.Surface2,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 8.dp,
        title = {
            Text("Add to Playlist", color = VinColors.Primary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (showCreateField) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Playlist Name", color = VinColors.Secondary) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = VinColors.Primary,
                            unfocusedTextColor = VinColors.Primary,
                            cursorColor = VinColors.Accent,
                            focusedIndicatorColor = VinColors.Accent,
                            unfocusedIndicatorColor = VinColors.GlassBorder
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showCreateField = false }) {
                            Text("Cancel", color = VinColors.Secondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newPlaylistName.isNotBlank()) {
                                    onCreatePlaylist(newPlaylistName)
                                    newPlaylistName = ""
                                    showCreateField = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
                        ) {
                            Text("Create", color = Color.White)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { showCreateField = true }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Add, null, tint = VinColors.AccentLight)
                        Text("Create New Playlist", color = VinColors.AccentLight, fontWeight = FontWeight.SemiBold)
                    }

                    Spacer(Modifier.height(4.dp))

                    if (playlists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No playlists yet", color = VinColors.Secondary, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(playlists) { pl ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(VinColors.White10)
                                        .clickable { onPlaylistSelected(pl) }
                                        .padding(vertical = 12.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.PlaylistPlay, null, tint = VinColors.Secondary)
                                    Text(pl.name, color = VinColors.Primary, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun UpdateDialog(
    updateInfo: com.vinmusic.update.UpdateInfo,
    onUpdateClick: () -> Unit,
    onLaterClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!updateInfo.forceUpdate) {
                onLaterClick()
            }
        },
        title = {
            Text(
                text = "New Update Available!",
                color = VinColors.Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "Version ${updateInfo.latestVersionName} is available.",
                    color = VinColors.Primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "What's new:\n${updateInfo.releaseNotes}",
                    color = VinColors.Secondary,
                    fontSize = 14.sp
                )
                if (updateInfo.forceUpdate) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "This update is required to continue using the app.",
                        color = Color(0xFFFF5555), // Red warning text
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onUpdateClick,
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
            ) {
                Text("Update Now", color = Color.White)
            }
        },
        dismissButton = {
            if (!updateInfo.forceUpdate) {
                TextButton(onClick = onLaterClick) {
                    Text("Later", color = VinColors.Secondary)
                }
            }
        },
        containerColor = VinColors.Surface2,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun UserAvatar(
    avatarIndex: Int,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    name: String = "",
    onClick: (() -> Unit)? = null
) {
    val gradients = remember {
        listOf(
            listOf(Color(0xFFEF4444), Color(0xFF991B1B)), // Solid Premium Red
            listOf(Color(0xFFFF416C), Color(0xFFFF4B2B)), // Pink-Orange
            listOf(Color(0xFF11998e), Color(0xFF38ef7d)), // Green-Teal
            listOf(Color(0xFF8A2387), Color(0xFFE94057))  // Violet-Pink-Red
        )
    }

    val colors = gradients.getOrElse(avatarIndex) { gradients[0] }
    val letter = if (name.isNotBlank()) {
        name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "V"
    } else {
        when (avatarIndex) {
            0 -> "V"
            1 -> "M"
            2 -> "L"
            3 -> "S"
            else -> "V"
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .border(1.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            fontSize = (size.value * 0.45f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

