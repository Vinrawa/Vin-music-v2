package com.vinmusic.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.room.withTransaction
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.player.PlayerSingleton
import com.vinmusic.diagnostics.ReliabilityDiagnostics
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "vin_downloads"
        const val NOTIFICATION_ID = 9999

        const val ACTION_ENQUEUE = "com.vinmusic.download.action.ENQUEUE"
        const val ACTION_PAUSE = "com.vinmusic.download.action.ACTION_PAUSE"
        const val ACTION_RESUME = "com.vinmusic.download.action.ACTION_RESUME"
        const val ACTION_CANCEL = "com.vinmusic.download.action.ACTION_CANCEL"

        const val EXTRA_VIDEO_ID = "videoId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_THUMBNAIL = "thumbnail"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val maxParallelDownloads = 3

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ReliabilityDiagnostics.init(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildServiceNotification("Initializing downloads...", 0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val videoId = intent?.getStringExtra(EXTRA_VIDEO_ID)

        Log.d(TAG, "onStartCommand action=$action videoId=$videoId")

        if (videoId != null) {
            when (action) {
                ACTION_ENQUEUE -> {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Unknown"
                    val author = intent.getStringExtra(EXTRA_AUTHOR) ?: "Unknown"
                    val duration = intent.getStringExtra(EXTRA_DURATION) ?: ""
                    val thumbnail = intent.getStringExtra(EXTRA_THUMBNAIL)
                    enqueueDownload(videoId, title, author, duration, thumbnail)
                }
                ACTION_PAUSE, ACTION_CANCEL -> {
                    cancelDownload(videoId)
                }
                ACTION_RESUME -> {
                    serviceScope.launch {
                        val db = VinDatabase.getInstance(applicationContext)
                        val entity = withContext(Dispatchers.IO) { db.downloadDao().get(videoId) }
                        if (entity != null) {
                            enqueueDownload(entity.videoId, entity.title, entity.author, entity.durationText, entity.thumbnailUrl)
                        }
                    }
                }
            }
        } else {
            checkQueue()
        }

        return START_NOT_STICKY
    }

    private fun enqueueDownload(videoId: String, title: String, author: String, duration: String, thumbnailUrl: String? = null) {
        serviceScope.launch {
            val db = VinDatabase.getInstance(applicationContext)
            withContext(Dispatchers.IO) {
                val existing = db.downloadDao().get(videoId)
                if (existing == null || existing.status != "completed") {
                    db.downloadDao().insert(
                        DownloadEntity(
                            videoId = videoId,
                            title = title,
                            author = author,
                            durationText = duration,
                            filePath = "cache",
                            sizeBytes = 0,
                            status = "queued",
                            progress = 0,
                            thumbnailUrl = thumbnailUrl
                        )
                    )
                }
            }
            checkQueue()
        }
    }

    private fun cancelDownload(videoId: String) {
        activeJobs[videoId]?.cancel()
        activeJobs.remove(videoId)
        serviceScope.launch(Dispatchers.IO) {
            val db = VinDatabase.getInstance(applicationContext)
            try {
                val dlEntity = db.downloadDao().get(videoId)
                dlEntity?.thumbnailPath?.let { path ->
                    val file = java.io.File(path)
                    if (file.exists()) file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete thumbnail on cancel: ${e.message}")
            }
            db.downloadDao().delete(videoId)
            checkQueue()
        }
    }

    private fun checkQueue() {
        serviceScope.launch {
            if (activeJobs.size >= maxParallelDownloads) return@launch

            val db = VinDatabase.getInstance(applicationContext)
            val queuedList = withContext(Dispatchers.IO) {
                db.downloadDao().getByStatus("queued")
            }

            if (queuedList.isEmpty() && activeJobs.isEmpty()) {
                // Queue fully drained — drop foreground protection here (not in
                // updateNotification, which can race a just-started download).
                @Suppress("DEPRECATION")
                stopForeground(true)
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(NOTIFICATION_ID)
                stopSelf()
                return@launch
            }

            for (entity in queuedList) {
                if (activeJobs.size >= maxParallelDownloads) break
                if (!activeJobs.containsKey(entity.videoId)) {
                    startDownloadTask(entity)
                }
            }
        }
    }

    private fun startDownloadTask(entity: DownloadEntity) {
        val videoId = entity.videoId
        val title = entity.title
        val author = entity.author
        val duration = entity.durationText

        val job = serviceScope.launch(Dispatchers.IO) {
            val db = VinDatabase.getInstance(applicationContext)
            var downloadingEntity = entity.copy(status = "downloading", progress = 0)

            try {
                // Check if device is offline before trying network
                val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                @Suppress("DEPRECATION")
                val isOnline = cm?.activeNetworkInfo?.isConnected == true
                if (!isOnline) {
                    Log.w(TAG, "Device is offline. Keeping download queued: $videoId")
                    db.downloadDao().insert(entity.copy(status = "queued"))
                    return@launch
                }

                Log.d(TAG, "Fetching stream URL for download: $videoId")
                ReliabilityDiagnostics.record("download", "start", videoId, status = "started")
                val prefs = applicationContext.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val quality = prefs.getString("download_quality", "High (256 kbps)")
                fun resolveDownloadUrl(): String? {
                    repeat(2) { attempt ->
                        ReliabilityDiagnostics.record("download", "resolve_attempt", videoId, attempt = attempt + 1, status = "started")
                        val resolved = InnerTube.getStreamUrl(videoId, quality)
                        ReliabilityDiagnostics.record("download", "resolve_attempt", videoId, attempt = attempt + 1, status = if (resolved.isNullOrBlank()) "empty" else "ok")
                        if (!resolved.isNullOrBlank()) return resolved
                        if (attempt == 0) Thread.sleep(350)
                    }
                    return null
                }
                val url = resolveDownloadUrl()
                if (url == null) {
                    Log.e(TAG, "Failed to fetch stream URL for download: $videoId")
                    ReliabilityDiagnostics.record("download", "resolve_end", videoId, status = "failed", error = "stream_url_null", details = InnerTube.lastDebugMsg)
                    db.downloadDao().insert(entity.copy(status = "failed"))
                    return@launch
                }

                // Start thumbnail download in parallel (non-blocking)
                val thumbDeferred = async(Dispatchers.IO) {
                    var thumbnailPath: String? = null
                    val tUrl = entity.thumbnailUrl
                    try {
                        if (tUrl != null) {
                            thumbnailPath = downloadThumbnail(videoId, tUrl)
                        }
                        if (thumbnailPath == null) {
                            thumbnailPath = downloadThumbnail(videoId, "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                        }
                        if (thumbnailPath == null) {
                            thumbnailPath = downloadThumbnail(videoId, "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
                        }
                        Log.d(TAG, "Thumbnail downloaded for $videoId: $thumbnailPath")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to download thumbnail for $videoId: ${e.message}")
                    }
                    thumbnailPath
                }
                
                downloadingEntity = entity.copy(status = "downloading", progress = 0, filePath = url)
                db.downloadDao().insert(downloadingEntity)
                updateNotification()

                val cache = PlayerSingleton.getDownloadCache(applicationContext)
                if (cache == null) {
                    Log.e(TAG, "SimpleCache not available for download: $videoId")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
                    return@launch
                }

                var activeUrl = url
                var uriParsed = android.net.Uri.parse(activeUrl)
                val clenStr = uriParsed.getQueryParameter("clen")
                val totalLength = clenStr?.toLongOrNull() ?: -1L

                Log.d(TAG, "Starting high-speed single stream download for: $videoId. clen: $totalLength")

                var streamCached = false
                var lastError: Exception? = null

                repeat(2) { attempt ->
                    if (streamCached) return@repeat

                    val safeUrl = activeUrl ?: ""
                    val resolvedUa = InnerTube.getUserAgentForUrl(safeUrl)
                    val isNativeClient = safeUrl.contains("c=IOS") || safeUrl.contains("c=ANDROID")
                    val requestProps = mutableMapOf(
                        "Accept-Encoding" to "identity"
                    )
                    if (!isNativeClient) {
                        requestProps["Origin"] = "https://www.youtube.com"
                        requestProps["Referer"] = "https://www.youtube.com/"
                    }

                    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                        .setUserAgent(resolvedUa)
                        .setConnectTimeoutMs(15_000)
                        .setReadTimeoutMs(20_000)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(requestProps)

                    val cacheDataSource = CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(httpDataSourceFactory)
                        .createDataSource()

                    val streamSpec = DataSpec.Builder()
                        .setUri(uriParsed)
                        .setKey(videoId)
                        .setLength(if (totalLength > 0) totalLength else -1L)
                        .build()

                    var lastDbUpdateMs = 0L
                    var lastPct = 0

                    val streamWriter = CacheWriter(
                        cacheDataSource,
                        streamSpec,
                        ByteArray(256 * 1024), // 256KB buffer for maximum throughput
                        CacheWriter.ProgressListener { requestLength, bytesCachedNow, _ ->
                            val effectiveLength = if (totalLength > 0) totalLength else requestLength
                            val pct = if (effectiveLength > 0) ((bytesCachedNow * 100) / effectiveLength).toInt().coerceIn(0, 99) else 50
                            val now = System.currentTimeMillis()
                            if (pct != lastPct && (pct % 5 == 0 || now - lastDbUpdateMs > 300)) {
                                lastPct = pct
                                lastDbUpdateMs = now
                                serviceScope.launch(Dispatchers.IO) {
                                    db.downloadDao().updateProgress(videoId, pct, bytesCachedNow)
                                }
                            }
                        }
                    )

                    try {
                        streamWriter.cache()
                        streamCached = true
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Error caching stream attempt ${attempt + 1}: ${e.message}")
                        ReliabilityDiagnostics.record("download", "cache", videoId, attempt = attempt + 1, status = "failed", httpCode = (e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode, error = e.javaClass.simpleName, details = e.message)
                        val refreshed = resolveDownloadUrl()
                        if (!refreshed.isNullOrBlank()) {
                            activeUrl = refreshed
                            uriParsed = android.net.Uri.parse(activeUrl)
                        }
                    }
                }

                if (!streamCached) {
                    throw lastError ?: IllegalStateException("Failed to download audio stream")
                }

                val finalThumbnailPath = thumbDeferred.await()
                val finalCachedBytes = cache.getCachedBytes(videoId, 0, -1)

                // Verify download is actually complete before marking as done
                if (finalCachedBytes < 100_000L) {
                    Log.e(TAG, "Download verification failed: $videoId only has $finalCachedBytes bytes cached")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed", progress = 0))
                    return@launch
                }
                if (totalLength > 0L && finalCachedBytes < (totalLength * 0.95).toLong()) {
                    Log.e(TAG, "Download verification failed: $videoId cached $finalCachedBytes of expected $totalLength bytes (${(finalCachedBytes * 100 / totalLength)}%)")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed", progress = 0, sizeBytes = finalCachedBytes))
                    return@launch
                }
                
                // Update DB atomically using transaction
                try {
                    db.withTransaction {
                        db.downloadDao().insert(
                            downloadingEntity.copy(
                                status = "completed",
                                progress = 100,
                                sizeBytes = finalCachedBytes,
                                thumbnailPath = finalThumbnailPath
                            )
                        )
                        // Update interaction signal for downloaded status
                        val sig = db.interactionSignalDao().get(videoId)
                        if (sig != null) {
                            sig.isDownloaded = true
                            db.interactionSignalDao().insert(sig)
                        } else {
                            db.interactionSignalDao().insert(
                                InteractionSignal(
                                    videoId = videoId,
                                    title = downloadingEntity.title,
                                    author = downloadingEntity.author,
                                    durationText = downloadingEntity.durationText,
                                    isDownloaded = true
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update download completion for $videoId: ${e.message}")
                    throw e
                }

                // Fetch and cache lyrics for offline usage
                try {
                    val cachedLyrics = db.cachedLyricsDao().get(videoId)
                    if (cachedLyrics == null) {
                        val prefs = this@DownloadService.getSharedPreferences("vin_music_prefs", android.content.Context.MODE_PRIVATE)
                        val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                        val lyricsRes = com.vinmusic.lyrics.LyricsHelper.fetch(downloadingEntity.title, downloadingEntity.author, videoId, provider)
                        val type = when (lyricsRes) {
                            is com.vinmusic.lyrics.LyricsResult.Synced -> "synced"
                            is com.vinmusic.lyrics.LyricsResult.Plain -> "plain"
                            else -> "not_found"
                        }
                        val content = when (lyricsRes) {
                            is com.vinmusic.lyrics.LyricsResult.Synced -> com.google.gson.Gson().toJson(lyricsRes.lines)
                            is com.vinmusic.lyrics.LyricsResult.Plain -> lyricsRes.text
                            else -> ""
                        }
                        if (type != "not_found" && content.isNotEmpty()) {
                            db.cachedLyricsDao().insert(
                                com.vinmusic.data.db.CachedLyricsEntity(videoId, type, content)
                            )
                            Log.d(TAG, "Lyrics cached offline for $videoId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cache offline lyrics: ${e.message}")
                }

                // Cache artist banner for offline artist cards
                try {
                    ArtistBannerCache.downloadBannerByName(this@DownloadService, downloadingEntity.author)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to cache artist banner: ${e.message}")
                }

                Log.d(TAG, "Download finished successfully: $videoId. Total cached bytes stored: $finalCachedBytes. Expected content length: $totalLength")
                ReliabilityDiagnostics.record("download", "complete", videoId, status = "ok", details = "bytes=$finalCachedBytes")

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $videoId")
                ReliabilityDiagnostics.record("download", "cancelled", videoId, status = "cancelled")
                // Deliberately NO db write here: cancelDownload() deletes this row
                // concurrently, and inserting "failed" would resurrect a ghost entry
                // referencing files that were just cleaned up.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading $videoId: ${e.message}", e)
                ReliabilityDiagnostics.record("download", "failed", videoId, status = "failed", httpCode = (e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode, error = e.javaClass.simpleName, details = e.message)
                val current = db.downloadDao().get(videoId)
                if (current != null) {
                    db.downloadDao().insert(current.copy(status = "failed"))
                } else {
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
                }
            } finally {
                activeJobs.remove(videoId)
                // Queue first: it may start the next download, and the notification
                // update below must not drop foreground protection while work remains.
                checkQueue()
                updateNotification()
            }
        }
        activeJobs[videoId] = job
    }

    private fun updateNotification() {
        val activeCount = activeJobs.size
        if (activeCount == 0) return // drained — checkQueue() owns foreground teardown

        val text = "Downloading $activeCount track(s)..."
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildServiceNotification(text, activeCount * 50))
    }

    private fun buildServiceNotification(text: String, progress: Int): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Vin Music Cache Downloader")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private val thumbnailHttp = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private suspend fun downloadThumbnail(videoId: String, thumbnailUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val thumbnailDir = File(applicationContext.filesDir, "thumbnails")
            if (!thumbnailDir.exists()) {
                thumbnailDir.mkdirs()
            }
            
            val thumbnailFile = File(thumbnailDir, "$videoId.jpg")
            
            val request = Request.Builder()
                .url(thumbnailUrl)
                .header("User-Agent", "VinMusic/2.0")
                .build()

            val body = thumbnailHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()
            } ?: return@withContext null

            body.use { input ->
                thumbnailFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Validate: delete if empty/corrupt
            if (thumbnailFile.length() == 0L) {
                thumbnailFile.delete()
                Log.w(TAG, "Thumbnail was 0 bytes, deleted: $videoId")
                return@withContext null
            }
            
            Log.d(TAG, "Thumbnail saved (${thumbnailFile.length()} bytes): ${thumbnailFile.absolutePath}")
            return@withContext thumbnailFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading thumbnail for $videoId: ${e.message}")
            return@withContext null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
