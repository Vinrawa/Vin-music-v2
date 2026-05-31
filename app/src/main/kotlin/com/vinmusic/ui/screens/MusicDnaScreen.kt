package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.data.db.HistoryEntry
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicDnaScreen(
    vm: PlayerViewModel,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val db = remember(ctx) { VinDatabase.getInstance(ctx) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var totalPlaysThisWeek by remember { mutableIntStateOf(0) }
    var topArtist by remember { mutableStateOf("No Plays Yet") }
    var topArtistCount by remember { mutableIntStateOf(0) }
    var topSongs by remember { mutableStateOf<List<Pair<HistoryEntry, Int>>>(emptyList()) }
    
    // Mood percentages
    var chillPct by remember { mutableIntStateOf(0) }
    var hypePct by remember { mutableIntStateOf(0) }
    var focusPct by remember { mutableIntStateOf(0) }
    var personalityBadge by remember { mutableStateOf("The Music Explorer") }
    var personalityDesc by remember { mutableStateOf("You have a highly diverse taste in music. You explore different artists, moods, and genres constantly!") }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val allHistory = db.historyDao().getAllHistory()
                val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                val weeklyHistory = allHistory.filter { it.playedAt >= sevenDaysAgo }

                if (weeklyHistory.isNotEmpty()) {
                    totalPlaysThisWeek = weeklyHistory.size

                    // Top Artist
                    val artistGroups = weeklyHistory.groupBy { it.author }
                    val mainArtist = artistGroups.maxByOrNull { it.value.size }
                    if (mainArtist != null) {
                        topArtist = mainArtist.key
                        topArtistCount = mainArtist.value.size
                    }

                    // Top Songs
                    val songGroups = weeklyHistory.groupBy { it.videoId }
                    topSongs = songGroups.map { it.value.first() to it.value.size }
                        .sortedByDescending { it.second }
                        .take(5)

                    // Mood Analysis – weight each play, not just distinct songs
                    var chillCount = 0
                    var hypeCount = 0
                    var focusCount = 0

                    weeklyHistory.forEach { song ->
                        val titleLower = song.title.lowercase()
                        val authorLower = song.author.lowercase()
                        // boost count if artist is known for a mood category
                        val isChill = listOf("lofi", "chill", "slowed", "sad", "love", "romantic", "emotional", "acoustic", "peaceful", "soothing").any { titleLower.contains(it) } ||
                                       listOf("ambient", "mellow").any { authorLower.contains(it) } ||
                                       // optional genre field if present in HistoryEntry
                                       (song as? com.vinmusic.data.db.HistoryEntry)?.genre?.lowercase()?.let { genreLower ->
                                           listOf("ambient", "chill", "acoustic").any { genreLower.contains(it) }
                                       } ?: false
                        val isHype = listOf("gym", "workout", "party", "dance", "remix", "rap", "hiphop", "trap", "energetic", "bass", "hype").any { titleLower.contains(it) } ||
                                       listOf("edm", "electro").any { authorLower.contains(it) } ||
                                       (song as? com.vinmusic.data.db.HistoryEntry)?.genre?.lowercase()?.let { genreLower ->
                                           listOf("edm", "electro", "dance").any { genreLower.contains(it) }
                                       } ?: false
                        val isFocus = listOf("focus", "study", "instrumental", "piano", "calm", "meditation", "zen").any { titleLower.contains(it) } ||
                                       listOf("classical", "ambient").any { authorLower.contains(it) } ||
                                       (song as? com.vinmusic.data.db.HistoryEntry)?.genre?.lowercase()?.let { genreLower ->
                                           listOf("classical", "ambient", "ambient").any { genreLower.contains(it) }
                                       } ?: false

                        // Increment based on detection – a song can contribute to multiple moods
                        if (isChill) chillCount++
                        if (isHype) hypeCount++
                        if (isFocus) focusCount++
                    }

                    val totalMoods = (chillCount + hypeCount + focusCount).coerceAtLeast(1)
                    chillPct = (chillCount * 100) / totalMoods
                    hypePct = (hypeCount * 100) / totalMoods
                    focusPct = (focusCount * 100) / totalMoods

                    // If no mood detected, spread evenly
                    if (chillCount == 0 && hypeCount == 0 && focusCount == 0) {
                        chillPct = 33
                        hypePct = 34
                        focusPct = 33
                    }

                    // Personality logic
                    val maxPct = maxOf(chillPct, hypePct, focusPct)
                    when {
                        maxPct == chillPct -> {
                            personalityBadge = "The Melancholic Dreamer"
                            personalityDesc = "Your weekly DNA leans heavily towards relaxing, romantic, and emotional vibes. You love zoning out to soothing lofi beats or deep acoustic melodies."
                        }
                        maxPct == hypePct -> {
                            personalityBadge = "The High-Voltage Powerhouse"
                            personalityDesc = "You listen to high-energy beats to power through your day, workouts, and parties. Your DNA is packed with hype, bass-heavy rap, and energetic remixes."
                        }
                        else -> {
                            personalityBadge = "The Deep Focus Scholar"
                            personalityDesc = "You prefer clean, instrumental, and calm sounds to keep your mind centered. Your DNA is built for concentration, study, and peaceful zen sessions."
                        }
                    }
                } else {
                    // Seed mock stats if history is completely empty so that the screen doesn't look blank
                    // Seed mock stats for first‑time users – keep realistic diversity
                    totalPlaysThisWeek = 0
                    topArtist = ""
                    topArtistCount = 0
                    chillPct = 33
                    hypePct = 34
                    focusPct = 33
                    personalityBadge = "The Curious Explorer"
                    personalityDesc = "Your listening habits span many moods. Keep discovering new beats!"
                }
                
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicDnaScreen", "Failed to compile weekly stats: ${e.message}")
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your Music DNA", fontWeight = FontWeight.Bold, color = VinColors.Primary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = VinColors.Primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxSize()
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VinColors.Accent)
            }
        } else {
            // Infinite lava lamp background specific to stats
            val infiniteTransition = rememberInfiniteTransition(label = "stats_bg")
            val blob1X by infiniteTransition.animateFloat(
                initialValue = -100f, targetValue = 300f,
                animationSpec = infiniteRepeatable(tween(25000, easing = LinearEasing), RepeatMode.Reverse),
                label = "blob1X"
            )
            val blob2Y by infiniteTransition.animateFloat(
                initialValue = 600f, targetValue = -100f,
                animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Reverse),
                label = "blob2Y"
            )

            Box(modifier = Modifier.fillMaxSize()) {
                // Background morphing bubbles
                Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
                    drawCircle(
                        color = Color(0xFF6338EC).copy(alpha = 0.25f),
                        radius = size.width * 0.7f,
                        center = androidx.compose.ui.geometry.Offset(blob1X.dp.toPx(), 200.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFFEC4899).copy(alpha = 0.2f),
                        radius = size.width * 0.6f,
                        center = androidx.compose.ui.geometry.Offset(150.dp.toPx(), blob2Y.dp.toPx())
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Weekly Summary Hero Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "WEEKLY STATS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight,
                                letterSpacing = 2.sp
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = totalPlaysThisWeek.toString(),
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = VinColors.Primary
                                    )
                                    Text("Songs Listened", fontSize = 12.sp, color = VinColors.Secondary)
                                }
                                
                                Box(
                                    modifier = Modifier
                                        .size(1.dp, 50.dp)
                                        .background(VinColors.White20)
                                )

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = topArtistCount.toString(),
                                        fontSize = 44.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = VinColors.Primary
                                    )
                                    Text("Plays of Top Artist", fontSize = 12.sp, color = VinColors.Secondary)
                                }
                            }

                            HorizontalDivider(color = VinColors.White10, modifier = Modifier.padding(vertical = 4.dp))

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = topArtist,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VinColors.Primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("Your Top Artist of the Week", fontSize = 12.sp, color = VinColors.Secondary)
                            }
                        }
                    }

                    // Personality Badge
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0x33EC4899), RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x11EC4899))
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Analytics,
                                null,
                                tint = Color(0xFFEC4899),
                                modifier = Modifier.size(36.dp)
                            )
                            Column {
                                Text(
                                    "Music Personality",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFEC4899),
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = personalityBadge,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                                Text(
                                    text = personalityDesc,
                                    fontSize = 12.sp,
                                    color = VinColors.Secondary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }

                    // Mood DNA Chart
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "YOUR MOOD DNA",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight,
                                letterSpacing = 2.sp
                            )

                            // Chill Mood Bar
                            MoodBar(
                                name = "Chill & Romantic",
                                percent = chillPct,
                                barColor = Color(0xFF8B5CF6),
                                icon = Icons.Default.MusicNote
                            )

                            // Hype Mood Bar
                            MoodBar(
                                name = "Hype & Energy",
                                percent = hypePct,
                                barColor = Color(0xFFEC4899),
                                icon = Icons.Default.Equalizer
                            )

                            // Focus Mood Bar
                            MoodBar(
                                name = "Focus & Calm",
                                percent = focusPct,
                                barColor = Color(0xFF10B981),
                                icon = Icons.Default.Timeline
                            )
                        }
                    }

                    // Top 5 Tracks List
                    if (topSongs.isNotEmpty()) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Top 5 Songs This Week",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.Primary,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )

                            topSongs.forEachIndexed { index, pair ->
                                val song = pair.first
                                val playCount = pair.second

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(VinColors.White10)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Rank Number Badge
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                when (index) {
                                                    0 -> Color(0xFFFFD700).copy(alpha = 0.25f) // Gold
                                                    1 -> Color(0xFFC0C0C0).copy(alpha = 0.25f) // Silver
                                                    2 -> Color(0xFFCD7F32).copy(alpha = 0.25f) // Bronze
                                                    else -> VinColors.White20
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            fontWeight = FontWeight.Bold,
                                            color = when (index) {
                                                0 -> Color(0xFFFFD700)
                                                1 -> Color(0xFFE2E2E2)
                                                2 -> Color(0xFFFFA07A)
                                                else -> VinColors.Primary
                                            },
                                            fontSize = 14.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(16.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = VinColors.Primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        // No additional song loading needed here; displaying top song info.
                                            
                                        Text(
                                            text = song.author,
                                            fontSize = 12.sp,
                                            color = VinColors.Secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "$playCount plays",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = VinColors.AccentLight
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
fun MoodBar(
    name: String,
    percent: Int,
    barColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val animatedPercent = animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "moodBarProgress"
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, null, tint = barColor, modifier = Modifier.size(16.dp))
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary)
            }
            Text("$percent%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = barColor)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(VinColors.White10)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedPercent.value)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
    }
}
