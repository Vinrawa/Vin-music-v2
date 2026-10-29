package com.vinmusic.soundcloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.LruCache
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.vinmusic.config.RemoteConfigHelper
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SoundCloudApi {
    private const val TAG = "SoundCloudApi"
    private const val BASE_URL = "https://api-v2.soundcloud.com"
    private const val DEFAULT_CLIENT_ID = "ZeISTkUY1Q4so1FsHJKZaJo898iWk0d3"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val waveformCache = LruCache<String, List<Int>>(100)

    private val artworkCache = ConcurrentHashMap<String, String>()
    private val pendingFetches = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var prefs: SharedPreferences? = null
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cachedClientId: String? = null

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val p = context.applicationContext.getSharedPreferences("soundcloud_artwork_cache", Context.MODE_PRIVATE)
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

    fun cacheArtwork(trackId: String, artworkUrl: String?) {
        if (artworkUrl.isNullOrBlank()) return
        val key = if (trackId.startsWith("sc:")) trackId else "sc:$trackId"
        artworkCache[key] = artworkUrl
        prefs?.edit()?.putString(key, artworkUrl)?.apply()
    }

    fun getArtwork(trackId: String): String? {
        val key = if (trackId.startsWith("sc:")) trackId else "sc:$trackId"
        val cached = artworkCache[key] ?: prefs?.getString(key, null)?.also { artworkCache[key] = it }
        if (cached == null) {
            prefetchArtwork(trackId)
        }
        return cached
    }

    fun prefetchArtwork(trackId: String) {
        val key = if (trackId.startsWith("sc:")) trackId else "sc:$trackId"
        if (artworkCache.containsKey(key)) return
        if (!pendingFetches.add(key)) return
        apiScope.launch {
            try {
                fetchTrackArtwork(key)
            } finally {
                pendingFetches.remove(key)
            }
        }
    }

    suspend fun fetchTrackArtwork(trackId: String): String? = withContext(Dispatchers.IO) {
        val key = if (trackId.startsWith("sc:")) trackId else "sc:$trackId"
        val cached = artworkCache[key]
        if (cached != null) return@withContext cached

        val rawId = trackId.removePrefix("sc:")
        val cid = getClientId()
        val trackUrl = "$BASE_URL/tracks/$rawId?client_id=$cid"
        try {
            val req = Request.Builder()
                .url(trackUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext null
            val body = resp.body?.string() ?: return@withContext null
            val track = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return@withContext null
            val user = if (track.has("user") && track.get("user").isJsonObject) track.getAsJsonObject("user") else null
            val artworkUrl = track.getNullableString("artwork_url")
                ?: user?.getNullableString("avatar_url")
            val hdArtwork = artworkUrl?.replace("large.jpg", "t500x500.jpg")
                ?.replace("badge.jpg", "t500x500.jpg")
            if (!hdArtwork.isNullOrBlank()) {
                cacheArtwork(key, hdArtwork)
                return@withContext hdArtwork
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch artwork for track $trackId: ${e.message}")
            null
        }
    }

    /**
     * Resolves an active SoundCloud client_id with a 3-tier fallback strategy:
     * 1. In-memory active key
     * 2. Firebase Remote Config key
     * 3. Hardcoded verified active key
     */
    fun getClientId(): String {
        cachedClientId?.let { return it }

        // Tier 2: Remote Config
        val rcId = runCatching { RemoteConfigHelper.getSoundCloudClientId() }.getOrNull()
        if (!rcId.isNullOrBlank()) {
            cachedClientId = rcId
            return rcId
        }

        // Tier 3: Hardcoded active fallback
        cachedClientId = DEFAULT_CLIENT_ID
        return DEFAULT_CLIENT_ID
    }

    /**
     * Attempts to refresh the client_id dynamically from soundcloud.com web scripts.
     */
    suspend fun refreshClientId(): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://soundcloud.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val html = httpClient.newCall(req).execute().body?.string() ?: ""
            val scriptPattern = Pattern.compile("https://[a-zA-Z0-9.-]*sndcdn.com/assets/[a-zA-Z0-9-]*\\.js")
            val matcher = scriptPattern.matcher(html)
            val scriptUrls = mutableListOf<String>()
            while (matcher.find()) {
                scriptUrls.add(matcher.group())
            }

            for (scriptUrl in scriptUrls.takeLast(6)) {
                val jsReq = Request.Builder().url(scriptUrl).build()
                val js = httpClient.newCall(jsReq).execute().body?.string() ?: continue
                val idPattern = Pattern.compile("client_id[:=][\"']([a-zA-Z0-9]{32})[\"']")
                val idMatcher = idPattern.matcher(js)
                if (idMatcher.find()) {
                    val foundId = idMatcher.group(1)
                    if (!foundId.isNullOrBlank()) {
                        Log.d(TAG, "Successfully refreshed dynamic client_id: ${foundId.take(8)}...")
                        cachedClientId = foundId
                        return@withContext foundId
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic client_id refresh failed: ${e.message}")
        }
        return@withContext getClientId()
    }

    /**
     * Searches SoundCloud tracks by query and maps them into the standard [VideoItem] model.
     * All SoundCloud videoIds are prefixed with `sc:`.
     */
    suspend fun searchTracks(query: String, limit: Int = 40): List<VideoItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val cid = getClientId()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/tracks?q=$encoded&client_id=$cid&limit=$limit"

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    val newCid = refreshClientId()
                    return@withContext searchWithClientId(query, newCid, limit)
                }
                return@withContext emptyList()
            }

            val body = resp.body?.string() ?: return@withContext emptyList()
            parseSearchResponse(body)
        } catch (e: Exception) {
            Log.e(TAG, "SoundCloud search failed for query='$query': ${e.message}")
            emptyList()
        }
    }

    private fun searchWithClientId(query: String, cid: String, limit: Int = 40): List<VideoItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/search/tracks?q=$encoded&client_id=$cid&limit=$limit"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
        val resp = httpClient.newCall(req).execute()
        val body = resp.body?.string() ?: return emptyList()
        return parseSearchResponse(body)
    }

    private fun JsonObject.getNullableString(key: String): String? {
        val elem = get(key) ?: return null
        return if (elem.isJsonPrimitive) elem.asString else null
    }

    private fun JsonObject.getNullableLong(key: String): Long? {
        val elem = get(key) ?: return null
        return if (elem.isJsonPrimitive) {
            runCatching { elem.asLong }.getOrNull()
        } else null
    }

    private fun parseSearchResponse(jsonStr: String): List<VideoItem> {
        val root = runCatching { gson.fromJson(jsonStr, JsonObject::class.java) }.getOrNull() ?: return emptyList()
        val collection = root.getAsJsonArray("collection") ?: return emptyList()
        val items = mutableListOf<VideoItem>()

        for (elem in collection) {
            if (!elem.isJsonObject) continue
            val track = elem.asJsonObject

            // Filter out 30-second SoundCloud Go+ paywalled tracks
            val policy = track.getNullableString("policy")
            if (policy.equals("SNIP", ignoreCase = true) || policy.equals("BLOCK", ignoreCase = true)) {
                continue
            }

            val media = if (track.has("media") && track.get("media").isJsonObject) track.getAsJsonObject("media") else null
            val transcodings = media?.getAsJsonArray("transcodings")
            val hasFullStream = transcodings != null && transcodings.any { tcElem ->
                if (!tcElem.isJsonObject) return@any false
                val tc = tcElem.asJsonObject
                val tcUrl = tc.getNullableString("url") ?: ""
                val isSnipped = tc.has("snipped") && tc.get("snipped").isJsonPrimitive && tc.get("snipped").asBoolean
                !isSnipped && tcUrl.contains("/stream/")
            }
            if (!hasFullStream) continue

            val id = track.getNullableLong("id") ?: continue
            val title = track.getNullableString("title") ?: "Untitled"
            val user = if (track.has("user") && track.get("user").isJsonObject) track.getAsJsonObject("user") else null
            val author = user?.getNullableString("username") ?: "SoundCloud Artist"
            val durationMs = track.getNullableLong("duration") ?: 0L
            val artworkUrl = track.getNullableString("artwork_url")
                ?: user?.getNullableString("avatar_url")
            val playbackCount = track.getNullableLong("playback_count")

            // High-res cover art (replace default 100x100 large.jpg with 500x500)
            val hdArtwork = artworkUrl?.replace("large.jpg", "t500x500.jpg")
                ?.replace("badge.jpg", "t500x500.jpg")
            if (hdArtwork != null) {
                cacheArtwork("sc:$id", hdArtwork)
            }

            items.add(
                VideoItem(
                    videoId = "sc:$id",
                    title = title,
                    author = author,
                    durationText = formatDuration(durationMs),
                    customThumbnailUrl = hdArtwork,
                    viewCount = playbackCount
                )
            )
        }
        return items
    }

    /**
     * Resolves the playable direct audio stream (AAC HLS or MP3 progressive) for a given SoundCloud track.
     */
    suspend fun getStream(trackId: String): SoundCloudStream? = withContext(Dispatchers.IO) {
        val rawId = trackId.removePrefix("sc:")
        val cid = getClientId()
        val trackUrl = "$BASE_URL/tracks/$rawId?client_id=$cid"

        try {
            val req = Request.Builder()
                .url(trackUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                if (resp.code == 401 || resp.code == 403) {
                    val newCid = refreshClientId()
                    return@withContext resolveStreamWithClientId(rawId, newCid)
                }
                return@withContext null
            }

            val body = resp.body?.string() ?: return@withContext null
            val stream = resolveStreamFromBody(body, cid)
            if (stream == null) {
                // If resolving stream failed with active cid, retry once with refreshed client_id
                val newCid = refreshClientId()
                return@withContext resolveStreamWithClientId(rawId, newCid)
            }
            stream
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream for track $trackId: ${e.message}")
            null
        }
    }

    /**
     * Resolves the playable direct audio stream URL for a given SoundCloud track.
     */
    suspend fun getStreamUrl(trackId: String): String? = getStream(trackId)?.url

    private fun resolveStreamWithClientId(rawId: String, cid: String): SoundCloudStream? {
        val trackUrl = "$BASE_URL/tracks/$rawId?client_id=$cid"
        val req = Request.Builder().url(trackUrl).header("User-Agent", "Mozilla/5.0 (Android; Mobile)").build()
        val resp = httpClient.newCall(req).execute()
        val body = resp.body?.string() ?: return null
        return resolveStreamFromBody(body, cid)
    }

    private fun resolveStreamFromBody(body: String, cid: String): SoundCloudStream? {
        val track = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
        val rawTrackId = track.getNullableLong("id")
        val user = if (track.has("user") && track.get("user").isJsonObject) track.getAsJsonObject("user") else null
        val artworkUrl = track.getNullableString("artwork_url") ?: user?.getNullableString("avatar_url")
        val hdArtwork = artworkUrl?.replace("large.jpg", "t500x500.jpg")?.replace("badge.jpg", "t500x500.jpg")
        if (rawTrackId != null && hdArtwork != null) {
            cacheArtwork("sc:$rawTrackId", hdArtwork)
        }

        val media = track.getAsJsonObject("media") ?: return null
        val transcodings = media.getAsJsonArray("transcodings") ?: return null

        val candidates = mutableListOf<JsonObject>()
        for (elem in transcodings) {
            if (!elem.isJsonObject) continue
            val tc = elem.asJsonObject
            val tcUrl = tc.getNullableString("url") ?: ""
            val isSnipped = tc.has("snipped") && tc.get("snipped").isJsonPrimitive && tc.get("snipped").asBoolean
            if (!isSnipped && tcUrl.contains("/stream/")) {
                candidates.add(tc)
            }
        }
        if (candidates.isEmpty()) return null

        // Sort candidates: Full stream first, then AAC HLS (high quality), then progressive MP3
        candidates.sortWith(Comparator { a, b ->
            val aUrl = a.getNullableString("url") ?: ""
            val bUrl = b.getNullableString("url") ?: ""
            val aProto = a.getAsJsonObject("format")?.getNullableString("protocol") ?: ""
            val bProto = b.getAsJsonObject("format")?.getNullableString("protocol") ?: ""
            val aMime = a.getAsJsonObject("format")?.getNullableString("mime_type") ?: ""
            val bMime = b.getAsJsonObject("format")?.getNullableString("mime_type") ?: ""

            val aScore = (if (aUrl.contains("/stream/")) 100 else 0) +
                (if (aProto == "hls" && aMime.contains("mp4")) 20 else 0) +
                (if (aProto == "progressive") 10 else 0)

            val bScore = (if (bUrl.contains("/stream/")) 100 else 0) +
                (if (bProto == "hls" && bMime.contains("mp4")) 20 else 0) +
                (if (bProto == "progressive") 10 else 0)

            bScore.compareTo(aScore)
        })

        for (candidate in candidates) {
            val streamInfoUrl = candidate.getNullableString("url") ?: continue
            val resolveUrl = if (streamInfoUrl.contains("?")) "$streamInfoUrl&client_id=$cid" else "$streamInfoUrl?client_id=$cid"

            try {
                val streamReq = Request.Builder()
                    .url(resolveUrl)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                    .build()

                val streamResp = httpClient.newCall(streamReq).execute()
                if (!streamResp.isSuccessful) continue

                val streamBody = streamResp.body?.string() ?: continue
                val streamObj = runCatching { gson.fromJson(streamBody, JsonObject::class.java) }.getOrNull()
                val directUrl = streamObj?.getNullableString("url") ?: continue

                val protocol = candidate.getAsJsonObject("format")?.getNullableString("protocol") ?: ""
                val isHls = protocol == "hls" || directUrl.contains(".m3u8") || directUrl.contains("media-streaming.soundcloud.cloud")

                return SoundCloudStream(url = directUrl, isHls = isHls)
            } catch (e: Exception) {
                Log.w(TAG, "Transcoding resolution attempt failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Fetches exact audio waveform energy samples for visual seekbar rendering.
     */
    suspend fun getWaveformSamples(waveformUrl: String): List<Int> = withContext(Dispatchers.IO) {
        val cached = waveformCache.get(waveformUrl)
        if (cached != null) return@withContext cached

        val jsonUrl = if (waveformUrl.endsWith(".png")) waveformUrl.replace(".png", ".json") else waveformUrl

        try {
            val req = Request.Builder().url(jsonUrl).build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext emptyList()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val obj = gson.fromJson(body, JsonObject::class.java)
            val samplesArr = obj.getAsJsonArray("samples") ?: return@withContext emptyList()
            val samples = ArrayList<Int>(samplesArr.size())
            for (s in samplesArr) {
                samples.add(s.asInt)
            }
            waveformCache.put(waveformUrl, samples)
            samples
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load waveform: ${e.message}")
            emptyList()
        }
    }

    private val chartCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<Long, List<VideoItem>>>()
    private const val CHART_CACHE_TTL = 30 * 60 * 1000L // 30 minutes

    /**
     * Clears in-memory chart tracks cache.
     */
    fun invalidateChartCache() {
        chartCache.clear()
    }

    /**
     * Fetches chart / underground playlist tracks, filtering out 30-second Go+ paywalled previews.
     */
    suspend fun getChartTracks(playlistId: Long, limit: Int = 20): List<VideoItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        chartCache[playlistId]?.let { (timestamp, cached) ->
            if (now - timestamp < CHART_CACHE_TTL && cached.isNotEmpty()) {
                return@withContext cached
            }
        }

        val cid = getClientId()
        val tracks = fetchChartTracksInternal(playlistId, cid, limit)
        if (tracks.isNotEmpty()) {
            chartCache[playlistId] = Pair(now, tracks)
            return@withContext tracks
        }

        // Retry once with refreshed client_id on failure
        val newCid = refreshClientId()
        val retryTracks = fetchChartTracksInternal(playlistId, newCid, limit)
        if (retryTracks.isNotEmpty()) {
            chartCache[playlistId] = Pair(now, retryTracks)
        }
        retryTracks
    }

    private fun fetchChartTracksInternal(playlistId: Long, cid: String, limit: Int): List<VideoItem> {
        try {
            val pUrl = "$BASE_URL/playlists/$playlistId?client_id=$cid"
            val pReq = Request.Builder()
                .url(pUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val pResp = httpClient.newCall(pReq).execute()
            if (!pResp.isSuccessful) return emptyList()

            val pBody = pResp.body?.string() ?: return emptyList()
            val pObj = runCatching { gson.fromJson(pBody, JsonObject::class.java) }.getOrNull() ?: return emptyList()
            val rawTracks = pObj.getAsJsonArray("tracks") ?: return emptyList()

            val allowedIds = mutableListOf<String>()
            for (elem in rawTracks) {
                if (!elem.isJsonObject) continue
                val t = elem.asJsonObject
                val pol = t.getNullableString("policy")
                if (pol.equals("SNIP", ignoreCase = true) || pol.equals("BLOCK", ignoreCase = true)) {
                    continue
                }
                val id = t.getNullableLong("id") ?: continue
                allowedIds.add(id.toString())
                if (allowedIds.size >= limit) break
            }

            if (allowedIds.isEmpty()) return emptyList()

            val idsParam = allowedIds.joinToString(",")
            val tUrl = "$BASE_URL/tracks?ids=$idsParam&client_id=$cid"
            val tReq = Request.Builder()
                .url(tUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                .build()
            val tResp = httpClient.newCall(tReq).execute()
            if (!tResp.isSuccessful) return emptyList()

            val tBody = tResp.body?.string() ?: return emptyList()
            val tArray = runCatching { gson.fromJson(tBody, JsonArray::class.java) }.getOrNull() ?: return emptyList()

            val results = mutableListOf<VideoItem>()
            for (elem in tArray) {
                if (!elem.isJsonObject) continue
                val track = elem.asJsonObject

                val pol = track.getNullableString("policy")
                if (pol.equals("SNIP", ignoreCase = true) || pol.equals("BLOCK", ignoreCase = true)) {
                    continue
                }

                val id = track.getNullableLong("id") ?: continue
                val title = track.getNullableString("title") ?: "Untitled"
                val user = if (track.has("user") && track.get("user").isJsonObject) track.getAsJsonObject("user") else null
                val author = user?.getNullableString("username") ?: "SoundCloud Artist"
                val durationMs = track.getNullableLong("duration") ?: 0L
                val artworkUrl = track.getNullableString("artwork_url")
                    ?: user?.getNullableString("avatar_url")
                val playbackCount = track.getNullableLong("playback_count")

                val hdArtwork = artworkUrl?.replace("large.jpg", "t500x500.jpg")
                    ?.replace("badge.jpg", "t500x500.jpg")
                if (hdArtwork != null) {
                    cacheArtwork("sc:$id", hdArtwork)
                }

                results.add(
                    VideoItem(
                        videoId = "sc:$id",
                        title = title,
                        author = author,
                        durationText = formatDuration(durationMs),
                        customThumbnailUrl = hdArtwork,
                        viewCount = playbackCount
                    )
                )
            }
            return results
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch chart tracks for playlist $playlistId: ${e.message}")
            return emptyList()
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}

data class SoundCloudChartCategory(
    val id: String,
    val title: String,
    val playlistId: Long
)

val DEFAULT_SOUNDCLOUD_CATEGORIES = listOf(
    SoundCloudChartCategory("electronic", "Phonk & Bass", 1714689240L),
    SoundCloudChartCategory("new_hot", "New & Hot", 1712717571L),
    SoundCloudChartCategory("hip_hop", "Hip-Hop", 1714689219L),
    SoundCloudChartCategory("trending", "Trending", 1714689261L)
)

data class SoundCloudStream(
    val url: String,
    val isHls: Boolean
)

val VideoItem.isSoundCloud: Boolean get() = videoId.startsWith("sc:")
val VideoItem.rawSoundCloudId: String get() = videoId.removePrefix("sc:")
