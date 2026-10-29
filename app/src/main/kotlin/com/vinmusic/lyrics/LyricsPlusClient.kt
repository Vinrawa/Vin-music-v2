package com.vinmusic.lyrics

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Client for the LyricsPlus engine (lyricsplus.binimum.org).
 *
 * Provides real-time word-by-word (syllable-level) karaoke sync
 * powered by Apple Music Sing TTML and QQ Music QRC databases.
 *
 * Response format:
 *   {
 *     "type": "Word" | "Line",
 *     "lyrics": [
 *       {
 *         "time": 9845,
 *         "duration": 2260,
 *         "text": "...",
 *         "syllabus": [ { "time": 9845, "duration": 211, "text": "Word " } ]
 *       }
 *     ]
 *   }
 */
object LyricsPlusClient {
    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, LyricsResult>()

    @Volatile private var disabledUntilMs: Long = 0L

    private val http = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .build()

    private const val BASE_HOST = "lyricsplus.binimum.org"

    fun isAvailable(): Boolean = System.currentTimeMillis() >= disabledUntilMs

    /**
     * Fetch word-by-word or line-synced lyrics for a song.
     */
    fun fetch(title: String, artist: String, durationMs: Long = 0L): LyricsResult? {
        if (!isAvailable()) {
            android.util.Log.w("LyricsPlusClient", "Temporarily cooldown until ${disabledUntilMs}")
            return null
        }

        val cacheKey = "${title.trim().lowercase()}|${artist.trim().lowercase()}"
        memoryCache[cacheKey]?.let { return it }

        if (title.isBlank()) return null

        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()

        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host(BASE_HOST)
            .addPathSegments("v2/lyrics/get")
            .addQueryParameter("title", cleanTitle)

        if (cleanArtist.isNotBlank()) {
            urlBuilder.addQueryParameter("artist", cleanArtist)
        }

        if (durationMs > 0L) {
            urlBuilder.addQueryParameter("duration", (durationMs / 1000L).toString())
        }

        val url = urlBuilder.build()
        android.util.Log.i("LyricsPlusClient", "Querying lyrics for '$cleanTitle' by '$cleanArtist' (duration=${durationMs/1000}s)")
        val body = executeRequest(url) ?: return null

        val result = parseResponse(body)
        if (result != null) {
            memoryCache[cacheKey] = result
        }
        return result
    }

    private fun executeRequest(url: HttpUrl): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.0 (Linux; Android)")
            .header("Accept", "application/json")
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    android.util.Log.w("LyricsPlusClient", "Rate limited (${response.code}), cooldown 2m")
                    disabledUntilMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2)
                    return null
                }
                if (!response.isSuccessful) {
                    android.util.Log.d("LyricsPlusClient", "HTTP ${response.code} for ${url.encodedPath}")
                    return null
                }
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            android.util.Log.e("LyricsPlusClient", "Network error: ${e.message}")
            null
        }
    }

    private fun parseResponse(body: String): LyricsResult? {
        return try {
            val root = gson.fromJson(body, JsonObject::class.java) ?: return null
            if (root.has("error")) {
                android.util.Log.d("LyricsPlusClient", "API returned error: ${root.get("error")}")
                return null
            }

            val type = root.get("type")?.asString ?: "Line"
            val lyricsArray = root.getAsJsonArray("lyrics") ?: return null
            if (lyricsArray.size() == 0) return null

            val lines = mutableListOf<LyricsLine>()

            for (elem in lyricsArray) {
                val item = elem.asJsonObject ?: continue
                val lineTime = item.get("time")?.asLong ?: 0L
                val lineDuration = item.get("duration")?.asLong ?: 0L
                val lineText = item.get("text")?.asString?.trim() ?: ""

                if (lineText.isBlank() || LyricsHelper.isNonLyricLinePublic(lineText)) continue

                val syllabusArray = item.getAsJsonArray("syllabus")
                val words: List<WordTiming>? = if (syllabusArray != null && syllabusArray.size() > 0) {
                    val wordList = mutableListOf<WordTiming>()
                    for (sylElem in syllabusArray) {
                        val syl = sylElem.asJsonObject ?: continue
                        val sylTime = syl.get("time")?.asLong ?: lineTime
                        val sylDuration = syl.get("duration")?.asLong ?: 0L
                        val rawText = syl.get("text")?.asString ?: ""
                        val sylText = rawText.trim()
                        if (sylText.isNotEmpty()) {
                            wordList.add(
                                WordTiming(
                                    text = sylText,
                                    startMs = sylTime,
                                    endMs = sylTime + sylDuration,
                                    hasTrailingSpace = rawText.endsWith(" ") || rawText.lastOrNull()?.isWhitespace() == true
                                )
                            )
                        }
                    }
                    wordList.takeIf { it.isNotEmpty() }
                } else null

                val isRich = (type.equals("Word", ignoreCase = true) || (words != null && words.any { it.endMs > 0 }))
                lines.add(
                    LyricsLine(
                        timeMs = lineTime,
                        text = lineText,
                        endTimeMs = lineTime + lineDuration,
                        words = words,
                        isRichSync = isRich
                    )
                )
            }

            if (lines.isEmpty()) return null

            lines.sortBy { it.timeMs }
            android.util.Log.i("LyricsPlusClient", "Successfully parsed ${lines.size} lines (richSync=${lines.any { it.isRichSync }})")
            LyricsResult.Synced(lines, "LyricsPlus")
        } catch (e: Exception) {
            android.util.Log.e("LyricsPlusClient", "Failed to parse JSON: ${e.message}")
            null
        }
    }
}
