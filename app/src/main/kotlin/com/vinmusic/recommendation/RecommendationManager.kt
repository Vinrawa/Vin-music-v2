package com.vinmusic.recommendation

import android.content.Context
import android.util.Log
import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SongMetadata(
    val title: String,
    val artist: String,
    val genre: String,
    val mood: String,
    val language: String,
    val energy: Double, // 0.0 to 1.0
    val tempo: Int,     // BPM
    val year: Int,
    val isOfficial: Boolean,
    val sourceQuality: String
)

data class RecommendedSong(
    val videoItem: VideoItem,
    val score: Double,
    val source: String,
    val reason: String
)

data class CachedSection(
    val title: String,
    val songs: List<RecommendedSong>
)

data class SpotifyMix(
    val id: String,
    val title: String,
    val description: String,
    val songs: List<RecommendedSong>,
    val gradientStartHex: String,
    val gradientEndHex: String
)

object RecommendationManager {
    private const val TAG = "VIN_REC"
    
    // Cache recommendations for 15 minutes to prevent frequent network requests
    private const val CACHE_EXPIRY_MS = 15 * 60 * 1000L
    private var lastCacheTime: Long = 0L
    private val cachedSections = ArrayList<Pair<String, List<RecommendedSong>>>()
    
    private var lastMixCacheTime: Long = 0L
    private val cachedMixes = ArrayList<SpotifyMix>()
    
    private val gson = Gson()

    data class GenreMixConfig(
        val description: String,
        val queries: List<String>,
        val gradientStartHex: String,
        val gradientEndHex: String,
        val targetMood: String
    )

    val GENRE_CONFIGS = mapOf(
        "Lofi" to GenreMixConfig(
            description = "Your personal sanctuary of calm. Lofi, acoustic indie, and soft chill melodies.",
            queries = listOf("hindi soft indie aesthetic", "acoustic lofi relax", "aesthetic bedtime chill"),
            gradientStartHex = "0xFF3B0764", // Deep Violet
            gradientEndHex = "0xFF1E1B4B",   // Dark Indigo
            targetMood = "Chill/Relaxed"
        ),
        "Rap/Hip-Hop" to GenreMixConfig(
            description = "Get moving with high-tempo rap, energetic workout tracks, and modern hip hop.",
            queries = listOf("energetic rap hits workout", "modern hip hop playlist popular", "trap music gym workout"),
            gradientStartHex = "0xFF7F1D1D", // Deep Crimson
            gradientEndHex = "0xFF450A0A",   // Dark Red
            targetMood = "Energetic"
        ),
        "Bollywood" to GenreMixConfig(
            description = "Melodious romantic soundtracks, Bollywood hits, and warm acoustic love songs.",
            queries = listOf("bollywood romantic hit tracks", "arijit singh sweet love audio", "hindi slow romantic ost"),
            gradientStartHex = "0xFF065F46", // Dark Emerald
            gradientEndHex = "0xFF022C22",   // Sage Black
            targetMood = "Romantic"
        ),
        "Punjabi Folk" to GenreMixConfig(
            description = "High-energy Punjabi beats, bhangra hits, and upbeat modern releases.",
            queries = listOf("upbeat punjabi dance bhangra", "karan aujla sidhu moose wala hits", "popular punjabi music charts"),
            gradientStartHex = "0xFFB45309", // Amber
            gradientEndHex = "0xFF78350F",   // Warm Orange
            targetMood = "Energetic"
        ),
        "Pop" to GenreMixConfig(
            description = "An upbeat collection of popular hits, dance anthems, and modern pop releases.",
            queries = listOf("popular pop hits charts", "dance pop anthems radio", "fresh upbeat pop music"),
            gradientStartHex = "0xFF1E3A8A", // Deep Blue
            gradientEndHex = "0xFF0F172A",   // Dark Slate
            targetMood = "Happy"
        ),
        "Indie" to GenreMixConfig(
            description = "Warm acoustic indie, singer-songwriter gems, and fresh independent sounds.",
            queries = listOf("hindi indie acoustic aesthetic", "indie folk playlist viral", "prateek kuhad anuv jain style"),
            gradientStartHex = "0xFF0F766E", // Deep Teal
            gradientEndHex = "0xFF115E59",   // Medium Jade
            targetMood = "Chill/Relaxed"
        ),
        "Rock" to GenreMixConfig(
            description = "Heavy guitar solos, classic rock anthems, and high-voltage grunge energy.",
            queries = listOf("popular rock workout music", "heavy grunge rock classics", "linkin park style rock music"),
            gradientStartHex = "0xFF1F2937", // Slate Gray
            gradientEndHex = "0xFF0F172A",   // Midnight Black
            targetMood = "Energetic"
        )
    )


    private fun saveToDisk(ctx: Context, sections: List<Pair<String, List<RecommendedSong>>>) {
        try {
            val prefs = ctx.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
            val list = sections.map { CachedSection(it.first, it.second) }
            val json = gson.toJson(list)
            prefs.edit()
                .putString("cached_sections", json)
                .putLong("cached_time", System.currentTimeMillis())
                .apply()
            Log.d(TAG, "Saved recommendations to disk cache.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save recommendations to disk: ${e.message}")
        }
    }

    private fun loadFromDisk(ctx: Context): List<Pair<String, List<RecommendedSong>>>? {
        try {
            val prefs = ctx.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
            val json = prefs.getString("cached_sections", null) ?: return null
            val time = prefs.getLong("cached_time", 0L)
            
            val type = object : TypeToken<List<CachedSection>>() {}.type
            val list: List<CachedSection> = gson.fromJson(json, type) ?: return null
            
            if (list.isEmpty()) {
                Log.w(TAG, "Disk cache has 0 sections — treating as invalid.")
                prefs.edit().remove("cached_sections").remove("cached_time").apply()
                return null
            }

            val totalSongs = list.sumOf { it.songs.size }
            if (totalSongs == 0) {
                Log.w(TAG, "Disk cache has sections but 0 songs — treating as invalid.")
                prefs.edit().remove("cached_sections").remove("cached_time").apply()
                return null
            }
            
            lastCacheTime = time
            Log.d(TAG, "Loaded ${list.size} sections ($totalSongs songs) from disk cache.")
            return list.map { Pair(it.title, it.songs) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load recommendations from disk: ${e.message}")
            return null
        }
    }

    // User Taste Profile Representation containing acoustic DNA vector
    data class TasteDNA(
        val targetEnergy: Double,
        val targetTempo: Int,
        val preferredGenres: Map<String, Double>,
        val preferredMoods: Map<String, Double>,
        val preferredLanguages: Map<String, Double>,
        val preferredArtists: Map<String, Double> = emptyMap()
    )

    data class TasteProfile(
        val topArtists: List<Pair<String, Double>>, // Artist name to affinity score
        val topGenres: List<Pair<String, Double>>,   // Genre to affinity score
        val topMoods: List<Pair<String, Double>>,    // Mood to affinity score
        val topLanguages: List<Pair<String, Double>>, // Language to affinity score
        val favoriteTracks: Set<String>,            // Video IDs
        val skippedTracks: Set<String>,             // Video IDs
        val skippedArtists: Set<String>,           // Artist names
        val downloadedTracks: List<InteractionSignal>,
        val likedTracks: List<InteractionSignal>,
        val tasteDNA: TasteDNA
    )

    fun invalidateCache(ctx: Context? = null) {
        Log.d(TAG, "Invalidating recommendation cache.")
        lastCacheTime = 0L
        lastMixCacheTime = 0L
        synchronized(cachedSections) {
            cachedSections.clear()
        }
        synchronized(cachedMixes) {
            cachedMixes.clear()
        }
        ctx?.let {
            try {
                it.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
            } catch (_: Exception) {}
        }
    }

    /**
     * Normalizes a song title to clean up YouTube search fluff (brackets, punctuation, common stop-words).
     */
    fun normalizeTitle(title: String): String {
        var text = title.lowercase(Locale.ROOT)
        text = text.replace(Regex("\\([^)]*\\)"), "")
        text = text.replace(Regex("\\[[^]]*\\]"), "")
        text = text.replace(Regex("[^a-zA-Z0-9\\s]"), "")
        
        val stopWords = listOf(
            "official", "audio", "video", "lyrics", "lyric", "explained", "meaning", 
            "reaction", "remix", "cover", "instrumental", "karaoke", "slowed", 
            "reverb", "nightcore", "live", "interview", "story", "documentary",
            "hd", "4k", "genius", "unplugged", "acoustic"
        )
        for (word in stopWords) {
            text = text.replace(Regex("\\b$word\\b"), "")
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    fun normalizeArtistName(name: String): String {
        var clean = name.lowercase(Locale.ROOT).trim()
        clean = clean.replace(Regex("- topic$"), "").trim()
        clean = clean.replace(Regex("\\bvevo\\b"), "").trim()
        clean = clean.replace(Regex("[^a-z0-9\\s]"), "")
        clean = clean.replace(Regex("\\s+"), " ").trim()
        return clean
    }

    val SIMILAR_ARTISTS_MAP = mapOf(
        "j. cole" to listOf("kendrick lamar", "drake", "jid", "cordae", "joey bada\$\$", "kanye west"),
        "j cole" to listOf("kendrick lamar", "drake", "jid", "cordae", "joey bada\$\$", "kanye west"),
        "kendrick lamar" to listOf("j. cole", "drake", "travis scott", "21 savage", "baby keem", "a\$ap rocky"),
        "21 savage" to listOf("metro boomin", "future", "travis scott", "drake", "lil baby", "gunna"),
        "travis scott" to listOf("metro boomin", "don toliver", "kid cudi", "a\$ap rocky", "kanye west"),
        "drake" to listOf("j. cole", "kendrick lamar", "the weeknd", "future", "lil baby", "travis scott"),
        "arijit singh" to listOf("atif aslam", "jubin nautiyal", "shreya ghoshal", "pritam", "darshan raval"),
        "sidhu moose wala" to listOf("karan aujla", "diljit dosanjh", "shubh", "prem dhillon", "amrit maan"),
        "karan aujla" to listOf("sidhu moose wala", "diljit dosanjh", "shubh", "ap dhillon", "garry sandhu"),
        "diljit dosanjh" to listOf("karan aujla", "sidhu moose wala", "ap dhillon", "ammy virk", "shubh"),
        "shubh" to listOf("ap dhillon", "gurinder gill", "sidhu moose wala", "karan aujla", "diljit dosanjh"),
        "prateek kuhad" to listOf("anuv jain", "local train", "when chai met toast", "yellow diary"),
        "anuv jain" to listOf("prateek kuhad", "aditya rikhari", "mitraz", "local train", "osho jain"),
        "mitraz" to listOf("anuv jain", "aditya rikhari", "darshan raval", "zaeden", "prateek kuhad"),
        "the weeknd" to listOf("post malone", "khalid", "frank ocean", "sza", "brent faiyaz"),
        "taylor swift" to listOf("olivia rodrigo", "billie eilish", "sabrina carpenter", "ed sheeran"),
        "eminem" to listOf("dr. dre", "50 cent", "snoop dogg", "j. cole", "kendrick lamar"),
        "badshah" to listOf("raftaar", "yo yo honey singh", "king", "mc stan", "divine"),
        "divine" to listOf("naezy", "raftaar", "mc stan", "seedhe maut", "kr\$na"),
        "kr\$na" to listOf("raftaar", "seedhe maut", "divine", "emiway bantai", "young stunners")
    )

    fun isSimilarArtist(artist1: String, artist2: String): Boolean {
        val norm1 = normalizeArtistName(artist1)
        val norm2 = normalizeArtistName(artist2)
        if (norm1 == norm2) return true
        
        val list1 = SIMILAR_ARTISTS_MAP[norm1]
        if (list1 != null && list1.any { normalizeArtistName(it) == norm2 }) return true
        
        val list2 = SIMILAR_ARTISTS_MAP[norm2]
        if (list2 != null && list2.any { normalizeArtistName(it) == norm1 }) return true
        
        return false
    }

    fun getLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[s1.length][s2.length]
    }

    fun isTooSimilar(title1: String, title2: String): Boolean {
        val n1 = normalizeTitle(title1)
        val n2 = normalizeTitle(title2)
        if (n1 == n2) return true
        if (n1.contains(n2) || n2.contains(n1)) return true
        
        val maxLen = maxOf(n1.length, n2.length)
        if (maxLen == 0) return true
        val dist = getLevenshteinDistance(n1, n2)
        val similarity = 1.0 - (dist.toDouble() / maxLen.toDouble())
        return similarity > 0.70
    }

    fun isNonMusicVideo(title: String, author: String): Boolean {
        val titleLow = title.lowercase(Locale.ROOT)
        val authorLow = author.lowercase(Locale.ROOT)
        
        val blacklistTerms = listOf(
            "explained", "meaning", "reaction", "review", "breakdown", "story", "stories",
            "genius", "interview", "podcast", "documentary", "behind the scenes", "tutorial",
            "lesson", "news", "hidden meaning", "analysis", "funny", "parody", "reaction video",
            "reviewing", "lyrics", "lyric", "lyric video", "behind the song", "teaser", "promo",
            "leak", "shorts", "be like", "when you", "pov", "tiktok", "tiktoks", "meme", "memes",
            "comedy", "comedian", "prank", "vlog", "vlogs", "gaming", "gameplay", "roast", "standup",
            "rant", "compilation", "fails", "challenge", "unboxing", "how to play", "tutorial",
            "guitar cover lesson", "piano lesson", "behind the track", "1 hour", "1hour", "loop",
            "looped", "deep dive", "important song", "best song", "worst song", "top song",
            "irl", "dropped", "unofficial", "timeline", "beef", "drama", "funny moments", "fails"
        )
        
        for (term in blacklistTerms) {
            if (titleLow.contains(term) || authorLow.contains(term)) {
                return true
            }
        }
        
        val blacklistedChannelKeywords = listOf(
            "news", "tv", "comedy", "vlog", "gaming", "cricket", "tech", "review",
            "fitness", "food", "travel", "lifestyle", "kids", "cartoon", "meme",
            "unboxing", "essay", "analysis", "genius", "vlogger", "react", "reaction",
            "podcast", "podcasts", "interview", "interviews", "talks", "show", "entertainment",
            "media", "gamer", "games", "prank", "pranks", "roast", "roasts", "clips", "fails"
        )
        if (blacklistedChannelKeywords.any { authorLow.contains(it) }) {
            return true
        }
        return false
    }

    fun isCompilationTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        val compileKeywords = listOf(
            "top 10", "top 20", "top 30", "top 40", "top 50", "top songs", "best songs", 
            "best of", "greatest hits", "jukebox", "mashup", "compilation", "all songs", 
            "full album", "full songs", "collection", "nonstop", "non-stop", "playlist",
            "hits of", "hits mix", "best mix", "song collection"
        )
        return compileKeywords.any { t.contains(it) }
    }

    fun isCompilationTrack(title: String, durationText: String): Boolean {
        if (isCompilationTitle(title)) return true

        val t = title.lowercase(Locale.ROOT)
        if (t.contains("30 min") || t.contains("60 min") || t.contains("1 hour") || 
            t.contains("nonstop") || t.contains("jukebox") || t.contains("compilation") || 
            t.contains("full album") || t.contains("mashup") || t.contains("all songs") || 
            t.contains("mix video") || t.contains("dj mix") || t.contains("non stop")) {
            return true
        }
        
        val parts = durationText.split(":")
        if (parts.size >= 3) return true // Over an hour long
        if (parts.size == 2) {
            val mins = parts[0].toIntOrNull() ?: 0
            if (mins >= 15) return true // > 15 minutes is treated as a compile/mix
        }
        return false
    }

    fun isCorporateOrDistributorChannel(author: String): Boolean {
        val authorLow = author.lowercase(Locale.ROOT)
        val corporateLabels = listOf(
            "t-series", "tseries", "t series", "zee music", "zeemusic", "sony music", "sonymusic", 
            "yrf", "yash raj", "saregama", "tips official", "tips industries", "aditya music",
            "lahari music", "white hill", "geet mp3", "jass records", "desi music factory",
            "vyrl", "hombale", "think music", "single track studios", "mufasa music",
            "shemaroo", "venus", "dharma", "reliance entertainment", "eros", "speed records",
            "speedrecords", "t-series regional", "tseries regional", "lts music", "ltsmusic",
            "times music", "timesmusic", "t-series regional", "tseries regional", "t-series apna punjab",
            "tseries apna punjab", "t-series haryanvi", "wave music", "t-series bhakti sagar", "hmv",
            "ultra regional", "ultra bollywood", "ultra music", "saregama hum bhojpuri", "saregama ghazal",
            "saregama punjabi", "zee music south", "zee music classic", "desi melodies", "speed records punjabi",
            "goldmines", "b4u", "vintage", "venus movies", "mars", "dhruvan", "madura audio", "anand audio",
            "haripa music", "muzik 247", "manorama music", "satyam auditions", "millennium audits", "speed audio",
            "paattu"
        )
        return corporateLabels.any { authorLow.contains(it) }
    }

    fun isUnofficialContent(title: String, author: String): Boolean {
        if (isCorporateOrDistributorChannel(author)) return false
        
        val titleLow = title.lowercase(Locale.ROOT)
        val authorLow = author.lowercase(Locale.ROOT)
        val fullText = "$titleLow $authorLow"
        
        val unofficialKeywords = listOf(
            "remix", "slowed", "reverb", "live", "cover", "reaction", "meme", 
            "fan-made", "fanmade", "mashup", "instrumental", "karaoke", 
            "nightcore", "sped up", "speed up", "tribute", "parody", 
            "roast", "gaming", "unboxing", "1 hour", "1hour", "loop", "looped",
            "fan edit", "status video", "shorts", "reels", "tutorial", "bts",
            "behind the scenes", "leak", "unplugged", "reaction video"
        )
        return unofficialKeywords.any { fullText.contains(it) }
    }

    fun isOfficialArtistChannel(title: String, author: String): Boolean {
        val authorLow = author.lowercase(Locale.ROOT)
        if (isCorporateOrDistributorChannel(author)) return true
        if (authorLow.contains("- topic") || authorLow.contains("vevo")) return true
        
        val unofficialKeywords = listOf(
            "songweed", "mr. scrub", "lix", "grow music", "ridhi sound", "vdj royal",
            "biffin", "uproxx", "webworthy", "rdcworld", "longbeachgriffy", "vibes"
        )
        if (unofficialKeywords.any { authorLow.contains(it) }) return false
        
        return true
    }

    fun inferMetadata(item: VideoItem): SongMetadata {
        val title = item.title.lowercase(Locale.ROOT)
        val author = item.author.lowercase(Locale.ROOT)
        val fullText = "$title $author"

        // 1. Language Detection
        var language = "English"
        val punjabiKeywords = listOf("punjabi", "jatt", "munde", "kudi", "patiala", "punjab", "sidhu", "moose wala", "dhillon", "aujla", "dosanjh", "shubh", "singh", "garry", "bhangra", "kaur")
        val hindiKeywords = listOf("hindi", "bollywood", "arijit", "kakkar", "nautiyal", "aslam", "sonu nigam", "shreya", "alkas", "udit", "kumars", "pritam", "ar rahman", "t-series", "zee music", "yrf", "dil", "pyar", "tujhe", "ho", "tum", "yaar", "tere", "ishq", "mohabbat", "kiya", "meri", "hum", "channa", "mereya", "raataan", "lambiyan", "sajna", "duniya", "zindagi", "sanam")
        val tamilKeywords = listOf("tamil", "anirudh", "arrahman", "ilayaraja", "kadhal", "kadhala", "kollywood", "yuvan", "srinivas", "vijay", "ajith", "kamal", "rajini")
        val koreanKeywords = listOf("k-pop", "bts", "blackpink", "twice", "korean", "newjeans", "stray kids", "exo", "jungkook", "jimin", "seventeen")

        if (punjabiKeywords.any { fullText.contains(it) }) {
            language = "Punjabi"
        } else if (hindiKeywords.any { fullText.contains(it) }) {
            language = "Hindi"
        } else if (tamilKeywords.any { fullText.contains(it) }) {
            language = "Tamil"
        } else if (koreanKeywords.any { fullText.contains(it) }) {
            language = "Korean"
        }

        // 2. Genre Detection
        var genre = "Pop"
        val lofiKeywords = listOf("lofi", "lo-fi", "chill", "slowed", "reverb", "aesthetic", "bedtime", "relax", "meditate", "sleep", "study")
        val rapKeywords = listOf("rap", "hip hop", "hiphop", "cypher", "freestyle", "beat", "diss", "badshah", "raftaar", "kr\$na", "emiway", "mc stan", "divine", "drake", "eminem", "shubh")
        val indieKeywords = listOf("indie", "prateek kuhad", "anuv jain", "local train", "yellow diary", "independent", "mitraz", "aditya rikhari", "darshan raval")
        val rockKeywords = listOf("rock", "metal", "grunge", "nirvana", "linkin park", "metallica", "guitar solo")
        val bollywoodKeywords = listOf("bollywood", "t-series", "zee music", "yrf", "soundtrack", "ost", "arijit", "pritam", "saregama")

        if (lofiKeywords.any { fullText.contains(it) }) {
            genre = "Lofi"
        } else if (rapKeywords.any { fullText.contains(it) }) {
            genre = "Rap/Hip-Hop"
        } else if (indieKeywords.any { fullText.contains(it) }) {
            genre = "Indie"
        } else if (rockKeywords.any { fullText.contains(it) }) {
            genre = "Rock"
        } else if (bollywoodKeywords.any { fullText.contains(it) } && language == "Hindi") {
            genre = "Bollywood"
        } else if (punjabiKeywords.any { fullText.contains(it) } && language == "Punjabi") {
            genre = "Punjabi Folk"
        }

        // 3. Mood Detection
        var mood = "Chill/Relaxed"
        val romanticKeywords = listOf("love", "pyar", "dil", "ishq", "romantic", "mohabat", "humsafar", "tum", "tujhe", "shreya ghoshal", "sweetheart", "kiss", "valentine", "sanam")
        val sadKeywords = listOf("sad", "breakup", "broken", "dard", "gam", "alone", "crying", "judaa", "tanha", "tears", "lonely", "hurt")
        val energeticKeywords = listOf("remix", "edm", "party", "club", "dance", "gym", "workout", "dj", "punjabi", "badshah", "upbeat", "bhangra", "bass boosted", "trap")
        val happyKeywords = listOf("happy", "smile", "fun", "celebration", "summer", "good vibes", "cheerful", "sunny")
        val darkKeywords = listOf("dark", "heavy", "metal", "evil", "ghost", "shadow", "rage")

        if (sadKeywords.any { fullText.contains(it) }) {
            mood = "Sad"
        } else if (romanticKeywords.any { fullText.contains(it) }) {
            mood = "Romantic"
        } else if (energeticKeywords.any { fullText.contains(it) }) {
            mood = "Energetic"
        } else if (happyKeywords.any { fullText.contains(it) }) {
            mood = "Happy"
        } else if (darkKeywords.any { fullText.contains(it) }) {
            mood = "Dark"
        }

        // 4. Energy & Tempo
        var energy = 0.5
        var tempo = 100
        when (genre) {
            "Lofi" -> { energy = 0.25; tempo = 74 }
            "Sad" -> { energy = 0.3; tempo = 82 }
            "Rap/Hip-Hop" -> { energy = 0.82; tempo = 136 }
            "Rock" -> { energy = 0.88; tempo = 128 }
            "Punjabi Folk" -> { energy = 0.85; tempo = 124 }
            "Bollywood" -> {
                if (mood == "Romantic" || mood == "Sad") {
                    energy = 0.45; tempo = 88
                } else {
                    energy = 0.65; tempo = 110
                }
            }
            else -> {
                when (mood) {
                    "Energetic" -> { energy = 0.85; tempo = 128 }
                    "Sad" -> { energy = 0.35; tempo = 80 }
                    "Romantic" -> { energy = 0.5; tempo = 92 }
                    "Happy" -> { energy = 0.7; tempo = 115 }
                    "Dark" -> { energy = 0.8; tempo = 120 }
                    else -> { energy = 0.55; tempo = 96 }
                }
            }
        }
        
        val hash = item.videoId.hashCode()
        energy = (energy + (hash % 10) / 100.0).coerceIn(0.1, 0.99)
        tempo = (tempo + (hash % 15) - 7).coerceIn(60, 180)

        // 5. Year
        var year = 2025
        val yearRegex = Regex("\\b(19\\d\\d|20[0-2]\\d)\\b")
        val matchResult = yearRegex.find(item.title)
        if (matchResult != null) {
            year = matchResult.value.toIntOrNull() ?: 2025
        } else {
            if (fullText.contains("retro") || fullText.contains("classic") || fullText.contains("90s") || fullText.contains("80s") || fullText.contains("kishore") || fullText.contains("lata")) {
                year = 1995 + (hash % 15)
            } else {
                year = 2021 + (hash % 5)
            }
        }
        
        val isOfficial = isOfficialArtistChannel(item.title, item.author) && 
                         !isUnofficialContent(item.title, item.author)
        val sourceQuality = if (isOfficial) "Ultra HD (320kbps)" else "Standard Quality (128kbps)"

        return SongMetadata(
            title = item.title,
            artist = item.author,
            genre = genre,
            mood = mood,
            language = language,
            energy = energy,
            tempo = tempo,
            year = year,
            isOfficial = isOfficial,
            sourceQuality = sourceQuality
        )
    }

    suspend fun buildTasteProfile(db: VinDatabase): TasteProfile = withContext(Dispatchers.IO) {
        val signals = db.interactionSignalDao().getAll()
        val imports = db.playlistDao().getAllPlaylistSongs()
        val history = db.historyDao().getAllHistory()
        
        val artistScores = HashMap<String, Double>()
        val genreScores = HashMap<String, Double>()
        val moodScores = HashMap<String, Double>()
        val langScores = HashMap<String, Double>()
        
        val favoriteTracks = HashSet<String>()
        val skippedTracks = HashSet<String>()
        val skippedArtists = HashSet<String>()
        val downloadedTracks = ArrayList<InteractionSignal>()
        val likedTracks = ArrayList<InteractionSignal>()

        // 1. Process Interaction Signals (Play, Likes, Skips)
        for (sig in signals) {
            val author = sig.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue

            var score = 0.0
            score += sig.completeCount * 5.0
            score += sig.repeatCount * 6.0
            if (sig.isLiked) {
                score += 10.0
                likedTracks.add(sig)
            }
            if (sig.isDownloaded) {
                score += 8.0
                downloadedTracks.add(sig)
            }
            score += sig.searchClickCount * 3.0
            score -= sig.skip20sCount * 6.0

            if (sig.skipCount > 0 && sig.skip20sCount == 0) {
                score -= sig.skipCount * 3.0
            }

            if (score > 6.0) {
                favoriteTracks.add(sig.videoId)
            }

            if (sig.skip20sCount >= 2 || sig.skipCount >= 4) {
                skippedTracks.add(sig.videoId)
                if (sig.skipCount > sig.playCount) {
                    skippedArtists.add(author.lowercase(Locale.ROOT))
                }
            }

            artistScores[author] = (artistScores[author] ?: 0.0) + score

            val fakeItem = VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
            val meta = inferMetadata(fakeItem)
            if (score > 0) {
                genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + score
                moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + score
                langScores[meta.language] = (langScores[meta.language] ?: 0.0) + score
            }
        }

        // 2. Process Imported Playlists (highly trains TasteDNA on cold/warm starts!)
        for (imp in imports) {
            val author = imp.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue
            
            // Give each imported song a dynamic base score weight of +3.0
            artistScores[author] = (artistScores[author] ?: 0.0) + 3.0
            
            val fakeItem = VideoItem(imp.videoId, imp.title, imp.author, imp.durationText)
            val meta = inferMetadata(fakeItem)
            genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + 3.0
            moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + 3.0
            langScores[meta.language] = (langScores[meta.language] ?: 0.0) + 3.0
        }

        // 3. Process History Entries (Listen back logs)
        for (h in history) {
            val author = h.author.trim()
            if (author.isBlank() || author.lowercase() == "unknown") continue
            
            // Base weight for simple play history
            artistScores[author] = (artistScores[author] ?: 0.0) + 1.0
            
            val fakeItem = VideoItem(h.videoId, h.title, h.author, h.durationText)
            val meta = inferMetadata(fakeItem)
            genreScores[meta.genre] = (genreScores[meta.genre] ?: 0.0) + 1.0
            moodScores[meta.mood] = (moodScores[meta.mood] ?: 0.0) + 1.0
            langScores[meta.language] = (langScores[meta.language] ?: 0.0) + 1.0
        }

        val sortedArtists = artistScores.toList()
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(8)

        val sortedGenres = genreScores.toList().sortedByDescending { it.second }
        val sortedMoods = moodScores.toList().sortedByDescending { it.second }
        val sortedLangs = langScores.toList().sortedByDescending { it.second }

        // 4. Compute Vector TasteDNA representing continuous acoustic targets
        var weightedEnergy = 0.0
        var weightedTempo = 0.0
        var totalWeight = 0.0
        
        for (sig in signals) {
            val score = sig.completeCount * 5.0 + sig.repeatCount * 6.0 + 
                        (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0)
            if (score > 2.0) {
                val fake = VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
                val meta = inferMetadata(fake)
                weightedEnergy += meta.energy * score
                weightedTempo += meta.tempo * score
                totalWeight += score
            }
        }
        
        // Add imports to vector profile calculation
        for (imp in imports.take(15)) {
            val fake = VideoItem(imp.videoId, imp.title, imp.author, imp.durationText)
            val meta = inferMetadata(fake)
            weightedEnergy += meta.energy * 3.0
            weightedTempo += meta.tempo * 3.0
            totalWeight += 3.0
        }

        val targetEnergy = if (totalWeight > 0) (weightedEnergy / totalWeight) else 0.58
        val targetTempo = if (totalWeight > 0) (weightedTempo / totalWeight).toInt() else 105

        val tasteDNA = TasteDNA(
            targetEnergy = targetEnergy,
            targetTempo = targetTempo,
            preferredGenres = genreScores,
            preferredMoods = moodScores,
            preferredLanguages = langScores,
            preferredArtists = artistScores
        )

        TasteProfile(
            topArtists = sortedArtists,
            topGenres = sortedGenres,
            topMoods = sortedMoods,
            topLanguages = sortedLangs,
            favoriteTracks = favoriteTracks,
            skippedTracks = skippedTracks,
            skippedArtists = skippedArtists,
            downloadedTracks = downloadedTracks,
            likedTracks = likedTracks,
            tasteDNA = tasteDNA
        )
    }

    /**
     * Mathematically calculates cosine/vector similarity between candidate metadata and TasteDNA profile
     */
    fun calculateTasteSimilarity(meta: SongMetadata, dna: TasteDNA): Double {
        val maxGenreVal = dna.preferredGenres.values.maxOrNull() ?: 1.0
        val genreWeight = dna.preferredGenres[meta.genre] ?: 0.1
        val genreScore = (genreWeight / maxGenreVal).coerceIn(0.1, 1.0)

        val maxMoodVal = dna.preferredMoods.values.maxOrNull() ?: 1.0
        val moodWeight = dna.preferredMoods[meta.mood] ?: 0.1
        val moodScore = (moodWeight / maxMoodVal).coerceIn(0.1, 1.0)

        val maxLangVal = dna.preferredLanguages.values.maxOrNull() ?: 1.0
        val langWeight = dna.preferredLanguages[meta.language] ?: 0.1
        val langScore = (langWeight / maxLangVal).coerceIn(0.1, 1.0)

        // Artist score: check exact artist affinity or similar artist affinity
        val normMetaArtist = normalizeArtistName(meta.artist)
        var artistScore = 0.0
        val maxArtistVal = dna.preferredArtists.values.maxOrNull() ?: 1.0
        
        val artistWeight = dna.preferredArtists.entries.firstOrNull { 
            normalizeArtistName(it.key) == normMetaArtist 
        }?.value ?: 0.0
        
        if (artistWeight > 0.0) {
            artistScore = (artistWeight / maxArtistVal).coerceIn(0.0, 1.0)
        } else {
            val similarArtistMatch = dna.preferredArtists.entries.firstOrNull { (prefArtist, _) ->
                isSimilarArtist(prefArtist, meta.artist)
            }
            if (similarArtistMatch != null) {
                artistScore = (similarArtistMatch.value / maxArtistVal * 0.6).coerceIn(0.0, 1.0)
            }
        }

        // Energy similarity delta
        val energyDelta = Math.abs(meta.energy - dna.targetEnergy)
        val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)

        // Tempo similarity mapped smoothly in BPM cosine spectrum
        val tempoDelta = Math.abs(meta.tempo - dna.targetTempo).toDouble()
        val tempoScore = Math.cos((tempoDelta / 120.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

        return (genreScore * 0.20) + (artistScore * 0.15) + (moodScore * 0.20) + (langScore * 0.15) + (energyScore * 0.15) + (tempoScore * 0.15)
    }

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

    // ── SPOTIFY DYNAMIC DAILY MIXES CURATION ──────────────────────────────────────────

    suspend fun getSpotifyMixes(ctx: Context, forceRefresh: Boolean = false): List<SpotifyMix> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        synchronized(cachedMixes) {
            if (!forceRefresh && lastMixCacheTime > 0 && (now - lastMixCacheTime < CACHE_EXPIRY_MS) && cachedMixes.isNotEmpty()) {
                return@withContext ArrayList(cachedMixes)
            }
        }

        Log.d(TAG, "Curating Spotify-Style Daily Mixes concurrently...")
        val db = VinDatabase.getInstance(ctx)
        val profile = buildTasteProfile(db)
        val dna = profile.tasteDNA

        val mixes = ArrayList<SpotifyMix>()
        val globalShownIds = HashSet<String>()

        // Dynamically compute the top 3 genre clusters from TasteDNA profile, fallback to defaults
        val topGenres = profile.topGenres.map { it.first }.filter { it.isNotBlank() }.toMutableList()
        val defaultFallbacks = listOf("Lofi", "Rap/Hip-Hop", "Bollywood")
        for (fallback in defaultFallbacks) {
            if (topGenres.size >= 3) break
            if (!topGenres.contains(fallback)) {
                topGenres.add(fallback)
            }
        }
        val selectedGenres = topGenres.take(3)

        // Generate Daily Mix 1, 2, and 3 based on these top clusters
        for (i in 0..2) {
            val genre = selectedGenres.getOrNull(i) ?: defaultFallbacks[i]
            val config = GENRE_CONFIGS[genre] ?: GenreMixConfig(
                description = "Your personal compilation of $genre tracks curated matching your TasteDNA.",
                queries = listOf("$genre official popular music", "$genre hit tracks playlist"),
                gradientStartHex = when (i) {
                    0 -> "0xFF3B0764"
                    1 -> "0xFF7F1D1D"
                    else -> "0xFF065F46"
                },
                gradientEndHex = when (i) {
                    0 -> "0xFF1E1B4B"
                    1 -> "0xFF450A0A"
                    else -> "0xFF022C22"
                },
                targetMood = "Chill/Relaxed"
            )

            val pool = fetchCandidatesFromQueries(config.queries)
            val songs = curateMixSongs(pool, profile, genre, genre, config.targetMood, globalShownIds)
            
            globalShownIds.addAll(songs.map { it.videoItem.videoId })

            mixes.add(SpotifyMix(
                id = "daily_mix_${i + 1}",
                title = "Daily Mix ${i + 1}",
                description = config.description,
                songs = songs,
                gradientStartHex = config.gradientStartHex,
                gradientEndHex = config.gradientEndHex
            ))
        }

        // 4. Discover Weekly Mix
        val dwQueries = listOf("underrated fresh acoustic gems", "new independent music releases 2026", "indie folk playlist viral")
        val dwPool = fetchCandidatesFromQueries(dwQueries)
        val dwSongs = curateDiscoverWeekly(dwPool, profile, globalShownIds, 8)
        globalShownIds.addAll(dwSongs.map { it.videoItem.videoId })
        mixes.add(SpotifyMix(
            id = "discover_weekly",
            title = "Discover Weekly",
            description = "Fresh discoveries matching your TasteDNA. Underrated official tracks you haven't heard yet.",
            songs = dwSongs,
            gradientStartHex = "0xFFEF4444", // Vibrant Red
            gradientEndHex = "0xFF7F1D1D"   // Dark Red
        ))

        // 5. Release Radar Mix
        val rrQueries = listOf("new music release 2026", "latest official hits charts 2026")
        val rrPool = fetchCandidatesFromQueries(rrQueries)
        val rrSongs = curateReleaseRadar(rrPool, profile, globalShownIds, 8)
        globalShownIds.addAll(rrSongs.map { it.videoItem.videoId })
        mixes.add(SpotifyMix(
            id = "release_radar",
            title = "Release Radar",
            description = "The latest official releases from your preferred artists and matching genres.",
            songs = rrSongs,
            gradientStartHex = "0xFFB45309", // Amber
            gradientEndHex = "0xFF78350F"   // Warm Orange
        ))

        // 6. Repeat Rewind Mix
        val rewindSongs = curateRepeatRewind(db, profile, globalShownIds, 8)
        mixes.add(SpotifyMix(
            id = "repeat_rewind",
            title = "Repeat Rewind",
            description = "Rewind your favorites. The songs you have played on repeat and liked the most.",
            songs = rewindSongs,
            gradientStartHex = "0xFF0369A1", // Cyan
            gradientEndHex = "0xFF0C4A6E"   // Dark Navy
        ))


        synchronized(cachedMixes) {
            cachedMixes.clear()
            cachedMixes.addAll(mixes)
            lastMixCacheTime = System.currentTimeMillis()
        }
        mixes
    }


    private fun curateMixSongs(
        candidates: List<VideoItem>,
        profile: TasteProfile,
        targetGenre: String,
        fallbackGenre: String,
        targetMood: String,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)
            val normTitle = normalizeTitle(title)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val meta = inferMetadata(item)
            val isGenreMatch = meta.genre == targetGenre || meta.genre == fallbackGenre
            val isMoodMatch = meta.mood == targetMood
            
            if (!isGenreMatch && !isMoodMatch) continue

            val userHistoryMatch = calculateTasteSimilarity(meta, profile.tasteDNA)
            
            // Boost exact matches slightly
            val finalScore = userHistoryMatch * 70.0 + (if (meta.isOfficial) 20.0 else 0.0) + (if (isGenreMatch) 10.0 else 0.0)
            scored.add(RecommendedSong(item, finalScore, "daily_mix", "Personal Mix match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private fun curateDiscoverWeekly(
        candidates: List<VideoItem>,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        val mainstreamArtists = listOf(
            "arijit singh", "diljit dosanjh", "karan aujla", "sidhu moose wala", "badshah", 
            "drake", "eminem", "taylor swift", "ed sheeran", "the weeknd", "travis scott", 
            "post malone", "kendrick lamar", "21 savage", "j cole", "justin bieber", 
            "billie eilish", "neha kakkar", "jubin nautiyal", "king", "mc stan", "divine"
        )

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            // Must not be in user favorites or high play history to ensure actual "Discovery"
            if (profile.favoriteTracks.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val meta = inferMetadata(item)
            val similarity = calculateTasteSimilarity(meta, profile.tasteDNA)
            
            val isMainstream = mainstreamArtists.contains(normAuthor) || isCorporateOrDistributorChannel(author)
            val mainstreamPenalty = if (isMainstream) 15.0 else 0.0
            
            val isIndependent = !isMainstream && (meta.genre == "Indie" || meta.genre == "Lofi" || 
                                title.lowercase(Locale.ROOT).contains("indie") || 
                                author.lowercase(Locale.ROOT).contains("indie") ||
                                title.lowercase(Locale.ROOT).contains("independent"))
            val independentBoost = if (isIndependent) 10.0 else 0.0

            // Boost official quality, penalize if artist is already super famous to promote true discover weekly gems!
            val finalScore = similarity * 80.0 + (if (meta.isOfficial) 20.0 else 0.0) - mainstreamPenalty + independentBoost
            scored.add(RecommendedSong(item, finalScore, "discover_weekly", "Fresh new track match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private fun curateReleaseRadar(
        candidates: List<VideoItem>,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> {
        val scored = ArrayList<RecommendedSong>()
        val localArtists = HashMap<String, Int>()

        for (item in candidates) {
            if (globalShownIds.contains(item.videoId)) continue

            val title = item.title
            val author = item.author.trim()
            val normAuthor = author.lowercase(Locale.ROOT)

            if (isCompilationTrack(title, item.durationText)) continue
            if (!isOfficialArtistChannel(title, author) || isUnofficialContent(title, author)) continue
            if (profile.skippedTracks.contains(item.videoId) || profile.skippedArtists.contains(normAuthor)) continue

            val meta = inferMetadata(item)
            // Release radar focuses heavily on fresh 2025/2026 releases!
            if (meta.year < 2024) continue

            val similarity = calculateTasteSimilarity(meta, profile.tasteDNA)
            var artistBoost = 0.0
            if (profile.topArtists.any { it.first.lowercase(Locale.ROOT) == normAuthor }) {
                artistBoost = 20.0
            }

            val finalScore = similarity * 60.0 + artistBoost + 20.0 // 20.0 official release bonus
            scored.add(RecommendedSong(item, finalScore, "release_radar", "New release match"))
        }

        val distinct = scored.distinctBy { it.videoItem.videoId }
            .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
            .sortedByDescending { it.score }

        val selected = ArrayList<RecommendedSong>()
        for (rec in distinct) {
            if (selected.size >= limit) break
            val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
            val currentCount = localArtists[artLow] ?: 0
            if (currentCount < 2) {
                selected.add(rec)
                localArtists[artLow] = currentCount + 1
            }
        }
        return selected
    }

    private suspend fun curateRepeatRewind(
        db: VinDatabase,
        profile: TasteProfile,
        globalShownIds: HashSet<String> = HashSet(),
        limit: Int = 8
    ): List<RecommendedSong> = withContext(Dispatchers.IO) {
        val historyList = db.historyDao().getAllHistory()
        val scored = ArrayList<RecommendedSong>()
        val globalVideoIds = HashSet<String>()

        // Gather all highly interacted songs (likes, downloads, or high play counts)
        val signals = db.interactionSignalDao().getAll()

        for (sig in signals) {
            if (globalVideoIds.contains(sig.videoId) || globalShownIds.contains(sig.videoId)) continue
            
            val item = VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
            if (isCompilationTrack(sig.title, sig.durationText)) continue
            
            // Calculate mathematical exponential time decay using lastPlayedAt to favor recent obsessions
            val ageDays = if (sig.lastPlayedAt > 0) {
                (System.currentTimeMillis() - sig.lastPlayedAt).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
            } else {
                30.0 // Default to 30 days if not set
            }
            val decayFactor = Math.exp(-ageDays / 14.0) // 14-day exponential decay half-life

            val baseScore = sig.playCount * 5.0 + sig.repeatCount * 6.0 + 
                            (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0)
            val score = baseScore * decayFactor
            
            if (score >= 1.0) {
                scored.add(RecommendedSong(item, score, "repeat_rewind", "Your highly played favorite"))
                globalVideoIds.add(sig.videoId)
            }
        }

        // Add plain history entries if we need more songs
        for (h in historyList) {
            if (scored.size >= limit) break
            if (globalVideoIds.contains(h.videoId) || globalShownIds.contains(h.videoId)) continue
            if (isCompilationTrack(h.title, h.durationText)) continue
            
            val item = VideoItem(h.videoId, h.title, h.author, h.durationText)
            scored.add(RecommendedSong(item, 1.0, "repeat_rewind", "From your history"))
            globalVideoIds.add(h.videoId)
        }

        scored.sortedByDescending { it.score }.take(limit)
    }

    // ── SPOTIFY-STYLE RADIO AUTOPLAY SIMILARITY MATCHING ──────────────────────────

    suspend fun getAutoplayRecommendations(ctx: Context, seedSong: VideoItem): List<VideoItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Generating Smart Autoplay Radio for '${seedSong.title}'...")
        val seedMeta = inferMetadata(seedSong)
        
        // Search queries themed around the seed song's characteristics
        val queries = listOf(
            "${seedSong.author} official popular music",
            "${seedMeta.genre} hits ${seedMeta.language} 2026",
            "${seedSong.title} similar tracks"
        )
        val pool = fetchCandidatesFromQueries(queries)
        val scored = ArrayList<Pair<VideoItem, Double>>()

        for (item in pool) {
            if (item.videoId == seedSong.videoId) continue
            if (isCompilationTrack(item.title, item.durationText)) continue
            if (!isOfficialArtistChannel(item.title, item.author) || isUnofficialContent(item.title, item.author)) continue

            val meta = inferMetadata(item)
            
            // Match similarity metrics in acoustic fields: energy, tempo, genre
            val genreScore = if (meta.genre == seedMeta.genre) 1.0 else 0.1
            val moodScore = if (meta.mood == seedMeta.mood) 1.0 else 0.2
            val langScore = if (meta.language == seedMeta.language) 1.0 else 0.3
            
            val energyDelta = Math.abs(meta.energy - seedMeta.energy)
            val energyScore = (1.0 - energyDelta).coerceIn(0.0, 1.0)
            
            val tempoDelta = Math.abs(meta.tempo - seedMeta.tempo).toDouble()
            val tempoScore = Math.cos((tempoDelta / 120.0 * Math.PI).coerceIn(0.0, Math.PI)) / 2.0 + 0.5

            val totalSimilarity = genreScore * 0.3 + moodScore * 0.2 + langScore * 0.2 + energyScore * 0.15 + tempoScore * 0.15
            
            // Penalize same author slightly to avoid repeating same artist sequentially in radio queue
            val sameArtistPenalty = if (item.author.lowercase(Locale.ROOT) == seedSong.author.lowercase(Locale.ROOT)) 0.3 else 0.0
            
            // Apply heavy transitions penalty (BPM or Energy delta too high) for crossfade feeling
            val transitionPenalty = if (tempoDelta > 25.0 || energyDelta > 0.3) 0.3 else 0.0

            val totalScore = totalSimilarity - sameArtistPenalty - transitionPenalty
            scored.add(item to totalScore)
        }

        scored.sortedByDescending { it.second }
            .map { it.first }
            .distinctBy { it.videoId }
            .distinctBy { "${normalizeTitle(it.title)}|${it.author.lowercase(Locale.ROOT)}" }
            .take(5)
    }

    // ── GENERAL PERSONALIZED SHELVES (DIVERSITY & CAP GUARANTEE) ──────────────────

    suspend fun getRecommendations(ctx: Context, forceRefresh: Boolean = false): List<Pair<String, List<RecommendedSong>>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        // 1. Resolve current biological hour and expected time-of-day section key
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val expectedTimeSectionKey = when {
            hour in 5..11 -> "Morning Acoustic Sunshine"
            hour in 12..16 -> "Midday Chill & Focus"
            hour in 17..20 -> "Sunset Vibe & Energy"
            else -> "Midnight Sanctuary"
        }

        // 2. Validate memory cache: invalidate if time-of-day has shifted!
        synchronized(cachedSections) {
            val hasActiveTimeSection = cachedSections.any { it.first == expectedTimeSectionKey }
            if (!forceRefresh && lastCacheTime > 0 && (now - lastCacheTime < CACHE_EXPIRY_MS) && hasActiveTimeSection && cachedSections.isNotEmpty()) {
                val totalSongs = cachedSections.sumOf { it.second.size }
                if (totalSongs > 0) {
                    Log.d(TAG, "Returning memory cached personalized sections.")
                    return@withContext ArrayList(cachedSections)
                } else {
                    cachedSections.clear()
                    lastCacheTime = 0L
                }
            } else if (!hasActiveTimeSection) {
                lastCacheTime = 0L
                cachedSections.clear()
            }
        }

        // 3. Validate disk cache: invalidate if time-of-day has shifted!
        if (!forceRefresh) {
            val disk = loadFromDisk(ctx)
            if (disk != null && disk.isNotEmpty()) {
                val hasActiveTimeSection = disk.any { it.first == expectedTimeSectionKey }
                val prefs = ctx.getSharedPreferences("vin_music_recommendation_cache", Context.MODE_PRIVATE)
                val cachedTime = prefs.getLong("cached_time", 0L)
                if (hasActiveTimeSection && (now - cachedTime < CACHE_EXPIRY_MS)) {
                    synchronized(cachedSections) {
                        cachedSections.clear()
                        cachedSections.addAll(disk)
                    }
                    return@withContext disk
                } else {
                    prefs.edit().remove("cached_sections").remove("cached_time").apply()
                }
            }
        }

        Log.d(TAG, "Generating advanced personalized recommendation shelves concurrently...")
            val db = VinDatabase.getInstance(ctx)
        val profile = buildTasteProfile(db)
        val dna = profile.tasteDNA

        data class CurationTask(
            val sectionKey: String,
            val queries: List<String>,
            val seedItem: VideoItem?,
            val sourceType: String
        )

        val tasks = ArrayList<CurationTask>()

        // 1. More from [ArtistName] — one shelf per top artist (up to 3)
        val historyList = try { db.historyDao().getAllHistory() } catch (_: Exception) { emptyList() }
        val interactionSignals = try { db.interactionSignalDao().getAll() } catch (_: Exception) { emptyList() }
        val listenedArtists = (historyList.map { it.author.trim() } + interactionSignals.map { it.author.trim() })
            .filter { it.isNotBlank() && it.lowercase() != "unknown" && !isCorporateOrDistributorChannel(it) }
        val cleanListenedCount = listenedArtists.map { normalizeArtistName(it) }.distinct().size

        val fallbackMoreFromArtists = listOf("Arijit Singh", "Sidhu Moose Wala", "Karan Aujla")
        val topArtistsList = profile.topArtists.map { it.first }.filter { it.isNotBlank() }
        
        // Cold start safety rule: If user has heard fewer than 5 different artists overall, 
        // don't lock their feed onto J. Cole. Suggest highly diverse fallback artists instead!
        val moreFromArtists = if (cleanListenedCount >= 5) {
            topArtistsList.take(3)
        } else {
            fallbackMoreFromArtists
        }
        
        // Add a separate CurationTask per artist
        for (artistName in moreFromArtists) {
            tasks.add(CurationTask(
                sectionKey = "More from $artistName",
                queries = listOf("$artistName official audio popular", "$artistName hit songs"),
                seedItem = null,
                sourceType = "more_from_artist"
            ))
        }

        // 2. Rewind: Listen Back
        // Fetches from your history and highly played list - entirely offline seed!
        val rewindQueries = emptyList<String>() // Rewind is handled offline from local DB candidates!
        tasks.add(CurationTask("Rewind: Listen Back", rewindQueries, null, "rewind_listen_back"))

        // 4. Similar songs (dynamically seeded by most recently played track to adapt to music taste changes)
        // Cold start safety rule: if they listened to fewer than 5 different artists, do not seed with a single artist play.
        val recentPlayed = historyList.firstOrNull()
        val topPlayedList = interactionSignals.sortedByDescending { it.playCount }
        
        val similarSeed = if (cleanListenedCount >= 5) {
            if (recentPlayed != null) {
                db.interactionSignalDao().get(recentPlayed.videoId) ?: InteractionSignal(
                    videoId = recentPlayed.videoId,
                    title = recentPlayed.title,
                    author = recentPlayed.author,
                    durationText = recentPlayed.durationText,
                    playCount = 1
                )
            } else {
                topPlayedList.firstOrNull { it.playCount > 0 }
            }
        } else {
            null
        }
        
        val similarQueries = if (similarSeed != null) {
            listOf("${similarSeed.author} similar music", "${similarSeed.title} similar")
        } else {
            listOf("chill acoustic aesthetic hits", "indie music sessions popular")
        }
        val seedVideo = similarSeed?.let { VideoItem(it.videoId, it.title, it.author, it.durationText) }
        tasks.add(CurationTask("Similar songs", similarQueries, seedVideo, "similar_songs"))

        // 5. Dynamic Time-of-Day Curation Task
        val timeCuration = when (expectedTimeSectionKey) {
            "Morning Acoustic Sunshine" -> {
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = listOf("acoustic pop hits Taylor Swift Ed Sheeran", "fresh morning acoustic popular hindi songs", "Arijit Singh acoustic popular hits"),
                    seedItem = null,
                    sourceType = "morning_vibe"
                )
            }
            "Midday Chill & Focus" -> {
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = listOf("Prateek Kuhad chill hits", "Anuv Jain soft popular songs", "Ed Sheeran soft chill acoustic"),
                    seedItem = null,
                    sourceType = "afternoon_vibe"
                )
            }
            "Sunset Vibe & Energy" -> {
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = listOf("Karan Aujla energetic popular hits", "Sidhu Moose Wala legendary popular hits", "Travis Scott rap hits official"),
                    seedItem = null,
                    sourceType = "evening_vibe"
                )
            }
            else -> {
                CurationTask(
                    sectionKey = expectedTimeSectionKey,
                    queries = listOf("Anuv Jain romantic popular", "Lana Del Rey sad slow songs", "Mitraz romantic aesthetic hits"),
                    seedItem = null,
                    sourceType = "night_vibe"
                )
            }
        }
        tasks.add(timeCuration)

        val newSections = ArrayList<Pair<String, List<RecommendedSong>>>()
        val compilationSongs = java.util.Collections.synchronizedList(ArrayList<RecommendedSong>())

        
        // GLOBAL ARTIST & DUPES DE-CLUSTERING FILTER: Cap at 2 tracks per artist globally on the Home Screen!
        val globalArtistCounts = HashMap<String, Int>()
        val globalShownVideoIds = HashSet<String>()
        val globalShownTitlesAndArtists = HashSet<String>()

        coroutineScope {
            val deferredResults = tasks.map { task ->
                async(Dispatchers.IO) {
                    val taskCandidates = ArrayList<VideoItem>()
                    
                    if (task.sourceType == "rewind_listen_back") {
                        // Offline Rewind: Gather from history and interaction signals
                        val historyList = db.historyDao().getAllHistory()
                        val signalsList = db.interactionSignalDao().getAll()
                        
                        val offlinePool = (historyList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) } +
                                          signalsList.map { VideoItem(it.videoId, it.title, it.author, it.durationText) })
                                          .distinctBy { it.videoId }
                        taskCandidates.addAll(offlinePool)
                    } else {
                        val queryJobs = task.queries.map { query ->
                            async(Dispatchers.IO) {
                                try {
                                    InnerTube.search(query)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Search query failed '$query': ${e.message}")
                                    emptyList<VideoItem>()
                                }
                            }
                        }
                        val queryResults = queryJobs.awaitAll()
                        for (res in queryResults) {
                            taskCandidates.addAll(res)
                        }
                    }
                    task to taskCandidates
                }
            }

            val taskResults = deferredResults.awaitAll()

            for ((task, candidates) in taskResults) {
                val filteredScored = ArrayList<RecommendedSong>()
                
                // Shuffle candidates dynamically on every call to avoid fixed/static playlist grids and promote fresh discoveries
                val shuffledCandidates = candidates.shuffled()
                
                val candidateVideoIds = shuffledCandidates.map { it.videoId }
                val signalsMap = db.interactionSignalDao().getAll()
                    .filter { it.videoId in candidateVideoIds }
                    .associateBy { it.videoId }

                for (item in shuffledCandidates) {
                    val author = item.author.trim()
                    val title = item.title.trim()
                    val normAuthor = author.lowercase(Locale.ROOT)
                    val normTitle = normalizeTitle(title)

                    // Strict filter rules: compilation and unofficial streams blocked from main shelves!
                    val isCompile = isCompilationTrack(title, item.durationText) || isCompilationTitle(title)
                    if (isCompile) {
                        val meta = inferMetadata(item)
                        val similarity = calculateTasteSimilarity(meta, dna)
                        val score = similarity * 50.0 + (if (meta.isOfficial) 10.0 else 0.0)
                        compilationSongs.add(RecommendedSong(item, score, "compilation", "Jukebox / Compilation mix"))
                        continue
                    }
                    if (isNonMusicVideo(title, author)) continue
                    if (isUnofficialContent(title, author)) continue

                    if (profile.skippedTracks.contains(item.videoId) || 
                        profile.skippedArtists.contains(normAuthor)) {
                        continue
                    }

                    if (task.seedItem != null) {
                        if (task.sourceType == "similar_songs" && normalizeArtistName(author) == normalizeArtistName(task.seedItem.author)) {
                            continue
                        }
                        if (isTooSimilar(title, task.seedItem.title)) continue
                    }

                    val normKey = "$normTitle|$normAuthor"
                    if (globalShownVideoIds.contains(item.videoId) || globalShownTitlesAndArtists.contains(normKey)) {
                        continue
                    }

                    val sig = signalsMap[item.videoId]
                    
                    // Score with vector profile matching TasteDNA similarity
                    val meta = inferMetadata(item)
                    val similarity = calculateTasteSimilarity(meta, dna)
                    
                    var historyScore = 0.0
                    if (sig != null) {
                        val raw = sig.completeCount * 5.0 + sig.repeatCount * 6.0 + 
                                  (if (sig.isLiked) 10.0 else 0.0) + (if (sig.isDownloaded) 8.0 else 0.0) + 
                                  sig.searchClickCount * 3.0 - sig.skip20sCount * 6.0
                        historyScore = (raw / 40.0).coerceIn(0.0, 1.0)
                    }

                    // Dynamic vibe alignment scoring bonus matching current bio hour
                    var vibeBonus = 0.0
                    if (task.sourceType == "morning_vibe") {
                        if (meta.energy >= 0.55 || meta.mood in listOf("Happy", "Energetic", "Chill")) {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "afternoon_vibe") {
                        if (meta.energy <= 0.55 || meta.mood in listOf("Chill", "Happy") || meta.genre == "Lofi") {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "evening_vibe") {
                        if (meta.energy >= 0.60 || meta.mood in listOf("Energetic", "Happy", "Dark")) {
                            vibeBonus = 15.0
                        }
                    } else if (task.sourceType == "night_vibe") {
                        if (meta.energy <= 0.45 || meta.mood in listOf("Sad", "Romantic", "Chill", "Dark")) {
                            vibeBonus = 15.0
                        }
                    }

                    // Dynamic random entropy factor (+/- 12 points) to guarantee varied selections on refresh
                    val randomEntropyFactor = Math.random() * 12.0
                    val finalScore = (similarity * 60.0) + (historyScore * 20.0) + (if (meta.isOfficial) 20.0 else 0.0) + vibeBonus + randomEntropyFactor

                    val reason = when (task.sourceType) {
                        "more_from_artist" -> "Official track from your top artist"
                        "rewind_listen_back" -> "Familiar song from your history"
                        "artists_like"   -> "Artist similarity matches TasteDNA"
                        "similar_songs"  -> "Acoustically matches your top song"
                        "trending_songs" -> "Trending official hit"
                        "hidden_gems"    -> "Acoustically matches underrated gem"
                        "morning_vibe"   -> "Perfect acoustic start for your morning"
                        "afternoon_vibe" -> "Chill focus beats for your afternoon"
                        "evening_vibe"   -> "Energetic vibes for your evening"
                        "night_vibe"     -> "Soothing deep melodies for your night"
                        else             -> "Curated recommendations"
                    }

                    filteredScored.add(RecommendedSong(item, finalScore, task.sourceType, reason))
                }

                val distinctScored = filteredScored.distinctBy { it.videoItem.videoId }
                    .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                    .sortedByDescending { it.score }

                val selected = ArrayList<RecommendedSong>()
                
                if (task.sourceType == "more_from_artist") {
                    // For artist-specific shelves, bypass the global capping and select a randomized subset of the top 15 matches
                    // to guarantee highly relevant yet completely fresh/different tracks on reload!
                    val topCandidates = distinctScored.take(15).shuffled()
                    for (rec in topCandidates) {
                        if (selected.size >= 8) break
                        selected.add(rec)
                    }
                } else {
                    // Incorporate strict GLOBAL ARTIST CAPPING: max 2 songs per artist combined for general/time-of-day mixes
                    for (rec in distinctScored) {
                        if (selected.size >= 8) break
                        val artLow = rec.videoItem.author.lowercase(Locale.ROOT)
                        val globalCount = globalArtistCounts[artLow] ?: 0
                        
                        if (globalCount < 2) {
                            selected.add(rec)
                            globalArtistCounts[artLow] = globalCount + 1
                        }
                    }
                }

                if (selected.size >= 3) {
                    newSections.add(task.sectionKey to selected)
                    for (sel in selected) {
                        globalShownVideoIds.add(sel.videoItem.videoId)
                        val normKey = "${normalizeTitle(sel.videoItem.title)}|${sel.videoItem.author.lowercase(Locale.ROOT)}"
                        globalShownTitlesAndArtists.add(normKey)
                    }
                    Log.d(TAG, "Shelf '${task.sectionKey}' curated with ${selected.size} tracks successfully.")
                }
            }
        }

        if (newSections.isEmpty()) {
            Log.w(TAG, "Advanced curation resulted in empty shelves, generating premium default charts.")
            try {
                val fallbackQueries = listOf(
                    "Discover Weekly" to "acoustic warm indie pop songs"
                )
                for ((title, query) in fallbackQueries) {
                    val results = InnerTube.search(query)
                    val songs = results
                        .filter { !isCompilationTrack(it.title, it.durationText) }
                        .filter { !isNonMusicVideo(it.title, it.author) }
                        .filter { !isUnofficialContent(it.title, it.author) }
                        .take(8).map { item ->
                            RecommendedSong(item, 50.0, "curated", "Discover Weekly hit")
                        }
                    if (songs.size >= 3) {
                        newSections.add(title to songs)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load fallbacks: ${e.message}")
            }
        }

        // Append distinct dynamic compilation songs as a separate premium shelf!
        if (compilationSongs.isNotEmpty()) {
            val distinctCompilations = compilationSongs.distinctBy { it.videoItem.videoId }
                .distinctBy { "${normalizeTitle(it.videoItem.title)}|${it.videoItem.author.lowercase(Locale.ROOT)}" }
                .sortedByDescending { it.score }
                .take(8)
            if (distinctCompilations.size >= 3) {
                newSections.add("Jukebox & Compilations" to distinctCompilations)
            }
        }

        synchronized(cachedSections) {
            cachedSections.clear()
            cachedSections.addAll(newSections)
            lastCacheTime = System.currentTimeMillis()
        }
        saveToDisk(ctx, newSections)
        ArrayList(newSections)
    }
}
