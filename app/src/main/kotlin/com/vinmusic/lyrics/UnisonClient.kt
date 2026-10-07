package com.vinmusic.lyrics

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Client for the Unison lyrics API (unison.boidu.dev).
 *
 * Supports two lookup strategies:
 *   1. **Direct** — `GET /lyrics?v={videoId}` (fastest, uses YTM video ID)
 *   2. **Search** — `GET /lyrics/search?q={query}` (fallback when videoId lookup fails)
 *
 * Response format: `{ success: true, data: { lyrics, format, syncType, videoId, ... } }`
 *   - format: "ttml" → word-level rich sync
 *   - format: "lrc"  → line-level sync
 *   - format: "plain" → unsynced text
 */
object UnisonClient {
    private val gson = Gson()
    @Volatile private var disabledUntilMs: Long = 0L
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private const val BASE_HOST = "unison.boidu.dev"

    fun isAvailable(): Boolean = System.currentTimeMillis() >= disabledUntilMs

    /**
     * Fetch lyrics via direct videoId lookup, falling back to search.
     * Returns null if the API is disabled or returns no usable lyrics.
     */
    fun fetch(videoId: String, title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        if (!isAvailable()) {
            android.util.Log.w("UnisonClient", "Skipped — self-disabled until ${android.text.format.DateFormat.format("HH:mm:ss", java.util.Date(disabledUntilMs))}")
            return null
        }

        // Strategy A: Direct lookup by videoId
        if (videoId.isNotBlank()) {
            android.util.Log.d("UnisonClient", "Direct lookup: v=$videoId")
            val direct = fetchByVideoId(videoId)
            if (direct != null) {
                val src = when (direct) { is LyricsResult.Synced -> direct.source; is LyricsResult.Plain -> direct.source; else -> "?" }
                android.util.Log.d("UnisonClient", "Direct lookup succeeded: $src")
                return direct
            }
            android.util.Log.d("UnisonClient", "Direct lookup returned null, falling back to search")
        }

        // Strategy B: Search by title + artist
        if (title.isNotBlank()) {
            val query = if (artist.isNotBlank()) "$title $artist" else title
            android.util.Log.d("UnisonClient", "Search: q=$query")
            val result = fetchBySearch(query, title, durationMs)
            if (result != null) {
                val src = when (result) { is LyricsResult.Synced -> result.source; is LyricsResult.Plain -> result.source; else -> "?" }
                android.util.Log.d("UnisonClient", "Search succeeded: $src")
                return result
            }
            android.util.Log.d("UnisonClient", "Search returned null")
            return null
        }

        return null
    }

    // ── Strategy A: Direct lookup ────────────────────────────────────────────

    private fun fetchByVideoId(videoId: String): LyricsResult? {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(BASE_HOST)
            .addPathSegment("lyrics")
            .addQueryParameter("v", videoId)
            .build()

        val body = executeRequest(url) ?: return null
        return parseUnisonResponse(body)
    }

    // ── Strategy B: Search ────────────────────────────────────────────────────

    /**
     * Search returns metadata only (videoId, format, syncType) — NOT the lyrics
     * text itself. So this is a two-step fetch:
     *   1. GET /lyrics/search?q=... → pick best result by quality + match
     *   2. GET /lyrics?v={picked videoId} → get the actual lyrics content
     *
     * We pass the original title and durationMs so pickBestVideoId can reject
     * garbage matches (wrong song, wrong duration).
     */
    private fun fetchBySearch(query: String, originalTitle: String, durationMs: Long): LyricsResult? {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(BASE_HOST)
            .addPathSegments("lyrics/search")
            .addQueryParameter("q", encoded)
            .build()

        val body = executeRequest(url) ?: return null
        val videoId = pickBestVideoId(body, originalTitle, durationMs) ?: return null
        android.util.Log.d("UnisonClient", "Search picked videoId=$videoId, fetching lyrics...")
        return fetchByVideoId(videoId)
    }

    /**
     * Parse search response and return the best videoId.
     *
     * Filtering rules — a search result must pass ALL of:
     *   - matchScore >= 0.70 (API's own confidence score)
     *   - duration within 30% of the requested duration (if known)
     *
     * Among passing results, prefer: richsync > linesync > plain.
     * Returns null if nothing passes the quality gate.
     */
    private fun pickBestVideoId(searchBody: String, originalTitle: String, durationMs: Long): String? {
        val json = gson.fromJson(searchBody, JsonObject::class.java) ?: return null
        if (json.get("success")?.asBoolean != true) return null
        val data = json.get("data") ?: return null
        if (!data.isJsonArray) return null
        val arr = data.asJsonArray
        if (arr.size() == 0) return null

        val items = arr.mapNotNull { it.asJsonObject }
        android.util.Log.d("UnisonClient", "Search: ${arr.size()} results")

        // Filter by matchScore (API's own relevance metric)
        val minScore = 0.70
        val scored = items.filter { item ->
            val score = item.get("matchScore")?.asDouble ?: 0.0
            if (score < minScore) {
                val song = item.get("song")?.asString ?: "?"
                android.util.Log.d("UnisonClient", "  Rejected '$song' — matchScore=$score < $minScore")
                false
            } else true
        }

        // Filter by duration (within 30% of expected)
        val durationFiltered = if (durationMs > 0) {
            scored.filter { item ->
                val apiDurationMs = (item.get("duration")?.asLong ?: 0L) * 1000L
                if (apiDurationMs <= 0) return@filter true  // no duration info → don't reject
                val diff = kotlin.math.abs(apiDurationMs - durationMs).toDouble()
                val ratio = diff / durationMs
                if (ratio > 0.30) {
                    val song = item.get("song")?.asString ?: "?"
                    android.util.Log.d("UnisonClient", "  Rejected '$song' — duration ${apiDurationMs/1000}s vs ${durationMs/1000}s (off by ${(ratio*100).toInt()}%)")
                    false
                } else true
            }
        } else scored

        if (durationFiltered.isEmpty()) {
            android.util.Log.d("UnisonClient", "No results passed quality gate")
            return null
        }

        // Prefer richsync, then linesync, then anything
        val rich = durationFiltered.filter { it.get("syncType")?.asString == "richsync" }
        val synced = durationFiltered.filter {
            val st = it.get("syncType")?.asString ?: ""
            st == "richsync" || st == "linesync"
        }
        val pick = rich.firstOrNull() ?: synced.firstOrNull() ?: durationFiltered.first()
        val videoId = pick.get("videoId")?.asString
        val song = pick.get("song")?.asString ?: "?"
        val format = pick.get("format")?.asString ?: "?"
        val syncType = pick.get("syncType")?.asString ?: "?"
        val score = pick.get("matchScore")?.asDouble ?: 0.0
        android.util.Log.d("UnisonClient", "Picked: '$song' videoId=$videoId score=$score format=$format syncType=$syncType")
        return videoId
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun executeRequest(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.0")
            .header("Accept", "application/json")
            .build()

        return http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                android.util.Log.w("UnisonClient", "Auth error ${response.code} — self-disabling for 15m")
                disabledUntilMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
                return@use null
            }
            if (!response.isSuccessful) {
                android.util.Log.d("UnisonClient", "HTTP ${response.code} from ${url.encodedPath}")
                return@use null
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                android.util.Log.d("UnisonClient", "Empty response body from ${url.encodedPath}")
                return@use null
            }
            body
        }
    }

    // ── Response parsing ─────────────────────────────────────────────────────

    private fun parseUnisonResponse(body: String): LyricsResult? {
        val json = gson.fromJson(body, JsonObject::class.java)
        if (json == null) {
            android.util.Log.e("UnisonClient", "Failed to parse response as JSON")
            return null
        }
        val success = json.get("success")?.asBoolean ?: false
        if (!success) {
            val err = json.get("error")?.asString ?: "unknown"
            android.util.Log.d("UnisonClient", "API returned success=false: $err")
            return null
        }

        // Direct lookup response: { success, data: { lyrics, format, ... } }
        val dataElement = json.get("data")
        if (dataElement == null) {
            android.util.Log.d("UnisonClient", "Response has no 'data' field")
            return null
        }
        if (dataElement.isJsonArray) {
            // Defensive: search arrays should never reach here (handled by
            // pickBestVideoId → fetchByVideoId). If one does, log and bail.
            android.util.Log.w("UnisonClient", "Unexpected JsonArray in parseUnisonResponse")
            return null
        }
        android.util.Log.d("UnisonClient", "Parsing single item")
        return parseUnisonItem(dataElement.asJsonObject)
    }

    /** Parse a single Unison result object into LyricsResult */
    private fun parseUnisonItem(data: JsonObject): LyricsResult? {
        val lyrics = data.get("lyrics")?.asString?.trim()?.takeIf { it.isNotEmpty() }
        if (lyrics == null) {
            android.util.Log.d("UnisonClient", "Item has no lyrics text")
            return null
        }
        val format = data.get("format")?.asString ?: "plain"
        val syncType = data.get("syncType")?.asString ?: ""
        val source = "Unison"

        return when (format) {
            "ttml" -> {
                val lines = TtmlParser.parse(lyrics, forceRichSync = syncType == "richsync")
                if (lines.isNotEmpty()) LyricsResult.Synced(lines, source) else null
            }
            "lrc" -> {
                val lines = LyricsHelper.parseLrc(lyrics)
                if (lines.isNotEmpty()) LyricsResult.Synced(lines, source) else null
            }
            else -> {
                // "plain" or unknown
                LyricsResult.Plain(lyrics, source)
            }
        }
    }
}

// ── TTML Parser (shared, used by Unison for richsync lyrics) ──────────────────

internal object TtmlParser {
    /**
     * Parse TTML string into synced lyrics lines.
     * Filters out background vocal spans (ttm:role="x-bg").
     *
     * @param forceRichSync If true, mark all lines as richSync regardless of word timings
     *   (used when the API declares syncType="richsync" even if timing data is sparse).
     */
    fun parse(ttml: String, forceRichSync: Boolean = false): List<LyricsLine> {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                // This XML comes from a remote lyrics API — fully disable DOCTYPE so
                // external entities AND internal entity expansion ("billion laughs")
                // are rejected outright, not just external resolution.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                runCatching { isExpandEntityReferences = false }
            }
            val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
            val paragraphs = mutableListOf<Element>()
            collectElements(doc.documentElement, "p", paragraphs)

            paragraphs.mapNotNull { p ->
                val words = collectTimedWords(p)
                val lineStart = parseTime(attr(p, "begin"))
                    ?: words.minOfOrNull { it.startMs }
                    ?: firstSpanStart(p)
                    ?: return@mapNotNull null
                val explicitEnd = parseTime(attr(p, "end"))
                val duration = parseTime(attr(p, "dur"))
                val lineEnd = explicitEnd
                    ?: duration?.let { lineStart + it }
                    ?: words.maxOfOrNull { it.endMs }
                    ?: 0L

                val text = if (words.isNotEmpty()) {
                    words.joinToString(" ") { it.text }.trim()
                } else {
                    p.textContent?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                }

                if (text.isBlank() || LyricsHelper.isNonLyricLinePublic(text)) {
                    null
                } else {
                    LyricsLine(
                        timeMs = lineStart,
                        text = text,
                        endTimeMs = lineEnd.takeIf { it > lineStart } ?: 0L,
                        words = words,
                        isRichSync = forceRichSync || (words.isNotEmpty() && words.any { it.endMs > 0 })
                    )
                }
            }
                .distinctBy { "${it.timeMs}|${it.text}" }
                .sortedBy { it.timeMs }
        } catch (e: Exception) {
            // Catch Exception, not Throwable — Errors (StackOverflow from deeply
            // nested XML) should not be silently swallowed here.
            emptyList()
        }
    }

    /**
     * Collect word-level `<span>` elements, filtering out background vocals.
     */
    private fun collectTimedWords(root: Element): List<WordTiming> {
        val spans = mutableListOf<Element>()
        collectElements(root, "span", spans)
        return spans.mapNotNull { span ->
            // Filter background vocals (ttm:role="x-bg")
            val role = span.getAttribute("ttm:role")
            if (role == "x-bg") return@mapNotNull null

            val start = parseTime(attr(span, "begin")) ?: return@mapNotNull null
            val end = parseTime(attr(span, "end"))
                ?: parseTime(attr(span, "dur"))?.let { start + it }
                ?: 0L
            val raw = span.textContent ?: return@mapNotNull null
            val collapsed = raw.replace(Regex("\\s+"), " ")
            val text = collapsed.trim()
            if (text.isBlank()) return@mapNotNull null
            // Detect whether a whitespace text node follows this span inside the
            // parent <p> — that's where TTML stores inter-word spaces.
            val next = span.nextSibling
            val trailingSpace = next is org.w3c.dom.Text &&
                next.textContent.any { it.isWhitespace() }
            WordTiming(
                text = text,
                startMs = start,
                endMs = end.takeIf { it > start } ?: 0L,
                hasTrailingSpace = trailingSpace,
                isBgVocal = false  // BG vocals are filtered out; field kept for Phase 2
            )
        }.sortedBy { it.startMs }
    }

    private fun firstSpanStart(root: Element): Long? {
        val spans = mutableListOf<Element>()
        collectElements(root, "span", spans)
        return spans.mapNotNull { parseTime(attr(it, "begin")) }.minOrNull()
    }

    private fun collectElements(node: Node?, localName: String, out: MutableList<Element>) {
        if (node == null) return
        if (node is Element && node.local() == localName) out += node
        var child = node.firstChild
        while (child != null) {
            collectElements(child, localName, out)
            child = child.nextSibling
        }
    }

    private fun Element.local(): String = localName ?: nodeName.substringAfterLast(':')

    private fun attr(element: Element, name: String): String {
        val direct = element.getAttribute(name)
        if (direct.isNotBlank()) return direct
        val prefixed = element.getAttribute("tts:$name")
        if (prefixed.isNotBlank()) return prefixed
        val param = element.getAttribute("ttp:$name")
        if (param.isNotBlank()) return param
        return ""
    }

    private fun parseTime(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.endsWith("ms")) return value.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (value.endsWith("s")) return value.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }

        val parts = value.split(":")
        val seconds = when (parts.size) {
            3 -> {
                val hours = parts[0].toDoubleOrNull() ?: return null
                val minutes = parts[1].toDoubleOrNull() ?: return null
                val secs = parts[2].toDoubleOrNull() ?: return null
                hours * 3600 + minutes * 60 + secs
            }
            2 -> {
                val minutes = parts[0].toDoubleOrNull() ?: return null
                val secs = parts[1].toDoubleOrNull() ?: return null
                minutes * 60 + secs
            }
            1 -> parts[0].toDoubleOrNull() ?: return null
            else -> return null
        }
        return (seconds * 1000).toLong()
    }
}
