package com.vinmusic.lyrics

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long = 0L,
    val hasTrailingSpace: Boolean = true,
    val isBgVocal: Boolean = false
)

data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val endTimeMs: Long = 0L,
    val words: List<WordTiming>? = null,
    val isRichSync: Boolean = false
) {
    companion object {
        /** Build a LyricsLine with isRichSync auto-detected from word timings. */
        fun withRichSync(line: LyricsLine, forceRichSync: Boolean = false): LyricsLine {
            val rich = forceRichSync ||
                (line.words?.isNotEmpty() == true && line.words.any { it.endMs > 0 })
            return if (rich == line.isRichSync) line else line.copy(isRichSync = rich)
        }
    }
}

sealed class LyricsResult {
    data class Synced(val lines: List<LyricsLine>, val source: String) : LyricsResult()
    data class Plain(val text: String, val source: String) : LyricsResult()
    object NotFound : LyricsResult()
}

/** Quality score for cache comparison. Higher = better. */
fun qualityOf(result: LyricsResult?): Int = when (result) {
    is LyricsResult.Synced -> {
        if (result.lines.any { it.isRichSync }) 3 else 2
    }
    is LyricsResult.Plain -> 1
    else -> 0
}

data class LyricsCandidate(
    val source: String,
    val lineCount: Int,
    val firstLineText: String,
    val isSynced: Boolean,
    val result: LyricsResult
) : java.io.Serializable

/** Compute word-level progress for karaoke highlighting. */
fun computeWordProgress(
    words: List<WordTiming>,
    positionMs: Long
): Pair<Int, Float> {
    if (words.isEmpty()) return 0 to 0f
    val idx = words.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val word = words[idx]
    val wordDuration = (word.endMs - word.startMs).coerceAtLeast(1)
    val elapsed = (positionMs - word.startMs).coerceIn(0, wordDuration)
    val fraction = elapsed.toFloat() / wordDuration
    return idx to fraction.coerceIn(0f, 1f)
}

object LyricsHelper {
    private val gson = Gson()
    
    private val http = OkHttpClient.Builder().apply {
        connectTimeout(5, TimeUnit.SECONDS)
        readTimeout(8, TimeUnit.SECONDS)
    }.build()

    // List of common Indian record labels and YouTube distribution channel keywords
    private val INDIAN_RECORD_LABELS = listOf(
        "t-series", "t series", "speed records", "yrf", "yash raj", "sony music", 
        "zee music", "aditya music", "lahari music", "tips", "saregama", 
        "geet mp3", "jass records", "white hill", "saga hits", "desi music factory", 
        "vyrl", "t-series apap", "t-series regional", "tseries", "zeemusic", 
        "sonymusic", "tips official"
    )

    private val blacklistedProviders = ConcurrentHashMap<String, Long>()
    private const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    private fun isBlacklisted(provider: String): Boolean {
        val lastFailed = blacklistedProviders[provider] ?: return false
        if (System.currentTimeMillis() - lastFailed > COOLDOWN_MS) {
            blacklistedProviders.remove(provider)
            return false
        }
        return true
    }

    private fun markFailed(provider: String) {
        blacklistedProviders[provider] = System.currentTimeMillis()
    }

    fun fetch(title: String, artist: String, videoId: String = "", provider: String = "Auto", durationMs: Long = 0L): LyricsResult {
        var t = cleanTitle(title)
        var a = cleanArtist(artist)

        // Fallback: If artist is empty or matched as a record label, try to extract the real singer from the title
        if (a.isEmpty() && (title.contains("-") || title.contains("|") || title.contains(":"))) {
            val separator = when {
                title.contains("-") -> "-"
                title.contains("|") -> "|"
                else -> ":"
            }
            val parts = title.split(separator)
            if (parts.size >= 2) {
                val firstPart = parts[0].trim()
                val secondPart = parts[1].trim()

                // Check if the first part is a potential artist (not a label)
                val cleanFirst = cleanArtist(firstPart)
                if (cleanFirst.isNotEmpty()) {
                    a = cleanFirst
                    t = cleanTitle(secondPart)
                } else {
                    // Try the second part
                    val cleanSecond = cleanArtist(secondPart)
                    if (cleanSecond.isNotEmpty()) {
                        a = cleanSecond
                        t = cleanTitle(firstPart)
                    }
                }
            }
        }

        if (provider != "Auto") {
            try {
                when (provider) {
                    "Unison" -> { tryUnison(videoId, title, artist, durationMs)?.let { return it } }
                    "YouTube Music" -> { tryYouTubeMusic(videoId)?.let { return it } }
                    "LrcLib" -> {
                        tryLrcLibGet(t, a, durationMs)?.let { return it }
                        tryLrcLibSearch(t, a, durationMs)?.let { return it }
                    }
                    "Genius" -> { tryGenius(t, a)?.let { return it } }
                }
            } catch (e: Exception) {
                // If specific provider requested and fails, just ignore and return NotFound
            }
            return LyricsResult.NotFound
        }

        // Auto: quality-tier based provider waterfall.
        //
        // Quality tiers (higher = better):
        //   Tier 3 — RichSync (word-level): Unison(richsync) → LrcLib(synced+words) → YTM
        //   Tier 2 — LineSync (line-level): Unison(linesync) → LrcLib(synced) → YTM
        //   Tier 1 — Plain:                 Unison(plain) → Genius
        //
        // We run ALL providers and pick the best quality result.
        // Provider order is the tiebreaker within the same tier.
        val providers: List<Pair<String, () -> LyricsResult?>> = listOf(
            "Unison"        to { tryUnison(videoId, title, artist, durationMs) },
            "LrcLib"        to { tryLrcLibGet(t, a, durationMs) ?: tryLrcLibSearch(t, a, durationMs) },
            "YouTube Music" to { tryYouTubeMusic(videoId) },
            "Genius"        to { tryGenius(t, a) }
        )

        var bestSynced: LyricsResult.Synced? = null
        var fallbackPlain: LyricsResult.Plain? = null
        for ((name, provider) in providers) {
            if (isBlacklisted(name)) continue
            val res = try {
                provider()
            } catch (e: Exception) {
                android.util.Log.e("LyricsHelper", "Provider $name failed: ${e.message}")
                if (e is javax.net.ssl.SSLException) markFailed(name)
                null
            }
            when (res) {
                is LyricsResult.Synced -> {
                    // Keep best quality: richsync > linesync, first-wins as tiebreaker
                    val isNewRichSync = res.lines.any { it.isRichSync }
                    val currentIsRichSync = bestSynced?.lines?.any { it.isRichSync } == true
                    if (bestSynced == null || (isNewRichSync && !currentIsRichSync)) {
                        bestSynced = res
                        android.util.Log.d("LyricsHelper", "Better synced candidate via $name (richSync=$isNewRichSync)")
                    }
                }
                is LyricsResult.Plain -> {
                    android.util.Log.d("LyricsHelper", "Plain lyrics found via $name — keeping as fallback")
                    if (fallbackPlain == null) fallbackPlain = res
                }
                else -> { /* NotFound or null — try next provider */ }
            }
        }
        return bestSynced ?: fallbackPlain ?: LyricsResult.NotFound
    }

    private fun tryUnison(videoId: String, title: String, artist: String, durationMs: Long): LyricsResult? {
        val exactArtist = artist.replace(" - Topic", "", ignoreCase = true).trim()
        val cleanedTitle = cleanTitle(title).ifBlank { title.trim() }
        val cleanedArtist = cleanArtist(artist).ifBlank { exactArtist }
        return UnisonClient.fetch(
            videoId = videoId,
            title = cleanedTitle,
            artist = cleanedArtist,
            durationMs = durationMs
        )
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?]|\\(.*?\\)"), "") // Remove parentheses and brackets content
            // Strip featured artists and everything after them. The word anchors matter:
            // the old unbounded `feat\.?.*|ft\.?.*` alternation matched "ft" inside
            // ordinary words, truncating titles like "Gift of Love" → "Gi".
            .replace(Regex("(?i)\\s*\\b(?:feat(?:uring)?|ft)\\.?(?:\\s+.*|$)"), "")
            .replace(Regex("(?i)\\b(?:official|music videos?|lyrical|videos?|audios?|lyrics?|hd|4k|full songs?|full videos?)\\b"), "")
            .replace(Regex("(?i)\\b(?:latest|new|punjabi|hindi)\\s+songs?\\b.*"), "")
            .replace(" - Topic", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        var clean = artist.replace(" - Topic", "", ignoreCase = true).trim()

        val lower = clean.lowercase()
        if (INDIAN_RECORD_LABELS.any { lower == it || lower.contains(it) }) {
            return ""
        }

        val parts = clean.split(Regex(",|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\band\\b", RegexOption.IGNORE_CASE))
        if (parts.isNotEmpty()) {
            clean = parts[0].trim()
        }

        val cleanLower = clean.lowercase()
        if (INDIAN_RECORD_LABELS.any { cleanLower == it || cleanLower.contains(it) }) {
            return ""
        }

        return clean
    }

    // ── LrcLib direct GET ──────────────────────────────────────────────────────
    private fun tryLrcLibGet(title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        if (artist.isEmpty()) return null
        val durationParam = if (durationMs > 0L) "&duration=${durationMs / 1000L}" else ""
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}$durationParam"
        val resp = get(url) ?: return null
        return parseLrcLibItem(resp, "LrcLib")
    }

    private fun tryLrcLibSearch(title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        val urls = mutableListOf<String>()
        if (artist.isNotEmpty()) {
            urls.add("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}")
            urls.add("https://lrclib.net/api/search?q=${enc("$title $artist".trim())}")
        }
        urls.add("https://lrclib.net/api/search?q=${enc(title)}")

        for (url in urls) {
            val resp = get(url) ?: continue
            try {
                val arr = gson.fromJson(resp, List::class.java) ?: continue
                if (arr.isEmpty()) continue
                // Pre-regression behavior: take the FIRST relevant result.
                // Do NOT rank by duration or by syncedLyrics length — that
                // picked shifted/community LRC variants and caused drift.
                val best = arr
                    .mapNotNull { item ->
                        val itemMap = item as? Map<*, *> ?: return@mapNotNull null
                        val parsed = parseLrcLibItem(gson.toJson(itemMap), "LrcLib") ?: return@mapNotNull null
                        val score = scoreLrcLibCandidate(itemMap, title, artist, durationMs)
                        Triple(score, parsed is LyricsResult.Synced, parsed)
                    }
                    .filter { it.first >= 0.38 }
                    .maxWithOrNull(
                        compareBy<Triple<Double, Boolean, LyricsResult?>> { it.second }
                            .thenBy { it.first }
                    )
                best?.third?.let { return it }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun scoreLrcLibCandidate(item: Map<*, *>, title: String, artist: String, durationMs: Long): Double {
        val candidateTitle = (item["trackName"] as? String).orEmpty()
        val candidateArtist = (item["artistName"] as? String).orEmpty()
        val candidateDurationMs = ((item["duration"] as? Number)?.toLong() ?: 0L) * 1000L

        val titleScore = lyricTextSimilarity(title, candidateTitle)
        val artistScore = if (artist.isBlank() || candidateArtist.isBlank()) {
            0.35
        } else {
            lyricTextSimilarity(artist, candidateArtist)
        }
        val durationScore = if (durationMs > 0L && candidateDurationMs > 0L) {
            val diff = kotlin.math.abs(durationMs - candidateDurationMs)
            when {
                diff <= 2_500L -> 1.0
                diff <= 7_500L -> 0.75
                diff <= 15_000L -> 0.45
                else -> 0.0
            }
        } else {
            0.35
        }
        val syncedBonus = if (!(item["syncedLyrics"] as? String).isNullOrBlank()) 0.12 else 0.0
        return (titleScore * 0.48) + (artistScore * 0.28) + (durationScore * 0.24) + syncedBonus
    }

    private fun lyricTextSimilarity(left: String, right: String): Double {
        val a = normalizeForLyricsMatch(left)
        val b = normalizeForLyricsMatch(right)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.82
        val aTokens = a.split(" ").filter { it.length > 1 }.toSet()
        val bTokens = b.split(" ").filter { it.length > 1 }.toSet()
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        return aTokens.intersect(bTokens).size.toDouble() / aTokens.union(bTokens).size.toDouble()
    }

    private fun normalizeForLyricsMatch(text: String): String {
        return cleanTitle(text)
            .lowercase()
            .replace(Regex("""\b(feat|ft|with)\b.*"""), " ")
            .replace(Regex("""[^a-z0-9\u0900-\u097F\u0A00-\u0A7F\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun parseLrcLibItem(json: String, source: String): LyricsResult? {
        return try {
            val item = gson.fromJson(json, Map::class.java) ?: return null
            val lrc   = item["syncedLyrics"] as? String
            val plain = item["plainLyrics"] as? String
            when {
                !lrc.isNullOrBlank() -> {
                    val lines = parseLrc(lrc)
                    if (lines.isNotEmpty()) LyricsResult.Synced(lines, source) else null
                }
                !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
                    .takeIf { it.isNotBlank() }
                    ?.let { LyricsResult.Plain(it, source) }
                else                   -> null
            }
        } catch (_: Exception) { null }
    }

    // ── YouTube Music ──────────────────────────────────────────────────────────
    // #1 priority: returns perfectly-synced lyrics from the SAME source as the
    // playing song (Musixmatch-backed, officially licensed). Uses the videoId so
    // there's no fuzzy title/artist matching and zero drift.
    private fun tryYouTubeMusic(videoId: String): LyricsResult? {
        if (videoId.isBlank()) return null
        return try {
            val browseId = com.vinmusic.innertube.InnerTube.getLyricsBrowseId(videoId) ?: return null
            val (synced, plain) = com.vinmusic.innertube.InnerTube.getLyrics(browseId)
            when {
                !synced.isNullOrBlank() -> {
                    val lines = parseLrc(synced)
                    if (lines.isNotEmpty()) LyricsResult.Synced(lines, "YouTube Music") else null
                }
                !plain.isNullOrBlank() -> sanitizePlainLyrics(plain)
                    .takeIf { it.isNotBlank() }
                    ?.let { LyricsResult.Plain(it, "YouTube Music") }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    // ── LRC parser ─────────────────────────────────────────────────────────────
    fun parseLrc(lrc: String): List<LyricsLine> {
        val timestampRegex = Regex("""\[(\d{1,2}):(\d{2})[\.:](\d{1,3})]""")
        val wordRegex = Regex("""<(\d{1,2}):(\d{2})[\.:](\d{1,3})>([^<]*)""")
        return lrc.lines()
            .flatMap { rawLine ->
                val line = rawLine.trim()
                val matches = timestampRegex.findAll(line).toList()
                if (matches.isEmpty()) return@flatMap emptyList()
                val wordMatches = wordRegex.findAll(line).toList()
                val text = line
                    .replace(timestampRegex, "")
                    .replace(wordRegex) { it.groupValues[4] }
                    .trim()
                if (text.isEmpty() || isNonLyricLine(text)) return@flatMap emptyList()
                matches.map { m ->
                    val ms = m.groupValues[1].toLong() * 60_000 +
                            m.groupValues[2].toLong() * 1_000 +
                            m.groupValues[3].padEnd(3, '0').take(3).toLong()
                    val words = wordMatches.mapIndexedNotNull { index, wordMatch ->
                        val rawWord = wordMatch.groupValues[4]
                        val wordText = rawWord.trim()
                        if (wordText.isBlank()) return@mapIndexedNotNull null
                        val startMs = wordMatch.groupValues[1].toLong() * 60_000 +
                                wordMatch.groupValues[2].toLong() * 1_000 +
                                wordMatch.groupValues[3].padEnd(3, '0').take(3).toLong()
                        val next = wordMatches.getOrNull(index + 1)
                        val endMs = next?.let {
                            it.groupValues[1].toLong() * 60_000 +
                                    it.groupValues[2].toLong() * 1_000 +
                                    it.groupValues[3].padEnd(3, '0').take(3).toLong()
                        } ?: 0L
                        WordTiming(
                            text = wordText,
                            startMs = startMs,
                            endMs = endMs,
                            hasTrailingSpace = rawWord.lastOrNull()?.isWhitespace() ?: true
                        )
                    }
                    LyricsLine(
                        timeMs = ms,
                        text = text,
                        endTimeMs = words.lastOrNull()?.endMs?.takeIf { it > ms } ?: 0L,
                        words = words.takeIf { it.isNotEmpty() }
                    )
                }
            }
            .distinctBy { "${it.timeMs}|${it.text}" }
            .sortedBy { it.timeMs }
    }

    private fun sanitizePlainLyrics(text: String): String {
        return text
            .replace("\u00A0", " ")
            .lines()
            .map { it.trim() }
            .filter { line -> line.isEmpty() || !isNonLyricLine(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun isNonLyricLinePublic(raw: String): Boolean = isNonLyricLine(raw)

    private fun isNonLyricLine(raw: String): Boolean {
        val text = raw.trim().trim('[', ']', '(', ')', '{', '}').trim()
        if (text.isBlank()) return false
        val lower = text.lowercase()

        if (Regex("""^\d+\s+contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^\d+\s*(embed|translations?)$""", RegexOption.IGNORE_CASE).matches(text)) return true

        val sectionLine = Regex(
            """^(intro|outro|verse|chorus|pre[-\s]?chorus|post[-\s]?chorus|bridge|hook|refrain|interlude|instrumental|drop|break|spoken|sample|skit|part|segue)(\s+\d+|\s+[ivx]+)?(\s*[:.-].*)?$""",
            RegexOption.IGNORE_CASE
        )
        if (sectionLine.matches(text)) return true

        val junkPhrases = listOf(
            "you might also like",
            "embed",
            "read more",
            "see live",
            "get tickets",
            "track info",
            "produced by",
            "written by",
            "release date",
            "translations",
            "lyrics",
            "album",
            "contributors"
        )
        if (junkPhrases.any { lower == it || (text.length < 42 && lower.startsWith(it)) }) return true

        val languages = setOf(
            "english", "hindi", "punjabi", "urdu", "spanish", "espanol", "francais",
            "portugues", "deutsch", "italiano", "turkce", "japanese", "russian",
            "korean", "chinese", "arabic", "romanization", "translation"
        )
        if (text.length < 24 && lower in languages) return true

        return false
    }

    fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.0 (https://github.com/vinmusic)")
            .build()
        // Close the response on every path — a bare return-null on non-2xx leaked
        // the connection, and 404s are the *normal* path when a lyric isn't found.
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun transliterateToHinglish(text: String, lang: String = "pa"): String {
        var detectedLang = lang
        if (lang == "pa") {
            when {
                text.any { it in '\u0A00'..'\u0A7F' } -> detectedLang = "pa"
                text.any { it in '\u0900'..'\u097F' } -> detectedLang = "hi"
                else -> return text
            }
        } else {
            if (!text.any { it in '\u0A00'..'\u0A7F' || it in '\u0900'..'\u097F' }) return text
        }
        
        try {
            val body = okhttp3.FormBody.Builder().add("q", text).build()
            val request = Request.Builder()
                .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$detectedLang&tl=en&dt=rm")
                .post(body)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            
            val response = http.newCall(request).execute().use { it.body?.string() } ?: return text
            val jsonArray = gson.fromJson(response, List::class.java)
            if (jsonArray.isNullOrEmpty()) return text
            val firstOuter = jsonArray.firstOrNull() as? List<*> ?: return text
            val sb = java.lang.StringBuilder()
            for (item in firstOuter) {
                val chunk = item as? List<*> ?: continue
                if (chunk.size > 3) {
                    val romanized = chunk[3] as? String
                    if (romanized != null) {
                        sb.append(romanized)
                    }
                }
            }
            if (sb.isEmpty()) return text
            
            return sb.toString().replace("ā", "a").replace("ī", "i").replace("ū", "u")
                .replace("ḍ", "d").replace("ṭ", "t").replace("ṇ", "n")
                .replace("ś", "sh").replace("ṣ", "sh").replace("ṛ", "r")
                .replace("ṃ", "n").replace("ḥ", "h").replace("ñ", "n")
        } catch (e: Exception) {
            return text
        }
    }

    private fun tryGenius(title: String, artist: String): LyricsResult? {
        val q = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "https://genius.com/api/search/multi?q=${enc(q)}"
        val resp = get(searchUrl) ?: return null

        try {
            val json = gson.fromJson(resp, Map::class.java) ?: return null
            val responseMap = json["response"] as? Map<*, *> ?: return null
            val sections = responseMap["sections"] as? List<*> ?: return null
            var songUrl: String? = null

            for (sec in sections) {
                val secMap = sec as? Map<*, *> ?: continue
                if (secMap["type"] == "song") {
                    val hits = secMap["hits"] as? List<*> ?: continue
                    if (hits.isNotEmpty()) {
                        val hit = hits[0] as? Map<*, *> ?: continue
                        val result = hit["result"] as? Map<*, *> ?: continue
                        songUrl = result["url"] as? String
                        break
                    }
                }
            }

            if (songUrl.isNullOrBlank()) return null

            val html = get(songUrl) ?: return null
            val doc = org.jsoup.Jsoup.parse(html)

            val containers = doc.select("div[data-lyrics-container=true]")
            val lyricsText = if (containers.isEmpty()) {
                val legacy = doc.select(".lyrics")
                if (legacy.isNotEmpty()) getPlainText(legacy[0]) else ""
            } else {
                containers.joinToString("\n\n") { getPlainText(it) }
            }

            if (lyricsText.isNotBlank()) {
                val cleanedLines = mutableListOf<String>()
                val lines = lyricsText.lines()
                val langKeywords = setOf(
                    "translations", "türkçe", "日本語", "français", "português", "español", 
                    "svenska", "русский", "italiano", "deutsch", "english", "polski", 
                    "tiếng việt", "română", "nederlands", "עברית", "العربية", "فارسی", 
                    "한국어", "中文", "українська", "ελληνικά", "magyar", "suomi", 
                    "norsk", "dansk", "català", "hrvatski", "bahasa indonesia", "bahasa melayu",
                    "japanese", "russian"
                )
                
                for (i in lines.indices) {
                    val line = lines[i]
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        cleanedLines.add(line)
                        continue
                    }
                    
                    val lower = trimmed.lowercase()
                    val isJunk = lower.startsWith("translations") || 
                                 langKeywords.any { lower == it || (trimmed.length < 30 && (lower.contains(it) || lower.startsWith(it))) } || 
                                 trimmed.endsWith(" lyrics", ignoreCase = true) ||
                                 (trimmed.contains("lyrics", ignoreCase = true) && i < 10) ||
                                 trimmed.contains("read more", ignoreCase = true) ||
                                 trimmed.contains("credits to", ignoreCase = true) ||
                                 trimmed.contains("feeding time of ktt", ignoreCase = true) ||
                                 lower.contains("you might also like")
                    
                    if (i < 35 && isJunk) {
                        continue
                    }
                    cleanedLines.add(line)
                }
                
                val cleanedLyrics = sanitizePlainLyrics(cleanedLines.joinToString("\n"))
                if (cleanedLyrics.isNotBlank()) {
                    return LyricsResult.Plain(cleanedLyrics, "Genius")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LyricsHelper", "Genius failed: ${e.message}")
        }
        return null
    }

    private fun getPlainText(element: org.jsoup.nodes.Node): String {
        val sb = StringBuilder()
        fun traverse(node: org.jsoup.nodes.Node) {
            if (node is org.jsoup.nodes.TextNode) {
                sb.append(node.text())
            } else if (node is org.jsoup.nodes.Element) {
                if (node.tagName() == "br") {
                    sb.append("\n")
                    return
                }
                val isBlock = node.isBlock
                if (isBlock && sb.isNotEmpty() && !sb.endsWith("\n")) {
                    sb.append("\n")
                }
                for (child in node.childNodes()) {
                    traverse(child)
                }
                if (isBlock && !sb.endsWith("\n")) {
                    sb.append("\n")
                }
            }
        }
        traverse(element)
        return sb.toString().trim()
    }

    /** Fetch lyrics candidates from all providers in parallel. Returns deduplicated, sorted list. */
    suspend fun fetchCandidates(
        title: String,
        artist: String,
        videoId: String = "",
        durationMs: Long = 0L
    ): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val providers = listOf(
            "Unison" to suspend { tryUnison(videoId, title, artist, durationMs) },
            "LrcLib" to suspend { tryLrcLibGet(title, artist, durationMs) },
            "YouTube Music" to suspend { if (videoId.isNotEmpty()) tryYouTubeMusic(videoId) else null },
            "Genius" to suspend { tryGenius(title, artist) }
        )

        val results = providers.map { (name, fetcher) ->
            async {
                try {
                    val result = fetcher()
                    if (result != null && result !is LyricsResult.NotFound) {
                        val lines = when (result) {
                            is LyricsResult.Synced -> result.lines.size
                            is LyricsResult.Plain -> result.text.lines().size
                            else -> 0
                        }
                        val firstLine = when (result) {
                            is LyricsResult.Synced -> result.lines.firstOrNull()?.text ?: ""
                            is LyricsResult.Plain -> result.text.lines().firstOrNull() ?: ""
                            else -> ""
                        }
                        LyricsCandidate(
                            source = name,
                            lineCount = lines,
                            firstLineText = firstLine,
                            isSynced = result is LyricsResult.Synced,
                            result = result
                        )
                    } else null
                } catch (e: Exception) {
                    android.util.Log.e("LyricsHelper", "Candidate fetch failed for $name: ${e.message}")
                    null
                }
            }
        }

        results.awaitAll()
            .filterNotNull()
            .distinctBy { it.source }
            .sortedByDescending { qualityOf(it.result) }
    }
}
