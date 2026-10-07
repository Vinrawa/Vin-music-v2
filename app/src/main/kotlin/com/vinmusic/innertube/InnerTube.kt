package com.vinmusic.innertube

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

object InnerTube {
    const val TAG  = "VIN_STREAM"
    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    /**
     * WEB_REMIX clientVersion built from today's date (ytmusicapi-style) so it can
     * never go stale — YouTube starts rejecting hardcoded versions with 400
     * INVALID_ARGUMENT once they fall behind the live web client.
     */
    val WEB_REMIX_CLIENT_VERSION: String
        get() = "1." + java.time.LocalDate.now()
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".01.00"

    /** Last log message — shown on screen without ADB */
    @Volatile
    var lastDebugMsg = ""; private set

    /** Visitor data token, refreshed periodically for long-running sessions. */
    @Volatile private var visitorData = ""
    @Volatile private var visitorFetchedAt = 0L
    private const val VISITOR_TTL_MS = 60 * 60 * 1000L // 1 hour TTL
    @Volatile private var appContext: android.content.Context? = null

    val verifiedArtistCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun init(context: android.content.Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences("innertube_prefs", android.content.Context.MODE_PRIVATE)
        val savedToken = prefs.getString("visitor_data", null)
        val savedTime = prefs.getLong("visitor_fetched_at", 0L)
        if (!savedToken.isNullOrBlank() && System.currentTimeMillis() - savedTime < 24 * 60 * 60 * 1000L) {
            visitorData = savedToken
            visitorFetchedAt = savedTime
            android.util.Log.d("DEBUG_TOKEN", "Loaded persisted visitorData from disk (len=${savedToken.length})")
        } else {
            // Warm up in background asynchronously on launch
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                ensureVisitorData()
            }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val existing = req.header("Cookie")
            val missing = mutableListOf<String>()
            if (existing.isNullOrBlank() || !existing.contains("SOCS=")) missing.add("SOCS=CAI")
            if (existing.isNullOrBlank() || !existing.contains("CONSENT=")) missing.add("CONSENT=YES+1")

            val finalCookie = when {
                existing.isNullOrBlank() -> missing.joinToString("; ")
                missing.isNotEmpty() -> "$existing; ${missing.joinToString("; ")}"
                else -> existing
            }
            chain.proceed(req.newBuilder().header("Cookie", finalCookie).build())
        }
        .build()

    // Timeouts must stay above YouTube's realistic /player response latency.
    private val racingHttp = http.newBuilder()
        .connectTimeout(8_000, TimeUnit.MILLISECONDS)
        .readTimeout(12_000, TimeUnit.MILLISECONDS)
        .build()

    // ── Client definitions ────────────────────────────────────────────────────
    private data class YTClient(
        val name: String,
        val version: String,
        val clientId: String,      // X-YouTube-Client-Name value
        val ua: String,
        val extra: Map<String, Any> = emptyMap()
    )

    private val CLIENTS = listOf(
        // [OK] Top Fallback: VISIONOS — unthrottled, unciphered direct audio, reliable botguard bypass
        YTClient("VISIONOS", "0.1", "101",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "RealityDevice14,1",
                  "osName" to "visionOS",    "osVersion"   to "1.3.21O771")),
        // [OK] Primary: ANDROID_VR — confirmed OK + direct googlevideo.com URL
        YTClient("ANDROID_VR", "1.60.19", "28",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
            mapOf("androidSdkVersion" to 32)),
        // YouTube Music web client fallback. Cellular IPs may reject ANDROID_VR
        // with LOGIN_REQUIRED while accepting the first-party Music client.
        YTClient("WEB_REMIX", WEB_REMIX_CLIENT_VERSION, "67",
            "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36"),
        // TV embed — no cipher decryption needed
        YTClient("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85",
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 TV Safari/538.1"),
        // Modern IOS Client
        YTClient("IOS", "19.29.1", "5",
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X) AppleWebKit/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2",
                  "osName" to "iPhone",    "osVersion"   to "17.5.1.21F90")),
        // Android TestSuite fallback
        YTClient("ANDROID_TESTSUITE", "1.9", "89",
            "com.google.android.youtube.testsuite/1.9 (Linux; U; Android 12; en_US)",
            mapOf("androidSdkVersion" to 32)),
    )

    // ── Visitor data ──────────────────────────────────────────────────────────
    /** Fetches a fresh YouTube visitor data token (helps bypass LOGIN_REQUIRED) */
    fun ensureVisitorData(force: Boolean = false) {
        android.util.Log.d("DEBUG_TOKEN", "ensureVisitorData called: force=$force, currentToken='${visitorData.take(15)}', elapsedMs=${System.currentTimeMillis() - visitorFetchedAt}")
        // Serialize refreshes — concurrent LOGIN_REQUIRED responses each used to fire
        // their own fetch (~6 parallel endpoint hits + HTML scrapes racing writes to
        // one prefs file). Rare TTL-gated operation, so a coarse lock costs nothing.
        synchronized(this) {
            if (!force && visitorData.isNotEmpty() && System.currentTimeMillis() - visitorFetchedAt < VISITOR_TTL_MS) return
            try {
                // 1. Try official JSON visitor_id endpoint (fast, robust, never hits redirect loop)
                val jsonBody = gson.toJson(mapOf(
                    "context" to mapOf(
                        "client" to mapOf(
                            "clientName" to "WEB",
                            "clientVersion" to "2.20240801.01.00",
                            "hl" to "en",
                            "gl" to "IN"
                        )
                    )
                )).toRequestBody(JSON)

                val req = Request.Builder()
                    .url("$BASE/visitor_id?prettyPrint=false")
                    .post(jsonBody)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()

                val raw = http.newCall(req).execute().use { response ->
                    response.body?.string().orEmpty()
                }
                // YouTube may return an HTML/string challenge instead of JSON.
                // Never let that prevent the HTML visitor-data fallback.
                val root = raw.trimStart().takeIf { it.startsWith("{") }?.let {
                    runCatching { gson.fromJson(it, Map::class.java) }.getOrNull()
                }
                val responseContext = root?.get("responseContext") as? Map<*, *>
                var vd = responseContext?.get("visitorData") as? String

                // 2. Fallback to scraping music.youtube.com if API endpoint returned empty
                if (vd.isNullOrBlank()) {
                    val html = http.newCall(Request.Builder()
                        .url("https://music.youtube.com/")
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                        .build()).execute().use { it.body?.string() }

                    vd = html?.let {
                        Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
                    }
                }

                if (!vd.isNullOrBlank()) {
                    visitorData = vd
                    visitorFetchedAt = System.currentTimeMillis()
                    appContext?.getSharedPreferences("innertube_prefs", android.content.Context.MODE_PRIVATE)?.edit()?.apply {
                        putString("visitor_data", visitorData)
                        putLong("visitor_fetched_at", visitorFetchedAt)
                        apply()
                    }
                    android.util.Log.d("DEBUG_TOKEN", "ensureVisitorData PERSISTED: token='${visitorData.take(20)}...'")
                }
                log("visitorData: ${if (vd != null) "${vd.take(20)}... [OK]" else "not found"}")
            } catch (e: Exception) {
                android.util.Log.e("DEBUG_TOKEN", "ensureVisitorData ERROR: ${e.message}")
                log("visitorData fetch err: ${e.message?.take(60)}")
            }
        }
    }

    // Helper to dynamically match the User-Agent based on googlevideo URL params
    fun getUserAgentForUrl(url: String): String {
        return when {
            url.contains("c=VISIONOS") || url.contains("cps=1021") ->
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
            url.contains("c=ANDROID_VR") ->
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip"
            url.contains("c=ANDROID_TESTSUITE") ->
                "com.google.android.youtube.testsuite/1.9 (Linux; U; Android 12; en_US)"
            url.contains("c=ANDROID") ->
                "com.google.android.youtube/19.29.35 (Linux; U; Android 14; en_US) gzip"
            url.contains("c=IOS") ->
                "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X) AppleWebKit/605.1.15"
            url.contains("c=TVHTML5") ->
                "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 TV Safari/538.1"
            url.contains("c=WEB_REMIX") ->
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36"
            else ->
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    fun getSongDescription(videoId: String): String? {
        if (videoId.isBlank()) return null
        val descriptions = mutableListOf<String>()

        fetchPlayerDescription(videoId, "WEB_REMIX", WEB_REMIX_CLIENT_VERSION, "67")?.let { descriptions.add(it) }
        fetchPlayerDescription(videoId, "WEB", "2.20231219.04.00", "1")?.let { descriptions.add(it) }
        fetchNextDescription(videoId)?.let { descriptions.add(it) }

        return descriptions
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .maxByOrNull { descriptionCreditScore(it) }
    }

    private fun fetchPlayerDescription(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientId: String
    ): String? {
        val ctx = mapOf(
            "clientName" to clientName,
            "clientVersion" to clientVersion,
            "hl" to "en",
            "gl" to "IN"
        )
        val body = mapOf(
            "context" to mapOf("client" to ctx),
            "videoId" to videoId
        )
        return try {
            val raw = http.newCall(Request.Builder()
                .url("$BASE/player?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("X-YouTube-Client-Name", clientId)
                .header("X-YouTube-Client-Version", clientVersion)
                .build()
            ).execute().use { it.body?.string() } ?: return null
            val root = gson.fromJson(raw, Map::class.java)
            val videoDetails = root["videoDetails"] as? Map<*, *>
            videoDetails?.get("shortDescription") as? String
        } catch (e: Exception) {
            log("getSongDescription $clientName error: ${e.message}")
            null
        }
    }

    private fun fetchNextDescription(videoId: String): String? {
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB",
                "clientVersion" to "2.20231219.04.00",
                "hl" to "en",
                "gl" to "IN"
            )),
            "videoId" to videoId
        )
        return try {
            val raw = http.newCall(Request.Builder()
                .url("$BASE/next?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20231219.04.00")
                .build()
            ).execute().use { it.body?.string() } ?: return null
            val root = gson.fromJson(raw, Map::class.java)
            val candidates = mutableListOf<String>()
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        (node["attributedDescription"] as? Map<*, *>)?.get("content")
                            ?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                        val description = node["description"]
                        if (description is Map<*, *>) {
                            ytText(description).takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
            candidates.maxByOrNull { descriptionCreditScore(it) }
        } catch (e: Exception) {
            log("getSongDescription next error: ${e.message}")
            null
        }
    }

    private fun descriptionCreditScore(description: String): Int {
        val lower = description.lowercase(java.util.Locale.ROOT)
        val creditTerms = listOf(
            "provided to youtube by", "producer", "composer", "lyricist", "writer",
            "associated performer", "vocals", "mixer", "mixing", "mastering",
            "engineer", "recording", "publisher", "released on", "auto-generated"
        )
        val matchedTerms = creditTerms.count { lower.contains(it) }
        return matchedTerms * 10_000 + description.length.coerceAtMost(10_000)
    }

    private data class CachedStreamUrl(
        val fetchedAt: Long,
        val url: String,
        val networkKey: String
    )

    private val streamUrlCache =
        java.util.concurrent.ConcurrentHashMap<String, CachedStreamUrl>()

    private fun currentNetworkKey(): String {
        val context = appContext ?: return "unknown"
        return try {
            val connectivityManager =
                context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
            when {
                capabilities == null -> "unknown"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }

    /** TTL is checked on read, so entries never left the map on their own — sweep
     *  expired ones (and cap size) whenever a new URL is cached. */
    private fun cacheStreamUrl(videoId: String, url: String) {
        streamUrlCache[videoId] = CachedStreamUrl(
            fetchedAt = System.currentTimeMillis(),
            url = url,
            networkKey = currentNetworkKey()
        )
        if (streamUrlCache.size > MAX_STREAM_URL_CACHE_ENTRIES) {
            val cutoff = System.currentTimeMillis() - STREAM_URL_CACHE_TTL_MS
            streamUrlCache.entries.removeIf { it.value.fetchedAt < cutoff }
        }
        // Still oversized (e.g. many URLs cached within one TTL window)? Drop oldest.
        if (streamUrlCache.size > MAX_STREAM_URL_CACHE_ENTRIES) {
            streamUrlCache.entries
                .sortedBy { it.value.fetchedAt }
                .take(streamUrlCache.size - MAX_STREAM_URL_CACHE_ENTRIES)
                .forEach { streamUrlCache.remove(it.key, it.value) }
        }
    }
    private const val STREAM_URL_CACHE_TTL_MS = 20 * 60 * 1000L // 20 minutes (prevents stale IP/session tokens)
    private const val MAX_STREAM_URL_CACHE_ENTRIES = 200

    fun invalidateStreamUrl(videoId: String) {
        if (videoId.isNotBlank()) {
            streamUrlCache.remove(videoId)
            android.util.Log.d("DEBUG_TOKEN", "invalidateStreamUrl: removed videoId=$videoId from streamUrlCache")
        }
    }

    // ── Main entry ────────────────────────────────────────────────────────────
    fun getStreamUrl(videoId: String, quality: String? = null, forceFresh: Boolean = false): String? {
        android.util.Log.d("DEBUG_TOKEN", "getStreamUrl START videoId=$videoId forceFresh=$forceFresh")
        log("getStreamUrl videoId=$videoId quality=$quality forceFresh=$forceFresh")
        if (videoId.isBlank()) { log("ERROR: blank videoId!"); return null }

        // Fast In-Memory Cache Check (0ms instantaneous return for duplicate/recent requests)
        if (!forceFresh) {
            val cachedEntry = streamUrlCache[videoId]
            if (cachedEntry != null) {
                val networkKey = currentNetworkKey()
                if (System.currentTimeMillis() - cachedEntry.fetchedAt < STREAM_URL_CACHE_TTL_MS &&
                    cachedEntry.url.isNotBlank() &&
                    cachedEntry.networkKey == networkKey
                ) {
                    android.util.Log.d("DEBUG_TOKEN", "getStreamUrl IN-MEMORY CACHE HIT: videoId=$videoId network=$networkKey (0ms)")
                    return cachedEntry.url
                }
            }
        }

        // 1. Primary: Patched NewPipeExtractor (fastest & bypasses bot checks directly in ~350ms)
        try {
            NewPipeInit.init()
            val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                org.schabi.newpipe.extractor.ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            val url = info.audioStreams
                .filter { it.content?.isNotEmpty() == true }
                .let { streams ->
                    val targetKbps = when {
                        quality?.contains("96") == true  -> 96
                        quality?.contains("160") == true -> 160
                        quality?.contains("128") == true -> 128
                        quality?.contains("256") == true -> 256
                        quality?.contains("320") == true -> 320
                        else -> null
                    }
                    if (targetKbps != null) {
                        streams.minByOrNull { Math.abs(it.bitrate - (targetKbps * 1024)) }?.content
                    } else {
                        streams.maxByOrNull { it.bitrate }?.content
                    }
                }
            if (!url.isNullOrEmpty()) {
                cacheStreamUrl(videoId, url)
                log("NewPipe result SUCCESS Host: ${android.net.Uri.parse(url).host}")
                return url
            }
        } catch (e: Throwable) {
            log("NewPipe fast attempt: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }

        // 2. Fetch visitor token and try InnerTube direct clients in parallel race
        ensureVisitorData()
        log("Trying InnerTube direct clients in parallel racing...")
        val url = runBlocking {
            val channel = Channel<String>(Channel.UNLIMITED)
            val remaining = java.util.concurrent.atomic.AtomicInteger(CLIENTS.size)

            CLIENTS.forEach { client ->
                launch(Dispatchers.IO) {
                    try {
                        log("Trying parallel race: ${client.name}...")
                        val res = fetchViaClient(videoId, client, quality, racingHttp)
                        if (!res.isNullOrEmpty()) {
                            channel.trySend(res)
                        }
                    } catch (e: Throwable) {
                        log("${client.name} threw in race: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            channel.close()
                        }
                    }
                }
            }

            var successfulUrl: String? = null
            try {
                for (res in channel) {
                    successfulUrl = res
                    coroutineContext.cancelChildren() // Cancel other client requests
                    break
                }
            } catch (e: Exception) {
                log("Race channel error: ${e.message}")
            }
            successfulUrl
        }

        if (!url.isNullOrEmpty()) {
            log("SUCCESS via parallel race Host: ${android.net.Uri.parse(url).host}")
            return url
        }

        // Auto-recovery: If parallel race failed (likely due to token challenge), force a fresh token and retry once immediately
        log("Initial parallel race returned no URL. Refreshing visitor token and retrying race...")
        ensureVisitorData(force = true)
        val refreshedUrl = runBlocking {
            val channel = Channel<String>(Channel.UNLIMITED)
            val remaining = java.util.concurrent.atomic.AtomicInteger(CLIENTS.size)

            CLIENTS.forEach { client ->
                launch(Dispatchers.IO) {
                    try {
                        val res = fetchViaClient(videoId, client, quality, racingHttp)
                        if (!res.isNullOrEmpty()) {
                            channel.trySend(res)
                        }
                    } catch (e: Throwable) {
                        log("${client.name} retry race failed: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                    } finally {
                        if (remaining.decrementAndGet() == 0) {
                            channel.close()
                        }
                    }
                }
            }

            var successfulRetryUrl: String? = null
            try {
                for (res in channel) {
                    successfulRetryUrl = res
                    coroutineContext.cancelChildren()
                    break
                }
            } catch (_: Exception) {}
            successfulRetryUrl
        }

        if (!refreshedUrl.isNullOrEmpty()) {
            log("SUCCESS via refreshed token race Host: ${android.net.Uri.parse(refreshedUrl).host}")
            return refreshedUrl
        }

        log("ALL clients and fallbacks failed for $videoId")
        
        // Final fallback: Experimental Resolver (Metrolist IOS style)
        val experimentalUrl = ExperimentalResolver.getStreamUrl(videoId, quality)
        if (!experimentalUrl.isNullOrEmpty()) {
            log("SUCCESS via ExperimentalResolver Host: ${android.net.Uri.parse(experimentalUrl).host}")
            return experimentalUrl
        }
        
        return null
    }

    // ── InnerTube player request ──────────────────────────────────────────────
    private fun fetchViaClient(
        videoId: String,
        client: YTClient,
        quality: String? = null,
        clientOverride: OkHttpClient = http,
        allowRetry: Boolean = true
    ): String? {
        android.util.Log.d("DEBUG_TOKEN", "fetchViaClient: client=${client.name} sending visitorData='$visitorData' allowRetry=$allowRetry")
        val endpoint = "$BASE/player?prettyPrint=false"
        log("--- DIAGNOSTIC: Testing ${client.name} / ${client.version} ---")
        log("Endpoint: $endpoint")

        val ctx = buildMap<String, Any> {
            put("clientName",    client.name)
            put("clientVersion", client.version)
            put("hl",            "en")
            put("gl",            "IN")
            if (visitorData.isNotEmpty()) put("visitorData", visitorData)
            putAll(client.extra)
        }

        val body: Map<String, Any> = if (client.name == "TVHTML5_SIMPLY_EMBEDDED_PLAYER") {
            mapOf(
                "context"        to mapOf(
                    "client"     to ctx,
                    "thirdParty" to mapOf("embedUrl" to "https://www.youtube.com/")),
                "videoId"        to videoId,
                "racyCheckOk"    to true, "contentCheckOk" to true)
        } else {
            mapOf(
                "context"        to mapOf("client" to ctx),
                "videoId"        to videoId,
                "racyCheckOk"    to true, "contentCheckOk" to true)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(gson.toJson(body).toRequestBody(JSON))
            .header("Content-Type",             "application/json")
            .header("User-Agent",               client.ua)
            .header("X-YouTube-Client-Name",    client.clientId)
            .header("X-YouTube-Client-Version", client.version)
            .header("Origin",                   "https://www.youtube.com")
            .header("Referer",                  "https://www.youtube.com/")
            .apply {
                if (client.name == "WEB_REMIX") {
                    header("X-YouTube-Client-Name", client.clientId)
                    header("X-YouTube-Client-Version", client.version)
                    header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                }
            }
        if (visitorData.isNotEmpty())
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)

        val raw: String = try {
            clientOverride.newCall(reqBuilder.build()).execute().use { response ->
                log("HTTP Status: ${response.code}")
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            log("Exception Stage: Network request failed - ${e.message}")
            return null
        }
        if (raw.isEmpty()) {
            log("Result: Empty body")
            return null
        }

        try {
            val root   = gson.fromJson(raw, Map::class.java)
            val status = (root["playabilityStatus"] as? Map<*, *>)?.get("status") as? String
            val reason = (root["playabilityStatus"] as? Map<*, *>)?.get("reason") as? String
            log("playabilityStatus: $status")
            log("reason: $reason")

            if (status == "LOGIN_REQUIRED" && allowRetry) {
                log("${client.name}: LOGIN_REQUIRED — forcing visitor token refresh + retry")
                android.util.Log.w("DEBUG_TOKEN", "${client.name}: LOGIN_REQUIRED — forcing visitor token refresh + immediate retry")
                ensureVisitorData(force = true)
                return fetchViaClient(videoId, client, quality, clientOverride, allowRetry = false)
            }

            val sd = root["streamingData"] as? Map<*, *>
            val hasFormats = sd?.containsKey("formats") == true
            val hasAdaptiveFormats = sd?.containsKey("adaptiveFormats") == true
            val hasHls = sd?.containsKey("hlsManifestUrl") == true
            val hasDash = sd?.containsKey("dashManifestUrl") == true
            log("Returned -> formats: $hasFormats, adaptiveFormats: $hasAdaptiveFormats, HLS: $hasHls, DASH: $hasDash")

            if (status != "OK") return null
            if (sd == null) return null

            val adaptiveFormats = sd["adaptiveFormats"] as? List<*>
            if (adaptiveFormats != null) {
                val hasCipher = adaptiveFormats.any { (it as? Map<*, *>)?.containsKey("signatureCipher") == true || (it as? Map<*, *>)?.containsKey("cipher") == true }
                log("adaptiveFormats URLs contained cipher/signature: $hasCipher")
            }

            // Best: audio-only adaptive without cipher
            val audioUrl = adaptiveFormats
                ?.mapNotNull { it as? Map<*, *> }
                ?.filter { f ->
                    val mime  = f["mimeType"] as? String ?: ""
                    val url   = f["url"]      as? String ?: ""
                    val noCip = !f.containsKey("signatureCipher") && !f.containsKey("cipher")
                    mime.startsWith("audio/") && url.isNotEmpty() && noCip
                }
                ?.let { streams ->
                    val targetKbps = when {
                        quality?.contains("96") == true  -> 96
                        quality?.contains("160") == true -> 160
                        quality?.contains("128") == true -> 128
                        quality?.contains("256") == true -> 256
                        quality?.contains("320") == true -> 320
                        else -> null
                    }
                    if (targetKbps != null) {
                        streams.minByOrNull { Math.abs(((it["bitrate"] as? Double)?.toLong() ?: 0L) - (targetKbps * 1024)) }
                    } else {
                        streams.maxByOrNull { (it["bitrate"] as? Double)?.toLong() ?: 0L }
                    }
                }
                ?.get("url") as? String

            if (!audioUrl.isNullOrEmpty()) {
                log("Found direct adaptive audio format.")
                return audioUrl
            }

            // Fallback: muxed stream
            return (sd["formats"] as? List<*>)
                ?.mapNotNull { it as? Map<*, *> }
                ?.filter { it.containsKey("url") && !it.containsKey("signatureCipher") && !it.containsKey("cipher") }
                ?.firstOrNull()?.get("url") as? String
        } catch (e: Exception) {
            log("Exception Stage: JSON parsing/extraction failed - ${e.message}")
            return null
        }
    }

    /**
     * Fetches a lightweight progressive video stream URL (typically 360p MP4)
     * from YouTube or NewPipe to use as a background video.
     */
    fun getVideoStreamUrl(videoId: String): String? {
        log("getVideoStreamUrl videoId=$videoId")
        if (videoId.isBlank()) return null
        ensureVisitorData()
        
        // 1. Try NewPipeExtractor progressive video streams
        try {
            NewPipeInit.init()
            val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
                org.schabi.newpipe.extractor.ServiceList.YouTube,
                "https://www.youtube.com/watch?v=$videoId"
            )
            val url = info.videoStreams
                .filter { it.content?.isNotEmpty() == true }
                // Compare numeric resolution — lexicographic order would pick
                // "1080p" as "smallest" since "1" < "3".
                .minByOrNull { stream ->
                    stream.getResolution()?.takeWhile { ch -> ch.isDigit() }?.toIntOrNull()
                        ?: Int.MAX_VALUE
                }?.content
            if (!url.isNullOrEmpty()) {
                log("Video URL via NewPipe: ${url.take(60)}")
                return url
            }
        } catch (e: Throwable) {
            log("NewPipe Video FAILED: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }

        // 2. Fallback to InnerTube client formats (progressive formats)
        for (client in CLIENTS) {
            try {
                val url = fetchVideoViaClient(videoId, client)
                if (!url.isNullOrEmpty()) {
                    log("Video URL via client ${client.name}: ${url.take(60)}")
                    return url
                }
            } catch (e: Throwable) {}
        }
        return null
    }

    private fun fetchVideoViaClient(videoId: String, client: YTClient): String? {
        val ctx = mapOf(
            "clientName" to client.name,
            "clientVersion" to client.version,
            "hl" to "en", "gl" to "IN"
        )
        val body = mapOf(
            "context" to mapOf("client" to ctx),
            "videoId" to videoId,
            "racyCheckOk" to true, "contentCheckOk" to true
        )
        val reqBuilder = Request.Builder()
            .url("$BASE/player?prettyPrint=false")
            .post(gson.toJson(body).toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", client.ua)
        val raw = http.newCall(reqBuilder.build()).execute().use { it.body?.string() } ?: return null
        val root = gson.fromJson(raw, Map::class.java)
        val sd = root["streamingData"] as? Map<*, *> ?: return null
        
        // Return progressive 360p format if available, or any progressive format
        return (sd["formats"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.filter { f ->
                val url = f["url"] as? String ?: ""
                url.isNotEmpty() && !f.containsKey("signatureCipher") && !f.containsKey("cipher")
            }
            ?.minByOrNull { (it["bitrate"] as? Double)?.toLong() ?: 9999999L }
            ?.get("url") as? String
    }

    // ── Music content filter ─────────────────────────────────────────────────
    /** Returns true if this video is likely a music/audio track (not a vlog/cartoon/gaming video) */
    private fun isMusicContent(title: String, author: String, durationText: String): Boolean {
        val titleLow  = title.lowercase(java.util.Locale.ROOT)
        val authorLow = author.lowercase(java.util.Locale.ROOT)
        val durLow    = durationText.lowercase(java.util.Locale.ROOT)

        // 1. Duration filter: music is 1:15 to 10:00. Skip shorts and very long mixes.
        if (durationText.isBlank()) return false // Music must have a duration
        
        // Reject explicitly marked shorts
        if (durLow.contains("short")) return false

        val secs = parseDurationSecs(durationText)
        if (secs != null) {
            // Reject if less than 60 seconds (very short clips/shorts) or greater than 2400 seconds (40 minutes)
            if (secs < 60 || secs > 2400) return false
        }

        // 2. Strict Blacklist for non-music videos (memes, reviews, reaction, talks, explanations)
        val blacklist = listOf(
            "explained", "meaning", "reaction", "review", "breakdown", "story", "stories",
            "genius", "interview", "podcast", "documentary", "behind the scenes", "tutorial",
            "lesson", "news", "hidden meaning", "analysis", "funny", "parody", "reaction video",
            "reviewing", "behind the song", "teaser", "promo",
            "leak", "shorts", "karaoke", "be like", "when you", "pov", "tiktok",
            "tiktoks", "meme", "memes", "comedy", "comedian", "prank", "vlog", "vlogs", "gaming",
            "gameplay", "roast", "standup", "rant", "compilation", "fails", "challenge", "unboxing",
            "how to play", "tutorial", "guitar cover lesson", "piano lesson", "behind the track",
            "1 hour", "1hour", "1 hr", "1hr", "10 hours", "10hours", "loop", "looped", "hours loop",
            "fans", "everytime he", "everytime she", "first time", "finna be", "finna", "likes to",
            "deep dive", "important song", "best song", "worst song", "top song", "in real life",
            "irl", "dropped", "what happened", "what happens", "things you", "why they", "why he",
            "why she", "why the", "how fans", "how to", "unofficial", "trunk sale", "bts", "timeline",
            "beef", "drama", "reaction to", "react to", "funny moments", "funny video",
            "reacts", "reacting", "react", "reviewer", "reviewers", "critic", "critics",
            "foreigner", "american", "singers", "composer", "vocal coach", "honest opinion",
            "first time listening", "listening to", "hearing for", "reactionary", "unbiased",
            "honest review", "reaction compilation", "mashup reaction"
        )

        for (term in blacklist) {
            if (titleLow.contains(term) || authorLow.contains(term)) {
                return false
            }
        }

        // 3. Channel/Author validation
        val blacklistedChannelKeywords = listOf(
            "news", "tv", "comedy", "vlog", "gaming", "cricket", "tech", "review",
            "fitness", "food", "travel", "lifestyle", "kids", "cartoon", "meme", "daveo",
            "rdcworld", "longbeachgriffy", "distora", "peacock", "animator",
            "unboxing", "essay", "analysis", "genius", "vlogger", "react", "reaction", "reacts",
            "reacting", "reviewer", "critic", "critics", "podcast", "podcasts", "interview",
            "interviews", "talks", "show", "shows", "entertainment", "media", "vids",
            "videos", "gamer", "games", "prank", "pranks", "roast", "roasts", "clips", "moments",
            "fails", "compilation", "compilations"
        )
        if (blacklistedChannelKeywords.any { authorLow.contains(it) }) return false

        return true
    }

    private fun ytText(node: Any?): String {
        val map = node as? Map<*, *> ?: return ""
        (map["simpleText"] as? String)?.let { return it }
        val runs = map["runs"] as? List<*> ?: return ""
        return runs.mapNotNull { (it as? Map<*, *>)?.get("text")?.toString() }
            .joinToString("")
            .trim()
    }

    /** Parse duration string like "3:45" or "1:02:30" into total seconds. Returns 0 on failure. */
    private fun parseDurationSeconds(dur: String): Long {
        if (dur.isBlank()) return 0L
        val parts = dur.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600L + parts[1] * 60L + parts[2]
            2 -> parts[0] * 60L + parts[1]
            1 -> parts[0].toLong()
            else -> 0L
        }
    }

    private fun musicArtistText(textNode: Any?): String {
        val map = textNode as? Map<*, *> ?: return ""
        val runs = map["runs"] as? List<*> ?: return ytText(textNode)

        val linkedArtists = runs.mapNotNull { runNode ->
            val run = runNode as? Map<*, *> ?: return@mapNotNull null
            val text = run["text"]?.toString()?.trim() ?: return@mapNotNull null
            val browseId = (((run["navigationEndpoint"] as? Map<*, *>)
                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String).orEmpty()
            val lower = text.lowercase(java.util.Locale.ROOT)
            val isArtistLikeBrowse = browseId.isNotBlank() &&
                !browseId.startsWith("MPRE") &&
                !browseId.startsWith("VL") &&
                !browseId.startsWith("OL") &&
                !browseId.startsWith("PL")
            if (isArtistLikeBrowse && text.isNotBlank() && lower !in setOf("song", "video", "album", "single")) text else null
        }
        if (linkedArtists.isNotEmpty()) {
            return linkedArtists.distinct().joinToString(", ")
        }

        val raw = ytText(textNode)
        val cleanFallback = raw
            .split(Regex("""\s*(?:•|·|â€¢|\|)\s*"""))
            .map { part ->
                part.replace(Regex("""^(song|video|album|single)\s*[-:]*\s*""", RegexOption.IGNORE_CASE), "")
                    .trim()
            }
            .firstOrNull { part ->
                part.isNotBlank() &&
                    parseDurationSecs(part) == null &&
                    !part.equals("song", ignoreCase = true) &&
                    !part.equals("video", ignoreCase = true) &&
                    !part.equals("album", ignoreCase = true) &&
                    !part.equals("single", ignoreCase = true)
            }
            .orEmpty()
        if (cleanFallback.isNotBlank()) return cleanFallback
        return raw.split("•").firstOrNull()
            ?.replace(Regex("""^(song|video)\s*[-:]*\s*""", RegexOption.IGNORE_CASE), "")
            ?.trim()
            .orEmpty()
    }

    private fun musicFixedDuration(item: Map<*, *>): String {
        val fixedColumns = item["fixedColumns"] as? List<*> ?: return ""
        for (column in fixedColumns) {
            val renderer = ((column as? Map<*, *>)?.get("musicResponsiveListItemFixedColumnRenderer") as? Map<*, *>)
                ?: continue
            val text = ytText(renderer["text"])
            if (parseDurationSecs(text) != null) return text
        }
        return ""
    }

    private fun normalizeArtistText(value: String): String =
        value.lowercase(java.util.Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    private fun isLooseArtistMusicVideo(artistName: String, item: VideoItem): Boolean {
        val title = normalizeArtistText(item.title)
        val author = normalizeArtistText(item.author)
        val artist = normalizeArtistText(artistName)
        if (artist.isBlank() || title.isBlank()) return false
        // Note: blank durationText is now ALLOWED — many unofficial/rare uploads
        // don't expose a duration. They still surface but rank a bit lower
        // (see artistUploadScore). Only reject if we CAN parse it and it's
        // outside a sane song range (60s..1500s = 25 min, raised from 900s so
        // long unreleased tracks and full-album uploads aren't dropped).

        val tokens = artist.split(" ")
            .filter { it.length > 1 && it !in setOf("the", "a", "an", "and") }
        val tokenMatches = tokens.count { token -> title.contains(token) || author.contains(token) }
        val hasArtistMatch = title.contains(artist) ||
            author.contains(artist) ||
            (tokens.isNotEmpty() && tokenMatches >= minOf(2, tokens.size))
        if (!hasArtistMatch) return false

        val seconds = parseDurationSecs(item.durationText)
        if (seconds != null && (seconds < 60 || seconds > 1500)) return false

        val fullText = "$title $author"
        // Narrowed junk list: keep genuine spam (reactions, shorts, loops,
        // gaming, etc.) but REMOVED ambiguous terms that were blocking legit
        // rare/unofficial uploads — "full album", "greatest hits", "best of",
        // "compilation", "playlist", "cover", "karaoke", "instrumental",
        // "behind the scenes", "teaser", "promo", "interview", "podcast",
        // "documentary", "essay", "breakdown", "explained", "meaning", "analysis".
        val junkTerms = listOf(
            "reaction", "reacts", "reacting", "review",
            "news", "tutorial", "how to",
            "meme", "parody", "comedy", "prank", "vlog", "gaming", "gameplay",
            "roast", "standup", "unboxing", "shorts", "tiktok", "reels",
            "1 hour", "1hour", "1 hr", "1hr", "loop", "looped",
            "mashup"
        )
        return junkTerms.none { fullText.contains(it) }
    }

    private fun artistUploadScore(artistName: String, item: VideoItem): Int {
        val artist = normalizeArtistText(artistName)
        val title = normalizeArtistText(item.title)
        val author = normalizeArtistText(item.author)
        var score = 0
        if (author.contains(artist)) score += 40
        if (title.contains(artist)) score += 30
        if (author.contains("topic") || author.contains("vevo")) score += 15
        if (listOf("unreleased", "leak", "leaked", "demo", "rare", "loosie").any { title.contains(it) }) score += 12
        if (listOf("audio", "song", "track", "lyrics", "lyric").any { title.contains(it) }) score += 8
        val seconds = parseDurationSecs(item.durationText)
        if (seconds != null && seconds in 120..480) score += 4
        // Blank-duration items still surface (filter allows them now) but rank
        // slightly lower so clean official cuts stay on top.
        if (seconds == null) score -= 5
        return score
    }

    /** Parse "3:45" or "1:02:30" to total seconds */
    private fun parseDurationSecs(dur: String): Int? {
        if (dur.isBlank()) return null
        val parts = dur.split(":").mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    // ── Search ────────────────────────────────────────────────────────────────
    /** Search for music only. Hits YouTube Music API for 100% pure audio results. */
    private val searchCache = android.util.LruCache<String, Pair<Long, List<VideoItem>>>(50)

    fun search(query: String): List<VideoItem> {
        val normalizedQuery = query.trim()
        val cached = searchCache.get(normalizedQuery)
        if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60 * 1000) {
            return cached.second
        }
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "query" to normalizedQuery,
            "params" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D" // YouTube Music 'Songs' filter
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("Search error: ${e.message}"); null } ?: return emptyList()

        val songs = mutableListOf<VideoItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val mr = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (mr != null) {
                            try {
                                val flexCols = mr["flexColumns"] as? List<*> ?: emptyList<Any>()
                                var title = ""
                                var author = ""
                                val vid = (mr["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String ?: ""
                                
                                val col0 = flexCols.getOrNull(0) as? Map<*, *>
                                title = ((col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)
                                    ?.get("text") as? Map<*, *>)?.get("runs")?.let { runs ->
                                        (runs as List<*>).joinToString("") { (it as? Map<*, *>)?.get("text")?.toString() ?: "" }
                                    } ?: ""
                                
                                val col1 = flexCols.getOrNull(1) as? Map<*, *>
                                val rawSubtitle = ((col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)
                                    ?.get("text") as? Map<*, *>)?.get("runs")?.let { runs ->
                                        (runs as List<*>).joinToString("") { (it as? Map<*, *>)?.get("text")?.toString() ?: "" }
                                    } ?: ""
                                
                                val parts = rawSubtitle.split(" • ", " - ")
                                author = parts.firstOrNull() ?: rawSubtitle
                                val dur = parts.lastOrNull()?.let { if (it.contains(":")) it else "" } ?: ""
                                
                                if (vid.isNotEmpty() && title.isNotEmpty()) {
                                    songs.add(VideoItem(vid, title, author, dur))
                                }
                            } catch (e: Exception) {}
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) { log("Search parse: ${e.message}") }
        val result = songs.distinctBy { it.videoId }.take(30)
        if (result.isNotEmpty()) {
            searchCache.put(normalizedQuery, Pair(System.currentTimeMillis(), result))
        }
        return result
    }

    /** Search regular YouTube for artist uploads that are not indexed as official YouTube Music songs. */
    fun searchYouTubeArtistUploads(artistName: String): List<VideoItem> {
        val cleanArtist = artistName.trim()
        if (cleanArtist.isBlank()) return emptyList()

        // Mix of broad + targeted queries. The old literal phrases
        // ("$artist leaked song") matched exact phrases only, so a video titled
        // "Kendrick Lamar - prayer (unreleased)" never surfaced. These broader
        // queries let YouTube's own ranking pull relevant uploads regardless of
        // whether the title contains the word "leaked".
        val queries = listOf(
            cleanArtist,
            "$cleanArtist song",
            "$cleanArtist audio",
            "$cleanArtist unreleased",
            "$cleanArtist rare track",
            "$cleanArtist demo"
        )

        return queries
            .flatMap { searchYouTubeVideosForArtist(it, cleanArtist) }
            .distinctBy { it.videoId }
            .sortedByDescending { artistUploadScore(cleanArtist, it) }
            .take(40)
    }

    /**
     * Returns unofficial/rare/unreleased uploads for an artist — the stuff Top
     * Songs currently throws away. Keeps only items whose title matches the
     * rare-terms set (unreleased/leak/leaked/demo/rare/loosie/snippet/cdq) OR
     * whose author isn't an official music channel (no "topic"/"vevo" and the
     * artist name isn't in the author). Used by the "More from Artist" section.
     * Caps at 20.
     */
    fun getArtistRareUploads(artistName: String): List<VideoItem> {
        val cleanArtist = artistName.trim()
        if (cleanArtist.isBlank()) return emptyList()
        val artist = normalizeArtistText(cleanArtist)

        val rareTerms = listOf(
            "unreleased", "leak", "leaked", "demo", "rare", "loosie",
            "snippet", "cdq", "acapella", "instrumental demo"
        )
        return searchYouTubeArtistUploads(cleanArtist)
            .filter { item ->
                val title = normalizeArtistText(item.title)
                val author = normalizeArtistText(item.author)
                val isRareTitle = rareTerms.any { title.contains(it) }
                val isOfficialChannel = author.contains("topic") || author.contains("vevo") || author.contains(artist)
                isRareTitle || !isOfficialChannel
            }
            .distinctBy { it.videoId }
            .take(20)
    }

    private fun searchYouTubeVideosForArtist(query: String, artistName: String): List<VideoItem> {
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB",
                "clientVersion" to "2.20231219.04.00",
                "hl" to "en",
                "gl" to "IN"
            )),
            "query" to query,
            "params" to "EgIQAQ%3D%3D"
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$BASE/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", "2.20231219.04.00")
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) {
            log("YouTube artist upload search error: ${e.message}")
            null
        } ?: return emptyList()

        val videos = mutableListOf<VideoItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val renderer = (node["videoRenderer"]
                            ?: node["compactVideoRenderer"]
                            ?: node["gridVideoRenderer"]) as? Map<*, *>
                        if (renderer != null) {
                            val videoId = renderer["videoId"] as? String
                            val title = ytText(renderer["title"])
                            val author = ytText(renderer["ownerText"])
                                .ifBlank { ytText(renderer["shortBylineText"]) }
                                .ifBlank { ytText(renderer["longBylineText"]) }
                            val duration = ytText(renderer["lengthText"])
                            if (!videoId.isNullOrBlank() && title.isNotBlank()) {
                                val item = VideoItem(videoId, title, author, duration)
                                if (isLooseArtistMusicVideo(artistName, item)) {
                                    videos.add(item)
                                }
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("YouTube artist upload parse error: ${e.message}")
        }
        return videos.distinctBy { it.videoId }
    }

    // ── YouTube Music Browse and Search API ───────────────────────────────────

    /**
     * Load an artist's actual YouTube channel Videos tab and rank it by the
     * view-count metadata returned by YouTube (Popular order).
     */
    fun getArtistChannelVideos(channelIdInput: String, artistNameFallback: String = ""): List<VideoItem> {
        var channelId = channelIdInput.trim()
        if (channelId.isBlank() && artistNameFallback.isNotBlank()) {
            channelId = resolveArtistChannelId(artistNameFallback)
        }
        if (channelId.isBlank()) return emptyList()

        fun fetch(params: String?): String? {
            val body = buildMap<String, Any> {
                put("browseId", channelId)
                put("context", mapOf("client" to mapOf(
                    "clientName" to "WEB",
                    "clientVersion" to "2.20231219.04.00",
                    "hl" to "en",
                    "gl" to "IN"
                )))
                if (!params.isNullOrBlank()) put("params", params)
            }
            return try {
                http.newCall(Request.Builder()
                    .url("$BASE/browse?prettyPrint=false")
                    .post(gson.toJson(body).toRequestBody(JSON))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("X-YouTube-Client-Name", "1")
                    .header("X-YouTube-Client-Version", "2.20231219.04.00")
                    .header("Origin", "https://www.youtube.com")
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                ).execute().use { it.body?.string() }
            } catch (e: Exception) {
                log("getArtistChannelVideos request error: ${e.message}")
                null
            }
        }

        fun viewCount(text: String): Long {
            val match = Regex("([0-9]+(?:\\.[0-9]+)?)[ ]*([kmb])?")
                .find(text.lowercase().replace(",", "")) ?: return 0L
            val number = match.groupValues[1].toDoubleOrNull() ?: return 0L
            val multiplier = when (match.groupValues.getOrNull(2)) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                "b" -> 1_000_000_000.0
                else -> 1.0
            }
            return (number * multiplier).toLong()
        }

        fun parse(raw: String): List<Pair<VideoItem, Long>> {
            val result = mutableListOf<Pair<VideoItem, Long>>()
            try {
                val root = gson.fromJson(raw, Map::class.java)
                fun scan(node: Any?) {
                    when (node) {
                        is Map<*, *> -> {
                            val rich = node["richItemRenderer"] as? Map<*, *>
                            val content = rich?.get("content") as? Map<*, *>
                            val renderer = (node["videoRenderer"]
                                ?: node["gridVideoRenderer"]
                                ?: content?.get("videoRenderer")
                                ?: content?.get("gridVideoRenderer")) as? Map<*, *>
                            if (renderer != null) {
                                val id = renderer["videoId"] as? String ?: ""
                                val title = ytText(renderer["title"])
                                val author = ytText(renderer["ownerText"])
                                    .ifBlank { ytText(renderer["shortBylineText"]) }
                                    .ifBlank { artistNameFallback }
                                val duration = ytText(renderer["lengthText"])
                                val views = ytText(renderer["viewCountText"])
                                    .ifBlank { ytText(renderer["shortViewCountText"]) }
                                if (id.isNotBlank() && title.isNotBlank()) {
                                    val parsedViews = viewCount(views)
                                    result += VideoItem(
                                        videoId = id,
                                        title = title,
                                        author = author,
                                        durationText = duration,
                                        viewCount = parsedViews.takeIf { it > 0L }
                                    ) to parsedViews
                                }
                            }
                            node.values.forEach { scan(it) }
                        }
                        is List<*> -> node.forEach { scan(it) }
                    }
                }
                scan(root)
            } catch (e: Exception) {
                log("getArtistChannelVideos parse error: ${e.message}")
            }
            return result
        }

        val parsed = fetch("EgZ2aWRlb3M%3D")?.let(::parse).orEmpty()
        val fallback = if (parsed.isEmpty()) fetch(null)?.let(::parse).orEmpty() else emptyList()
        return (parsed + fallback)
            .distinctBy { it.first.videoId }
            .sortedByDescending { it.second }
            .take(50)
            .map { it.first }
    }

    /**
     * Curated YouTube Music Browse API: Retrieves proper albums and singles for an artist
     * directly using their YouTube Music channel ID. This is extremely robust and avoids search junk.
     */
    fun getArtistAlbumsAndSingles(channelId: String, artistName: String): Pair<List<AlbumItem>, List<AlbumItem>> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "browseId" to channelId,
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            ))
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getArtistAlbumsAndSingles error: ${e.message}"); null } ?: return Pair(emptyList(), emptyList())

        val albumsList = mutableListOf<AlbumItem>()
        val singlesList = mutableListOf<AlbumItem>()

        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scanForShelves(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val shelf = node["musicCarouselShelfRenderer"] as? Map<*, *>
                        if (shelf != null) {
                            val headerRenderer = shelf["header"] as? Map<*, *>
                            val basicHeader = headerRenderer?.get("musicCarouselShelfBasicHeaderRenderer") as? Map<*, *>
                            val titleRuns = (basicHeader?.get("title") as? Map<*, *>)?.get("runs") as? List<*>
                            val shelfTitle = titleRuns?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }
                                ?.joinToString("")?.lowercase() ?: ""

                            val contents = shelf["contents"] as? List<*>
                            if (contents != null) {
                                for (item in contents) {
                                    val itemMap = item as? Map<*, *> ?: continue
                                    
                                    // 1) Support musicTwoRowItemRenderer (curated grid items)
                                    val mtr = itemMap["musicTwoRowItemRenderer"] as? Map<*, *>
                                    if (mtr != null) {
                                        val title = ((mtr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                                        val navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                            ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                                        val thumb = ((mtr["thumbnailRenderer"] as? Map<*, *>)
                                            ?.get("musicThumbnailRenderer") as? Map<*, *>)
                                            ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } }
                                            ?.let {
                                                var url = it
                                                if (url.startsWith("//")) url = "https:$url"
                                                if (url.startsWith("http://")) url = url.replace("http://", "https://")
                                                url
                                            } ?: ""
                                        val subtitle = ((mtr["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                                        if (navId.isNotEmpty() && title.isNotEmpty()) {
                                            val albumItem = AlbumItem(navId, title, artistName, thumb, subtitle)
                                            if (shelfTitle.contains("album")) {
                                                albumsList.add(albumItem)
                                            } else if (shelfTitle.contains("single") || shelfTitle.contains("ep")) {
                                                singlesList.add(albumItem)
                                            }
                                        }
                                    }
                                    
                                    // 2) Support musicResponsiveListItemRenderer (curated list items)
                                    val mrli = itemMap["musicResponsiveListItemRenderer"] as? Map<*, *>
                                    if (mrli != null) {
                                        val flexCols = mrli["flexColumns"] as? List<*>
                                        
                                        val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                                        val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                                        val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                                        
                                        val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                                        val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                                        val subtitle = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                                        val navId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                            ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                                        val thumb = ((mrli["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>)
                                            ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } }
                                            ?.let {
                                                var url = it
                                                if (url.startsWith("//")) url = "https:$url"
                                                if (url.startsWith("http://")) url = url.replace("http://", "https://")
                                                url
                                            } ?: ""

                                        if (navId.isNotEmpty() && title.isNotEmpty()) {
                                            val albumItem = AlbumItem(navId, title, artistName, thumb, subtitle)
                                            if (shelfTitle.contains("album")) {
                                                albumsList.add(albumItem)
                                            } else if (shelfTitle.contains("single") || shelfTitle.contains("ep")) {
                                                singlesList.add(albumItem)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            node.values.forEach { scanForShelves(it) }
                        }
                    }
                    is List<*> -> node.forEach { scanForShelves(it) }
                }
            }
            scanForShelves(root)
        } catch (e: Exception) {
            log("getArtistAlbumsAndSingles parse error: ${e.message}")
        }

        return Pair(
            albumsList.distinctBy { it.playlistId },
            singlesList.distinctBy { it.playlistId }
        )
    }

    /**
     * Resolves the list of songs in an MPRE album browse page.
     * Hits the /browse endpoint of YouTube Music with WEB_REMIX client to load songs correctly.
     */
    fun getAlbumSongs(albumId: String): List<VideoItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "browseId" to albumId,
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            ))
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getAlbumSongs error: ${e.message}"); null } ?: return emptyList()

        val songs = mutableListOf<VideoItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val mrli = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (mrli != null) {
                            val playlistItemData = mrli["playlistItemData"] as? Map<*, *>
                            val videoId = playlistItemData?.get("videoId") as? String ?: ""
                            
                            val flexCols = mrli["flexColumns"] as? List<*>
                            val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                            val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val title = ytText(col0Renderer?.get("text"))
                            
                            val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                            val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val author = musicArtistText(col1Renderer?.get("text"))
                            val duration = musicFixedDuration(mrli)
                            
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                songs.add(VideoItem(videoId, title, author, duration))
                            }
                        } else {
                            node.values.forEach { scan(it) }
                        }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("getAlbumSongs parse error: ${e.message}")
        }
        return songs.distinctBy { it.videoId }
    }

    // ── YouTube Music Lyrics ───────────────────────────────────────────────────
    // #1 lyrics source: perfectly synced (Musixmatch-backed) and official, pulled
    // from the same backend as the playing song. Two-step flow mirroring
    // Metrolist/InnerTune/RiMusic: /next -> lyrics browseId -> /browse -> lines.

    /**
     * Step 1: From a videoId, get the browseId of the song's lyrics tab.
     * Returns the MPLYT_-prefixed browseId, or null if the song has no lyrics.
     */
    fun getLyricsBrowseId(videoId: String): String? {
        if (videoId.isBlank()) return null
        ensureVisitorData()
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "enablePersistentPlaylistPanel" to true,
            "isAudioOnly" to true,
            "videoId" to videoId
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/next?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getLyricsBrowseId error: ${e.message}"); return null } ?: return null

        // Scan the response for any engagement panel titled "LYRICS" (or its
        // equivalent) and extract the browseId it navigates to. YTM puts the
        // lyrics panel under engagementPanels -> {menu, structuredDescription}
        // or directly as a musicDescriptionShelfRenderer, so we scan loosely.
        var found: String? = null
        try {
            val root = gson.fromJson(raw, Map::class.java)
            // Marker stack: as we descend, remember if the current subtree is
            // within a panel/run whose title contains "lyric". A browseId seen
            // while inside that region is the lyrics browseId.
            val lyricMarker = ArrayDeque<Boolean>()

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        // Title-bearing runs often live next to the browseId;
                        // remember when we enter a "lyric" labelled region.
                        val titleText = (node["title"] as? Map<*, *>)?.let { ytText(it) }
                        val headerText = (node["header"] as? Map<*, *>)?.let { ytText(it) }
                        val combined = "$titleText $headerText".lowercase()
                        val enteredLyric = combined.contains("lyric")
                        if (enteredLyric) lyricMarker.addLast(true)

                        val browseId = ((node["navigationEndpoint"] as? Map<*, *>)
                            ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String
                        if (browseId != null && browseId.startsWith("MPLYT_") &&
                            (lyricMarker.isNotEmpty() || found == null)
                        ) {
                            found = browseId
                        }

                        node.values.forEach { scan(it) }

                        if (enteredLyric) lyricMarker.removeLast()
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("getLyricsBrowseId parse error: ${e.message}")
        }
        return found
    }

    /**
     * Step 2: Fetch the synced (LRC-style timed) and/or plain lyrics for a browseId.
     * Returns (syncedLrc, plainText). Either or both may be null/blank.
     */
    fun getLyrics(browseId: String): Pair<String?, String?> {
        if (browseId.isBlank()) return null to null
        ensureVisitorData()
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "browseId" to browseId,
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            ))
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getLyrics error: ${e.message}"); return null to null } ?: return null to null

        var synced: String? = null
        var plain: String? = null
        try {
            val root = gson.fromJson(raw, Map::class.java)
            // YTM lyrics live in a musicDescriptionShelfRenderer whose "runs"
            // carry either timed words (when synced) or plain text lines. Synced
            // runs embed inline <mm:ss.xx> timestamps; plain runs do not.
            val sbSynced = StringBuilder()
            val sbPlain = StringBuilder()

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val desc = node["musicDescriptionShelfRenderer"] as? Map<*, *>
                        if (desc != null) {
                            val runs = (desc["runs"] as? List<*>) ?: emptyList<Any>()
                            // Each "run" is a line. A run's text may begin with an
                            // inline timestamp like "<mm:ss.xx>" for synced lyrics.
                            runs.forEach { runNode ->
                                val run = runNode as? Map<*, *> ?: return@forEach
                                val text = run["text"]?.toString() ?: ""
                                if (text.isBlank()) return@forEach
                                // Detect inline timestamps → synced.
                                if (Regex("""<\d{1,2}:\d{2}[.:]\d{1,3}>""").containsMatchIn(text)) {
                                    // YTM uses "<mm:ss.xx>word..." inline LRC syntax.
                                    sbSynced.append("[").append(text.trim()).append("]\n")
                                } else {
                                    // Plain line.
                                    sbPlain.append(text.trim()).append("\n")
                                }
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
            // If we collected synced runs, build a proper LRC string. YTM's inline
            // format is "<mm:ss.xx>word" repeated within a run; convert each run to
            // a standard "[mm:ss.xx] line" LRC entry.
            if (sbSynced.isNotEmpty()) {
                synced = ytmInlineToLrc(sbSynced.toString())
            }
            if (sbPlain.isNotEmpty()) {
                plain = sbPlain.toString().trim()
            }
            // Fallback: if no synced but runs had plain text and the line count is
            // reasonable, treat plain as the only output.
            if (synced.isNullOrBlank() && plain.isNullOrBlank()) {
                // Some responses put all text in runs without timestamps under a
                // different renderer name; do a final loose scan for any "runs".
                val loose = StringBuilder()
                fun looseScan(node: Any?) {
                    when (node) {
                        is Map<*, *> -> {
                            val runs = node["runs"] as? List<*>
                            if (runs != null) {
                                runs.forEach { r ->
                                    val t = (r as? Map<*, *>)?.get("text")?.toString() ?: ""
                                    if (t.isNotBlank()) loose.append(t.trim()).append("\n")
                                }
                            }
                            node.values.forEach { looseScan(it) }
                        }
                        is List<*> -> node.forEach { looseScan(it) }
                    }
                }
                looseScan(root)
                plain = loose.toString().trim().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            log("getLyrics parse error: ${e.message}")
        }
        return synced to plain
    }

    /**
     * Convert YTM's inline "<mm:ss.xx>word <mm:ss.xx>word" syntax to standard
     * LRC "[mm:ss.xx] word" lines, grouping all words on a single line into one
     * LRC entry at that line's first timestamp.
     */
    private fun ytmInlineToLrc(raw: String): String {
        val out = StringBuilder()
        val tokenRegex = Regex("""<(\d{1,2}):(\d{2})[.:](\d{1,3})>""")
        // Split into lines first (YTM puts each lyric line as a separate run
        // already); for each line, take the first timestamp as the LRC time and
        // strip all inline timestamps for the display text.
        raw.split("\n").forEach { rawLine ->
            val matches = tokenRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) return@forEach
            val first = matches.first()
            val min = first.groupValues[1].padStart(2, '0')
            val sec = first.groupValues[2]
            val frac = first.groupValues[3].padEnd(3, '0').take(2)
            val text = rawLine.replace(tokenRegex, "").trim()
            if (text.isNotEmpty()) {
                out.append("[$min:$sec.$frac]$text\n")
            }
        }
        return out.toString().trim()
    }

    /**
     * Fallback search for albums: Searches YouTube Music for albums by artist name.
     * Uses WEB_REMIX client and parses BOTH musicTwoRowItemRenderer and musicResponsiveListItemRenderer.
     */
    fun searchArtistAlbums(artistName: String): List<AlbumItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "query" to artistName,
            "params" to "EgWKAQIYAWoKEAoQAxAEEAkQBQ%3D%3D" // Albums filter
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("searchArtistAlbums error: ${e.message}"); null } ?: return emptyList()

        val albums = mutableListOf<AlbumItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        // 1) musicTwoRowItemRenderer
                        val mtr = node["musicTwoRowItemRenderer"] as? Map<*, *>
                        if (mtr != null) {
                            val title = ((mtr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            val navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mtr["thumbnailRenderer"] as? Map<*, *>)
                                ?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                            val subtitle = ((mtr["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            
                            val subLower = subtitle.lowercase()
                            if (navId.isNotEmpty() && title.isNotEmpty() && !subLower.contains("single") && !subLower.contains("ep")) {
                                albums.add(AlbumItem(navId, title, artistName, thumb, subtitle))
                            }
                        }
                        
                        // 2) musicResponsiveListItemRenderer
                        val mrli = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (mrli != null) {
                            val flexCols = mrli["flexColumns"] as? List<*>
                            
                            val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                            val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            
                            val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                            val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val subtitle = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            
                            val navId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mrli["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                            
                            val subLower = subtitle.lowercase()
                            if (navId.isNotEmpty() && title.isNotEmpty() && !subLower.contains("single") && !subLower.contains("ep")) {
                                albums.add(AlbumItem(navId, title, artistName, thumb, subtitle))
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("searchArtistAlbums parse error: ${e.message}")
        }
        return albums.distinctBy { it.playlistId }.take(12)
    }

    /**
     * Fallback search for singles: Searches YouTube Music for singles by artist name.
     * Uses WEB_REMIX client and parses BOTH musicTwoRowItemRenderer and musicResponsiveListItemRenderer.
     */
    fun searchArtistSingles(artistName: String): List<AlbumItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "query" to artistName,
            "params" to "EgWKAQIYAWoKEAoQAxAEEAkQBQ%3D%3D" // Same Albums/Singles list param
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("searchArtistSingles error: ${e.message}"); null } ?: return emptyList()

        val singles = mutableListOf<AlbumItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        // 1) musicTwoRowItemRenderer
                        val mtr = node["musicTwoRowItemRenderer"] as? Map<*, *>
                        if (mtr != null) {
                            val title = ((mtr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            val navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mtr["thumbnailRenderer"] as? Map<*, *>)
                                ?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                            val subtitle = ((mtr["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            
                            val subLower = subtitle.lowercase()
                            if (navId.isNotEmpty() && title.isNotEmpty() && (subLower.contains("single") || subLower.contains("ep"))) {
                                singles.add(AlbumItem(navId, title, artistName, thumb, subtitle))
                            }
                        }
                        
                        // 2) musicResponsiveListItemRenderer
                        val mrli = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (mrli != null) {
                            val flexCols = mrli["flexColumns"] as? List<*>
                            
                            val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                            val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            
                            val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                            val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val subtitle = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            
                            val navId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mrli["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                            
                            val subLower = subtitle.lowercase()
                            if (navId.isNotEmpty() && title.isNotEmpty() && (subLower.contains("single") || subLower.contains("ep"))) {
                                singles.add(AlbumItem(navId, title, artistName, thumb, subtitle))
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("searchArtistSingles parse error: ${e.message}")
        }
        return singles.distinctBy { it.playlistId }.take(10)
    }

    /**
     * Searches YouTube Music for community playlists (public playlists created by the community)
     * using the official WEB_REMIX client and parameters.
     */
    fun searchCommunityPlaylists(query: String): List<AlbumItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "query" to query,
            // "community_playlists" filter param
            "params" to "EgeKAQQoAEABagwQDhAKEAMQBBAJEAU%3D"
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("searchCommunityPlaylists error: ${e.message}"); null } ?: return emptyList()

        val playlists = mutableListOf<AlbumItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        // 1) musicTwoRowItemRenderer (grid items)
                        val mtr = node["musicTwoRowItemRenderer"] as? Map<*, *>
                        if (mtr != null) {
                            val title = ((mtr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            val navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mtr["thumbnailRenderer"] as? Map<*, *>)
                                ?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                            val subtitle = ((mtr["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                            if (navId.isNotEmpty() && title.isNotEmpty()) {
                                playlists.add(AlbumItem(navId, title, "", thumb, subtitle))
                            }
                        }

                        // 2) musicResponsiveListItemRenderer (list items)
                        val mrli = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (mrli != null) {
                            val flexCols = mrli["flexColumns"] as? List<*>

                            val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                            val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                            val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                            val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val subtitle = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                            val navId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                            val thumb = ((mrli["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>)
                                ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""

                            if (navId.isNotEmpty() && title.isNotEmpty()) {
                                playlists.add(AlbumItem(navId, title, "", thumb, subtitle))
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("searchCommunityPlaylists parse error: ${e.message}")
        }
        return playlists.distinctBy { it.playlistId }
    }

    /**
     * Official YouTube Music Browse API for Mood Categories (Relax, Workout, Commute, Focus, etc.).
     * Hits /browse with WEB_REMIX client to load curated sections, playlists, tracks, and artists.
     */
    fun getMoodCategoryPage(browseId: String, params: String = ""): List<Pair<String, List<Any>>> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mutableMapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "browseId" to browseId
        )
        if (params.isNotEmpty()) {
            body["params"] = params
        }

        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getMoodCategoryPage error: ${e.message}"); null } ?: return emptyList()

        val sections = mutableListOf<Pair<String, List<Any>>>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val carousel = node["musicCarouselShelfRenderer"] as? Map<*, *>
                        if (carousel != null) {
                            val header = carousel["header"] as? Map<*, *>
                            val basicHeader = header?.get("musicCarouselShelfBasicHeaderRenderer") as? Map<*, *>
                            var shelfTitle = ((basicHeader?.get("title") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                            if (shelfTitle.isEmpty()) {
                                shelfTitle = (basicHeader?.get("accessibilityData") as? Map<*, *>)
                                    ?.let { (it["accessibilityData"] as? Map<*, *>)?.get("label") as? String } ?: ""
                            }

                            val items = mutableListOf<Any>()
                            val contents = carousel["contents"] as? List<*>
                            if (contents != null) {
                                for (cNode in contents) {
                                    val itemMap = cNode as? Map<*, *> ?: continue
                                    
                                    // 1) Playlist / Album / Artist
                                    val mtr = itemMap["musicTwoRowItemRenderer"] as? Map<*, *>
                                    if (mtr != null) {
                                        val title = ((mtr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""
                                        
                                        var navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                            ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                                        
                                        if (navId.isEmpty()) {
                                            navId = (((mtr["navigationEndpoint"] as? Map<*, *>)
                                                ?.get("watchPlaylistEndpoint") as? Map<*, *>)?.get("playlistId") as? String)
                                                ?: (((mtr["navigationEndpoint"] as? Map<*, *>)
                                                ?.get("watchEndpoint") as? Map<*, *>)?.get("playlistId") as? String)
                                                ?: ""
                                        }
                                        
                                        val thumb = ((mtr["thumbnailRenderer"] as? Map<*, *>)
                                            ?.get("musicThumbnailRenderer") as? Map<*, *>)
                                            ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""
                                        val subtitle = ((mtr["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                                        if (navId.isNotEmpty() && title.isNotEmpty()) {
                                            if (navId.startsWith("UC")) {
                                                items.add(ArtistItem(navId, title, thumb, subtitle))
                                            } else {
                                                items.add(AlbumItem(navId, title, subtitle, thumb, ""))
                                            }
                                        }
                                    }

                                    // 2) Song / Playlist / Artist (ListItem Renderer)
                                    val mrli = itemMap["musicResponsiveListItemRenderer"] as? Map<*, *>
                                    if (mrli != null) {
                                        val flexCols = mrli["flexColumns"] as? List<*>
                                        val col0 = flexCols?.getOrNull(0) as? Map<*, *>
                                        val col0Renderer = col0?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                                        val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                                        val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                                        val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                                        val author = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                            ?.map { (it as? Map<*, *>)?.get("text") as? String ?: "" }?.joinToString("") ?: ""

                                        val videoId = ((mrli["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String)
                                            ?: (((mrli["navigationEndpoint"] as? Map<*, *>)?.get("watchEndpoint") as? Map<*, *>)?.get("videoId") as? String) ?: ""
                                        
                                        var browseId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                            ?.get("browseEndpoint") as? Map<*, *>)?.get("browseId") as? String) ?: ""
                                            
                                        if (browseId.isEmpty() && videoId.isEmpty()) {
                                            browseId = (((mrli["navigationEndpoint"] as? Map<*, *>)
                                                ?.get("watchPlaylistEndpoint") as? Map<*, *>)?.get("playlistId") as? String)
                                                ?: (((mrli["navigationEndpoint"] as? Map<*, *>)
                                                ?.get("watchEndpoint") as? Map<*, *>)?.get("playlistId") as? String)
                                                ?: ""
                                        }

                                        val thumb = ((mrli["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>)
                                            ?.let { thr -> ((thr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } } ?: ""

                                        if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                            items.add(VideoItem(videoId, title, author, ""))
                                        } else if (browseId.isNotEmpty() && title.isNotEmpty()) {
                                            if (browseId.startsWith("UC")) {
                                                items.add(ArtistItem(browseId, title, thumb, author))
                                            } else {
                                                items.add(AlbumItem(browseId, title, author, thumb, ""))
                                            }
                                        }
                                    }
                                }
                            }
                            if (shelfTitle.isNotEmpty() && items.isNotEmpty()) {
                                sections.add(shelfTitle to items)
                            }
                        }
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            scan(root)
        } catch (e: Exception) {
            log("getMoodCategoryPage parse error: ${e.message}")
        }
        return sections
    }

    private val searchAllCache = android.util.LruCache<String, Pair<Long, AllSearchResults>>(20)

    // ── Search All Types (Songs + Artists + Albums in one call) ──────────────
    fun searchAll(query: String): AllSearchResults {
        val cached = searchAllCache.get(query)
        if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60 * 1000) {
            return cached.second
        }

        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB", "clientVersion" to "2.20231219.04.00",
                "hl" to "en", "gl" to "IN"
            )),
            "query" to query
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$BASE/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent",   "Mozilla/5.0")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { null } ?: return AllSearchResults()

        return try {
            val root = gson.fromJson(raw, Map::class.java)
            val secs = (root["contents"] as? Map<*, *>)
                ?.get("twoColumnSearchResultsRenderer").let { it as? Map<*, *> }
                ?.get("primaryContents").let { it as? Map<*, *> }
                ?.get("sectionListRenderer").let { it as? Map<*, *> }
                ?.get("contents") as? List<*> ?: return AllSearchResults()

            // Fetch true music songs via our dedicated YTM search,
            // while parsing artists, albums, and videos from standard YouTube search.
            val songs   = search(query).toMutableList()
            val videos  = mutableListOf<VideoItem>()
            val artists = mutableListOf<ArtistItem>()
            val albums  = mutableListOf<AlbumItem>()
            val songVideoIds = songs.map { it.videoId }.toSet()

            for (sec in secs) {
                val items = ((sec as? Map<*, *>)?.get("itemSectionRenderer") as? Map<*, *>)
                    ?.get("contents") as? List<*> ?: continue
                for (item in items) {
                    val m = item as? Map<*, *> ?: continue

                    // Video (user uploads, unreleased, leaked, non-official content)
                    val renderer = (m["videoRenderer"]
                        ?: m["compactVideoRenderer"]
                        ?: m["gridVideoRenderer"]) as? Map<*, *>
                    if (renderer != null) {
                        val vid   = renderer["videoId"] as? String ?: ""
                        val title = ytText(renderer["title"])
                        val author = ytText(renderer["ownerText"])
                            .ifBlank { ytText(renderer["shortBylineText"]) }
                        val dur   = ytText(renderer["lengthText"])
                        if (vid.isNotBlank() && title.isNotBlank() && vid !in songVideoIds) {
                            // Exclude very short items (< 30s) — likely intros/outros/ads
                            val durSec = parseDurationSeconds(dur)
                            if (durSec >= 30) {
                                videos.add(VideoItem(vid, title, author, dur))
                            }
                        }
                    }

                    // Artist/Channel
                    m["channelRenderer"]?.let { c ->
                        val cr   = c as? Map<*, *> ?: return@let
                        val id   = cr["channelId"] as? String ?: return@let
                        val name = ((cr["title"] as? Map<*, *>)?.get("simpleText") as? String)
                            ?: ((cr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                            ?: return@let
                        val subs = ((cr["subscriberCountText"] as? Map<*, *>)?.get("simpleText") as? String)
                            ?: ((cr["subscriberCountText"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        val thumb = ((cr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                            ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                            ?.let {
                                var url = it
                                if (url.startsWith("//")) url = "https:$url"
                                if (url.startsWith("http://")) url = url.replace("http://", "https://")
                                url
                            } ?: ""
                        
                        val ownerBadges = cr["ownerBadges"] as? List<*>
                        // Badge styles only — the old `subs.contains("M")` fallback marked
                        // every channel with a K/M subscriber count ("1.2K", "3.4M") verified.
                        val isVerified = ownerBadges?.any { b ->
                            val mbr = (b as? Map<*, *>)?.get("metadataBadgeRenderer") as? Map<*, *>
                            val style = mbr?.get("style") as? String ?: ""
                            style == "BADGE_STYLE_TYPE_VERIFIED_ARTIST" || style == "BADGE_STYLE_TYPE_VERIFIED"
                        } == true

                        if (isVerified) verifiedArtistCache[id] = true
                        artists.add(ArtistItem(id, name, thumb, subs, isVerified))
                    }

                    // Album/Playlist
                    m["playlistRenderer"]?.let { p ->
                        val pr    = p as? Map<*, *> ?: return@let
                        val id    = pr["playlistId"] as? String ?: return@let
                        val title = ((pr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                            ?: ((pr["title"] as? Map<*, *>)?.get("simpleText") as? String)
                            ?: return@let
                        val auth  = ((pr["shortBylineText"] as? Map<*, *>)?.get("runs") as? List<*>)
                            ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                        val countNode = pr["videoCountText"] ?: pr["thumbnailText"]
                        val count = ((countNode as? Map<*, *>)?.get("runs") as? List<*>)
                            ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }?.joinToString("")
                            ?: ((countNode as? Map<*, *>)?.get("simpleText") as? String)
                            ?: ""
                        val thumb = findUrlInNode(pr) ?: ""
                        albums.add(AlbumItem(id, title, auth, thumb, count))
                    }
                }
            }

            // Enrich artist list with direct YTM search artists if empty or missing exact query match
            val cleanQuery = query.trim().lowercase()
            val hasExactArtist = artists.any { it.name.lowercase().contains(cleanQuery) }
            if (!hasExactArtist) {
                val ytmArtistId = resolveArtistChannelId(query)
                if (ytmArtistId.isNotBlank() && artists.none { it.channelId == ytmArtistId }) {
                    val cd = fetchChannelData(ytmArtistId, query)
                    val artThumb = cd.avatarUrl.ifEmpty { cd.bannerUrl }
                    // Verified status must come from the badge cache only; subscriber
                    // text contains "K"/"M" for virtually every channel.
                    val isVer: Boolean = verifiedArtistCache[ytmArtistId] == true
                    artists.add(0, ArtistItem(ytmArtistId, cd.title.ifEmpty { query }, artThumb, cd.subscriberCount, isVer))
                }
            }

            val finalResults = AllSearchResults(songs, videos.distinctBy { it.videoId }.take(30), artists.distinctBy { it.channelId }, albums)
            searchAllCache.put(query, Pair(System.currentTimeMillis(), finalResults))
            finalResults
        } catch (e: Exception) { AllSearchResults() }
    }

    /** Get top songs for an artist, preferring the artist channel's Popular videos. */
    fun getArtistTopSongs(artistName: String): List<VideoItem> =
        getArtistChannelVideos("", artistName).ifEmpty {
            (search("$artistName top songs") + searchYouTubeArtistUploads(artistName))
                .distinctBy { it.videoId }
                .sortedByDescending { artistUploadScore(artistName, it) }
                .take(50)
        }

    // ── Channel browse (artist banner + bio) ─────────────────────────────────
    data class ChannelData(val bannerUrl: String = "", val bio: String = "", val subscriberCount: String = "", val title: String = "", val avatarUrl: String = "")

    private val channelDataCache = android.util.LruCache<String, Pair<Long, ChannelData>>(30)
    private val resolveArtistCache = android.util.LruCache<String, Pair<Long, String>>(50)

    fun fetchChannelData(channelIdInput: String, artistNameFallback: String = ""): ChannelData {
        var activeChannelId = channelIdInput.trim()
        val queryKey = activeChannelId.ifBlank { artistNameFallback }.trim()
        if (queryKey.isBlank()) return ChannelData()

        // 1. Check shared ArtistDataCache (0ms instant hit)
        val cachedShared = com.vinmusic.data.ArtistDataCache.get(queryKey)
        if (cachedShared != null && (cachedShared.avatarUrl.isNotBlank() || cachedShared.bannerUrl.isNotBlank() || cachedShared.subscriberCount.isNotBlank())) {
            return ChannelData(
                bannerUrl = cachedShared.bannerUrl,
                bio = cachedShared.bio,
                subscriberCount = cachedShared.subscriberCount,
                title = cachedShared.name.ifBlank { artistNameFallback },
                avatarUrl = cachedShared.avatarUrl
            )
        }

        // 2. Check local memory cache (10 min TTL)
        val cacheKey = queryKey.lowercase()
        val cached = channelDataCache.get(cacheKey)
        if (cached != null && System.currentTimeMillis() - cached.first < 600_000) {
            return cached.second
        }

        // 0. If channelId is missing, resolve it from YTM search by artist name
        if (activeChannelId.isBlank() && artistNameFallback.isNotBlank()) {
            activeChannelId = resolveArtistChannelId(artistNameFallback)
        }

        var banner = ""
        var avatar = ""
        var subs = ""
        var title = ""
        var bio = ""

        if (activeChannelId.isNotBlank()) {
            val webBody = mapOf(
                "browseId" to activeChannelId,
                "context" to mapOf("client" to mapOf("clientName" to "WEB", "clientVersion" to "2.20231219.04.00", "hl" to "en", "gl" to "IN"))
            )
            val ytmBody = mapOf(
                "browseId" to activeChannelId,
                "context" to mapOf("client" to mapOf("clientName" to "WEB_REMIX", "clientVersion" to WEB_REMIX_CLIENT_VERSION, "hl" to "en", "gl" to "IN"))
            )

            var webParsed: ChannelData? = null
            var ytmParsed: ChannelData? = null

            val webThread = Thread {
                try {
                    val raw = racingHttp.newCall(Request.Builder()
                        .url("$BASE/browse?prettyPrint=false")
                        .post(gson.toJson(webBody).toRequestBody(JSON))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    ).execute().use { it.body?.string() }
                    if (raw != null) webParsed = parseChannelDataJson(raw)
                } catch (e: Exception) { android.util.Log.e("VinArtistDebug", "WEB error: ${e.message}") }
            }
            val ytmThread = Thread {
                try {
                    val rawYtm = racingHttp.newCall(Request.Builder()
                        .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                        .post(gson.toJson(ytmBody).toRequestBody(JSON))
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    ).execute().use { it.body?.string() }
                    if (rawYtm != null) ytmParsed = parseYtmChannelDataJson(rawYtm)
                } catch (e: Exception) { android.util.Log.e("VinArtistDebug", "YTM error: ${e.message}") }
            }

            webThread.start()
            ytmThread.start()
            try {
                webThread.join(3500)
                ytmThread.join(3500)
            } catch (_: Exception) {}

            // Merge: start with WEB data, then override with YTM (better banners)
            webParsed?.let {
                banner = it.bannerUrl; avatar = it.avatarUrl; subs = it.subscriberCount; title = it.title; bio = it.bio
            }
            val isTopicChannel = title.contains("- Topic", ignoreCase = true)
            ytmParsed?.let { ytm ->
                if (ytm.bannerUrl.isNotBlank()) banner = ytm.bannerUrl
                if (ytm.avatarUrl.isNotBlank()) avatar = ytm.avatarUrl
                if (subs.isBlank() || isTopicChannel) subs = ytm.subscriberCount.ifBlank { subs }
                if (title.isBlank() || isTopicChannel) title = ytm.title.ifBlank { title }
                if (bio.isBlank()) bio = ytm.bio
            }
        }

        // 3. Fallbacks: cross-assign banner/avatar if one is missing
        if (avatar.isBlank() && banner.isNotBlank()) avatar = banner
        if (banner.isBlank() && avatar.isNotBlank()) banner = avatar

        val finalTitle = title.ifBlank { artistNameFallback }

        // Fetch real artist bio from Wikipedia if missing or generic
        var finalBio = bio
        if (finalTitle.isNotEmpty()) {
            val wikiBio = fetchArtistBio(finalTitle)
            if (wikiBio.isNotEmpty()) {
                finalBio = wikiBio
            } else if (finalBio.contains("Subscribe", ignoreCase = true) || finalBio.contains("Official Channel", ignoreCase = true)) {
                val cleanTitle = finalTitle.replace("- Topic", "").replace("VEVO", "", ignoreCase = true).trim()
                val cleanWikiBio = fetchArtistBio(cleanTitle)
                if (cleanWikiBio.isNotEmpty()) {
                    finalBio = cleanWikiBio
                }
            }
        }

        val result = ChannelData(bannerUrl = banner, bio = finalBio, subscriberCount = subs, title = finalTitle, avatarUrl = avatar)
        channelDataCache.put(cacheKey, Pair(System.currentTimeMillis(), result))
        if (result.avatarUrl.isNotBlank() || result.bannerUrl.isNotBlank() || result.subscriberCount.isNotBlank()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                com.vinmusic.data.ArtistDataCache.put(
                    queryKey,
                    com.vinmusic.data.CachedArtistData(
                        channelId = activeChannelId,
                        name = finalTitle,
                        avatarUrl = result.avatarUrl,
                        bannerUrl = result.bannerUrl,
                        subscriberCount = result.subscriberCount,
                        bio = result.bio
                    )
                )
            }
        }
        return result
    }

    private fun resolveArtistChannelId(artistName: String): String {
        android.util.Log.d("VinArtistDebug", "resolveArtistChannelId called: '$artistName'")
        // Check cache (5 min TTL)
        val cacheKey = artistName.replace(".", "").trim().lowercase()
        val cached = resolveArtistCache.get(cacheKey)
        if (cached != null && System.currentTimeMillis() - cached.first < 300_000) {
            android.util.Log.d("VinArtistDebug", "resolveArtistChannelId CACHE HIT: '${cached.second}'")
            return cached.second
        }
        return try {
            val cleanName = artistName.replace("- Topic", "", ignoreCase = true).replace("VEVO", "", ignoreCase = true).replace(".", "").trim().lowercase()
            android.util.Log.d("VinArtistDebug", "cleanName='$cleanName'")

            var exactId = ""
            var partialId = ""

            fun checkCandidate(title: String, browseId: String, pageType: String) {
                val cleanTitle = title.replace(".", "").trim().lowercase()
                android.util.Log.d("VinArtistDebug", "candidate: cleanTitle='$cleanTitle' browseId='$browseId' pageType='$pageType'")
                if (cleanTitle == cleanName && exactId.isEmpty()) {
                    exactId = browseId
                    android.util.Log.d("VinArtistDebug", "EXACT match: '$cleanTitle' == '$cleanName' -> $browseId")
                } else if ((cleanTitle.contains(cleanName) || cleanName.contains(cleanTitle)) && cleanTitle.isNotBlank() && partialId.isEmpty()) {
                    partialId = browseId
                    android.util.Log.d("VinArtistDebug", "PARTIAL match: '$cleanTitle' <-> '$cleanName' -> $browseId")
                }
            }

            fun isArtistPageType(pageType: String): Boolean =
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL"

            fun extractPageType(nav: Map<*, *>?): String =
                (((nav?.get("browseEndpointContextSupportedConfigs") as? Map<*, *>)
                    ?.get("browseEndpointContextMusicConfig") as? Map<*, *>)?.get("pageType") as? String) ?: ""

            fun scanForArtistId(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        // 1. Standard list items (related artists in search results)
                        val item = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (item != null) {
                            val nav = ((item["navigationEndpoint"] as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>)
                            val pageType = extractPageType(nav)
                            if (isArtistPageType(pageType)) {
                                val flex = item["flexColumns"] as? List<*>
                                val title = flex?.firstOrNull()?.let {
                                    ((((it as? Map<*, *>)?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>)
                                        ?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                        ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                                } ?: ""
                                val browseId = (nav?.get("browseId") as? String) ?: ""
                                checkCandidate(title, browseId, pageType)
                            }
                        }

                        // 2. Top Result card (musicCardShelfRenderer) — this is where the searched artist usually appears
                        val cardShelf = node["musicCardShelfRenderer"] as? Map<*, *>
                        if (cardShelf != null) {
                            val cardTitle = ((cardShelf["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            val cardNav = ((cardShelf["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let {
                                    ((it as? Map<*, *>)?.get("navigationEndpoint") as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>
                                }
                            val cardPageType = extractPageType(cardNav)
                            val cardBrowseId = (cardNav?.get("browseId") as? String) ?: ""
                            if (isArtistPageType(cardPageType) && cardBrowseId.isNotBlank()) {
                                android.util.Log.d("VinArtistDebug", "TOP RESULT card: title='$cardTitle' browseId='$cardBrowseId' pageType='$cardPageType'")
                                checkCandidate(cardTitle, cardBrowseId, cardPageType)
                            }
                            // Also check subtitle for artist page type
                            val subtitleNav = (cardShelf["navigationEndpoint"] as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>
                            val subtitlePageType = extractPageType(subtitleNav)
                            val subtitleBrowseId = (subtitleNav?.get("browseId") as? String) ?: ""
                            if (isArtistPageType(subtitlePageType) && subtitleBrowseId.isNotBlank() && cardBrowseId.isBlank()) {
                                checkCandidate(cardTitle, subtitleBrowseId, subtitlePageType)
                            }
                        }

                        // 3. Two-column item (another possible renderer)
                        val twoCol = node["musicTwoColumnItemRenderer"] as? Map<*, *>
                        if (twoCol != null) {
                            val tTitle = ((twoCol["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            val tNav = ((twoCol["navigationEndpoint"] as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>)
                            val tPageType = extractPageType(tNav)
                            val tBrowseId = (tNav?.get("browseId") as? String) ?: ""
                            if (isArtistPageType(tPageType) && tBrowseId.isNotBlank()) {
                                checkCandidate(tTitle, tBrowseId, tPageType)
                            }
                        }

                        node.values.forEach { scanForArtistId(it) }
                    }
                    is List<*> -> node.forEach { scanForArtistId(it) }
                }
            }

            // First attempt: unfiltered search (picks up Top Result card)
            val body = mapOf(
                "query" to artistName,
                "context" to mapOf("client" to mapOf("clientName" to "WEB_REMIX", "clientVersion" to WEB_REMIX_CLIENT_VERSION, "hl" to "en", "gl" to "IN"))
            )
            val raw = http.newCall(Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            ).execute().use { it.body?.string() } ?: return ""

            val root = gson.fromJson(raw, Map::class.java)
            scanForArtistId(root)

            // Second attempt: filtered artist search if nothing found yet
            if (exactId.isEmpty() && partialId.isEmpty()) {
                android.util.Log.d("VinArtistDebug", "Trying filtered artist search...")
                val filteredBody = mapOf(
                    "query" to artistName,
                    "params" to "EgWKAQIIAWoKEAMQBBAKEAkQBQ%3D%3D",
                    "context" to mapOf("client" to mapOf("clientName" to "WEB_REMIX", "clientVersion" to WEB_REMIX_CLIENT_VERSION, "hl" to "en", "gl" to "IN"))
                )
                val filteredRaw = http.newCall(Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                    .post(gson.toJson(filteredBody).toRequestBody(JSON))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                ).execute().use { it.body?.string() }

                if (filteredRaw != null) {
                    val filteredRoot = gson.fromJson(filteredRaw, Map::class.java)
                    scanForArtistId(filteredRoot)
                }
            }

            val result = exactId.ifEmpty { partialId }
            android.util.Log.d("VinArtistDebug", "resolveArtistChannelId result: '$result' (exact='$exactId' partial='$partialId')")
            resolveArtistCache.put(cacheKey, Pair(System.currentTimeMillis(), result))
            result
        } catch (e: Exception) { android.util.Log.e("VinArtistDebug", "resolveArtistChannelId error: ${e.message}"); "" }
    }

    private fun parseChannelDataJson(rawJson: String): ChannelData {
        return try {
            val root = gson.fromJson(rawJson, Map::class.java)
            val header = root["header"] as? Map<*, *> ?: emptyMap<String, Any>()

            var banner = ""
            var avatar = ""
            var subs = ""
            var title = ((root["metadata"] as? Map<*, *>)
                ?.get("channelMetadataRenderer") as? Map<*, *>)?.get("title") as? String ?: ""
            var bio = ((root["metadata"] as? Map<*, *>)
                ?.get("channelMetadataRenderer") as? Map<*, *>)?.get("description") as? String ?: ""

            // 1. YouTube's New pageHeaderRenderer / pageHeaderViewModel
            val pageHeaderViewModel = ((header["pageHeaderRenderer"] as? Map<*, *>)
                ?.get("content") as? Map<*, *>)
                ?.get("pageHeaderViewModel") as? Map<*, *>

            if (pageHeaderViewModel != null) {
                // Banner
                val bannerSources = (((pageHeaderViewModel["banner"] as? Map<*, *>)
                    ?.get("imageBannerViewModel") as? Map<*, *>)
                    ?.get("image") as? Map<*, *>)
                    ?.get("sources") as? List<*>
                banner = bannerSources?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } ?: ""

                // Avatar
                val avatarSources = (((((pageHeaderViewModel["image"] as? Map<*, *>)
                    ?.get("decoratedAvatarViewModel") as? Map<*, *>)
                    ?.get("avatar") as? Map<*, *>)
                    ?.get("avatarViewModel") as? Map<*, *>)
                    ?.get("image") as? Map<*, *>)
                    ?.get("sources") as? List<*>
                avatar = avatarSources?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } ?: ""

                // Title fallback
                if (title.isBlank()) {
                    title = (((pageHeaderViewModel["title"] as? Map<*, *>)
                        ?.get("dynamicTextViewModel") as? Map<*, *>)
                        ?.get("text") as? Map<*, *>)
                        ?.get("content") as? String ?: ""
                }
            }

            // 2. Legacy headers fallback (c4TabbedHeaderRenderer, interactiveTabbedHeaderRenderer)
            val hdr = (header["c4TabbedHeaderRenderer"] as? Map<*, *>)
                ?: (header["interactiveTabbedHeaderRenderer"] as? Map<*, *>)

            fun extractUrl(node: Map<*, *>?, key: String): String {
                val thumbnails = (node?.get(key) as? Map<*, *>)?.get("thumbnails") as? List<*>
                return thumbnails?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                    ?.let {
                        var url = it
                        if (url.startsWith("//")) url = "https:$url"
                        if (url.startsWith("http://")) url = url.replace("http://", "https://")
                        url
                    } ?: ""
            }

            if (banner.isBlank()) banner = extractUrl(hdr, "banner")
            if (banner.isBlank()) banner = extractUrl(hdr, "cover")
            if (banner.isBlank()) banner = extractUrl(header, "banner")

            if (avatar.isBlank()) avatar = extractUrl(hdr, "avatar")
            if (subs.isBlank()) subs = (hdr?.get("subscriberCountText") as? Map<*, *>)?.get("simpleText") as? String ?: ""

            ChannelData(bannerUrl = banner, bio = bio, subscriberCount = subs, title = title, avatarUrl = avatar)
        } catch (e: Exception) {
            ChannelData()
        }
    }

    private fun parseYtmChannelDataJson(rawJson: String): ChannelData {
        return try {
            val root = gson.fromJson(rawJson, Map::class.java)
            val header = root["header"] as? Map<*, *> ?: emptyMap<String, Any>()

            val mihr = (header["musicImmersiveHeaderRenderer"] as? Map<*, *>)
                ?: (header["musicVisualHeaderRenderer"] as? Map<*, *>)
                ?: (header["musicHeaderRenderer"] as? Map<*, *>)

            if (mihr == null) return ChannelData()

            val titleRuns = (mihr["title"] as? Map<*, *>)?.get("runs") as? List<*>
            val title = titleRuns?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""

            val subBtn = mihr["subscriptionButton"] as? Map<*, *>
            val subRuns = (((subBtn?.get("subscribeButtonRenderer") as? Map<*, *>)
                ?.get("subscriberCountText") as? Map<*, *>)?.get("runs") as? List<*>)
            val subs = subRuns?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""

            fun extractUrl(node: Map<*, *>?): String {
                val thumbnails = (node?.get("thumbnail") as? Map<*, *>)?.get("thumbnails") as? List<*>
                return thumbnails?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String } ?: ""
            }

            val bNode = (mihr["thumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>
            val banner = extractUrl(bNode)

            val aNode = (mihr["foregroundThumbnail"] as? Map<*, *>)?.get("musicThumbnailRenderer") as? Map<*, *>
            val avatar = extractUrl(aNode)

            val bioRuns = (mihr["description"] as? Map<*, *>)?.get("runs") as? List<*>
            val bio = bioRuns?.joinToString("") { (it as? Map<*, *>)?.get("text") as? String ?: "" } ?: ""

            ChannelData(bannerUrl = banner, bio = bio, subscriberCount = subs, title = title, avatarUrl = avatar)
        } catch (e: Exception) { ChannelData() }
    }

    fun fetchDeezerArtistImage(artistName: String): String? {
        if (artistName.isBlank()) return null
        return try {
            val cleanName = artistName.replace("- Topic", "", ignoreCase = true).replace("VEVO", "", ignoreCase = true).trim()
            val url = "https://api.deezer.com/search/artist?q=${java.net.URLEncoder.encode(cleanName, "UTF-8")}"
            val raw = http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { it.body?.string() } ?: return null
            val root = gson.fromJson(raw, Map::class.java)
            val data = root["data"] as? List<*>
            val first = data?.firstOrNull() as? Map<*, *>
            val picXl = first?.get("picture_xl") as? String
            val picBig = first?.get("picture_big") as? String
            picXl?.ifBlank { null } ?: picBig?.ifBlank { null }
        } catch (e: Exception) { null }
    }

    private fun fetchArtistBio(artistName: String): String {
        try {
            val url = "https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&titles=${java.net.URLEncoder.encode(artistName, "UTF-8")}&format=json"
            val raw = http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() } ?: return ""
            val root = gson.fromJson(raw, Map::class.java)
            val query = root["query"] as? Map<*, *>
            val pages = query?.get("pages") as? Map<*, *>
            val page = pages?.values?.firstOrNull() as? Map<*, *>
            val extract = page?.get("extract") as? String
            if (!extract.isNullOrBlank() && !extract.contains("may refer to", ignoreCase = true)) {
                return extract.trim()
            }
        } catch (e: Exception) { }
        return ""
    }

    /** Load image bytes — used for notification artwork */
    fun loadThumbnailBytes(url: String): ByteArray? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
    } catch (_: Exception) { null }

    /**
     * Fetches the watch next radio queue for a given video ID using the InnerTube /next endpoint.
     * This uses YouTube Music's high-fidelity official recommendations.
     */
    fun getWatchNextRadio(videoId: String): List<VideoItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            )),
            "videoId" to videoId,
            "playlistId" to "RDAMVM$videoId"
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/next?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getWatchNextRadio error: ${e.message}"); null } ?: return emptyList()

        val songs = mutableListOf<VideoItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)

            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val ppvr = node["playlistPanelVideoRenderer"] as? Map<*, *>
                        if (ppvr != null) {
                            val id = ppvr["videoId"] as? String
                            if (!id.isNullOrBlank()) {
                                val t = (((ppvr["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.firstOrNull() as? Map<*, *>)?.get("text") as? String
                                    ?: (ppvr["title"] as? Map<*, *>)?.get("simpleText") as? String
                                
                                val a = (((ppvr["shortBylineText"] ?: ppvr["longBylineText"]) as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                                    ?: ((ppvr["shortBylineText"] ?: ppvr["longBylineText"]) as? Map<*, *>)?.get("simpleText") as? String
                                    ?: ""
                                    
                                val dur = (ppvr["lengthText"] as? Map<*, *>)?.get("simpleText") as? String
                                    ?: (((ppvr["lengthText"] as? Map<*, *>)?.get("runs") as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String })
                                    ?: ""

                                if (!t.isNullOrBlank()) {
                                    songs.add(VideoItem(id, t, a, dur))
                                }
                            }
                        } else {
                            node.values.forEach { scan(it) }
                        }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }

            scan(root)
            log("getWatchNextRadio found ${songs.size} items for $videoId")
        } catch (e: Exception) {
            log("getWatchNextRadio parse error: ${e.message}")
        }
        return songs.distinctBy { it.videoId }
    }

    private val suggestionsCache = android.util.LruCache<String, Pair<Long, List<String>>>(50)

    // ── Suggestions ───────────────────────────────────────────────────────────
    fun getSuggestions(query: String): List<String> {
        try {
            if (query.isBlank()) return emptyList()
            
            val cached = suggestionsCache.get(query)
            if (cached != null && System.currentTimeMillis() - cached.first < 5 * 60 * 1000) {
                return cached.second
            }
            
            val url = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false"
            val body = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB_REMIX",
                        "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                        "hl" to "en",
                        "gl" to "IN"
                    )
                ),
                "input" to query
            )
            val resp = http.newCall(Request.Builder()
                .url(url)
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build())
                .execute().use { it.body?.string() } ?: return emptyList()

            val root = gson.fromJson(resp, Map::class.java)
            val contents = root["contents"] as? List<*> ?: emptyList<Any?>()
            val results = buildList<String> {
                for (section in contents) {
                    val secMap = section as? Map<*, *> ?: continue
                    val renderer = secMap["searchSuggestionsSectionRenderer"] as? Map<*, *> ?: continue
                    val items = renderer["contents"] as? List<*> ?: continue
                    for (item in items) {
                        val itemMap = item as? Map<*, *> ?: continue
                        val suggestionRenderer = itemMap["searchSuggestionRenderer"] as? Map<*, *> ?: continue
                        val suggestion = suggestionRenderer["suggestion"] as? Map<*, *> ?: continue
                        val runs = suggestion["runs"] as? List<*> ?: continue
                        val text = runs.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }.joinToString("")
                        if (text.isNotBlank()) add(text)
                    }
                }
            }.take(10)
            suggestionsCache.put(query, Pair(System.currentTimeMillis(), results))
            return results
        } catch (e: Exception) {
            log("Suggestions fetch failed: ${e.message}")
            return emptyList()
        }
    }

    /** Scrapes all VideoItems from a YouTube playlist (PL...) or album (OLAK...) browse endpoint */
    fun getPlaylistSongs(playlistId: String): Pair<String, List<VideoItem>> {
        // Normalize playlist ID for YTM browse endpoint:
        // - VLPLxxx, VLRDxxx → already correct browse IDs
        // - PLxxx → needs VL prefix
        // - RDCLAKxxx → radio mixes, need VL prefix for browse
        // - Other → pass through as-is
        val targetId = when {
            playlistId.startsWith("VL") -> playlistId
            playlistId.startsWith("PL") -> "VL$playlistId"
            playlistId.startsWith("RDCLAK") || playlistId.startsWith("RD") -> "VL$playlistId"
            playlistId.startsWith("OL") -> playlistId  // OLAK album IDs
            else -> playlistId
        }
        val body = mapOf(
            "browseId" to targetId,
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX", "clientVersion" to WEB_REMIX_CLIENT_VERSION,
                "hl" to "en", "gl" to "IN"
            ))
        )
        val raw = try {
            val reqBuilder = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Origin", "https://music.youtube.com")
                .header("Referer", "https://music.youtube.com/")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", WEB_REMIX_CLIENT_VERSION)
            
            // Inject cookie and authorization header for private library playlists
            YTMusicApi.getCookie()?.let { cookie ->
                reqBuilder.header("Cookie", cookie)
                YTMusicSession.authorizationHeader(cookie)?.let { reqBuilder.header("Authorization", it) }
                reqBuilder.header("X-Goog-AuthUser", "0")
            }
            
            http.newCall(reqBuilder.build()).execute().use { it.body?.string() }
        } catch (e: Exception) { log("getPlaylistSongs error: ${e.message}"); null } ?: return Pair("Playlist", emptyList())

        return try {
            val root = gson.fromJson(raw, Map::class.java)
            
            // Extract playlist title if available (supports standard and music headers)
            val header = root["header"] as? Map<*, *>
            val playlistTitle = (header?.get("playlistHeaderRenderer") as? Map<*, *>)
                ?.get("title")?.let { titleNode ->
                    (titleNode as? Map<*, *>)?.get("simpleText") as? String
                    ?: ((titleNode as? Map<*, *>)?.get("runs") as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                } 
                ?: (header?.get("musicHeaderRenderer") as? Map<*, *>)?.get("title")?.let { titleNode ->
                    ((titleNode as? Map<*, *>)?.get("runs") as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                }
                ?: "YouTube Playlist"

            val songs = mutableListOf<VideoItem>()
            
            // Recursive scan function to find video items
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val videoRenderer = (node["playlistVideoRenderer"] ?: node["videoRenderer"]) as? Map<*, *>
                        val responsiveRenderer = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        
                        if (videoRenderer != null) {
                            val id = videoRenderer["videoId"] as? String
                            if (!id.isNullOrBlank()) {
                                val t = (((videoRenderer["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.firstOrNull() as? Map<*, *>)?.get("text") as? String
                                    ?: (videoRenderer["title"] as? Map<*, *>)?.get("simpleText") as? String
                                
                                val a = (((videoRenderer["shortBylineText"] ?: videoRenderer["ownerText"]) as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                                    ?: ((videoRenderer["shortBylineText"] ?: videoRenderer["ownerText"]) as? Map<*, *>)?.get("simpleText") as? String
                                    ?: ""
                                    
                                val dur = (videoRenderer["lengthText"] as? Map<*, *>)?.get("simpleText") as? String
                                    ?: (((videoRenderer["lengthText"] as? Map<*, *>)?.get("runs") as? List<*>)?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String })
                                    ?: ""

                                if (!t.isNullOrBlank()) {
                                    songs.add(VideoItem(id, t, a, dur))
                                }
                            }
                        } else if (responsiveRenderer != null) {
                            val id = (responsiveRenderer["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String
                                ?: findVideoId(responsiveRenderer)
                            if (!id.isNullOrBlank()) {
                                val flexCols = responsiveRenderer["flexColumns"] as? List<*>
                                
                                fun colTextNode(index: Int): Any? {
                                    val col = flexCols?.getOrNull(index) as? Map<*, *> ?: return null
                                    val flex = col["musicResponsiveListItemFlexColumnRenderer"] as? Map<*, *> ?: return null
                                    return flex["text"]
                                }
                                fun colText(index: Int): String? = ytText(colTextNode(index)).takeIf { it.isNotBlank() }
                                
                                val t = colText(0) ?: ""
                                val a = colText(1)?.split("•")?.firstOrNull()?.trim() ?: ""
                                val parsedArtists = musicArtistText(colTextNode(1)).ifBlank { a }
                                val dur = (colText(2) ?: "").ifBlank { musicFixedDuration(responsiveRenderer) }
                                
                                if (t.isNotBlank()) {
                                    songs.add(VideoItem(id, t, parsedArtists, dur))
                                }
                            }
                        } else {
                            for (value in node.values) {
                                scan(value)
                            }
                        }
                    }
                    is List<*> -> {
                        for (value in node) {
                            scan(value)
                        }
                    }
                }
            }

            scan(root)
            log("getPlaylistSongs found ${songs.size} items for $playlistId")
            Pair(playlistTitle, songs)
        } catch (e: Exception) {
            log("getPlaylistSongs parse error: ${e.message}")
            Pair("Playlist", emptyList())
        }
    }

    private fun extractPlaylistId(url: String): String? {
        val clean = url.trim()
        if (clean.startsWith("spotify:playlist:")) {
            return clean.substringAfter("spotify:playlist:")
        }
        val regex = Regex("""/playlist/([a-zA-Z0-9]+)""")
        val match = regex.find(clean)
        return match?.groupValues?.get(1)
    }

    /** Imports a Spotify playlist via open embed metadata to bypass private developer API keys */
    fun importSpotifyPlaylist(url: String): Pair<String, List<String>> {
        log("importSpotifyPlaylist url=$url")
        
        // 1. Follow any redirects to get the real open.spotify.com URL (handles spotify.link / etc.)
        var finalUrl = url
        try {
            val headRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .head()
                .build()
            http.newCall(headRequest).execute().use { resp ->
                finalUrl = resp.request.url.toString()
                log("Resolved final URL: $finalUrl")
            }
        } catch (e: Exception) {
            log("Failed to resolve redirects: ${e.message}")
        }

        // 2. Extract playlist ID
        val playlistId = extractPlaylistId(finalUrl)
        if (playlistId == null) {
            log("Could not extract Spotify playlist ID from: $finalUrl")
            return Pair("Invalid Spotify URL", emptyList())
        }
        
        val embedUrl = "https://open.spotify.com/embed/playlist/$playlistId"
        log("Fetching Spotify embed URL: $embedUrl")
        
        val html = try {
            http.newCall(Request.Builder()
                .url(embedUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            ).execute().use { it.body?.string() }
        } catch (e: Exception) {
            log("Spotify fetch failed: ${e.message}")
            null
        } ?: return Pair("Failed to fetch Spotify playlist", emptyList())

        try {
            val match = Regex("""<script\s+id="__NEXT_DATA__"\s+type="application/json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html)
            val jsonStr = match?.groupValues?.get(1) ?: return Pair("Failed to parse Spotify embed data", emptyList())
            
            val root = gson.fromJson(jsonStr, Map::class.java)
            val props = root["props"] as? Map<*, *>
            val pageProps = props?.get("pageProps") as? Map<*, *>
            val state = pageProps?.get("state") as? Map<*, *>
            val stateData = state?.get("data") as? Map<*, *>
            val entity = stateData?.get("entity") as? Map<*, *>
            
            val playlistName = (entity?.get("title") as? String)
                ?: (entity?.get("name") as? String)
                ?: "Imported Playlist"
                
            val trackList = entity?.get("trackList") as? List<*> ?: emptyList<Any>()
            val trackQueries = mutableListOf<String>()
            
            for (item in trackList) {
                val track = item as? Map<*, *> ?: continue
                val title = track["title"] as? String ?: continue
                val subtitle = track["subtitle"] as? String ?: ""
                
                val cleanTitle = title.replaceHtmlEntities().trim()
                val cleanArtist = subtitle.replaceHtmlEntities().trim()
                
                if (cleanTitle.isNotEmpty()) {
                    if (cleanArtist.isNotEmpty()) {
                        trackQueries.add("$cleanTitle - $cleanArtist")
                    } else {
                        trackQueries.add(cleanTitle)
                    }
                }
            }
            
            log("Spotify parsed successfully: name='$playlistName', count=${trackQueries.size}")
            return Pair(playlistName, trackQueries)
        } catch (e: Exception) {
            log("Spotify parsing failed: ${e.message}")
            return Pair("Failed to parse Spotify playlist", emptyList())
        }
    }

    private fun String.replaceHtmlEntities(): String {
        return this
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun findVideoId(node: Any?): String? {
        when (node) {
            is Map<*, *> -> {
                val videoId = node["videoId"] as? String
                if (!videoId.isNullOrBlank()) return videoId
                for (value in node.values) {
                    val found = findVideoId(value)
                    if (found != null) return found
                }
            }
            is List<*> -> {
                for (value in node) {
                    val found = findVideoId(value)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findUrlInNode(node: Any?): String? {
        when (node) {
            is Map<*, *> -> {
                val url = node["url"] as? String
                if (!url.isNullOrBlank() && (url.startsWith("http") || url.startsWith("//"))) {
                    return url
                }
                for (value in node.values) {
                    val found = findUrlInNode(value)
                    if (found != null) return found
                }
            }
            is List<*> -> {
                for (item in node.asReversed()) {
                    val found = findUrlInNode(item)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun log(msg: String) { lastDebugMsg = msg; Log.d(TAG, msg) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

data class VideoItem(
    val videoId: String,
    val title: String,
    val author: String,
    val durationText: String = "",
    val customThumbnailUrl: String? = null,
    val localUriString: String? = null,
    /** Present only when the source response exposes a trustworthy view count. */
    val viewCount: Long? = null
) {
    val thumbnail:   String get() = customThumbnailUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    val thumbnailHd: String get() = customThumbnailUrl ?: "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
}

data class ArtistItem(
    val channelId: String,
    val name: String,
    val thumbnail: String,
    val subscriberCount: String = "",
    val isVerified: Boolean = false
)

class AlbumItem(
    val playlistId: String,
    val title: String,
    val author: String,
    thumbnail: String,
    val songCount: String = ""
) {
    val thumbnail: String = thumbnail?.let { t -> if (t.startsWith("//")) "https:$t" else if (t.startsWith("http://")) t.replace("http://", "https://") else t } ?: ""

    operator fun component1() = playlistId
    operator fun component2() = title
    operator fun component3() = author
    operator fun component4() = this.thumbnail
    operator fun component5() = songCount

    fun copy(
        playlistId: String = this.playlistId,
        title: String = this.title,
        author: String = this.author,
        thumbnail: String = this.thumbnail,
        songCount: String = this.songCount
    ) = AlbumItem(playlistId, title, author, thumbnail, songCount)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AlbumItem) return false
        return playlistId == other.playlistId && title == other.title && author == other.author && thumbnail == other.thumbnail && songCount == other.songCount
    }

    override fun hashCode(): Int {
        var result = playlistId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + thumbnail.hashCode()
        result = 31 * result + songCount.hashCode()
        return result
    }

    override fun toString(): String {
        return "AlbumItem(playlistId=$playlistId, title=$title, author=$author, thumbnail=$thumbnail, songCount=$songCount)"
    }
}

data class AllSearchResults(
    val songs:   List<VideoItem>   = emptyList(),
    val videos:  List<VideoItem>   = emptyList(),
    val artists: List<ArtistItem>  = emptyList(),
    val albums:  List<AlbumItem>   = emptyList(),
    val singles: List<AlbumItem>   = emptyList()
)
