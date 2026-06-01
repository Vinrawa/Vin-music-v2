package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.lyrics.LyricsHelper
import com.vinmusic.lyrics.LyricsResult
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlin.math.roundToInt
import kotlin.random.Random

// Diverse premium discovery query templates
private val DISCOVER_QUERIES = listOf(
    "trending viral hindi songs 2025",
    "best punjabi hits 2025",
    "top english pop hits 2025",
    "new bollywood songs 2025",
    "lofi chill beats 2025",
    "top tamil hits 2025",
    "trending k-pop songs 2025",
    "new indie songs 2025",
    "best rap hip hop 2025",
    "romantic songs 2025"
)

// Data class to wrap song with dynamic recommendation metadata
data class DiscoverSong(
    val videoItem: VideoItem,
    val recommendationReason: String,
    val vibeScore: Int
)

/**
 * Filter search results to ensure only official audio/video released by the artist is added.
 * Excludes fan-made content, covers, karaokes, reaction videos, and loops.
 */
fun isOfficialRelease(song: VideoItem): Boolean {
    val titleLower = song.title.lowercase()
    
    // Ignore common non-official/amateur patterns
    val ignoreKeywords = listOf(
        "cover", "karaoke", "instrumental", "tribute", "reaction", 
        "mashup", "reverb", "slowed", "1 hour", "2 hour", "3 hour", "nonstop", 
        "non-stop", "loop", "bgm", "clean audio", "lyrics video", "lyrics only",
        "parody", "fanmade", "fan-made", "synthesia", "piano tutorial"
    )
    
    return ignoreKeywords.none { titleLower.contains(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<DiscoverSong>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val swipeState = rememberSwipeState(scope)
    
    // Controls if the next card should auto-play its preview
    var autoPreviewEnabled by remember { mutableStateOf(true) }
    
    // Floating toast popup state
    var showToastMessage by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val db  = remember(ctx) { com.vinmusic.data.db.VinDatabase.getInstance(ctx) }

    // Automatically clear floating toast after 2.5 seconds
    LaunchedEffect(showToastMessage) {
        if (showToastMessage != null) {
            delay(2500L)
            showToastMessage = null
        }
    }

    // ── Load personalized smart discovery pool ──
    fun loadDiscoverSongs() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch user interactions, library statistics, and local playlists
                val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
                val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
                val liked   = try { db.likedSongDao().getAll() } catch (_: Exception) { emptyList() }
                val playlistSongs = try { db.playlistDao().getAllPlaylistSongs() } catch (_: Exception) { emptyList() }

                val discoverPool = mutableListOf<DiscoverSong>()

                // Gather top artists from signals, history, and custom playlists
                val topArtists = signals
                    .filter { it.playCount > 0 }
                    .sortedByDescending { it.playCount * 3 + it.completeCount - it.skipCount }
                    .map { it.author }
                    .distinct()

                val historyArtists = history
                    .groupBy { it.author }
                    .entries
                    .sortedByDescending { it.value.size }
                    .map { it.key }

                val playlistArtists = playlistSongs
                    .groupBy { it.author }
                    .entries
                    .sortedByDescending { it.value.size }
                    .map { it.key }

                val combinedArtists = (topArtists + historyArtists + playlistArtists).distinct()

                // OPTIMIZATION: Instead of 20 parallel search requests which throttle network/choke playback,
                // we choose exactly a few random seeds to query concurrently.
                val seedArtists = combinedArtists.shuffled().take(2)
                val seedLikes = liked.shuffled().take(1)
                val seedPlaylists = playlistSongs.shuffled().take(1)

                coroutineScope {
                    val deferreds = mutableListOf<Deferred<List<DiscoverSong>>>()

                    // 2. Fetch popular/new songs from seed artists concurrently
                    seedArtists.forEach { artist ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("$artist popular songs")
                                    .filter { isOfficialRelease(it) }
                                    .take(3)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Based on your love for $artist",
                                            vibeScore = Random.nextInt(92, 100)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })

                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("$artist new song")
                                    .filter { isOfficialRelease(it) }
                                    .take(2)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "New from $artist",
                                            vibeScore = Random.nextInt(94, 99)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 3. Similar to Liked Songs seed concurrently
                    seedLikes.forEach { likedSong ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("songs like ${likedSong.title} ${likedSong.author}")
                                    .filter { isOfficialRelease(it) }
                                    .take(4)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Vibe match with '${likedSong.title}'",
                                            vibeScore = Random.nextInt(88, 98)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 4. Similar to Playlist Songs seed concurrently
                    seedPlaylists.forEach { plSong ->
                        deferreds.add(async(Dispatchers.IO) {
                            try {
                                InnerTube.search("songs like ${plSong.title} ${plSong.author}")
                                    .filter { isOfficialRelease(it) }
                                    .take(4)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Inspired by your playlist track",
                                            vibeScore = Random.nextInt(89, 97)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // 5. Fresh serendipity query templates concurrently
                    val freshQuery = DISCOVER_QUERIES.random()
                    deferreds.add(async(Dispatchers.IO) {
                        try {
                            InnerTube.search(freshQuery)
                                .filter { isOfficialRelease(it) }
                                .take(4)
                                .map { song ->
                                    DiscoverSong(
                                        videoItem = song,
                                        recommendationReason = "Fresh: ${freshQuery.replace("2025", "").trim()}",
                                        vibeScore = Random.nextInt(78, 92)
                                    )
                                }
                        } catch (_: Exception) { emptyList() }
                    })

                    val results = deferreds.awaitAll().flatten()
                    discoverPool.addAll(results)
                }

                // 6. Deduplicate, shuffle and set max 2 songs per artist
                val uniqueDiscover = discoverPool
                    .distinctBy { it.videoItem.videoId }
                    .shuffled()
                    .fold(mutableListOf<DiscoverSong>()) { acc, song ->
                        val count = acc.count { it.videoItem.author == song.videoItem.author }
                        if (count < 2) acc.apply { add(song) } else acc
                    }
                    .take(30)

                withContext(Dispatchers.Main) {
                    cards = uniqueDiscover
                    isLoading = false
                    autoPreviewEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) { loadDiscoverSongs() }

    Box(modifier = Modifier.fillMaxSize().background(VinColors.BgColor)) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                    Text("Tuning your acoustic profile...", color = VinColors.Secondary, fontSize = 14.sp)
                }
            }
        } else if (cards.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("You've swiped them all!", color = VinColors.Primary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Ready for another customized batch?", color = VinColors.Secondary, fontSize = 14.sp)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { loadDiscoverSongs() }, colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)) {
                    Icon(Icons.Default.Refresh, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Refresh Deck", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val currentCard = cards.last()

            // Ambient background with animated layers
            val infiniteTransition = rememberInfiniteTransition(label = "discover_bg")
            val bgRotation by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(60000, easing = LinearEasing), RepeatMode.Restart),
                label = "bgRotation"
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f, targetValue = 0.55f,
                animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "pulse"
            )

            // Blur background cover
            AsyncImage(
                model = currentCard.videoItem.thumbnailHd ?: currentCard.videoItem.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(60.dp).graphicsLayer(alpha = 0.5f),
                contentScale = ContentScale.Crop
            )

            // Dynamic light overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(rotationZ = bgRotation, scaleX = 1.5f, scaleY = 1.5f)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF8B5CF6).copy(alpha = pulseAlpha),
                                Color(0xFFEC4899).copy(alpha = pulseAlpha * 0.6f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x99000000),
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xDD000000)
                        )
                    )
                )
            )

            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                    .size(42.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }

            // Screen Header title
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Discover Mix", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, color = Color.White)
                Text("${cards.size} songs • Smart matching enabled", fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
            }

            // Stack & Control layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(top = 80.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (cards.size > 1) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.82f)
                                .offset(y = 14.dp).graphicsLayer { scaleX = 0.93f; scaleY = 0.93f; alpha = 0.6f },
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
                        ) {}
                    }
                    if (cards.size > 2) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.82f).fillMaxHeight(0.76f)
                                .offset(y = 26.dp).graphicsLayer { scaleX = 0.87f; scaleY = 0.87f; alpha = 0.35f },
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f))
                        ) {}
                    }

                    // Discover Card
                    DiscoverCard(
                        discoverSong = currentCard,
                        vm = vm,
                        swipeState = swipeState,
                        autoPreviewEnabled = autoPreviewEnabled,
                        onSwipedLeft = {
                            scope.launch {
                                swipeState.animateLeft { cards = cards.dropLast(1) }
                            }
                        },
                        onSwipedRight = {
                            scope.launch {
                                swipeState.animateRight {
                                    vm.toggleLike(currentCard.videoItem)
                                    showToastMessage = "Added to Liked Songs"
                                    cards = cards.dropLast(1)
                                }
                            }
                        }
                    )
                }

                // Swiper Controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip button
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = { scope.launch { swipeState.animateLeft { cards = cards.dropLast(1) } } },
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(Color(0xFFFF4D4D).copy(alpha = 0.15f))
                                .border(1.5.dp, Color(0xFFFF4D4D).copy(alpha = 0.5f), CircleShape)
                        ) { Icon(Icons.Default.Close, "Skip", tint = Color(0xFFFF4D4D), modifier = Modifier.size(28.dp)) }
                        Text("Skip", fontSize = 11.sp, color = Color(0xFFFF4D4D).copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                    }

                    // Center play/preview button
                    val isCurrentPlaying = vm.currentSong?.videoId == currentCard.videoItem.videoId && vm.isPlaying
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = {
                                autoPreviewEnabled = true
                                if (vm.currentSong?.videoId == currentCard.videoItem.videoId) vm.togglePlay()
                                else { vm.playSong(currentCard.videoItem) }
                            },
                            modifier = Modifier.size(80.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))))
                        ) {
                            Icon(
                                if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Preview", tint = Color.White, modifier = Modifier.size(38.dp)
                            )
                        }
                        Text("Preview", fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Medium)
                    }

                    // Like Button (Triggers Like & Toast)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    swipeState.animateRight {
                                        vm.toggleLike(currentCard.videoItem)
                                        showToastMessage = "Added to Liked Songs"
                                        cards = cards.dropLast(1)
                                    }
                                }
                            },
                            modifier = Modifier.size(64.dp).clip(CircleShape)
                                .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                .border(1.5.dp, Color(0xFF10B981).copy(alpha = 0.5f), CircleShape)
                        ) { Icon(Icons.Default.Favorite, "Like", tint = Color(0xFF10B981), modifier = Modifier.size(28.dp)) }
                        Text("Like", fontSize = 11.sp, color = Color(0xFF10B981).copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Floating Toast Popup at the bottom (Song is added to your liked playlist)
        AnimatedVisibility(
            visible = showToastMessage != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 120.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = showToastMessage ?: "",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }


}

@Composable
fun DiscoverCard(
    discoverSong: DiscoverSong,
    vm: PlayerViewModel,
    swipeState: SwipeState,
    autoPreviewEnabled: Boolean,
    onSwipedLeft: () -> Unit,
    onSwipedRight: () -> Unit
) {
    val song = discoverSong.videoItem
    val isCurrentSong = vm.currentSong?.videoId == song.videoId
    val isPlaying = isCurrentSong && vm.isPlaying && !vm.isLoading

    // Dynamic Lyrics State (loaded fast on demand)
    var famousLine by remember { mutableStateOf<String?>(null) }
    
    // Auto-preview chorus & load lyrics dynamically
    LaunchedEffect(song.videoId) {
        // Fast optimized lyrics fetch (only SimpMusic or LrcLib)
        launch(Dispatchers.IO) {
            try {
                // Try SimpMusic first (extremely fast direct videoId lookup)
                var lyricsResult = LyricsHelper.fetch(song.title, song.author, song.videoId, provider = "SimpMusic")
                if (lyricsResult is LyricsResult.NotFound) {
                    // Quick fallback to LrcLib
                    lyricsResult = LyricsHelper.fetch(song.title, song.author, song.videoId, provider = "LrcLib")
                }
                
                val line = when (lyricsResult) {
                    is LyricsResult.Synced -> {
                        if (lyricsResult.lines.isNotEmpty()) {
                            val durationMs = if (vm.durationMs > 0L) vm.durationMs else 180000L
                            val targetMs = (durationMs * 0.35f).toLong()
                            val closest = lyricsResult.lines.minByOrNull { kotlin.math.abs(it.timeMs - targetMs) }
                            closest?.text
                        } else null
                    }
                    is LyricsResult.Plain -> {
                        val lines = lyricsResult.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                        if (lines.isNotEmpty()) lines[lines.size / 2] else null
                    }
                    else -> null
                }
                withContext(Dispatchers.Main) {
                    famousLine = line
                }
            } catch (_: Exception) {}
        }

        // Wait to settle transitions and debounce fast swipes
        delay(1000L)
        if (autoPreviewEnabled) {
            if (vm.currentSong?.videoId != song.videoId) {
                vm.playSong(song)
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing), RepeatMode.Restart),
        label = "vinylRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .offset { IntOffset(swipeState.offsetX.value.roundToInt(), swipeState.offsetY.value.roundToInt()) }
            .graphicsLayer { rotationZ = (swipeState.offsetX.value / 18f).coerceIn(-12f, 12f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag -> change.consume(); swipeState.drag(drag.x, drag.y) },
                    onDragEnd = { swipeState.released(size.width * 0.32f, onSwipedLeft, onSwipedRight) }
                )
            }
            .clip(RoundedCornerShape(28.dp))
    ) {
        AsyncImage(
            model = song.thumbnailHd ?: song.thumbnail,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Scrim overlay
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Transparent, Color(0xCC000000), Color(0xEE000000))
                )
            )
        )

        // Top pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Vibe Match Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899))))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Analytics, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    "${discoverSong.vibeScore}% Vibe Match",
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            // active state preview pill
            AnimatedVisibility(
                visible = isPlaying,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF10B981).copy(alpha = 0.9f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text("Chorus Live", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Recommendation reason tag
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.65f))
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = discoverSong.recommendationReason,
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Centered Disc (sized nicely to avoid clashing with bottom info)
        val vinylOffset by animateDpAsState(
            targetValue = if (isPlaying) 40.dp else 0.dp,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "vinylSlide"
        )
        Box(
            modifier = Modifier.align(Alignment.Center).offset(y = (-70).dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(170.dp)
                    .offset(x = vinylOffset)
                    .graphicsLayer { rotationZ = if (isPlaying) rotationAngle else 0f }
                    .clip(CircleShape)
                    .background(Color(0xFF111111))
                    .border(2.dp, Color(0xFF2A2A2A), CircleShape)
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f; val cy = size.height / 2f
                    for (r in listOf(0.42f, 0.33f, 0.24f, 0.15f)) {
                        drawCircle(color = Color(0x22FFFFFF), radius = size.width * r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
                    }
                    drawCircle(color = Color(0xFF222222), radius = size.width * 0.08f)
                    drawCircle(color = Color(0xFFAAAAAA), radius = size.width * 0.025f)
                }
            }

            Card(
                modifier = Modifier.size(160.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
            ) {
                AsyncImage(
                    model = song.thumbnailHd ?: song.thumbnail,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Bottom Column holding Lyrics, Titles, and Badges vertically (Guarantees zero overlapping!)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Instagram-style Famous Line Lyrics Overlay (Fits perfectly within bottom space)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = null,
                        tint = VinColors.AccentLight.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = famousLine ?: "Search lyrics...",
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Song Info (Title & Author)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    song.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    song.author, fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            // Badges row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                        .background(Color.White.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) { Text(song.durationText, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                if (isCurrentSong) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF8B5CF6).copy(alpha = 0.4f))
                            .border(1.dp, Color(0xFF8B5CF6), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) { Text("Chorus Preview", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // Drag hints
        val dragRatio = (swipeState.offsetX.value / 280f).coerceIn(-1f, 1f)
        AnimatedVisibility(
            visible = dragRatio > 0.15f,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
        ) {
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = -12f }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.9f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text("LIKE", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White) }
        }
        AnimatedVisibility(
            visible = dragRatio < -0.15f,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)
        ) {
            Box(
                modifier = Modifier.graphicsLayer { rotationZ = 12f }
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFF4D4D).copy(alpha = 0.9f))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) { Text("SKIP", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color.White) }
        }
    }
}

private fun strokeStyle() = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f)

class SwipeState(val scope: kotlinx.coroutines.CoroutineScope) {
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)

    fun drag(x: Float, y: Float) {
        scope.launch {
            offsetX.snapTo(offsetX.value + x)
            offsetY.snapTo(offsetY.value + y)
        }
    }

    suspend fun animateLeft(onComplete: () -> Unit) {
        offsetX.animateTo(-1400f, tween(300, easing = FastOutSlowInEasing))
        onComplete()
        offsetX.snapTo(0f); offsetY.snapTo(0f)
    }

    suspend fun animateRight(onComplete: () -> Unit) {
        offsetX.animateTo(1400f, tween(300, easing = FastOutSlowInEasing))
        onComplete()
        offsetX.snapTo(0f); offsetY.snapTo(0f)
    }

    fun released(threshold: Float, onLeft: () -> Unit, onRight: () -> Unit) {
        scope.launch {
            when {
                offsetX.value > threshold -> onRight()
                offsetX.value < -threshold -> onLeft()
                else -> {
                    launch { offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    launch { offsetY.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
            }
        }
    }
}

@Composable
fun rememberSwipeState(scope: kotlinx.coroutines.CoroutineScope = rememberCoroutineScope()): SwipeState =
    remember(scope) { SwipeState(scope) }
