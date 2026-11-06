package com.vinmusic.spotify

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SpotifyUserProfile(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String?,
    val product: String
)

data class SpotifyUserPlaylist(
    val id: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val trackCount: Int,
    val ownerName: String
)

object SpotifySession {
    private const val TAG = "SpotifySession"
    private const val PREFS_NAME = "vin_music_spotify_prefs"
    private const val KEY_SP_DC = "spotify_sp_dc"
    private const val KEY_ACCESS_TOKEN = "spotify_access_token"
    private const val KEY_TOKEN_EXPIRY = "spotify_token_expiry"
    private const val KEY_USER_NAME = "spotify_user_name"
    private const val KEY_USER_EMAIL = "spotify_user_email"
    private const val KEY_USER_AVATAR = "spotify_user_avatar"
    private const val KEY_USER_PRODUCT = "spotify_user_product"

    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache of resolved Spotify track -> VideoItem so repeat clicks are instant
    private val resolvedTrackCache = ConcurrentHashMap<String, VideoItem>()

    // Global artwork cache for Spotify tracks
    private val artworkCache = ConcurrentHashMap<String, String>()

    fun cacheArtwork(trackId: String, url: String?) {
        if (url.isNullOrBlank()) return
        val clean = trackId.removePrefix("sp:")
        artworkCache[clean] = url
        artworkCache["sp:$clean"] = url
    }

    fun getCachedArtwork(trackIdOrVideoId: String): String? {
        val clean = trackIdOrVideoId.removePrefix("sp:")
        return artworkCache[clean] ?: artworkCache["sp:$clean"]
    }

    private fun JsonElement?.optObj(): JsonObject? = if (this != null && this.isJsonObject) this.asJsonObject else null
    private fun JsonElement?.optArr(): JsonArray? = if (this != null && this.isJsonArray) this.asJsonArray else null
    private fun JsonElement?.optStr(): String? = if (this != null && this.isJsonPrimitive && this.asJsonPrimitive.isString) this.asString else null
    private fun JsonElement?.optInt(default: Int = 0): Int = if (this != null && this.isJsonPrimitive && this.asJsonPrimitive.isNumber) this.asInt else default
    private fun JsonElement?.optLong(default: Long = 0L): Long = if (this != null && this.isJsonPrimitive && this.asJsonPrimitive.isNumber) this.asLong else default

    private fun JsonObject?.optObj(key: String): JsonObject? = this?.get(key)?.optObj()
    private fun JsonObject?.optArr(key: String): JsonArray? = this?.get(key)?.optArr()
    private fun JsonObject?.optStr(key: String): String? = this?.get(key)?.optStr()
    private fun JsonObject?.optInt(key: String, default: Int = 0): Int = this?.get(key)?.optInt(default) ?: default
    private fun JsonObject?.optLong(key: String, default: Long = 0L): Long = this?.get(key)?.optLong(default) ?: default

    val DEFAULT_SECRETS = mapOf(
        61 to intArrayOf(44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78),
        60 to intArrayOf(79, 109, 69, 123, 90, 65, 46, 74, 94, 34, 58, 48, 70, 71, 92, 92, 85, 122, 63, 91, 64, 87, 87),
        59 to intArrayOf(123, 105, 79, 70, 110, 59, 52, 125, 60, 49, 80, 70, 89, 75, 80, 86, 63, 53, 123, 37, 117, 49, 52, 93, 77, 62, 47, 86, 48, 104, 68, 72)
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasSession(context: Context): Boolean {
        val spDc = prefs(context).getString(KEY_SP_DC, null)
        val token = prefs(context).getString(KEY_ACCESS_TOKEN, null)
        return !spDc.isNullOrBlank() || !token.isNullOrBlank()
    }

    fun getSpDc(context: Context): String? =
        prefs(context).getString(KEY_SP_DC, null)

    fun getUserName(context: Context): String =
        prefs(context).getString(KEY_USER_NAME, "Spotify User") ?: "Spotify User"

    fun getUserEmail(context: Context): String =
        prefs(context).getString(KEY_USER_EMAIL, "") ?: ""

    fun getUserAvatar(context: Context): String? =
        prefs(context).getString(KEY_USER_AVATAR, null)

    fun getUserProduct(context: Context): String =
        prefs(context).getString(KEY_USER_PRODUCT, "free") ?: "free"

    fun saveSession(context: Context, spDc: String) {
        prefs(context).edit()
            .putString(KEY_SP_DC, spDc.trim())
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .apply()
    }

    fun saveAccessToken(context: Context, token: String, expiryMs: Long = System.currentTimeMillis() + 3600_000L) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, token.trim())
            .putLong(KEY_TOKEN_EXPIRY, expiryMs)
            .apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit().clear().apply()
        resolvedTrackCache.clear()
    }

    /**
     * Generates a 6-digit Time-based One-Time Password matching Spotify Web Player's algorithm.
     */
    fun generateTotp(timestampSeconds: Long, secret: IntArray): String {
        val transformed = secret.mapIndexed { index, elem ->
            elem xor ((index % 33) + 9)
        }
        val joined = transformed.joinToString("")
        val secretBytes = joined.toByteArray(Charsets.UTF_8)
        val counter = timestampSeconds / 30L
        val counterBytes = java.nio.ByteBuffer.allocate(8).putLong(counter).array()
        val mac = javax.crypto.Mac.getInstance("HmacSHA1")
        mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)
        val offset = hash.last().toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        val code = binary % 1_000_000
        return code.toString().padStart(6, '0')
    }

    private fun fetchServerTime(): Long? {
        return try {
            val req = Request.Builder()
                .url("https://open.spotify.com/api/server-time")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "application/json")
                .build()
            val resp = http.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                json.get("serverTime")?.asLong
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get server time: ${e.message}")
            null
        }
    }

    /**
     * Obtains a valid Bearer access token using the user's sp_dc cookie and Spotify's TOTP token handshake.
     */
    suspend fun getAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val cachedToken = p.getString(KEY_ACCESS_TOKEN, null)
        val expiry = p.getLong(KEY_TOKEN_EXPIRY, 0L)
        if (!cachedToken.isNullOrBlank() && System.currentTimeMillis() < expiry - 60_000L) {
            return@withContext cachedToken
        }

        val spDc = getSpDc(context)
        if (spDc.isNullOrBlank() && !cachedToken.isNullOrBlank()) {
            return@withContext cachedToken
        }

        val serverTime = fetchServerTime() ?: (System.currentTimeMillis() / 1000L)
        val reasons = listOf("transport", "init")

        for (reason in reasons) {
            for ((version, secret) in DEFAULT_SECRETS) {
                try {
                    val totp = generateTotp(serverTime, secret)
                    val url = "https://open.spotify.com/api/token?reason=$reason&productType=web-player&totp=$totp&totpVer=$version&totpServer=$totp"
                    val builder = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                        .header("Accept", "application/json")
                        .header("Referer", "https://open.spotify.com/")
                        .header("Origin", "https://open.spotify.com")

                    if (!spDc.isNullOrBlank()) {
                        val cookieHeader = if (spDc.contains("sp_dc=")) spDc else "sp_dc=$spDc"
                        builder.header("Cookie", cookieHeader)
                    }

                    val resp = http.newCall(builder.build()).execute()
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val json = gson.fromJson(bodyStr, JsonObject::class.java)
                        val token = json.get("accessToken")?.asString
                        val expMs = json.get("accessTokenExpirationTimestampMs")?.asLong ?: (System.currentTimeMillis() + 3600_000L)
                        if (!token.isNullOrBlank()) {
                            Log.i(TAG, "Successfully acquired Spotify access token using TOTP ver $version, reason=$reason")
                            saveAccessToken(context, token, expMs)
                            return@withContext token
                        }
                    } else {
                        Log.w(TAG, "api/token failed (ver $version, reason $reason, code ${resp.code}): $bodyStr")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "api/token attempt failed (ver $version, reason $reason): ${e.message}")
                }
            }
        }

        Log.e(TAG, "Failed to obtain any valid Spotify access token")
        null
    }

    /**
     * Executes a persisted GraphQL query on Spotify's internal Pathfinder API (bypassing REST 429 limits).
     */
    private fun queryPathfinder(
        token: String,
        operationName: String,
        sha256Hash: String,
        variables: JsonObject
    ): JsonObject? {
        val extensions = JsonObject().apply {
            val pq = JsonObject()
            pq.addProperty("version", 1)
            pq.addProperty("sha256Hash", sha256Hash)
            add("persistedQuery", pq)
        }
        val payload = JsonObject().apply {
            addProperty("operationName", operationName)
            add("variables", variables)
            add("extensions", extensions)
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = payload.toString().toRequestBody(mediaType)
        val req = Request.Builder()
            .url("https://api-partner.spotify.com/pathfinder/v1/query")
            .post(body)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("app-platform", "WebPlayer")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Referer", "https://open.spotify.com/")
            .header("Origin", "https://open.spotify.com")
            .build()

        return try {
            val resp = http.newCall(req).execute()
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                gson.fromJson(bodyStr, JsonObject::class.java)
            } else {
                Log.w(TAG, "Pathfinder $operationName failed (code ${resp.code}): $bodyStr")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pathfinder $operationName error: ${e.message}")
            null
        }
    }

    /**
     * Fetches current user profile from Spotify Web API or Pathfinder.
     */
    suspend fun fetchProfile(context: Context): SpotifyUserProfile? = withContext(Dispatchers.IO) {
        val token = getAccessToken(context)
        if (token == null) {
            Log.e(TAG, "fetchProfile: No access token available")
            return@withContext null
        }

        // 1. Try Pathfinder profileAttributes
        try {
            val pfRes = queryPathfinder(token, "profileAttributes", "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0", JsonObject())
            val meObj = pfRes?.optObj("data")?.optObj("me")
            val profObj = meObj?.optObj("profile")
            if (profObj != null) {
                val name = profObj.get("name")?.optStr()
                    ?: profObj.get("displayName")?.optStr()
                    ?: meObj?.get("username")?.optStr()
                    ?: "Spotify User"
                val avatarUrl = profObj.optObj("avatar")?.optArr("sources")?.firstOrNull()?.optObj()?.get("url")?.optStr()
                val id = meObj?.get("username")?.optStr() ?: "spotify_user"
                val email = getUserEmail(context)
                val product = getUserProduct(context)

                prefs(context).edit()
                    .putString(KEY_USER_NAME, name)
                    .putString(KEY_USER_AVATAR, avatarUrl)
                    .apply()

                return@withContext SpotifyUserProfile(id, name, email, avatarUrl, product)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pathfinder profileAttributes failed: ${e.message}")
        }

        // 2. Try REST API
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .build()

            val resp = http.newCall(req).execute()
            Log.i(TAG, "fetchProfile response code: ${resp.code}")
            if (resp.isSuccessful) {
                val json = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                val id = json.get("id")?.asString.orEmpty()
                val name = json.get("display_name")?.asString ?: "Spotify User"
                val email = json.get("email")?.asString.orEmpty()
                val product = json.get("product")?.asString ?: "free"
                var avatarUrl: String? = null
                val images = json.getAsJsonArray("images")
                if (images != null && images.size() > 0) {
                    avatarUrl = images[0].asJsonObject.get("url")?.asString
                }

                prefs(context).edit()
                    .putString(KEY_USER_NAME, name)
                    .putString(KEY_USER_EMAIL, email)
                    .putString(KEY_USER_AVATAR, avatarUrl)
                    .putString(KEY_USER_PRODUCT, product)
                    .apply()

                return@withContext SpotifyUserProfile(id, name, email, avatarUrl, product)
            } else {
                Log.w(TAG, "fetchProfile failed code ${resp.code}: ${resp.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Spotify profile: ${e.message}", e)
        }

        // 3. Fallback: return cached profile if we have session
        if (hasSession(context)) {
            return@withContext SpotifyUserProfile(
                id = "spotify_user",
                displayName = getUserName(context),
                email = getUserEmail(context),
                avatarUrl = getUserAvatar(context),
                product = getUserProduct(context)
            )
        }
        null
    }

    /**
     * Fetches user's saved personal playlists using Pathfinder libraryV3 with REST fallback.
     */
    suspend fun fetchPlaylists(context: Context): List<SpotifyUserPlaylist> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context)
        if (token == null) {
            Log.e(TAG, "fetchPlaylists: No access token available")
            return@withContext emptyList()
        }
        val list = mutableListOf<SpotifyUserPlaylist>()

        // 1. Try Spotify Pathfinder GraphQL API (libraryV3)
        try {
            val vars = JsonObject().apply {
                val filtersArr = JsonArray().apply { add("Playlists") }
                add("filters", filtersArr)
                add("order", com.google.gson.JsonNull.INSTANCE)
                addProperty("textFilter", "")
                val featuresArr = JsonArray().apply {
                    add("LIKED_SONGS")
                    add("YOUR_EPISODES_V2")
                    add("PRERELEASES")
                    add("EVENTS")
                }
                add("features", featuresArr)
                addProperty("limit", 50)
                addProperty("offset", 0)
                addProperty("flatten", false)
                add("expandedFolders", JsonArray())
                add("folderUri", com.google.gson.JsonNull.INSTANCE)
                addProperty("includeFoldersWhenFlattening", true)
            }

            val hashes = listOf(
                "2de10199b2441d6e4ae875f27d2db361020c399fb10b03951120223fbed10b08",
                "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455"
            )

            for (hash in hashes) {
                val pfRes = queryPathfinder(token, "libraryV3", hash, vars)
                val items = pfRes?.optObj("data")?.optObj("me")?.optObj("libraryV3")?.optArr("items")
                if (items != null && items.size() > 0) {
                    for (elem in items) {
                        try {
                            val itemObj = elem.optObj()?.optObj("item") ?: continue
                            val uri = itemObj.get("_uri")?.optStr() ?: itemObj.get("uri")?.optStr() ?: continue
                            val id = uri.substringAfterLast(":")
                            val data = itemObj.optObj("data") ?: itemObj
                            val name = data.get("name")?.optStr() ?: "Untitled Playlist"
                            val desc = data.get("description")?.optStr()
                            val trackCount = data.optObj("tracks")?.get("totalCount")?.optInt()
                                ?: data.get("totalTracks")?.optInt()
                                ?: data.optObj("content")?.get("totalCount")?.optInt()
                                ?: data.optObj("tracksV2")?.get("totalCount")?.optInt()
                                ?: data.optObj("tracks")?.get("total")?.optInt()
                                ?: 0
                            val ownerName = data.optObj("ownerV2")?.optObj("data")?.get("name")?.optStr()
                                ?: data.optObj("owner")?.get("name")?.optStr()
                                ?: data.optObj("owner")?.get("display_name")?.optStr()
                                ?: "Spotify"
                            var imgUrl: String? = null
                            val imagesItems = data.optObj("images")?.optArr("items")
                            if (imagesItems != null && imagesItems.size() > 0) {
                                val sources = imagesItems[0].optObj()?.optArr("sources")
                                if (sources != null && sources.size() > 0) {
                                    imgUrl = sources[0].optObj()?.get("url")?.optStr()
                                }
                            }
                            list.add(SpotifyUserPlaylist(id, name, desc, imgUrl, trackCount, ownerName))
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing libraryV3 item: ${e.message}")
                        }
                    }
                    if (list.isNotEmpty()) {
                        Log.i(TAG, "Successfully fetched ${list.size} playlists via Pathfinder libraryV3")
                        return@withContext list
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pathfinder libraryV3 query failed: ${e.message}")
        }

        // 2. Fallback to public REST API
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/playlists?limit=50")
                .header("Authorization", "Bearer $token")
                .build()

            val resp = http.newCall(req).execute()
            Log.i(TAG, "fetchPlaylists REST fallback response code: ${resp.code}")
            if (resp.isSuccessful) {
                val json = gson.fromJson(resp.body?.string().orEmpty(), JsonObject::class.java)
                val items = json?.optArr("items") ?: JsonArray()
                for (elem in items) {
                    val obj = elem.optObj() ?: continue
                    val id = obj.get("id")?.optStr() ?: continue
                    val name = obj.get("name")?.optStr() ?: "Untitled Playlist"
                    val desc = obj.get("description")?.optStr()
                    val trackCount = obj.optObj("tracks")?.get("total")?.optInt()
                        ?: obj.optObj("tracks")?.get("totalCount")?.optInt()
                        ?: 0
                    val ownerName = obj.optObj("owner")?.get("display_name")?.optStr() ?: "Spotify"
                    var imgUrl: String? = null
                    val images = obj.optArr("images")
                    if (images != null && images.size() > 0) {
                        imgUrl = images[0].optObj()?.get("url")?.optStr()
                    }
                    list.add(SpotifyUserPlaylist(id, name, desc, imgUrl, trackCount, ownerName))
                }
            } else {
                Log.w(TAG, "fetchPlaylists REST fallback failed code ${resp.code}: ${resp.body?.string()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Spotify playlists: ${e.message}", e)
        }
        list
    }

    /**
     * Fetches tracks from a Spotify playlist with rich metadata (Spotify album art, duration).
     * Uses Pathfinder fetchPlaylist with REST fallback.
     * Returns VideoItems with "sp:<id>" for instant zero-lag rendering.
     */
    suspend fun fetchPlaylistTracks(context: Context, playlistId: String): List<VideoItem> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context) ?: return@withContext emptyList()
        val isLikedCollection = playlistId.equals("tracks", ignoreCase = true) ||
            playlistId.contains("collection", ignoreCase = true) ||
            playlistId.contains("liked", ignoreCase = true)
        if (isLikedCollection) {
            return@withContext fetchLikedTracks(context, limit = 500)
        }
        val list = mutableListOf<VideoItem>()

        // 1. Try Spotify Pathfinder GraphQL API (fetchPlaylist)
        try {
            val formattedUri = if (playlistId.startsWith("spotify:playlist:") || playlistId.startsWith("spotify:collection:")) {
                playlistId
            } else {
                "spotify:playlist:$playlistId"
            }
            val vars = JsonObject().apply {
                addProperty("uri", formattedUri)
                addProperty("offset", 0)
                addProperty("limit", 100)
                addProperty("enableWatchFeedEntrypoint", false)
            }
            val pfRes = queryPathfinder(token, "fetchPlaylist", "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf", vars)
            val dataObj = pfRes?.optObj("data")
            val contentItems = dataObj?.optObj("playlistV2")?.optObj("content")?.optArr("items")
                ?: dataObj?.optObj("me")?.optObj("library")?.optObj("tracks")?.optArr("items")
            if (contentItems != null && contentItems.size() > 0) {
                for (elem in contentItems) {
                    try {
                        val itemData = elem.optObj()?.optObj("itemV2")?.optObj("data")
                            ?: elem.optObj()?.optObj("itemV3")?.optObj("data")
                            ?: elem.optObj()?.optObj("track")?.optObj("data")
                            ?: elem.optObj()?.optObj("track")
                            ?: continue
                        val uri = itemData.get("uri")?.optStr() ?: itemData.get("_uri")?.optStr() ?: continue
                        val id = uri.substringAfterLast(":")
                        val name = itemData.get("name")?.optStr() ?: continue
                        val artistsArr = itemData.optObj("artists")?.optArr("items")
                            ?: itemData.optArr("artists")
                        val artistName = if (artistsArr != null && artistsArr.size() > 0) {
                            artistsArr[0].optObj()?.optObj("profile")?.get("name")?.optStr()
                                ?: artistsArr[0].optObj()?.get("name")?.optStr()
                                ?: "Unknown"
                        } else "Unknown"
                        val durationMs = itemData.optObj("trackDuration")?.get("totalMilliseconds")?.optLong()
                            ?: itemData.get("duration_ms")?.optLong()
                            ?: 180000L
                        val minutes = durationMs / 1000 / 60
                        val seconds = (durationMs / 1000) % 60
                        val durationText = String.format("%d:%02d", minutes, seconds)
                        var thumbUrl: String? = null
                        val album = itemData.optObj("albumOfTrack") ?: itemData.optObj("album")
                        val coverSources = album?.optObj("coverArt")?.optArr("sources")
                            ?: album?.optArr("images")
                        if (coverSources != null && coverSources.size() > 0) {
                            thumbUrl = coverSources[0].optObj()?.get("url")?.optStr()
                        }
                        if (thumbUrl != null) {
                            cacheArtwork(id, thumbUrl)
                        }

                        list.add(
                            VideoItem(
                                videoId = "sp:$id",
                                title = name,
                                author = artistName,
                                durationText = durationText,
                                customThumbnailUrl = thumbUrl
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing fetchPlaylist item: ${e.message}")
                    }
                }
                if (list.isNotEmpty()) {
                    Log.i(TAG, "Successfully fetched ${list.size} tracks via Pathfinder fetchPlaylist")
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pathfinder fetchPlaylist failed: ${e.message}")
        }

        // 2. Fallback to public REST API with pagination loop
        try {
            val cleanId = playlistId.substringAfterLast(":")
            var nextUrl: String? = "https://api.spotify.com/v1/playlists/$cleanId/tracks?limit=100"
            while (nextUrl != null && list.size < 500) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .build()

                val resp = http.newCall(req).execute()
                Log.i(TAG, "fetchPlaylistTracks REST fallback response code: ${resp.code}")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchPlaylistTracks failed code ${resp.code}: ${resp.body?.string()}")
                    break
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                val items = json?.optArr("items") ?: JsonArray()
                for (elem in items) {
                    val trackObj = elem.optObj()?.optObj("track") ?: continue
                    val id = trackObj.get("id")?.optStr() ?: "track_${System.nanoTime()}"
                    val title = trackObj.get("name")?.optStr() ?: continue
                    val artistsArr = trackObj.optArr("artists")
                    val artist = if (artistsArr != null && artistsArr.size() > 0) {
                        artistsArr[0].optObj()?.get("name")?.optStr() ?: "Unknown"
                    } else "Unknown"
                    val durationMs = trackObj.get("duration_ms")?.optLong() ?: 180000L
                    val minutes = durationMs / 1000 / 60
                    val seconds = (durationMs / 1000) % 60
                    val durationText = String.format("%d:%02d", minutes, seconds)
                    var thumbUrl: String? = null
                    val album = trackObj.optObj("album")
                    val images = album?.optArr("images")
                    if (images != null && images.size() > 0) {
                        thumbUrl = images[0].optObj()?.get("url")?.optStr()
                    }
                    if (thumbUrl != null) {
                        cacheArtwork(id, thumbUrl)
                    }

                    list.add(
                        VideoItem(
                            videoId = "sp:$id",
                            title = title,
                            author = artist,
                            durationText = durationText,
                            customThumbnailUrl = thumbUrl
                        )
                    )
                }
                nextUrl = json?.get("next")?.optStr()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch playlist tracks: ${e.message}", e)
        }
        list
    }

    /**
     * Resolves a provider (Spotify/Tidal) track title + artist into a playable YouTube VideoItem.
     *
     * Result ranking matters here: a bare `search().firstOrNull()` happily returns a music video,
     * a 10-hour loop or a slowed re-upload, so candidates are scored and the obviously wrong ones
     * (compilations, non-music videos, unofficial modifications, unrelated titles) are rejected.
     */
    suspend fun resolveTrackDirect(title: String, artist: String): VideoItem? {
        val key = "$title|$artist".lowercase()
        resolvedTrackCache[key]?.let { return it }

        val results = runCatching { InnerTube.search("$title $artist") }.getOrNull().orEmpty()
        if (results.isEmpty()) return null

        val wantedTitle = com.vinmusic.recommendation.RecommendationManager.normalizeTitle(title)
        val wantedArtist = com.vinmusic.recommendation.RecommendationManager.normalizeArtistName(artist)

        val best = results.asSequence()
            .filter { it.videoId.isNotBlank() }
            .filterNot { it.videoId.startsWith("sc:") || it.videoId.startsWith("td:") || it.videoId.startsWith("sp:") }
            .filterNot { com.vinmusic.recommendation.RecommendationManager.isCompilationTrack(it.title, it.durationText) }
            .filterNot { com.vinmusic.recommendation.RecommendationManager.isNonMusicVideo(it.title, it.author) }
            .filterNot { com.vinmusic.recommendation.RecommendationManager.isUnofficialContent(it.title, it.author) }
            .map { item ->
                val itemTitle = com.vinmusic.recommendation.RecommendationManager.normalizeTitle(item.title)
                val itemArtist = com.vinmusic.recommendation.RecommendationManager.normalizeArtistName(item.author)
                var score = 0
                // The track title must actually be present in the candidate title.
                if (itemTitle.contains(wantedTitle) || wantedTitle.contains(itemTitle)) score += 4
                // Prefer uploads whose channel matches the track artist.
                if (wantedArtist.isNotBlank() && itemArtist.contains(wantedArtist)) score += 3
                // Studio releases live on Topic channels / VEVO far more often than on MV uploads.
                val authorLow = item.author.lowercase()
                if (authorLow.endsWith(" - topic") || authorLow.contains("vevo")) score += 2
                item to score
            }
            .filter { it.second >= 4 }
            .sortedByDescending { it.second }
            .map { it.first }
            .firstOrNull()

        if (best != null) {
            resolvedTrackCache[key] = best
            return best
        }
        return null
    }

    suspend fun resolveTrack(title: String, artist: String): VideoItem {
        return resolveTrackDirect(title, artist) ?: VideoItem(
            videoId = "search:${java.net.URLEncoder.encode("$title $artist", "UTF-8")}",
            title = title,
            author = artist,
            durationText = "3:30"
        )
    }

    /**
     * Fetches user's Spotify Liked tracks with rich metadata.
     * Uses Pathfinder fetchLibraryTracks with collection fallback and REST fallback.
     */
    suspend fun fetchLikedTracks(context: Context, limit: Int = 500): List<VideoItem> = withContext(Dispatchers.IO) {
        val token = getAccessToken(context) ?: return@withContext emptyList()
        val list = mutableListOf<VideoItem>()

        // 1. Primary: Spotify REST API with cursor pagination (loads complete library up to limit)
        try {
            var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=50"
            while (nextUrl != null && list.size < limit) {
                val req = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .build()

                val resp = http.newCall(req).execute()
                Log.i(TAG, "fetchLikedTracks REST response code: ${resp.code} for $nextUrl")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "fetchLikedTracks failed code ${resp.code}: ${resp.body?.string()}")
                    break
                }
                val bodyStr = resp.body?.string().orEmpty()
                val json = gson.fromJson(bodyStr, JsonObject::class.java)
                val items = json?.optArr("items") ?: JsonArray()
                for (elem in items) {
                    val trackObj = elem.optObj()?.optObj("track") ?: continue
                    val id = trackObj.get("id")?.optStr() ?: "track_${System.nanoTime()}"
                    val title = trackObj.get("name")?.optStr() ?: continue
                    val artistsArr = trackObj.optArr("artists")
                    val artist = if (artistsArr != null && artistsArr.size() > 0) {
                        artistsArr[0].optObj()?.get("name")?.optStr() ?: "Unknown"
                    } else "Unknown"
                    val durationMs = trackObj.get("duration_ms")?.optLong() ?: 180000L
                    val minutes = durationMs / 1000 / 60
                    val seconds = (durationMs / 1000) % 60
                    val durationText = String.format("%d:%02d", minutes, seconds)
                    var thumbUrl: String? = null
                    val album = trackObj.optObj("album")
                    val images = album?.optArr("images")
                    if (images != null && images.size() > 0) {
                        thumbUrl = images[0].optObj()?.get("url")?.optStr()
                    }
                    if (thumbUrl != null) {
                        cacheArtwork(id, thumbUrl)
                    }

                    list.add(
                        VideoItem(
                            videoId = "sp:$id",
                            title = title,
                            author = artist,
                            durationText = durationText,
                            customThumbnailUrl = thumbUrl
                        )
                    )
                }
                nextUrl = json?.get("next")?.optStr()
            }
            if (list.isNotEmpty()) {
                Log.i(TAG, "Successfully fetched ${list.size} liked tracks via paginated REST API")
                return@withContext list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch liked tracks via REST: ${e.message}", e)
        }

        // 2. Fallback: Spotify Pathfinder GraphQL API (fetchLibraryTracks)
        try {
            val vars = JsonObject().apply {
                addProperty("uri", "spotify:collection:tracks")
                addProperty("offset", 0)
                addProperty("limit", limit.coerceAtMost(50))
            }
            val hashes = listOf(
                "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
                "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf"
            )
            for (hash in hashes) {
                val pfRes = queryPathfinder(token, "fetchLibraryTracks", hash, vars)
                val dataObj = pfRes?.optObj("data")
                val trackItems = dataObj?.optObj("me")?.optObj("library")?.optObj("tracks")?.optArr("items")
                    ?: dataObj?.optObj("playlistV2")?.optObj("content")?.optArr("items")
                if (trackItems != null && trackItems.size() > 0) {
                    for (elem in trackItems) {
                        try {
                            val trackObj = elem.optObj()?.optObj("track")
                                ?: elem.optObj()?.optObj("itemV2")?.optObj("data")
                                ?: elem.optObj()?.optObj("itemV3")?.optObj("data")
                                ?: elem.optObj()?.optObj("item")
                                ?: continue
                            val innerData = trackObj.optObj("data") ?: trackObj
                            val uri = trackObj.get("_uri")?.optStr()
                                ?: trackObj.get("uri")?.optStr()
                                ?: innerData.get("uri")?.optStr()
                                ?: continue
                            val id = uri.substringAfterLast(":")
                            val title = innerData.get("name")?.optStr() ?: continue
                            val artistsArr = innerData.optObj("artists")?.optArr("items")
                                ?: innerData.optArr("artists")
                            val artist = if (artistsArr != null && artistsArr.size() > 0) {
                                val first = artistsArr[0].optObj()
                                first?.optObj("profile")?.get("name")?.optStr()
                                    ?: first?.get("name")?.optStr() ?: "Unknown"
                            } else "Unknown"
                            val durationMs = innerData.optObj("trackDuration")?.get("totalMilliseconds")?.optLong()
                                ?: innerData.get("duration_ms")?.optLong() ?: 180000L
                            val minutes = durationMs / 1000 / 60
                            val seconds = (durationMs / 1000) % 60
                            val durationText = String.format("%d:%02d", minutes, seconds)
                            var thumbUrl: String? = null
                            val album = innerData.optObj("albumOfTrack") ?: innerData.optObj("album")
                            val coverSources = album?.optObj("coverArt")?.optArr("sources")
                                ?: album?.optArr("images")
                            if (coverSources != null && coverSources.size() > 0) {
                                thumbUrl = coverSources[0].optObj()?.get("url")?.optStr()
                            }
                            if (thumbUrl != null) {
                                cacheArtwork(id, thumbUrl)
                            }

                            list.add(
                                VideoItem(
                                    videoId = "sp:$id",
                                    title = title,
                                    author = artist,
                                    durationText = durationText,
                                    customThumbnailUrl = thumbUrl
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing fetchLibraryTracks item: ${e.message}")
                        }
                    }
                    if (list.isNotEmpty()) {
                        Log.i(TAG, "Successfully fetched ${list.size} liked tracks via Pathfinder fetchLibraryTracks")
                        return@withContext list
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pathfinder fetchLibraryTracks failed: ${e.message}")
        }
        list
    }
}
