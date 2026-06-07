package com.vinmusic.innertube

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object InnerTube {
    const val TAG  = "VIN_STREAM"
    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    /** Last log message — shown on screen without ADB */
    var lastDebugMsg = ""; private set

    /** Visitor data token (fetched once, used in every player request) */
    @Volatile private var visitorData = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Client definitions ────────────────────────────────────────────────────
    // Confirmed from PC tests: ANDROID_VR (id=28) returns OK + direct audio URL
    // ANDROID (id=3) and IOS (id=5) return 400 from our network

    private data class YTClient(
        val name: String,
        val version: String,
        val clientId: String,      // X-YouTube-Client-Name value
        val ua: String,
        val extra: Map<String, Any> = emptyMap()
    )

    private val CLIENTS = listOf(
        // [OK] Primary: ANDROID_VR — confirmed OK + direct googlevideo.com URL
        YTClient("ANDROID_VR", "1.60.19", "28",
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip",
            mapOf("androidSdkVersion" to 32)),
        // TV embed — no cipher decryption needed
        YTClient("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", "85",
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 TV Safari/538.1"),
        // ANDROID + IOS fallbacks (may work on device even if blocked from PC)
        YTClient("ANDROID", "17.31.35", "3",
            "com.google.android.youtube/17.31.35(Linux; U; Android 11) gzip",
            mapOf("androidSdkVersion" to 30)),
        YTClient("IOS", "19.09.3", "5",
            "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X) AppleWebKit/605.1.15",
            mapOf("deviceMake" to "Apple", "deviceModel" to "iPhone16,2",
                  "osName" to "iPhone",    "osVersion"   to "17.4.0.21E217")),
    )

    // ── Visitor data ──────────────────────────────────────────────────────────
    /** Fetches a fresh YouTube visitor data token (helps bypass LOGIN_REQUIRED) */
    private fun ensureVisitorData() {
        if (visitorData.isNotEmpty()) return
        try {
            val html = http.newCall(Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent",      "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                .build()).execute().use { it.body?.string() }

            val vd = html?.let {
                Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
            }
            visitorData = vd ?: ""
            log("visitorData: ${if (vd != null) "${vd.take(20)}... [OK]" else "not found"}")
        } catch (e: Exception) {
            log("visitorData fetch err: ${e.message?.take(60)}")
        }
    }

    // Helper to dynamically match the User-Agent based on googlevideo URL params
    fun getUserAgentForUrl(url: String): String {
        return when {
            url.contains("c=ANDROID_VR") -> "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12; GB) gzip"
            url.contains("c=ANDROID") -> "com.google.android.youtube/17.31.35(Linux; U; Android 11) gzip"
            url.contains("c=IOS") -> "com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X) AppleWebKit/605.1.15"
            url.contains("c=TVHTML5") -> "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 TV Safari/538.1"
            else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
    }

    // ── Main entry ────────────────────────────────────────────────────────────
    fun getStreamUrl(videoId: String, quality: String? = null): String? {
        log("getStreamUrl videoId=$videoId quality=$quality")
        if (videoId.isBlank()) { log("ERROR: blank videoId!"); return null }

        // 1. Fetch visitor token (bypasses LOGIN_REQUIRED for most music)
        ensureVisitorData()

        // 2. Try NewPipeExtractor first (deciphers both signature and n-parameter for unthrottled streaming)
        log("Trying NewPipeExtractor...")
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
            log("NewPipe result: ${url?.take(60)}")
            if (!url.isNullOrEmpty()) return url
        } catch (e: Throwable) {
            log("NewPipe FAILED: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }

        // 3. Fallback to InnerTube direct clients
        log("Trying InnerTube direct clients as fallback...")
        for (client in CLIENTS) {
            log("Trying ${client.name}...")
            try {
                val url = fetchViaClient(videoId, client, quality)
                if (!url.isNullOrEmpty()) {
                    log("SUCCESS via ${client.name}: ${url.take(60)}")
                    return url
                }
            } catch (e: Throwable) {
                log("${client.name} threw: ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
        }

        log("ALL clients and fallbacks failed for $videoId")
        return null
    }

    // ── InnerTube player request ──────────────────────────────────────────────
    private fun fetchViaClient(videoId: String, client: YTClient, quality: String? = null): String? {
        val ctx = buildMap<String, Any> {
            put("clientName",    client.name)
            put("clientVersion", client.version)
            put("hl",            "en")
            put("gl",            "IN")   // India locale — improves music availability
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
            .url("$BASE/player?prettyPrint=false")
            .post(gson.toJson(body).toRequestBody(JSON))
            .header("Content-Type",             "application/json")
            .header("User-Agent",               client.ua)
            .header("X-YouTube-Client-Name",    client.clientId)
            .header("X-YouTube-Client-Version", client.version)
            .header("Origin",                   "https://www.youtube.com")
            .header("Referer",                  "https://www.youtube.com/")
        if (visitorData.isNotEmpty())
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)

        val raw = http.newCall(reqBuilder.build()).execute().use { it.body?.string() }
            ?: run { log("${client.name}: null body"); return null }

        val root   = gson.fromJson(raw, Map::class.java)
        val status = (root["playabilityStatus"] as? Map<*, *>)?.get("status") as? String
        val reason = (root["playabilityStatus"] as? Map<*, *>)?.get("reason") as? String
        log("${client.name}: status=$status reason=${reason?.take(50)}")

        if (status != "OK") return null

        val sd = root["streamingData"] as? Map<*, *>
            ?: run { log("${client.name}: no streamingData"); return null }

        // Best: audio-only adaptive without cipher
        val audioUrl = (sd["adaptiveFormats"] as? List<*>)
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

        if (!audioUrl.isNullOrEmpty()) return audioUrl

        // Fallback: muxed stream
        return (sd["formats"] as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.filter { it.containsKey("url") && !it.containsKey("signatureCipher") }
            ?.firstOrNull()?.get("url") as? String
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
                .minByOrNull { it.getResolution() ?: "" }?.content
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
    fun search(query: String): List<VideoItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to "1.20231214.00.00",
                "hl" to "en", "gl" to "IN"
            )),
            "query" to query,
            "params" to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D" // YouTube Music 'Songs' filter
        )
        val raw = try {
            http.newCall(Request.Builder()
                .url("$ytMusicBase/search?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("X-YouTube-Client-Name", "67")
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
        return songs.distinctBy { it.videoId }.take(30)
    }

    // ── YouTube Music Browse and Search API ───────────────────────────────────

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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
                            val title = ((col0Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            
                            val col1 = flexCols?.getOrNull(1) as? Map<*, *>
                            val col1Renderer = col1?.get("musicResponsiveListItemFlexColumnRenderer") as? Map<*, *>
                            val author = ((col1Renderer?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
                                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                            
                            if (videoId.isNotEmpty() && title.isNotEmpty()) {
                                songs.add(VideoItem(videoId, title, author, ""))
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

    /**
     * Fallback search for albums: Searches YouTube Music for albums by artist name.
     * Uses WEB_REMIX client and parses BOTH musicTwoRowItemRenderer and musicResponsiveListItemRenderer.
     */
    fun searchArtistAlbums(artistName: String): List<AlbumItem> {
        val ytMusicBase = "https://music.youtube.com/youtubei/v1"
        val body = mapOf(
            "context" to mapOf("client" to mapOf(
                "clientName" to "WEB_REMIX",
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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

    // ── Search All Types (Songs + Artists + Albums in one call) ──────────────
    fun searchAll(query: String): AllSearchResults {
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
            // while parsing artists and albums from standard YouTube search.
            val songs   = search(query).toMutableList()
            val artists = mutableListOf<ArtistItem>()
            val albums  = mutableListOf<AlbumItem>()

            for (sec in secs) {
                val items = ((sec as? Map<*, *>)?.get("itemSectionRenderer") as? Map<*, *>)
                    ?.get("contents") as? List<*> ?: continue
                for (item in items) {
                    val m = item as? Map<*, *> ?: continue

                    // Artist/Channel
                    m["channelRenderer"]?.let { c ->
                        val cr   = c as? Map<*, *> ?: return@let
                        val id   = cr["channelId"] as? String ?: return@let
                        val name = (cr["title"] as? Map<*, *>)?.get("simpleText") as? String ?: return@let
                        val subs = (cr["subscriberCountText"] as? Map<*, *>)?.get("simpleText") as? String ?: ""
                        val thumb = ((cr["thumbnail"] as? Map<*, *>)?.get("thumbnails") as? List<*>)
                            ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                            ?.let {
                                var url = it
                                if (url.startsWith("//")) url = "https:$url"
                                if (url.startsWith("http://")) url = url.replace("http://", "https://")
                                url
                            } ?: ""
                        artists.add(ArtistItem(id, name, thumb, subs))
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
            AllSearchResults(songs, artists, albums)
        } catch (e: Exception) { AllSearchResults() }
    }

    /** Get top songs for an artist by name */
    fun getArtistTopSongs(artistName: String): List<VideoItem> =
        search("$artistName top songs")

    // ── Channel browse (artist banner + bio) ─────────────────────────────────
    data class ChannelData(val bannerUrl: String = "", val bio: String = "", val subscriberCount: String = "", val title: String = "", val avatarUrl: String = "")

    fun fetchChannelData(channelId: String): ChannelData {
        if (channelId.isBlank()) return ChannelData()
        val body = mapOf("browseId" to channelId,
            "context" to mapOf("client" to mapOf("clientName" to "WEB",
                "clientVersion" to "2.20231219.04.00", "hl" to "en", "gl" to "IN")))
        val raw = try {
            http.newCall(Request.Builder().url("$BASE/browse?prettyPrint=false")
                .post(gson.toJson(body).toRequestBody(JSON))
                .header("Content-Type", "application/json").header("User-Agent", "Mozilla/5.0")
                .build()).execute().use { it.body?.string() }
        } catch (e: Exception) { null } ?: return ChannelData()
        return try {
            val root = gson.fromJson(raw, Map::class.java)
            val hdr  = (root["header"] as? Map<*, *>)?.get("c4TabbedHeaderRenderer") as? Map<*, *>
            val banner = ((hdr?.get("banner") as? Map<*, *>)?.get("thumbnails") as? List<*>)
                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                ?.let {
                    var url = it
                    if (url.startsWith("//")) url = "https:$url"
                    if (url.startsWith("http://")) url = url.replace("http://", "https://")
                    url
                } ?: ""
            val avatarNode = hdr?.get("avatar") as? Map<*, *>
            val avatar = (avatarNode?.get("thumbnails") as? List<*>)
                ?.lastOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                ?.let {
                    var url = it
                    if (url.startsWith("//")) url = "https:$url"
                    if (url.startsWith("http://")) url = url.replace("http://", "https://")
                    url
                } ?: ""
            val subs = (hdr?.get("subscriberCountText") as? Map<*, *>)?.get("simpleText") as? String ?: ""
            val title = ((root["metadata"] as? Map<*, *>)
                ?.get("channelMetadataRenderer") as? Map<*, *>)?.get("title") as? String ?: ""
            var bio  = ((root["metadata"] as? Map<*, *>)
                ?.get("channelMetadataRenderer") as? Map<*, *>)?.get("description") as? String ?: ""
            
            // Attempt to fetch real artist bio from Wikipedia to replace generic YouTube channel descriptions (which users reported as "fake info")
            if (title.isNotEmpty()) {
                val wikiBio = fetchArtistBio(title)
                if (wikiBio.isNotEmpty()) {
                    bio = wikiBio
                } else if (bio.contains("Subscribe", ignoreCase = true) || bio.contains("Official Channel", ignoreCase = true)) {
                    // Try without "Topic" or "Vevo"
                    val cleanTitle = title.replace("- Topic", "").replace("VEVO", "", ignoreCase = true).trim()
                    val cleanWikiBio = fetchArtistBio(cleanTitle)
                    if (cleanWikiBio.isNotEmpty()) {
                        bio = cleanWikiBio
                    }
                }
            }

            ChannelData(banner, bio, subs, title, avatar)
        } catch (e: Exception) { ChannelData() }
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
                "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
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

    // ── Suggestions ───────────────────────────────────────────────────────────
    fun getSuggestions(query: String): List<String> = try {
        if (query.isBlank()) emptyList()
        else {
            val url = "https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false"
            val body = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB_REMIX",
                        "clientVersion" to "1.20231214.00.00",
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
            buildList<String> {
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
        }
    } catch (e: Exception) {
        log("Suggestions fetch failed: ${e.message}")
        emptyList()
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
                "clientName" to "WEB_REMIX", "clientVersion" to "1.20231214.00.00",
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
                .header("X-YouTube-Client-Version", "1.20231214.00.00")
            
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
                                
                                fun colText(index: Int): String? {
                                    val col = flexCols?.getOrNull(index) as? Map<*, *> ?: return null
                                    val flex = col["musicResponsiveListItemFlexColumnRenderer"] as? Map<*, *> ?: return null
                                    val textNode = flex["text"] as? Map<*, *> ?: return null
                                    return (textNode["simpleText"] as? String)
                                        ?: (textNode["runs"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }?.joinToString("")
                                }
                                
                                val t = colText(0) ?: ""
                                val a = colText(1)?.split("•")?.firstOrNull()?.trim() ?: ""
                                val dur = colText(2) ?: ""
                                
                                if (t.isNotBlank()) {
                                    songs.add(VideoItem(id, t, a, dur))
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
    val videoId: String, val title: String,
    val author: String, val durationText: String = ""
) {
    val thumbnail:   String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    val thumbnailHd: String get() = "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg"
}

data class ArtistItem(
    val channelId: String,
    val name: String,
    val thumbnail: String,
    val subscriberCount: String = ""
)

class AlbumItem(
    val playlistId: String,
    val title: String,
    val author: String,
    thumbnail: String,
    val songCount: String = ""
) {
    val thumbnail: String = if (thumbnail.startsWith("//")) "https:$thumbnail" else if (thumbnail.startsWith("http://")) thumbnail.replace("http://", "https://") else thumbnail

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
    val artists: List<ArtistItem>  = emptyList(),
    val albums:  List<AlbumItem>   = emptyList(),
    val singles: List<AlbumItem>   = emptyList()
)
