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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.vinmusic.ui.theme.Vin
import com.vinmusic.ui.theme.glassCard
import com.vinmusic.ui.theme.floatingShadow
import com.vinmusic.ui.theme.shimmerEffect
import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.interaction.*
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val CATEGORIES = listOf("All", "For You", "Happy", "Sad", "Energize", "Sleep", "Focus", "Workout", "Party", "Bollywood", "Lo-fi", "Rap", "Indie", "K-Pop", "90s Hits", "Long Listens")
private val HOME_CURRENT_YEAR: Int get() = java.time.LocalDate.now().year

private data class DedupedHomeRecommendations(
    val ytMusicSections: List<com.vinmusic.innertube.YTMusicHomeSection>,
    val quickPicks: List<VideoItem>,
    val radio: List<VideoItem>,
    val sections: List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>,
)

/**
 * The Home tab receives recommendations from several independent engines. Give
 * each song one home on the page so Quick Picks, YT Music, Radio, and local
 * shelves do not echo the same track.
 */
private fun dedupeHomeRecommendations(
    ytMusicSections: List<com.vinmusic.innertube.YTMusicHomeSection>,
    quickPicks: List<VideoItem>,
    radio: List<VideoItem>,
    sections: List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>,
): DedupedHomeRecommendations {
    val seen = HashSet<String>()
    val seenVideoIds = HashSet<String>()
    fun key(song: VideoItem): String {
        val title = song.title.lowercase().replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        val artist = song.author.lowercase().replace(Regex("""\s+"""), " ").trim()
        return "$title|$artist"
    }
    fun accept(song: VideoItem): Boolean {
        val videoId = song.videoId.trim()
        // A provider may attach slightly different title/artist metadata to the
        // same video ID. Treat that as one track as well; semantic de-duping
        // alone cannot prevent duplicate Compose keys in that case.
        if (videoId.isNotEmpty() && videoId in seenVideoIds) return false
        if (!seen.add(key(song))) return false
        if (videoId.isNotEmpty()) seenVideoIds += videoId
        return true
    }

    val filteredYt = ytMusicSections.mapNotNull { shelf ->
        shelf.copy(songs = shelf.songs.filter(::accept)).takeIf { it.songs.isNotEmpty() }
    }
    val filteredQuickPicks = quickPicks.filter(::accept)
    val filteredRadio = radio.filter(::accept)
    val filteredSections = sections.mapNotNull { (title, songs) ->
        (title to songs.filter { accept(it.videoItem) }).takeIf { it.second.isNotEmpty() }
    }
    return DedupedHomeRecommendations(filteredYt, filteredQuickPicks, filteredRadio, filteredSections)
}

data class RapSubCategory(
    val name: String,
    val queries: List<String>  // multiple queries for richer results
)

private val RAP_SUB_CATEGORIES = listOf(
    RapSubCategory("All Rap",        listOf("best rap songs $HOME_CURRENT_YEAR", "top rap hits")),
    RapSubCategory("Lyrical",        listOf("lyrical rap deep bars", "lyrical hip hop conscious rap")),
    RapSubCategory("Storytelling",   listOf("storytelling rap songs", "narrative rap best songs")),
    RapSubCategory("Vibe",           listOf("chill vibe rap songs", "vibe rap relaxed flow")),
    RapSubCategory("Sad",            listOf("sad rap songs emotional", "sad rap heartbreak")),
    RapSubCategory("Happy",          listOf("happy upbeat rap songs", "feel good rap")),
    RapSubCategory("Aggressive",     listOf("aggressive rap hard bars", "aggressive trap rap")),
    RapSubCategory("Desi Hip-Hop",   listOf("desi hip hop indian rap", "indian rap songs $HOME_CURRENT_YEAR")),
    RapSubCategory("Old School",     listOf("old school hip hop classic", "90s rap golden era")),
    RapSubCategory("Trap",           listOf("trap music best songs", "trap rap hard beats")),
    RapSubCategory("Drill",          listOf("drill rap songs", "uk drill rap")),
    RapSubCategory("Freestyle",      listOf("freestyle rap best", "freestyle rap cypher"))
)

// SIMILAR_ARTISTS_MAP is now fetched dynamically from Last.fm
// Fallback map used only when Last.fm is unavailable
private val SIMILAR_ARTISTS_FALLBACK = mapOf(
    "j. cole" to listOf("Kendrick Lamar", "Drake", "JID", "Cordae"),
    "kendrick lamar" to listOf("J. Cole", "Drake", "Travis Scott", "21 Savage"),
    "drake" to listOf("J. Cole", "Kendrick Lamar", "The Weeknd", "Future"),
    "arijit singh" to listOf("Atif Aslam", "Jubin Nautiyal", "Shreya Ghoshal"),
    "sidhu moose wala" to listOf("Karan Aujla", "Diljit Dosanjh", "Shubh"),
    "the weeknd" to listOf("Post Malone", "Khalid", "Frank Ocean", "SZA"),
    "eminem" to listOf("Dr. Dre", "50 Cent", "Snoop Dogg", "J. Cole"),
    "badshah" to listOf("Raftaar", "Yo Yo Honey Singh", "King", "MC Stan")
)

fun normalizeArtistName(name: String): String {
    var clean = name.lowercase(java.util.Locale.ROOT).trim()
    clean = clean.replace(Regex("- topic$"), "").trim()
    clean = clean.replace(Regex("\\bvevo\\b"), "").trim()
    clean = clean.replace(Regex("[^a-z0-9\\s]"), "")
    clean = clean.replace(Regex("\\s+"), " ").trim()
    return clean
}

private suspend fun fetchSimilarArtistsLocally(artist: String): List<String> {
    val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
    if (apiKey.isBlank()) return emptyList()
    val urlString = "https://ws.audioscrobbler.com/2.0/?method=artist.getsimilar" +
            "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
            "&api_key=$apiKey" +
            "&format=json"
    
    return try {
        val url = java.net.URL(urlString)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        
        if (connection.responseCode == 200) {
            val responseText = connection.inputStream.bufferedReader().use { it.readText() }
            val responseJson = com.google.gson.JsonParser.parseString(responseText).asJsonObject
            val similarartists = responseJson.getAsJsonObject("similarartists")
            if (similarartists != null) {
                val artistArray = similarartists.getAsJsonArray("artist")
                if (artistArray != null) {
                    return artistArray.map { it.asJsonObject.get("name").asString }
                }
            }
        }
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}



data class QuickPlaylist(
    val name: String,
    val query: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val gradStart: Color,
    val gradEnd: Color
)

private data class PlaylistSectionCache(
    val title: String,
    val playlists: List<AlbumItem>
)

private val QUICK_PLAYLISTS = listOf(
    QuickPlaylist("Chill Vibes", "chill lofi hindi music",  Icons.Default.MusicNote,  Color(0xFFC5A880), Color(0xFF1E1A14)),
    QuickPlaylist("Workout",    "gym workout music $HOME_CURRENT_YEAR",  Icons.Default.Bolt, Color(0xFFB39873), Color(0xFF191612)),
    QuickPlaylist("Party Hits", "party hits $HOME_CURRENT_YEAR india", Icons.Default.Star, Color(0xFFD6BE9C), Color(0xFF2C251C)),
    QuickPlaylist("Focus",      "study focus music",       Icons.Default.School,     Color(0xFFA38C6D), Color(0xFF171411)),
    QuickPlaylist("Bollywood",  "bollywood superhits",     Icons.Default.Favorite,   Color(0xFFC5A880), Color(0xFF251F17)),
)

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onPlayQueue: (VideoItem, List<VideoItem>) -> Unit = onSongClick,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onDiscoverClick: () -> Unit,
    isPlayerOpen: Boolean = false
) {
    val ctx   = LocalContext.current
    val db    = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
    val prefs = remember(ctx) { ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE) }
    val playlistSectionCachePrefs = remember(ctx) { ctx.getSharedPreferences("home_playlist_section_cache", Context.MODE_PRIVATE) }
    val homeGson = remember { Gson() }

    fun loadPlaylistSectionCache(key: String): List<Pair<String, List<AlbumItem>>> {
        return try {
            val json = playlistSectionCachePrefs.getString(key, null) ?: return emptyList()
            val type = object : TypeToken<List<PlaylistSectionCache>>() {}.type
            val cached: List<PlaylistSectionCache> = homeGson.fromJson(json, type)
            // Gson can bypass Kotlin constructors via Unsafe, producing null fields on non-null types.
            // Filter out any corrupt entries to prevent NPE crashes during Compose rendering.
            cached.mapNotNull { section ->
                val title = section.title ?: return@mapNotNull null
                val safePlaylists = section.playlists?.filter { item ->
                    @Suppress("SENSELESS_COMPARISON")
                    item != null && item.playlistId != null && item.title != null && item.author != null
                } ?: return@mapNotNull null
                if (safePlaylists.isNotEmpty()) title to safePlaylists else null
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlaylistSectionCache(key: String, sections: List<Pair<String, List<AlbumItem>>>) {
        if (sections.none { it.second.isNotEmpty() }) return
        val payload = sections.map { PlaylistSectionCache(it.first, it.second) }
        playlistSectionCachePrefs.edit()
            .putString(key, homeGson.toJson(payload))
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

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

    var categoryPlaylists  by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var isCategoryLoading by remember { mutableStateOf(false) }

    // Rap sub-category state
    var rapSubFilter by remember { mutableStateOf("All Rap") }
    var rapSubSections by remember { mutableStateOf<List<Pair<String, List<AlbumItem>>>>(emptyList()) }
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
    val remotePlaylistSongsCache = remember { mutableStateMapOf<String, List<VideoItem>>() }

    var recommendationSections by remember { mutableStateOf<List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>>(emptyList()) }
    var spotifyMixes by remember { mutableStateOf<List<com.vinmusic.recommendation.SpotifyMix>>(emptyList()) }
    var isLoadingMixes by remember { mutableStateOf(false) }
    var selectedSpotifyMix by remember { mutableStateOf<com.vinmusic.recommendation.SpotifyMix?>(null) }
    var isRecommendationsLoading by remember { mutableStateOf(true) }
    val isRefreshing by vm.isHomeRefreshing.collectAsStateWithLifecycle()
    val homeRefreshGeneration by vm.homeRefreshGeneration.collectAsStateWithLifecycle()
    var categoryRefreshTrigger by remember { mutableIntStateOf(0) }
    val pullRefreshState = rememberPullToRefreshState()
    val homeRefreshPayload by vm.homeRefreshPayload.collectAsStateWithLifecycle()

    var quickPicks by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingQuickPicks by remember { mutableStateOf(false) }

    val topTracksSignals by vm.topTracksFlow.collectAsState(initial = emptyList())
    val onRepeatTracks = remember(topTracksSignals) {
        topTracksSignals.map { signal ->
            VideoItem(
                videoId = signal.videoId,
                title = signal.title,
                author = signal.author,
                durationText = signal.durationText
            )
        }
    }

    var recommendedRadio by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingRecommendedRadio by remember { mutableStateOf(false) }
    var radioSeedSong by remember { mutableStateOf<VideoItem?>(null) }
    var lastRadioSeedId by remember { mutableStateOf("") }

    var ytMusicSections by remember { mutableStateOf<List<com.vinmusic.innertube.YTMusicHomeSection>>(emptyList()) }
    var isLoadingYtHome by remember { mutableStateOf(false) }
    var ytMusicConnected by remember { mutableStateOf(YTMusicSession.hasCookie(ctx)) }
    var ytLibraryPlaylists by remember { mutableStateOf<List<com.vinmusic.innertube.AlbumItem>>(emptyList()) }
    var isLoadingYtPlaylists by remember { mutableStateOf(false) }

    // Mood Deep Sections
    var moodSections by remember { mutableStateOf<List<Pair<String, List<AlbumItem>>>>(emptyList()) }
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

                val cachedJson = cachePrefs.getString("playlists_json_v4", null)
                val now = System.currentTimeMillis()

                if (cachedJson != null && !forceRefresh) {
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
                val signals = db.interactionSignalDao().getAll()
                val history = db.historyDao().getAllHistory()

                val personalizedArtists = mutableListOf<String>()

                // Extract top liked artist
                val likedArtists = liked
                    .filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .entries.sortedByDescending { it.value.size }
                    .map { it.key }
                likedArtists.firstOrNull()?.let { personalizedArtists.add(it) }

                // Extract top played artists from interaction signals
                val topSignalArtists = signals
                    .filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .mapValues { entry -> entry.value.sumOf { it.playCount } }
                    .filter { it.value > 0 }
                    .entries.sortedByDescending { it.value }
                    .map { it.key }
                topSignalArtists.take(3).forEach { artist ->
                    if (!personalizedArtists.contains(artist)) {
                        personalizedArtists.add(artist)
                    }
                }

                // Extract recently played artists from history
                val topHistoryArtists = history
                    .filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .entries.sortedByDescending { it.value.size }
                    .map { it.key }
                topHistoryArtists.take(2).forEach { artist ->
                    if (!personalizedArtists.contains(artist)) {
                        personalizedArtists.add(artist)
                    }
                }

                // Combine other liked artists
                likedArtists.forEach { artist ->
                    if (!personalizedArtists.contains(artist)) {
                        personalizedArtists.add(artist)
                    }
                }

                val profile = try {
                    com.vinmusic.recommendation.RecommendationManager.buildTasteProfile(db)
                } catch (_: Exception) { null }
                val topGenreTerms = profile?.topGenres?.take(4)
                    ?.map { it.first.lowercase().replace("rap/hip-hop", "rap hip hop").replace("punjabi folk", "punjabi") }
                    ?: emptyList()
                val topLang = profile?.topLanguages?.firstOrNull()?.first?.lowercase()?.takeIf { it != "unknown" }
                val topMood = profile?.topMoods?.firstOrNull()?.first?.lowercase()

                val queries = mutableListOf<String>()

                // Custom playlists (up to 2, shuffled)
                if (localPlaylists.isNotEmpty()) {
                    val genericNames = listOf("playlist", "new playlist", "imported playlist", "favorites", "liked", "custom playlist")
                    localPlaylists.filter { pl ->
                        val plName = pl.name.trim()
                        plName.isNotEmpty() && !genericNames.any { plName.lowercase() == it }
                    }.shuffled().take(2).forEach { pl ->
                        queries.add("${pl.name.trim()} playlist")
                    }
                }

                // Artist-specific playlists (top affinity artists only)
                personalizedArtists.take(4).forEach { artist ->
                    queries.add("$artist playlist")
                }

                // Genre + language blends straight from the TasteDNA profile — a user
                // who listens like you should get the playlists someone like you keeps.
                topGenreTerms.forEachIndexed { i, genreTerm ->
                    queries.add("$genreTerm ${topLang ?: ""} essentials playlist".trim().replace(Regex("\\s+"), " "))
                    if (i == 0 && topMood != null) {
                        queries.add("$genreTerm $topMood songs playlist".trim())
                    }
                }

                val tasteQueries = queries.distinctBy { it.lowercase().trim() }

                // Fallbacks pool — only fills remaining slots when the profile is thin.
                val defaultFallbackPool = listOf(
                    "${topLang ?: "hindi"} punjabi hits playlist",
                    "chill lofi study playlist",
                    "acoustic indie vibes playlist",
                    "r&b soul hits playlist",
                    "hip hop lyrical playlist",
                    "bedroom pop indie playlist",
                    "punjabi bhangra playlist",
                    "bollywood romantic playlist"
                )
                val shuffledFallbacks = defaultFallbackPool.shuffled()
                    .filter { fb -> tasteQueries.none { it.equals(fb, ignoreCase = true) } }

                // Taste-derived queries always go out; fallbacks only fill the tail.
                val finalQueries = (tasteQueries.take(6) + shuffledFallbacks.take(2)).shuffled()

                val allResults = mutableListOf<com.vinmusic.innertube.AlbumItem>()
                coroutineScope {
                    val deferreds = finalQueries.map { query ->
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

                // Junk filter — kill the weird spam playlists ("Top 50 nonstop jukebox",
                // karaoke/reaction/tiktok uploads) before they ever reach home.
                fun isJunkPlaylist(title: String): Boolean {
                    val t = title.lowercase()
                    val junkPhrases = listOf(
                        "top 50", "top 40", "top 30", "top 20", "top 10", "jukebox", "nonstop",
                        "non-stop", "mashup", "full album", "all songs", "greatest hits", "hits of",
                        "megamix", "superhit collection", "karaoke", "cover songs", "reaction",
                        "tiktok", "insta reels", "reels", "status video", "whatsapp status",
                        "slowed reverb", "nightcore", "8d audio", "bass boosted", "quiz"
                    )
                    return junkPhrases.any { t.contains(it) }
                }

                fun playlistTasteScore(item: com.vinmusic.innertube.AlbumItem): Int {
                    val t = item.title.lowercase()
                    var score = 0
                    topGenreTerms.forEachIndexed { i, g -> if (t.contains(g)) score += (6 - i) }
                    if (topLang != null && t.contains(topLang)) score += 3
                    if (topMood != null && t.contains(topMood)) score += 2
                    return score
                }

                val uniquePlaylists = allResults
                    .distinctBy { it.playlistId }
                    .filter { it.playlistId.startsWith("PL") || it.playlistId.startsWith("VL") }
                    // Taste-ranked with stable tie-breaking rotation via shuffled() first.
                    .shuffled()
                    .sortedByDescending { playlistTasteScore(it) }
                    .take(16)

                if (uniquePlaylists.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("playlists_json_v4", com.google.gson.Gson().toJson(uniquePlaylists))
                        .putLong("cache_time", now)
                        .apply()
                }

                withContext(Dispatchers.Main) {
                    if (uniquePlaylists.isNotEmpty()) recommendedPlaylists = uniquePlaylists
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

    suspend fun loadAutoHomeRecommendationSections(): List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>> {
        val sections = mutableListOf<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>()
        val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
        val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }

        val repeatPool = (signals
            .sortedWith(
                compareByDescending<com.vinmusic.data.db.InteractionSignal> { it.repeatCount * 4 + it.playCount + if (it.isLiked) 8 else 0 }
                    .thenByDescending { it.lastPlayedAt }
            )
            .map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
            history.take(40).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            .distinctBy { it.videoId }
            .take(12)
            .mapIndexed { index, item ->
                com.vinmusic.recommendation.RecommendedSong(
                    videoItem = item,
                    score = (100 - index).toDouble(),
                    source = "repeat_rewind",
                    reason = "On repeat"
                )
            }
        if (repeatPool.isNotEmpty()) {
            sections.add("On Repeat" to repeatPool)
        }

        return sections
    }

    suspend fun loadRepeatRewindMix(): com.vinmusic.recommendation.SpotifyMix? {
        val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
        val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
        val songs = (signals
            .sortedWith(
                compareByDescending<com.vinmusic.data.db.InteractionSignal> { it.repeatCount * 4 + it.playCount + if (it.isLiked) 8 else 0 }
                    .thenByDescending { it.lastPlayedAt }
            )
            .map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
            history.take(60).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            .distinctBy { it.videoId }
            .take(8)
            .mapIndexed { index, item ->
                com.vinmusic.recommendation.RecommendedSong(
                    videoItem = item,
                    score = (100 - index).toDouble(),
                    source = "repeat_rewind",
                    reason = "Your repeat"
                )
            }
        if (songs.isEmpty()) return null
        return com.vinmusic.recommendation.SpotifyMix(
            id = "repeat_rewind",
            title = "Repeat Rewind",
            description = "Your most replayed tracks.",
            songs = songs,
            gradientStartHex = "0xFFA38C6D",
            gradientEndHex = "0xFF171411"
        )
    }

    suspend fun loadLocalQuickPicks(): List<VideoItem> {
        // Instant local Quick Picks must surface NEW music, not listening history.
        // Serve cached related-song maps + forgotten favorites (played before but
        // not in the last 14 days); liked songs are the cold-start last resort
        // while the full network version loads.
        val related = try {
            db.relatedSongDao().quickPickVideos(18)
        } catch (_: Exception) { emptyList() }
        val forgotten = try {
            db.songCacheMetaDao().forgottenFavorites(
                System.currentTimeMillis() - 86_400_000L * 14, 8
            )
        } catch (_: Exception) { emptyList() }

        val picks = (
            related.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                forgotten.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            .filter { it.videoId.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.videoId }
        if (picks.isNotEmpty()) return picks.take(18)

        return try {
            db.likedSongDao().getAll()
                .shuffled()
                .take(12)
                .map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
        } catch (_: Exception) { emptyList() }
    }

    LaunchedEffect(homeRefreshPayload) {
        homeRefreshPayload?.let { payload ->
            recommendationSections = payload.recommendationSections
            spotifyMixes = payload.spotifyMixes
            quickPicks = payload.quickPicks
            ytMusicSections = payload.ytMusicSections
            ytMusicConnected = YTMusicSession.hasCookie(ctx)
            ytLibraryPlaylists = payload.ytLibraryPlaylists
            recommendedRadio = payload.recommendedRadio
            recommendedAlbums = payload.recommendedAlbums
        }
    }

    fun mergeRecommendationSections(
        fastSections: List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>,
        fullSections: List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>
    ): List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>> {
        if (fullSections.isEmpty()) return fastSections
        val merged = LinkedHashMap<String, List<com.vinmusic.recommendation.RecommendedSong>>()
        fastSections.forEach { (title, songs) ->
            if (songs.isNotEmpty()) merged[title] = songs
        }
        fullSections.forEach { (title, songs) ->
            if (songs.isNotEmpty()) merged[title] = songs
        }
        return merged.entries.map { it.key to it.value }
    }

    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                isRecommendationsLoading = true
                val fastRecs = loadAutoHomeRecommendationSections()
                withContext(Dispatchers.Main) {
                    recommendationSections = fastRecs
                }
                // Reuse the recommendation cache on normal Home entry. Fresh
                // rotation is reserved for pull-to-refresh so expensive
                // network curation never blocks the first usable Home render.
                val fullRecs = kotlinx.coroutines.withTimeoutOrNull(35_000L) {
                    com.vinmusic.recommendation.RecommendationManager.getRecommendations(ctx)
                }.orEmpty()
                if (fullRecs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        recommendationSections = mergeRecommendationSections(fastRecs, fullRecs)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("HomeScreen", "Failed to load recommendations: ${e.message}")
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    isRecommendationsLoading = false
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                isLoadingMixes = true
                val rewindMix = loadRepeatRewindMix()
                val genreMixes = try { vm.recommendationRepository.getGenreMixes() } catch (_: Exception) { emptyList() }
                val combined = listOfNotNull(rewindMix) + genreMixes
                withContext(Dispatchers.Main) {
                    spotifyMixes = combined
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
                val local = loadLocalQuickPicks()
                if (local.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (quickPicks.isEmpty()) quickPicks = local
                        isLoadingQuickPicks = false
                    }
                }
                val qp = kotlinx.coroutines.withTimeoutOrNull(9000L) {
                    vm.recommendationRepository.getQuickPicks()
                }.orEmpty()
                withContext(Dispatchers.Main) {
                    if (qp.isNotEmpty()) quickPicks = qp
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
                isLoadingYtPlaylists = YTMusicSession.hasCookie(ctx)
                val (ytHome, ytPlaylists) = coroutineScope {
                    val homeDeferred = async { runCatching { vm.recommendationRepository.getYouTubeMusicHomeSections() }.getOrDefault(emptyList()) }
                    val playlistsDeferred = async {
                        if (YTMusicSession.hasCookie(ctx)) {
                            runCatching { vm.recommendationRepository.getLibraryPlaylists() }.getOrDefault(emptyList())
                        } else {
                            emptyList()
                        }
                    }
                    homeDeferred.await() to playlistsDeferred.await()
                }
                withContext(Dispatchers.Main) {
                    ytMusicSections = ytHome
                    ytLibraryPlaylists = ytPlaylists
                    ytMusicConnected = YTMusicSession.hasCookie(ctx)
                    isLoadingYtHome = false
                    isLoadingYtPlaylists = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Failed to load YouTube Music shelves: ${e.message}")
                withContext(Dispatchers.Main) {
                    isLoadingYtHome = false
                    isLoadingYtPlaylists = false
                }
            }
        }
    }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "yt_music_cookie") {
                val connected = YTMusicSession.hasCookie(ctx)
                ytMusicConnected = connected
                isLoadingYtPlaylists = false
                if (!connected) {
                    ytMusicSections = emptyList()
                    ytLibraryPlaylists = emptyList()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(recentlyPlayed.firstOrNull()?.videoId) {
        val lastSong = recentlyPlayed.firstOrNull()
        if (lastSong != null && lastSong.videoId != lastRadioSeedId) {
            lastRadioSeedId = lastSong.videoId
            val seed = VideoItem(lastSong.videoId, lastSong.title, lastSong.author, lastSong.durationText)
            radioSeedSong = seed
            scope.launch(Dispatchers.IO) {
                try {
                    isLoadingRecommendedRadio = true
                    val rad = vm.recommendationRepository.getSongRadio(seed.videoId, seed.title, seed.author)
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
    // Counts genuine song changes so we can auto-refresh Quick Picks every 2 songs.
    var songsPlayedSinceQuickPickRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.currentSong?.videoId) {
        val currentSeedId = vm.currentSong?.videoId ?: ""
        if (currentSeedId.isNotEmpty() && currentSeedId != lastRecommendationSeedId) {
            lastRecommendationSeedId = currentSeedId
            songsPlayedSinceQuickPickRefresh += 1
            // Load "Similar To" songs for current song
            scope.launch(Dispatchers.IO) {
                try {
                    val similar = vm.recommendationRepository.getSongRadio(currentSeedId, vm.currentSong?.title ?: "", vm.currentSong?.author ?: "")
                    withContext(Dispatchers.Main) {
                        similarToSongs = similar.take(12)
                    }
                } catch (_: Exception) {}
            }

            // Auto-refresh Quick Picks after every 2 songs played. We invalidate
            // the cache so getQuickPicks() regenerates from fresh history/related data.
            if (songsPlayedSinceQuickPickRefresh >= 2) {
                songsPlayedSinceQuickPickRefresh = 0
                scope.launch(Dispatchers.IO) {
                    try {
                        vm.recommendationRepository.invalidateQuickPicksCache()
                        val qp = kotlinx.coroutines.withTimeoutOrNull(9000L) {
                            vm.recommendationRepository.getQuickPicks()
                        }.orEmpty()
                        if (qp.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                quickPicks = qp
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    LaunchedEffect(selectedRecommendedPlaylist) {
        val recommendedPl = selectedRecommendedPlaylist
        if (recommendedPl != null) {
            val cacheKey = recommendedPl.playlistId
            val cachedSongs = remotePlaylistSongsCache[cacheKey]
            if (!cachedSongs.isNullOrEmpty()) {
                recommendedPlaylistSongs = cachedSongs
                isLoadingPlaylistSongs = false
                return@LaunchedEffect
            }
            isLoadingPlaylistSongs = true
            recommendedPlaylistSongs = emptyList()
            scope.launch(Dispatchers.IO) {
                try {
                    val (_, playlistSongs) = com.vinmusic.innertube.InnerTube.getPlaylistSongs(recommendedPl.playlistId)
                    val filteredSongs = playlistSongs
                        .filterNot { com.vinmusic.recommendation.RecommendationManager.isNonMusicVideo(it.title, it.author) }
                        .filterNot { com.vinmusic.recommendation.RecommendationManager.isCompilationTrack(it.title, it.durationText) }
                        .distinctBy { it.videoId.ifBlank { "${it.title}|${it.author}" } }
                    withContext(Dispatchers.Main) {
                        remotePlaylistSongsCache[cacheKey] = filteredSongs
                        recommendedPlaylistSongs = filteredSongs
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
        val artist = selectedArtist ?: return
        ArtistProfileScreen(
            artist      = artist,
            vm          = vm,
            onBack      = { selectedArtist = null },
            onSongClick = onSongClick,
            onAlbumClick = onAlbumClick,
            onArtistClick = { selectedArtist = it }
        )
        return
    }

    selectedSpotifyMix?.let { mix ->
        HomeSpotifyMixDetailScreen(
            mix = mix,
            onBack = { selectedSpotifyMix = null },
            onPlaySong = { song, queue -> onPlayQueue(song, queue) },
            onImport = {
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
            }
        )
        return
    }

    selectedRecommendedPlaylist?.let { recommendedPl ->
        HomeRemotePlaylistDetailScreen(
            playlist = recommendedPl,
            songs = recommendedPlaylistSongs,
            isLoading = isLoadingPlaylistSongs,
            onBack = { selectedRecommendedPlaylist = null },
            onPlaySong = { song, queue -> onPlayQueue(song, queue) },
            onImport = {
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
            }
        )
        return
    }

    // ── Recommended Albums with Local Cache ──
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("recommended_albums_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("albums_json_v2", null)
                val now = System.currentTimeMillis()
                
                if (cachedJson != null) {
                    val type = object : com.google.gson.reflect.TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                    val list: List<com.vinmusic.innertube.AlbumItem> = com.google.gson.Gson().fromJson(cachedJson, type)
                    if (list.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            recommendedAlbums = list
                        }
                        return@launch
                    }
                }
                
                val liked = db.likedSongDao().getAll()
                val signals = db.interactionSignalDao().getAll()
                val history = db.historyDao().getAllHistory()
                
                val artists = mutableListOf<String>()
                
                liked.filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .entries.sortedByDescending { it.value.size }
                    .take(4).forEach { artists.add(it.key) }
                    
                signals.filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .mapValues { e -> e.value.sumOf { it.playCount } }
                    .filter { it.value > 0 }
                    .entries.sortedByDescending { it.value }
                    .take(4).forEach { if (!artists.contains(it.key)) artists.add(it.key) }
                    
                history.filter { it.author.isNotBlank() && it.author.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it.author) }
                    .groupBy { it.author.trim() }
                    .entries.sortedByDescending { it.value.size }
                    .take(4).forEach { if (!artists.contains(it.key)) artists.add(it.key) }
                
                val queries = mutableListOf<String>()
                artists.forEach { artist ->
                    queries.add("$artist album")
                }
                
                val fallbackAlbumPool = listOf(
                    "best hindi albums $HOME_CURRENT_YEAR",
                    "punjabi hit albums popular",
                    "billboard top albums english",
                    "indie artist music albums",
                    "latest lofi chill albums",
                    "best pop music albums",
                    "bollywood golden classics albums",
                    "slowed acoustic albums hits",
                    "ambient synthwave albums",
                    "top hip hop rap albums",
                    "new english albums $HOME_CURRENT_YEAR",
                    "underrated indie albums",
                    "r&b soul albums popular",
                    "desi hip hop albums",
                    "k-pop albums trending"
                )
                
                val shuffledFallbacks = fallbackAlbumPool.shuffled()
                var fallbackIndex = 0
                while (queries.size < 8 && fallbackIndex < shuffledFallbacks.size) {
                    val fallback = shuffledFallbacks[fallbackIndex]
                    if (!queries.contains(fallback)) {
                        queries.add(fallback)
                    }
                    fallbackIndex++
                }
                
                val selectedQueries = queries.shuffled().take(5)
                
                val allAlbums = mutableListOf<com.vinmusic.innertube.AlbumItem>()
                coroutineScope {
                    val deferreds = selectedQueries.map { q ->
                        async(Dispatchers.IO) {
                            try {
                                com.vinmusic.innertube.InnerTube.searchAll(q).albums
                            } catch (_: Exception) {
                                emptyList<com.vinmusic.innertube.AlbumItem>()
                            }
                        }
                    }
                    allAlbums.addAll(deferreds.awaitAll().flatten())
                }
                
                val uniqueAlbums = allAlbums
                    .distinctBy { it.playlistId }
                    .filter { it.playlistId.isNotEmpty() }
                    .shuffled()
                    .take(12)
                
                if (uniqueAlbums.isNotEmpty()) {
                    cachePrefs.edit()
                        .putString("albums_json_v2", com.google.gson.Gson().toJson(uniqueAlbums))
                        .putLong("cache_time", now)
                        .apply()
                }
                
                withContext(Dispatchers.Main) {
                    if (uniqueAlbums.isNotEmpty()) recommendedAlbums = uniqueAlbums
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
                val cachedJson = cachePrefs.getString("artists_json_v3", null)
                val cacheTime = cachePrefs.getLong("cache_time_v3", 0L)
                val now = System.currentTimeMillis()
                val cacheExpirationMs = 30 * 60 * 1000L // 30 minutes refresh

                if (cachedJson != null && (now - cacheTime) < cacheExpirationMs) {
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
                    .shuffled() // Shuffle listened artists so suggestions refresh dynamically

                val cleanListened = listenedArtists.map { normalizeArtistName(it) }.filter { it.isNotEmpty() }.toSet()
                val artistNames = ArrayList<String>()
                

                if (cleanListened.size < 5) {
                    // Cold-start/Variety Phase: Use Last.fm similar artists (local)
                    for (artName in listenedArtists.take(5)) {
                        try {
                            val lastFmSimilar = fetchSimilarArtistsLocally(artName)
                            for (simArt in lastFmSimilar) {
                                if (artistNames.size >= 16) break
                                val normSim = normalizeArtistName(simArt)
                                if (!cleanListened.contains(normSim) && !artistNames.map { normalizeArtistName(it) }.contains(normSim)) {
                                    artistNames.add(simArt)
                                }
                            }
                        } catch (_: Exception) {
                            // Fallback to hardcoded map if Last.fm fails
                            val normArt = normalizeArtistName(artName)
                            for ((key, value) in SIMILAR_ARTISTS_FALLBACK) {
                                if (normalizeArtistName(key) == normArt) {
                                    for (simArt in value) {
                                        if (artistNames.size >= 16) break
                                        val normSim = normalizeArtistName(simArt)
                                        if (!cleanListened.contains(normSim) && !artistNames.map { normalizeArtistName(it) }.contains(normSim)) {
                                            artistNames.add(simArt)
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }

                    // Fill with diverse top quality fallback artists if still need more
                    val fallbackList = listOf(
                        "Arijit Singh", "Sidhu Moose Wala", "Karan Aujla", "Diljit Dosanjh",
                        "The Weeknd", "Drake", "Anuv Jain", "Travis Scott",
                        "Kendrick Lamar", "J. Cole", "Seedhe Maut", "King",
                        "SZA", "Frank Ocean", "Prateek Kuhad", "AP Dhillon",
                        "DIVINE", "Talha Anjum", "KR\$NA", "Raftaar"
                    ).shuffled()
                    for (art in fallbackList) {
                        if (artistNames.size >= 16) break
                        val normFallback = normalizeArtistName(art)
                        if (!cleanListened.contains(normFallback) && !artistNames.map { normalizeArtistName(it) }.contains(normFallback)) {
                            artistNames.add(art)
                        }
                    }
                } else {
                    // Warm-start Discovery Phase: Use Last.fm similar artists in parallel
                    val topListened = listenedArtists.take(8)
                    coroutineScope {
                        val simJobs = topListened.map { artName ->
                            async(Dispatchers.IO) {
                                try {
                                    fetchSimilarArtistsLocally(artName)
                                } catch (_: Exception) {
                                    val normArt = normalizeArtistName(artName)
                                    SIMILAR_ARTISTS_FALLBACK.entries.firstOrNull { normalizeArtistName(it.key) == normArt }?.value ?: emptyList()
                                }
                            }
                        }
                        val allSimilar = simJobs.awaitAll().flatten()
                        for (simArt in allSimilar) {
                            if (artistNames.size >= 16) break
                            val normSim = normalizeArtistName(simArt)
                            if (!cleanListened.contains(normSim) && !artistNames.map { normalizeArtistName(it) }.contains(normSim)) {
                                artistNames.add(simArt)
                            }
                        }
                    }
                }
                
                coroutineScope {
                    val semaphore = kotlinx.coroutines.sync.Semaphore(4) // Max 4 concurrent requests
                    val deferreds = artistNames.map { name ->
                        async(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                val searchRes = InnerTube.searchAll(name)
                                searchRes.artists.firstOrNull { artist ->
                                    artist.name.lowercase().contains(name.lowercase()) || name.lowercase().contains(artist.name.lowercase())
                                } ?: searchRes.artists.firstOrNull()
                            } catch (_: Exception) { null } finally { semaphore.release() }
                        }
                    }
                    val resolved = deferreds.awaitAll()
                        .filterNotNull()
                        .distinctBy { normalizeArtistName(it.name) }
                        .shuffled()
                        .take(16)
                    if (resolved.isNotEmpty()) {
                        cachePrefs.edit()
                            .putString("artists_json_v3", com.google.gson.Gson().toJson(resolved))
                            .putLong("cache_time_v3", now)
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

    LaunchedEffect(filter, categoryRefreshTrigger, homeRefreshGeneration) {
        if (filter == "All" || filter == "For You") return@LaunchedEffect

        val moodChips = setOf("Happy", "Sad", "Energize", "Sleep", "Focus", "Workout", "Party")

        if (filter in moodChips) {
            // ── Deep Mood Sections: official YTM category sections + artist-specific
            isMoodLoading = true
            moodSections = emptyList()
            val sectionCacheKey = "mood_sections_${filter.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
            if (!isRefreshing) {
                val cachedSections = loadPlaylistSectionCache(sectionCacheKey)
                if (cachedSections.isNotEmpty()) {
                    moodSections = cachedSections
                    isMoodLoading = false
                    return@LaunchedEffect
                }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val MOOD_PARAMS_MAP = mapOf(
                        "Chill" to "ggMPOg1uX1JOQWZFeDByc2Jm",
                        "Energize" to "ggMPOg1uX2lRZUZiMnNrQnJW",
                        "Happy" to "ggMPOg1uXzZQbDB5eThLRTQ3",
                        "Feel Good" to "ggMPOg1uXzZQbDB5eThLRTQ3",
                        "Focus" to "ggMPOg1uX0NvNGNhWThMYWRh",
                        "Romance" to "ggMPOg1uX0FzQ2FhZWtUY211",
                        "Sad" to "ggMPOg1uX0JLQ0gySWZKZVY1",
                        "Sleep" to "ggMPOg1uX1MxaFQ3Z0JMZkN4",
                        "Workout" to "ggMPOg1uX09LWkhnTjRGRUJh",
                        "Party" to "ggMPOg1uX0pmQ0s2V0JRclZs",
                        "Bollywood" to "ggMPOg1uX2ZvbzNJMzJwRkFT",
                        "Indie" to "ggMPOg1uX3FzMXBrNWlUMWNH",
                        "Lo-fi" to "ggMPOg1uX1JOQWZFeDByc2Jm",
                        "K-Pop" to "ggMPOg1uX0JrbjBDOFFPSzJW",
                        "90s Hits" to "ggMPOg1uX253QXk4VXN5NGdj"
                    )
                    
                    val moodKeyword = when (filter) {
                        "Happy"    -> "happy upbeat feel good"
                        "Sad"      -> "sad emotional heartbreak"
                        "Energize" -> "energetic pump up hype"
                        "Sleep"    -> "sleep calm soothing ambient"
                        "Focus"    -> "focus deep work concentration lofi"
                        "Workout" -> "workout gym motivation power"
                        "Party"   -> "party dance club hits"
                        "Long Listens" -> "1 hour nonstop mix playlist"
                        else          -> filter.filter { it.isLetter() || it.isWhitespace() }.trim().lowercase()
                    }
                    val moodLabel = filter.filter { it.isLetter() || it.isWhitespace() }.trim()
                    val sections = mutableListOf<Pair<String, List<AlbumItem>>>()

                    // Try official category sections first (preserves all named shelves from YTM)
                    val officialParams = MOOD_PARAMS_MAP[filter]
                    if (officialParams != null) {
                        try {
                            val officialSections = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                com.vinmusic.innertube.YTMusicApi.getMoodPlaylistSections(officialParams)
                            }.orEmpty()
                            sections.addAll(officialSections)
                        } catch (e: Exception) {
                            android.util.Log.e("HomeScreen", "Failed to fetch official category sections: ${e.message}")
                        }
                    }

                    // Fallback if official sections returned nothing
                    if (sections.isEmpty()) {
                        try {
                            val genericResults = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                com.vinmusic.innertube.InnerTube.searchCommunityPlaylists("best $moodKeyword playlist")
                            }.orEmpty().take(8)
                            if (genericResults.isNotEmpty()) sections.add("Top $moodLabel Playlists" to genericResults)
                        } catch (_: Exception) {}

                        if (sections.isEmpty()) {
                            try {
                                val fallbackResults = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                    com.vinmusic.innertube.InnerTube.searchAll("best $moodKeyword playlist $HOME_CURRENT_YEAR").albums
                                }.orEmpty().take(8)
                                if (fallbackResults.isNotEmpty()) sections.add("Top $moodLabel Playlists" to fallbackResults)
                            } catch (_: Exception) {}
                        }

                        try {
                            val moreResults = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                com.vinmusic.innertube.InnerTube.searchCommunityPlaylists("$moodKeyword songs mix")
                            }.orEmpty().take(8)
                            if (moreResults.isNotEmpty()) sections.add("$moodLabel Mixes" to moreResults)
                        } catch (_: Exception) {}
                    }

                    // Add artist-specific mood sections from recently played
                    val topArtists = recentlyPlayed
                        .map { it.author.trim() }
                        .filter { it.isNotBlank() && it.lowercase() != "unknown" && !com.vinmusic.recommendation.RecommendationManager.isCorporateOrDistributorChannel(it) }
                        .groupBy { it }
                        .entries.sortedByDescending { it.value.size }
                        .map { it.key }
                        .distinct()
                        .take(4)

                    for (artistName in topArtists) {
                        try {
                            val shortKeyword = moodKeyword.split(" ").take(2).joinToString(" ")
                            val artistResults = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                                com.vinmusic.innertube.InnerTube.searchCommunityPlaylists("$artistName $shortKeyword")
                            }.orEmpty().take(6)
                            if (artistResults.isNotEmpty()) {
                                sections.add("$artistName · $moodLabel" to artistResults)
                            }
                        } catch (_: Exception) {}
                    }

                    withContext(Dispatchers.Main) {
                        if (sections.isNotEmpty()) {
                            savePlaylistSectionCache(sectionCacheKey, sections)
                            moodSections = sections
                        }
                        isMoodLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Deep Mood failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isMoodLoading = false
                    }
                }
            }
        } else if (filter == "Rap") {
            // Rap parent selected — don't load generic flat list, sub-categories handle it
            isRapSubLoading = true
            rapSubSections = emptyList()
            val rapSectionCacheKey = "rap_sections_${rapSubFilter.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
            if (!isRefreshing) {
                val cachedSections = loadPlaylistSectionCache(rapSectionCacheKey)
                if (cachedSections.isNotEmpty()) {
                    rapSubSections = cachedSections
                    isRapSubLoading = false
                    return@LaunchedEffect
                }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val sections = loadRapSubSections(rapSubFilter, recentlyPlayed)
                    withContext(Dispatchers.Main) {
                        if (sections.isNotEmpty()) {
                            savePlaylistSectionCache(rapSectionCacheKey, sections)
                            rapSubSections = sections
                        }
                        isRapSubLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Rap sub-category failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isRapSubLoading = false
                    }
                }
            }
        } else {
            // Genre chips: Bollywood, Lo-fi, Indie, K-Pop, 90s Hits — load full sectioned layout
            isMoodLoading = true
            moodSections = emptyList()
            val genreSectionCacheKey = "genre_sections_${filter.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
            if (!isRefreshing) {
                val cachedSections = loadPlaylistSectionCache(genreSectionCacheKey)
                if (cachedSections.isNotEmpty()) {
                    moodSections = cachedSections
                    isMoodLoading = false
                    return@LaunchedEffect
                }
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val MOOD_PARAMS_MAP = mapOf(
                        "Chill" to "ggMPOg1uX1JOQWZFeDByc2Jm",
                        "Energize" to "ggMPOg1uX2lRZUZiMnNrQnJW",
                        "Happy" to "ggMPOg1uXzZQbDB5eThLRTQ3",
                        "Feel Good" to "ggMPOg1uXzZQbDB5eThLRTQ3",
                        "Focus" to "ggMPOg1uX0NvNGNhWThMYWRh",
                        "Romance" to "ggMPOg1uX0FzQ2FhZWtUY211",
                        "Sad" to "ggMPOg1uX0JLQ0gySWZKZVY1",
                        "Sleep" to "ggMPOg1uX1MxaFQ3Z0JMZkN4",
                        "Workout" to "ggMPOg1uX09LWkhnTjRGRUJh",
                        "Party" to "ggMPOg1uX0pmQ0s2V0JRclZs",
                        "Bollywood" to "ggMPOg1uX2ZvbzNJMzJwRkFT",
                        "Indie" to "ggMPOg1uX3FzMXBrNWlUMWNH",
                        "Lo-fi" to "ggMPOg1uX1JOQWZFeDByc2Jm",
                        "K-Pop" to "ggMPOg1uX0JrbjBDOFFPSzJW",
                        "90s Hits" to "ggMPOg1uX253QXk4VXN5NGdj"
                    )

                    val sections = mutableListOf<Pair<String, List<AlbumItem>>>()
                    val officialParams = MOOD_PARAMS_MAP[filter]
                    if (officialParams != null) {
                        try {
                            val officialSections = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                com.vinmusic.innertube.YTMusicApi.getMoodPlaylistSections(officialParams)
                            }.orEmpty()
                            sections.addAll(officialSections)
                        } catch (e: Exception) {
                            android.util.Log.e("HomeScreen", "Failed to fetch official category genre sections: ${e.message}")
                        }
                    }

                    // Fallback: search-based if official sections returned nothing
                    if (sections.isEmpty()) {
                        val query = when (filter) {
                            "Bollywood" -> "bollywood hits playlist $HOME_CURRENT_YEAR"
                            "Lo-fi"     -> "lofi beats chill playlist"
                            "Indie"     -> "indie pop playlist"
                            "K-Pop"     -> "kpop hits playlist"
                            "90s Hits"  -> "90s bollywood classic hits playlist"
                            else        -> "$filter playlist"
                        }
                        val results = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                            com.vinmusic.innertube.InnerTube.searchCommunityPlaylists(query)
                        }.orEmpty().take(15)
                        if (results.isNotEmpty()) {
                            sections.add("Top ${filter.filter { it.isLetter() || it.isWhitespace() }.trim()} Picks" to results)
                        } else {
                            val fallback = kotlinx.coroutines.withTimeoutOrNull(7_000L) {
                                com.vinmusic.innertube.InnerTube.searchAll(query).albums
                            }.orEmpty().take(15)
                            if (fallback.isNotEmpty()) {
                                sections.add("Top ${filter.filter { it.isLetter() || it.isWhitespace() }.trim()} Picks" to fallback)
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (sections.isNotEmpty()) {
                            savePlaylistSectionCache(genreSectionCacheKey, sections)
                            moodSections = sections
                        }
                        isMoodLoading = false
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HomeScreen", "Category filter failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        isMoodLoading = false
                    }
                }
            }
        }
    }

    // ── Rap Sub-Category auto-reload when sub-filter changes ──
    LaunchedEffect(rapSubFilter) {
        if (filter != "Rap") return@LaunchedEffect
        isRapSubLoading = true
        rapSubSections = emptyList()
        val rapSectionCacheKey = "rap_sections_${rapSubFilter.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"
        val cachedSections = loadPlaylistSectionCache(rapSectionCacheKey)
        if (cachedSections.isNotEmpty()) {
            rapSubSections = cachedSections
            isRapSubLoading = false
            return@LaunchedEffect
        }
        scope.launch(Dispatchers.IO) {
            try {
                val sections = loadRapSubSections(rapSubFilter, recentlyPlayed)
                withContext(Dispatchers.Main) {
                    if (sections.isNotEmpty()) {
                        savePlaylistSectionCache(rapSectionCacheKey, sections)
                        rapSubSections = sections
                    }
                    isRapSubLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeScreen", "Rap sub-filter changed failed: ${e.message}")
                withContext(Dispatchers.Main) { isRapSubLoading = false }
            }
        }
    }

    // ── Long Listens (45min+ extended mixes & albums)
    // ── Long Listens (45min+ extended mixes & albums) with Dynamic Queries ──
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val cachePrefs = ctx.getSharedPreferences("long_listens_cache", Context.MODE_PRIVATE)
                val cachedJson = cachePrefs.getString("songs_json", null)
                val cacheTime = cachePrefs.getLong("cache_time", 0L)
                val now = System.currentTimeMillis()
                val cacheExpirationMs = 6 * 60 * 60 * 1000L // 6 hours

                // Use cache only if fresh
                if (cachedJson != null && (now - cacheTime) < cacheExpirationMs) {
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

                // Build dynamic queries based on user's top genres
                val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
                val topGenres = signals.map { it.author.trim() }
                    .groupBy { it }
                    .entries.sortedByDescending { it.value.size }
                    .take(3)
                    .map { it.key }

                val results = mutableListOf<VideoItem>()
                val longQueries = mutableListOf<String>()

                // Add queries based on user's top artists/genres
                for (artist in topGenres.take(2)) {
                    longQueries.add("$artist extended mix long")
                    longQueries.add("$artist nonstop mix 1 hour")
                }

                // Add genre-based queries
                val genreQueries = listOf(
                    "long lofi beats study 3 hours",
                    "extended mix nonstop hits $HOME_CURRENT_YEAR",
                    "1 hour music mix playlist"
                )
                longQueries.addAll(genreQueries.shuffled().take(2))

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
                    if (distinctLong.isNotEmpty()) longListens = distinctLong
                    isLoadingLongListens = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingLongListens = false
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VinColors.BgColor)) {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
        val isScreenActive = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && !isPlayerOpen

        if (isScreenActive) {
            // ✨ 1. Dynamic Animated Lava Lamp Fluid Background ✨
            val infiniteTransition = rememberInfiniteTransition(label = "home_bg_anims")
            val blob1X by infiniteTransition.animateFloat(
                initialValue = -100f, targetValue = 400f,
                animationSpec = infiniteRepeatable(tween(35000, easing = LinearEasing), RepeatMode.Reverse),
                label = "blob1X"
            )
            val blob2Y by infiniteTransition.animateFloat(
                initialValue = 800f, targetValue = -150f,
                animationSpec = infiniteRepeatable(tween(42000, easing = LinearEasing), RepeatMode.Reverse),
                label = "blob2Y"
            )
            val blob3X by infiniteTransition.animateFloat(
                initialValue = 500f, targetValue = -200f,
                animationSpec = infiniteRepeatable(tween(48000, easing = LinearEasing), RepeatMode.Reverse),
                label = "blob3X"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                // Blob 1: Dynamic Light Brown aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFC5A880).copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(blob1X.dp.toPx(), 220.dp.toPx()),
                        radius = size.width * 0.75f
                    ),
                    radius = size.width * 0.75f,
                    center = Offset(blob1X.dp.toPx(), 220.dp.toPx())
                )
                // Blob 2: Warm Glowing Gold aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFB39873).copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(100.dp.toPx(), blob2Y.dp.toPx()),
                        radius = size.width * 0.65f
                    ),
                    radius = size.width * 0.65f,
                    center = Offset(100.dp.toPx(), blob2Y.dp.toPx())
                )
                // Blob 3: Warm Charcoal aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF2C251C).copy(alpha = 0.12f), Color.Transparent),
                        center = Offset(blob3X.dp.toPx(), 620.dp.toPx()),
                        radius = size.width * 0.70f
                    ),
                    radius = size.width * 0.70f,
                    center = Offset(blob3X.dp.toPx(), 620.dp.toPx())
                )
            }
        }
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                vm.refreshHome()
                categoryRefreshTrigger++
            },
            state = pullRefreshState,
            modifier = Modifier.fillMaxSize()
        ) {
        val displayedRecommendations = remember(
            ytMusicSections, quickPicks, recommendedRadio, recommendationSections
        ) {
            dedupeHomeRecommendations(
                ytMusicSections = ytMusicSections,
                quickPicks = quickPicks,
                radio = recommendedRadio,
                sections = recommendationSections,
            )
        }
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
                        .graphicsLayer(shadowElevation = 8.dp.value, shape = RoundedCornerShape(24.dp), clip = false)
                        .clip(RoundedCornerShape(24.dp))
                        .background(VinColors.White10)
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.16f), VinColors.GlassBorder)
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
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
                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                Text(
                                    text = "${greeting()}, $userName",
                                    fontSize = 18.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
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
                        Column {
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
                            // Time-aware greeting — a small human moment that makes
                            // home feel composed rather than generated.
                            Text(
                                text = remember {
                                    when (java.util.Calendar.getInstance()
                                        .get(java.util.Calendar.HOUR_OF_DAY)) {
                                        in 5..11 -> "Good morning — fresh picks for your day"
                                        in 12..17 -> "Good afternoon — your mix is ready"
                                        in 18..21 -> "Good evening — unwind with your sound"
                                        else -> "Late night session — calm vibes ahead"
                                    }
                                },
                                style = Vin.Text.bodySmall.copy(color = VinColors.Secondary)
                            )
                        }
                    }

                    // Frosted search bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(shadowElevation = 6.dp.value, shape = RoundedCornerShape(18.dp), clip = false)
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
                                style = Vin.Text.cardSubtitle.copy(fontSize = 14.sp)
                            )
                        }
                    }
                }
            }

            // ── Premium Capsule Filter Chips ───────────────────────────────────────
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(CATEGORIES, key = { it }) { cat ->
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
                                            listOf(Color(0xFFC5A880), Color(0xFF8C7355))
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
                                        if (active) VinColors.AccentGlow else Color.White.copy(alpha = 0.08f)
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
                            // History is event-based, so the same song can
                            // legitimately occur more than once. Its list key
                            // must include the position, not just videoId.
                            itemsIndexed(historySongs.take(8), key = { index, song -> "history_${song.videoId}_$index" }) { _, song ->
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
                            items(ytLibraryPlaylists, key = { it.playlistId }) { pl ->
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
                                    items(recommendedPlaylists, key = { it.playlistId }) { pl ->
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
                // 0. On Repeat (Smart Playlist)
                if (onRepeatTracks.isNotEmpty()) {
                    item {
                        SectionTitle("On Repeat")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            itemsIndexed(onRepeatTracks, key = { index, song -> "repeat_${song.videoId}_$index" }) { _, song ->
                                SmallRecentlyPlayedCard(song = song) {
                                    onSongClick(song, onRepeatTracks)
                                }
                            }
                        }
                    }
                }

                if (isLoadingQuickPicks && displayedRecommendations.quickPicks.isEmpty()) {
                    item { ShelfSkeleton(cardHeight = 64.dp, cardWidth = 280.dp) }
                } else if (displayedRecommendations.quickPicks.isNotEmpty()) {
                    item {
                        SectionTitle("Quick Picks")
                        Spacer(Modifier.height(10.dp))
                        
                        val columns = displayedRecommendations.quickPicks.chunked(3)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            itemsIndexed(columns, key = { index, _ -> "quick_pick_column_$index" }) { _, columnSongs ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.width(280.dp)
                                ) {
                                    columnSongs.forEach { song ->
                                        QuickPickRow(
                                            song = song,
                                            onClick = { onSongClick(song, displayedRecommendations.quickPicks) },
                                            onMore = { onSongMore(song) }
                                        )
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
                            items(colCount, key = { it }) { index ->
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
                if (isLoadingRecommendedRadio && displayedRecommendations.radio.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (displayedRecommendations.radio.isNotEmpty() && radioSeedSong != null) {
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
                            itemsIndexed(displayedRecommendations.radio, key = { index, song -> "radio_${song.videoId}_$index" }) { _, song ->
                                RecommendedRadioCard(song = song) {
                                    onSongClick(song, displayedRecommendations.radio)
                                }
                            }
                        }
                    }
                }
                // Custom Spotify-Style Mixes divided into premium distinct shelves (below Recommended Radio)
                if (spotifyMixes.isNotEmpty()) {
                    val rewindMixes = spotifyMixes.filter { it.id == "repeat_rewind" }
                    val genreMixes = spotifyMixes.filterNot { it.id == "repeat_rewind" }

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
                                items(rewindMixes, key = { it.id }) { mix ->
                                    SpotifyMixCompactCard(
                                        mix = mix,
                                        onClick = { selectedSpotifyMix = mix }
                                    )
                                }
                            }
                        }
                    }
                    if (genreMixes.isNotEmpty()) {
                        item {
                            SectionTitle("Genre Mixes")
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            ) {
                                items(genreMixes, key = { it.id }) { mix ->
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
                        val nowPlaying = vm.currentSong ?: return@item
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .scale(1.35f),
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
                            itemsIndexed(similarToSongs, key = { index, song -> "similar_${song.videoId}_$index" }) { _, s ->
                                RecommendedRadioCard(song = s) { onSongClick(s, similarToSongs) }
                            }
                        }
                    }
                }

                // 1.9. YouTube Music personalized shelves (account-connected) —
                // real YT-engine picks lead above locally generated shelves.
                if (!isLoadingYtHome && displayedRecommendations.ytMusicSections.isNotEmpty()) {
                    displayedRecommendations.ytMusicSections.forEach { section ->
                        if (section.songs.isNotEmpty()) {
                            item {
                                SectionTitle(section.title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    itemsIndexed(section.songs, key = { index, song -> "yt_shelf_${song.videoId}_$index" }) { _, song ->
                                        TrackCard(song = song) {
                                            onSongClick(song, section.songs)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Dynamic Recommendations Sections (Personalized Music Engine)
                if (isRecommendationsLoading && displayedRecommendations.sections.isEmpty()) {
                    item { ShelfSkeleton() }
                } else {
                    displayedRecommendations.sections.forEach { (title, recList) ->
                        // Local discovery shelves add a different signal to the
                        // connected YT Music shelves, so show both.
                        if (recList.isNotEmpty()) {
                            if (title == "Side A") {
                                // Hero treatment: big top pick + regular row below.
                                item {
                                    SectionTitle(title)
                                    Spacer(Modifier.height(10.dp))
                                    val heroRec = recList.first()
                                    val heroItems = recList.map { it.videoItem }
                                    SideAHeroCard(heroRec) {
                                        onSongClick(heroRec.videoItem, heroItems)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    ) {
                                        itemsIndexed(recList.drop(1), key = { index, rec -> "side_a_${rec.videoItem.videoId}_$index" }) { _, rec ->
                                            RecommendedTrackCard(song = rec.videoItem, reason = rec.reason) {
                                                onSongClick(rec.videoItem, heroItems)
                                            }
                                        }
                                    }
                                }
                            } else {
                                item {
                                    SectionTitle(title)
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    ) {
                                        val videoItems = recList.map { it.videoItem }
                                        itemsIndexed(recList, key = { index, rec -> "recommendation_${rec.videoItem.videoId}_$index" }) { _, rec ->
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
                            itemsIndexed(downloadedSongs.take(8), key = { index, song -> "download_${song.videoId}_$index" }) { _, song ->
                                TrackCard(song = song) {
                                    onPlayQueue(song, downloadedSongs)
                                }
                            }
                        }
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
                            items(ytLibraryPlaylists, key = { it.playlistId }) { pl ->
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
                            Text("Loading", color = VinColors.Secondary, fontSize = 12.sp)
                        }
                    }
                }

                // 0. YouTube Music official home (Metrolist FEmusic_home)
                if (isLoadingYtHome && displayedRecommendations.ytMusicSections.isEmpty()) {
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
                    displayedRecommendations.ytMusicSections.forEach { section ->
                        if (section.songs.isNotEmpty()) {
                            item {
                                SectionTitle(section.title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    itemsIndexed(section.songs, key = { index, song -> "yt_shelf_${song.videoId}_$index" }) { _, song ->
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
                // 0. On Repeat (Smart Playlist)
                if (onRepeatTracks.isNotEmpty()) {
                    item {
                        SectionTitle("On Repeat")
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            itemsIndexed(onRepeatTracks, key = { index, song -> "repeat_${song.videoId}_$index" }) { _, song ->
                                SmallRecentlyPlayedCard(song = song) {
                                    onSongClick(song, onRepeatTracks)
                                }
                            }
                        }
                    }
                }

                if (isLoadingQuickPicks && displayedRecommendations.quickPicks.isEmpty()) {
                    item { ShelfSkeleton(cardHeight = 64.dp, cardWidth = 280.dp) }
                } else if (displayedRecommendations.quickPicks.isNotEmpty()) {
                    item {
                        SectionTitle("Quick Picks")
                        Spacer(Modifier.height(10.dp))
                        
                        val columns = displayedRecommendations.quickPicks.chunked(3)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                        ) {
                            itemsIndexed(columns, key = { index, _ -> "quick_pick_column_$index" }) { _, columnSongs ->
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.width(280.dp)
                                ) {
                                    columnSongs.forEach { song ->
                                        QuickPickRow(
                                            song = song,
                                            onClick = { onSongClick(song, displayedRecommendations.quickPicks) },
                                            onMore = { onSongMore(song) }
                                        )
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
                            items(colCount, key = { it }) { index ->
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
                if (isLoadingRecommendedRadio && displayedRecommendations.radio.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(24.dp))
                        }
                    }
                } else if (displayedRecommendations.radio.isNotEmpty() && radioSeedSong != null) {
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
                            itemsIndexed(displayedRecommendations.radio, key = { index, song -> "radio_${song.videoId}_$index" }) { _, song ->
                                RecommendedRadioCard(song = song) {
                                    onSongClick(song, displayedRecommendations.radio)
                                }
                            }
                        }
                    }
                }

                // Custom Spotify-Style Mixes divided into premium distinct shelves
                if (spotifyMixes.isNotEmpty()) {
                    val rewindMixes = spotifyMixes.filter { it.id == "repeat_rewind" }
                    val genreMixes = spotifyMixes.filterNot { it.id == "repeat_rewind" }

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
                                items(rewindMixes, key = { it.id }) { mix ->
                                    SpotifyMixCompactCard(
                                        mix = mix,
                                        onClick = { selectedSpotifyMix = mix }
                                    )
                                }
                            }
                        }
                    }
                    if (genreMixes.isNotEmpty()) {
                        item {
                            SectionTitle("Genre Mixes")
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                            ) {
                                items(genreMixes, key = { it.id }) { mix ->
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
                if (isRecommendationsLoading && displayedRecommendations.sections.isEmpty()) {
                    item { ShelfSkeleton() }
                } else {
                    displayedRecommendations.sections.forEach { (title, recList) ->
                        // Local discovery shelves add a different signal to the
                        // connected YT Music shelves, so show both.
                        if (recList.isNotEmpty()) {
                            if (title == "Side A") {
                                // Hero treatment: big top pick + regular row below.
                                item {
                                    SectionTitle(title)
                                    Spacer(Modifier.height(10.dp))
                                    val heroRec = recList.first()
                                    val heroItems = recList.map { it.videoItem }
                                    SideAHeroCard(heroRec) {
                                        onSongClick(heroRec.videoItem, heroItems)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    ) {
                                        itemsIndexed(recList.drop(1), key = { index, rec -> "side_a_${rec.videoItem.videoId}_$index" }) { _, rec ->
                                            RecommendedTrackCard(song = rec.videoItem, reason = rec.reason) {
                                                onSongClick(rec.videoItem, heroItems)
                                            }
                                        }
                                    }
                                }
                            } else {
                                item {
                                    SectionTitle(title)
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    ) {
                                        val videoItems = recList.map { it.videoItem }
                                        itemsIndexed(recList, key = { index, rec -> "recommendation_${rec.videoItem.videoId}_$index" }) { _, rec ->
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
            }
            "Rap" -> {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        items(RAP_SUB_CATEGORIES, key = { it.name }) { sub ->
                            val active = sub.name == rapSubFilter
                            val interactionSource = remember { MutableInteractionSource() }
                            val isPressed by interactionSource.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                targetValue = if (isPressed) 0.94f else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "sub_chip_scale"
                            )

                            Box(
                                modifier = Modifier
                                    .graphicsLayer(
                                        scaleX = scale,
                                        scaleY = scale,
                                        shadowElevation = if (active) 4.dp.value else 0f,
                                        shape = RoundedCornerShape(20.dp),
                                        clip = false
                                    )
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (active) {
                                            Brush.horizontalGradient(
                                                listOf(Color(0xFFC5A880), Color(0xFF8C7355))
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
                                        RoundedCornerShape(20.dp)
                                    )
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null
                                    ) { rapSubFilter = sub.name }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = sub.name,
                                    fontSize = 13.sp,
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

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
                                CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(36.dp))
                                Text(
                                    "Loading",
                                    color = VinColors.Secondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    rapSubSections.forEach { (title, playlists) ->
                        if (playlists.isNotEmpty()) {
                            item {
                                SectionTitle(title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    items(playlists) { playlist ->
                                        RecommendedPlaylistCard(playlist = playlist) {
                                            selectedRecommendedPlaylist = playlist
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (rapSubSections.isEmpty() && !isRapSubLoading) {
                        item { EmptyScreenState("No playlists found for this sub-category.") }
                    }
                }
            }
            else -> {
                // ── Long Listens special case ──
                if (filter == "Long Listens") {
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
                        itemsIndexed(longListens, key = { index, song -> "long_listen_${song.videoId}_$index" }) { _, song ->
                            SongListItem(
                                song = song,
                                isPlaying = vm.currentSong?.videoId == song.videoId,
                                onClick = { onSongClick(song, longListens) },
                                onMore = { onSongMore(song) }
                            )
                        }
                    } else if (isLoadingLongListens) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(36.dp))
                                    Text("Loading long mixes...", color = VinColors.Secondary, fontSize = 13.sp)
                                }
                            }
                        }
                    } else {
                        item { EmptyScreenState("No long listens found. Try again later.") }
                    }
                }
                // ── Mood & Genre Sections (artist-specific + generic mood playlists)
                else if ((isMoodLoading || isCategoryLoading) && moodSections.isEmpty() && categoryPlaylists.isEmpty()) {
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
                                    "Loading",
                                    color = VinColors.Secondary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    // Multi-row sections (Moods)
                    moodSections.forEach { (title, playlists) ->
                        if (playlists.isNotEmpty()) {
                            item {
                                SectionTitle(title)
                                Spacer(Modifier.height(10.dp))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(bottom = 24.dp)
                                ) {
                                    items(playlists) { playlist ->
                                        RecommendedPlaylistCard(playlist = playlist) {
                                            selectedRecommendedPlaylist = playlist
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Genre fallback (using categoryPlaylists)
                    if (categoryPlaylists.isNotEmpty()) {
                        item {
                            SectionTitle("Top ${filter.filter { it.isLetter() || it.isWhitespace() }.trim()} Picks")
                            Spacer(Modifier.height(10.dp))
                            // Display playlists in a grid-like flow row
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                maxItemsInEachRow = 2
                            ) {
                                categoryPlaylists.forEach { playlist ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        RecommendedPlaylistCard(
                                            playlist = playlist,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            selectedRecommendedPlaylist = playlist
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    if (moodSections.isEmpty() && categoryPlaylists.isEmpty() && !isMoodLoading && !isCategoryLoading) {
                        item { EmptyScreenState("No playlists found for this vibe. Try another category.") }
                    }
                }
            }
        }
    }
    } // end PullToRefreshBox
    } // end Box background aura

    val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                val file = java.io.File(ctx.filesDir, "user_custom_avatar.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                prefs.edit().putString("custom_profile_image_path", file.absolutePath).apply()
            } catch (e: Exception) {
                Log.e("HomeScreen", "Failed to save custom profile image: ${e.message}")
            }
        }
    }

    if (showProfileDialog) {
        val hasCustomPhoto = remember(prefs.getString("custom_profile_image_path", null)) {
            prefs.getString("custom_profile_image_path", null)?.let { java.io.File(it).exists() } == true
        }

        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text("Edit Profile", color = VinColors.Primary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    UserAvatar(avatarIndex = avatarIndex, size = 64.dp, name = editName)
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { photoPickerLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent, contentColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Change Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        if (hasCustomPhoto) {
                            OutlinedButton(
                                onClick = {
                                    prefs.edit().remove("custom_profile_image_path").apply()
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                            ) {
                                Text("Remove", fontSize = 12.sp)
                            }
                        }
                    }

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
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
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
        val mix = selectedSpotifyMix ?: return
        val startColor = runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
        val endColor = runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }

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
                                onPlayQueue(tracks[0], tracks)
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
                            itemsIndexed(mix.songs, key = { index, recSong -> "${recSong.videoItem.videoId}_$index" }) { index, recSong ->
                                val song = recSong.videoItem
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(VinColors.White10)
                                        .clickable {
                                            val tracks = mix.songs.map { it.videoItem }
                                            onPlayQueue(song, tracks)
                                            selectedSpotifyMix = null
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) {
                                        AsyncImage(
                                            model = song.thumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize().scale(1.35f),
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
        val recommendedPl = selectedRecommendedPlaylist ?: return
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
                            itemsIndexed(recommendedPlaylistSongs, key = { index, song -> "${song.videoId}_$index" }) { index, song ->
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
                                            modifier = Modifier.fillMaxSize().scale(1.35f),
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

@Composable
fun HomeRemotePlaylistDetailScreen(
    playlist: AlbumItem,
    songs: List<VideoItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlaySong: (VideoItem, List<VideoItem>) -> Unit,
    onImport: () -> Unit
) {
    val queue = remember(songs) {
        songs.distinctBy { it.videoId.ifBlank { "${it.title}|${it.author}" } }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        AsyncImage(
            model = playlist.thumbnail,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .blur(28.dp)
                .scale(1.12f),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.35f), VinColors.BgColor, VinColors.BgColor),
                        startY = 0f,
                        endY = 760f
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = "Playlist",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.White10)
                            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))
                    ) {
                        AsyncImage(
                            model = playlist.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = playlist.title,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (playlist.author.isNotBlank()) playlist.author else "YouTube Music",
                        color = VinColors.Secondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (isLoading) "Loading" else "${queue.size} songs",
                        color = VinColors.AccentLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { queue.firstOrNull()?.let { onPlaySong(it, queue) } },
                        enabled = !isLoading && queue.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.ExtraBold)
                    }

                    Button(
                        onClick = onImport,
                        enabled = !isLoading && queue.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                        border = BorderStroke(1.dp, VinColors.GlassBorder)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Import", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            when {
                isLoading -> item {
                    HomePlaylistLoadingState()
                }
                queue.isEmpty() -> item {
                    HomePlaylistEmptyState("No songs found for this playlist.")
                }
                else -> itemsIndexed(queue, key = { index, song -> "remote_${song.videoId}_$index" }) { index, song ->
                    HomePlaylistTrackRow(
                        index = index + 1,
                        song = song,
                        onClick = { onPlaySong(song, queue) }
                    )
                }
            }
        }
    }
}

@Composable
fun HomeSpotifyMixDetailScreen(
    mix: com.vinmusic.recommendation.SpotifyMix,
    onBack: () -> Unit,
    onPlaySong: (VideoItem, List<VideoItem>) -> Unit,
    onImport: () -> Unit
) {
    val queue = remember(mix) {
        mix.songs.map { it.videoItem }.distinctBy { it.videoId.ifBlank { "${it.title}|${it.author}" } }
    }
    val startColor = runCatching {
        Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#")))
    }.getOrElse { Color(0xFFC5A880) }
    val endColor = runCatching {
        Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#")))
    }.getOrElse { Color(0xFF1E1A14) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(Brush.verticalGradient(listOf(startColor.copy(alpha = 0.7f), VinColors.BgColor)))
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        text = "Mix",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HomeMixArtworkGrid(
                        songs = queue,
                        modifier = Modifier
                            .size(210.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(startColor, endColor)))
                    )

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = mix.title,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = mix.description,
                        color = VinColors.Secondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${queue.size} songs",
                        color = VinColors.AccentLight,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { queue.firstOrNull()?.let { onPlaySong(it, queue) } },
                        enabled = queue.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Play", fontWeight = FontWeight.ExtraBold)
                    }

                    Button(
                        onClick = onImport,
                        enabled = queue.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                        border = BorderStroke(1.dp, VinColors.GlassBorder)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text("Import", color = Color.White, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            if (queue.isEmpty()) {
                item { HomePlaylistEmptyState("No tracks inside this mix.") }
            } else {
                itemsIndexed(queue, key = { index, song -> "mix_${song.videoId}_$index" }) { index, song ->
                    HomePlaylistTrackRow(
                        index = index + 1,
                        song = song,
                        onClick = { onPlaySong(song, queue) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMixArtworkGrid(songs: List<VideoItem>, modifier: Modifier = Modifier) {
    val covers = songs.take(4)
    Box(modifier = modifier.border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp))) {
        if (covers.isEmpty()) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(54.dp)
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                repeat(2) { row ->
                    Row(Modifier.weight(1f)) {
                        repeat(2) { column ->
                            val song = covers.getOrNull(row * 2 + column)
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (song != null) {
                                    AsyncImage(
                                        model = song.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().scale(1.2f),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePlaylistTrackRow(index: Int, song: VideoItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            color = VinColors.Secondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp)
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(VinColors.White10)
        ) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.25f),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(song.author, song.durationText).filter { it.isNotBlank() }.joinToString(" - "),
                color = VinColors.Secondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = VinColors.White40,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun HomePlaylistLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(34.dp))
            Text("Loading", color = VinColors.Secondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HomePlaylistEmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = VinColors.Secondary, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/**
 * Helper function to load rap sub-section data.
 * Handles both the initial rap sub-section loading and updates when the sub-filter changes.
 */
private suspend fun loadRapSubSections(
    rapSubFilter: String,
    recentlyPlayed: List<com.vinmusic.data.db.HistoryEntry>
): List<Pair<String, List<AlbumItem>>> {
    val sections = mutableListOf<Pair<String, List<AlbumItem>>>()
    
    val sub = RAP_SUB_CATEGORIES.firstOrNull { it.name == rapSubFilter } ?: RAP_SUB_CATEGORIES[0]

    coroutineScope {
        val deferreds = sub.queries.map { q ->
            async(Dispatchers.IO) {
                try {
                    // Use searchCommunityPlaylists (YTMusic WEB_REMIX client) for proper playlist results
                    val playlists = com.vinmusic.innertube.InnerTube.searchCommunityPlaylists("$q playlist").take(8)
                    // Fallback to searchAll().albums if community playlists return nothing
                    playlists.ifEmpty {
                        com.vinmusic.innertube.InnerTube.searchAll("$q playlist").albums.take(8)
                    }
                } catch (_: Exception) { emptyList<AlbumItem>() }
            }
        }
        val results = deferreds.awaitAll()
        if (results.isNotEmpty() && results[0].isNotEmpty()) {
            sections.add("Top ${sub.name} Playlists" to results[0])
        }
        if (results.size > 1 && results[1].isNotEmpty()) {
            sections.add("More ${sub.name} Mixes" to results[1])
        }
    }

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
            val artistResults = com.vinmusic.innertube.InnerTube.searchAll("$artistName $keyword playlist").albums.take(6)
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
    Text(
        text,
        modifier = Modifier.padding(horizontal = 20.dp),
        style = Vin.Text.sectionHeader
    )
}

/**
 * Hero treatment for the "Side A" shelf's top pick — one large artwork-backed
 * card that gives home a focal point instead of another uniform row.
 */
@Composable
private fun SideAHeroCard(rec: com.vinmusic.recommendation.RecommendedSong, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(190.dp)
            .clip(RoundedCornerShape(Vin.Radius.xxl))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = rec.videoItem.thumbnailHd,
            contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.2f),
            contentScale = ContentScale.Crop
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.88f))
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "SIDE A · TOP PICK",
                    style = Vin.Text.overline.copy(color = VinColors.AccentLight)
                )
                Text(
                    rec.videoItem.title,
                    style = Vin.Text.h3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    rec.videoItem.author,
                    style = Vin.Text.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(VinColors.Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/** Shimmering placeholder row shown while a shelf loads — replaces spinners. */
@Composable
private fun ShelfSkeleton(cardHeight: Dp = 190.dp, cardWidth: Dp = 150.dp) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(bottom = 24.dp)
    ) {
        items(4, key = { it }) { index ->
            Box(
                modifier = Modifier
                    .width(cardWidth)
                    .height(cardHeight)
                    .clip(RoundedCornerShape(Vin.Radius.lg))
                    .background(VinColors.Surface)
                    .shimmerEffect()
            )
        }
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
            .glassCard(cornerRadius = 18.dp)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VinColors.Surface)
                .shimmerEffect()
        ) {
            AsyncImage(
                model = song.thumbnail, contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.35f),
                contentScale = ContentScale.Crop
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Vin.Text.cardTitleSmall)
            Text(song.author, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Vin.Text.cardSubtitle)
        }
    }
}

@Composable
fun QuickPlaylistCard(
    pl: QuickPlaylist,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "quick_playlist_scale"
    )

    Box(
        modifier = Modifier
            .width(120.dp)
            .height(120.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .graphicsLayer(shadowElevation = 8.dp.value, shape = RoundedCornerShape(18.dp), clip = false)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(
                colors = listOf(pl.gradStart, pl.gradEnd)
            ))
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) {
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
                pl.name, style = Vin.Text.cardTitleSmall.copy(color = Color.White),
                maxLines = 2, lineHeight = 16.sp
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
            Text(message, style = Vin.Text.cardSubtitle)
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
            .glassCard(cornerRadius = 20.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(144.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(VinColors.Surface)
                .shimmerEffect()
        ) {
            AsyncImage(
                model = song.thumbnail, contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.35f), contentScale = ContentScale.Crop
            )
        }
        
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Vin.Text.cardTitle
            )
            Text(
                text = song.author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Vin.Text.cardSubtitle
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
                .graphicsLayer(shadowElevation = 12.dp.value, shape = CircleShape, clip = false)
                .background(
                    brush = Brush.linearGradient(listOf(VinColors.Accent, VinColors.AccentLight)),
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
                    .shimmerEffect()
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
            style = Vin.Text.cardTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 18.sp
        )
        Text(
            text = if (artist.subscriberCount.isNotEmpty()) homeMonthlyListenersText(artist.subscriberCount) else "Artist",
            style = Vin.Text.cardSubtitle,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

private fun homeMonthlyListenersText(source: String): String {
    val compact = source
        .replace(Regex("""@\S+"""), "")
        .replace("subscribers", "", ignoreCase = true)
        .replace("subscriber", "", ignoreCase = true)
        .replace(Regex("""\bartist\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[•|·]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (compact.isBlank()) "" else "$compact Subscribers"
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
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientStartHex.replace("0x", "#"))) }.getOrElse { Color(0xFFC5A880) }
    }
    val endColor = remember(mix.gradientEndHex) {
        runCatching { Color(android.graphics.Color.parseColor(mix.gradientEndHex.replace("0x", "#"))) }.getOrElse { Color(0xFF1E1A14) }
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
                .graphicsLayer(shadowElevation = 10.dp.value, shape = RoundedCornerShape(18.dp), clip = false)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(colors = listOf(startColor, endColor)))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp))
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
            style = Vin.Text.cardTitleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = mix.description,
            style = Vin.Text.cardSubtitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun SpotifyMixCompactCard(
    mix: com.vinmusic.recommendation.SpotifyMix,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val coverUrl = mix.songs.firstOrNull()?.videoItem?.thumbnail

    Row(
        modifier = Modifier
            .width(210.dp)
            .graphicsLayer(
                scaleX = if (isPressed) 0.96f else 1f,
                scaleY = if (isPressed) 0.96f else 1f
            )
            .glassCard(cornerRadius = 18.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VinColors.Surface)
                .shimmerEffect()
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = mix.title,
                    modifier = Modifier.fillMaxSize().scale(1.35f),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(VinColors.Accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = VinColors.AccentLight, modifier = Modifier.size(24.dp))
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                mix.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Vin.Text.cardTitleSmall
            )
            Text(
                mix.description,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = Vin.Text.cardSubtitle,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun QuickPickRow(
    song: VideoItem,
    onClick: () -> Unit,
    onMore: (() -> Unit)? = null
) {
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
            .glassCard(cornerRadius = 16.dp)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(VinColors.Surface)
                .shimmerEffect()
        ) {
            AsyncImage(
                model = song.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.35f),
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
                style = Vin.Text.cardTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.author,
                style = Vin.Text.cardSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        } else {
            Spacer(Modifier.width(8.dp))
        }
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
            .glassCard(cornerRadius = 20.dp)
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
                style = Vin.Text.cardTitleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = song.author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = Vin.Text.cardSubtitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun RecommendedPlaylistCard(playlist: com.vinmusic.innertube.AlbumItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rec_playlist_scale"
    )

    Column(
        modifier = modifier
            .width(140.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .graphicsLayer(shadowElevation = 8.dp.value, shape = RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .background(VinColors.Surface)
                .shimmerEffect()
        ) {
            AsyncImage(
                model = playlist.thumbnail,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.title.orEmpty(),
            style = Vin.Text.cardTitleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = playlist.author.orEmpty(),
            style = Vin.Text.cardSubtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
