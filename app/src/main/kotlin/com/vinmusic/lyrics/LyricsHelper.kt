package com.vinmusic.lyrics

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class LyricsLine(val timeMs: Long, val text: String)

sealed class LyricsResult {
    data class Synced(val lines: List<LyricsLine>, val source: String) : LyricsResult()
    data class Plain(val text: String, val source: String) : LyricsResult()
    object NotFound : LyricsResult()
}

object LyricsHelper {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetch(title: String, artist: String, videoId: String = "", provider: String = "Auto"): LyricsResult {
        val t = clean(title)
        val a = clean(artist)

        when (provider) {
            "LrcLib" -> {
                tryLrcLibGet(t, a)?.let { return it }
                tryLrcLibSearch(t, a)?.let { return it }
            }
            "Paxsenix" -> {
                tryPaxsenix(t, a)?.let { return it }
            }
            "KuGou" -> {
                tryKugou(t, a)?.let { return it }
            }
            "SimpMusic" -> {
                trySimpMusic(videoId)?.let { return it }
            }
            else -> {
                // Auto: SimpMusic (precise videoId match) -> LrcLib -> KuGou -> Paxsenix
                trySimpMusic(videoId)?.let { return it }
                tryLrcLibGet(t, a)?.let { return it }
                tryLrcLibSearch(t, a)?.let { return it }
                tryKugou(t, a)?.let { return it }
                tryPaxsenix(t, a)?.let { return it }
            }
        }

        return LyricsResult.NotFound
    }

    // ── LrcLib direct GET ──────────────────────────────────────────────────────
    private fun tryLrcLibGet(title: String, artist: String): LyricsResult? {
        val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}"
        return parseLrcLibItem(get(url) ?: return null, "LrcLib")
    }

    private fun tryLrcLibSearch(title: String, artist: String): LyricsResult? {
        // Try with artist first, then without
        for (url in listOf(
            "https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}",
            "https://lrclib.net/api/search?q=${enc("$title $artist".trim())}"
        )) {
            val resp = get(url) ?: continue
            return try {
                val arr = gson.fromJson(resp, List::class.java) ?: continue
                if (arr.isEmpty()) continue
                parseLrcLibItem(gson.toJson(arr[0]), "LrcLib") ?: continue
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
        val url  = "https://paxsenix.skiddle.id/lyrics?title=${enc(title)}&artist=${enc(artist)}"
        val resp = get(url) ?: return null
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

    private fun clean(s: String) = s
        .replace(Regex("\\[.*?]|\\(.*?\\)"), "")
        .replace(Regex("(?i)official|music video|audio|lyrics?|hd|4k|feat\\.?.*"), "")
        .replace(" - Topic", "").trim()

    fun get(url: String): String? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent","Mozilla/5.0").build())
            .execute().use { it.body?.string() }
    } catch (_: Exception) { null }

    // ── SimpMusic Lyrics Scraper ───────────────────────────────────────────────
    private fun trySimpMusic(videoId: String): LyricsResult? {
        if (videoId.isBlank()) return null
        val url = "https://lyrics.simpmusic.org/api/v1/$videoId"
        val resp = get(url) ?: return null
        return try {
            val json = gson.fromJson(resp, Map::class.java) ?: return null
            val data = json["data"] as? List<*> ?: return null
            if (data.isEmpty()) return null
            val first = data[0] as? Map<*, *> ?: return null
            val lrc = first["syncedLyrics"] as? String
            val plain = first["plainLyrics"] as? String
            val source = (first["source"] as? String) ?: "SimpMusic"
            when {
                !lrc.isNullOrBlank() -> LyricsResult.Synced(parseLrc(lrc), source)
                !plain.isNullOrBlank() -> LyricsResult.Plain(plain, source)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── KuGou Music Scraper ────────────────────────────────────────────────────
    private fun tryKugou(title: String, artist: String): LyricsResult? {
        val query = "$title $artist".trim()
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

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
