package com.vinmusic.spotify

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class CanvasItem(
    @SerializedName("song") val song: String,
    @SerializedName("artist") val artist: String,
    @SerializedName("album") val album: String?,
    @SerializedName("url") val url: String
)

data class CanvasIndex(
    @SerializedName("items") val items: List<CanvasItem> = emptyList()
)

object SpotifyCanvasClient {
    private const val TAG = "VinMusicCanvasClient"
    private const val CDN_INDEX_URL = "https://raw.githubusercontent.com/ViviMusicGroup/vivimusicanvas/main/canvas.json"
    private const val CACHE_FILE_NAME = "vin_music_canvas_index.json"
    private const val CACHE_EXPIRY_MS = 24 * 60 * 60 * 1000L // 24 hours

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Fast in-memory lookup cache: "normalizedTitle|normalizedArtist" -> canvasUrl
    private val memoryCache = ConcurrentHashMap<String, String>()
    
    // Cached items loaded from disk or remote CDN
    @Volatile
    private var canvasItems: List<CanvasItem>? = null
    @Volatile
    private var lastIndexFetchTime: Long = 0L

    private val CLEAN_REGEX = Pattern.compile(
        """(?i)\s*[\(\[\{]?(?:official\s*(?:video|audio|music\s*video|lyric\s*video|hd|4k|visualizer)?|lyrics?|remastered|explicit|audio|full\s*song|clean)[\)\]\}]?"""
    )

    fun normalize(text: String): String {
        var cleaned = text
        // Strip pipe noise e.g. "Song | Movie Name | Singer"
        val pipeIdx = cleaned.indexOf('|')
        if (pipeIdx != -1) {
            cleaned = cleaned.substring(0, pipeIdx)
        }
        // Strip bracketed noise e.g. [Official Video], (Audio), (Lyrics)
        cleaned = cleaned.replace(Regex("""(?i)\s*[\(\[\{][^\)\]\}]*(?:official|video|music video|lyrics?|lyrical|audio|visualizer|hd|4k|remaster|explicit|full song|clean|version|original)[^\)\]\}]*[\)\]\}]"""), "")
        // Strip trailing hyphen noise e.g. - Official Video
        cleaned = cleaned.replace(Regex("""(?i)\s*-\s*(?:official|video|music video|lyrics?|lyrical|audio|visualizer|hd|4k|remaster|full song).*$"""), "")
        // Strip feat / ft
        cleaned = cleaned.replace(Regex("""(?i)\s*[\(\[\{]?(?:feat\.?|ft\.?|featuring)\s+[^()\[\]{}]+[\)\]\}]?"""), "")

        return cleaned
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    fun extractBaseTitle(text: String): String {
        val beforeParen = text.substringBefore("(").substringBefore("[").substringBefore("|").substringBefore("-")
        return normalize(beforeParen)
    }

    private fun hasTokenOverlap(a: String, b: String): Boolean {
        val tokensA = a.split(" ").filter { it.length > 2 }
        val tokensB = b.split(" ").filter { it.length > 2 }
        return tokensA.any { it in tokensB }
    }

    suspend fun getCanvasUrl(
        context: Context,
        title: String,
        artist: String,
        videoId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val normTitle = normalize(title)
        val baseTitle = extractBaseTitle(title)
        val normArtist = normalize(artist)
        if (normTitle.isBlank() && baseTitle.isBlank() && videoId.isNullOrBlank()) return@withContext null

        val cacheKey = "$normTitle|$normArtist"

        // 1. Check in-memory match
        memoryCache[cacheKey]?.let { return@withContext it }

        // 2. Direct Spotify Track ID check via Paxsenix Live API
        if (videoId != null && videoId.startsWith("sp:")) {
            val trackId = videoId.removePrefix("sp:")
            val liveUrl = fetchLiveCanvasFromPaxsenix(trackId)
            if (!liveUrl.isNullOrBlank()) {
                memoryCache[cacheKey] = liveUrl
                Log.d(TAG, "Found live Spotify Canvas via Paxsenix for sp:$trackId -> $liveUrl")
                return@withContext liveUrl
            }
        }

        // 3. Ensure local index is loaded
        ensureIndexLoaded(context)

        val items = canvasItems

        // 4. Exact title & artist match or token overlap
        var matched = items?.firstOrNull { item ->
            val itTitle = normalize(item.song)
            val itArtist = normalize(item.artist)
            (itTitle == normTitle || itTitle == baseTitle) &&
                    (itArtist.isBlank() || normArtist.isBlank() || itArtist.contains(normArtist) || normArtist.contains(itArtist) || hasTokenOverlap(itArtist, normArtist))
        }

        // 5. Fallback exact title match (if title has length >= 4)
        if (matched == null && items != null) {
            matched = items.firstOrNull { item ->
                val itTitle = normalize(item.song)
                (itTitle.length >= 4) && (itTitle == normTitle || itTitle == baseTitle)
            }
        }

        // 6. Fuzzy match fallback
        if (matched == null && items != null) {
            matched = items.firstOrNull { item ->
                val itTitle = normalize(item.song)
                val itArtist = normalize(item.artist)
                (itTitle.length >= 4 && (normTitle.contains(itTitle) || itTitle.contains(normTitle))) &&
                        (itArtist.isBlank() || normArtist.isBlank() || itArtist.contains(normArtist) || normArtist.contains(itArtist) || hasTokenOverlap(itArtist, normArtist))
            }
        }

        matched?.url?.let { validUrl ->
            memoryCache[cacheKey] = validUrl
            Log.d(TAG, "Found Spotify Canvas for \"$title\" by \"$artist\" -> $validUrl")
            return@withContext validUrl
        }

        null
    }

    private fun fetchLiveCanvasFromPaxsenix(trackId: String): String? {
        return try {
            val url = "https://spotify-canva.vercel.app/api/canvas?trackId=$trackId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val root = gson.fromJson(body, JsonObject::class.java)
                val list = root?.getAsJsonArray("canvasesList")
                if (list != null && list.size() > 0) {
                    val first = list[0].asJsonObject
                    val canvasUrl = first.get("canvasUrl")?.asString
                    if (!canvasUrl.isNullOrBlank()) {
                        return canvasUrl
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Paxsenix canvas fetch failed for $trackId: ${e.message}")
            null
        }
    }

    private suspend fun ensureIndexLoaded(context: Context) {
        if (canvasItems != null && (System.currentTimeMillis() - lastIndexFetchTime < CACHE_EXPIRY_MS)) {
            return
        }

        // Try reading from disk cache first
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
            if (cacheFile.exists() && (System.currentTimeMillis() - cacheFile.lastModified() < CACHE_EXPIRY_MS)) {
                val json = cacheFile.readText()
                val parsed = gson.fromJson(json, CanvasIndex::class.java)
                if (parsed?.items?.isNotEmpty() == true) {
                    canvasItems = parsed.items
                    lastIndexFetchTime = cacheFile.lastModified()
                    Log.d(TAG, "Loaded ${parsed.items.size} canvas items from disk cache")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading canvas disk cache: ${e.message}")
        }

        // Fetch fresh index from CDN
        try {
            val req = Request.Builder().url(CDN_INDEX_URL).build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string().orEmpty()
                val parsed = gson.fromJson(body, CanvasIndex::class.java)
                if (parsed?.items?.isNotEmpty() == true) {
                    canvasItems = parsed.items
                    lastIndexFetchTime = System.currentTimeMillis()
                    try {
                        val cacheFile = File(context.cacheDir, CACHE_FILE_NAME)
                        cacheFile.writeText(body)
                    } catch (_: Exception) {}
                    Log.d(TAG, "Fetched ${parsed.items.size} canvas items from CDN")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download canvas index from CDN: ${e.message}")
        }
    }
}
