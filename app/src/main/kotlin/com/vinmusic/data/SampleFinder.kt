package com.vinmusic.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.vinmusic.innertube.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SampleItem(
    val originalTitle: String,
    val originalArtist: String,
    val type: String, // "Sample", "Interpolation", "Cover", "Based on"
    val note: String? = null
)

sealed class SampleResult {
    data class Found(val items: List<SampleItem>) : SampleResult()
    object NotFound : SampleResult()
}

object SampleFinder {
    private const val TAG = "SampleFinder"
    private val cache = ConcurrentHashMap<String, SampleResult>()
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun findSamples(song: VideoItem, description: String?): SampleResult = withContext(Dispatchers.IO) {
        val cacheKey = song.videoId.takeIf { it.isNotBlank() } ?: "${song.title}_${song.author}"
        cache[cacheKey]?.let { return@withContext it }

        val foundList = mutableListOf<SampleItem>()

        // Source 1: Check YouTube description (Fastest, zero network, 100% official label text)
        if (!description.isNullOrBlank()) {
            parseDescriptionForSamples(description, foundList)
        }

        // Source 2: Check Genius song relationships (World's #1 crowdsourced sample database: samples, interpolates, covers)
        if (foundList.isEmpty()) {
            try {
                queryGeniusForSamples(song.title, song.author, foundList)
            } catch (e: Exception) {
                Log.d(TAG, "Genius sample check skipped: ${e.message}")
            }
        }

        // Source 3: Check MusicBrainz (recording & work relationships)
        if (foundList.isEmpty() && !song.videoId.startsWith("sc:")) {
            try {
                queryMusicBrainzForSamples(song.title, song.author, foundList)
            } catch (e: Exception) {
                Log.d(TAG, "MusicBrainz sample check skipped: ${e.message}")
            }
        }

        // Source 4: Check Last.fm Track Wiki (Rich cultural & sample history)
        if (foundList.isEmpty()) {
            try {
                queryLastFmForSamples(song.title, song.author, foundList)
            } catch (e: Exception) {
                Log.d(TAG, "Last.fm sample check skipped: ${e.message}")
            }
        }

        val result = if (foundList.isNotEmpty()) {
            SampleResult.Found(foundList.distinctBy { "${it.originalTitle.lowercase()}|${it.originalArtist.lowercase()}" })
        } else {
            SampleResult.NotFound
        }

        cache[cacheKey] = result
        result
    }

    private fun parseDescriptionForSamples(description: String, out: MutableList<SampleItem>) {
        val lines = description.lines()
        val samplePatterns = listOf(
            Regex("""(?:contains\s+(?:samples|elements|interpolations)\s+of|contains\s+an\s+interpolation\s+of|samples\s+from|sampling|based\s+on)\s+["'“]?([^"'\n\r\(\)]+)["'”]?\s*(?:by|written\s+by|performed\s+by)?\s*([^,\n\r\.]*)""", RegexOption.IGNORE_CASE),
            Regex("""(?:samples?|interpolates?):\s*["'“]?([^"'\n\r\-]+)["'”]?\s*(?:-|by)?\s*([^,\n\r\.]*)""", RegexOption.IGNORE_CASE)
        )

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            for (pattern in samplePatterns) {
                val match = pattern.find(trimmed)
                if (match != null) {
                    val rawSong = match.groupValues.getOrNull(1)?.trim() ?: ""
                    val rawArtist = match.groupValues.getOrNull(2)?.trim() ?: ""
                    val cleanSong = rawSong.trim('"', '\'', '“', '”', ',', '.', ' ')
                    val cleanArtist = rawArtist.trim('"', '\'', '“', '”', ',', '.', ' ')

                    if (cleanSong.isNotBlank() && cleanSong.length > 2 && cleanSong.length < 80) {
                        val type = when {
                            trimmed.contains("interpolation", ignoreCase = true) -> "Interpolation"
                            trimmed.contains("based on", ignoreCase = true) -> "Based On"
                            trimmed.contains("cover", ignoreCase = true) -> "Cover"
                            else -> "Sample"
                        }
                        out.add(SampleItem(cleanSong, cleanArtist, type))
                        return
                    }
                }
            }
        }
    }

    private fun queryGeniusForSamples(title: String, author: String, out: MutableList<SampleItem>) {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(author)
        if (cleanTitle.isBlank()) return

        val q = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
        val enc = URLEncoder.encode(q.trim(), "UTF-8")
        val searchUrl = "https://genius.com/api/search/multi?q=$enc"

        val searchReq = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val searchResp = httpClient.newCall(searchReq).execute()
        if (!searchResp.isSuccessful) return
        val searchBody = searchResp.body?.string() ?: return
        val searchJson = runCatching { gson.fromJson(searchBody, JsonObject::class.java) }.getOrNull() ?: return
        val sections = searchJson.getAsJsonObject("response")?.getAsJsonArray("sections") ?: return
        var songId: Long? = null
        for (sec in sections) {
            val sObj = sec.asJsonObject ?: continue
            if (sObj.get("type")?.asString == "song") {
                val hits = sObj.getAsJsonArray("hits")
                if (hits != null && hits.size() > 0) {
                    val firstHit = hits[0].asJsonObject?.getAsJsonObject("result")
                    songId = firstHit?.get("id")?.asLong
                    break
                }
            }
        }
        if (songId == null) return

        val songUrl = "https://genius.com/api/songs/$songId"
        val songReq = Request.Builder()
            .url(songUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val songResp = httpClient.newCall(songReq).execute()
        if (!songResp.isSuccessful) return
        val songBody = songResp.body?.string() ?: return
        val songJson = runCatching { gson.fromJson(songBody, JsonObject::class.java) }.getOrNull() ?: return
        val rels = songJson.getAsJsonObject("response")?.getAsJsonObject("song")?.getAsJsonArray("song_relationships") ?: return

        for (relElem in rels) {
            val rel = relElem.asJsonObject ?: continue
            val relType = rel.get("relationship_type")?.asString ?: ""
            val songs = rel.getAsJsonArray("songs") ?: continue
            if (songs.size() == 0) continue

            val mappedType = when (relType) {
                "samples" -> "Sample"
                "interpolates" -> "Interpolation"
                "cover_of" -> "Cover"
                else -> null
            } ?: continue

            for (s in songs) {
                val sObj = s.asJsonObject ?: continue
                val sTitle = sObj.get("title")?.asString ?: ""
                val sArtist = sObj.get("artist_names")?.asString ?: ""
                if (sTitle.isNotBlank()) {
                    out.add(SampleItem(sTitle, sArtist, mappedType))
                }
            }
        }
    }

    private fun queryMusicBrainzForSamples(title: String, author: String, out: MutableList<SampleItem>) {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(author)
        if (cleanTitle.isBlank()) return

        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8")
        val encodedArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val url = "https://musicbrainz.org/ws/2/recording/?query=recording:%22$encodedTitle%22+AND+artist:%22$encodedArtist%22&fmt=json&limit=3"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VinMusic/2.3.5 ( myvinmusicapp@gmail.com )")
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) return
        val bodyStr = resp.body?.string() ?: return
        val root = runCatching { gson.fromJson(bodyStr, JsonObject::class.java) }.getOrNull() ?: return
        val recordings = root.getAsJsonArray("recordings") ?: return
        if (recordings.size() == 0) return

        val recId = recordings[0].asJsonObject.get("id")?.asString ?: return

        // Fetch relations for this recording
        val relUrl = "https://musicbrainz.org/ws/2/recording/$recId?inc=work-rels+recording-rels&fmt=json"
        val relReq = Request.Builder()
            .url(relUrl)
            .header("User-Agent", "VinMusic/2.3.5 ( myvinmusicapp@gmail.com )")
            .build()

        val relResp = httpClient.newCall(relReq).execute()
        if (!relResp.isSuccessful) return
        val relBody = relResp.body?.string() ?: return
        val relRoot = runCatching { gson.fromJson(relBody, JsonObject::class.java) }.getOrNull() ?: return
        val relations = relRoot.getAsJsonArray("relations") ?: return

        var linkedWorkId: String? = null

        for (elem in relations) {
            if (!elem.isJsonObject) continue
            val rel = elem.asJsonObject
            val type = rel.get("type")?.asString?.lowercase() ?: ""

            if (type == "performance" && rel.has("work") && rel.get("work").isJsonObject) {
                linkedWorkId = rel.getAsJsonObject("work").get("id")?.asString
            }

            if (type in listOf("samples", "sampled by", "is sampled by", "contains samples of", "elements")) {
                val targetRec = if (rel.has("recording") && rel.get("recording").isJsonObject) rel.getAsJsonObject("recording") else null
                val sampleTitle = targetRec?.get("title")?.asString
                val sampleArtist = targetRec?.getAsJsonArray("artist-credit")?.firstOrNull()?.asJsonObject?.get("name")?.asString ?: ""
                if (!sampleTitle.isNullOrBlank()) {
                    out.add(SampleItem(sampleTitle, sampleArtist, "Sample"))
                }
            }
        }

        // If recording had a linked work, check work relations for "based on" or "samples"
        if (out.isEmpty() && !linkedWorkId.isNullOrBlank()) {
            val workUrl = "https://musicbrainz.org/ws/2/work/$linkedWorkId?inc=work-rels&fmt=json"
            val workReq = Request.Builder()
                .url(workUrl)
                .header("User-Agent", "VinMusic/2.3.5 ( myvinmusicapp@gmail.com )")
                .build()

            val workResp = httpClient.newCall(workReq).execute()
            if (workResp.isSuccessful) {
                val workBody = workResp.body?.string()
                if (workBody != null) {
                    val workRoot = runCatching { gson.fromJson(workBody, JsonObject::class.java) }.getOrNull()
                    val workRelations = workRoot?.getAsJsonArray("relations")
                    if (workRelations != null) {
                        for (wElem in workRelations) {
                            if (!wElem.isJsonObject) continue
                            val wRel = wElem.asJsonObject
                            val wType = wRel.get("type")?.asString?.lowercase() ?: ""
                            if (wType in listOf("based on", "samples", "elements", "cover")) {
                                val targetWork = if (wRel.has("work") && wRel.get("work").isJsonObject) wRel.getAsJsonObject("work") else null
                                val workTitle = targetWork?.get("title")?.asString
                                if (!workTitle.isNullOrBlank()) {
                                    val relType = if (wType == "based on") "Interpolation" else "Sample"
                                    out.add(SampleItem(workTitle, "", relType))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun queryLastFmForSamples(title: String, author: String, out: MutableList<SampleItem>) {
        val cleanTitle = cleanSearchTerm(title)
        val cleanArtist = cleanSearchTerm(author)
        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return

        val encTrack = URLEncoder.encode(cleanTitle, "UTF-8")
        val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val apiKey = "526331c755d4f3a5e8c965230186a29b"
        val url = "https://ws.audioscrobbler.com/2.0/?method=track.getInfo&api_key=$apiKey&artist=$encArtist&track=$encTrack&format=json"

        val req = Request.Builder().url(url).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) return
        val bodyStr = resp.body?.string() ?: return
        val root = runCatching { gson.fromJson(bodyStr, JsonObject::class.java) }.getOrNull() ?: return
        val track = root.getAsJsonObject("track") ?: return
        val wiki = track.getAsJsonObject("wiki") ?: return
        val content = wiki.get("content")?.asString ?: wiki.get("summary")?.asString ?: return

        // Scan wiki text for sample mentions
        val sampleRegex = Regex("""(?:samples|sampled|interpolates|interpolation of)\s+["'“]?([^"'\n\.\;]+)["'”]?\s*(?:by|from)?\s*([^,\n\.\;]*)""", RegexOption.IGNORE_CASE)
        val match = sampleRegex.find(content)
        if (match != null) {
            val sTitle = match.groupValues.getOrNull(1)?.trim('"', '\'', '“', '”', ' ') ?: ""
            val sArtist = match.groupValues.getOrNull(2)?.trim('"', '\'', '“', '”', ' ') ?: ""
            if (sTitle.isNotBlank() && sTitle.length > 2 && sTitle.length < 80) {
                out.add(SampleItem(sTitle, sArtist, "Sample"))
            }
        }
    }

    private fun cleanSearchTerm(term: String): String {
        return term
            .replace(Regex("""\((?:official|video|audio|lyrics|hd|4k|feat\.|ft\.).*?\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[(?:official|video|audio|lyrics|hd|4k|feat\.|ft\.).*?\]""", RegexOption.IGNORE_CASE), "")
            .replace("-topic", "", ignoreCase = true)
            .replace("- topic", "", ignoreCase = true)
            .trim()
    }
}
