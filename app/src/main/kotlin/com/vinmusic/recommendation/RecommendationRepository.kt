package com.vinmusic.recommendation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.ArtistItem
import com.vinmusic.innertube.YTMusicApi
import com.vinmusic.innertube.YTMusicHomeSection
import com.vinmusic.data.db.RelatedSongMap
import com.vinmusic.data.db.SongCacheMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VinDatabase,
    private val recDb: RecommendationDatabase
) {
    private val TAG = "VIN_REC_REP"

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
    private val prefs = context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val CACHE_EXPIRY_MS = 15 * 60 * 1000L // 15 minutes

    // ── Local Disk Cache Helpers ──────────────────────────────────────────────

    private fun saveCacheStr(key: String, json: String) {
        try {
            prefs.edit()
                .putString(key, json)
                .putLong("${key}_time", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write disk cache for key '$key': ${e.message}")
        }
    }

    private fun loadCacheStr(key: String): String? {
        try {
            val time = prefs.getLong("${key}_time", 0L)
            if (System.currentTimeMillis() - time > CACHE_EXPIRY_MS) {
                prefs.edit().remove(key).remove("${key}_time").apply()
                return null
            }
            return prefs.getString(key, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load disk cache for key '$key': ${e.message}")
            return null
        }
    }

    private fun saveVideoItems(key: String, list: List<VideoItem>) {
        saveCacheStr(key, gson.toJson(list))
    }

    private fun loadVideoItems(key: String): List<VideoItem>? {
        val json = loadCacheStr(key) ?: return null
        val type = object : TypeToken<List<VideoItem>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun saveArtistItems(key: String, list: List<ArtistItem>) {
        saveCacheStr(key, gson.toJson(list))
    }

    private fun loadArtistItems(key: String): List<ArtistItem>? {
        val json = loadCacheStr(key) ?: return null
        val type = object : TypeToken<List<ArtistItem>>() {}.type
        return gson.fromJson(json, type)
    }

    // ── Primary Metrolist Curation Functions ───────────────────────────────────

    /**
     * Metrolist-style Quick Picks: local related_song_map + forgotten favorites + YouTube related().
     * Falls back to TasteDNA search when cache is empty.
     */
    suspend fun getQuickPicks(): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "quick_picks_v2"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Loaded getQuickPicks from disk cache.")
            return@withContext cached
        }

        Log.d(TAG, "Generating Metrolist-style Quick Picks...")
        val profile = RecommendationManager.buildTasteProfile(db)
        val combined = LinkedHashMap<String, VideoItem>()

        // 1) Cached related songs from recent seeds
        db.relatedSongDao().quickPickVideos(30).forEach { row ->
            addFilteredQuickPick(combined, VideoItem(row.videoId, row.title, row.author, row.durationText), profile)
        }

        // 2) Forgotten favorites — played before but not in last 14 days
        val twoWeeksAgo = System.currentTimeMillis() - 86400000L * 14
        db.songCacheMetaDao().forgottenFavorites(twoWeeksAgo, 8).forEach { meta ->
            addFilteredQuickPick(
                combined,
                VideoItem(meta.videoId, meta.title, meta.author, meta.durationText),
                profile,
            )
        }

        // 3) YouTube Music related() for blended seeds (top 3 recently played + 2 random liked tracks)
        val recentHistory = db.historyDao().getAllHistory().take(3)
        val likedSongs = db.likedSongDao().getAll().shuffled().take(2)
        val seeds = (recentHistory.map { it.videoId } + likedSongs.map { it.videoId }).distinct()
        
        if (seeds.isNotEmpty()) {
            coroutineScope {
                val deferreds = seeds.map { seedId ->
                    async(Dispatchers.IO) {
                        try {
                            fetchYtRelatedForSeed(seedId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch related for seed $seedId: ${e.message}")
                            null
                        }
                    }
                }
                deferreds.awaitAll().filterNotNull().flatten().forEach { item ->
                    addFilteredQuickPick(combined, item, profile)
                }
            }
        }

        var result = combined.values.take(20)
        if (result.size < 6) {
            Log.d(TAG, "Quick picks sparse (${result.size}), TasteDNA fallback...")
            result = getQuickPicksTasteDnaFallback(profile).take(20)
        }

        if (result.isNotEmpty()) saveVideoItems(cacheKey, result)
        result
    }

    /** Official YouTube Music home shelves (FEmusic_home) — requires cookie for best results. */
    suspend fun getYouTubeMusicHomeSections(): List<YTMusicHomeSection> = withContext(Dispatchers.IO) {
        val cacheKey = "yt_home_sections"
        val cachedJson = loadCacheStr(cacheKey)
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<List<YTMusicHomeSectionCache>>() {}.type
                val list: List<YTMusicHomeSectionCache> = gson.fromJson(cachedJson, type)
                if (list.isNotEmpty()) {
                    return@withContext list.map { it.toSection() }
                }
            } catch (_: Exception) { }
        }

        val profile = RecommendationManager.buildTasteProfile(db)
        val sections = ArrayList<YTMusicHomeSection>()
        var page = YTMusicApi.getHomePage()
        sections.addAll(filterHomeSections(page.sections, profile))
        var guard = 0
        while (!page.continuation.isNullOrBlank() && sections.size < 12 && guard < 3) {
            page = YTMusicApi.getHomePage(continuation = page.continuation)
            sections.addAll(filterHomeSections(page.sections, profile))
            guard++
        }

        if (sections.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(sections.map { YTMusicHomeSectionCache.from(it) }))
        }
        sections
    }

    /** Official user library playlists (FEmusic_liked_playlists) — requires cookie. */
    suspend fun getLibraryPlaylists(): List<com.vinmusic.innertube.AlbumItem> = withContext(Dispatchers.IO) {
        val cacheKey = "library_playlists"
        val cached = loadCacheStr(cacheKey)
        if (cached != null) {
            try {
                val type = object : TypeToken<List<com.vinmusic.innertube.AlbumItem>>() {}.type
                val list: List<com.vinmusic.innertube.AlbumItem> = gson.fromJson(cached, type)
                if (list.isNotEmpty()) return@withContext list
            } catch (_: Exception) {}
        }
        val result = YTMusicApi.getLibraryPlaylists()
        if (result.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(result))
        }
        result
    }

    /** Cache related tracks after playback (feeds quick picks over time). */
    suspend fun cacheRelatedForSong(videoId: String) = withContext(Dispatchers.IO) {
        if (db.relatedSongDao().hasRelated(videoId)) return@withContext
        val related = fetchYtRelatedForSeed(videoId) ?: return@withContext
        if (related.isEmpty()) return@withContext
        db.relatedSongDao().deleteForSong(videoId)
        val rows = related.take(25).map {
            RelatedSongMap(videoId, it.videoId, it.title, it.author, it.durationText)
        }
        db.relatedSongDao().insertAll(rows)
        Log.d(TAG, "Cached ${rows.size} related songs for $videoId")
    }

    suspend fun touchSongPlayMeta(song: VideoItem) = withContext(Dispatchers.IO) {
        val existing = db.songCacheMetaDao().topPlayed(500).find { it.videoId == song.videoId }
        val playTime = existing?.totalPlayTime?.plus(30_000L) ?: 30_000L
        db.songCacheMetaDao().upsert(
            SongCacheMeta(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText,
                lastPlayedAt = System.currentTimeMillis(),
                totalPlayTime = playTime,
            )
        )
    }

    private data class YTMusicHomeSectionCache(
        val title: String,
        val songs: List<VideoItem>,
        val browseId: String?,
        val params: String?,
    ) {
        fun toSection() = YTMusicHomeSection(title, songs, browseId, params)
        companion object {
            fun from(s: YTMusicHomeSection) = YTMusicHomeSectionCache(s.title, s.songs, s.browseId, s.params)
        }
    }

    private fun addFilteredQuickPick(
        out: LinkedHashMap<String, VideoItem>,
        item: VideoItem,
        profile: RecommendationManager.TasteProfile,
    ) {
        val author = item.author.trim().lowercase(Locale.ROOT)
        if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) return
        if (RecommendationManager.isNonMusicVideo(item.title, item.author)) return
        if (RecommendationManager.isUnofficialContent(item.title, item.author)) return
        if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(author)) return
        out.putIfAbsent(item.videoId, item)
    }

    private suspend fun fetchYtRelatedForSeed(videoId: String): List<VideoItem>? {
        val next = YTMusicApi.getNextRelated(videoId, playlistId = "RDAMVM$videoId")
        val browse = next.relatedBrowse ?: return null
        val raw = YTMusicApi.getRelatedSongs(browse.browseId, browse.params)
        return if (raw.isNotEmpty()) raw else null
    }

    private suspend fun getQuickPicksTasteDnaFallback(
        profile: RecommendationManager.TasteProfile,
    ): List<VideoItem> {
        val dna = profile.tasteDNA
        val queries = ArrayList<String>()
        
        val topArtists = profile.topArtists.take(3).map { it.first }
        val topGenres = profile.topGenres.take(2).map { it.first }
        
        // Add dynamic artist + genre blend queries
        if (topArtists.isNotEmpty() && topGenres.isNotEmpty()) {
            queries.add("${topArtists[0]} ${topGenres[0]} official songs")
        }
        
        // Dynamic search terms matching DNA profile parameters
        val energyTerm = when {
            dna.targetEnergy > 0.75 -> "energetic upbeat dance"
            dna.targetEnergy < 0.40 -> "acoustic soft chill lofi"
            else -> "popular"
        }
        
        val tempoTerm = when {
            dna.targetTempo > 125 -> "fast tempo workout beats"
            dna.targetTempo < 90 -> "slow relaxing mood"
            else -> "hits"
        }
        
        // Generate queries based on top artists
        for (artist in topArtists) {
            queries.add("$artist $energyTerm official music")
            queries.add("$artist popular tracks")
        }
        
        // Generate queries based on top genres
        for (genre in topGenres) {
            queries.add("$genre $tempoTerm $energyTerm hits 2026")
            queries.add("$genre trending music official")
        }
        
        // Fallbacks
        queries.add("trending official music hits 2026")
        if (queries.size > 8) {
            val uniqueQueries = queries.distinct().shuffled().take(6)
            queries.clear()
            queries.addAll(uniqueQueries)
        }
        val candidates = fetchCandidatesFromQueries(queries)
        val scored = ArrayList<Pair<VideoItem, Double>>()
        for (item in candidates) {
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            val meta = RecommendationManager.inferMetadata(item)
            val similarity = RecommendationManager.calculateTasteSimilarity(meta, dna)
            val officialBonus = if (meta.isOfficial) 25.0 else 0.0
            scored.add(item to (similarity * 75.0 + officialBonus))
        }
        val selected = ArrayList<VideoItem>()
        val artistCounts = HashMap<String, Int>()
        for (item in scored.sortedByDescending { it.second }.map { it.first }) {
            if (selected.size >= 12) break
            val author = item.author.lowercase(Locale.ROOT)
            val count = artistCounts[author] ?: 0
            if (count < 2) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }
        return selected
    }

    private fun filterHomeSections(
        sections: List<YTMusicHomeSection>,
        profile: RecommendationManager.TasteProfile,
    ): List<YTMusicHomeSection> {
        return sections.mapNotNull { section ->
            val filtered = section.songs.filter { item ->
                !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                    !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                    !RecommendationManager.isUnofficialContent(item.title, item.author) &&
                    !profile.skippedTracks.contains(item.videoId)
            }.distinctBy { it.videoId }
            if (filtered.size >= 3) section.copy(songs = filtered.take(12)) else null
        }
    }

    /**
     * 2. getRelatedSongs(videoId)
     * Returns contextual similar songs for a target track.
     */
    suspend fun getRelatedSongs(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "related_songs_$videoId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Generating live related songs for $videoId...")

        // 1. Metrolist: next → related browse (official YT Music related tab)
        var pool = fetchYtRelatedForSeed(videoId).orEmpty()

        // 2. InnerTube watch-next radio
        if (pool.isEmpty()) pool = InnerTube.getWatchNextRadio(videoId)

        // 3. TasteDNA query search fallback
        if (pool.isEmpty()) {
            Log.d(TAG, "getWatchNextRadio empty for $videoId, using TasteDNA fallback queries...")
            val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
            val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
            val signalEntry = db.interactionSignalDao().get(videoId)

            val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: ""
            val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: ""

            if (seedTitle.isNotEmpty()) {
                val seedItem = VideoItem(videoId, seedTitle, seedAuthor)
                val seedMeta = RecommendationManager.inferMetadata(seedItem)
                val queries = listOf(
                    "$seedAuthor official popular",
                    "${seedMeta.genre} similar hits 2026",
                    "$seedTitle similar music"
                )
                pool = fetchCandidatesFromQueries(queries)
            } else {
                Log.w(TAG, "No metadata available for fallback seed videoId '$videoId'. Doing raw search fallback.")
                pool = InnerTube.search(videoId)
            }
        }

        // Apply scoring and similarity filters
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)
        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: ""
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: ""

        val seedMeta = if (seedTitle.isNotEmpty()) {
            RecommendationManager.inferMetadata(VideoItem(videoId, seedTitle, seedAuthor))
        } else null

        val scored = ArrayList<Pair<VideoItem, Double>>()
        val profile = RecommendationManager.buildTasteProfile(db)

        for (item in pool) {
            if (item.videoId == videoId) continue
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(item.author.lowercase(Locale.ROOT))) continue

            val meta = RecommendationManager.inferMetadata(item)
            
            var totalSimilarity = 0.5
            if (seedMeta != null) {
                val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.1
                val artistScore = if (meta.artist.lowercase() == seedMeta.artist.lowercase()) {
                    1.0
                } else if (RecommendationManager.isSimilarArtist(meta.artist, seedMeta.artist)) {
                    0.6
                } else {
                    0.0
                }
                val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
                val langScore = if (meta.language == seedMeta.language) 1.0 else 0.3
                
                val energyDelta = Math.abs(meta.energy - seedMeta.energy)
                val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)
                
                val tempoDelta = Math.abs(meta.tempo - seedMeta.tempo).toDouble()
                val tempoScore = Math.cos((tempoDelta / 120.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

                totalSimilarity = genreScore * 0.25 + artistScore * 0.20 + moodScore * 0.15 + langScore * 0.15 + energyScore * 0.125 + tempoScore * 0.125
            } else {
                // Similarity against overall TasteDNA profile
                totalSimilarity = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            }
            
            val officialBonus = if (meta.isOfficial) 0.15 else 0.0
            scored.add(item to (totalSimilarity + officialBonus))
        }

        val sorted = scored.sortedByDescending { it.second }.map { it.first }

        // Same-Artist Spam Penalty: Cap at max 2 songs per artist
        val selected = ArrayList<VideoItem>()
        val artistCounts = HashMap<String, Int>()
        for (item in sorted) {
            if (selected.size >= 12) break
            val author = item.author.lowercase(Locale.ROOT)
            val count = artistCounts[author] ?: 0
            if (count < 2) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }

        if (selected.isNotEmpty()) {
            saveVideoItems(cacheKey, selected)
        }
        selected
    }

    suspend fun getSongRadio(videoId: String, fallbackTitle: String = "", fallbackAuthor: String = ""): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "song_radio_$videoId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Curating live smart radio for seed track $videoId...")

        val pool = mutableListOf<VideoItem>()
        
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)

        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: fallbackTitle
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: fallbackAuthor

        val online = isOnline()

        if (online) {
            Log.d(TAG, "Device is ONLINE. Using YouTube Watch Next/Related APIs for radio.")
            val ytRelated = fetchYtRelatedForSeed(videoId).orEmpty()
            if (ytRelated.isNotEmpty()) {
                pool.addAll(ytRelated)
            } else {
                pool.addAll(InnerTube.getWatchNextRadio(videoId))
            }

            // Blend with Spotify similarity ONLY if YouTube pool is small,
            // restricting to maximum 3 searches sequentially to prevent rate limits.
            if (pool.size < 10 && seedTitle.isNotEmpty()) {
                Log.d(TAG, "YouTube pool small (${pool.size}). Adding up to 3 Spotify-similar tracks.")
                val cleanTitle = seedTitle.replace("(Official Video)", "", ignoreCase = true)
                    .replace("(Official Music Video)", "", ignoreCase = true)
                    .replace("(Lyric Video)", "", ignoreCase = true).trim()

                val spotifyTrack = recDb.trackDao().findTrack(cleanTitle)
                if (spotifyTrack != null) {
                    val similarTracks = try {
                        recDb.trackDao().getSimilarTracksInCluster(
                            spotifyTrack.cluster_id,
                            spotifyTrack.energy,
                            spotifyTrack.valence,
                            spotifyTrack.dance,
                            spotifyTrack.acoustic,
                            spotifyTrack.tempo,
                            limit = 8
                        )
                    } catch (e: Exception) {
                        recDb.trackDao().getSimilarTracks(
                            spotifyTrack.energy,
                            spotifyTrack.valence,
                            spotifyTrack.dance,
                            spotifyTrack.acoustic,
                            spotifyTrack.tempo,
                            limit = 8
                        )
                    }

                    var searchesDone = 0
                    for (track in similarTracks) {
                        if (searchesDone >= 3) break
                        try {
                            val searchResults = InnerTube.search("${track.title} ${track.artist}")
                            val found = searchResults.firstOrNull { 
                                !RecommendationManager.isCompilationTrack(it.title, it.durationText) && 
                                !RecommendationManager.isNonMusicVideo(it.title, it.author) 
                            }
                            if (found != null && pool.none { it.videoId == found.videoId }) {
                                pool.add(found)
                                searchesDone++
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Spotify conversion search failed for ${track.title}: ${e.message}")
                        }
                    }
                }
            }

            // Fallback to TasteDNA Search queries if still empty
            if (pool.isEmpty()) {
                Log.d(TAG, "getWatchNextRadio empty for $videoId, using TasteDNA fallback queries...")
                if (seedTitle.isNotEmpty()) {
                    val seedItem = VideoItem(videoId, seedTitle, seedAuthor)
                    val seedMeta = RecommendationManager.inferMetadata(seedItem)
                    val queries = listOf(
                        "$seedAuthor radio hits",
                        "${seedMeta.genre} hits ${seedMeta.language} 2026",
                        "$seedTitle similar track"
                    )
                    pool.addAll(fetchCandidatesFromQueries(queries))
                } else {
                    pool.addAll(InnerTube.search(videoId).take(15))
                }
            }
        } else {
            Log.d(TAG, "Device is OFFLINE. Generating offline radio from downloaded songs.")
            val downloads = db.downloadDao().getByStatus("completed")
            val downloadedItems = downloads.map { 
                VideoItem(it.videoId, it.title, it.author, it.durationText) 
            }

            if (downloadedItems.isNotEmpty()) {
                // Try Spotify Similarity first (purely in-memory against downloaded items list)
                var spotifyMatches = emptyList<VideoItem>()
                if (seedTitle.isNotEmpty()) {
                    val cleanTitle = seedTitle.replace("(Official Video)", "", ignoreCase = true)
                        .replace("(Official Music Video)", "", ignoreCase = true)
                        .replace("(Lyric Video)", "", ignoreCase = true).trim()

                    val spotifyTrack = recDb.trackDao().findTrack(cleanTitle)
                    if (spotifyTrack != null) {
                        val similarTracks = try {
                            recDb.trackDao().getSimilarTracksInCluster(
                                spotifyTrack.cluster_id,
                                spotifyTrack.energy,
                                spotifyTrack.valence,
                                spotifyTrack.dance,
                                spotifyTrack.acoustic,
                                spotifyTrack.tempo,
                                limit = 30
                            )
                        } catch (e: Exception) {
                            recDb.trackDao().getSimilarTracks(
                                spotifyTrack.energy,
                                spotifyTrack.valence,
                                spotifyTrack.dance,
                                spotifyTrack.acoustic,
                                spotifyTrack.tempo,
                                limit = 30
                            )
                        }

                        val matchedList = mutableListOf<VideoItem>()
                        for (similarTrack in similarTracks) {
                            val downloadMatch = downloadedItems.firstOrNull { dl ->
                                RecommendationManager.isTooSimilar(dl.title, similarTrack.title) ||
                                (dl.title.contains(similarTrack.title, ignoreCase = true) && 
                                 dl.author.contains(similarTrack.artist, ignoreCase = true))
                            }
                            if (downloadMatch != null && downloadMatch.videoId != videoId && matchedList.none { it.videoId == downloadMatch.videoId }) {
                                matchedList.add(downloadMatch)
                            }
                        }
                        spotifyMatches = matchedList
                        Log.d(TAG, "Matched ${spotifyMatches.size} offline Spotify similarity tracks against local downloads.")
                    }
                }

                pool.addAll(spotifyMatches)

                // If pool is small, sort other downloaded items by similarity
                if (pool.size < 10) {
                    val profile = RecommendationManager.buildTasteProfile(db)
                    val seedMeta = if (seedTitle.isNotEmpty()) {
                        RecommendationManager.inferMetadata(VideoItem(videoId, seedTitle, seedAuthor))
                    } else null

                    val scoredDownloads = mutableListOf<Pair<VideoItem, Double>>()
                    for (item in downloadedItems) {
                        if (item.videoId == videoId) continue
                        if (pool.any { it.videoId == item.videoId }) continue

                        val meta = RecommendationManager.inferMetadata(item)
                        var similarity = 0.5
                        if (seedMeta != null) {
                            val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.1
                            val artistScore = if (meta.artist.lowercase() == seedMeta.artist.lowercase()) 1.0 else 0.0
                            val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
                            similarity = genreScore * 0.4 + artistScore * 0.3 + moodScore * 0.3
                        } else {
                            similarity = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
                        }
                        scoredDownloads.add(item to similarity)
                    }

                    val sortedDownloads = scoredDownloads.sortedByDescending { it.second }.map { it.first }
                    pool.addAll(sortedDownloads)
                }

                // If pool is still small or empty, add downloaded items shuffled
                if (pool.size < 5) {
                    val fallbackDownloads = downloadedItems.filter { it.videoId != videoId }.shuffled()
                    for (item in fallbackDownloads) {
                        if (pool.none { it.videoId == item.videoId }) {
                            pool.add(item)
                        }
                    }
                }
            } else {
                Log.w(TAG, "No downloaded tracks found for offline radio.")
            }
        }

        val seedMeta = if (seedTitle.isNotEmpty()) {
            RecommendationManager.inferMetadata(VideoItem(videoId, seedTitle, seedAuthor))
        } else null

        val scored = ArrayList<Pair<VideoItem, Double>>()
        val profile = RecommendationManager.buildTasteProfile(db)

        val recentlyPlayedTitles = db.historyDao().getAllHistory().take(20).map { it.title }
        val shouldExcludeCurrentArtist = Math.random() < 0.5 // 50% chance to exclude seed artist for variety

        for (item in pool) {
            if (item.videoId == videoId) continue
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(item.author.lowercase(Locale.ROOT))) continue
            
            if (seedTitle.isNotEmpty() && RecommendationManager.isTooSimilar(seedTitle, item.title)) continue
            
            if (recentlyPlayedTitles.contains(item.title)) continue
            if (shouldExcludeCurrentArtist && seedAuthor.isNotEmpty() && item.author.contains(seedAuthor, ignoreCase = true)) continue

            val meta = RecommendationManager.inferMetadata(item)

            var totalSimilarity = 0.5
            if (seedMeta != null) {
                val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.1
                val artistScore = if (meta.artist.lowercase() == seedMeta.artist.lowercase()) {
                    1.0
                } else if (RecommendationManager.isSimilarArtist(meta.artist, seedMeta.artist)) {
                    0.6
                } else {
                    0.0
                }
                val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
                val langScore = if (meta.language == seedMeta.language) 1.0 else 0.3

                val energyDelta = Math.abs(meta.energy - seedMeta.energy)
                val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)

                val tempoDelta = Math.abs(meta.tempo - seedMeta.tempo).toDouble()
                val tempoScore = Math.cos((tempoDelta / 120.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

                totalSimilarity = genreScore * 0.25 + artistScore * 0.20 + moodScore * 0.15 + langScore * 0.15 + energyScore * 0.125 + tempoScore * 0.125
            } else {
                totalSimilarity = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            }
            
            val officialBonus = if (meta.isOfficial) 0.1 else 0.0
            scored.add(item to (totalSimilarity + officialBonus))
        }

        val sorted = scored.sortedByDescending { it.second }.map { it.first }

        val sequenced = ArrayList<VideoItem>()
        val candidatesPool = ArrayList(sorted)
        var lastArtist = seedAuthor.lowercase(Locale.ROOT)

        while (candidatesPool.isNotEmpty() && sequenced.size < 20) {
            val nextItem = candidatesPool.firstOrNull { candidate ->
                val diffArtist = candidate.author.lowercase(Locale.ROOT) != lastArtist
                val notTooSimilar = sequenced.none { existing -> 
                    RecommendationManager.isTooSimilar(existing.title, candidate.title)
                }
                diffArtist && notTooSimilar
            } ?: candidatesPool.firstOrNull { candidate ->
                sequenced.none { existing -> 
                    RecommendationManager.isTooSimilar(existing.title, candidate.title)
                }
            } ?: candidatesPool.first()

            sequenced.add(nextItem)
            lastArtist = nextItem.author.lowercase(Locale.ROOT)
            candidatesPool.remove(nextItem)
            
            candidatesPool.removeAll { candidate -> 
                RecommendationManager.isTooSimilar(nextItem.title, candidate.title)
            }
        }

        // --- RELAXED FALLBACK (Guarantees playback never stops) ---
        if (sequenced.isEmpty() && pool.isNotEmpty()) {
            Log.d(TAG, "Sequenced list empty after strict filtering. Using relaxed fallback from pool.")
            val fallbackPool = pool.filter { it.videoId != videoId && !profile.skippedTracks.contains(it.videoId) }
            sequenced.addAll(fallbackPool.take(20))
        }

        // --- ULTIMATE SAFETY FALLBACK ---
        if (sequenced.isEmpty()) {
            Log.w(TAG, "Smart Radio pool is empty. Falling back to history/liked songs to keep music playing.")
            val fallbackSongs = mutableListOf<VideoItem>()
            val history = db.historyDao().getAllHistory().take(20)
            fallbackSongs.addAll(history.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            val liked = db.likedSongDao().getAll().take(20)
            fallbackSongs.addAll(liked.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            
            val uniqueFallbacks = fallbackSongs.filter { it.videoId != videoId && !profile.skippedTracks.contains(it.videoId) }.distinctBy { it.videoId }.shuffled()
            sequenced.addAll(uniqueFallbacks.take(10))
        }

        if (sequenced.isNotEmpty()) {
            saveVideoItems(cacheKey, sequenced)
        }
        sequenced
    }

    /**
     * 4. getArtistSongs(artistId)
     * Retrieves top/popular official songs for artist profile page (using channelId).
     */
    suspend fun getArtistSongs(artistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "artist_songs_$artistId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Fetching popular/official songs for artist channel ID $artistId...")
        val artistData = InnerTube.fetchChannelData(artistId)
        val artistName = artistData.title.ifBlank {
            // Fallback: try to search for the ID or scrape name from metadata
            ""
        }

        val songs = ArrayList<VideoItem>()
        if (artistName.isNotEmpty()) {
            // Fetch top songs by artist name
            val topSongs = InnerTube.getArtistTopSongs(artistName)
            songs.addAll(topSongs)

            // Scrape album and singles to ingest high-fidelity official tracks
            try {
                val (albums, singles) = InnerTube.getArtistAlbumsAndSingles(artistId, artistName)
                val allCollections = (albums.take(2) + singles.take(2)).distinctBy { it.playlistId }
                for (col in allCollections) {
                    val albumSongs = InnerTube.getAlbumSongs(col.playlistId)
                    songs.addAll(albumSongs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scrape albums for artist '$artistName': ${e.message}")
            }
        } else {
            // Direct ID search fallback
            val searchRes = InnerTube.search(artistId)
            songs.addAll(searchRes)
        }

        // Apply strict curation filters
        val filtered = songs.distinctBy { it.videoId }
            .filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
            .filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
            .filter { !RecommendationManager.isUnofficialContent(it.title, it.author) }
            .take(25)

        if (filtered.isNotEmpty()) {
            saveVideoItems(cacheKey, filtered)
        }
        filtered
    }

    /**
     * 5. getArtistRelatedArtists(artistId)
     * Returns similar artists for profile discovery.
     */
    suspend fun getArtistRelatedArtists(artistId: String): List<ArtistItem> = withContext(Dispatchers.IO) {
        val cacheKey = "related_artists_$artistId"
        val cached = loadArtistItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Fetching related artists for channel ID $artistId...")
        val artistData = InnerTube.fetchChannelData(artistId)
        val artistName = artistData.title

        val related = ArrayList<ArtistItem>()
        if (artistName.isNotEmpty()) {
            val searchResult = InnerTube.searchAll("artists like $artistName music")
            related.addAll(searchResult.artists)
        }

        val finalRelated = related.distinctBy { it.channelId }.take(8)
        if (finalRelated.isNotEmpty()) {
            saveArtistItems(cacheKey, finalRelated)
        }
        finalRelated
    }

    /**
     * 6. getPlaylistSongs(playlistId)
     * High-fidelity playlist resolver layered with strict filters and cache.
     */
    suspend fun getPlaylistSongs(playlistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "playlist_songs_$playlistId"
        val cached = loadVideoItems(cacheKey)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        Log.d(TAG, "Resolving playlist songs for $playlistId...")
        val (_, songs) = InnerTube.getPlaylistSongs(playlistId)

        // Strict Curation filters applied to loaded playlists
        val filtered = songs.filter { !RecommendationManager.isCompilationTrack(it.title, it.durationText) }
            .filter { !RecommendationManager.isNonMusicVideo(it.title, it.author) }
            .distinctBy { it.videoId }

        if (filtered.isNotEmpty()) {
            saveVideoItems(cacheKey, filtered)
        }
        filtered
    }

    /**
     * Curates and returns genre-based mixes for Lofi, Rap/Hip-Hop, Bollywood, Punjabi Folk, Pop, Indie, and Rock
     * with caching, similarity scoring, target mood filtering, and artist diversity.
     */
    suspend fun getGenreMixes(): List<SpotifyMix> = withContext(Dispatchers.IO) {
        val cacheKey = "genre_mixes_v3"
        val cachedJson = loadCacheStr(cacheKey)
        if (cachedJson != null) {
            try {
                val type = object : TypeToken<List<SpotifyMix>>() {}.type
                val list: List<SpotifyMix> = gson.fromJson(cachedJson, type)
                if (list.isNotEmpty()) {
                    Log.d(TAG, "Loaded getGenreMixes from disk cache.")
                    return@withContext list
                }
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Generating Genre-based smart mixes...")
        val profile = RecommendationManager.buildTasteProfile(db)
        val tasteDNA = profile.tasteDNA

        val mixes = ArrayList<SpotifyMix>()

        coroutineScope {
            val deferreds = RecommendationManager.GENRE_CONFIGS.map { (genreName, config) ->
                async(Dispatchers.IO) {
                    val genreCandidates = try {
                        config.queries.map { query ->
                            async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                } catch (e: Exception) {
                                    emptyList<VideoItem>()
                                }
                            }
                        }.awaitAll().flatten().distinctBy { it.videoId }
                    } catch (e: Exception) {
                        emptyList<VideoItem>()
                    }

                    // Content quality filters
                    val filteredCandidates = genreCandidates.filter { item ->
                        !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                        !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                        !RecommendationManager.isUnofficialContent(item.title, item.author) &&
                        !profile.skippedTracks.contains(item.videoId) &&
                        !profile.skippedArtists.contains(item.author.trim().lowercase(Locale.ROOT))
                    }

                    // Score candidates
                    val scored = filteredCandidates.mapNotNull { item ->
                        val meta = RecommendationManager.inferMetadata(item)
                        val isGenreMatch = meta.genre.lowercase(Locale.ROOT) == genreName.lowercase(Locale.ROOT)
                        val isMoodMatch = meta.mood.lowercase(Locale.ROOT) == config.targetMood.lowercase(Locale.ROOT)
                        
                        if (isGenreMatch || isMoodMatch) {
                            val similarity = RecommendationManager.calculateTasteSimilarity(meta, tasteDNA)
                            val officialBonus = if (meta.isOfficial) 0.15 else 0.0
                            val finalScore = similarity + officialBonus
                            RecommendedSong(item, finalScore, "genre_mix", "Matches your genre preference")
                        } else {
                            null
                        }
                    }

                    // Artist diversity
                    val sorted = scored.distinctBy { it.videoItem.videoId }
                        .distinctBy { "${RecommendationManager.normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                        .sortedByDescending { it.score }

                    val selected = ArrayList<RecommendedSong>()
                    val artistCounts = HashMap<String, Int>()

                    for (rec in sorted) {
                        if (selected.size >= 12) break
                        val normArtist = RecommendationManager.normalizeArtistName(rec.videoItem.author)
                        val count = artistCounts[normArtist] ?: 0
                        if (count < 2) {
                            selected.add(rec)
                            artistCounts[normArtist] = count + 1
                        }
                    }

                    if (selected.size >= 3) {
                        SpotifyMix(
                            id = "genre_mix_${genreName.lowercase(Locale.ROOT).replace("/", "_")}",
                            title = "$genreName Mix",
                            description = config.description,
                            songs = selected,
                            gradientStartHex = config.gradientStartHex,
                            gradientEndHex = config.gradientEndHex
                        )
                    } else {
                        null
                    }
                }
            }
            
            val resolvedMixes = deferreds.awaitAll().filterNotNull()
            mixes.addAll(resolvedMixes)
        }

        if (mixes.isNotEmpty()) {
            saveCacheStr(cacheKey, gson.toJson(mixes))
        }
        mixes
    }

    suspend fun getMoodCategoryPage(browseId: String, params: String): List<Pair<String, List<Any>>> = withContext(Dispatchers.IO) {
        InnerTube.getMoodCategoryPage(browseId, params)
    }

    /** Retrieves the user's top artists to inject into dynamic search queries for hyper-personalization. */
    suspend fun getTopArtistsForPersonalization(limit: Int = 2): List<String> = withContext(Dispatchers.IO) {
        try {
            val profile = RecommendationManager.buildTasteProfile(db)
            profile.topArtists.take(limit).map { it.first }
        } catch (e: Exception) {
            emptyList()
        }
    }
 
     // ── Internal Helpers ──────────────────────────────────────────────────────

    private suspend fun fetchCandidatesFromQueries(queries: List<String>): List<VideoItem> = coroutineScope {
        val deferredResults = queries.map { query ->
            async(Dispatchers.IO) {
                try {
                    InnerTube.search(query)
                } catch (e: Exception) {
                    Log.e(TAG, "Search query failed '$query': ${e.message}")
                    emptyList<VideoItem>()
                }
            }
        }
        deferredResults.awaitAll().flatten().distinctBy { it.videoId }
    }

    private suspend fun fetchArtistName(channelId: String): String = withContext(Dispatchers.IO) {
        try {
            val data = InnerTube.fetchChannelData(channelId)
            return@withContext data.title
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve artist title for channel $channelId: ${e.message}")
            return@withContext ""
        }
    }
}
