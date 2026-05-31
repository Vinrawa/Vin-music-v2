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
                    enqueueDownload(videoId, title, author, duration)
                }
                ACTION_PAUSE, ACTION_CANCEL -> {
                    cancelDownload(videoId)
                }
                ACTION_RESUME -> {
                    serviceScope.launch {
                        val db = VinDatabase.getInstance(applicationContext)
                        val entity = withContext(Dispatchers.IO) { db.downloadDao().get(videoId) }
                        if (entity != null) {
                            enqueueDownload(entity.videoId, entity.title, entity.author, entity.durationText)
                        }
                    }
                }
            }
        } else {
            checkQueue()
        }

        return START_NOT_STICKY
    }

    private fun enqueueDownload(videoId: String, title: String, author: String, duration: String) {
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
                            progress = 0
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
            val downloadingEntity = entity.copy(status = "downloading", progress = 0, filePath = url)
            db.downloadDao().insert(downloadingEntity)
            updateNotification()

            try {
                val cache = PlayerSingleton.getCache(applicationContext)
                if (cache == null) {
                    Log.e(TAG, "SimpleCache not available for download: $videoId")
                    db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
                    updateNotification()
                    checkQueue()
                    return@launch
                }

                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X) AppleWebKit/605.1.15")
                    .setDefaultRequestProperties(mapOf(
                        "Origin"  to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/"
                    ))

                val cacheDataSource = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .createDataSource()

                val uriParsed = android.net.Uri.parse(url)
                val clenStr = uriParsed.getQueryParameter("clen")
                val contentLength = clenStr?.toLongOrNull() ?: -1L

                val dataSpec = DataSpec.Builder()
                    .setUri(uriParsed)
                    .setKey(videoId)
                    .apply {
                        if (contentLength > 0) {
                            setLength(contentLength)
                        }
                    }
                    .build()

                Log.d(TAG, "Starting CacheWriter download for: $videoId. clen parameter: $contentLength. url: ${url.take(120)}")

                val cacheWriter = CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    ByteArray(128 * 1024),
                    object : CacheWriter.ProgressListener {
                        private var lastUpdate = 0L
                        override fun onProgress(requestLength: Long, bytesCached: Long, newBytesCached: Long) {
                            val now = System.currentTimeMillis()
                            val pct = if (requestLength > 0) (bytesCached * 100 / requestLength).toInt() else 0
                            
                            Log.d(TAG, "Download progress videoId=$videoId: bytesCached=$bytesCached, requestLength=$requestLength, pct=$pct%")

                            if (now - lastUpdate > 1000) {
                                lastUpdate = now
                                serviceScope.launch(Dispatchers.IO) {
                                    db.downloadDao().insert(
                                        downloadingEntity.copy(
                                            progress = pct,
                                            sizeBytes = bytesCached
                                        )
                                    )
                                    updateNotification()
                                }
                            }
                        }
                    }
                )

                cacheWriter.cache()

                val finalCachedBytes = cache.getCachedBytes(videoId, 0, -1)
                db.downloadDao().insert(
                    downloadingEntity.copy(
                        status = "completed",
                        progress = 100,
                        sizeBytes = finalCachedBytes
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
                Log.d(TAG, "Download finished successfully: $videoId. Total cached bytes stored: $finalCachedBytes. Expected content length: $contentLength")

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $videoId")
                db.downloadDao().insert(downloadingEntity.copy(status = "failed", progress = 0))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading $videoId: ${e.message}", e)
                db.downloadDao().insert(downloadingEntity.copy(status = "failed"))
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
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildServiceNotification("All downloads complete or idle", 0))
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

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
