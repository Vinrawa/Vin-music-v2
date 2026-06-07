package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vinmusic.data.db.HistoryEntry
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.recommendation.TasteProfile
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
    
    // TasteDNA profile state
    var profile by remember { mutableStateOf<TasteProfile?>(null) }
    
    // Additional metrics
    var totalPlays by remember { mutableIntStateOf(0) }
    var smartRadioAccuracy by remember { mutableIntStateOf(0) }
    var topSongs by remember { mutableStateOf<List<Pair<HistoryEntry, Int>>>(emptyList()) }
    var favoriteGenres by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                // 1. Fetch the exact mathematical TasteDNA Profile!
                val fetchedProfile = vm.tasteProfileManager.calculateTasteProfile()
                
                // 2. Fetch history and stats
                val allHistory = db.historyDao().getAllHistory()
                totalPlays = allHistory.size
                
                if (allHistory.isNotEmpty()) {
                    // Top Songs (Songs that shaped your taste)
                    val songGroups = allHistory.groupBy { it.videoId }
                    topSongs = songGroups.map { it.value.first() to it.value.size }
                        .sortedByDescending { it.second }
                        .take(5)
                        
                    // Top Genres (inferred from top songs for simplicity)
                    val genreMap = HashMap<String, Int>()
                    allHistory.forEach { song ->
                        val genre = com.vinmusic.recommendation.RecommendationManager.inferMetadata(com.vinmusic.innertube.VideoItem(song.videoId, song.title, song.author)).genre
                        genreMap[genre] = (genreMap[genre] ?: 0) + 1
                    }
                    favoriteGenres = genreMap.entries.map { it.key to it.value }.sortedByDescending { it.second }.take(4)
                }

                // 3. Smart Radio Accuracy (Skip rate inverted)
                val signals = db.interactionSignalDao().getAll()
                val totalRecommended = signals.sumOf { it.playCount + it.skip20sCount }
                val skips = signals.sumOf { it.skip20sCount }
                if (totalRecommended > 0) {
                    smartRadioAccuracy = 100 - ((skips * 100) / totalRecommended)
                } else {
                    smartRadioAccuracy = 100
                }
                
                withContext(Dispatchers.Main) {
                    profile = fetchedProfile
                    isLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("MusicDnaScreen", "Failed to compile DNA stats: ${e.message}")
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
        if (isLoading || profile == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = VinColors.Accent)
            }
        } else {
            // Infinite gradient mesh background
            val infiniteTransition = rememberInfiniteTransition(label = "dna_bg")
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
                Canvas(modifier = Modifier.fillMaxSize().blur(100.dp)) {
                    drawCircle(
                        color = Color(0xFF4F46E5).copy(alpha = 0.25f),
                        radius = size.width * 0.7f,
                        center = androidx.compose.ui.geometry.Offset(blob1X.dp.toPx(), 200.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF10B981).copy(alpha = 0.2f),
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
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    
                    // SECTION 1: TOP CARD - THE MUSIC DNA
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0x331E293B))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "YOUR UNIQUE SIGNATURE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight,
                                letterSpacing = 2.sp
                            )
                            
                            val moodText = when {
                                profile!!.valence > 65 -> "Happy & Upbeat"
                                profile!!.valence < 35 -> "Dark & Emotional"
                                else -> "Chill & Balanced"
                            }
                            val primaryColor = when {
                                profile!!.valence > 65 -> Color(0xFFFBBF24)
                                profile!!.valence < 35 -> Color(0xFF8B5CF6)
                                else -> Color(0xFF38BDF8)
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(primaryColor.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Fingerprint, null, tint = primaryColor, modifier = Modifier.size(28.dp))
                                }
                                Column {
                                    Text("Primary Mood", fontSize = 12.sp, color = VinColors.Secondary)
                                    Text(moodText, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            DnaStatBar("Energy", profile!!.energy, Color(0xFFEC4899), Icons.Default.ElectricBolt)
                            DnaStatBar("Danceability", profile!!.danceability, Color(0xFFF59E0B), Icons.Default.DirectionsRun)
                            DnaStatBar("Acousticness", profile!!.acousticness, Color(0xFF10B981), Icons.Default.Spa)
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Average Tempo: ${profile!!.tempo} BPM",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = VinColors.Secondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // SECTION 2: FAVORITE GENRES
                    if (favoriteGenres.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Your Favorite Genres", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                favoriteGenres.take(2).forEach { genre ->
                                    Card(
                                        modifier = Modifier.weight(1f).aspectRatio(1.5f).border(1.dp, VinColors.White10, RoundedCornerShape(16.dp)),
                                        colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(genre.first, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // SECTION 3: RECENTLY EVOLVING TASTE & SMART RADIO ACCURACY
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, VinColors.White10, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF38BDF8))
                                Text("Taste Trend", fontSize = 12.sp, color = VinColors.Secondary)
                                Text("Exploring more ${if (profile!!.energy > 60) "Energetic" else "Acoustic"} vibes recently.", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary, lineHeight = 18.sp)
                            }
                        }
                        
                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, VinColors.White10, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF10B981))
                                Text("Radio Accuracy", fontSize = 12.sp, color = VinColors.Secondary)
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("$smartRadioAccuracy%", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                                }
                                Text("Matches your taste", fontSize = 11.sp, color = VinColors.Secondary)
                            }
                        }
                    }

                    // SECTION 4: SONGS THAT SHAPED YOUR TASTE
                    if (topSongs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Songs That Shaped Your Taste", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)

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
                                    Box(
                                        modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(
                                                when (index) {
                                                    0 -> Color(0xFFFFD700).copy(alpha = 0.25f)
                                                    1 -> Color(0xFFC0C0C0).copy(alpha = 0.25f)
                                                    2 -> Color(0xFFCD7F32).copy(alpha = 0.25f)
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
                                        Text(
                                            text = song.author,
                                            fontSize = 12.sp,
                                            color = VinColors.Secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("$playCount plays", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VinColors.AccentLight)
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
fun DnaStatBar(
    name: String,
    percent: Int,
    barColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    val animatedPercent = animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
        label = "dnaBarProgress"
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
