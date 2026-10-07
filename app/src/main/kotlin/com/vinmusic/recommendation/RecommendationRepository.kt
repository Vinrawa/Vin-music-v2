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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private val inFlightRadios = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<VideoItem>>>()
    }

    object RecentlySuggestedTracker {
        private val suggestedHistory = java.util.concurrent.ConcurrentHashMap<String, Long>()
        /** Maps normalized artist name → list of timestamps when they were suggested. */
        private val suggestedArtists = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()

        fun recordSuggestions(songs: List<VideoItem>) {
            val now = System.currentTimeMillis()
            val cutoff = now - 2 * 60 * 60 * 1000L // 2 hours
            for (song in songs) {
                suggestedHistory[song.videoId] = now
                val normArt = RecommendationManager.normalizeArtistName(song.author)
                if (normArt.isNotBlank()) {
                    suggestedArtists.getOrPut(normArt) { mutableListOf() }.add(now)
                }
            }
            // Cleanup entries older than 2 hours
            suggestedHistory.entries.removeIf { now - it.value > cutoff }
            // Time-based eviction for artist fatigue (remove timestamps > 2h old)
            for ((key, timestamps) in suggestedArtists) {
                timestamps.removeAll { now - it > cutoff }
                if (timestamps.isEmpty()) suggestedArtists.remove(key)
            }
        }

        fun isRecentlySuggested(videoId: String): Boolean {
            val last = suggestedHistory[videoId] ?: return false
            return System.currentTimeMillis() - last < 90 * 60 * 1000L // 90 mins cooldown
        }

        fun getArtistSuggestionCount(artist: String): Int {
            val normArt = RecommendationManager.normalizeArtistName(artist)
            val timestamps = suggestedArtists[normArt] ?: return 0
            val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
            return timestamps.count { it > cutoff }
        }
    }

    private suspend fun fetchLastFmSimilarTracks(artist: String, title: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
        if (apiKey.isBlank() || artist.isBlank() || title.isBlank()) return@withContext emptyList()
        var conn: java.net.HttpURLConnection? = null
        try {
            val urlString = "https://ws.audioscrobbler.com/2.0/?method=track.getsimilar" +
                "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                "&track=${java.net.URLEncoder.encode(title, "UTF-8")}" +
                "&api_key=$apiKey" +
                "&format=json&limit=15"
            conn = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val root = com.google.gson.JsonParser.parseString(json).asJsonObject
                val simtracks = root.getAsJsonObject("similartracks")?.getAsJsonArray("track")
                if (simtracks != null) {
                    val list = mutableListOf<Pair<String, String>>()
                    for (elem in simtracks) {
                        val obj = elem.asJsonObject
                        val tTitle = obj.get("name")?.asString ?: ""
                        val tArtist = obj.getAsJsonObject("artist")?.get("name")?.asString ?: ""
                        if (tTitle.isNotBlank() && tArtist.isNotBlank()) {
                            list.add(tArtist to tTitle)
                        }
                    }
                    return@withContext list
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
        }
        emptyList()
    }

    private fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager == null) return true
            val network = connectivityManager.activeNetwork ?: return true
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return true
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true
        }
    }
    private val prefs = context.getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val CACHE_EXPIRY_MS = 15 * 60 * 1000L // 15 minutes

    private fun normalizeQueueArtist(artist: String): String =
        RecommendationManager.normalizeArtistName(artist).trim().lowercase(Locale.ROOT)

    private fun sameQueueLanguage(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() &&
            first.trim().equals(second.trim(), ignoreCase = true)

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

    private fun loadCacheStr(key: String, allowStale: Boolean = false): String? {
        try {
            val time = prefs.getLong("${key}_time", 0L)
            if (!allowStale && System.currentTimeMillis() - time > CACHE_EXPIRY_MS) {
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

    private fun loadVideoItems(key: String, allowStale: Boolean = true): List<VideoItem>? {
        val json = loadCacheStr(key, allowStale = allowStale) ?: return null
        val type = object : TypeToken<List<VideoItem>>() {}.type
        return gson.fromJson(json, type)
    }

    /**
     * Clears the cached Quick Picks so the next [getQuickPicks] call regenerates
     * a fresh list. Used by the "refresh Quick Picks after every 2 songs" hook.
     */
    fun invalidateQuickPicksCache() {
        try {
            prefs.edit().remove("quick_picks_v2").remove("quick_picks_v2_time").apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invalidate Quick Picks cache: ${e.message}")
        }
    }

    private fun saveArtistItems(key: String, list: List<ArtistItem>) {
        saveCacheStr(key, gson.toJson(list))
    }

    private fun loadArtistItems(key: String, allowStale: Boolean = true): List<ArtistItem>? {
        val json = loadCacheStr(key, allowStale = allowStale) ?: return null
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
        // Strict TTL — stale Quick Picks would freeze the shelf on the same songs.
        val cached = loadVideoItems(cacheKey, allowStale = false)
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Loaded getQuickPicks from disk cache.")
            return@withContext cached
        }

        Log.d(TAG, "Generating Metrolist-style Quick Picks...")
        val profile = RecommendationManager.buildTasteProfile(db)
        val combined = LinkedHashMap<String, VideoItem>()
        val languageOverflow = ArrayList<VideoItem>()

        // 1) Cached related songs from recent seeds
        db.relatedSongDao().quickPickVideos(30).forEach { row ->
            addFilteredQuickPick(combined, VideoItem(row.videoId, row.title, row.author, row.durationText), profile, languageOverflow)
        }

        // 2) Forgotten favorites — played before but not in last 14 days (small cap
        //    so resurfaced history never dominates the shelf)
        val twoWeeksAgo = System.currentTimeMillis() - 86400000L * 14
        db.songCacheMetaDao().forgottenFavorites(twoWeeksAgo, 5).forEach { meta ->
            addFilteredQuickPick(
                combined,
                VideoItem(meta.videoId, meta.title, meta.author, meta.durationText),
                profile,
                languageOverflow,
            )
        }

        // 3) YouTube Music related() for diversified seeds, interleaved round-robin
        //    so no single seed dominates the shelf.
        val seeds = buildQuickPickSeeds()
        if (seeds.isNotEmpty()) {
            coroutineScope {
                val perSeedLists = seeds.map { seedId ->
                    async(Dispatchers.IO) {
                        try {
                            fetchYtRelatedForSeed(seedId).orEmpty()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch related for seed $seedId: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().filter { it.isNotEmpty() }

                val maxLen = perSeedLists.maxOfOrNull { it.size } ?: 0
                interleave@ for (i in 0 until maxLen) {
                    for (list in perSeedLists) {
                        if (i < list.size) {
                            addFilteredQuickPick(combined, list[i], profile, languageOverflow)
                            if (combined.size >= 30) break@interleave
                        }
                    }
                }
            }
        }

        // Soft language rule: only backfill language-mismatched candidates when the
        // strict pool is thin, instead of letting the shelf collapse.
        var picked = combined.values.toList()
        if (picked.size < 8 && languageOverflow.isNotEmpty()) {
            val seenIds = picked.map { it.videoId }.toSet()
            picked = picked + languageOverflow.filter { it.videoId !in seenIds }.take(12 - picked.size)
        }

        var result = picked.take(20)
        if (result.size < 6) {
            Log.d(TAG, "Quick picks sparse (${result.size}), TasteDNA fallback...")
            val existingIds = result.map { it.videoId }.toSet()
            val fallback = getQuickPicksTasteDnaFallback(profile).filter { it.videoId !in existingIds }
            result = (result + fallback).take(20)
        }

        if (result.isNotEmpty()) saveVideoItems(cacheKey, result)
        result
    }

    /**
     * Diversified Quick Pick seeds drawn across listening behavior — most-completed
     * tracks, likes, forgotten favorites and older plays — excluding the last ~10
     * played IDs so the shelf doesn't echo what just finished. Capped at 6 seeds.
     */
    private suspend fun buildQuickPickSeeds(): List<String> {
        val history = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
        val recentIds = history.take(10).map { it.videoId }.toSet()
        val signals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
        val liked = try { db.likedSongDao().getAll() } catch (_: Exception) { emptyList() }
        val forgotten = try {
            db.songCacheMetaDao().forgottenFavorites(System.currentTimeMillis() - 86400000L * 14, 20)
        } catch (_: Exception) { emptyList() }

        fun eligible(ids: List<String>): List<String> =
            ids.filter { it.isNotBlank() && it !in recentIds }.distinct().shuffled()

        val pools = listOf(
            eligible(signals.sortedByDescending {
                it.completeCount + it.repeatCount * 2 + if (it.isLiked) 3 else 0
            }.map { it.videoId }).take(4),
            eligible(liked.map { it.videoId }).take(3),
            eligible(forgotten.map { it.videoId }).take(3),
            eligible(history.map { it.videoId }).take(3),
        )

        // Round-robin across pools for behavioral diversity.
        val seeds = LinkedHashSet<String>()
        var added = true
        while (seeds.size < 6 && added) {
            added = false
            for (pool in pools) {
                val next = pool.firstOrNull { it !in seeds }
                if (next != null) {
                    seeds.add(next)
                    added = true
                    if (seeds.size >= 6) break
                }
            }
        }
        return seeds.toList()
    }

    /** Official YouTube Music home shelves (FEmusic_home) — requires cookie for best results. */
    suspend fun getYouTubeMusicHomeSections(): List<YTMusicHomeSection> = withContext(Dispatchers.IO) {
        val cacheKey = "yt_home_sections"
        val cachedJson = loadCacheStr(cacheKey, allowStale = true)
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
        val cached = loadCacheStr(cacheKey, allowStale = true)
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
        languageOverflow: MutableList<VideoItem>? = null,
    ) {
        val author = item.author.trim().lowercase(Locale.ROOT)
        if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) return
        if (RecommendationManager.isNonMusicVideo(item.title, item.author)) return
        if (RecommendationManager.isUnofficialContent(item.title, item.author)) return
        if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(author)) return
        val meta = RecommendationManager.inferMetadata(item)
        if (!meta.isOfficial) return
        val topLang = profile.topLanguages.firstOrNull()?.first
        if (topLang != null && meta.language != topLang) {
            // Soft rule: hold back instead of dropping outright so a thin pool can be
            // backfilled with these later rather than collapsing.
            languageOverflow?.add(item)
            return
        }
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
        val yr = java.time.LocalDate.now().year
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
            queries.add("$genre $tempoTerm $energyTerm hits $yr")
            queries.add("$genre trending music official")
        }
        
        // Fallbacks
        queries.add("trending official music hits $yr")
        if (queries.size > 8) {
            val uniqueQueries = queries.distinct().shuffled().take(6)
            queries.clear()
            queries.addAll(uniqueQueries)
        }
        val candidates = fetchCandidatesFromQueries(queries)
        val topLang = profile.topLanguages.firstOrNull()?.first
        val scored = ArrayList<Pair<VideoItem, Double>>()
        for (item in candidates) {
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item)
            if (!meta.isOfficial) continue
            if (topLang != null && meta.language != topLang) continue
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
            if (count < 3) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }
        return selected
    }

    private fun isBannedShelfTitle(title: String): Boolean {
        val lower = title.lowercase(java.util.Locale.ROOT)
        return lower.contains("shorts") ||
            lower.contains("music video") ||
            lower.contains("videos") ||
            lower.contains("clip") ||
            lower.contains("trailer")
    }

    private fun filterHomeSections(
        sections: List<YTMusicHomeSection>,
        profile: RecommendationManager.TasteProfile,
    ): List<YTMusicHomeSection> {
        val topLang = profile.topLanguages.firstOrNull()?.first
        return sections.mapNotNull { section ->
            if (isBannedShelfTitle(section.title)) return@mapNotNull null

            val filtered = section.songs.filter { item ->
                !RecommendationManager.isCompilationTrack(item.title, item.durationText) &&
                    !RecommendationManager.isNonMusicVideo(item.title, item.author) &&
                    !RecommendationManager.isUnofficialContent(item.title, item.author) &&
                    !profile.skippedTracks.contains(item.videoId)
            }.mapNotNull { item ->
                val meta = RecommendationManager.inferMetadata(item)
                if (!meta.isOfficial) return@mapNotNull null
                if (topLang != null && !RecommendationManager.isCompatibleQueueLanguage(meta.language, topLang, allowUnknown = true)) return@mapNotNull null
                item
            }.distinctBy { it.videoId }
            if (filtered.size >= 2) section.copy(songs = filtered.take(12)) else null
        }
    }

    /**
     * 2. getRelatedSongs(videoId)
     * Returns contextual similar songs for a target track.
     */
    suspend fun getRelatedSongs(videoId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "related_songs_v2_$videoId"
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
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)

        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: ""
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: ""

        val seedMeta = if (seedTitle.isNotEmpty()) {
            val seedItem = VideoItem(videoId, seedTitle, seedAuthor)
            RecommendationManager.getCachedOrInferredMetadata(db, seedItem)
        } else null

        var profileCache: RecommendationManager.TasteProfile? = null
        suspend fun getProfile(): RecommendationManager.TasteProfile {
            return profileCache ?: RecommendationManager.buildTasteProfile(db).also { profileCache = it }
        }

        if (pool.isEmpty()) {
            Log.d(TAG, "getWatchNextRadio empty for $videoId, using TasteDNA fallback queries...")
            if (seedMeta != null && seedTitle.isNotEmpty()) {
                val profile = getProfile()
                val knownArtists = profile.topArtists.map { it.first }.toSet() + profile.skippedArtists + seedAuthor
                val yr = java.time.LocalDate.now().year

                val queries = try {
                    RecommendationManager.buildAcousticQueriesForSeed(recDb, seedMeta, knownArtists, db)
                } catch (_: Exception) {
                    listOf(
                        "$seedAuthor official popular",
                        "${seedMeta.genre} similar hits $yr",
                        "$seedTitle similar music"
                    )
                }
                pool = fetchCandidatesFromQueries(queries)
            } else {
                Log.w(TAG, "No metadata available for fallback seed videoId '$videoId'. Doing raw search fallback.")
                pool = InnerTube.search(videoId)
            }
        }

        // Apply scoring and similarity filters
        val scored = ArrayList<Pair<VideoItem, Double>>()
        val profile = getProfile()

        for (item in pool) {
            if (item.videoId == videoId) continue
            if (RecommendationManager.isCompilationTrack(item.title, item.durationText)) continue
            if (RecommendationManager.isNonMusicVideo(item.title, item.author)) continue
            if (RecommendationManager.isUnofficialContent(item.title, item.author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(item.author.lowercase(Locale.ROOT))) continue

            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item)

            if (!meta.isOfficial) continue
            if (seedMeta != null && meta.language != seedMeta.language) continue

            var totalSimilarity = 0.5
            if (seedMeta != null) {
                val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.0
                val artistScore = if (meta.artist.lowercase() == seedMeta.artist.lowercase()) {
                    1.0
                } else if (RecommendationManager.isSimilarArtist(meta.artist, seedMeta.artist)) {
                    0.6
                } else {
                    0.0
                }
                val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
                val langScore = if (meta.language == seedMeta.language) 1.0 else 0.0

                val energyDelta = Math.abs(meta.energy - seedMeta.energy)
                val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)

                val bpm1 = meta.tempo.toDouble()
                val bpm2 = seedMeta.tempo.toDouble()
                val effectiveTempoDelta = minOf(
                    Math.abs(bpm1 - bpm2),
                    Math.abs(bpm1 - bpm2 * 2.0),
                    Math.abs(bpm1 * 2.0 - bpm2),
                    Math.abs(bpm1 / 2.0 - bpm2),
                    Math.abs(bpm1 - bpm2 / 2.0)
                )
                val tempoScore = Math.cos((effectiveTempoDelta / 60.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

                totalSimilarity = genreScore * 0.25 + artistScore * 0.20 + moodScore * 0.15 + langScore * 0.15 + energyScore * 0.125 + tempoScore * 0.125
            } else {
                totalSimilarity = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            }

            val officialBonus = if (meta.isOfficial) 0.15 else 0.0
            scored.add(item to (totalSimilarity + officialBonus))
        }

        val sorted = scored.sortedByDescending { it.second }.map { it.first }

        val selected = ArrayList<VideoItem>()
        val artistCounts = HashMap<String, Int>()
        val seedAuthorLower = seedAuthor.lowercase(Locale.ROOT)
        for (item in sorted) {
            if (selected.size >= 12) break
            val author = item.author.lowercase(Locale.ROOT)
            val count = artistCounts[author] ?: 0
            val cap = if (author == seedAuthorLower) 1 else 3
            if (count < cap) {
                selected.add(item)
                artistCounts[author] = count + 1
            }
        }

        if (selected.isNotEmpty()) {
            saveVideoItems(cacheKey, selected)
        }
        selected
    }

    private data class SmartQueueCandidate(
        val item: VideoItem,
        val meta: SongMetadata,
        val seedSimilarity: Double,
        val tasteScore: Double,
        val behaviorScore: Double,
        val recentPenalty: Double,
        val baseScore: Double
    )

    suspend fun getSongRadio(videoId: String, fallbackTitle: String = "", fallbackAuthor: String = "", currentQueue: List<VideoItem> = emptyList()): List<VideoItem> = withContext(Dispatchers.IO) {
        val cacheKey = "song_radio_v6_$videoId"
        val cached = loadVideoItems(cacheKey, allowStale = false)
        if (cached != null && cached.isNotEmpty()) {
            return@withContext cached
        }

        val deferred = inFlightRadios.computeIfAbsent(videoId) {
            async(Dispatchers.IO) {
                try {
                    getSongRadioInternal(videoId, fallbackTitle, fallbackAuthor, currentQueue)
                } finally {
                    inFlightRadios.remove(videoId)
                }
            }
        }
        deferred.await()
    }

    private suspend fun getSongRadioInternal(videoId: String, fallbackTitle: String, fallbackAuthor: String, currentQueue: List<VideoItem>): List<VideoItem> {
        val cacheKey = "song_radio_v6_$videoId"
        val cached = loadVideoItems(cacheKey, allowStale = false)
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Smart Queue cache hit for seed=$videoId size=${cached.size}")
            return cached
        }

        Log.d(TAG, "Generating Multi-Angle Smart Queue for seed track $videoId...")

        val pool = mutableListOf<VideoItem>()
        
        val historyEntry = db.historyDao().getAllHistory().firstOrNull { it.videoId == videoId }
        val likedEntry = db.likedSongDao().getAll().firstOrNull { it.videoId == videoId }
        val signalEntry = db.interactionSignalDao().get(videoId)

        val seedTitle = historyEntry?.title ?: likedEntry?.title ?: signalEntry?.title ?: fallbackTitle
        val seedAuthor = historyEntry?.author ?: likedEntry?.author ?: signalEntry?.author ?: fallbackAuthor

        val profile = RecommendationManager.buildTasteProfile(db)
        val seedMeta = RecommendationManager.getCachedOrInferredMetadata(
            db,
            VideoItem(videoId, seedTitle.ifBlank { "Track" }, seedAuthor.ifBlank { "Artist" }),
            context
        )
        val online = isOnline()

        if (online) {
            Log.d(TAG, "Device ONLINE. Executing 4-Angle Candidate Generation Pipeline in parallel.")

            coroutineScope {
                // Angle 1: YouTube Music Related + Watch Next Radio (capped to avoid starving other angles)
                val ytJob = async {
                    val list = mutableListOf<VideoItem>()
                    try {
                        val related = fetchYtRelatedForSeed(videoId).orEmpty()
                        list.addAll(related.take(15))
                        if (list.size < 10) {
                            val radioTracks = InnerTube.getWatchNextRadio(videoId)
                            list.addAll(radioTracks.take(10))
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Angle 1 (YTM) error: ${e.message}")
                    }
                    list
                }

                // Angle 2: Last.fm Similar Tracks (Direct track/artist graph)
                val lastFmJob = async {
                    val list = mutableListOf<VideoItem>()
                    try {
                        val simPairs = fetchLastFmSimilarTracks(seedAuthor, seedTitle)
                        if (simPairs.isNotEmpty()) {
                            coroutineScope {
                                val searchJobs = simPairs.take(6).map { (art, tit) ->
                                    async {
                                        try {
                                            InnerTube.search("$art $tit official").firstOrNull {
                                                !RecommendationManager.isCompilationTrack(it.title, it.durationText)
                                            }
                                        } catch (_: Exception) { null }
                                    }
                                }
                                list.addAll(searchJobs.awaitAll().filterNotNull())
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Angle 2 (Last.fm) error: ${e.message}")
                    }
                    list
                }

                // Angle 3: Sub-Genre, Mood & Era Queries
                val subgenreJob = async {
                    val list = mutableListOf<VideoItem>()
                    try {
                        val yr = java.time.LocalDate.now().year
                        val genreLower = seedMeta.genre.lowercase(Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
                        val queries = mutableListOf<String>()
                        if (seedAuthor.isNotBlank()) {
                            queries.add("$seedAuthor official songs")
                        }
                        queries.add("$genreLower hits $yr")
                        queries.add("${seedMeta.mood.lowercase()} $genreLower music")

                        coroutineScope {
                            val qJobs = queries.take(3).map { q ->
                                async {
                                    try {
                                        InnerTube.search(q).take(5)
                                    } catch (_: Exception) { emptyList() }
                                }
                            }
                            list.addAll(qJobs.awaitAll().flatten())
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Angle 3 (Subgenre) error: ${e.message}")
                    }
                    list
                }

                // Angle 4: Local 500K DB / Spotify Acoustic Neighbors (BPM/Energy cluster)
                val acousticJob = async {
                    val list = mutableListOf<VideoItem>()
                    try {
                        val neighbors = RecommendationManager.findAcousticallySimilarTracks(
                            recDb, seedTitle, seedAuthor, setOf(seedAuthor), limit = 5
                        )
                        if (neighbors.isNotEmpty()) {
                            coroutineScope {
                                val aJobs = neighbors.take(4).map { trk ->
                                    async {
                                        try {
                                            InnerTube.search("${trk.title} ${trk.artist} official").firstOrNull()
                                        } catch (_: Exception) { null }
                                    }
                                }
                                list.addAll(aJobs.awaitAll().filterNotNull())
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Angle 4 (Acoustic) error: ${e.message}")
                    }
                    list
                }

                val (ytList, lastFmList, subgenreList, acousticList) = awaitAll(ytJob, lastFmJob, subgenreJob, acousticJob)
                pool.addAll(ytList)
                pool.addAll(lastFmList)
                pool.addAll(subgenreList)
                pool.addAll(acousticList)
            }
        } else {
            Log.d(TAG, "Device is OFFLINE. Using downloaded songs for radio.")
            val downloads = db.downloadDao().getByStatus("completed")
            val downloadedItems = downloads.map { 
                VideoItem(it.videoId, it.title, it.author, it.durationText) 
            }
            val available = downloadedItems
                .filter { it.videoId != videoId }
                .sortedWith(compareBy<VideoItem>({ it.author.lowercase(Locale.ROOT) }, { it.title.lowercase(Locale.ROOT) }, { it.videoId }))
            pool.addAll(available)
        }

        val history = db.historyDao().getAllHistory()
        val recentlyPlayedIds = history.take(20).map { it.videoId }.toSet()
        val recentlyPlayedTitles = history.take(20).map { RecommendationManager.normalizeTitle(it.title) }.toSet()
        val signals = db.interactionSignalDao().getAll().associateBy { it.videoId }
        val currentQueueArtists = currentQueue.map { normalizeQueueArtist(it.author) }.toSet()

        // De-duplicate before ranking. A related track can arrive from YTM,
        // radio, Last.fm and search under different result objects.
        val dedupedPool = pool.distinctBy {
            "${RecommendationManager.normalizeTitle(it.title)}|${normalizeQueueArtist(it.author)}"
        }
        val duplicateRemoved = pool.size - dedupedPool.size

        // Step-by-step filtering with diagnostics
        // Stage 1: isCompilationTrack
        val nonCompilation = dedupedPool.filter { item ->
            !RecommendationManager.isCompilationTrack(item.title, item.durationText)
        }
        val afterCompilationCount = nonCompilation.size

        // Stage 2: isNonMusicVideo
        val musicVideos = nonCompilation.filter { item ->
            !RecommendationManager.isNonMusicVideo(item.title, item.author)
        }
        val afterNonMusicVideoCount = musicVideos.size

        // Stage 3: isUnofficialContent
        val officialContent = musicVideos.filter { item ->
            !RecommendationManager.isUnofficialContent(item.title, item.author)
        }
        val afterUnofficialContentCount = officialContent.size

        // Stage 4: isOfficial & metadata extraction (capped to 20 upfront to prevent CPU/IO spikes)
        val initialCandidates = officialContent.take(20)
        var officialMetaPairs = initialCandidates.map { item ->
            val meta = RecommendationManager.getCachedOrInferredMetadata(db, item, context)
            item to meta
        }.filter { (_, meta) ->
            meta.isOfficial
        }
        if (officialMetaPairs.size < 5 && initialCandidates.size >= 5) {
            Log.d(TAG, "SmartQueue: Relaxing isOfficial filter (yielded ${officialMetaPairs.size}/${initialCandidates.size})")
            officialMetaPairs = initialCandidates.map { it to RecommendationManager.getCachedOrInferredMetadata(db, it, context) }
        }
        val afterOfficialCount = officialMetaPairs.size

        // Stage 5: skipped-track/artist & recently played filtering (with safety net)
        var recentlyPlayedRemoved = 0
        var afterUserFilters = officialMetaPairs.filter { (item, _) ->
            if (item.videoId == videoId) return@filter false
            if (profile.skippedTracks.contains(item.videoId)) return@filter false
            if (profile.skippedArtists.any { skipped -> normalizeQueueArtist(skipped) == normalizeQueueArtist(item.author) }) return@filter false

            val signal = signals[item.videoId]
            val recentlyPlayed = item.videoId in recentlyPlayedIds ||
                RecommendationManager.normalizeTitle(item.title) in recentlyPlayedTitles
            val strongRepeatIntent = signal?.isLiked == true ||
                (signal?.repeatCount ?: 0) > 0 ||
                (signal?.completeCount ?: 0) >= 2
            if (recentlyPlayed && !strongRepeatIntent) {
                recentlyPlayedRemoved++
                return@filter false
            }
            true
        }
        if (afterUserFilters.size < 5 && officialMetaPairs.size >= 5) {
            Log.d(TAG, "SmartQueue: Relaxing recently-played filter (yielded ${afterUserFilters.size}/${officialMetaPairs.size})")
            afterUserFilters = officialMetaPairs.filter { (item, _) -> item.videoId != videoId }
        }
        val afterUserFilterCount = afterUserFilters.size

        // Stage 6: isTooSimilar to seed
        var afterSimilarity = afterUserFilters.filter { (item, _) ->
            if (seedTitle.isNotEmpty() && RecommendationManager.isTooSimilar(seedTitle, item.title)) {
                false
            } else {
                true
            }
        }
        if (afterSimilarity.isEmpty() && afterUserFilters.isNotEmpty()) {
            afterSimilarity = afterUserFilters
        }
        val afterTooSimilarCount = afterSimilarity.size

        // Stage 7: Language filtering
        // Priority for Smart Queue language:
        // 1. Seed track's detected language (if known != "Unknown")
        // 2. Candidate pool majority language (from YouTube Radio/Related recommendations for this song)
        // 3. User's overall profile language (only if candidate pool doesn't indicate a language)
        // 4. "Unknown"
        val candidateMajorityLang = afterSimilarity.groupingBy { it.second.language }
            .eachCount()
            .filter { it.key.isNotBlank() && it.key != "Unknown" }
            .maxByOrNull { it.value }?.key

        val profileLanguage = profile.topLanguages.firstOrNull()?.first?.takeIf { it.isNotBlank() && it != "Unknown" }
        val queueLanguage = seedMeta?.language?.takeIf { it.isNotBlank() && it != "Unknown" }
            ?: candidateMajorityLang
            ?: profileLanguage
            ?: "Unknown"

        var languageFilteredPairs = if (queueLanguage == "Unknown") {
            afterSimilarity
        } else {
            afterSimilarity.filter { (_, meta) ->
                RecommendationManager.isCompatibleQueueLanguage(meta.language, queueLanguage, allowUnknown = true)
            }
        }

        // SAFETY NET: Never let language filtering collapse a healthy candidate pool to 0!
        if (languageFilteredPairs.size < 5 && afterSimilarity.size >= 5) {
            Log.d(TAG, "SmartQueue: Strict language filtering on '$queueLanguage' yielded only ${languageFilteredPairs.size}/${afterSimilarity.size} candidates. Relaxing language filter to maintain full Smart Queue.")
            languageFilteredPairs = afterSimilarity
        }
        val afterLanguageCount = languageFilteredPairs.size
        val languageRemoved = afterSimilarity.size - languageFilteredPairs.size

        val now = System.currentTimeMillis()
        val recentArtistCounts = history.take(20)
            .groupingBy { normalizeQueueArtist(it.author) }
            .eachCount()
        fun behaviorScore(item: VideoItem): Double {
            val signal = signals[item.videoId]
            val ageMs = (now - (signal?.lastPlayedAt ?: 0L)).coerceAtLeast(0L)
            val decay = if (signal == null || signal.lastPlayedAt <= 0L) 0.0
            else Math.exp(-ageMs.toDouble() / (14.0 * 24.0 * 60.0 * 60.0 * 1000.0))
            val positive = (signal?.completeCount ?: 0) * 0.20 +
                (signal?.repeatCount ?: 0) * 0.35 +
                (if (signal?.isLiked == true) 0.55 else 0.0) +
                (if (signal?.isDownloaded == true) 0.10 else 0.0)
            val negative = (signal?.skip20sCount ?: 0) * 0.55 +
                (signal?.skipCount ?: 0) * 0.20
            val recentArtistAffinity = (recentArtistCounts[normalizeQueueArtist(item.author)] ?: 0) * 0.04
            return ((positive - negative) * decay + recentArtistAffinity).coerceIn(-1.0, 1.0)
        }

        fun tempoSimilarity(first: Int, second: Int): Double {
            val bpm1 = first.toDouble()
            val bpm2 = second.toDouble()
            val effectiveDelta = minOf(
                Math.abs(bpm1 - bpm2), Math.abs(bpm1 - bpm2 * 2.0),
                Math.abs(bpm1 * 2.0 - bpm2), Math.abs(bpm1 / 2.0 - bpm2),
                Math.abs(bpm1 - bpm2 / 2.0)
            )
            return Math.cos((effectiveDelta / 60.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5
        }

        val scoredCandidates = languageFilteredPairs.map { (item, meta) ->
            val seedSimilarity = if (seedMeta != null) {
                val genre = if (meta.genre.equals(seedMeta.genre, ignoreCase = true)) 1.0 else 0.0
                val mood = if (meta.mood.equals(seedMeta.mood, ignoreCase = true)) 1.0 else 0.25
                val energy = (1.0 - Math.abs(meta.energy - seedMeta.energy)).coerceIn(0.0, 1.0)
                val tempo = tempoSimilarity(meta.tempo, seedMeta.tempo)
                val artist = when {
                    normalizeQueueArtist(item.author) == normalizeQueueArtist(seedAuthor) -> 1.0
                    RecommendationManager.isSimilarArtist(item.author, seedAuthor) -> 0.82
                    else -> 0.35
                }
                genre * 0.24 + mood * 0.14 + energy * 0.24 + tempo * 0.23 + artist * 0.15
            } else {
                0.35
            }
            val tasteScore = RecommendationManager.calculateTasteSimilarity(meta, profile.tasteDNA)
            val behavior = behaviorScore(item)
            val recentPenalty = if (item.videoId in recentlyPlayedIds) -0.18 else 0.0
            val currentQueuePenalty = if (normalizeQueueArtist(item.author) in currentQueueArtists) -0.12 else 0.0
            val fatiguePenalty = if (RecentlySuggestedTracker.isRecentlySuggested(item.videoId)) -0.45 else 0.0
            val artistFatiguePenalty = (RecentlySuggestedTracker.getArtistSuggestionCount(item.author) * -0.06).coerceIn(-0.30, 0.0)
            val officialBonus = if (meta.isOfficial) 0.05 else 0.0
            val baseScore = seedSimilarity * 0.50 + tasteScore * 0.28 + behavior * 0.12 +
                recentPenalty + currentQueuePenalty + fatiguePenalty + artistFatiguePenalty + officialBonus
            SmartQueueCandidate(item, meta, seedSimilarity, tasteScore, behavior, recentPenalty, baseScore)
        }
        val finalScoredCandidatesCount = scoredCandidates.size

        val sequenced = ArrayList<VideoItem>()
        val remaining = ArrayList(scoredCandidates)
        val artistCount = mutableMapOf<String, Int>()
        var lastArtist = normalizeQueueArtist(seedAuthor)
        val seedAuthorLower = normalizeQueueArtist(seedAuthor)
        var artistCapRemoved = 0

        fun positionScore(candidate: SmartQueueCandidate, position: Int): Double {
            // Close continuity first, then gradually hand more weight to the
            // user's taste and controlled discovery.
            val seedWeight = when {
                position < 3 -> 0.64
                position < 7 -> 0.48
                else -> 0.34
            }
            val tasteWeight = when {
                position < 3 -> 0.20
                position < 7 -> 0.32
                else -> 0.44
            }
            val discoveryBonus = if (position >= 7 &&
                !RecommendationManager.isSimilarArtist(seedAuthor, candidate.item.author) &&
                normalizeQueueArtist(candidate.item.author) != seedAuthorLower) 0.035 else 0.0
            return candidate.seedSimilarity * seedWeight +
                candidate.tasteScore * tasteWeight +
                candidate.behaviorScore * 0.12 +
                candidate.baseScore * 0.08 + discoveryBonus
        }

        // Pass 1: Strict spacing and artist caps
        while (remaining.isNotEmpty() && sequenced.size < 20) {
            val position = sequenced.size
            val eligible = remaining.filter { candidate ->
                val artist = normalizeQueueArtist(candidate.item.author)
                val count = artistCount[artist] ?: 0
                val cap = if (artist == seedAuthorLower) 1 else 2
                val lastIndex = sequenced.indexOfLast { normalizeQueueArtist(it.author) == artist }
                val gap = if (lastIndex < 0) Int.MAX_VALUE else sequenced.size - lastIndex
                count < cap &&
                    (gap >= 3 || artist != lastArtist) &&
                    sequenced.none { RecommendationManager.isTooSimilar(it.title, candidate.item.title) }
            }
            if (eligible.isEmpty()) {
                break
            }

            val next = eligible.sortedWith(
                compareByDescending<SmartQueueCandidate> { positionScore(it, position) }
                    .thenBy { RecommendationManager.normalizeTitle(it.item.title) }
                    .thenBy { normalizeQueueArtist(it.item.author) }
                    .thenBy { it.item.videoId }
            ).first()

            Log.d(
                TAG,
                "Smart Queue pick=${position + 1} title='${next.item.title}' artist='${next.item.author}' " +
                    "seed=${"%.3f".format(Locale.ROOT, next.seedSimilarity)} " +
                    "taste=${"%.3f".format(Locale.ROOT, next.tasteScore)} " +
                    "behavior=${"%.3f".format(Locale.ROOT, next.behaviorScore)} " +
                    "final=${"%.3f".format(Locale.ROOT, positionScore(next, position))}"
            )

            sequenced.add(next.item)
            val artist = normalizeQueueArtist(next.item.author)
            lastArtist = artist
            artistCount[artist] = (artistCount[artist] ?: 0) + 1
            remaining.remove(next)
            remaining.removeAll {
                RecommendationManager.isTooSimilar(next.item.title, it.item.title)
            }
        }

        // Pass 2: Relaxed spacing if queue size is small (< 10) and candidates remain
        if (sequenced.size < 10 && remaining.isNotEmpty()) {
            while (remaining.isNotEmpty() && sequenced.size < 20) {
                val position = sequenced.size
                val eligible = remaining.filter { candidate ->
                    val artist = normalizeQueueArtist(candidate.item.author)
                    val count = artistCount[artist] ?: 0
                    val cap = if (artist == seedAuthorLower) 2 else 4
                    val lastIndex = sequenced.indexOfLast { normalizeQueueArtist(it.author) == artist }
                    val gap = if (lastIndex < 0) Int.MAX_VALUE else sequenced.size - lastIndex
                    count < cap &&
                        gap >= 1 &&
                        sequenced.none { RecommendationManager.isTooSimilar(it.title, candidate.item.title) }
                }
                if (eligible.isEmpty()) {
                    artistCapRemoved += remaining.size
                    break
                }

                val next = eligible.sortedWith(
                    compareByDescending<SmartQueueCandidate> { positionScore(it, position) }
                        .thenBy { RecommendationManager.normalizeTitle(it.item.title) }
                        .thenBy { it.item.videoId }
                ).first()

                sequenced.add(next.item)
                val artist = normalizeQueueArtist(next.item.author)
                lastArtist = artist
                artistCount[artist] = (artistCount[artist] ?: 0) + 1
                remaining.remove(next)
                remaining.removeAll {
                    RecommendationManager.isTooSimilar(next.item.title, it.item.title)
                }
            }
        }

        // Pass 3: Fill remaining slots if queue is still small (< 10)
        if (sequenced.size < 10 && remaining.isNotEmpty()) {
            for (candidate in remaining.toList()) {
                if (sequenced.none { RecommendationManager.isTooSimilar(it.title, candidate.item.title) }) {
                    sequenced.add(candidate.item)
                    remaining.remove(candidate)
                }
                if (sequenced.size >= 15) break
            }
        }

        // Fallback 1: if the online/offline pool is empty, use history/liked songs
        if (sequenced.isEmpty()) {
            Log.d(TAG, "Pool empty, falling back to history/liked songs.")
            val fallback = mutableListOf<VideoItem>()
            fallback.addAll(history.take(30).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            fallback.addAll(db.likedSongDao().getAll().take(30).map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
            val filtered = fallback
                .filter { it.videoId != videoId }
                .distinctBy { "${RecommendationManager.normalizeTitle(it.title)}|${normalizeQueueArtist(it.author)}" }
                .map { it to RecommendationManager.getCachedOrInferredMetadata(db, it, context) }
                .filter { (_, meta) -> meta.isOfficial && RecommendationManager.isCompatibleQueueLanguage(meta.language, queueLanguage, allowUnknown = true) }
                .sortedWith(compareByDescending<Pair<VideoItem, SongMetadata>> {
                    RecommendationManager.calculateTasteSimilarity(it.second, profile.tasteDNA)
                }.thenBy { RecommendationManager.normalizeTitle(it.first.title) })
                .map { it.first }
            sequenced.addAll(filtered.take(15))
        }

        // Fallback 2 (Emergency): Direct search for seed artist / title to ensure queue is NEVER empty
        if (sequenced.isEmpty()) {
            Log.d(TAG, "SmartQueue: Still empty, executing emergency direct search...")
            try {
                val query = if (seedAuthor.isNotBlank() && seedAuthor != "Artist") {
                    "$seedAuthor official music"
                } else if (seedTitle.isNotBlank() && seedTitle != "Track") {
                    "$seedTitle songs"
                } else {
                    "trending music official"
                }
                val directResults = InnerTube.search(query).take(15)
                for (item in directResults) {
                    if (item.videoId != videoId && sequenced.none { it.videoId == item.videoId || RecommendationManager.isTooSimilar(it.title, item.title) }) {
                        sequenced.add(item)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Emergency search failed: ${e.message}")
            }
        }

        Log.d(
            TAG,
            "SmartQueue: seed=$videoId title='$seedTitle' artist='$seedAuthor' language='$queueLanguage' " +
                "totalRaw=${pool.size} unique=${dedupedPool.size} " +
                "afterCompilation=$afterCompilationCount afterNonMusicVideo=$afterNonMusicVideoCount " +
                "afterUnofficialContent=$afterUnofficialContentCount afterOfficial=$afterOfficialCount " +
                "afterUserFilter=$afterUserFilterCount afterTooSimilar=$afterTooSimilarCount " +
                "afterLanguage=$afterLanguageCount " +
                "removedDuplicates=$duplicateRemoved removedRecent=$recentlyPlayedRemoved " +
                "removedLanguage=$languageRemoved removedArtistCap=$artistCapRemoved " +
                "finalScored=$finalScoredCandidatesCount finalSelected=${sequenced.size}"
        )
        Log.d(
            TAG,
            "Smart Queue selected=" + sequenced.joinToString(" | ") {
                "${it.title} — ${it.author}"
            }
        )

        if (sequenced.isNotEmpty()) {
            RecentlySuggestedTracker.recordSuggestions(sequenced)
            saveVideoItems(cacheKey, sequenced)
        }
        return sequenced
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
        val cachedJson = loadCacheStr(cacheKey, allowStale = true)
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
