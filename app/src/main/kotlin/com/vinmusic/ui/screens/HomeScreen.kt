package com.vinmusic.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vinmusic.innertube.AlbumItem
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.YTMusicSession
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.components.SongListItem
import com.vinmusic.ui.components.TrackCard
import com.vinmusic.ui.components.UserAvatar
import com.vinmusic.ui.theme.VinColors
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val CATEGORIES = listOf("All", "For You", "Happy", "Sad", "Energize", "Sleep", "Focus", "Workout", "Party", "Bollywood", "Lo-fi", "Rap", "Indie", "K-Pop", "90s Hits")

data class RapSubCategory(
    val name: String,
    val icon: String,
    val queries: List<String>  // multiple queries for richer results
)

private val RAP_SUB_CATEGORIES = listOf(
    RapSubCategory("All Rap",        "🎤", listOf("best rap songs 2025", "top rap hits")),
    RapSubCategory("Lyrical",        "📝", listOf("lyrical rap deep bars", "lyrical hip hop conscious rap")),
    RapSubCategory("Storytelling",   "📖", listOf("storytelling rap songs", "narrative rap best songs")),
    RapSubCategory("Vibe",           "🌊", listOf("chill vibe rap songs", "vibe rap relaxed flow")),
    RapSubCategory("Sad",            "😢", listOf("sad rap songs emotional", "sad rap heartbreak")),
    RapSubCategory("Happy",          "😄", listOf("happy upbeat rap songs", "feel good rap")),
    RapSubCategory("Aggressive",     "🔥", listOf("aggressive rap hard bars", "aggressive trap rap")),
    RapSubCategory("Desi Hip-Hop",   "🇮🇳", listOf("desi hip hop indian rap", "indian rap songs 2025")),
    RapSubCategory("Old School",     "📼", listOf("old school hip hop classic", "90s rap golden era")),
    RapSubCategory("Trap",           "💣", listOf("trap music best songs", "trap rap hard beats")),
    RapSubCategory("Drill",          "🔫", listOf("drill rap songs", "uk drill rap")),
    RapSubCategory("Freestyle",      "⚡", listOf("freestyle rap best", "freestyle rap cypher"))
)

private val SIMILAR_ARTISTS_MAP = mapOf(
    "j. cole" to listOf("Kendrick Lamar", "Drake", "JID", "Cordae", "Joey Bada\$\$", "Kanye West"),
    "j cole" to listOf("Kendrick Lamar", "Drake", "JID", "Cordae", "Joey Bada\$\$", "Kanye West"),
    "kendrick lamar" to listOf("J. Cole", "Drake", "Travis Scott", "21 Savage", "Baby Keem", "A\$AP Rocky"),
    "21 savage" to listOf("Metro Boomin", "Future", "Travis Scott", "Drake", "Lil Baby", "Gunna"),
    "travis scott" to listOf("Metro Boomin", "Don Toliver", "Kid Cudi", "A\$AP Rocky", "Kanye West"),
    "drake" to listOf("J. Cole", "Kendrick Lamar", "The Weeknd", "Future", "Lil Baby", "Travis Scott"),
    "arijit singh" to listOf("Atif Aslam", "Jubin Nautiyal", "Shreya Ghoshal", "Pritam", "Darshan Raval"),
    "sidhu moose wala" to listOf("Karan Aujla", "Diljit Dosanjh", "Shubh", "Prem Dhillon", "Amrit Maan"),
    "karan aujla" to listOf("Sidhu Moose Wala", "Diljit Dosanjh", "Shubh", "AP Dhillon", "Garry Sandhu"),
    "diljit dosanjh" to listOf("Karan Aujla", "Sidhu Moose Wala", "AP Dhillon", "Ammy Virk", "Shubh"),
    "shubh" to listOf("AP Dhillon", "Gurinder Gill", "Sidhu Moose Wala", "Karan Aujla", "Diljit Dosanjh"),
    "prateek kuhad" to listOf("Anuv Jain", "Local Train", "When Chai Met Toast", "Yellow Diary"),
    "anuv jain" to listOf("Prateek Kuhad", "Aditya Rikhari", "Mitraz", "Local Train", "Osho Jain"),
    "mitraz" to listOf("Anuv Jain", "Aditya Rikhari", "Darshan Raval", "Zaeden", "Prateek Kuhad"),
    "the weeknd" to listOf("Post Malone", "Khalid", "Frank Ocean", "SZA", "Brent Faiyaz"),
    "taylor swift" to listOf("Olivia Rodrigo", "Billie Eilish", "Sabrina Carpenter", "Ed Sheeran"),
    "eminem" to listOf("Dr. Dre", "50 Cent", "Snoop Dogg", "J. Cole", "Kendrick Lamar"),
    "badshah" to listOf("Raftaar", "Yo Yo Honey Singh", "King", "MC Stan", "Divine"),
    "divine" to listOf("Naezy", "Raftaar", "MC Stan", "Seedhe Maut", "Kr\$na"),
    "kr\$na" to listOf("Raftaar", "Seedhe Maut", "Divine", "Emiway Bantai", "Young Stunners")
)

fun normalizeArtistName(name: String): String {
    var clean = name.lowercase(java.util.Locale.ROOT).trim()
    clean = clean.replace(Regex("- topic$"), "").trim()
    clean = clean.replace(Regex("\\bvevo\\b"), "").trim()
    clean = clean.replace(Regex("[^a-z0-9\\s]"), "")
    clean = clean.replace(Regex("\\s+"), " ").trim()
    return clean
}

data class QuickPlaylist(
    val name: String,
    val query: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradStart: Color,
    val gradEnd: Color
)

private val QUICK_PLAYLISTS = listOf(
    QuickPlaylist("Chill Vibes", "chill lofi hindi music",  Icons.Default.MusicNote,  Color(0xFF8B5CF6), Color(0xFF3B0764)),
    QuickPlaylist("Workout",    "gym workout music 2025",  Icons.Default.Bolt,       Color(0xFF3B82F6), Color(0xFF1E3A8A)),
    QuickPlaylist("Party Hits",  "party hits 2025 india",   Icons.Default.Star,       Color(0xFFEF4444), Color(0xFF500724)),
    QuickPlaylist("Focus",      "study focus music",       Icons.Default.School,     Color(0xFF10B981), Color(0xFF064E3B)),
    QuickPlaylist("Bollywood",  "bollywood superhits",     Icons.Default.Favorite,   Color(0xFFEF4444), Color(0xFF7F1D1D)),
)

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onDiscoverClick: () -> Unit
) {
    val ctx   = LocalContext.current
    val db    = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
    val prefs = remember(ctx) { ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE) }

    var userName       by remember { mutableStateOf(prefs.getString("user_name", "Vin") ?: "Vin") }
    var avatarIndex    by remember { mutableIntStateOf(prefs.getInt("user_avatar_idx", 0)) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var editName       by remember { mutableStateOf(userName) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "user_name") {
                userName = prefs.getString("user_name", "Vin") ?: "Vin"
            } else if (key == "user_avatar_idx") {
                avatarIndex = prefs.getInt("user_avatar_idx", 0)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    var filter         by remember { mutableStateOf("All") }
    var likedSongs     by remember { mutableStateOf<List<com.vinmusic.data.db.LikedSong>>(emptyList()) }

    var categorySongs  by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isCategoryLoading by remember { mutableStateOf(false) }

    // Rap sub-category state
    var rapSubFilter by remember { mutableStateOf("All Rap") }
    var rapSubSections by remember { mutableStateOf<List<Pair<String, List<VideoItem>>>>(emptyList()) }
    var isRapSubLoading by remember { mutableStateOf(false) }

    // Home Screen Sections Data
    var recentlyPlayed  by remember { mutableStateOf<List<com.vinmusic.data.db.HistoryEntry>>(emptyList()) }
    var suggestedArtists by remember { mutableStateOf<List<com.vinmusic.innertube.ArtistItem>>(emptyList()) }
    var recommendedAlbums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var downloads       by remember { mutableStateOf<List<com.vinmusic.data.db.DownloadEntity>>(emptyList()) }

    var selectedArtist by remember { mutableStateOf<com.vinmusic.innertube.ArtistItem?>(null) }
    val scope     = rememberCoroutineScope()

    var recommendedPlaylists by remember { mutableStateOf<List<com.vinmusic.innertube.AlbumItem>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var recommendedPlaylistsLoaded by remember { mutableStateOf(false) }
    
    var selectedRecommendedPlaylist by remember { mutableStateOf<com.vinmusic.innertube.AlbumItem?>(null) }
    var recommendedPlaylistSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingPlaylistSongs by remember { mutableStateOf(false) }

    var recommendationSections by remember { mutableStateOf<List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>>(emptyList()) }
    var spotifyMixes by remember { mutableStateOf<List<com.vinmusic.recommendation.SpotifyMix>>(emptyList()) }
    var isLoadingMixes by remember { mutableStateOf(false) }
    var selectedSpotifyMix by remember { mutableStateOf<com.vinmusic.recommendation.SpotifyMix?>(null) }
    var isRecommendationsLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()

    var quickPicks by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingQuickPicks by remember { mutableStateOf(false) }

    var recommendedRadio by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingRecommendedRadio by remember { mutableStateOf(false) }
    var radioSeedSong by remember { mutableStateOf<VideoItem?>(null) }

    var ytMusicSections by remember { mutableStateOf<List<com.vinmusic.innertube.YTMusicHomeSection>>(emptyList()) }
    var isLoadingYtHome by remember { mutableStateOf(false) }
    var ytMusicConnected by remember { mutableStateOf(YTMusicSession.hasCookie(ctx)) }
    var ytLibraryPlaylists by remember { mutableStateOf<List<com.vinmusic.innertube.AlbumItem>>(emptyList()) }
    var isLoadingYtPlaylists by remember { mutableStateOf(false) }

    // Mood Deep Sections
    var moodSections by remember { mutableStateOf<List<Pair<String, List<VideoItem>>>>(emptyList()) }
    var isMoodLoading by remember { mutableStateOf(false) }

    // Long Listens (45min+ mixes)
    var longListens by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingLongListens by remember { mutableStateOf(false) }

    // Similar To currently playing song
    var similarToSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }

    // Helper to reload everything
    fun loadRecommendedPlaylists(forceRefresh: Boolean = false) {
        isLoadingPlaylists = true
        recommendedPlaylistsLoaded = false
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("recommended_playlists_cache", Context.MODE_PRIVATE)
                if (forceRefresh) {
                    cachePrefs.edit().clear().apply()
                }

                val cachedJson = cachePrefs.getString("playlists_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()

                if (cachedJson != null && !forceRefresh && (now - cacheTime < 12 * 60 * 60 * 1000L)) {
                    val type = object : com.google.gson.reflect.TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                    val list: List<com.vinmusic.innertube.AlbumItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            recommendedPlaylists = list
                        }
                        return@launch
                    }
                }

                val localPlaylists = db.playlistDao().getAll()
                val liked = db.likedSongDao().getAll()
                val queries = mutableListOf<String>()

                if (localPlaylists.isNotEmpty()) {
                    localPlaylists.shuffled().take(2).forEach { pl ->
                        val plName = pl.name.trim()
                        val genericNames = listOf("playlist", "new playlist", "imported playlist", "favorites", "liked", "custom playlist")
                        if (plName.isNotEmpty() && !genericNames.any { plName.lowercase() == it }) {
                            queries.add("$plName playlist")
                        }
                    }
                }

                if (liked.isNotEmpty()) {
                    val topArtist = liked.groupBy { it.author }
                        .filter { it.key.isNotBlank() }
                        .maxByOrNull { it.value.size }?.key?.trim()
                    if (!topArtist.isNullOrEmpty()) {
                        queries.add("$topArtist playlist")
                    }
                }

                if (queries.size < 2) {
                    queries.add("hindi lofi chill playlist")
                    queries.add("punjabi hits playlist")
                    queries.add("viral english songs playlist")
                }

                val allResults = mutableListOf<com.vinmusic.innertube.AlbumItem>()
                coroutineScope {
                    val deferreds = queries.take(3).map { query ->
                        async(Dispatchers.IO) {
                            try {
                                com.vinmusic.innertube.InnerTube.searchCommunityPlaylists(query)
                            } catch (e: Exception) {
                                emptyList<com.vinmusic.innertube.AlbumItem>()
                            }
                        }
                    }
                    allResults.addAll(deferreds.awaitAll().flatten())
                }

                val uniquePlaylists = allResults
                    .distinctBy { it.playlistId }
                    .filter { it.playlistId.startsWith("PL") || it.playlistId.startsWith("VL") }
                    .take(8)

                if (uniquePlaylists.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("playlists_json", com.google.gson.Gson().toJson(uniquePlaylists))
                        .putLong("cache_time", now)
                        .apply()
                }

                withContext(Dispatchers.Main) {
                    recommendedPlaylists = uniquePlaylists
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load recommended playlists", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingPlaylists = false
                    recommendedPlaylistsLoaded = true
                }
            }
        }
    }

    fun triggerRefresh() {
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            try {
                ctx.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE).edit().clear().apply()
                com.vinmusic.recommendation.RecommendationManager.invalidateCache(ctx)
                
                // Clear persistent caches
                ctx.getSharedPreferences("suggested_artists_cache", Context.MODE_PRIVATE).edit().clear().apply()
                ctx.getSharedPreferences("long_listens_cache", Context.MODE_PRIVATE).edit().clear().apply()
                ctx.getSharedPreferences("recommended_albums_cache", Context.MODE_PRIVATE).edit().clear().apply()
                ctx.getSharedPreferences("recommended_playlists_cache", Context.MODE_PRIVATE).edit().clear().apply()
                
                loadRecommendedPlaylists(forceRefresh = true)

                // Concurrently resolve all recommendation and network streams
                val recs = com.vinmusic.recommendation.RecommendationManager.getRecommendations(ctx, forceRefresh = true)
                val mixes = com.vinmusic.recommendation.RecommendationManager.getSpotifyMixes(ctx, forceRefresh = true)
                val qp = try { vm.recommendationRepository.getQuickPicks() } catch (_: Exception) { emptyList() }
                val yt = try { vm.recommendationRepository.getYouTubeMusicHomeSections() } catch (_: Exception) { emptyList() }
                val playlists = try {
                    if (YTMusicSession.hasCookie(ctx)) vm.recommendationRepository.getLibraryPlaylists() else emptyList()
                } catch (_: Exception) { emptyList() }
                
                val seed = radioSeedSong
                val rad = if (seed != null) {
                    try { vm.recommendationRepository.getSongRadio(seed.videoId) } catch (_: Exception) { emptyList() }
                } else emptyList()
                
                val albumsResult = try { InnerTube.searchAll("best hindi albums playlist 2025").albums.take(6) } catch (_: Exception) { emptyList() }

                // Single unified Main thread dispatch
                withContext(Dispatchers.Main) {
                    recommendationSections = recs
                    spotifyMixes = mixes
                    quickPicks = qp
                    ytMusicSections = yt
                    ytMusicConnected = YTMusicSession.hasCookie(ctx)
                    ytLibraryPlaylists = playlists
                    if (rad.isNotEmpty()) {
                        recommendedRadio = rad
                    }
                    if (albumsResult.isNotEmpty()) {
                        recommendedAlbums = albumsResult
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Refresh failed: ${e.message}")
            } finally {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadRecommendedPlaylists()
        scope.launch(Dispatchers.IO) {
            try {
                isRecommendationsLoading = true
                val recs = com.vinmusic.recommendation.RecommendationManager.getRecommendations(ctx, forceRefresh = false)
                recommendationSections = recs
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load recommendations: ${e.message}")
            } finally {
                isRecommendationsLoading = false
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                isLoadingMixes = true
                val mixes = com.vinmusic.recommendation.RecommendationManager.getSpotifyMixes(ctx, forceRefresh = false)
                withContext(Dispatchers.Main) {
                    spotifyMixes = mixes
                    isLoadingMixes = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load Spotify mixes: ${e.message}")
                withContext(Dispatchers.Main) {
                    isLoadingMixes = false
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                isLoadingQuickPicks = true
                val qp = vm.recommendationRepository.getQuickPicks()
                withContext(Dispatchers.Main) {
                    quickPicks = qp
                    isLoadingQuickPicks = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load Quick Picks: ${e.message}")
                withContext(Dispatchers.Main) {
                    isLoadingQuickPicks = false
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                isLoadingYtHome = true
                val yt = vm.recommendationRepository.getYouTubeMusicHomeSections()
                withContext(Dispatchers.Main) {
                    ytMusicSections = yt
                    ytMusicConnected = YTMusicSession.hasCookie(ctx)
                    isLoadingYtHome = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load YT Music home: ${e.message}")
                withContext(Dispatchers.Main) { isLoadingYtHome = false }
            }
        }
        scope.launch(Dispatchers.IO) {
            if (YTMusicSession.hasCookie(ctx)) {
                try {
                    isLoadingYtPlaylists = true
                    val playlists = vm.recommendationRepository.getLibraryPlaylists()
                    withContext(Dispatchers.Main) {
                        ytLibraryPlaylists = playlists
                        isLoadingYtPlaylists = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Failed to load YT playlists: ${e.message}")
                    withContext(Dispatchers.Main) { isLoadingYtPlaylists = false }
                }
            }
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "yt_music_cookie") {
                val connected = YTMusicSession.hasCookie(ctx)
                ytMusicConnected = connected
                if (connected) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            isLoadingYtPlaylists = true
                            val playlists = vm.recommendationRepository.getLibraryPlaylists()
                            withContext(Dispatchers.Main) {
                                ytLibraryPlaylists = playlists
                                isLoadingYtPlaylists = false
                            }
                        } catch (_: Exception) {
                            withContext(Dispatchers.Main) { isLoadingYtPlaylists = false }
                        }
                    }
                } else {
                    ytLibraryPlaylists = emptyList()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(recentlyPlayed) {
        val lastSong = recentlyPlayed.firstOrNull()
        if (lastSong != null) {
            val seed = VideoItem(lastSong.videoId, lastSong.title, lastSong.author, lastSong.durationText)
            radioSeedSong = seed
            scope.launch(Dispatchers.IO) {
                try {
                    isLoadingRecommendedRadio = true
                    val rad = vm.recommendationRepository.getSongRadio(seed.videoId)
                    withContext(Dispatchers.Main) {
                        recommendedRadio = rad
                        isLoadingRecommendedRadio = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Failed to load Recommended Radio: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isLoadingRecommendedRadio = false
                    }
                }
            }
        }
    }

    var lastRecommendationSeedId by remember { mutableStateOf("") }
    LaunchedEffect(vm.currentSong?.videoId) {
        val currentSeedId = vm.currentSong?.videoId ?: ""
        if (currentSeedId.isNotEmpty() && currentSeedId != lastRecommendationSeedId) {
            lastRecommendationSeedId = currentSeedId
            scope.launch(Dispatchers.IO) {
                try {
                    com.vinmusic.recommendation.RecommendationManager.invalidateCache()
                    val recs = com.vinmusic.recommendation.RecommendationManager.getRecommendations(ctx, forceRefresh = true)
                    withContext(Dispatchers.Main) {
                        recommendationSections = recs
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Failed to update recommendations on song play: ${e.message}")
                }
            }
            // Load "Similar To" songs for current song
            scope.launch(Dispatchers.IO) {
                try {
                    val similar = vm.recommendationRepository.getSongRadio(currentSeedId)
                    withContext(Dispatchers.Main) {
                        similarToSongs = similar.take(12)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── Recommended Playlists with Local Cache ──
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("recommended_playlists_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("playlists_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()
                
                if (cachedJson != null && (now - cacheTime < 12 * 60 * 60 * 1000L)) {
                    val type = object : com.google.gson.reflect.TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                    val list: List<com.vinmusic.innertube.AlbumItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            recommendedPlaylists = list
                            isLoadingPlaylists = false
                        }
                        return@launch
                    }
                }
                
                isLoadingPlaylists = true
                val localPlaylists = db.playlistDao().getAll()
                val liked = db.likedSongDao().getAll()
                val queries = mutableListOf<String>()
                
                if (localPlaylists.isNotEmpty()) {
                    localPlaylists.shuffled().take(2).forEach { pl ->
                        val plName = pl.name.trim()
                        val genericNames = listOf("playlist", "new playlist", "imported playlist", "favorites", "liked", "custom playlist")
                        if (plName.isNotEmpty() && !genericNames.any { plName.lowercase() == it }) {
                            queries.add("$plName playlist")
                        }
                    }
                }
                
                if (liked.isNotEmpty()) {
                    val topArtist = liked.groupBy { it.author }
                        .filter { it.key.isNotBlank() }
                        .maxByOrNull { it.value.size }?.key?.trim()
                    if (!topArtist.isNullOrEmpty()) {
                        queries.add("$topArtist playlist")
                    }
                }
                
                if (queries.size < 2) {
                    queries.add("hindi lofi chill playlist")
                    queries.add("punjabi hits playlist")
                    queries.add("viral english songs playlist")
                }
                
                val allResults = mutableListOf<com.vinmusic.innertube.AlbumItem>()
                coroutineScope {
                    val deferreds = queries.take(3).map { query ->
                        async(Dispatchers.IO) {
                            try {
                                val searchResult = com.vinmusic.innertube.InnerTube.searchAll(query)
                                searchResult.albums
                            } catch (e: Exception) {
                                emptyList<com.vinmusic.innertube.AlbumItem>()
                            }
                        }
                    }
                    allResults.addAll(deferreds.awaitAll().flatten())
                }
                
                val uniquePlaylists = allResults
                    .distinctBy { it.playlistId }
                    .filter { it.playlistId.startsWith("PL") || it.playlistId.startsWith("VL") }
                    .take(8)
                
                if (uniquePlaylists.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("playlists_json", com.google.gson.Gson().toJson(uniquePlaylists))
                        .putLong("cache_time", now)
                        .apply()
                }
                
                withContext(Dispatchers.Main) {
                    recommendedPlaylists = uniquePlaylists
                    isLoadingPlaylists = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load recommended playlists", e)
                withContext(Dispatchers.Main) {
                    isLoadingPlaylists = false
                }
            }
        }
    }

    LaunchedEffect(selectedRecommendedPlaylist) {
        val recommendedPl = selectedRecommendedPlaylist
        if (recommendedPl != null) {
            isLoadingPlaylistSongs = true
            recommendedPlaylistSongs = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val (_, playlistSongs) = com.vinmusic.innertube.InnerTube.getPlaylistSongs(recommendedPl.playlistId)
                    withContext(Dispatchers.Main) {
                        recommendedPlaylistSongs = playlistSongs
                        isLoadingPlaylistSongs = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingPlaylistSongs = false
                    }
                }
            }
        } else {
            recommendedPlaylistSongs = emptyList()
            isLoadingPlaylistSongs = false
        }
    }

    if (selectedArtist != null) {
        ArtistProfileScreen(
            artist      = selectedArtist!!,
            vm          = vm,
            onBack      = { selectedArtist = null },
            onSongClick = onSongClick,
            onAlbumClick = onAlbumClick
        )
        return
    }

    // ── Recommended Albums with Local Cache ──
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("recommended_albums_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("albums_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()
                
                if (cachedJson != null && (now - cacheTime < 24 * 60 * 60 * 1000L)) {
                    val type = object : com.google.gson.reflect.TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                    val list: List<com.vinmusic.innertube.AlbumItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            recommendedAlbums = list
                        }
                        return@launch
                    }
                }
                
                val albumsResult = com.vinmusic.innertube.InnerTube.searchAll("best hindi albums playlist 2025")
                val results = albumsResult.albums.take(6)
                if (results.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("albums_json", com.google.gson.Gson().toJson(results))
                        .putLong("cache_time", now)
                        .apply()
                }
                withContext(Dispatchers.Main) {
                    recommendedAlbums = results
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load albums: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        // Auto-refresh history database flow (Optimized: No network calls inside database collectors!)
        scope.launch(Dispatchers.IO) {
            db.historyDao().getRecentFlow().collect { history ->
                withContext(Dispatchers.Main) {
                    recentlyPlayed = history
                }
            }
        }

        // Suggested Artists with Local Cache (Resolves concurrently once and caches on disk)
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("suggested_artists_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("artists_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()
                
                if (cachedJson != null && (now - cacheTime < 6 * 60 * 60 * 1000L)) {
                    val type = object : com.google.gson.reflect.TypeToken<List<com.vinmusic.innertube.ArtistItem>>() {}.type
                    val list: List<com.vinmusic.innertube.ArtistItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            suggestedArtists = list
                        }
                        return@launch
                    }
                }
                
                val historyList = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
                val interactionSignals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
                
                // Extract all unique artists the user has already listened to
                val listenedArtists = (historyList.map { it.author.trim() } + interactionSignals.map { it.author.trim() })
                    .filter { it.isNotBlank() && it.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it) }
                    .distinct()

                val cleanListened = listenedArtists.map { normalizeArtistName(it) }.filter { it.isNotEmpty() }.toSet()
                val artistNames = ArrayList<String>()

                if (cleanListened.size < 5) {
                    // Cold-start/Variety Phase: Recommend a highly diverse list of fallback premium artists, excluding the ones they've already heard!
                    val fallbackList = listOf("Arijit Singh", "Sidhu Moose Wala", "Karan Aujla", "Diljit Dosanjh", "The Weeknd", "Drake", "Anuv Jain", "Travis Scott")
                    for (art in fallbackList) {
                        if (artistNames.size >= 8) break
                        val normFallback = normalizeArtistName(art)
                        if (!cleanListened.contains(normFallback)) {
                            artistNames.add(art)
                        }
                    }
                } else {
                    // Warm-start Discovery Phase: Suggest SIMILAR artists that they haven't listened to yet!
                    val topListened = listenedArtists.take(10)
                    for (artName in topListened) {
                        val normArt = normalizeArtistName(artName)
                        var similar: List<String>? = null
                        for ((key, value) in SIMILAR_ARTISTS_MAP) {
                            if (normalizeArtistName(key) == normArt) {
                                similar = value
                                break
                            }
                        }
                        if (similar != null) {
                            for (simArt in similar) {
                                if (artistNames.size >= 8) break
                                val normSim = normalizeArtistName(simArt)
                                if (!cleanListened.contains(normSim) && !artistNames.map { normalizeArtistName(it) }.contains(normSim)) {
                                    artistNames.add(simArt)
                                }
                            }
                        }
                    }
                }

                // Fill remaining spots with premium default artists they haven't listened to
                val fallbackList = listOf("Arijit Singh", "Sidhu Moose Wala", "Karan Aujla", "Diljit Dosanjh", "The Weeknd", "Drake", "Anuv Jain", "Travis Scott")
                for (art in fallbackList) {
                    if (artistNames.size >= 8) break
                    val normFallback = normalizeArtistName(art)
                    if (!cleanListened.contains(normFallback) && !artistNames.map { normalizeArtistName(it) }.contains(normFallback)) {
                        artistNames.add(art)
                    }
                }
                
                coroutineScope {
                    val deferreds = artistNames.map { name ->
                        async(Dispatchers.IO) {
                            try {
                                val searchRes = InnerTube.searchAll(name)
                                searchRes.artists.firstOrNull { artist ->
                                    artist.name.lowercase().contains(name.lowercase()) || name.lowercase().contains(artist.name.lowercase())
                                } ?: searchRes.artists.firstOrNull()
                            } catch (_: Exception) { null }
                        }
                    }
                    val resolved = deferreds.awaitAll().filterNotNull().distinctBy { it.channelId }.take(8)
                    if (resolved.isNotEmpty()) {
                        cachePrefs.edit()
                            .putString("artists_json", com.google.gson.Gson().toJson(resolved))
                            .putLong("cache_time", now)
                            .apply()
                            
                        withContext(Dispatchers.Main) {
                            suggestedArtists = resolved
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load suggested artists: ${e.message}")
            }
        }

        // Auto-refresh downloads flow
        scope.launch(Dispatchers.IO) {
            db.downloadDao().getAllFlow().collect { dls ->
                downloads = dls
            }
        }

        // Auto-refresh liked songs flow
        scope.launch(Dispatchers.IO) {
            db.likedSongDao().getAllFlow().collect { songs ->
                likedSongs = songs
            }
        }
    }

    LaunchedEffect(filter) {
        if (filter == "All" || filter == "For You") return@LaunchedEffect

        val moodChips = setOf("Happy", "Sad", "Energize", "Sleep", "Focus", "Workout", "Party")

        if (filter in moodChips) {
            // ── Deep Mood Sections: artist-specific songs for this mood
            isMoodLoading = true
            moodSections = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val moodKeyword = when (filter) {
                        "Happy"    -> "happy upbeat feel good"
                        "Sad"      -> "sad emotional heartbreak"
                        "Energize" -> "energetic pump up hype"
                        "Sleep"    -> "sleep calm soothing ambient"
                        "Focus"    -> "focus deep work concentration lofi"
                        "Workout" -> "workout gym motivation power"
                        "Party"   -> "party dance club hits"
                        else          -> filter.filter { it.isLetter() || it.isWhitespace() }.trim().lowercase()
                    }
                    val moodLabel = filter.filter { it.isLetter() || it.isWhitespace() }.trim()
                    val sections = mutableListOf<Pair<String, List<VideoItem>>>()

                    // 1. Generic top mood picks
                    try {
                        val genericResults = InnerTube.search("best $moodKeyword songs 2025").take(8)
                        if (genericResults.isNotEmpty()) sections.add("Top $moodLabel Picks" to genericResults)
                    } catch (_: Exception) {}

                    // 2. Artist-specific mood sections from user listening history
                    val topArtists = recentlyPlayed
                        .map { it.author.trim() }
                        .filter { it.isNotBlank() && it.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it) }
                        .groupBy { it }
                        .entries.sortedByDescending { it.value.size }
                        .map { it.key }
                        .distinct()
                        .take(5)

                    for (artistName in topArtists) {
                        try {
                            val artistResults = InnerTube.search("$artistName $moodKeyword songs").take(6)
                            if (artistResults.isNotEmpty()) {
                                sections.add("$artistName · $moodLabel" to artistResults)
                            }
                        } catch (_: Exception) {}
                    }

                    // 3. Mood playlist/mix
                    try {
                        val mixResults = InnerTube.search("$moodKeyword playlist mix nonstop").take(6)
                        if (mixResults.isNotEmpty()) sections.add("$moodLabel Mix" to mixResults)
                    } catch (_: Exception) {}

                    withContext(Dispatchers.Main) {
                        moodSections = sections
                        isMoodLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Mood sections failed: ${e.message}")
                    withContext(Dispatchers.Main) { isMoodLoading = false }
                }
            }
        } else if (filter == "Rap") {
            // Rap parent selected — don't load generic flat list, sub-categories handle it
            // Trigger sub-category loading
            isRapSubLoading = true
            rapSubSections = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val sections = loadRapSubSections(rapSubFilter, recentlyPlayed)
                    withContext(Dispatchers.Main) {
                        rapSubSections = sections
                        isRapSubLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Rap sub-category failed: ${e.message}")
                    withContext(Dispatchers.Main) { isRapSubLoading = false }
                }
            }
        } else {
            // Genre chip — standard flat list
            isCategoryLoading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val query = when (filter) {
                        "Bollywood" -> "bollywood hits 2025 official audio"
                        "Lo-fi"     -> "lofi hindi beats chill"
                        "Indie"     -> "indie pop songs chill"
                        "K-Pop"     -> "kpop hits music"
                        "90s Hits"  -> "90s bollywood classic hits"
                        else        -> "${filter.lowercase()} songs music 2025"
                    }
                    val results = InnerTube.search(query).take(15)
                    withContext(Dispatchers.Main) {
                        categorySongs = results
                        isCategoryLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Failed to load category songs: ${e.message}")
                    withContext(Dispatchers.Main) { isCategoryLoading = false }
                }
            }
        }
    }

    // ── Rap Sub-Category auto-reload when sub-filter changes ──
    LaunchedEffect(rapSubFilter) {
        if (filter != "Rap") return@LaunchedEffect
        isRapSubLoading = true
        rapSubSections = emptyList()
        scope.launch(Dispatchers.IO) {
            try {
                val sections = loadRapSubSections(rapSubFilter, recentlyPlayed)
                withContext(Dispatchers.Main) {
                    rapSubSections = sections
                    isRapSubLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Rap sub-filter changed failed: ${e.message}")
                withContext(Dispatchers.Main) { isRapSubLoading = false }
            }
        }
    }

    // ── Long Listens (45min+ extended mixes & albums)
    // ── Long Listens (45min+ extended mixes & albums) with Local Cache ──
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("long_listens_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("songs_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()
                
                if (cachedJson != null && (now - cacheTime < 24 * 60 * 60 * 1000L)) {
                    val type = object : com.google.gson.reflect.TypeToken<List<VideoItem>>() {}.type
                    val list: List<VideoItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            longListens = list
                            isLoadingLongListens = false
                        }
                        return@launch
                    }
                }
                
                isLoadingLongListens = true
                val results = mutableListOf<VideoItem>()
                val longQueries = listOf(
                    "1 hour hindi songs nonstop mix",
                    "2 hour bollywood hits playlist",
                    "long lofi beats study 3 hours",
                    "90 minutes workout music mix"
                )
                
                coroutineScope {
                    val deferreds = longQueries.map { q ->
                        async(Dispatchers.IO) {
                            try {
                                InnerTube.search(q).take(3)
                            } catch (_: Exception) { emptyList<VideoItem>() }
                        }
                    }
                    results.addAll(deferreds.awaitAll().flatten())
                }
                
                val distinctLong = results.distinctBy { it.videoId }.take(12)
                if (distinctLong.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("songs_json", com.google.gson.Gson().toJson(distinctLong))
                        .putLong("cache_time", now)
                        .apply()
                }
                
                withContext(Dispatchers.Main) {
                    longListens = distinctLong
                    isLoadingLongListens = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingLongListens = false
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { triggerRefresh() },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color.Transparent),
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {

            // ── Personalized Frosted Glass Top Header ──────────────────────────
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(VinColors.White10)
                        .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    editName = userName
                                    showProfileDialog = true
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            UserAvatar(
                                avatarIndex = avatarIndex,
                                size = 46.dp,
                                name = userName
                            )
                            Column {
                                Text(
                                    text = userName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Music Enthusiast",
                                    fontSize = 11.sp,
                                    color = VinColors.Secondary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    android.widget.Toast.makeText(ctx, "No new notifications", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Notifications,
                                    "Notifications",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(
                                onClick = onSettingsClick,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    "Settings",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!ytMusicConnected) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(VinColors.Accent.copy(alpha = 0.12f))
                            .border(1.dp, VinColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .clickable { onSettingsClick() }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = VinColors.AccentLight, modifier = Modifier.size(28.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "YouTube Music Connected Status",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                "Tap to connect YouTube Music for elite recommendations",
                                fontSize = 12.sp,
                                color = VinColors.Secondary
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = VinColors.AccentLight)
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ── Premium Actions: Tinder Discover ──
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp)
                        .graphicsLayer(shadowElevation = 8.dp.value, shape = RoundedCornerShape(20.dp), clip = false)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFEF4444).copy(alpha = 0.25f),
                                    Color(0xFF7F1D1D).copy(alpha = 0.35f)
                                )
                            )
                        )
                        .border(
                            BorderStroke(1.2.dp, Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFF7F1D1D)))),
                            RoundedCornerShape(20.dp)
                        )
                        .clickable { onDiscoverClick() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    "Discover Mix Deck",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    "Tinder-style swipe to unlock new music DNA",
                                    fontSize = 11.sp,
                                    color = VinColors.Secondary,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }


            // ── Screen Title & Sleek Search Glass Capsule ──────────────────────────
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "VIN",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "MUSIC",
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black,
                                color = VinColors.AccentLight,
                                letterSpacing = (-0.5).sp
                            )
                        }
                        Text(
                            text = "Your Acoustic Sanctuary",
                            fontSize = 11.sp,
                            color = VinColors.Secondary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Frosted search bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(18.dp))
                            .clickable { onSearchClick() }
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                null,
                                tint = VinColors.AccentLight,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Search songs, artists, or lofi mood mixes...",
                                fontSize = 14.sp,
                                color = VinColors.Secondary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ── "Vibe of the Day" Lyrics Capsule ──
            item {
                VibeOfTheDayCapsule(ctx, db)
                Spacer(Modifier.height(14.dp))
            }

            // ── Premium Capsule Filter Chips ───────────────────────────────────────
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(CATEGORIES) { cat ->
                        val active = cat == filter
                        val interactionSource = remember { MutableInteractionSource() }
                        val isPressed by interactionSource.collectIsPressedAsState()
                        val scale by animateFloatAsState(
                            targetValue = if (isPressed) 0.94f else 1f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "chip_scale"
                        )

                        Box(
                            modifier = Modifier
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    shadowElevation = if (active) 6.dp.value else 0f,
                                    shape = RoundedCornerShape(22.dp),
                                    clip = false
                                )
                                .clip(RoundedCornerShape(22.dp))
                                .background(
                                    if (active) {
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFEF4444), Color(0xFF991B1B))
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.02f))
                                        )
                                    }
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (active) Color.Transparent else Color.White.copy(alpha = 0.08f)
                                    ),
                                    RoundedCornerShape(22.dp)
                                )
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { filter = cat }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 13.sp,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
                                fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }
                }
            }


        // ── Dynamic filtered modules ──────────────────────────────────────────
        when (filter) {
            "All" -> {
                // 1. Recently Played (Horizontal Cards, Small Covers)
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                        SectionTitle("Recently Played")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            val historySongs = recentlyPlayed.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                            items(historySongs.take(8)) { song ->
                                SmallRecentlyPlayedCard(song = song) {
                                    onSongClick(song, historySongs)
                                }
                            }
                        }
                    }
                }

                // 1.1. Your YT Music Playlists
                if (ytLibraryPlaylists.isNotEmpty()) {
                    item {
                        SectionTitle("Your YT Music Playlists")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            items(ytLibraryPlaylists) { pl ->
                                RecommendedPlaylistCard(
                                    playlist = pl,
                                    onClick = { selectedRecommendedPlaylist = pl }
                                )
                            }
                        }
                    }
                }

                // Recommended playlists shelf
                if (isLoadingPlaylists || recommendedPlaylists.isNotEmpty() || recommendedPlaylistsLoaded) {
                    item {
                        SectionTitle("Recommended playlists")
                        Spacer(Modifier.height(10.dp))
                        when {
                            isLoadingPlaylists -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                                }
                            }
                            recommendedPlaylists.isNotEmpty() -> {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 24.dp)
                                ) {
                                    items(recommendedPlaylists) { pl ->
                                        RecommendedPlaylistCard(
                                            playlist = pl,
                                            onClick = { selectedRecommendedPlaylist = pl }
                                        )
                                    }
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No recommended playlists available right now. Try refreshing to see new suggestions.",
                                        color = VinColors.Secondary,
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // 1.5. Quick Picks
                if (isLoadingQuickPicks && quickPicks.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (quickPicks.isNotEmpty()) {
                    item {
                        SectionTitle("Quick Picks")
                        Spacer(Modifier.height(10.dp))
                        
                        val columns = quickPicks.chunked(3)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            items(columns) { columnSongs ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.width(280.dp)
                                ) {
                                    columnSongs.forEach { song ->
                                        QuickPickRow(song = song) {
                                            onSongClick(song, quickPicks)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 1.6. Artists you may like (right after Quick Picks)
                if (suggestedArtists.isNotEmpty()) {
                    item {
                        SectionTitle("Artists you may like")
                        Spacer(Modifier.height(10.dp))
                        val topRow = suggestedArtists.take(8)
                        val bottomRow = suggestedArtists.drop(8).take(8)
                        val colCount = maxOf(topRow.size, bottomRow.size)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            items(colCount) { index ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (index < topRow.size) {
                                        ArtistCircleCard(artist = topRow[index]) {
                                            selectedArtist = topRow[index]
                                        }
                                    }
                                    if (index < bottomRow.size) {
                                        ArtistCircleCard(artist = bottomRow[index]) {
                                            selectedArtist = bottomRow[index]
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 1.6. Recommended Radio
                if (isLoadingRecommendedRadio && recommendedRadio.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (recommendedRadio.isNotEmpty() && radioSeedSong != null) {
                    item {
                        SectionTitle("Recommended Radio")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Endless queue based on \"${radioSeedSong?.title}\"",
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            items(recommendedRadio) { song ->
                                RecommendedRadioCard(song = song) {
                                    onSongClick(song, recommendedRadio)
                                }
                            }
                        }
                    }
                }
                // Custom Spotify-Style Mixes divided into premium distinct shelves (below Recommended Radio)
                if (spotifyMixes.isNotEmpty()) {
                    val rewindMixes = spotifyMixes.filter { it.id == "repeat_rewind" }

                    // 3. Repeat Rewind
                    if (rewindMixes.isNotEmpty()) {
                        item {
                            SectionTitle("Repeat Rewind")
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            ) {
                                items(rewindMixes) { mix ->
                                    SpotifyMixCard(
                                        mix = mix,
                                        onClick = { selectedSpotifyMix = mix }
                                    )
                                }
                            }
                        }
                    }
                }
                // Similar To currently playing song
                if (vm.currentSong != null && similarToSongs.isNotEmpty()) {
                    item {
                        val nowPlaying = vm.currentSong!!
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            AsyncImage(
                                model = nowPlaying.thumbnail,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Similar To", fontSize = 11.sp, color = VinColors.Secondary)
                                Text(
                                    nowPlaying.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = VinColors.Primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            items(similarToSongs) { s ->
                                RecommendedRadioCard(song = s) { onSongClick(s, similarToSongs) }
                            }
                        }
                    }
                }

                // 2. Dynamic Recommendations Sections (Personalized Music Engine)
                if (isRecommendationsLoading && recommendationSections.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                            Text("Tuning your recommendations...", color = VinColors.Secondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    recommendationSections.forEach { (title, recList) ->
                        if (recList.isNotEmpty()) {
                            item {
                                SectionTitle(title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    val videoItems = recList.map { it.videoItem }
                                    items(recList) { rec ->
                                        RecommendedTrackCard(song = rec.videoItem, reason = rec.reason) {
                                            onSongClick(rec.videoItem, videoItems)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }



                // (Artists you may like is now shown right after Quick Picks above)

                // 4. Your Downloads (Offline tracks)
                if (downloads.isNotEmpty()) {
                    item {
                        SectionTitle("Your Downloads")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val downloadedSongs = downloads.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                            items(downloadedSongs.take(8)) { song ->
                                TrackCard(song = song) {
                                    onSongClick(song, downloadedSongs)
                                }
                            }
                        }
                    }
                }

                // 5. Long Listens (Extended mixes & albums 45min+)
                if (longListens.isNotEmpty()) {
                    item {
                        SectionTitle("Long Listens")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Extended mixes & albums over 45 minutes",
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                        )
                    }
                    items(longListens.take(8)) { song ->
                        SongListItem(
                            song = song,
                            isPlaying = vm.currentSong?.videoId == song.videoId,
                            onClick = { onSongClick(song, longListens) },
                            onMore = { onSongMore(song) }
                        )
                    }
                }
            }
            "For You" -> {
                // Your personal YT Music Playlists
                if (ytLibraryPlaylists.isNotEmpty()) {
                    item {
                        SectionTitle("Your YT Music Playlists")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 24.dp)
                        ) {
                            items(ytLibraryPlaylists) { pl ->
                                RecommendedPlaylistCard(
                                    playlist = pl,
                                    onClick = { selectedRecommendedPlaylist = pl }
                                )
                            }
                        }
                    }
                } else if (isLoadingYtPlaylists) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                            Text("Loading your YouTube playlists...", color = VinColors.Secondary, fontSize = 12.sp)
                        }
                    }
                }

                // 0. YouTube Music official home (Metrolist FEmusic_home)
                if (isLoadingYtHome && ytMusicSections.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                            Text("Loading YouTube Music picks...", color = VinColors.Secondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    ytMusicSections.forEach { section ->
                        if (section.songs.isNotEmpty()) {
                            item {
                                SectionTitle(section.title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    items(section.songs) { song ->
                                        TrackCard(song = song) {
                                            onSongClick(song, section.songs)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // 1. Quick Picks
                if (isLoadingQuickPicks && quickPicks.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (quickPicks.isNotEmpty()) {
                    item {
                        SectionTitle("Quick Picks")
                        Spacer(Modifier.height(10.dp))
                        
                        val columns = quickPicks.chunked(3)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            items(columns) { columnSongs ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.width(280.dp)
                                ) {
                                    columnSongs.forEach { song ->
                                        QuickPickRow(song = song) {
                                            onSongClick(song, quickPicks)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Artists you may like (right after Quick Picks)
                if (suggestedArtists.isNotEmpty()) {
                    item {
                        SectionTitle("Artists you may like")
                        Spacer(Modifier.height(10.dp))
                        val topRow = suggestedArtists.take(8)
                        val bottomRow = suggestedArtists.drop(8).take(8)
                        val colCount = maxOf(topRow.size, bottomRow.size)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(bottom = 24.dp)
                        ) {
                            items(colCount) { index ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (index < topRow.size) {
                                        ArtistCircleCard(artist = topRow[index]) {
                                            selectedArtist = topRow[index]
                                        }
                                    }
                                    if (index < bottomRow.size) {
                                        ArtistCircleCard(artist = bottomRow[index]) {
                                            selectedArtist = bottomRow[index]
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Recommended Radio
                if (isLoadingRecommendedRadio && recommendedRadio.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (recommendedRadio.isNotEmpty() && radioSeedSong != null) {
                    item {
                        SectionTitle("Recommended Radio")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Endless queue based on \"${radioSeedSong?.title}\"",
                            fontSize = 12.sp,
                            color = VinColors.Secondary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            items(recommendedRadio) { song ->
                                RecommendedRadioCard(song = song) {
                                    onSongClick(song, recommendedRadio)
                                }
                            }
                        }
                    }
                }

                // Custom Spotify-Style Mixes divided into premium distinct shelves
                if (spotifyMixes.isNotEmpty()) {
                    val rewindMixes = spotifyMixes.filter { it.id == "repeat_rewind" }

                    // 3. Repeat Rewind
                    if (rewindMixes.isNotEmpty()) {
                        item {
                            SectionTitle("Repeat Rewind")
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            ) {
                                items(rewindMixes) { mix ->
                                    SpotifyMixCard(
                                        mix = mix,
                                        onClick = { selectedSpotifyMix = mix }
                                    )
                                }
                            }
                        }
                    }
                }

                // (Artists you may like shown above after Quick Picks)

                // 4. Personalized Recommendations Sections
                if (isRecommendationsLoading && recommendationSections.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                            Text("Tuning your recommendations...", color = VinColors.Secondary, fontSize = 12.sp)
                        }
                    }
                } else {
                    recommendationSections.forEach { (title, recList) ->
                        if (recList.isNotEmpty()) {
                            item {
                                SectionTitle(title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    val videoItems = recList.map { it.videoItem }
                                    items(recList) { rec ->
                                        RecommendedTrackCard(song = rec.videoItem, reason = rec.reason) {
                                            onSongClick(rec.videoItem, videoItems)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "Rap" -> {
                // ── Sub-Category Chips Row ──
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "🎤 Rap Sub-Genres",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            items(RAP_SUB_CATEGORIES) { sub ->
                                val active = sub.name == rapSubFilter
                                val interactionSource = remember { MutableInteractionSource() }
                                val isPressed by interactionSource.collectIsPressedAsState()
                                val scale by animateFloatAsState(
                                    targetValue = if (isPressed) 0.93f else 1f,
                                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                    label = "rap_chip_scale"
                                )

                                Box(
                                    modifier = Modifier
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            shadowElevation = if (active) 8.dp.value else 0f,
                                            shape = RoundedCornerShape(20.dp),
                                            clip = false
                                        )
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            if (active) {
                                                Brush.horizontalGradient(
                                                    listOf(Color(0xFFFF6B35), Color(0xFFEF4444), Color(0xFF991B1B))
                                                )
                                            } else {
                                                Brush.verticalGradient(
                                                    listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))
                                                )
                                            }
                                        )
                                        .border(
                                            BorderStroke(
                                                if (active) 0.dp else 0.8.dp,
                                                if (active) Color.Transparent else Color.White.copy(alpha = 0.12f)
                                            ),
                                            RoundedCornerShape(20.dp)
                                        )
                                        .clickable(
                                            interactionSource = interactionSource,
                                            indication = null
                                        ) { rapSubFilter = sub.name }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Text(
                                            text = sub.icon,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = sub.name,
                                            fontSize = 12.sp,
                                            color = if (active) Color.White else Color.White.copy(alpha = 0.75f),
                                            fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Sub-Category Loading State ──
                if (isRapSubLoading && rapSubSections.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFFFF6B35), modifier = Modifier.size(36.dp))
                                Text(
                                    "Loading $rapSubFilter tracks...",
                                    color = VinColors.Secondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    // ── Sub-Category Sections ──
                    rapSubSections.forEach { (title, songs) ->
                        if (songs.isNotEmpty()) {
                            item {
                                SectionTitle(title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    items(songs) { song ->
                                        TrackCard(song = song) {
                                            onSongClick(song, songs)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (rapSubSections.isEmpty() && !isRapSubLoading) {
                        item { EmptyScreenState("No ${rapSubFilter} songs found. Try another sub-genre or check your internet.") }
                    }
                }
            }
            else -> {
                val moodChips = setOf("Happy", "Sad", "Energize", "Sleep", "Focus", "Workout", "Party")
                if (filter in moodChips) {
                    // ── Deep Mood Sections: artist-specific + generic mood songs
                    if (isMoodLoading && moodSections.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(36.dp))
                                    Text(
                                        "Curating your ${filter.filter { it.isLetter() || it.isWhitespace() }.trim()} vibe...",
                                        color = VinColors.Secondary,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    } else {
                        moodSections.forEach { (title, songs) ->
                            if (songs.isNotEmpty()) {
                                item {
                                    SectionTitle(title)
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    ) {
                                        items(songs) { song ->
                                            TrackCard(song = song) {
                                                onSongClick(song, songs)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (moodSections.isEmpty() && !isMoodLoading) {
                            item { EmptyScreenState("No songs found for this mood. Try again later.") }
                        }
                    }
                } else {
                    // Genre chip — flat list
                    if (isCategoryLoading) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(250.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = VinColors.Accent)
                            }
                        }
                    } else if (categorySongs.isNotEmpty()) {
                        item {
                            SectionTitle("$filter Hits")
                            Spacer(Modifier.height(12.dp))
                        }
                        items(categorySongs) { song ->
                            SongListItem(
                                song = song,
                                isPlaying = vm.currentSong?.videoId == song.videoId,
                                onClick = { onSongClick(song, categorySongs) },
                                onMore = { onSongMore(song) }
                            )
                        }
                    } else {
                        item {
                            EmptyScreenState("No songs found. Please check your internet connection.")
                        }
                    }
                }
            }
        }
    }
    } // end PullToRefreshBox
    } // end Box background aura

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
                            focusedBorderColor = VinColors.Accent, unfocusedBorderColor = VinColors.GlassBorder,
                            focusedTextColor = VinColors.Primary, unfocusedTextColor = VinColors.Primary,
                            focusedContainerColor = VinColors.White10, unfocusedContainerColor = VinColors.White10
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

    if (selectedSpotifyMix != null) {
        val mix = selectedSpotifyMix!!
        val startColor = runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFF3B0764) }
        val endColor = runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1B4B) }

        ModalBottomSheet(
            onDismissRequest = { selectedSpotifyMix = null },
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mix.title,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mix.description,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (mix.songs.isNotEmpty()) {
                                val tracks = mix.songs.map { it.videoItem }
                                onSongClick(tracks[0], tracks)
                                selectedSpotifyMix = null
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Play Mix", fontWeight = FontWeight.Bold)
                    }
                    
                    Button(
                        onClick = {
                            if (mix.songs.isNotEmpty()) {
                                scope.launch(Dispatchers.IO) {
                                    val playlistDbId = db.playlistDao().insertPlaylist(com.vinmusic.data.db.PlaylistEntity(name = mix.title))
                                    mix.songs.forEachIndexed { index, song ->
                                        db.playlistDao().insertSong(
                                            com.vinmusic.data.db.PlaylistSongEntity(
                                                playlistId = playlistDbId,
                                                videoId = song.videoItem.videoId,
                                                title = song.videoItem.title,
                                                author = song.videoItem.author,
                                                durationText = song.videoItem.durationText,
                                                position = index
                                            )
                                        )
                                    }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "Imported '${mix.title}' successfully!", android.widget.Toast.LENGTH_LONG).show()
                                        selectedSpotifyMix = null
                                    }
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, VinColors.GlassBorder)
                    ) {
                        Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp), tint = VinColors.Primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Import Mix", fontWeight = FontWeight.Bold, color = VinColors.Primary)
                    }
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
                                            selectedSpotifyMix = null
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

    if (selectedRecommendedPlaylist != null) {
        val recommendedPl = selectedRecommendedPlaylist!!
        ModalBottomSheet(
            onDismissRequest = { selectedRecommendedPlaylist = null },
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
                // Playlist Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.White10)
                    ) {
                        AsyncImage(
                            model = recommendedPl.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recommendedPl.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (recommendedPl.author.isNotBlank()) "Created by ${recommendedPl.author}" else "YouTube Playlist",
                            fontSize = 13.sp,
                            color = VinColors.Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${recommendedPlaylistSongs.size} tracks total",
                            fontSize = 12.sp,
                            color = VinColors.AccentLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Frosted Gradient Import Button
                Button(
                    onClick = {
                        if (recommendedPlaylistSongs.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                val playlistDbId = db.playlistDao().insertPlaylist(com.vinmusic.data.db.PlaylistEntity(name = recommendedPl.title))
                                recommendedPlaylistSongs.forEachIndexed { index, song ->
                                    db.playlistDao().insertSong(
                                        com.vinmusic.data.db.PlaylistSongEntity(
                                            playlistId = playlistDbId,
                                            videoId = song.videoId,
                                            title = song.title,
                                            author = song.author,
                                            durationText = song.durationText,
                                            position = index
                                        )
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "Imported '${recommendedPl.title}' successfully!", android.widget.Toast.LENGTH_LONG).show()
                                    selectedRecommendedPlaylist = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                    enabled = !isLoadingPlaylistSongs && recommendedPlaylistSongs.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import to Offline Library", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                
                Spacer(Modifier.height(16.dp))
                
                HorizontalDivider(color = VinColors.GlassBorder.copy(alpha = 0.3f))
                
                Spacer(Modifier.height(12.dp))
                
                // Scrollable preview tracks
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                ) {
                    if (isLoadingPlaylistSongs) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                        }
                    } else if (recommendedPlaylistSongs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No tracks found or loading failed.", color = VinColors.Secondary, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(recommendedPlaylistSongs) { index, song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(VinColors.White10)
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) {
                                        AsyncImage(
                                            model = "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg",
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

/**
 * Helper function to load rap sub-section data.
 * Handles both the initial rap sub-section loading and updates when the sub-filter changes.
 */
private suspend fun loadRapSubSections(
    rapSubFilter: String,
    recentlyPlayed: List<com.vinmusic.data.db.HistoryEntry>
): List<Pair<String, List<VideoItem>>> {
    val sections = mutableListOf<Pair<String, List<VideoItem>>>()
    
    val sub = RAP_SUB_CATEGORIES.firstOrNull { it.name == rapSubFilter } ?: RAP_SUB_CATEGORIES[0]

    coroutineScope {
        val deferreds = sub.queries.map { q ->
            async(Dispatchers.IO) {
                try {
                    InnerTube.search(q).take(10)
                } catch (_: Exception) { emptyList<VideoItem>() }
            }
        }
        val results = deferreds.awaitAll()
        // First query -> "Top [SubGenre] Picks"
        if (results.isNotEmpty() && results[0].isNotEmpty()) {
            sections.add("Top ${sub.name} Picks" to results[0])
        }
        // Second query -> "More [SubGenre]"
        if (results.size > 1 && results[1].isNotEmpty()) {
            sections.add("More ${sub.name}" to results[1])
        }
    }

    // Also load artist-specific rap from history
    val topArtists = recentlyPlayed
        .map { it.author.trim() }
        .filter { it.isNotBlank() && it.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it) }
        .groupBy { it }
        .entries.sortedByDescending { it.value.size }
        .map { it.key }
        .distinct()
        .take(3)

    for (artistName in topArtists) {
        try {
            val keyword = sub.queries.firstOrNull()?.split(" ")?.take(2)?.joinToString(" ") ?: "rap"
            val artistResults = InnerTube.search("$artistName $keyword").take(6)
            if (artistResults.isNotEmpty()) {
                sections.add("$artistName · ${sub.name}" to artistResults)
            }
        } catch (_: Exception) {}
    }

    return sections
}

// ── HomeScreen Sub-components ──────────────────────────────────────────────────

@Composable
private fun SectionTitle(text: String) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(5.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(VinColors.Accent))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
    }
}

@Composable
fun SmallRecentlyPlayedCard(song: VideoItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "recent_card_press"
    )

    Row(
        modifier = Modifier
            .width(210.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(18.dp))
            .background(VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = song.thumbnail, contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(song.author, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp, color = VinColors.Secondary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun QuickPlaylistCard(
    pl: QuickPlaylist,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(
                colors = listOf(pl.gradStart, pl.gradEnd)
            ))
            .clickable {
                scope.launch(Dispatchers.IO) {
                    val results = InnerTube.search(pl.query)
                    if (results.isNotEmpty()) {
                        scope.launch(Dispatchers.Main) { onSongClick(results[0], results) }
                    }
                }
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(pl.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(
                pl.name, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = Color.White, maxLines = 2, lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun EmptyScreenState(message: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Info, null, tint = VinColors.White20, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = VinColors.Secondary, fontSize = 13.sp)
        }
    }
}



private fun greeting(): String {
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        hour < 21 -> "Good evening"
        else      -> "Good night"
    }
}

@Composable
fun RecommendedTrackCard(song: com.vinmusic.innertube.VideoItem, reason: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "rec_card_scale"
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
        Box(modifier = Modifier.size(144.dp).clip(RoundedCornerShape(14.dp))) {
            AsyncImage(
                model = song.thumbnail, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = song.author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                color = VinColors.Secondary
            )
        }
    }
}

@Composable
fun ArtistCircleCard(
    artist: com.vinmusic.innertube.ArtistItem,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "artist_scale"
    )

    Column(
        modifier = Modifier
            .width(140.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(130.dp)
                .background(
                    brush = Brush.linearGradient(listOf(VinColors.Accent, Color(0xFF7C3AED))),
                    shape = CircleShape
                )
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(VinColors.Surface2)
            ) {
                AsyncImage(
                    model = artist.thumbnail,
                    contentDescription = artist.name,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Text(
            text = artist.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            color = VinColors.Primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
        Text(
            text = if (artist.subscriberCount.isNotEmpty()) artist.subscriberCount else "Artist",
            fontSize = 12.sp,
            color = VinColors.Secondary,
            fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
fun SpotifyMixCard(
    mix: com.vinmusic.recommendation.SpotifyMix,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy),
        label = "mix_card_scale"
    )

    val startColor = remember(mix.gradientStartHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFF3B0764) }
    }
    val endColor = remember(mix.gradientEndHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1B4B) }
    }

    Column(
        modifier = Modifier
            .width(148.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
        ) {
            val coverUrl = mix.songs.firstOrNull()?.videoItem?.thumbnail
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))
                            )
                        )
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            // A premium glassmorphic tag indicating "MIX"
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "MIX",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = mix.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = mix.description,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.55f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun QuickPickRow(song: VideoItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "qp_row_scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPressed) Color.White.copy(alpha = 0.06f) else VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.author,
                fontSize = 13.sp,
                color = VinColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium
            )
        }
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "Options",
            tint = VinColors.Secondary,
            modifier = Modifier.size(20.dp).padding(end = 4.dp)
        )
    }
}

@Composable
fun RecommendedRadioCard(song: VideoItem, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        label = "radio_card_scale"
    )

    Column(
        modifier = Modifier
            .width(140.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(20.dp))
            .background(VinColors.White10)
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(124.dp)
                .align(Alignment.CenterHorizontally)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.3f))
            )
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(2.dp, VinColors.GlassBorder, CircleShape),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(VinColors.BgColor)
                    .border(1.5.dp, VinColors.GlassBorder, CircleShape)
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(VinColors.Accent)
                )
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = song.author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = VinColors.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ── Vibe of the Day Lyrics Capsule ──────────────────────────────────────────────

private val AESTHETIC_LYRICS_QUOTES = listOf<Pair<String, String>>(
    Pair("Kho gaye hum kahan, rangon ke is jahaan mein...", "Kho Gaye Hum Kahan • Prateek Kuhad"),
    Pair("Teri baaton mein hai jo nasha, dil mera behak sa gaya...", "Husn • Anuv Jain"),
    Pair("Tum jo paas ho, toh har lamha haseen sa lagta hai...", "Baarishein • Anuv Jain"),
    Pair("Kuch toh hai jo hum hai keh nahi paaye, kuch toh hai jo tum ho samajh rahe...", "Tu Jo Mila • Pritam"),
    Pair("Dil se dil ki baatein, suno na tum humari...", "Kasoor • Prateek Kuhad"),
    Pair("Kaise mujhe tum mil gayi, kismat pe yaqeen ban gaya...", "Kaise Mujhe • A.R. Rahman"),
    Pair("Hold on to the memories, they will hold on to you...", "New Year's Day • Taylor Swift"),
    Pair("Main tenu samjhawan ki, na tere bina lagda jee...", "Samjhawan • Arijit Singh"),
    Pair("Jeene ke liye socha hi nahi, dard sambhalne honge...", "Tujhse Naraz Nahi Zindagi • Gulzar"),
    Pair("Main jahaan rahoon, main kahin bhi hoon, teri yaad sath hai...", "Namastey London • Himesh Reshammiya"),
    Pair("Ek pyaar ka nagma hai, maujon ki rawaani hai...", "Ek Pyaar Ka Nagma • Laxmikant-Pyarelal"),
    Pair("Aise kyun hai yeh jahaan, hum jo mile hain yahan...", "Aise Kyun • Anurag Saikia")
)

@Composable
fun VibeOfTheDayCapsule(context: Context, db: com.vinmusic.data.db.VinDatabase) {
    var quoteText by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var quoteSource by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val cachedLyrics = try { db.cachedLyricsDao().getRandomLyrics() } catch (_: Exception) { emptyList<com.vinmusic.data.db.CachedLyricsEntity>() }
            val customQuotes = ArrayList<Pair<String, String>>()

            for (ly in cachedLyrics) {
                if (ly.content.isNotEmpty()) {
                    if (ly.lyricsType == "plain" && ly.content.length > 30) {
                        val paragraphs = ly.content.split("\n\n", "\n").filter { p -> p.trim().length in 30..80 }
                        if (paragraphs.isNotEmpty()) {
                            val songMeta = db.songCacheMetaDao().topPlayed(500).find { meta -> meta.videoId == ly.videoId }
                            val trackTitle = songMeta?.let { "${it.title} • ${it.author}" } ?: "Your Library"
                            customQuotes.add(Pair(paragraphs.random().trim(), trackTitle))
                        }
                    } else if (ly.lyricsType == "synced") {
                        try {
                            val lines = com.google.gson.Gson().fromJson(ly.content, Array<com.vinmusic.lyrics.LyricsLine>::class.java).toList()
                            val cleanLines = lines.map { line -> line.text.trim() }.filter { text -> text.length in 25..75 }
                            if (cleanLines.isNotEmpty()) {
                                val songMeta = db.songCacheMetaDao().topPlayed(500).find { meta -> meta.videoId == ly.videoId }
                                val trackTitle = songMeta?.let { "${it.title} • ${it.author}" } ?: "Your Library"
                                customQuotes.add(Pair(cleanLines.random(), trackTitle))
                            }
                        } catch (_: Exception) {}
                    }
                }
            }

            val finalPool = if (customQuotes.isNotEmpty()) {
                customQuotes + AESTHETIC_LYRICS_QUOTES
            } else {
                AESTHETIC_LYRICS_QUOTES
            }

            val chosen = finalPool.random()
            withContext(Dispatchers.Main) {
                quoteText = chosen.first
                quoteSource = chosen.second
            }
        }
    }

    if (quoteText.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.09f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                )
                .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            // Suspended translucent quotation icon in backdrop for elite aesthetics
            Icon(
                imageVector = Icons.Default.FormatQuote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.06f),
                modifier = Modifier
                    .size(96.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 10.dp, y = 20.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(VinColors.Accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatQuote,
                            contentDescription = null,
                            tint = VinColors.Accent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = "VIBE OF THE DAY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = VinColors.AccentLight,
                        letterSpacing = 1.5.sp
                    )
                }

                Text(
                    text = "\"$quoteText\"",
                    fontSize = 16.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    lineHeight = 24.sp
                )

                Text(
                    text = quoteSource,
                    fontSize = 11.sp,
                    color = VinColors.Secondary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
