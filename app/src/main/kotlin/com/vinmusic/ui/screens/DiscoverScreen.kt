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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.recommendation.RecommendationManager
import com.vinmusic.ui.theme.VinColors
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

private val DISCOVER_CURRENT_YEAR: Int get() = java.time.LocalDate.now().year

// Diverse premium discovery query templates - focused on quality and variety
private val DISCOVER_QUERIES = listOf(
    "best rap hip hop songs $DISCOVER_CURRENT_YEAR",
    "top r&b soul hits $DISCOVER_CURRENT_YEAR",
    "new electronic dance music $DISCOVER_CURRENT_YEAR",
    "best jazz fusion songs",
    "top neo soul hits",
    "new synthwave retrowave $DISCOVER_CURRENT_YEAR",
    "best lo-fi hip hop beats",
    "top funk disco classics",
    "new ambient chill music",
    "best dream pop shoegaze",
    "top alternative rock $DISCOVER_CURRENT_YEAR",
    "new bedroom pop indie",
    "best trip hop downtempo",
    "top progressive rock modern",
    "new art pop experimental",
    "best afrobeat songs $DISCOVER_CURRENT_YEAR",
    "top reggae dancehall hits",
    "new latin reggaeton $DISCOVER_CURRENT_YEAR",
    "best punk rock songs",
    "top country hits $DISCOVER_CURRENT_YEAR",
    "new k-pop hits $DISCOVER_CURRENT_YEAR",
    "best metal songs $DISCOVER_CURRENT_YEAR",
    "top house techno music",
    "new drum and bass $DISCOVER_CURRENT_YEAR",
    "best acoustic folk songs",
    "top grime uk rap",
    "new vaporwave chill",
    "best bossa nova songs",
    "top blues rock classics",
    "new hyperpop experimental"
)

// Data class to wrap song with dynamic recommendation metadata
data class DiscoverSong(
    val videoItem: VideoItem,
    val recommendationReason: String,
    val vibeScore: Int,
    val matchHighlights: List<String> = emptyList()
)

/** Explains the signals the local recommendation engine actually used. */
private fun discoverMatchHighlights(
    song: VideoItem,
    profile: RecommendationManager.TasteProfile?
): List<String> {
    val metadata = runCatching { RecommendationManager.inferMetadata(song) }.getOrNull()
        ?: return listOf("Fresh official release")
    val highlights = mutableListOf<String>()

    profile?.topGenres?.take(3)?.firstOrNull {
        it.first.equals(metadata.genre, ignoreCase = true)
    }?.let { highlights += "Matches your ${metadata.genre} taste" }
    profile?.topMoods?.take(3)?.firstOrNull {
        it.first.equals(metadata.mood, ignoreCase = true)
    }?.let { highlights += "Fits your ${metadata.mood.lowercase()} mood" }
    profile?.tasteDNA?.let { dna ->
        if (kotlin.math.abs(metadata.energy - dna.targetEnergy) <= 0.12) {
            highlights += "Energy close to your usual"
        }
        if (kotlin.math.abs(metadata.tempo - dna.targetTempo) <= 14) {
            highlights += "Tempo close to your usual"
        }
    }
    if (highlights.isEmpty() && metadata.genre != "Unknown") {
        highlights += "A fresh ${metadata.genre} pick"
    }
    return highlights.ifEmpty { listOf("Fresh official release") }.take(2)
}

// ── Shown-card memory ────────────────────────────────────────────────────────
// Persists which songs were already dealt into a deck so repeated visits never
// reshuffle the same tracks. Keys are title|artist based (discoverSongKey), so
// re-uploads of the same song are caught too.
private const val DISCOVER_SHOWN_PREFS = "discover_shown_cards"
private const val DISCOVER_SHOWN_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

private fun loadShownDiscoverKeys(ctx: android.content.Context): Set<String> {
    return try {
        val prefs = ctx.getSharedPreferences(DISCOVER_SHOWN_PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("keys", null) ?: return emptySet()
        val map: Map<String, Long> = com.google.gson.Gson().fromJson(
            raw,
            object : com.google.gson.reflect.TypeToken<Map<String, Long>>() {}.type
        )
        val now = System.currentTimeMillis()
        map.filterValues { now - it < DISCOVER_SHOWN_MAX_AGE_MS }.keys
    } catch (_: Exception) {
        emptySet()
    }
}

private fun rememberShownDiscoverKeys(ctx: android.content.Context, keys: Collection<String>) {
    if (keys.isEmpty()) return
    try {
        val prefs = ctx.getSharedPreferences(DISCOVER_SHOWN_PREFS, android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString("keys", null)
        val existing: MutableMap<String, Long> = if (raw != null) {
            try {
                com.google.gson.Gson().fromJson(
                    raw,
                    object : com.google.gson.reflect.TypeToken<MutableMap<String, Long>>() {}.type
                )
            } catch (_: Exception) { mutableMapOf() }
        } else mutableMapOf()
        val now = System.currentTimeMillis()
        keys.forEach { existing[it] = now }
        // Prune entries older than the retention window so the file stays small.
        existing.values.removeAll { now - it >= DISCOVER_SHOWN_MAX_AGE_MS }
        prefs.edit().putString("keys", com.google.gson.Gson().toJson(existing)).apply()
    } catch (_: Exception) { /* memory is best-effort */ }
}

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
        "parody", "fanmade", "fan-made", "synthesia", "piano tutorial",
        "remix", "sped up", "speed up", "speedup", "nightcore", "8d audio",
        "lofi version", "lo-fi version", "slowed reverb", "edit audio",
        "tiktok version", "tik tok version", "extended mix", "club mix"
    )
    
    return ignoreKeywords.none { titleLower.contains(it) }
}

private fun parseDiscoverDurationMs(durationText: String): Long? {
    val parts = durationText.split(":")
        .map { it.trim().toLongOrNull() ?: return null }
    if (parts.size !in 2..3) return null
    return parts.fold(0L) { total, part -> total * 60L + part } * 1_000L
}

private fun discoverPreviewStartMs(durationText: String): Long {
    val duration = parseDiscoverDurationMs(durationText) ?: return 42_000L
    if (duration <= 70_000L) return (duration * 0.28f).toLong().coerceAtLeast(8_000L)
    val target = (duration * 0.38f).toLong()
    val latest = (duration - 38_000L).coerceAtLeast(12_000L)
    return target.coerceIn(22_000L.coerceAtMost(latest), latest)
}

private fun formatPreviewTime(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}

private fun isDiscoverCandidate(song: VideoItem): Boolean {
    val duration = parseDiscoverDurationMs(song.durationText) ?: return false
    if (duration !in 45_000L..720_000L) return false
    if (!isOfficialRelease(song)) return false
    if (RecommendationManager.isCompilationTrack(song.title, song.durationText)) return false
    if (RecommendationManager.isNonMusicVideo(song.title, song.author)) return false
    if (RecommendationManager.isUnofficialContent(song.title, song.author)) return false
    return true
}

private fun buildDiscoverDeck(pool: List<DiscoverSong>): List<DiscoverSong> {
    return pool
        .filter { isDiscoverCandidate(it.videoItem) }
        .distinctBy { discoverSongKey(it.videoItem) }
        .shuffled()
        .sortedByDescending { it.vibeScore }
        .fold(mutableListOf<DiscoverSong>()) { acc, song ->
            val artistKey = song.videoItem.author.lowercase().trim()
            val artistCount = acc.count { it.videoItem.author.lowercase().trim() == artistKey }
            if (artistCount < 1) acc.apply { add(song) } else acc
        }
        .take(36)
}

private fun discoverSongKey(song: VideoItem): String {
    val title = song.title
        .lowercase()
        .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        .replace(Regex("""(?i)\bofficial\b|\bmusic video\b|\baudio\b|\blyrics?\b|\bfull song\b|\bvisualizer\b|\bhd\b|\b4k\b"""), " ")
        .replace(Regex("""(?i)\bfeat\.?\b.*|\bft\.?\b.*"""), " ")
        .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    val artist = song.author
        .lowercase()
        .replace(" - topic", "")
        .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return "$title|$artist"
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

    var selectedMix by remember { mutableStateOf<com.vinmusic.recommendation.SpotifyMix?>(null) }

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

                // A discovery deck must contain NEW music — never tracks the user
                // just played or was already shown. These exclusions apply to every
                // candidate source below.
                val recentHistoryIds = history.take(40).map { it.videoId }.toSet()
                val shownKeys = loadShownDiscoverKeys(ctx)

                fun usableForDeck(songs: List<VideoItem>): List<VideoItem> = songs.filter {
                    it.videoId !in recentHistoryIds &&
                        !shownKeys.contains(discoverSongKey(it)) &&
                        isDiscoverCandidate(it)
                }

                val tasteProfile = try {
                    RecommendationManager.buildTasteProfile(db)
                } catch (_: Exception) { null }

                fun dnaScore(song: VideoItem): Int {
                    val profile = tasteProfile ?: return 72
                    return try {
                        (RecommendationManager.calculateTasteSimilarity(
                            RecommendationManager.inferMetadata(song), profile.tasteDNA
                        ) * 100).toInt().coerceIn(55, 99)
                    } catch (_: Exception) { 72 }
                }

                val discoverPool = mutableListOf<DiscoverSong>()
                fun addCandidate(song: VideoItem, reason: String, score: Int) {
                    if (song.videoId !in recentHistoryIds &&
                        !shownKeys.contains(discoverSongKey(song)) &&
                        isDiscoverCandidate(song)
                    ) {
                        discoverPool.add(DiscoverSong(song, reason, score.coerceIn(55, 100)))
                    }
                }

                // ── Instant deck: only related tracks from this user's own seeds ─
                // Do not use the broad cache here. Its recency query can contain
                // stale metadata and makes Discover feel unrelated to the listener.
                val instantSeedIds = (
                    signals.sortedByDescending { it.completeCount + it.repeatCount * 2 + it.playCount }
                        .map { it.videoId } + history.map { it.videoId }
                    ).filter { it.isNotBlank() }.distinct().take(4)
                instantSeedIds.forEach { seedId ->
                    try {
                        db.relatedSongDao().relatedForSong(seedId, 8).forEach { row ->
                            val item = VideoItem(row.videoId, row.title, row.author, row.durationText)
                            addCandidate(item, "A fresh match for your listening", dnaScore(item))
                        }
                    } catch (_: Exception) {}
                }
                val instantDeck = buildDiscoverDeck(discoverPool)
                if (instantDeck.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        cards = instantDeck
                        autoPreviewEnabled = true
                    }
                    // A dealt card should not be dealt again next visit—even if
                    // the user exits without swiping it. This is what prevents the
                    // same deck from returning over and over.
                    rememberShownDiscoverKeys(ctx, instantDeck.map { discoverSongKey(it.videoItem) })
                }

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
                // Artist seeds progress deterministically through the user's artist
                // list in 6-hour buckets, so each session explores a different slice
                // (plus one wildcard for variety).
                val rotationBucket = (System.currentTimeMillis() / (6L * 3_600_000)).toInt()
                val rotatedArtists = if (combinedArtists.isEmpty()) emptyList() else
                    List(combinedArtists.size) { i -> combinedArtists[(i + rotationBucket) % combinedArtists.size] }
                val seedArtists = rotatedArtists.take(2)
                    .distinct()
                    .take(2)
                val seedLikes = liked.shuffled().take(1)
                val seedPlaylists = playlistSongs.shuffled().take(1)

                coroutineScope {
                    val deferreds = mutableListOf<Deferred<List<DiscoverSong>>>()
                    // A slow YouTube request must not keep the first card behind
                    // a spinner. Individual sources are optional; the deck uses
                    // whichever personalized sources return within this budget.
                    fun boundedFetch(block: suspend () -> List<DiscoverSong>): Deferred<List<DiscoverSong>> =
                        async(Dispatchers.IO) {
                            withTimeoutOrNull(7_500L) { block() } ?: emptyList()
                        }

                    // Quick picks from recommendation engine
                    deferreds.add(boundedFetch {
                        try {
                            usableForDeck(vm.recommendationRepository.getQuickPicks())
                                .take(12)
                                .map { song ->
                                    DiscoverSong(
                                        videoItem = song,
                                        recommendationReason = "Smart mix from your taste",
                                        vibeScore = dnaScore(song)
                                    )
                                }
                        } catch (_: Exception) { emptyList() }
                    })

                    // Radio seeds from liked/history/playlist songs — ranked similar
                    // songs via Smart Queue, not raw search.
                    val radioSeeds = (liked.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                        history.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                        playlistSongs.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
                        .distinctBy { it.videoId }
                        .sortedByDescending { song -> signals.firstOrNull { it.videoId == song.videoId }?.playCount ?: 0 }
                        .take(1)

                    radioSeeds.forEach { seed ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(vm.recommendationRepository.getSongRadio(seed.videoId, seed.title, seed.author))
                                    .take(6)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Radio match from ${seed.title}",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // Artist-specific searches — rotated by time bucket so repeated
                    // sessions explore different artists instead of the same two.
                    seedArtists.forEach { artist ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(InnerTube.search("$artist popular songs"))
                                    .take(3)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Based on your love for $artist",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })

                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(InnerTube.search("$artist new song"))
                                    .take(2)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "New from $artist",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // Similar to Liked Songs — YT Music's own related-tracks engine
                    // (scored similar songs), not "songs like X" text search.
                    seedLikes.forEach { likedSong ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(vm.recommendationRepository.getRelatedSongs(likedSong.videoId))
                                    .take(5)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Vibe match with '${likedSong.title}'",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // Similar to Playlist Songs seed
                    seedPlaylists.forEach { plSong ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(vm.recommendationRepository.getRelatedSongs(plSong.videoId))
                                    .take(5)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Inspired by your playlist track",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // Fresh discovery queries for variety
                    val personalizedFreshQueries = tasteProfile?.topGenres
                        ?.take(2)
                        ?.map { (genre, _) -> "$genre new releases $DISCOVER_CURRENT_YEAR" }
                        ?.takeIf { it.isNotEmpty() }
                        ?: DISCOVER_QUERIES.shuffled().take(2)
                    personalizedFreshQueries.forEach { freshQuery ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(InnerTube.search(freshQuery))
                                    .take(5)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                        recommendationReason = "Fresh: ${freshQuery.replace(DISCOVER_CURRENT_YEAR.toString(), "").trim()}",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    // Generic exploration is useful only for a true cold start.
                    // Never let it flood an established user's deck with music
                    // that has no connection to their listening behavior.
                    val genreDiversityQueries = listOf(
                        "best jazz songs all time",
                        "top electronic music $DISCOVER_CURRENT_YEAR",
                        "best classical crossover",
                        "top afrobeat songs $DISCOVER_CURRENT_YEAR",
                        "new reggae dancehall hits",
                        "best metal songs trending",
                        "top punk rock songs",
                        "new latin reggaeton hits",
                        "best drum and bass $DISCOVER_CURRENT_YEAR",
                        "top country hits $DISCOVER_CURRENT_YEAR",
                        "new k-pop songs trending",
                        "best grime uk rap",
                        "top house techno $DISCOVER_CURRENT_YEAR",
                        "new bossa nova songs",
                        "best blues rock classics"
                    )
                    if (combinedArtists.isEmpty() && liked.isEmpty() && playlistSongs.isEmpty()) genreDiversityQueries.shuffled().take(2).forEach { query ->
                        deferreds.add(boundedFetch {
                            try {
                                usableForDeck(InnerTube.search(query))
                                    .take(3)
                                    .map { song ->
                                        DiscoverSong(
                                            videoItem = song,
                                            recommendationReason = "Expand your taste",
                                            vibeScore = dnaScore(song)
                                        )
                                    }
                            } catch (_: Exception) { emptyList() }
                        })
                    }

                    val results = deferreds.awaitAll().flatten()
                    discoverPool.addAll(results)
                }

                // 6. Deduplicate, score-sort, and keep artist diversity.
                // Cards are remembered as "shown" only when actually swiped
                // (see dropCurrentCard) — marking the whole deck on deal made
                // every repeat visit slower and emptier.
                val uniqueDiscover = buildDiscoverDeck(discoverPool).map { candidate ->
                    candidate.copy(
                        matchHighlights = discoverMatchHighlights(candidate.videoItem, tasteProfile)
                    )
                }

                withContext(Dispatchers.Main) {
                    val existingKeys = cards.map { discoverSongKey(it.videoItem) }.toSet()
                    val newCards = uniqueDiscover.filter { discoverSongKey(it.videoItem) !in existingKeys }
                    if (cards.isEmpty()) {
                        // First load — use entire deck
                        cards = uniqueDiscover
                    } else if (newCards.isNotEmpty()) {
                        // Append new cards to the BOTTOM of the stack (index 0)
                        // so they appear after the user swipes through existing cards
                        cards = newCards + cards
                    }
                    isLoading = false
                    autoPreviewEnabled = true
                }
                if (uniqueDiscover.isNotEmpty()) {
                    rememberShownDiscoverKeys(ctx, uniqueDiscover.map { discoverSongKey(it.videoItem) })
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDiscoverSongs()
    }

    fun dropCurrentCard() {
        val swipedCard = cards.lastOrNull()
        if (swipedCard != null) {
            // Remember only cards the user actually swiped past — this keeps
            // future decks fresh without draining the instant local pool.
            scope.launch(Dispatchers.IO) {
                rememberShownDiscoverKeys(ctx, listOf(discoverSongKey(swipedCard.videoItem)))
            }
        }
        val remainingCards = cards.dropLast(1)
        cards = remainingCards
        if (remainingCards.size <= 4 && !isLoading) {
            loadDiscoverSongs()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VinColors.BgColor)) {
        if (isLoading && cards.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(48.dp), strokeWidth = 3.dp)
                    Text("Loading", color = VinColors.Secondary, fontSize = 14.sp)
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

            // Solid dark gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(com.vinmusic.ui.theme.Vin.Gradients.background)
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
                DiscoverGenreFilterRow(
                    selected = null,
                    onSelected = { }
                )

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
                                swipeState.animateLeft {
                                    vm.recordDiscoverSkip(currentCard.videoItem)
                                    showToastMessage = "We'll show less like this"
                                    dropCurrentCard()
                                }
                            }
                        },
                        onSwipedRight = {
                            scope.launch {
                                swipeState.animateRight {
                                    vm.toggleLike(currentCard.videoItem)
                                    showToastMessage = "Added to Liked Songs"
                                    dropCurrentCard()
                                }
                            }
                        },
                        onSwipedUp = {
                            val discoveryQueue = cards.asReversed().map { it.videoItem }
                            onSongClick(currentCard.videoItem, discoveryQueue)
                            showToastMessage = "Playing your discovery queue"
                            dropCurrentCard()
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
                            onClick = {
                                scope.launch {
                                    swipeState.animateLeft {
                                        vm.recordDiscoverSkip(currentCard.videoItem)
                                        showToastMessage = "We'll show less like this"
                                        dropCurrentCard()
                                    }
                                }
                            },
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
                                else vm.playSongPreview(
                                    currentCard.videoItem,
                                    discoverPreviewStartMs(currentCard.videoItem.durationText)
                                )
                            },
                            modifier = Modifier.size(80.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFFC5A880), Color(0xFFE5CBA3))))
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
                                        dropCurrentCard()
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

        if (selectedMix != null) {
            val mix = selectedMix ?: return
            val startColor = remember(mix.gradientStartHex) {
                runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
            }
            val endColor = remember(mix.gradientEndHex) {
                runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }
            }

            ModalBottomSheet(
                onDismissRequest = { selectedMix = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = VinColors.Surface2,
                dragHandle = { BottomSheetDefaults.DragHandle(color = VinColors.GlassBorder) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                        ) {
                            GenreMix2x2Grid(songs = mix.songs, modifier = Modifier.fillMaxSize())
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mix.title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mix.description,
                                fontSize = 12.sp,
                                color = VinColors.Secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (mix.songs.isNotEmpty()) {
                                val tracks = mix.songs.map { it.videoItem }
                                onSongClick(tracks[0], tracks)
                                selectedMix = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play Mix", fontWeight = FontWeight.Bold)
                    }

                    Spacer(Modifier.height(16.dp))

                    HorizontalDivider(color = VinColors.GlassBorder.copy(alpha = 0.3f))

                    Spacer(Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                    ) {
                        if (mix.songs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No tracks inside this mix.", color = VinColors.Secondary, fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                itemsIndexed(mix.songs) { index, recSong ->
                                    val song = recSong.videoItem
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(VinColors.White10)
                                            .clickable {
                                                val tracks = mix.songs.map { it.videoItem }
                                                onSongClick(song, tracks)
                                                selectedMix = null
                                            }
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) {
                                            AsyncImage(
                                                model = song.thumbnail,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = song.title,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = song.author,
                                                fontSize = 11.sp,
                                                color = VinColors.Secondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
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
    onSwipedRight: () -> Unit,
    onSwipedUp: () -> Unit
) {
    val song = discoverSong.videoItem
    val isCurrentSong = vm.currentSong?.videoId == song.videoId
    val isPlaying = isCurrentSong && vm.isPlaying && !vm.isLoading
    
    // Auto-preview from the hook/mid-song section instead of starting at 0:00.
    LaunchedEffect(song.videoId) {
        // Wait to settle transitions and debounce fast swipes
        delay(350L)
        if (autoPreviewEnabled) {
            if (vm.currentSong?.videoId != song.videoId) {
                vm.playSongPreview(song, discoverPreviewStartMs(song.durationText))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .offset { IntOffset(swipeState.offsetX.value.roundToInt(), swipeState.offsetY.value.roundToInt()) }
            .graphicsLayer { rotationZ = (swipeState.offsetX.value / 18f).coerceIn(-12f, 12f) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, drag -> change.consume(); swipeState.drag(drag.x, drag.y) },
                    onDragEnd = {
                        swipeState.released(
                            horizontalThreshold = size.width * 0.32f,
                            verticalThreshold = size.height * 0.18f,
                            onLeft = onSwipedLeft,
                            onRight = onSwipedRight,
                            onUp = onSwipedUp
                        )
                    }
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
                    .background(Brush.linearGradient(listOf(Color(0xFFC5A880), Color(0xFFE5CBA3))))
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

            if (isPlaying) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.46f))
                        .padding(6.dp)
                )
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

        if (discoverSong.matchHighlights.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                discoverSong.matchHighlights.forEach { highlight ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.46f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null, tint = VinColors.AccentLight, modifier = Modifier.size(11.dp))
                        Text(
                            text = highlight,
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.88f),
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Center artwork, no decorative disc behind it.
        Box(
            modifier = Modifier.align(Alignment.Center).offset(y = (-70).dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.size(172.dp)
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

        // Bottom column stays compact so card metadata never collides with the artwork.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.52f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(Icons.Default.GraphicEq, null, tint = VinColors.AccentLight, modifier = Modifier.size(15.dp))
                Text(
                    text = "Hook preview starts at ${formatPreviewTime(discoverPreviewStartMs(song.durationText))}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.92f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Song Info (Title & Author)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    song.title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
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
                            .background(Color(0xFFC5A880).copy(alpha = 0.4f))
                            .border(1.dp, Color(0xFFC5A880), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) { Text("Previewing", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold) }
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

        AnimatedVisibility(
            visible = swipeState.offsetY.value < -90f && kotlin.math.abs(swipeState.offsetY.value) > kotlin.math.abs(swipeState.offsetX.value),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(VinColors.Accent.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Text("PLAY THIS DISCOVERY QUEUE", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun DiscoverGenreFilterRow(
    selected: Unit?,
    onSelected: (Unit?) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "All Songs",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = "Swipe up to play the deck",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = VinColors.AccentLight
            )
        }
    }
}

@Composable
fun GenreMix2x2Grid(songs: List<com.vinmusic.recommendation.RecommendedSong>, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
    ) {
        val coverSongs = songs.take(4)
        if (coverSongs.size >= 4) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = coverSongs[0].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    AsyncImage(
                        model = coverSongs[1].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = coverSongs[2].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    AsyncImage(
                        model = coverSongs[3].videoItem.thumbnail,
                        contentDescription = null,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else {
            val singleCover = coverSongs.firstOrNull()?.videoItem?.thumbnail
            if (singleCover != null) {
                AsyncImage(
                    model = singleCover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
fun GenreMixCard(
    mix: com.vinmusic.recommendation.SpotifyMix,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "genre_mix_card_scale"
    )

    val startColor = remember(mix.gradientStartHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
    }
    val endColor = remember(mix.gradientEndHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }
    }

    Column(
        modifier = Modifier
            .width(130.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
        ) {
            GenreMix2x2Grid(songs = mix.songs, modifier = Modifier.fillMaxSize())
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                        )
                    )
            )
            // A premium glassmorphic tag indicating "MIX"
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "MIX",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = mix.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
        Text(
            text = mix.description,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
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

    suspend fun animateUp(onComplete: () -> Unit) {
        offsetY.animateTo(-1800f, tween(300, easing = FastOutSlowInEasing))
        onComplete()
        offsetX.snapTo(0f); offsetY.snapTo(0f)
    }

    fun released(
        horizontalThreshold: Float,
        verticalThreshold: Float,
        onLeft: () -> Unit,
        onRight: () -> Unit,
        onUp: () -> Unit
    ) {
        scope.launch {
            when {
                offsetY.value < -verticalThreshold && kotlin.math.abs(offsetY.value) > kotlin.math.abs(offsetX.value) -> animateUp(onUp)
                offsetX.value > horizontalThreshold -> onRight()
                offsetX.value < -horizontalThreshold -> onLeft()
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
