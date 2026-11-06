package com.vinmusic.tidal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TidalContributor(
    val name: String,
    val role: String
)

data class TidalAlbum(
    val id: Long,
    val title: String,
    val artistName: String,
    val coverUrl: String,
    val numberOfTracks: Int,
    val releaseDate: String?
)

object TidalApi {
    private const val TAG = "TidalApi"
    private const val BASE_URL = "https://api.tidal.com/v1"
    private const val DEFAULT_TOKEN = "CzET4vdadNUFQ5JU"
    private const val PREFS_NAME = "vin_tidal_cache"

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val artworkCache = ConcurrentHashMap<String, String>()
    private val contributorsCache = ConcurrentHashMap<String, List<TidalContributor>>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs = p
                    p.all.forEach { (k, v) ->
                        if (v is String && v.isNotBlank()) {
                            artworkCache[k] = v
                        }
                    }
                }
            }
        }
    }

    fun getCoverUrl(coverUuid: String?, size: String = "1280x1280"): String? {
        if (coverUuid.isNullOrBlank()) return null
        val cleanUuid = coverUuid.replace("-", "/")
        return "https://resources.tidal.com/images/$cleanUuid/$size.jpg"
    }

    fun cacheArtwork(trackId: String, artworkUrl: String?) {
        if (artworkUrl.isNullOrBlank()) return
        val key = if (trackId.startsWith("td:")) trackId else "td:$trackId"
        artworkCache[key] = artworkUrl
        prefs?.edit()?.putString(key, artworkUrl)?.apply()
    }

    fun getArtwork(trackId: String): String? {
        val key = if (trackId.startsWith("td:")) trackId else "td:$trackId"
        return artworkCache[key] ?: prefs?.getString(key, null)?.also { artworkCache[key] = it }
    }

    suspend fun getFeaturedNewTracks(
        countryCode: String = "IN",
        limit: Int = 30
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/featured/new/tracks?countryCode=$countryCode&limit=$limit"
        try {
            val req = Request.Builder()
                .url(url)
                .header("x-tidal-token", DEFAULT_TOKEN)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "getFeaturedNewTracks failed with code ${resp.code}")
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            parseTracksJson(body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load featured new tracks: ${e.message}")
            emptyList()
        }
    }

    suspend fun getFeaturedNewAlbums(
        countryCode: String = "IN",
        limit: Int = 20
    ): List<TidalAlbum> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/featured/new/albums?countryCode=$countryCode&limit=$limit"
        try {
            val req = Request.Builder()
                .url(url)
                .header("x-tidal-token", DEFAULT_TOKEN)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()

            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val items = root.getAsJsonArray("items") ?: return@withContext emptyList()

            val result = ArrayList<TidalAlbum>(items.size())
            for (elem in items) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val id = obj.get("id")?.asLong ?: continue
                val title = obj.get("title")?.asString ?: "Unknown Album"
                val artistName = obj.getAsJsonObject("artist")?.get("name")?.asString
                    ?: obj.getAsJsonArray("artists")?.firstOrNull()?.asJsonObject?.get("name")?.asString
                    ?: "Unknown Artist"
                val coverUuid = obj.get("cover")?.asString
                val coverUrl = getCoverUrl(coverUuid, "640x640") ?: ""
                val numTracks = obj.get("numberOfTracks")?.asInt ?: 0
                val releaseDate = obj.get("releaseDate")?.asString

                result.add(
                    TidalAlbum(
                        id = id,
                        title = title,
                        artistName = artistName,
                        coverUrl = coverUrl,
                        numberOfTracks = numTracks,
                        releaseDate = releaseDate
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load featured new albums: ${e.message}")
            emptyList()
        }
    }

    suspend fun searchTracks(
        query: String,
        countryCode: String = "IN",
        limit: Int = 25
    ): List<VideoItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$BASE_URL/search?query=$enc&countryCode=$countryCode&limit=$limit"

        try {
            val req = Request.Builder()
                .url(url)
                .header("x-tidal-token", DEFAULT_TOKEN)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()

            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val tracksObj = root.getAsJsonObject("tracks") ?: return@withContext emptyList()
            parseTracksArray(tracksObj.getAsJsonArray("items"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search Tidal tracks: ${e.message}")
            emptyList()
        }
    }

    suspend fun getTrackContributors(
        trackId: String,
        countryCode: String = "IN"
    ): List<TidalContributor> = withContext(Dispatchers.IO) {
        val cleanId = trackId.removePrefix("td:")
        val cached = contributorsCache[cleanId]
        if (cached != null) return@withContext cached

        val url = "$BASE_URL/tracks/$cleanId/contributors?countryCode=$countryCode"
        try {
            val req = Request.Builder()
                .url(url)
                .header("x-tidal-token", DEFAULT_TOKEN)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()

            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = gson.fromJson(body, JsonObject::class.java)
            val items = root.getAsJsonArray("items") ?: return@withContext emptyList()

            val result = ArrayList<TidalContributor>(items.size())
            for (elem in items) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val name = obj.get("name")?.asString ?: continue
                val role = obj.get("role")?.asString ?: "Contributor"
                result.add(TidalContributor(name = name, role = role))
            }
            contributorsCache[cleanId] = result
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch track contributors: ${e.message}")
            emptyList()
        }
    }

    private fun parseTracksJson(body: String): List<VideoItem> {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return emptyList()
        val items = root.getAsJsonArray("items") ?: return emptyList()
        return parseTracksArray(items)
    }

    private fun parseTracksArray(items: JsonArray?): List<VideoItem> {
        if (items == null) return emptyList()
        val list = ArrayList<VideoItem>(items.size())

        for (elem in items) {
            if (!elem.isJsonObject) continue
            val obj = elem.asJsonObject
            val id = obj.get("id")?.asLong ?: continue
            val title = obj.get("title")?.asString ?: "Unknown Title"

            // Artist Name
            val artistName = obj.getAsJsonObject("artist")?.get("name")?.asString
                ?: obj.getAsJsonArray("artists")?.let { arr ->
                    val names = mutableListOf<String>()
                    for (a in arr) {
                        a.asJsonObject?.get("name")?.asString?.let { names.add(it) }
                    }
                    if (names.isNotEmpty()) names.joinToString(", ") else null
                }
                ?: "Unknown Artist"

            // Duration
            val durationSec = obj.get("duration")?.asLong ?: 0L
            val mins = durationSec / 60
            val secs = durationSec % 60
            val durationText = String.format("%d:%02d", mins, secs)

            // Album Cover
            val albumObj = obj.getAsJsonObject("album")
            val coverUuid = albumObj?.get("cover")?.asString
            val coverUrl = getCoverUrl(coverUuid, "1280x1280")

            if (coverUrl != null) {
                cacheArtwork("td:$id", coverUrl)
            }

            list.add(
                VideoItem(
                    videoId = "td:$id",
                    title = title,
                    author = artistName,
                    durationText = durationText,
                    customThumbnailUrl = coverUrl
                )
            )
        }
        return list
    }
}

val VideoItem.isTidal: Boolean get() = videoId.startsWith("td:")
val VideoItem.rawTidalId: String get() = videoId.removePrefix("td:")
