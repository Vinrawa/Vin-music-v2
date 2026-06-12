package com.vinmusic.lyrics

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class LyricsLine(val timeMs: Long, val text: String)

sealed class LyricsResult {
    data class Synced(val lines: List<LyricsLine>, val source: String) : LyricsResult()
    data class Plain(val text: String, val source: String) : LyricsResult()
    object NotFound : LyricsResult()
}

object LyricsHelper {
    private val gson = Gson()
    
    private val http = OkHttpClient.Builder().apply {
        if (com.vinmusic.BuildConfig.DEBUG) {
            try {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                )
                val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {}
        }
        connectTimeout(4, TimeUnit.SECONDS)
        readTimeout(4, TimeUnit.SECONDS)
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

    fun fetch(title: String, artist: String, videoId: String = "", provider: String = "Auto"): LyricsResult {
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
                    "LrcLib" -> {
                        tryLrcLibGet(t, a)?.let { return it }
                        tryLrcLibSearch(t, a)?.let { return it }
                    }
                    "SimpMusic" -> { trySimpMusic(t, a)?.let { return it } }
                    "Paxsenix" -> { tryPaxsenix(t, a)?.let { return it } }
                    "KuGou" -> { tryKugou(t, a)?.let { return it } }
                }
            } catch (e: Exception) {
                // If specific provider requested and fails, just ignore and return NotFound
            }
            return LyricsResult.NotFound
        }

        // Auto: Race all providers in parallel and return the first successful result instantly
        return kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(9000L) {
                val channel = kotlinx.coroutines.channels.Channel<LyricsResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val remaining = java.util.concurrent.atomic.AtomicInteger(5) // 5 tasks: LrcLibGet, LrcLibSearch, KuGou, Paxsenix, SimpMusic

                fun submit(name: String, delayMs: Long = 0L, block: () -> LyricsResult?) {
                    if (isBlacklisted(name)) {
                        if (remaining.decrementAndGet() == 0) channel.close()
                        return
                    }
                    launch {
                        try {
                            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
                            val res = block()
                            if (res != null) {
                                channel.trySend(res)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("LyricsHelper", "Provider $name failed: ${e.message}")
                            if (e is javax.net.ssl.SSLException) {
                                markFailed(name)
                            }
                        } finally {
                            if (remaining.decrementAndGet() == 0) {
                                channel.close()
                            }
                        }
                    }
                }

                submit("LrcLib", 0L) { tryLrcLibGet(t, a) }
                submit("LrcLibSearch", 0L) { tryLrcLibSearch(t, a) }
                submit("SimpMusic", 1500L) { trySimpMusic(t, a) }
                submit("KuGou", 2500L) { tryKugou(t, a) }
                submit("Paxsenix", 3500L) { tryPaxsenix(t, a) }

                try {
                    for (res in channel) {
                        coroutineContext.cancelChildren()
                        return@withTimeoutOrNull res
                    }
                    LyricsResult.NotFound
                } catch (e: Exception) {
                    LyricsResult.NotFound
                }
            } ?: LyricsResult.NotFound
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?]|\\(.*?\\)"), "") // Remove parentheses and brackets content
            .replace(Regex("(?i)official|music video|lyrical|video|audio|lyrics?|hd|4k|feat\\.?.*|ft\\.?.*|full song|full video|latest song.*|new song.*|punjabi song.*|hindi song.*"), "")
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
    private fun tryLrcLibGet(title: String, artist: String): LyricsResult? {
        if (artist.isEmpty()) return null
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        val resp = get(url) ?: return null
        return parseLrcLibItem(resp, "LrcLib")
    }

    private fun tryLrcLibSearch(title: String, artist: String): LyricsResult? {
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
                val firstItem = gson.toJson(arr[0])
                parseLrcLibItem(firstItem, "LrcLib")?.let { return it }
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun parseLrcLibItem(json: String, source: String): LyricsResult? {
        return try {
            val item = gson.fromJson(json, Map::class.java) ?: return null
            val lrc   = item["syncedLyrics"] as? String
            val plain = item["plainLyrics"] as? String
            when {
                !lrc.isNullOrBlank()   -> LyricsResult.Synced(parseLrc(lrc), source)
                !plain.isNullOrBlank() -> LyricsResult.Plain(plain, source)
                else                   -> null
            }
        } catch (_: Exception) { null }
    }

    // ── Paxsenix ───────────────────────────────────────────────────────────────
    private fun tryPaxsenix(title: String, artist: String): LyricsResult? {
        val queryUrl = if (artist.isNotEmpty()) {
            "https://paxsenix.skiddle.id/lyrics?title=${enc(title)}&artist=${enc(artist)}"
        } else {
            "https://paxsenix.skiddle.id/lyrics?title=${enc(title)}"
        }
        val resp = get(queryUrl) ?: return null
        return try {
            val json  = gson.fromJson(resp, Map::class.java) ?: return null
            val lrc   = json["syncedLyrics"] as? String
            val plain = json["plainLyrics"] as? String
            when {
                !lrc.isNullOrBlank()   -> LyricsResult.Synced(parseLrc(lrc), "Paxsenix")
                !plain.isNullOrBlank() -> LyricsResult.Plain(plain, "Paxsenix")
                else                   -> null
            }
        } catch (_: Exception) { null }
    }

    // ── LRC parser ─────────────────────────────────────────────────────────────
    fun parseLrc(lrc: String): List<LyricsLine> {
        val re = Regex("""^\[(\d{2}):(\d{2})[\.\:](\d{2,3})\](.*)""")
        return lrc.lines()
            .mapNotNull { re.matchEntire(it.trim()) }
            .map { m ->
                val ms = m.groupValues[1].toLong() * 60_000 +
                         m.groupValues[2].toLong() * 1_000 +
                         m.groupValues[3].padEnd(3,'0').take(3).toLong()
                LyricsLine(ms, m.groupValues[4].trim())
            }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.timeMs }
    }

    fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.0 (https://github.com/vinmusic)")
            .build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) return null
        return response.use { it.body?.string() }
    }

    // SimpMusic ───────────────────────────────────────────────────────────────
    private fun trySimpMusic(title: String, artist: String): LyricsResult? {
        val q = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "https://lyrics.simpmusic.org/api/v1/search?q=${enc(q)}"
        val resp = get(searchUrl) ?: return null
        try {
            val list = gson.fromJson(resp, List::class.java) ?: return null
            if (list.isEmpty()) return null
            val first = list[0] as? Map<*, *> ?: return null
            val id = first["id"] as? String ?: return null
            
            val lyricsUrl = "https://lyrics.simpmusic.org/api/v1/lyrics/$id"
            val lyricsResp = get(lyricsUrl) ?: return null
            val lyricsData = gson.fromJson(lyricsResp, Map::class.java) ?: return null
            
            val lrc = lyricsData["synced"] as? String
            val plain = lyricsData["plain"] as? String
            
            when {
                !lrc.isNullOrBlank() -> return LyricsResult.Synced(parseLrc(lrc), "SimpMusic")
                !plain.isNullOrBlank() -> return LyricsResult.Plain(plain, "SimpMusic")
            }
        } catch (_: Exception) {}
        return null
    }

    // ── KuGou Music Scraper ────────────────────────────────────────────────────
    private fun tryKugou(title: String, artist: String): LyricsResult? {
        val query = if (artist.isNotEmpty()) "$title $artist".trim() else title
        val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${enc(query)}"
        val searchResp = get(searchUrl) ?: return null
        return try {
            val searchJson = gson.fromJson(searchResp, Map::class.java) ?: return null
            val candidates = searchJson["candidates"] as? List<*> ?: return null
            if (candidates.isEmpty()) return null

            val firstCand = candidates[0] as? Map<*, *> ?: return null
            val id = (firstCand["id"] as? Number)?.toLong() ?: return null
            val accesskey = firstCand["accesskey"] as? String ?: return null

            val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val downloadResp = get(downloadUrl) ?: return null
            val downloadJson = gson.fromJson(downloadResp, Map::class.java) ?: return null
            val base64Content = downloadJson["content"] as? String ?: return null
            if (base64Content.isBlank()) return null

            val decodedBytes = android.util.Base64.decode(base64Content, android.util.Base64.DEFAULT)
            val lrcString = String(decodedBytes, Charsets.UTF_8)

            if (lrcString.isNotBlank()) {
                val lines = parseLrc(lrcString)
                if (lines.isNotEmpty()) {
                    LyricsResult.Synced(lines, "KuGou")
                } else {
                    LyricsResult.Plain(lrcString, "KuGou")
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
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
}
