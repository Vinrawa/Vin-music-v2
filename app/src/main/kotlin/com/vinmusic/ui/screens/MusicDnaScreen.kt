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
import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.recommendation.MusicDnaComparison
import com.vinmusic.recommendation.MusicDnaInsights
import com.vinmusic.recommendation.RecommendationManager
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    
    // Core taste and the recent listening window are intentionally kept
    // separate: a short-lived mood should not rewrite someone's full DNA.
    var comparison by remember { mutableStateOf<MusicDnaComparison?>(null) }
    
    // Additional metrics
    var topSongs by remember { mutableStateOf<List<InteractionSignal>>(emptyList()) }
    var favoriteGenres by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }

    LaunchedEffect(Unit) {
        // Open the screen from existing local signals first. Feature enrichment
        // can be expensive for a large library and must never block navigation.
        try {
            val signals = withContext(Dispatchers.IO) { db.interactionSignalDao().getAll() }
            val tasteProfile = withTimeoutOrNull(3_000L) {
                withContext(Dispatchers.IO) { RecommendationManager.buildTasteProfile(db) }
            }
            comparison = MusicDnaInsights.build(signals)
            favoriteGenres = tasteProfile?.topGenres?.take(4)?.map { it.first to it.second.toInt() }.orEmpty()
            topSongs = signals
                .filter { it.playCount > 0 || it.completeCount > 0 || it.repeatCount > 0 }
                .sortedByDescending { it.playCount + it.completeCount + it.repeatCount * 2 + if (it.isLiked) 3 else 0 }
                .take(5)
        } catch (e: Exception) {
            android.util.Log.e("MusicDnaScreen", "Failed to load DNA stats: ${e.message}")
        } finally {
            isLoading = false
        }

        // Enrich in the background for a future visit; the screen is already
        // usable even if this takes time or is cancelled.
        scope.launch(Dispatchers.IO) {
            runCatching { withTimeoutOrNull(8_000L) { vm.tasteProfileManager.calculateTasteProfile() } }
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
        } else if (comparison == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Your Music DNA is still learning.\nPlay and rate a few more songs to unlock it.", color = VinColors.Secondary, textAlign = TextAlign.Center)
            }
        } else {
            val dnaComparison = comparison ?: return@Scaffold Unit
            val dna = dnaComparison.core.profile
            val currentEra = dnaComparison.currentEra
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF1E1A14),
                                    VinColors.BgColor
                                )
                            )
                        )
                )

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
                        colors = CardDefaults.cardColors(containerColor = Color(0x33C5A880))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "YOUR CORE TASTE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = VinColors.AccentLight,
                                letterSpacing = 2.sp
                            )
                            
                            val moodText = when {
                                dna.valence > 65 -> "Happy & Upbeat"
                                dna.valence < 35 -> "Dark & Emotional"
                                else -> "Chill & Balanced"
                            }
                            val primaryColor = when {
                                dna.valence > 65 -> VinColors.AccentLight
                                dna.valence < 35 -> Color(0xFF8C7355)
                                else -> VinColors.Accent
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

                            Text(
                                text = "${dnaComparison.confidence.label} confidence · based on ${dnaComparison.core.trackCount} tracked songs and ${dnaComparison.core.interactionCount} listening signals",
                                fontSize = 12.sp,
                                color = VinColors.Secondary,
                                lineHeight = 16.sp
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            DnaStatBar("Energy", dna.energy, VinColors.Accent, Icons.Default.ElectricBolt)
                            DnaStatBar("Danceability", dna.danceability, VinColors.AccentLight, Icons.Default.DirectionsRun)
                            DnaStatBar("Acousticness", dna.acousticness, Color(0xFF8C7355), Icons.Default.Spa)
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Average Tempo: ${dna.tempo} BPM",
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
                    
                    // SECTION 3: THE CURRENT ERA
                    currentEra?.let { recent ->
                        val recentMood = when {
                            recent.profile.valence > 65 -> "Bright & Upbeat"
                            recent.profile.valence < 35 -> "Dark & Reflective"
                            else -> "Chill & Balanced"
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    "YOUR CURRENT ERA",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = VinColors.AccentLight,
                                    letterSpacing = 1.5.sp
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(recentMood, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                                        Text("Last 14 days · ${recent.trackCount} recent tracks", fontSize = 12.sp, color = VinColors.Secondary)
                                    }
                                    Icon(Icons.Default.AutoAwesome, null, tint = VinColors.Accent, modifier = Modifier.size(26.dp))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DnaDeltaChip("Energy", recent.profile.energy - dna.energy)
                                    DnaDeltaChip("Tempo", recent.profile.tempo - dna.tempo, " BPM")
                                    DnaDeltaChip("Acoustic", recent.profile.acousticness - dna.acousticness)
                                }
                            }
                        }

                        DnaComparisonChart(core = dna, recent = recent.profile)
                    }

                    // SECTION 4: EVIDENCE-BACKED TASTE TREND
                    val shift = dnaComparison.strongestShift()
                    val trendCopy = when {
                        shift == null -> "Your recent listens are close to your core taste."
                        shift.amount > 0 -> "You are leaning into more ${shift.dimension} lately."
                        else -> "You are leaning into less ${shift.dimension} lately."
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, VinColors.White10, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.TrendingUp, null, tint = VinColors.Accent)
                                Text("Taste Trend", fontSize = 12.sp, color = VinColors.Secondary)
                                Text(trendCopy, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary, lineHeight = 18.sp)
                            }
                        }
                        
                        Card(
                            modifier = Modifier.weight(1f).border(1.dp, VinColors.White10, RoundedCornerShape(20.dp)),
                            colors = CardDefaults.cardColors(containerColor = VinColors.White10)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Default.VerifiedUser, null, tint = VinColors.AccentLight)
                                Text("Listening confidence", fontSize = 12.sp, color = VinColors.Secondary)
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(dnaComparison.confidence.label, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                                }
                                Text("${dnaComparison.core.interactionCount} local signals", fontSize = 11.sp, color = VinColors.Secondary)
                            }
                        }
                    }

                    // SECTION 5: SONGS THAT SHAPED YOUR TASTE
                    if (topSongs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Songs That Shaped Your Taste", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)

                            topSongs.forEachIndexed { index, song ->

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
                                                    0 -> VinColors.Accent.copy(alpha = 0.25f)
                                                    1 -> VinColors.AccentLight.copy(alpha = 0.25f)
                                                    2 -> Color(0xFF8C7355).copy(alpha = 0.25f)
                                                    else -> VinColors.White20
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = (index + 1).toString(),
                                            fontWeight = FontWeight.Bold,
                                            color = when (index) {
                                                0 -> VinColors.Accent
                                                1 -> VinColors.AccentLight
                                                2 -> Color(0xFF8C7355)
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
                                    Text("${song.playCount} plays", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = VinColors.AccentLight)
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(220.dp))
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

@Composable
private fun DnaDeltaChip(label: String, delta: Int, suffix: String = "%") {
    val isUp = delta > 0
    val isNeutral = delta == 0
    val color = when {
        isNeutral -> VinColors.Secondary
        isUp -> VinColors.AccentLight
        else -> Color(0xFF8C7355)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, color = VinColors.Secondary)
        Text(
            text = if (isNeutral) "Same" else "${if (isUp) "+" else ""}$delta$suffix",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/** Compact, directly-labelled comparison instead of a vague overall score. */
@Composable
private fun DnaComparisonChart(
    core: com.vinmusic.recommendation.AudioFeatureProfile,
    recent: com.vinmusic.recommendation.AudioFeatureProfile
) {
    val coreColor = VinColors.Accent
    val recentColor = VinColors.AccentLight
    val metrics = listOf(
        Triple("Energy", core.energy, recent.energy),
        Triple("Dance", core.danceability, recent.danceability),
        Triple("Acoustic", core.acousticness, recent.acousticness),
        Triple("Tempo", ((core.tempo - 40) * 100 / 180).coerceIn(0, 100), ((recent.tempo - 40) * 100 / 180).coerceIn(0, 100))
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = VinColors.White10)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("CORE VS CURRENT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = VinColors.AccentLight, letterSpacing = 1.5.sp)
                Text("How your recent listening is shifting", fontSize = 13.sp, color = VinColors.Secondary)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                DnaChartLegend("Core Taste", coreColor)
                DnaChartLegend("Current Era", recentColor)
            }

            metrics.forEach { (label, coreValue, recentValue) ->
                val coreProgress by animateFloatAsState(
                    targetValue = coreValue / 100f,
                    animationSpec = tween(700, easing = FastOutSlowInEasing),
                    label = "core_$label"
                )
                val recentProgress by animateFloatAsState(
                    targetValue = recentValue / 100f,
                    animationSpec = tween(950, easing = FastOutSlowInEasing),
                    label = "recent_$label"
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary)
                        Text(
                            text = if (label == "Tempo") "${core.tempo} → ${recent.tempo} BPM" else "$coreValue → $recentValue%",
                            fontSize = 12.sp,
                            color = VinColors.Secondary
                        )
                    }
                    DnaComparisonBar(coreProgress, recentProgress, coreColor, recentColor)
                }
            }
        }
    }
}

@Composable
private fun DnaChartLegend(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, fontSize = 11.sp, color = VinColors.Secondary)
    }
}

@Composable
private fun DnaComparisonBar(coreProgress: Float, recentProgress: Float, coreColor: Color, recentColor: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(VinColors.White10)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(coreProgress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(coreColor.copy(alpha = 0.72f))
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(VinColors.White10)
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(recentProgress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(4.dp))
                    .background(recentColor)
            )
        }
    }
}
