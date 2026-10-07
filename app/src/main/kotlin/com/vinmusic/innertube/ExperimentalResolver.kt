package com.vinmusic.innertube

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ExperimentalResolver {
    private const val TAG = "VIN_STREAM"
    private const val BASE = "https://www.youtube.com/youtubei/v1"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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

    fun getStreamUrl(videoId: String, quality: String? = null): String? {
        Log.d(TAG, "--- EXPERIMENTAL RESOLVER INITIATED ---")
        Log.d(TAG, "Attempting Metrolist IOS fallback for videoId: $videoId")
        
        val ctx = mapOf(
            "clientName" to "IOS",
            "clientVersion" to "21.46.1",
            "hl" to "en",
            "gl" to "IN",
            "osName" to "iPhone",
            "osVersion" to "18.4.22E142",
            "deviceMake" to "Apple",
            "deviceModel" to "iPhone16,2"
        )

        val body = mapOf(
            "context" to mapOf("client" to ctx),
            "videoId" to videoId,
            "racyCheckOk" to true, 
            "contentCheckOk" to true
        )

        val reqBuilder = Request.Builder()
            .url("$BASE/player?prettyPrint=false")
            .post(gson.toJson(body).toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .header("User-Agent", "com.google.ios.youtube/21.46.1 (iPhone16,2; U; CPU iOS 18_4 like Mac OS X;)")
            .header("X-YouTube-Client-Name", "5")
            .header("X-YouTube-Client-Version", "21.46.1")
            .header("Origin", "https://www.youtube.com")

        try {
            val raw = http.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string() ?: ""
            }
            
            val root = gson.fromJson(raw, Map::class.java)
            val status = (root["playabilityStatus"] as? Map<*, *>)?.get("status") as? String
            val reason = (root["playabilityStatus"] as? Map<*, *>)?.get("reason") as? String
            
            Log.d(TAG, "Experimental IOS: status=$status reason=$reason")
            
            if (status != "OK") return null

            val sd = root["streamingData"] as? Map<*, *> ?: return null
            val adaptiveFormats = sd["adaptiveFormats"] as? List<*> ?: return null

            val audioUrl = adaptiveFormats
                .mapNotNull { it as? Map<*, *> }
                .filter { f ->
                    val mime = f["mimeType"] as? String ?: ""
                    val url = f["url"] as? String ?: ""
                    val noCip = !f.containsKey("signatureCipher") && !f.containsKey("cipher")
                    mime.startsWith("audio/") && url.isNotEmpty() && noCip
                }
                .let { streams ->
                    val targetKbps = when {
                        quality?.contains("96") == true -> 96
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
                }?.get("url") as? String

            if (!audioUrl.isNullOrEmpty()) {
                Log.d(TAG, "Experimental Resolver SUCCESS. Selected URL Host: ${java.net.URI(audioUrl).host}")
                return audioUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Experimental Resolver Error: ${e.message}")
        }
        return null
    }
}
