package com.vinmusic.download

import android.content.Context
import android.util.Log
import com.vinmusic.innertube.InnerTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

object ArtistBannerCache {
    private const val TAG = "ArtistBannerCache"
    private const val DIR_NAME = "artist_banners"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun dir(context: Context): File {
        val d = File(context.filesDir, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun normalizedName(artistName: String): String {
        val cleaned = artistName.lowercase()
            .replace(Regex("[^a-z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        // Non-Latin names (core Punjabi/Hindi audience) collapse to "" here, which
        // made every such artist share a single ".jpg" banner file. Use a stable
        // hash-based name instead.
        if (cleaned.isEmpty()) {
            return "artist_%08x".format(artistName.hashCode())
        }
        return cleaned
    }

    fun bannerPath(context: Context, artistName: String): String? {
        val file = File(dir(context), "${normalizedName(artistName)}.jpg")
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun hasBanner(context: Context, artistName: String): Boolean {
        return bannerPath(context, artistName) != null
    }

    suspend fun downloadBanner(context: Context, artistName: String, channelId: String): String? = withContext(Dispatchers.IO) {
        val existing = bannerPath(context, artistName)
        if (existing != null) return@withContext existing

        try {
            val channelData = InnerTube.fetchChannelData(channelId)
            val bannerUrl = channelData.bannerUrl
            if (bannerUrl.isBlank()) return@withContext null

            val file = File(dir(context), "${normalizedName(artistName)}.jpg")
            val request = Request.Builder()
                .url(bannerUrl)
                .header("User-Agent", "VinMusic/2.0")
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                val file = File(dir(context), "${normalizedName(artistName)}.jpg")
                file.outputStream().use { output ->
                    body.byteStream().copyTo(output)
                }
            }

            if (file.length() == 0L) {
                file.delete()
                Log.w(TAG, "Banner was 0 bytes, deleted: $artistName")
                return@withContext null
            }

            Log.d(TAG, "Banner cached (${file.length()} bytes): $artistName")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache banner for $artistName: ${e.message}")
            null
        }
    }

    suspend fun downloadBannerByName(context: Context, artistName: String): String? = withContext(Dispatchers.IO) {
        val existing = bannerPath(context, artistName)
        if (existing != null) return@withContext existing

        try {
            val cleanName = artistName
                .replace("-topic", "", ignoreCase = true)
                .replace("- topic", "", ignoreCase = true)
                .trim()
            val res = InnerTube.searchAll(cleanName)
            val artist = res.artists.maxByOrNull { it.subscriberCount.toLongOrNull() ?: 0L } ?: return@withContext null
            downloadBanner(context, artistName, artist.channelId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve artist for banner: $artistName: ${e.message}")
            null
        }
    }

    fun deleteBanner(context: Context, artistName: String): Boolean {
        val file = File(dir(context), "${normalizedName(artistName)}.jpg")
        return if (file.exists()) file.delete() else false
    }
}
