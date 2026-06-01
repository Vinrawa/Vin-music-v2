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
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val maxParallelDownloads = 2

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

            Log.d(TAG, "Fetching stream URL for download: $videoId")
            val prefs = applicationContext.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
            val quality = prefs.getString("download_quality", "High (256 kbps)")
            val url = InnerTube.getStreamUrl(videoId, quality)
            if (url == null) {
                Log.e(TAG, "Failed to fetch stream URL for download: $videoId")
                db.downloadDao().insert(entity.copy(status = "failed"))
                updateNotification()
                checkQueue()
                return@launch
            }

            // Create a copying entity storing the stream url in filePath
            var thumbnailPath: String? = null
            if (entity.thumbnailUrl != null) {
                try {
                    thumbnailPath = downloadThumbnail(videoId, entity.thumbnailUrl!!)
                    Log.d(TAG, "Thumbnail downloaded for $videoId: $thumbnailPath")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to download thumbnail for $videoId: ${e.message}")
                    // Continue with download even if thumbnail fails
                }
            }
            
            val downloadingEntity = entity.copy(status = "downloading", progress = 0, filePath = url, thumbnailPath = thumbnailPath)
            db.downloadDao().insert(downloadingEntity)
            updateNotification()

            try {
                val cache = PlayerSingleton.getDownloadCache(applicationContext)
                if (cache == null) {
                    Log.e(TAG, "SimpleCache not available for download: $videoId")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
                    updateNotification()
                    checkQueue()
                    return@launch
                }

                val resolvedUa = InnerTube.getUserAgentForUrl(url)
                val requestProps = buildMap<String, String> {
                    if (resolvedUa.startsWith("Mozilla")) {
                        put("Origin", "https://www.youtube.com")
                        put("Referer", "https://www.youtube.com/")
                    }
                    put("Accept-Encoding", "identity")
                }

                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(resolvedUa)
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(requestProps)

                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .createDataSource()

                val uriParsed = android.net.Uri.parse(url)
                val clenStr = uriParsed.getQueryParameter("clen")
                val contentLength = clenStr?.toLongOrNull() ?: -1L

                var totalLength = contentLength
                if (totalLength <= 0) {
                    try {
                        val spec = DataSpec.Builder()
                            .setUri(uriParsed)
                            .setKey(videoId)
                            .build()
                        totalLength = cacheDataSource.open(spec)
                        cacheDataSource.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to resolve content length: ${e.message}")
                        totalLength = 10 * 1024 * 1024L // 10MB fallback
                    }
                }

                Log.d(TAG, "Starting chunked download for: $videoId. clen: $totalLength. url: ${url.take(120)}")

                val chunkSize = 1024 * 1024L // 1MB chunks
                var bytesCached = 0L

                while (bytesCached < totalLength) {
                    yield() // Check for coroutine cancellation gracefully

                    val chunkEnd = minOf(bytesCached + chunkSize, totalLength)
                    val chunkLength = chunkEnd - bytesCached

                    val chunkDataSpec = DataSpec.Builder()
                        .setUri(uriParsed)
                        .setKey(videoId)
                        .setPosition(bytesCached)
                        .setLength(chunkLength)
                        .build()

                    val chunkWriter = CacheWriter(
                        cacheDataSource,
                        chunkDataSpec,
                        ByteArray(128 * 1024), // 128KB buffer for caching chunks
                        null
                    )

                    try {
                        chunkWriter.cache()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error caching chunk starting at $bytesCached: ${e.message}")
                        throw e
                    }

                    bytesCached = chunkEnd
                    val pct = if (totalLength > 0) (bytesCached * 100 / totalLength).toInt().coerceIn(0, 100) else 0

                    db.downloadDao().insert(
                        downloadingEntity.copy(
                            progress = pct,
                            sizeBytes = bytesCached
                        )
                    )
                    updateNotification()
                }

                val finalCachedBytes = cache.getCachedBytes(videoId, 0, -1)
                
                // Verify download is actually complete before marking as done
                if (finalCachedBytes < 100_000L) {
                    Log.e(TAG, "Download verification failed: $videoId only has $finalCachedBytes bytes cached")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed", progress = 0))
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
                                thumbnailPath = downloadingEntity.thumbnailPath  // Preserve thumbnail path
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
                Log.d(TAG, "Download finished successfully: $videoId. Total cached bytes stored: $finalCachedBytes. Expected content length: $contentLength")

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $videoId")
                db.downloadDao().insert(downloadingEntity.copy(status = "failed", progress = 0))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading $videoId: ${e.message}", e)
                val current = db.downloadDao().get(videoId)
                if (current != null) {
                    db.downloadDao().insert(current.copy(status = "failed"))
                } else {
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
                }
            } finally {
                activeJobs.remove(videoId)
                updateNotification()
                checkQueue()
            }
        }
        activeJobs[videoId] = job
    }

    private fun updateNotification() {
        val activeCount = activeJobs.size
        if (activeCount == 0) {
            @Suppress("DEPRECATION")
            stopForeground(true)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            return
        }

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

    private suspend fun downloadThumbnail(videoId: String, thumbnailUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val thumbnailDir = File(applicationContext.cacheDir, "thumbnails")
            if (!thumbnailDir.exists()) {
                thumbnailDir.mkdirs()
            }
            
            val thumbnailFile = File(thumbnailDir, "$videoId.jpg")
            
            java.net.URL(thumbnailUrl).openStream().use { input ->
                thumbnailFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "Thumbnail saved: ${thumbnailFile.absolutePath}")
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
