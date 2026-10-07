package com.vinmusic.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.vinmusic.data.db.VinDatabase

import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.diagnostics.ReliabilityDiagnostics
import kotlinx.coroutines.*

@UnstableApi
object PlayerCacheManager {
    private const val TAG = "VIN_PLAYER_CACHE"

    data class CacheCheckResult(
        val isDownloadCacheValid: Boolean,
        val isPlayerCached: Boolean,
        val isCachedComplete: Boolean,
        val totalCachedBytes: Long,
        val isDeviceOnline: Boolean
    )

    fun isOnline(ctx: Context): Boolean {
        return try {
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun checkCacheStatus(ctx: Context, database: VinDatabase, videoId: String): CacheCheckResult = withContext(Dispatchers.IO) {
        val localDownload = database.downloadDao().get(videoId)
        val isDownloadCompleted = localDownload?.status == "completed"
        val dlCache = if (isDownloadCompleted) PlayerSingleton.getDownloadCache(ctx) else null
        val dlCacheBytes = dlCache?.getCachedBytes(videoId, 0, -1) ?: 0L
        
        // Healing Mechanism: If DB says downloaded but actual cached bytes are missing, heal DB state!
        var healed = false
        if (isDownloadCompleted && dlCacheBytes < 100_000L) {
            Log.w(TAG, "Download DB says completed, but cached bytes are missing ($dlCacheBytes). Healing DB.")
            try {
                database.withTransaction {
                    database.downloadDao().delete(videoId)
                    val sig = database.interactionSignalDao().get(videoId)
                    if (sig != null) {
                        sig.isDownloaded = false
                        database.interactionSignalDao().insert(sig)
                    }
                }
                healed = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to heal database for song $videoId: ${e.message}")
            }
        }

        val isDownloadCacheValid = !healed && isDownloadCompleted && dlCacheBytes > 100_000L
        val pCache = PlayerSingleton.getCache(ctx)
        val pCacheBytes = pCache?.getCachedBytes(videoId, 0, -1) ?: 0L
        val isPlayerCached = pCacheBytes > 500_000L
        val playerContentLength = pCache?.getContentMetadata(videoId)
            ?.get(ContentMetadata.KEY_CONTENT_LENGTH, -1L) ?: -1L
        val onlineState = isOnline(ctx)

        // If content length is known, verify >= 95% is cached.
        // When device is online: do NOT falsely treat partial ~1.2MB as 100% complete if content length is unknown.
        // When device is offline: allow playing from cache if pCacheBytes > 500KB so user can listen offline seamlessly.
        val isPlayerCacheComplete = when {
            playerContentLength > 0L -> pCacheBytes >= (playerContentLength * 0.95).toLong()
            !onlineState -> pCacheBytes > 500_000L
            else -> false
        }
        val isCachedComplete = isDownloadCacheValid || isPlayerCacheComplete
        val totalCachedBytes = if (isDownloadCacheValid) dlCacheBytes else if (isPlayerCacheComplete) pCacheBytes else 0L

        CacheCheckResult(
            isDownloadCacheValid = isDownloadCacheValid,
            isPlayerCached = isPlayerCached,
            isCachedComplete = isCachedComplete,
            totalCachedBytes = totalCachedBytes,
            isDeviceOnline = onlineState
        )
    }

    fun prefetchNextSongs(
        ctx: Context,
        scope: CoroutineScope,
        db: VinDatabase?,
        quality: String,
        nextSongs: List<VideoItem>,
        nextStreamUrlDeferred: Pair<String, Deferred<String?>>?
    ): Job {
        return scope.launch(Dispatchers.IO) {
            try {
                // Wait a bit to let the active song start playing smoothly
                delay(3000)
                
                for ((offset, nextSong) in nextSongs.withIndex()) {
                    // Prefetch thumbnail images using Coil in parallel
                    launch(Dispatchers.IO) {
                        try {
                            val loader = SingletonImageLoader.get(ctx)
                            val req1 = ImageRequest.Builder(ctx)
                                .data(nextSong.thumbnail)
                                .build()
                            loader.enqueue(req1)
                            
                            val req2 = ImageRequest.Builder(ctx)
                                .data(nextSong.thumbnailHd)
                                .build()
                            loader.enqueue(req2)
                            Log.d(TAG, "Prefetched thumbnails for next song: ${nextSong.title}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to prefetch thumbnail: ${e.message}")
                        }
                    }
                    
                    // Verify actual cached bytes exist in the download cache before skipping prefetch
                    val localDownload = db?.downloadDao()?.get(nextSong.videoId)
                    val isComplete = localDownload?.status == "completed"
                    val dlCache = if (isComplete) ctx.let { PlayerSingleton.getDownloadCache(it) } else null
                    val dlCacheBytes = dlCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                    if (isComplete && dlCacheBytes > 100_000L) {
                        Log.d(TAG, "prefetchNextSongs: Song ${nextSong.title} (offset $offset) is downloaded offline. Skipping.")
                        continue
                    }
                    
                    // Check if player cache already has enough bytes
                    val pCache = PlayerSingleton.getCache(ctx)
                    val pCacheBytes = pCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                    if (pCacheBytes > 1_500_000L) {
                        Log.d(TAG, "prefetchNextSongs: Song ${nextSong.title} (offset $offset) already cached. Skipping.")
                        continue
                    }
                    
                    val cache = PlayerSingleton.getCache(ctx) ?: continue
                    var cached = false
                    var lastError: Exception? = null
                    for (attempt in 1..2) {
                        val streamUrl = try {
                            val deferredPair = nextStreamUrlDeferred
                            if (attempt == 1 && offset == 0 && deferredPair != null && deferredPair.first == nextSong.videoId) {
                                Log.d(TAG, "prefetchNextSongs: Found active stream URL prefetch deferred. Waiting...")
                                deferredPair.second.await()
                            } else {
                                Log.d(TAG, "prefetchNextSongs: Fetching stream URL for prefetch offset=$offset attempt=$attempt...")
                                ReliabilityDiagnostics.record("prefetch", "resolve", nextSong.videoId, attempt = attempt, status = "started")
                                InnerTube.getStreamUrl(nextSong.videoId, quality)
                            }
                        } catch (e: Exception) {
                            lastError = e
                            null
                        }
                        if (streamUrl.isNullOrBlank()) continue

                        try {
                            val resolvedUa = com.vinmusic.innertube.InnerTube.getUserAgentForUrl(streamUrl)
                            val isNativeClient = streamUrl.contains("c=IOS") || streamUrl.contains("c=ANDROID")
                            val requestProps = mutableMapOf(
                                "Accept-Encoding" to "identity"
                            )
                            if (!isNativeClient) {
                                requestProps["Origin"] = "https://www.youtube.com"
                                requestProps["Referer"] = "https://www.youtube.com/"
                            }
                            val httpFactory = DefaultHttpDataSource.Factory()
                                .setUserAgent(resolvedUa)
                                .setConnectTimeoutMs(30_000)
                                .setReadTimeoutMs(30_000)
                                .setAllowCrossProtocolRedirects(true)
                                .setDefaultRequestProperties(requestProps)
                            val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                                .setCache(cache)
                                .setUpstreamDataSourceFactory(httpFactory)
                                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                                .createDataSource()
                            val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                                .setUri(android.net.Uri.parse(streamUrl))
                                .setPosition(0)
                                .setLength(12_000_000L)
                                .setKey(nextSong.videoId)
                                .build()
                            androidx.media3.datasource.cache.CacheWriter(cacheDataSource, dataSpec, null, null).cache()
                            cached = true
                            ReliabilityDiagnostics.record("prefetch", "cache", nextSong.videoId, attempt = attempt, status = "ok")
                            // Ensure cached song has metadata in DB so it appears in Cached list
                            try {
                                val existing = db?.interactionSignalDao()?.get(nextSong.videoId)
                                if (existing == null) {
                                    db?.interactionSignalDao()?.insert(
                                        com.vinmusic.data.db.InteractionSignal(
                                            videoId = nextSong.videoId,
                                            title = nextSong.title,
                                            author = nextSong.author,
                                            durationText = nextSong.durationText
                                        )
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to upsert InteractionSignal for prefetch: ${e.message}")
                            }
                            Log.d(TAG, "prefetchNextSongs: Successfully completed prefetch of 2.5MB for ${nextSong.title} (offset $offset)")
                            break
                        } catch (e: Exception) {
                            lastError = e
                            val status = (e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                            ReliabilityDiagnostics.record("prefetch", "cache", nextSong.videoId, attempt = attempt, status = "failed", httpCode = status, error = e.javaClass.simpleName, details = e.message)
                            if (status == 401 || status == 403) {
                                runCatching { cache.removeResource(nextSong.videoId) }
                                com.vinmusic.innertube.InnerTube.invalidateStreamUrl(nextSong.videoId)
                            }
                            if (attempt < 2) delay(350)
                        }
                    }
                    if (!cached) Log.w(TAG, "prefetchNextSongs failed for ${nextSong.videoId}: ${lastError?.message}")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "prefetchNextSongs cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "prefetchNextSongs failed: ${e.message}", e)
            }
        }
    }
}
