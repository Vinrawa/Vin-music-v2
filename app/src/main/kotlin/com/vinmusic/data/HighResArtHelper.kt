package com.vinmusic.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-resolution cover art helper for tracks.
 *
 * Upgrades standard YouTube 480x360 / 720p thumbnails to pristine 1200x1200px
 * lossless square album artwork via iTunes Store API.
 *
 * If the track is a YouTube-exclusive remix, live concert, or mashup that is
 * not in the official catalog, it safely falls back to YouTube's native HD thumbnail
 * without delay or broken images.
 */
object HighResArtHelper {
    private const val PREFS_NAME = "vin_highres_artwork_v2"
    private const val SENTINEL_NOT_FOUND = "__NOT_FOUND__"

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memoryCache = ConcurrentHashMap<String, String>()
    private var prefs: SharedPreferences? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    fun init(context: Context) {
        if (prefs != null) return
        try {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            p.all.forEach { (key, value) ->
                if (value is String) {
                    memoryCache[key] = value
                }
            }
            android.util.Log.d("HighResArtHelper", "Loaded ${memoryCache.size} cached artwork entries")
        } catch (e: Exception) {
            android.util.Log.e("HighResArtHelper", "Failed to init prefs: ${e.message}")
        }
    }

    /**
     * Get high-res artwork URL if already cached in memory.
     * Returns null if not cached or if verified as not in official catalog (so caller uses YT native HD).
     */
    fun getArtwork(videoId: String): String? {
        val cached = memoryCache[videoId] ?: return null
        return if (cached == SENTINEL_NOT_FOUND) null else cached
    }

    /**
     * Asynchronously prefetch high-res artwork for a track and cache it.
     */
    fun prefetch(videoId: String, title: String, artist: String) {
        if (videoId.isBlank() || videoId.startsWith("sc:")) return
        if (memoryCache.containsKey(videoId)) return

        scope.launch {
            fetchArtwork(videoId, title, artist)
        }
    }

    /**
     * Synchronously resolve high-res artwork for a track.
     */
    fun fetchArtwork(videoId: String, title: String, artist: String): String? {
        if (videoId.isBlank() || videoId.startsWith("sc:")) return null

        val cached = memoryCache[videoId]
        if (cached != null) {
            return if (cached == SENTINEL_NOT_FOUND) null else cached
        }

        val cleanT = cleanTitle(title)
        val cleanA = cleanArtist(artist)
        if (cleanT.isBlank()) {
            cacheArtwork(videoId, SENTINEL_NOT_FOUND)
            return null
        }

        val query = if (cleanA.isNotBlank()) "$cleanT $cleanA" else cleanT
        val encoded = try { URLEncoder.encode(query, "UTF-8") } catch (_: Exception) { return null }
        val url = "https://itunes.apple.com/search?term=$encoded&entity=song&limit=6"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    cacheArtwork(videoId, SENTINEL_NOT_FOUND)
                    return null
                }
                val raw = response.body?.string() ?: ""
                val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
                val results = root.getAsJsonArray("results")
                if (results == null || results.size() == 0) {
                    cacheArtwork(videoId, SENTINEL_NOT_FOUND)
                    return null
                }

                for (elem in results) {
                    val item = elem.asJsonObject ?: continue
                    val trackName = item.get("trackName")?.asString ?: ""
                    val artistName = item.get("artistName")?.asString ?: ""
                    val artwork100 = item.get("artworkUrl100")?.asString ?: ""

                    if (artwork100.isBlank()) continue

                    // Confidence check: Title similarity should be good
                    if (isMatch(cleanT, cleanA, trackName, artistName)) {
                        val highResUrl = artwork100
                            .replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/1200x1200bb.jpg")
                            .replace("100x100bb", "1200x1200bb")

                        android.util.Log.d("HighResArtHelper", "Matched '$title' -> $highResUrl")
                        cacheArtwork(videoId, highResUrl)
                        return highResUrl
                    }
                }

                // If no result passed confidence check, record sentinel
                cacheArtwork(videoId, SENTINEL_NOT_FOUND)
                return null
            }
        } catch (e: Exception) {
            android.util.Log.e("HighResArtHelper", "Fetch failed: ${e.message}")
            return null
        }
    }

    private fun cacheArtwork(videoId: String, url: String) {
        memoryCache[videoId] = url
        prefs?.edit()?.putString(videoId, url)?.apply()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?]|\\(.*?\\)"), "")
            .replace(Regex("(?i)\\s*\\b(?:feat(?:uring)?|ft)\\.?(?:\\s+.*|$)"), "")
            .replace(Regex("(?i)\\b(?:official|music videos?|lyrical|videos?|audios?|lyrics?|hd|4k|full songs?|full videos?)\\b"), "")
            .replace(Regex("(?i)\\b(?:latest|new|punjabi|hindi)\\s+songs?\\b.*"), "")
            .replace(" - Topic", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        var clean = artist.replace(" - Topic", "", ignoreCase = true).trim()
        val parts = clean.split(Regex(",|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\band\\b", RegexOption.IGNORE_CASE))
        if (parts.isNotEmpty()) {
            clean = parts[0].trim()
        }
        return clean
    }

    private fun isMatch(searchTitle: String, searchArtist: String, itunesTitle: String, itunesArtist: String): Boolean {
        val sT = normalize(searchTitle)
        val iT = normalize(itunesTitle)
        if (sT.isBlank() || iT.isBlank()) return false

        // 1. Strict Title Matching: Title MUST match
        val titleMatch: Boolean
        if (sT == iT || sT.startsWith(iT) || iT.startsWith(sT)) {
            titleMatch = true
        } else {
            val sTokens = sT.split(" ").filter { it.length > 1 }.toSet()
            val iTokens = iT.split(" ").filter { it.length > 1 }.toSet()
            if (sTokens.isNotEmpty() && iTokens.isNotEmpty()) {
                val intersection = sTokens.intersect(iTokens).size.toDouble()
                val score = intersection / kotlin.math.max(sTokens.size, iTokens.size)
                titleMatch = score >= 0.7 || (intersection >= 2 && intersection >= kotlin.math.min(sTokens.size, iTokens.size))
            } else {
                titleMatch = false
            }
        }

        // If title doesn't match, NEVER return true
        if (!titleMatch) return false

        // 2. Strict Artist Matching: Artist MUST match if provided
        val sA = normalize(searchArtist)
        val iA = normalize(itunesArtist)
        if (sA.isBlank() || iA.isBlank()) {
            return true
        }

        if (sA == iA || sA.contains(iA) || iA.contains(sA)) {
            return true
        }

        val sATokens = sA.split(" ").filter { it.length > 2 }.toSet()
        val iATokens = iA.split(" ").filter { it.length > 2 }.toSet()
        if (sATokens.isNotEmpty() && iATokens.isNotEmpty() && sATokens.intersect(iATokens).isNotEmpty()) {
            return true
        }

        return false
    }

    private fun normalize(str: String): String {
        return str.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
