package com.vinmusic.innertube

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Parsed shelf from YouTube Music home / related browse (Metrolist FEmusic_home). */
data class YTMusicHomeSection(
    val title: String,
    val songs: List<VideoItem>,
    val browseId: String? = null,
    val params: String? = null,
)

data class YTMusicHomePage(
    val sections: List<YTMusicHomeSection>,
    val continuation: String? = null,
)

data class YTRelatedBrowse(
    val browseId: String,
    val params: String? = null,
)

data class YTNextResult(
    val relatedBrowse: YTRelatedBrowse?,
    val radioPlaylistId: String? = null,
)

/**
 * YouTube Music Innertube client (WEB_REMIX) — official home & related feeds.
 * See Metrolist: YouTube.home() → browseId FEmusic_home; YouTube.next() → related tab.
 */
object YTMusicApi {
    private const val TAG = "VIN_YTM"
    private const val BASE = "https://music.youtube.com/youtubei/v1"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var appContext: Context? = null

    fun attachContext(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun invalidateSession() { /* cookie read fresh each request */ }

    private fun webRemixContext() = mapOf(
        "client" to mapOf(
            "clientName" to "WEB_REMIX",
            "clientVersion" to "1.20231214.00.00",
            "hl" to "en",
            "gl" to "IN",
        )
    )

    private fun buildRequest(url: String, body: Map<*, *>): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .post(gson.toJson(body).toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", "1.20231214.00.00")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
        val ctx = appContext
        if (ctx != null) {
            YTMusicSession.getCookie(ctx)?.let { cookie ->
                b.header("Cookie", cookie)
                YTMusicSession.authorizationHeader(cookie)?.let { b.header("Authorization", it) }
                b.header("X-Goog-AuthUser", "0")
            }
        }
        return b
    }

    private fun postBrowse(browseId: String, params: String? = null, continuation: String? = null): String? {
        val body = if (continuation != null) {
            mapOf("context" to webRemixContext(), "continuation" to continuation)
        } else {
            buildMap<String, Any> {
                put("context", webRemixContext())
                put("browseId", browseId)
                if (!params.isNullOrBlank()) put("params", params)
            }
        }
        return try {
            buildRequest("$BASE/browse?prettyPrint=false", body)
                .build().let { http.newCall(it).execute().use { r -> r.body?.string() } }
        } catch (e: Exception) {
            Log.e(TAG, "browse $browseId failed: ${e.message}")
            null
        }
    }

    /**
     * Normalize thumbnail URLs to HTTPS.
     * Handles "//" prefix (protocol-relative) and "http://" (insecure).
     */
    private fun String.normalizeUrl(): String {
        var url = this
        if (url.startsWith("//")) url = "https:$url"
        if (url.startsWith("http://")) url = "https://" + url.removePrefix("http://")
        return url
    }

    fun getCookie(): String? = appContext?.let { YTMusicSession.getCookie(it) }

    fun findUrlInNode(node: Any?): String? {
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

    /** Official personalized home shelves (FEmusic_home). */
    fun getHomePage(continuation: String? = null, params: String? = null): YTMusicHomePage {
        val raw = if (continuation != null) {
            postBrowse("", continuation = continuation)
        } else {
            postBrowse("FEmusic_home", params = params)
        } ?: return YTMusicHomePage(emptyList(), null)

        return if (continuation != null) parseHomeContinuation(raw) else parseHomeResponse(raw)
    }

    /** Official user library playlists (FEmusic_liked_playlists). */
    fun getLibraryPlaylists(): List<com.vinmusic.innertube.AlbumItem> {
        val raw = postBrowse("FEmusic_liked_playlists")
        if (raw == null) {
            Log.e(TAG, "getLibraryPlaylists: raw response is NULL! Check cookies and connection.")
            return emptyList()
        }
        Log.d(TAG, "getLibraryPlaylists: raw response length = ${raw.length}. Raw snippet: ${raw.take(300)}")
        val playlists = mutableListOf<com.vinmusic.innertube.AlbumItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)
            
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val twoRow = node["musicTwoRowItemRenderer"] as? Map<*, *>
                        if (twoRow != null) {
                            val nav = twoRow["navigationEndpoint"] as? Map<*, *>
                            val browse = nav?.get("browseEndpoint") as? Map<*, *>
                            val browseId = browse?.get("browseId") as? String
                            if (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")) && browseId != "VLLL" && browseId != "LL" && browseId != "VLWL" && browseId != "WL") {
                                val title = ((twoRow["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
                                val subtitle = ((twoRow["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                                    ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
                                    ?.joinToString("") ?: ""
                                
                                val thumbnailRenderer = twoRow["thumbnail"] as? Map<*, *>
                                val musicThumbnailRenderer = thumbnailRenderer?.get("musicThumbnailRenderer") as? Map<*, *>
                                val originalThumbnail = ((musicThumbnailRenderer?.get("thumbnail") as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                                    ?.normalizeUrl() ?: ""
                                val thumbnail = if (originalThumbnail.isNotEmpty()) originalThumbnail else (findUrlInNode(twoRow)?.normalizeUrl() ?: "")
                                
                                playlists.add(com.vinmusic.innertube.AlbumItem(
                                    playlistId = browseId,
                                    title = title,
                                    author = "YouTube Music",
                                    thumbnail = thumbnail,
                                    songCount = subtitle
                                ))
                            }
                        }
                        
                        val responsive = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (responsive != null) {
                            val nav = responsive["navigationEndpoint"] as? Map<*, *>
                            val browse = nav?.get("browseEndpoint") as? Map<*, *>
                            val browseId = browse?.get("browseId") as? String
                            if (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")) && browseId != "VLLL" && browseId != "LL" && browseId != "VLWL" && browseId != "WL") {
                                val title = columnText(responsive, 0) ?: ""
                                val subtitle = columnText(responsive, 1) ?: ""
                                
                                val thumbnailRenderer = responsive["thumbnail"] as? Map<*, *>
                                val musicThumbnailRenderer = thumbnailRenderer?.get("musicThumbnailRenderer") as? Map<*, *>
                                val originalThumbnail = ((musicThumbnailRenderer?.get("thumbnail") as? Map<*, *>)?.get("thumbnails") as? List<*>)
                                    ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("url") as? String }
                                    ?.normalizeUrl() ?: ""
                                val thumbnail = if (originalThumbnail.isNotEmpty()) originalThumbnail else (findUrlInNode(responsive)?.normalizeUrl() ?: "")
                                
                                playlists.add(com.vinmusic.innertube.AlbumItem(
                                    playlistId = browseId,
                                    title = title,
                                    author = "YouTube Music",
                                    thumbnail = thumbnail,
                                    songCount = subtitle
                                ))
                            }
                        }
                        
                        node.values.forEach { scan(it) }
                    }
                    is List<*> -> node.forEach { scan(it) }
                }
            }
            
            scan(root)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse library playlists: ${e.message}")
        }
        return playlists.distinctBy { it.playlistId }
    }

    /**
     * watch/next — extracts related browse endpoint (tab 2) like Metrolist YouTube.next().
     */
    fun getNextRelated(videoId: String, playlistId: String? = null): YTNextResult {
        val body = buildMap<String, Any> {
            put("context", webRemixContext())
            put("videoId", videoId)
            playlistId?.let { put("playlistId", it) }
        }
        val raw = try {
            buildRequest("$BASE/next?prettyPrint=false", body)
                .build().let { http.newCall(it).execute().use { it.body?.string() } }
        } catch (e: Exception) {
            Log.e(TAG, "next failed: ${e.message}")
            return YTNextResult(null, null)
        } ?: return YTNextResult(null, null)

        return parseNextResponse(raw)
    }

    /** Browse related shelf from endpoint returned by [getNextRelated]. */
    fun getRelatedSongs(browseId: String, params: String? = null): List<VideoItem> {
        val raw = postBrowse(browseId, params = params) ?: return emptyList()
        return parseRelatedBrowse(raw)
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseHomeResponse(raw: String): YTMusicHomePage {
        val sections = mutableListOf<YTMusicHomeSection>()
        var continuation: String? = null
        try {
            val root = gson.fromJson(raw, Map::class.java)
            val sectionList = (root["contents"] as? Map<*, *>)
                ?.get("singleColumnBrowseResultsRenderer") as? Map<*, *>
            val tabs = (sectionList?.get("tabs") as? List<*>)?.firstOrNull() as? Map<*, *>
            val tabContent = (tabs?.get("tabRenderer") as? Map<*, *>)?.get("content") as? Map<*, *>
            val slr = tabContent?.get("sectionListRenderer") as? Map<*, *>
            continuation = extractContinuation(slr?.get("continuations"))
            val contents = slr?.get("contents") as? List<*> ?: emptyList<Any?>()
            for (block in contents) {
                val shelf = (block as? Map<*, *>)?.get("musicCarouselShelfRenderer") as? Map<*, *> ?: continue
                parseCarouselShelf(shelf)?.let { sections.add(it) }
            }
            Log.d(TAG, "home parsed ${sections.size} sections")
        } catch (e: Exception) {
            Log.e(TAG, "parseHome: ${e.message}")
        }
        return YTMusicHomePage(sections, continuation)
    }

    private fun parseHomeContinuation(raw: String): YTMusicHomePage {
        val sections = mutableListOf<YTMusicHomeSection>()
        var continuation: String? = null
        try {
            val root = gson.fromJson(raw, Map::class.java)
            val cont = root["continuationContents"] as? Map<*, *>
            val slc = cont?.get("sectionListContinuation") as? Map<*, *>
            continuation = extractContinuation(slc?.get("continuations"))
            val contents = slc?.get("contents") as? List<*> ?: emptyList<Any?>()
            for (block in contents) {
                val shelf = (block as? Map<*, *>)?.get("musicCarouselShelfRenderer") as? Map<*, *>
                    ?: continue
                parseCarouselShelf(shelf)?.let { sections.add(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseHomeContinuation: ${e.message}")
        }
        return YTMusicHomePage(sections, continuation)
    }

    private fun parseCarouselShelf(shelf: Map<*, *>): YTMusicHomeSection? {
        val header = shelf["header"] as? Map<*, *> ?: return null
        val basic = header["musicCarouselShelfBasicHeaderRenderer"] as? Map<*, *>
        val title = ((basic?.get("title") as? Map<*, *>)?.get("runs") as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            ?.joinToString("")?.trim().orEmpty()
        if (title.isEmpty()) return null

        val moreBtn = basic?.get("moreContentButton") as? Map<*, *>
        val browseEndpoint = ((moreBtn?.get("buttonRenderer") as? Map<*, *>)
            ?.get("navigationEndpoint") as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>
        val browseId = browseEndpoint?.get("browseId") as? String
        val params = browseEndpoint?.get("params") as? String

        val songs = mutableListOf<VideoItem>()
        val contents = shelf["contents"] as? List<*> ?: emptyList<Any?>()
        for (item in contents) {
            val map = item as? Map<*, *> ?: continue
            parseVideoFromShelfItem(map)?.let { songs.add(it) }
        }
        if (songs.isEmpty()) return null
        return YTMusicHomeSection(title, songs.distinctBy { it.videoId }, browseId, params)
    }

    private fun parseVideoFromShelfItem(item: Map<*, *>): VideoItem? {
        // musicResponsiveListItemRenderer (quick picks row)
        val responsive = item["musicResponsiveListItemRenderer"] as? Map<*, *>
        if (responsive != null) {
            val id = (responsive["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String
                ?: return null
            val title = columnText(responsive, 0) ?: return null
            val author = columnText(responsive, 1)?.split("•")?.firstOrNull()?.trim() ?: ""
            val dur = columnText(responsive, 2) ?: ""
            return VideoItem(id, title, author, dur)
        }
        // musicTwoRowItemRenderer
        val twoRow = item["musicTwoRowItemRenderer"] as? Map<*, *>
        if (twoRow != null) {
            val nav = twoRow["navigationEndpoint"] as? Map<*, *>
            val watch = nav?.get("watchEndpoint") as? Map<*, *>
            val id = watch?.get("videoId") as? String ?: return null
            val title = ((twoRow["title"] as? Map<*, *>)?.get("runs") as? List<*>)
                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: return null
            val author = ((twoRow["subtitle"] as? Map<*, *>)?.get("runs") as? List<*>)
                ?.firstOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String } ?: ""
            return VideoItem(id, title, author, "")
        }
        return null
    }

    private fun columnText(renderer: Map<*, *>, index: Int): String? {
        val cols = renderer["flexColumns"] as? List<*> ?: return null
        val col = cols.getOrNull(index) as? Map<*, *> ?: return null
        val flex = col["musicResponsiveListItemFlexColumnRenderer"] as? Map<*, *>
        return ((flex?.get("text") as? Map<*, *>)?.get("runs") as? List<*>)
            ?.mapNotNull { (it as? Map<*, *>)?.get("text") as? String }
            ?.joinToString("")
    }

    private fun parseNextResponse(raw: String): YTNextResult {
        try {
            val root = gson.fromJson(raw, Map::class.java)
            val watchNext = (root["contents"] as? Map<*, *>)
                ?.get("singleColumnMusicWatchNextResultsRenderer") as? Map<*, *>
            val tabbed = (watchNext?.get("tabbedRenderer") as? Map<*, *>)
                ?.get("watchNextTabbedResultsRenderer") as? Map<*, *>
            val tabs = tabbed?.get("tabs") as? List<*> ?: return YTNextResult(null, null)

            val relatedTab = tabs.getOrNull(2) as? Map<*, *>
            val browseEndpoint = ((relatedTab?.get("tabRenderer") as? Map<*, *>)
                ?.get("endpoint") as? Map<*, *>)?.get("browseEndpoint") as? Map<*, *>
            val related = (browseEndpoint?.get("browseId") as? String)?.let { id ->
                YTRelatedBrowse(id, browseEndpoint["params"] as? String)
            }

            // Automix / radio playlist id from queue panel
            var radioId: String? = null
            fun scanRadio(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        val auto = node["automixPreviewVideoRenderer"] as? Map<*, *>
                        val wpe = ((auto?.get("content") as? Map<*, *>)
                            ?.get("automixPlaylistVideoRenderer") as? Map<*, *>)
                            ?.get("navigationEndpoint") as? Map<*, *>
                        val playlistId = ((wpe?.get("watchPlaylistEndpoint") as? Map<*, *>)
                            ?.get("playlistId") as? String)
                        if (!playlistId.isNullOrBlank()) radioId = playlistId
                        node.values.forEach { scanRadio(it) }
                    }
                    is List<*> -> node.forEach { scanRadio(it) }
                }
            }
            scanRadio(root)
            return YTNextResult(related, radioId)
        } catch (e: Exception) {
            Log.e(TAG, "parseNext: ${e.message}")
            return YTNextResult(null, null)
        }
    }

    private fun parseRelatedBrowse(raw: String): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        try {
            val root = gson.fromJson(raw, Map::class.java)
            fun scan(node: Any?) {
                when (node) {
                    is Map<*, *> -> {
                        parseVideoFromShelfItem(node)?.let { out.add(it) }
                        val responsive = node["musicResponsiveListItemRenderer"] as? Map<*, *>
                        if (responsive != null) {
                            val id = (responsive["playlistItemData"] as? Map<*, *>)?.get("videoId") as? String
                            if (!id.isNullOrBlank()) {
                                val overlay = responsive["overlay"] as? Map<*, *>
                                val musicType = ((overlay?.get("musicItemThumbnailOverlayRenderer") as? Map<*, *>)
                                    ?.get("content") as? Map<*, *>)
                                    ?.get("musicPlayButtonRenderer") as? Map<*, *>
                                val watchCfg = ((musicType?.get("playNavigationEndpoint") as? Map<*, *>)
                                    ?.get("watchEndpoint") as? Map<*, *>)
                                    ?.get("watchEndpointMusicSupportedConfigs") as? Map<*, *>
                                val videoType = ((watchCfg?.get("watchEndpointMusicConfig") as? Map<*, *>)
                                    ?.get("musicVideoType") as? String)
                                if (videoType == null || videoType == "MUSIC_VIDEO_TYPE_ATV") {
                                    columnText(responsive, 0)?.let { title ->
                                        val author = columnText(responsive, 1)?.split("•")?.firstOrNull()?.trim() ?: ""
                                        val dur = columnText(responsive, 2) ?: ""
                                        out.add(VideoItem(id, title, author, dur))
                                    }
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
            Log.e(TAG, "parseRelated: ${e.message}")
        }
        return out.distinctBy { it.videoId }
    }

    private fun extractContinuation(continuations: Any?): String? {
        val list = continuations as? List<*> ?: return null
        for (c in list) {
            val map = c as? Map<*, *> ?: continue
            val next = map["nextContinuationData"] as? Map<*, *>
            val token = next?.get("continuation") as? String
            if (!token.isNullOrBlank()) return token
        }
        return null
    }
}
