package com.vinmusic.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.recommendation.RecommendationRepository
import kotlinx.coroutines.*
import java.io.File

/**
 * Singleton that holds the ONE ExoPlayer instance shared between
 * PlayerViewModel and VinMusicService (for proper system notifications).
 * Also maintains play queue and track state to support background playback.
 */
@UnstableApi
object PlayerSingleton {
    private const val TAG = "VIN_PLAYER"

    @Volatile private var _player: ExoPlayer? = null

    /** The active MediaSession (set by VinMusicService) */
    @Volatile var mediaSession: MediaSession? = null

    val actionEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 10)

    // ── Shared Playback States (Process-alive, UI observable) ────────────────
    var currentSong    by mutableStateOf<VideoItem?>(null)
    var isPlaying      by mutableStateOf(false)
    var isLoading      by mutableStateOf(false)
    var errorMessage   by mutableStateOf<String?>(null)
    var queue          by mutableStateOf<List<VideoItem>>(emptyList())
    var queueIndex     by mutableIntStateOf(-1)
    var repeat         by mutableStateOf(false)
    var shuffle        by mutableStateOf(false)
    var smartAutoplayEnabled by mutableStateOf(true)
    var isAutoplayLoading by mutableStateOf(false)


    var nextStreamUrlDeferred: Pair<String, Deferred<String?>>? = null

    private var db: VinDatabase? = null
    private var recommendationRepository: RecommendationRepository? = null
    private var prefs: android.content.SharedPreferences? = null
    private var context: Context? = null

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireWakeLocks(ctx: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "VinMusic::TransitionWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max timeout
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null) {
                val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val lockType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    @Suppress("DEPRECATION")
                    android.net.wifi.WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wifiManager.createWifiLock(lockType, "VinMusic::TransitionWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    private fun releaseWakeLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WakeLock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var fetchJob: Job? = null
    private var prefetchCacheJob: Job? = null
    private var playStartTime: Long = 0L
    private var previousSongId: String? = null
    var hasLoggedCompleteForCurrent: Boolean = false

    var onSongEndedCallback: (() -> Boolean)? = null

    init {
        scope.launch {
            actionEvents.collect { action ->
                when (action) {
                    "NEXT" -> playNext()
                    "PREV" -> playPrev()
                    "LIKE" -> currentSong?.let { toggleLike(it) }
                    "REPEAT" -> repeat = !repeat
                }
            }
        }
    }

    fun getOrCreate(context: Context): ExoPlayer {
        return _player ?: synchronized(this) {
            _player ?: run {
                val ctx = context.applicationContext
                this.context = ctx
                db = VinDatabase.getInstance(ctx)
                recommendationRepository = RecommendationRepository(ctx, db!!)
                prefs = ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                smartAutoplayEnabled = prefs?.getBoolean("smart_autoplay", true) ?: true

                
                buildPlayer(ctx).also { playerInstance ->
                    _player = playerInstance
                    setupPlayerListener(playerInstance)
                }
            }
        }
    }

    private fun setupPlayerListener(playerInstance: ExoPlayer) {
        playerInstance.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                Log.d(TAG, "isPlaying = $playing")
            }
            override fun onPlaybackStateChanged(state: Int) {
                val stateStr = when(state) {
                    Player.STATE_IDLE      -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY     -> "READY"
                    Player.STATE_ENDED     -> "ENDED"
                    else -> "UNKNOWN($state)"
                }
                Log.d(TAG, "PlaybackState = $stateStr")
                when (state) {
                    Player.STATE_READY     -> {
                        isLoading  = false
                        errorMessage = null
                        releaseWakeLocks()
                        prefetchNextSong()
                    }
                    Player.STATE_BUFFERING -> isLoading = true
                    Player.STATE_ENDED     -> onSongEnded()
                    Player.STATE_IDLE      -> releaseWakeLocks()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "unknown"
                val cause = error.cause?.message ?: "no cause"
                Log.e(TAG, "PlayerError: $msg | cause: $cause", error)
                isLoading    = false
                errorMessage = "[Error] $msg"
                playerInstance.stop()
                releaseWakeLocks()
            }
        })
    }

    private fun prefetchNextSong() {
        if (queue.isEmpty()) return
        
        scope.launch {
            try {
                // Determine next index and next song
                val nextIndex = if (shuffle) {
                    queue.indices.random()
                } else {
                    (queueIndex + 1) % queue.size
                }
                
                if (nextIndex !in queue.indices) return@launch
                var nextSong = queue[nextIndex]
                
                // If it's the last song in the queue and smart autoplay is enabled, prefetch recommendations
                if (queueIndex == queue.size - 1 && !repeat && smartAutoplayEnabled) {
                    val seedSong = currentSong ?: queue[queueIndex]
                    Log.d(TAG, "Prefetching autoplay recommendations for seed=${seedSong.videoId}")
                    val recommended = withContext(Dispatchers.IO) {
                        recommendationRepository?.getSongRadio(seedSong.videoId)
                    }
                    if (!recommended.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            val newQueue = queue.toMutableList()
                            val existingIds = newQueue.map { it.videoId }.toSet()
                            val uniqueRecs = recommended.filter { it.videoId !in existingIds }
                            if (uniqueRecs.isNotEmpty()) {
                                newQueue.addAll(uniqueRecs)
                                queue = newQueue
                                Log.d(TAG, "Autoplay recommendations appended to queue during prefetch")
                                
                                // Re-evaluate next song with newly appended items
                                val newNextIndex = if (shuffle) {
                                    queue.indices.random()
                                } else {
                                    (queueIndex + 1) % queue.size
                                }
                                if (newNextIndex in queue.indices) {
                                    nextSong = queue[newNextIndex]
                                }
                            }
                        }
                    }
                }
                
                // If we already have a prefetch running or completed for this next song, skip
                if (nextStreamUrlDeferred?.first == nextSong.videoId) {
                    Log.d(TAG, "Prefetch already active or complete for next song videoId=${nextSong.videoId}")
                    return@launch
                }
                
                // If it's already downloaded, skip network prefetching
                val isDownloaded = withContext(Dispatchers.IO) {
                    db?.downloadDao()?.get(nextSong.videoId)?.status == "completed"
                }
                if (isDownloaded) {
                    Log.d(TAG, "Next song ${nextSong.title} is downloaded. Skipping network prefetch.")
                    return@launch
                }
                
                Log.d(TAG, "Prefetching stream URL for next song: ${nextSong.title} (videoId=${nextSong.videoId})")
                val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                
                val deferred = async(Dispatchers.IO) {
                    try {
                        InnerTube.getStreamUrl(nextSong.videoId, quality)
                    } catch (e: Exception) {
                        Log.e(TAG, "Prefetch failed for videoId=${nextSong.videoId}: ${e.message}")
                        null
                    }
                }
                
                nextStreamUrlDeferred = Pair(nextSong.videoId, deferred)
                Log.d(TAG, "Prefetch successfully scheduled for videoId=${nextSong.videoId}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in prefetchNextSong: ${e.message}")
            }
        }
    }

    val player: ExoPlayer get() = requireNotNull(_player) { "Player not initialized — call getOrCreate first" }

    @Volatile private var _cache: androidx.media3.datasource.cache.SimpleCache? = null
    @Volatile private var _cacheFailed = false

    @Synchronized
    fun getCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
        if (_cacheFailed) return null
        return _cache ?: run {
            try {
                val cacheDir = File(context.cacheDir, "player_cache")
                val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024L * 1024L * 1024L) // 1 GB Cache
                val dbProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
                androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, dbProvider).also { _cache = it }
            } catch (e: Exception) {
                Log.e(TAG, "SimpleCache init failed: ${e.message}. Dynamic cache disabled.", e)
                _cacheFailed = true
                null
            }
        }
    }

    private fun buildPlayer(ctx: Context): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X) AppleWebKit/605.1.15")
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(mapOf(
                "Origin"  to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            ))

        val cacheObj = getCache(ctx)
        val dataSourceFactory = if (cacheObj != null) {
            Log.d(TAG, "ExoPlayer initialized with 1GB automatic cache")
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cacheObj)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            Log.d(TAG, "ExoPlayer initialized with standard direct streaming (no-cache fallback)")
            httpDataSourceFactory
        }

        return ExoPlayer.Builder(ctx)
            .setLooper(android.os.Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // Keep CPU awake for streaming in background

            .build()
    }

    // ── Queue & Playback Functions (Service/Background Safe) ─────────────────

    fun playSong(song: VideoItem) {
        context?.let { acquireWakeLocks(it) }
        fetchJob?.cancel()

        // Log end of previous song
        recordEnd()

        currentSong   = song
        isLoading     = true
        isPlaying     = false
        errorMessage  = null
        val prefetchedUrlDeferred = nextStreamUrlDeferred
        nextStreamUrlDeferred = null

        // Reset tracking for new song
        hasLoggedCompleteForCurrent = false
        previousSongId = song.videoId
        playStartTime = System.currentTimeMillis()
        recordPlay(song)

        val database = db ?: return

        // Save to history + Metrolist-style related cache for recommendations
        scope.launch(Dispatchers.IO) {
            database.historyDao().insert(HistoryEntry(song.videoId, song.title, song.author, null, song.durationText))
            recommendationRepository?.touchSongPlayMeta(song)
            recommendationRepository?.cacheRelatedForSong(song.videoId)
        }

        fetchJob = scope.launch {
            try {
                Log.d(TAG, "Fetching stream/download for videoId=${song.videoId}")

                // Check if song is downloaded in cache
                val localDownload = withContext(Dispatchers.IO) { database.downloadDao().get(song.videoId) }
                val isCachedComplete = localDownload?.status == "completed"

                val url: String
                val artBytes: ByteArray?

                if (isCachedComplete) {
                    Log.d(TAG, "Playing fully cached offline song: videoId=${song.videoId}")
                    url = if (!localDownload?.filePath.isNullOrBlank() && localDownload?.filePath != "cache") {
                        localDownload.filePath
                    } else {
                        "https://dummy.com/${song.videoId}.m4a"
                    }
                    artBytes = null

                    val artBytesDeferred = async(Dispatchers.IO) { InnerTube.loadThumbnailBytes(song.thumbnailHd) }
                    launch(Dispatchers.IO) {
                        val loadedBytes = runCatching { artBytesDeferred.await() }.getOrNull()
                        if (loadedBytes != null) {
                            withContext(Dispatchers.Main) {
                                val currentItem = player.currentMediaItem
                                if (currentItem != null && currentItem.mediaId == song.videoId) {
                                    val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                                        .setArtworkData(loadedBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                        .build()
                                    val updatedItem = currentItem.buildUpon()
                                        .setMediaMetadata(updatedMetadata)
                                        .build()
                                    player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                                }
                            }
                        }
                    }
                } else {
                    errorMessage = null
                    // Fetch stream URL and artwork bytes in parallel
                    val urlDeferred = if (prefetchedUrlDeferred != null && prefetchedUrlDeferred.first == song.videoId) {
                        prefetchedUrlDeferred.second
                    } else {
                        val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                        async(Dispatchers.IO) {
                            InnerTube.getStreamUrl(song.videoId, quality)
                        }
                    }
                    val artBytesDeferred = async(Dispatchers.IO) { InnerTube.loadThumbnailBytes(song.thumbnailHd) }

                    val fetchedUrl = urlDeferred.await()
                    if (fetchedUrl == null) {
                        isLoading    = false
                        errorMessage = "[Error] ${InnerTube.lastDebugMsg}"
                        Log.e(TAG, "Stream URL is NULL for ${song.videoId}")
                        return@launch
                    }
                    url = fetchedUrl
                    artBytes = if (artBytesDeferred.isCompleted) {
                        runCatching { artBytesDeferred.getCompleted() }.getOrNull()
                    } else {
                        null
                    }

                    // Asynchronously load artwork bytes in the background and update the player item without stopping playback
                    if (artBytes == null) {
                        launch(Dispatchers.IO) {
                            val loadedBytes = runCatching { artBytesDeferred.await() }.getOrNull()
                            if (loadedBytes != null) {
                                withContext(Dispatchers.Main) {
                                    val currentItem = player.currentMediaItem
                                    if (currentItem != null && currentItem.mediaId == song.videoId) {
                                        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
                                            .setArtworkData(loadedBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                            .build()
                                        val updatedItem = currentItem.buildUpon()
                                            .setMediaMetadata(updatedMetadata)
                                            .build()
                                        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
                                        Log.d(TAG, "Asynchronously updated notification artwork bytes")
                                    }
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    // Ensure service is running
                    try {
                        val ctx = context!!
                        val intent = android.content.Intent(ctx, VinMusicService::class.java)
                        ctx.startService(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start VinMusicService in playSong: ${e.message}")
                    }

                    val metaBuilder = MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.author)
                        .setArtworkUri(android.net.Uri.parse(song.thumbnailHd))
                    if (artBytes != null)
                        metaBuilder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)

                    val mediaItem = MediaItem.Builder()
                        .setMediaId(song.videoId)
                        .setUri(url)
                        .setCustomCacheKey(song.videoId)
                        .setMediaMetadata(metaBuilder.build())
                        .build()

                    Log.d(TAG, "Setting media item: ${url.take(80)}")
                    player.stop()
                    player.clearMediaItems()
                    
                    if (isCachedComplete) {
                        val cache = getCache(context!!)
                        if (cache != null) {
                            val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                                .setCache(cache)
                                .setUpstreamDataSourceFactory {
                                    object : androidx.media3.datasource.DataSource {
                                        private var uri: android.net.Uri? = null
                                        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                                        override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                                            uri = dataSpec.uri
                                            return 0L
                                        }
                                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                            return C.RESULT_END_OF_INPUT
                                        }
                                        override fun getUri(): android.net.Uri? = uri
                                        override fun close() {}
                                    }
                                }
                                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                            val mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                                .createMediaSource(mediaItem)
                            player.setMediaSource(mediaSource)
                        } else {
                            player.setMediaItem(mediaItem)
                        }
                    } else {
                        player.setMediaItem(mediaItem)
                    }
                    
                    player.prepare()
                    player.playWhenReady = true
                    errorMessage = null
                    prefetchNextSongs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error in playSong fetch: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMessage = "[Error] ${e.message ?: "Playback failed"}"
                    player.stop()
                    player.clearMediaItems()
                    releaseWakeLocks()
                }
            }
        }
    }

    fun setQueue(songs: List<VideoItem>, startIndex: Int = 0) {
        queue      = songs
        queueIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (songs.isNotEmpty()) playSong(songs[queueIndex])
    }

    fun playNext() {
        if (queue.isEmpty()) return
        
        // Spotify Radio Mode / Smart Autoplay
        if (queueIndex == queue.size - 1 && !repeat && smartAutoplayEnabled) {
            val seedSong = currentSong ?: queue[queueIndex]
            isAutoplayLoading = true
            scope.launch {
                try {
                    val recommended = recommendationRepository?.getSongRadio(seedSong.videoId)
                    if (!recommended.isNullOrEmpty()) {
                        val newQueue = queue.toMutableList()
                        newQueue.addAll(recommended)
                        queue = newQueue
                        
                        queueIndex++
                        withContext(Dispatchers.Main) {
                            playSong(queue[queueIndex])
                        }
                    } else {
                        val next = if (shuffle) queue.indices.random() else 0
                        queueIndex = next
                        playSong(queue[next])
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch autoplay recommendations: ${e.message}")
                    val next = if (shuffle) queue.indices.random() else 0
                    queueIndex = next
                    playSong(queue[next])
                } finally {
                    isAutoplayLoading = false
                }
            }
            return
        }

        val next = if (shuffle) queue.indices.random() else (queueIndex + 1) % queue.size
        queueIndex = next
        playSong(queue[next])
    }

    fun playPrev() {
        if (queue.isEmpty()) return
        if (player.currentPosition > 3000) { player.seekTo(0); return }
        val prev = if (queueIndex > 0) queueIndex - 1 else queue.size - 1
        queueIndex = prev
        playSong(queue[prev])
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(fraction: Float) {
        val duration = player.duration
        if (duration > 0) player.seekTo((fraction * duration).toLong())
    }

    fun seekToMs(ms: Long) {
        player.seekTo(ms)
    }

    fun setSmartAutoplay(enabled: Boolean) {
        smartAutoplayEnabled = enabled
        prefs?.edit()?.putBoolean("smart_autoplay", enabled)?.apply()
    }

    private fun prefetchNextSongs() {
        val ctx = context ?: return
        if (queue.isEmpty()) return
        
        prefetchCacheJob?.cancel()
        prefetchCacheJob = scope.launch(Dispatchers.IO) {
            try {
                // Wait a bit to let the active song start playing smoothly
                delay(3000)
                
                val nextIndex = if (shuffle) queue.indices.random() else (queueIndex + 1) % queue.size
                if (nextIndex !in queue.indices) return@launch
                val nextSong = queue[nextIndex]
                
                // If it is already downloaded, skip
                val localDownload = db?.downloadDao()?.get(nextSong.videoId)
                if (localDownload?.status == "completed") {
                    Log.d(TAG, "prefetchNextSongs: Next song is already downloaded offline. Skipping prefetch.")
                    return@launch
                }
                
                // Get stream URL (either by waiting for nextStreamUrlDeferred or fetching it)
                var streamUrl: String? = null
                val deferredPair = nextStreamUrlDeferred
                if (deferredPair != null && deferredPair.first == nextSong.videoId) {
                    Log.d(TAG, "prefetchNextSongs: Found active stream URL prefetch deferred. Waiting...")
                    streamUrl = deferredPair.second.await()
                } else {
                    Log.d(TAG, "prefetchNextSongs: Fetching stream URL for prefetch...")
                    val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                    streamUrl = InnerTube.getStreamUrl(nextSong.videoId, quality)
                }
                
                if (streamUrl.isNullOrBlank()) {
                    Log.d(TAG, "prefetchNextSongs: Next song stream URL is empty or null. Aborting.")
                    return@launch
                }
                
                val cache = getCache(ctx) ?: return@launch
                Log.d(TAG, "prefetchNextSongs: Starting prefetch of 2.5MB for song ${nextSong.title} (videoId=${nextSong.videoId})")
                
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("com.google.ios.youtube/19.09.3 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X) AppleWebKit/605.1.15")
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(30_000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(mapOf(
                        "Origin"  to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/"
                    ))
                
                val cacheDataSource = androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(httpFactory)
                    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource()
                
                val dataSpec = androidx.media3.datasource.DataSpec.Builder()
                    .setUri(android.net.Uri.parse(streamUrl))
                    .setPosition(0)
                    .setLength(2_500_000L) // 2.5MB (Approx. 2 mins of audio)
                    .setKey(nextSong.videoId)
                    .build()
                
                val cacheWriter = androidx.media3.datasource.cache.CacheWriter(
                    cacheDataSource,
                    dataSpec,
                    null, // temporaryBuffer
                    null  // progressListener
                )
                
                cacheWriter.cache()
                Log.d(TAG, "prefetchNextSongs: Successfully completed prefetch of 2.5MB for ${nextSong.title}")
            } catch (e: CancellationException) {
                Log.d(TAG, "prefetchNextSongs cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "prefetchNextSongs failed: ${e.message}", e)
            }
        }
    }



    private fun onSongEnded() {
        if (onSongEndedCallback?.invoke() == true) {
            return
        }
        when {
            repeat -> player.seekTo(0)
            else -> playNext()
        }
    }

    fun toggleLike(song: VideoItem) {
        val database = db ?: return
        scope.launch(Dispatchers.IO) {
            val isCurrentlyLiked = database.likedSongDao().getAll().any { it.videoId == song.videoId }
            val nextLiked = if (isCurrentlyLiked) {
                database.likedSongDao().delete(song.videoId)
                false
            } else {
                database.likedSongDao().insert(
                    LikedSong(song.videoId, song.title, song.author, song.durationText)
                )
                true
            }
            
            // Update interaction signal
            val sig = database.interactionSignalDao().get(song.videoId)
            if (sig != null) {
                sig.isLiked = nextLiked
                database.interactionSignalDao().insert(sig)
            } else {
                database.interactionSignalDao().insert(
                    InteractionSignal(
                        videoId = song.videoId,
                        title = song.title,
                        author = song.author,
                        durationText = song.durationText,
                        isLiked = nextLiked
                    )
                )
            }
        }
    }

    private fun recordPlay(song: VideoItem) {
        val database = db ?: return
        scope.launch(Dispatchers.IO) {
            val signal = database.interactionSignalDao().get(song.videoId) ?: InteractionSignal(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText
            )
            signal.playCount += 1
            signal.lastPlayedAt = System.currentTimeMillis()
            if (previousSongId == song.videoId) {
                signal.repeatCount += 1
            }
            database.interactionSignalDao().insert(signal)
        }
    }

    private fun recordEnd() {
        val database = db ?: return
        val prevId = previousSongId ?: return
        val playDuration = System.currentTimeMillis() - playStartTime
        scope.launch(Dispatchers.IO) {
            val signal = database.interactionSignalDao().get(prevId) ?: return@launch
            if (playDuration < 30_000 && !hasLoggedCompleteForCurrent) {
                signal.skipCount += 1
                if (playDuration < 20_000) {
                    signal.skip20sCount += 1
                }
                database.interactionSignalDao().insert(signal)
            }
        }
    }
}
