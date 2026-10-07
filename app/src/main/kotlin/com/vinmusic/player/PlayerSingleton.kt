package com.vinmusic.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
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
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.room.withTransaction
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.diagnostics.ReliabilityDiagnostics
import com.vinmusic.recommendation.RecommendationRepository
import kotlinx.coroutines.*
import java.io.File
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap

/**
 * Singleton that holds the ONE ExoPlayer instance shared between
 * PlayerViewModel and VinMusicService (for proper system notifications).
 * Also maintains play queue and track state to support background playback.
 */
@UnstableApi
object PlayerSingleton {
    private const val TAG = "VIN_PLAYER"
    internal const val STREAM_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"



    @Volatile private var _player: ExoPlayer? = null

    /** The active MediaSession (set by VinMusicService) */
    @Volatile var mediaSession: MediaSession? = null
    @Volatile var notificationMediaItem: MediaItem? = null
    @Volatile var currentSongLikedForNotification: Boolean = false
    @Volatile var onNotificationActionsChanged: (() -> Unit)? = null

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
    private var _smartShuffle by mutableStateOf(false)
    val smartShuffle get() = _smartShuffle
    var smartAutoplayEnabled by mutableStateOf(true)
    var isAutoplayLoading by mutableStateOf(false)
    private var isExtendingQueue = false

    // ── Autoplay tail tracking ────────────────────────────────────────────────
    // Tracks the unranked "fast path" radio tracks appended by playSongWithRadio.
    // When the ranked Smart Queue finishes loading it replaces exactly these
    // tracks, leaving anything the user queued manually untouched.
    @Volatile
    var autoplayTailStartIndex: Int = -1
        private set
    val autoplayTailVideoIds: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    fun clearAutoplayTail() {
        autoplayTailStartIndex = -1
        autoplayTailVideoIds.clear()
    }

    /** Records fast-path radio tracks so Smart Queue can replace them later. */
    fun markAutoplayTail(startIndex: Int, videoIds: List<String>) {
        autoplayTailStartIndex = startIndex
        autoplayTailVideoIds.addAll(videoIds)
    }

    /**
     * Swaps the unranked fast-path radio tracks for ranked Smart Queue results.
     * Only IDs recorded via [autoplayTailVideoIds] are removed, so songs the
     * user added ("Play next" / "Add to queue") survive the replacement. When no
     * tail was tracked this behaves as a plain append after the current song.
     */
    fun replaceAutoplayTail(rankedRecommendations: List<VideoItem>) {
        val removed: Set<String> = synchronized(autoplayTailVideoIds) {
            val copy = autoplayTailVideoIds.toSet()
            autoplayTailVideoIds.clear()
            copy
        }
        autoplayTailStartIndex = -1

        val currentQueue = queue.toMutableList()
        // Never drop the track the user has already skipped onto mid-tail.
        val currentId = currentSong?.videoId
        val effectiveRemoved = if (currentId != null) removed - currentId else removed
        if (effectiveRemoved.isNotEmpty()) currentQueue.removeAll { it.videoId in effectiveRemoved }
        val existingIds = currentQueue.map { it.videoId }.toSet()
        val uniqueRecs = rankedRecommendations
            .distinctBy { it.videoId }
            .filter { it.videoId !in existingIds }
        if (uniqueRecs.isEmpty()) {
            if (effectiveRemoved.isNotEmpty()) queue = currentQueue // tail removed but nothing to insert
            return
        }

        val currentIndex = currentQueue.indexOfFirst { it.videoId == currentSong?.videoId }
        val insertAt = (if (currentIndex >= 0) currentIndex + 1 else currentQueue.size)
            .coerceIn(0, currentQueue.size)
        queue = buildList {
            addAll(currentQueue.subList(0, insertAt))
            addAll(uniqueRecs)
            addAll(currentQueue.subList(insertAt, currentQueue.size))
        }
        // queueIndex follows the currently playing song; recompute after the splice
        val newIdx = queue.indexOfFirst { it.videoId == currentSong?.videoId }
        if (newIdx >= 0) queueIndex = newIdx
        // Clear stale prefetch from the old fast-path tail and prefetch the new upcoming track
        nextStreamUrlDeferred = null
        prefetchNextSong()
        prefetchNextSongs()  // Also cache media bytes to disk so 2nd song plays even if network drops
    }

    // Stored playback parameters — survive across song transitions
    var storedSpeed by mutableFloatStateOf(1.0f)
    var storedPitch by mutableFloatStateOf(1.0f)

    val eightDAudioProcessor = EightDAudioProcessor()
    val audioFeatureProcessor = AudioFeatureProcessor()
    var is8dEnabled by mutableStateOf(false)
        private set

    private var virtualizer: android.media.audiofx.Virtualizer? = null
    private var presetReverb: android.media.audiofx.PresetReverb? = null

    @Volatile
    var nextStreamUrlDeferred: Pair<String, Deferred<String?>>? = null

    private var db: VinDatabase? = null
    private var recommendationRepository: RecommendationRepository? = null
    private var prefs: android.content.SharedPreferences? = null
    internal var context: Context? = null

    // Dedicated transition WakeLock to keep CPU awake during network stream resolution between tracks when screen is OFF.
    // ExoPlayer's setWakeMode only keeps CPU awake while ExoPlayer is actively playing/buffering; between tracks when
    // ExoPlayer is stopped and Dispatchers.IO is fetching the stream URL, Linux kernel can suspend the CPU.
    private var transitionWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    private fun acquireTransitionWakeLock(ctx: Context) {
        try {
            if (transitionWakeLock == null) {
                val powerManager = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                transitionWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "VinMusic:TransitionWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            // 20-second safety timeout guarantees CPU will never stay awake indefinitely if network stalls
            transitionWakeLock?.acquire(20_000L)
            Log.d(TAG, "TransitionWakeLock acquired for background track switch")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire transition WakeLock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseTransitionWakeLock() {
        try {
            transitionWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "TransitionWakeLock released (ExoPlayer active)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release transition WakeLock: ${e.message}")
        }
    }

    @Synchronized
    private fun acquireWifiLock(ctx: Context) {
        try {
            if (wifiLock == null) {
                val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(mode, "VinMusic:WifiStreamingLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
                Log.d(TAG, "WifiStreamingLock acquired for low-latency background streaming")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock: ${e.message}")
        }
    }

    @Synchronized
    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiStreamingLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WifiLock: ${e.message}")
        }
    }

    private fun acquireWakeLocks(ctx: Context) {
        acquireTransitionWakeLock(ctx)
        acquireWifiLock(ctx)
    }

    private fun releaseWakeLocks() {
        releaseTransitionWakeLock()
        releaseWifiLock()
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled coroutine exception in PlayerSingleton", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + coroutineExceptionHandler)

    private var fetchJob: Job? = null
    private var errorRetryCount = 0
    private var prefetchCacheJob: Job? = null
    private var playStartTime: Long = 0L
    private var previousSongId: String? = null
    var hasLoggedCompleteForCurrent: Boolean = false

    var onSongEndedCallback: (() -> Boolean)? = null

    init {
        scope.launch {
            actionEvents.collect { action ->
                try {
                    when (action) {
                        "NEXT" -> playNext()
                        "PREV" -> playPrev()
                        "LIKE" -> currentSong?.let { toggleLike(it) }
                        "REPEAT" -> repeat = !repeat
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling action event '$action'", e)
                }
            }
        }
    }

    fun getOrCreate(context: Context): ExoPlayer {
        return _player ?: synchronized(this) {
            _player ?: run {
                val ctx = context.applicationContext
                this.context = ctx
                ReliabilityDiagnostics.init(ctx)
                val databaseInstance = VinDatabase.getInstance(ctx)
                db = databaseInstance
                val recDb = com.vinmusic.recommendation.RecommendationDatabase.getInstance(ctx)
                recommendationRepository = RecommendationRepository(ctx, databaseInstance, recDb)
                prefs = ctx.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                smartAutoplayEnabled = prefs?.getBoolean("smart_autoplay", true) ?: true
                _smartShuffle = prefs?.getBoolean("smart_shuffle", false) ?: false

                
                buildPlayer(ctx).also { playerInstance ->
                    _player = playerInstance
                    setupPlayerListener(playerInstance)
                    triggerOfflineSync()
                }
            }
        }
    }

    private fun setupPlayerListener(playerInstance: ExoPlayer) {
        playerInstance.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                Log.d(TAG, "isPlaying = $playing")
                if (playing) {
                    context?.let { acquireWakeLocks(it) }
                } else {
                    releaseWakeLocks()
                }
                context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
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
                        releaseTransitionWakeLock()
                        isLoading  = false
                        errorMessage = null
                        // Reset the per-error retry budget on a successful load so a
                        // couple of lifetime errors don't permanently disable retry.
                        errorRetryCount = 0
                        // Re-apply stored playback parameters after song loads
                        reapplyPlaybackParameters()
                        prefetchNextSong()
                        ReliabilityDiagnostics.record("playback", "ready", currentSong?.videoId, status = "ok")
                    }
                    Player.STATE_BUFFERING -> isLoading = true
                    Player.STATE_ENDED     -> onSongEnded()
                    Player.STATE_IDLE      -> releaseWakeLocks()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val msg = error.message ?: "unknown"
                val cause = error.cause?.message ?: "no cause"
                val httpCode = findHttpCode(error)
                Log.e(TAG, "PlayerError: $msg | cause: $cause", error)
                ReliabilityDiagnostics.record(
                    "playback", "error", currentSong?.videoId,
                    attempt = errorRetryCount + 1,
                    status = "failed", httpCode = httpCode,
                    error = msg, details = cause
                )
                
                val song = currentSong
                if (errorRetryCount < 2 && song != null) {
                    val pos = playerInstance.currentPosition.coerceAtLeast(0)
                    Log.d(TAG, "Retrying playback due to Source error... (retryCount=$errorRetryCount, pos=$pos)")
                    errorRetryCount++
                    scope.launch {
                        nextStreamUrlDeferred = null
                        val isAuthRejection = httpCode == 401 || httpCode == 403
                        if (isAuthRejection) {
                            // Evict rejected stream URL from in-memory cache so retry doesn't re-use it
                            com.vinmusic.innertube.InnerTube.invalidateStreamUrl(song.videoId)
                            // Force refresh the YouTube visitor token so challenges are cleared
                            com.vinmusic.innertube.InnerTube.ensureVisitorData(force = true)
                            // The online player cache is disposable. Sweep it on an
                            // authorization failure so an expired/partial signed URL
                            // cannot survive and force the user to clear app data.
                            runCatching { context?.let { clearPlayerCache(it, "http_$httpCode") } }
                            ReliabilityDiagnostics.record(
                                "playback", "cache_invalidate", song.videoId,
                                attempt = errorRetryCount, status = "ok", httpCode = httpCode
                            )
                        }
                        playSong(song, startPositionMs = pos, forceFresh = isAuthRejection)
                    }
                    return
                }
                
                isLoading    = false
                errorMessage = when (httpCode) {
                    401, 403 -> "YouTube rejected this stream. Tap to retry."
                    else -> "Playback failed. Check your connection and retry."
                }
                playerInstance.stop()
                releaseWakeLocks()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "onAudioSessionIdChanged = $audioSessionId")
                if (is8dEnabled) {
                    setupAudioEffects(audioSessionId)
                }
            }
        })
    }

    private fun prefetchNextSong() {
        if (queue.isEmpty()) return
        
        scope.launch {
            try {
                // Determine next index and next song
                val nextIndex = if (shuffle) {
                    if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                } else {
                    (queueIndex + 1) % queue.size
                }
                
                if (nextIndex !in queue.indices) return@launch
                var nextSong = queue[nextIndex]
                
                // Lazy Queue Extension: When user is within 5 songs of queue end, dynamically fetch and append 10 more recommendations
                if (queue.size - queueIndex <= 5 && !repeat && smartAutoplayEnabled && !isExtendingQueue) {
                    isExtendingQueue = true
                    try {
                        val seedSong = currentSong ?: queue[queueIndex]
                        Log.d(TAG, "Lazy queue expansion triggered (queue size=${queue.size}, index=$queueIndex) for seed=${seedSong.videoId}")
                        val currentQueueCopy = queue.toList()
                        val recommended = withContext(Dispatchers.IO) {
                            try {
                                recommendationRepository?.getSongRadio(seedSong.videoId, seedSong.title, seedSong.author, currentQueueCopy)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (!recommended.isNullOrEmpty()) {
                            withContext(Dispatchers.Main) {
                                val newQueue = queue.toMutableList()
                                val existingIds = newQueue.map { it.videoId }.toSet()
                                val uniqueRecs = recommended.filter { it.videoId !in existingIds }.take(10)
                                if (uniqueRecs.isNotEmpty()) {
                                    newQueue.addAll(uniqueRecs)
                                    queue = newQueue
                                    Log.d(TAG, "SmartQueue lazily extended: appended ${uniqueRecs.size} tracks, new total=${queue.size}")

                                    // Re-evaluate next song with newly appended items
                                    val newNextIndex = if (shuffle) {
                                        if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                                    } else {
                                        (queueIndex + 1) % queue.size
                                    }
                                    if (newNextIndex in queue.indices) {
                                        nextSong = queue[newNextIndex]
                                    }
                                }
                            }
                        }
                    } finally {
                        // Without this, an exception mid-extension permanently disables lazy queue extension.
                        isExtendingQueue = false
                    }
                }
                
                // If we already have a prefetch running or completed for this next song, skip
                if (nextStreamUrlDeferred?.first == nextSong.videoId) {
                    Log.d(TAG, "Prefetch already active or complete for next song videoId=${nextSong.videoId}")
                    return@launch
                }
                
                // Verify actual cached bytes exist before skipping network prefetching!
                val isDownloaded = withContext(Dispatchers.IO) {
                    val ctx = context ?: return@withContext false
                    val isComplete = db?.downloadDao()?.get(nextSong.videoId)?.status == "completed"
                    if (isComplete) {
                        val dlCache = getDownloadCache(ctx)
                        val dlCacheBytes = dlCache?.getCachedBytes(nextSong.videoId, 0, -1) ?: 0L
                        dlCacheBytes > 100_000L
                    } else {
                        false
                    }
                }
                if (isDownloaded) {
                    Log.d(TAG, "Next song ${nextSong.title} is downloaded and cache is valid. Skipping network prefetch.")
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
    @Volatile private var _dbProvider: androidx.media3.database.DatabaseProvider? = null

    @Synchronized
    private fun getDatabaseProvider(context: Context): androidx.media3.database.DatabaseProvider {
        return _dbProvider ?: androidx.media3.database.StandaloneDatabaseProvider(context.applicationContext).also { _dbProvider = it }
    }

    @Synchronized
    fun getCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
        if (_cacheFailed) return null
        return _cache ?: run {
            try {
                val cacheDir = File(context.cacheDir, "player_cache")
                val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(1024L * 1024L * 1024L) // 1 GB Cache
                val dbProvider = getDatabaseProvider(context)
                androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, dbProvider).also { _cache = it }
            } catch (e: Exception) {
                Log.e(TAG, "SimpleCache init failed: ${e.message}. Dynamic cache disabled.", e)
                _cacheFailed = true
                null
            }
        }
    }

    @Volatile private var _downloadCache: androidx.media3.datasource.cache.SimpleCache? = null
    @Volatile private var _downloadCacheFailed = false

    @Synchronized
    fun getDownloadCache(context: Context): androidx.media3.datasource.cache.SimpleCache? {
        if (_downloadCacheFailed) return null
        return _downloadCache ?: run {
            try {
                val downloadCacheDir = File(context.filesDir, "downloads")
                val evictor = androidx.media3.datasource.cache.NoOpCacheEvictor()
                val dbProvider = getDatabaseProvider(context)
                androidx.media3.datasource.cache.SimpleCache(downloadCacheDir, evictor, dbProvider).also { _downloadCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "SimpleCache downloads init failed: ${e.message}.", e)
                _downloadCacheFailed = true
                null
            }
        }
    }

    /** Clear only the disposable online player cache; downloaded songs are separate. */
    private suspend fun clearPlayerCache(context: Context, reason: String) {
        // Disk I/O — must leave the Main dispatcher; this runs during 403 recovery
        // when playback is already struggling, so jank here compounds the failure.
        withContext(Dispatchers.IO) {
            val cache = getCache(context) ?: return@withContext
            val keys = runCatching { cache.keys.toList() }.getOrDefault(emptyList())
            keys.forEach { key -> runCatching { cache.removeResource(key) } }
            Log.w(TAG, "Cleared player cache after $reason (${keys.size} entries)")
        }
        ReliabilityDiagnostics.record(
            "playback", "cache_clear", currentSong?.videoId,
            status = "ok", details = reason
        )
    }

    private fun createDynamicHttpDataSourceFactory(): androidx.media3.datasource.DataSource.Factory {
        return androidx.media3.datasource.DataSource.Factory {
            object : androidx.media3.datasource.DataSource {
                private var currentDataSource: androidx.media3.datasource.DataSource? = null
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    // Match the User-Agent to the client that generated the stream URL.
                    // YouTube CDN validates the UA against the `c=` param in the URL.
                    val urlStr = dataSpec.uri.toString()
                    val resolvedUa = com.vinmusic.innertube.InnerTube.getUserAgentForUrl(urlStr)
                    val isNativeClient = urlStr.contains("c=IOS") ||
                        urlStr.contains("c=ANDROID") ||
                        urlStr.contains("c=VISIONOS") ||
                        urlStr.contains("cps=1021")
                    val requestProps = mutableMapOf(
                        "Accept-Encoding" to "identity"
                    )
                    if (!isNativeClient) {
                        requestProps["Origin"] = "https://www.youtube.com"
                        requestProps["Referer"] = "https://www.youtube.com/"
                    }
                    val source = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setUserAgent(resolvedUa)
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(30_000)
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(requestProps)
                        .createDataSource()
                    currentDataSource = source
                    return source.open(dataSpec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    return currentDataSource?.read(buffer, offset, length) ?: -1
                }
                override fun getUri(): android.net.Uri? = currentDataSource?.uri
                override fun close() {
                    currentDataSource?.close()
                }
            }
        }
    }

    private fun findHttpCode(error: Throwable): Int? {
        var current: Throwable? = error
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
        }
        return null
    }

    private fun buildPlayer(ctx: Context): ExoPlayer {
        val dynamicHttpFactory = createDynamicHttpDataSourceFactory()
        val cacheObj = getCache(ctx)
        val dataSourceFactory = if (cacheObj != null) {
            Log.d(TAG, "ExoPlayer initialized with 1GB automatic cache")
            androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(cacheObj)
                .setUpstreamDataSourceFactory(dynamicHttpFactory)
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            Log.d(TAG, "ExoPlayer initialized with standard direct streaming (no-cache fallback)")
            dynamicHttpFactory
        }

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(ctx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(eightDAudioProcessor, audioFeatureProcessor))
                    .build()
            }
        }

        return ExoPlayer.Builder(ctx, renderersFactory)
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
            .build().apply {
                skipSilenceEnabled = true // Enable gapless/skip silence
            }
    }

    // ── Queue & Playback Functions (Service/Background Safe) ─────────────────

    private fun buildMediaMetadata(song: VideoItem, artBytes: ByteArray? = null): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.author)

        if (artBytes != null) {
            builder.setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            val artUrl = song.thumbnailHd.takeIf { it.isNotBlank() } ?: song.thumbnail
            if (artUrl.isNotBlank()) {
                builder.setArtworkUri(android.net.Uri.parse(artUrl))
            }
        }

        return builder.build()
    }

    private fun buildMediaItem(song: VideoItem, url: String? = null, artBytes: ByteArray? = null): MediaItem {
        val builder = MediaItem.Builder()
            .setMediaId(song.videoId)
            .setMediaMetadata(buildMediaMetadata(song, artBytes))

        if (url != null) {
            builder
                .setUri(url)
                .setCustomCacheKey(song.videoId)
        }

        return builder.build()
    }

    // ── Artwork Helpers ──────────────────────────────────────────────────────────

    /**
     * Try to load artwork bytes from Coil's disk cache.
     * Coil caches thumbnails when they're loaded in the UI, so this avoids
     * a network round-trip for songs the user has already seen.
     */
    private suspend fun loadArtBytesFromCoilCache(ctx: Context, videoId: String, thumbnail: String, thumbnailHd: String): ByteArray? {
        return try {
            val loader = SingletonImageLoader.get(ctx)
            // Try HD first, then standard
            val urls = listOfNotNull(
                thumbnailHd.takeIf { it.isNotBlank() },
                thumbnail.takeIf { it.isNotBlank() },
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
            for (url in urls) {
                val request = ImageRequest.Builder(ctx)
                    .data(url)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = result.image.toBitmap()
                    val stream = java.io.ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    return stream.toByteArray()
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Coil cache artwork load failed: ${e.message}")
            null
        }
    }

    /**
     * Save artwork bytes to local thumbnail file for future instant loading
     * (both online and offline playback).
     */
    private fun saveArtBytesToLocal(ctx: Context?, videoId: String, artBytes: ByteArray) {
        if (ctx == null || artBytes.isEmpty()) return
        try {
            val thumbnailDir = File(ctx.filesDir, "thumbnails")
            if (!thumbnailDir.exists()) thumbnailDir.mkdirs()
            val thumbnailFile = File(thumbnailDir, "$videoId.jpg")
            if (!thumbnailFile.exists() || thumbnailFile.length() == 0L) {
                thumbnailFile.writeBytes(artBytes)
                Log.d(TAG, "Saved artwork to local thumbnail file: ${thumbnailFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save artwork locally: ${e.message}")
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun playSong(song: VideoItem, startPositionMs: Long = 0L, forceFresh: Boolean = false) {
        ReliabilityDiagnostics.record("playback", "start", song.videoId, status = "started")
        context?.let { acquireWakeLocks(it) }
        fetchJob?.cancel()
        val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)

        // Log end of previous song
        recordEnd()

        currentSong   = song
        currentSongLikedForNotification = false
        onNotificationActionsChanged?.invoke()
        context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
        isLoading     = true
        isPlaying     = false
        errorMessage  = null
        val prefetchedUrlDeferred = nextStreamUrlDeferred
        nextStreamUrlDeferred = null

        notificationMediaItem = buildMediaItem(song)



        // Reset tracking for new song
        hasLoggedCompleteForCurrent = false
        previousSongId = song.videoId
        playStartTime = System.currentTimeMillis()
        recordPlay(song)
        checkAndClaimFeatures(song)

        val database = db ?: run {
            // Without this reset the spinner set above would spin forever.
            isLoading = false
            errorMessage = "Player is still starting. Try again in a moment."
            return
        }

        scope.launch(Dispatchers.IO) {
            val liked = database.likedSongDao().isLiked(song.videoId)
            withContext(Dispatchers.Main) {
                if (currentSong?.videoId == song.videoId) {
                    currentSongLikedForNotification = liked
                    onNotificationActionsChanged?.invoke()
                }
            }
        }

        // Save to history + Metrolist-style related cache for recommendations
        scope.launch(Dispatchers.IO) {
            database.historyDao().insert(
                HistoryEntry(
                    videoId = song.videoId,
                    title = song.title,
                    author = song.author,
                    genre = null,
                    durationText = song.durationText
                )
            )
            recommendationRepository?.touchSongPlayMeta(song)
            recommendationRepository?.cacheRelatedForSong(song.videoId)
        }

        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching stream/download for videoId=${song.videoId}")
                ReliabilityDiagnostics.record("playback", "resolve_start", song.videoId, status = "started")

                // 1. Check cache on Dispatchers.IO
                val cacheResult = run {
                    val ctx = context ?: return@run PlayerCacheManager.CacheCheckResult(false, false, false, 0L, false)
                    PlayerCacheManager.checkCacheStatus(ctx, database, song.videoId)
                }

                val isDownloadCacheValid = cacheResult.isDownloadCacheValid
                val isPlayerCached = cacheResult.isPlayerCached
                var isCachedComplete = cacheResult.isCachedComplete
                var totalCachedBytes = cacheResult.totalCachedBytes
                val isDeviceOnline = cacheResult.isDeviceOnline

                Log.d(TAG, "Cache check: isDownloadCacheValid=$isDownloadCacheValid isPlayerCached=$isPlayerCached isCachedComplete=$isCachedComplete totalCachedBytes=$totalCachedBytes isDeviceOnline=$isDeviceOnline")

                var url: String
                var artBytes: ByteArray? = null
                var onlineAndCached = isCachedComplete && isDeviceOnline

                // Load artwork bytes locally first on IO thread.
                val ctx = context
                if (ctx != null) {
                    try {
                        val database = com.vinmusic.data.db.VinDatabase.getInstance(ctx)
                        val dl = database.downloadDao().get(song.videoId)
                        if (dl?.thumbnailPath != null) {
                            val file = java.io.File(dl.thumbnailPath)
                            if (file.exists() && file.length() > 0) artBytes = file.readBytes()
                        }
                        if (artBytes == null) {
                            val cachePath = java.io.File(ctx.filesDir, "thumbnails/${song.videoId}.jpg")
                            if (cachePath.exists() && cachePath.length() > 0) artBytes = cachePath.readBytes()
                        }
                        if (artBytes == null) {
                            artBytes = loadArtBytesFromCoilCache(ctx, song.videoId, song.thumbnail, song.thumbnailHd)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Local artwork load failed: ${e.message}")
                    }
                }

                // Update notification with artwork bytes if we found them locally
                if (artBytes != null) {
                    withContext(Dispatchers.Main) {
                        if (currentSong?.videoId == song.videoId) {
                            notificationMediaItem = buildMediaItem(song, null, artBytes)
                        }
                    }
                    if (ctx != null) {
                        saveArtBytesToLocal(ctx, song.videoId, artBytes)
                    }
                }

                // 0. Local Device Audio (0ms instant playback via ContentResolver)
                if (song.localUriString != null || song.videoId.startsWith("local_") || song.videoId.startsWith("content://") || song.videoId.startsWith("file://")) {
                    // A bare `local_<id>` is not a playable URI — rebuild the MediaStore URI.
                    val localUri = song.localUriString ?: when {
                        song.videoId.startsWith("local_") -> {
                            val mediaStoreId = song.videoId.removePrefix("local_").toLongOrNull()
                            if (mediaStoreId != null) {
                                android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                    mediaStoreId
                                ).toString()
                            } else song.videoId
                        }
                        else -> song.videoId
                    }
                    Log.d(TAG, "Playing local device audio track directly: $localUri")
                    url = localUri
                    onlineAndCached = false
                } else if (isDownloadCacheValid) {
                    Log.d(TAG, "Playing fully downloaded song instantly from local cache: videoId=${song.videoId}")
                    url = "https://music.youtube.com/cache/${song.videoId}"
                    onlineAndCached = false
                } else if (!isDeviceOnline && isPlayerCached) {
                    Log.d(TAG, "Device offline: Playing cached bytes from PlayerCache: videoId=${song.videoId}")
                    url = "https://music.youtube.com/cache/${song.videoId}"
                    onlineAndCached = false
                } else {
                    withContext(Dispatchers.Main) { errorMessage = null }
                    // Fetch stream URL and artwork bytes in parallel
                    var fetchedUrl: String? = null
                    
                    if (!forceFresh && prefetchedUrlDeferred != null && prefetchedUrlDeferred.first == song.videoId) {
                        fetchedUrl = prefetchedUrlDeferred.second.await()
                        ReliabilityDiagnostics.record(
                            "playback", "resolve_prefetch", song.videoId,
                            status = if (fetchedUrl != null) "ok" else "empty"
                        )
                    }
                    
                    if (fetchedUrl == null) {
                        val quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)"
                        // Retry up to 2 times with 1-second delay for resilient playback
                        for (attempt in 1..2) {
                            ReliabilityDiagnostics.record("playback", "resolve_attempt", song.videoId, attempt = attempt, status = "started")
                            fetchedUrl = try {
                                InnerTube.getStreamUrl(song.videoId, quality, forceFresh = forceFresh)
                            } catch (e: Exception) {
                                Log.e(TAG, "Stream URL fetch attempt $attempt failed: ${e.message}")
                                null
                            }
                            ReliabilityDiagnostics.record(
                                "playback", "resolve_attempt", song.videoId,
                                attempt = attempt, status = if (fetchedUrl != null) "ok" else "empty"
                            )
                            if (fetchedUrl != null) break
                            if (attempt < 2) {
                                Log.d(TAG, "Retrying stream URL fetch in 1s (attempt ${attempt + 1}/2)...")
                                delay(1000)
                            }
                        }
                    }
                    
                    // Graceful Fallback: If network stream fetch fails but song is cached, fall back to offline playback instead of failing!
                    if (fetchedUrl == null && isCachedComplete) {
                        Log.w(TAG, "Online stream fetch failed for cached song. Falling back to local offline playback.")
                        onlineAndCached = false
                        fetchedUrl = "https://music.youtube.com/cache/${song.videoId}"
                    }

                    if (fetchedUrl == null) {
                        withContext(Dispatchers.Main) {
                            isLoading    = false
                            errorMessage = "Couldn't load this track. Check your connection and try again."
                        }
                        Log.e(TAG, "Stream URL is NULL for ${song.videoId} | ${InnerTube.lastDebugMsg}")
                        ReliabilityDiagnostics.record("playback", "resolve_end", song.videoId, status = "failed", error = "stream_url_null", details = InnerTube.lastDebugMsg)
                        return@launch
                    }
                    url = fetchedUrl
                }

                // Resolve SimpleCache on IO
                val resolvedCache = if (isCachedComplete) {
                    val c = context
                    if (c != null) {
                        if (isDownloadCacheValid) getDownloadCache(c) else getCache(c)
                    } else null
                } else null

                withContext(Dispatchers.Main) {
                    val ctx = context ?: return@withContext
                    // Ensure service is running
                    try {
                        val intent = android.content.Intent(ctx, VinMusicService::class.java)
                        ctx.startService(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start VinMusicService in playSong: ${e.message}")
                    }

                    val mediaItem = buildMediaItem(song, url, artBytes)
                    notificationMediaItem = mediaItem

                    Log.d(TAG, "Setting media source: ${url.take(80)}")
                    ReliabilityDiagnostics.record("playback", "prepare", song.videoId, status = "started", details = if (onlineAndCached) "online_cached" else "network_or_download")
                    player.playWhenReady = true
                    
                    // ALWAYS use custom MediaSource with dynamic UA factory to match the client that generated the stream URL
        val dynamicHttpFactory = createDynamicHttpDataSourceFactory()
        val mediaSource: androidx.media3.exoplayer.source.MediaSource = if (url.startsWith("content://") || url.startsWith("file://")) {
            Log.d(TAG, "Playing local media content URI with DefaultDataSource: $url")
            val defaultFactory = androidx.media3.datasource.DefaultDataSource.Factory(ctx)
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(defaultFactory)
                .createMediaSource(mediaItem)
        } else if (isCachedComplete) {
            val cache = resolvedCache
            if (cache != null) {
                if (onlineAndCached) {
                    Log.d(TAG, "Online Cached playback: prioritizing local cache with network fallback for videoId=${song.videoId}")
                    val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(dynamicHttpFactory) // Dynamic UA for network fallback!
                        .setCacheKeyFactory { dataSpec ->
                            dataSpec.key ?: song.videoId
                        }
                        .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                        .createMediaSource(mediaItem)
                } else {
                    Log.d(TAG, "Offline playback: using cache=${if (isDownloadCacheValid) "download" else "player"}, totalCachedBytes=$totalCachedBytes for videoId=${song.videoId}")
                    val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                        .setCache(cache)
                        .setCacheKeyFactory { dataSpec ->
                            dataSpec.key ?: song.videoId
                        }
                        .setUpstreamDataSourceFactory {
                            // Fixed upstream that reports bytesRemaining correctly relative to requested position
                            object : androidx.media3.datasource.DataSource {
                                private var uri: android.net.Uri? = null
                                private var bytesRemaining = 0L

                                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {}
                                
                                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                                    uri = dataSpec.uri
                                    val position = dataSpec.position
                                    bytesRemaining = if (totalCachedBytes > position) {
                                        totalCachedBytes - position
                                    } else {
                                        0L
                                    }
                                    Log.d(TAG, "Offline upstream open: position=$position, totalCachedBytes=$totalCachedBytes, bytesRemaining=$bytesRemaining")
                                    return bytesRemaining
                                }
                                
                                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                    if (bytesRemaining <= 0) {
                                        return androidx.media3.common.C.RESULT_END_OF_INPUT
                                    }
                                    Log.w(TAG, "Offline cache miss at remaining=$bytesRemaining, requesting length=$length")
                                    throw java.io.IOException("Offline cache miss: no bytes available at this position")
                                }
                                
                                override fun getUri(): android.net.Uri? = uri
                                override fun close() {}
                            }
                        }
                    androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                        .createMediaSource(mediaItem)
                }
            } else {
                // No cache available - use dynamic HTTP factory directly
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dynamicHttpFactory)
                    .createMediaSource(mediaItem)
            }
        } else {
            // Fresh network playback — route through player cache so prefetched
            // bytes from prefetchNextSongs() are used instead of re-downloading.
            val pCache = context?.let { getCache(it) }
            if (pCache != null) {
                val cacheFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                    .setCache(pCache)
                    .setUpstreamDataSourceFactory(dynamicHttpFactory)
                    .setCacheKeyFactory { dataSpec -> dataSpec.key ?: song.videoId }
                    .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(cacheFactory)
                    .createMediaSource(mediaItem)
            } else {
                androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(dynamicHttpFactory)
                    .createMediaSource(mediaItem)
            }
        }
        
        player.setMediaSource(mediaSource, safeStartPositionMs)
        player.prepare()
        player.playWhenReady = true
        errorMessage = null
        prefetchNextSongs()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Error in playSong fetch: ${e.message}", e)
                ReliabilityDiagnostics.record("playback", "exception", song.videoId, status = "failed", error = e.javaClass.simpleName, details = e.message)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    errorMessage = "Couldn't play this track. Check your connection and try again."
                    releaseWakeLocks()
                }
            }
        }
    }

    /**
     * Skip to a specific index in the existing queue without resetting it.
     * Unlike setQueue(), this preserves the queue, autoplay tail, and radioJob.
     */
    fun skipToIndex(index: Int) {
        if (index !in queue.indices) return
        if (index == queueIndex && currentSong?.videoId == queue[index].videoId) return
        queueIndex = index
        playSong(queue[index])
    }

    fun setQueue(songs: List<VideoItem>, startIndex: Int = 0) {
        clearAutoplayTail()
        nextStreamUrlDeferred = null
        queue      = songs
        queueIndex = startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
        if (songs.isNotEmpty()) playSong(songs[queueIndex])
    }

    fun playNextInQueue(song: VideoItem) {
        val currentQueue = queue.toMutableList()
        // Remove if already in queue to avoid duplicates
        currentQueue.removeAll { it.videoId == song.videoId }
        val insertAt = (queueIndex + 1).coerceIn(0, currentQueue.size)
        currentQueue.add(insertAt, song)
        queue = currentQueue
        // Adjust queueIndex if the current song shifted
        if (currentSong != null) {
            val newIdx = currentQueue.indexOfFirst { it.videoId == currentSong?.videoId }
            if (newIdx >= 0) queueIndex = newIdx
        }
        Log.d(TAG, "Play Next: inserted '${song.title}' at position ${insertAt + 1}")
    }

    fun addToEndOfQueue(song: VideoItem) {
        val currentQueue = queue.toMutableList()
        // Don't add duplicates
        if (currentQueue.any { it.videoId == song.videoId }) {
            Log.d(TAG, "Add to Queue: '${song.title}' already in queue")
            return
        }
        currentQueue.add(song)
        queue = currentQueue
        Log.d(TAG, "Add to Queue: appended '${song.title}' at position ${currentQueue.size}")
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        
        val mutableQueue = queue.toMutableList()
        val movedItem = mutableQueue.removeAt(fromIndex)
        mutableQueue.add(toIndex, movedItem)
        
        // Update queueIndex to follow the currently playing song
        val currentVideoId = currentSong?.videoId
        queue = mutableQueue
        if (currentVideoId != null) {
            val newIdx = mutableQueue.indexOfFirst { it.videoId == currentVideoId }
            if (newIdx >= 0) queueIndex = newIdx
        }
        Log.d(TAG, "Queue reorder: moved '${movedItem.title}' from ${fromIndex + 1} to ${toIndex + 1}")
    }

    fun playNext() {
        if (queue.isEmpty()) return
        
        // Spotify Radio Mode / Smart Autoplay
        if (queueIndex == queue.size - 1 && !repeat && smartAutoplayEnabled) {
            // Guard: prevent duplicate autoplay fetches on rapid "Next" taps
            if (isAutoplayLoading) return
            val seedSong = currentSong ?: queue[queueIndex]
            isAutoplayLoading = true
            scope.launch {
                try {
                    val currentQueueCopy = queue.toList()
                    val recommended = recommendationRepository?.getSongRadio(seedSong.videoId, seedSong.title, seedSong.author, currentQueueCopy)
                    if (!recommended.isNullOrEmpty()) {
                        val newQueue = queue.toMutableList()
                        val existingIds = newQueue.map { it.videoId }.toSet()
                        val uniqueRecs = recommended.filter { it.videoId !in existingIds }
                        
                        if (uniqueRecs.isNotEmpty()) {
                            newQueue.addAll(uniqueRecs)
                        }
                        queue = newQueue
                        
                        queueIndex++
                        withContext(Dispatchers.Main) {
                            playSong(queue[queueIndex])
                        }
                    } else {
                        // Online recommendations empty — try local fallback
                        val localFallback = getLocalQueueFallback()
                        if (localFallback.isNotEmpty()) {
                            val newQueue = queue.toMutableList()
                            newQueue.addAll(localFallback)
                            queue = newQueue
                            Log.d(TAG, "Queue auto-filled with ${localFallback.size} songs from local history")
                            queueIndex++
                            withContext(Dispatchers.Main) { playSong(queue[queueIndex]) }
                        } else if (!repeat && queueIndex == queue.size - 1) {
                            withContext(Dispatchers.Main) { _player?.pause() }
                            return@launch
                        } else {
                            val next = if (shuffle) {
                                if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                            } else 0
                            if (next < 0 || next >= queue.size) return@launch
                            queueIndex = next
                            withContext(Dispatchers.Main) { playSong(queue[next]) }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to fetch autoplay recommendations: ${e.message}")
                    // Fallback to local history when online fails
                    val localFallback = getLocalQueueFallback()
                    if (localFallback.isNotEmpty()) {
                        val newQueue = queue.toMutableList()
                        newQueue.addAll(localFallback)
                        queue = newQueue
                        Log.d(TAG, "Queue auto-filled with ${localFallback.size} songs from local history (after error)")
                        queueIndex++
                        withContext(Dispatchers.Main) { playSong(queue[queueIndex]) }
                    } else if (!repeat && queueIndex == queue.size - 1) {
                        withContext(Dispatchers.Main) { _player?.pause() }
                        return@launch
                    } else {
                        val next = if (shuffle) {
                            if (smartShuffle) getSmartShuffleNextIndex() else queue.indices.random()
                        } else 0
                        if (next < 0 || next >= queue.size) return@launch
                        queueIndex = next
                        withContext(Dispatchers.Main) { playSong(queue[next]) }
                    }
                } finally {
                    isAutoplayLoading = false
                }
            }
            return
        }

        if (shuffle && smartShuffle) {
            scope.launch {
                val next = getSmartShuffleNextIndex()
                if (next < 0 || next >= queue.size) return@launch
                withContext(Dispatchers.Main) {
                    queueIndex = next
                    playSong(queue[next])
                }
            }
            return
        }

        val next = if (shuffle) queue.indices.random() else (queueIndex + 1) % queue.size
        if (next == queueIndex && queue.size == 1 && !repeat) {
            _player?.pause()
            return
        }
        queueIndex = next
        playSong(queue[next])
    }

    fun playPrev() {
        if (queue.isEmpty()) return
        val activePlayer = _player ?: return
        if (activePlayer.currentPosition > 3000) { activePlayer.seekTo(0); return }
        val prev = if (queueIndex > 0) queueIndex - 1 else queue.size - 1
        queueIndex = prev
        playSong(queue[prev])
    }

    fun togglePlay() {
        val activePlayer = _player ?: return
        if (activePlayer.isPlaying) activePlayer.pause() else activePlayer.play()
    }

    fun seekTo(fraction: Float) {
        val duration = player.duration
        if (duration > 0) player.seekTo((fraction * duration).toLong())
    }

    fun seekToMs(ms: Long) {
        player.seekTo(ms)
    }

    /** Re-apply stored speed/pitch after song transition so params don't reset */
    private fun reapplyPlaybackParameters() {
        if (storedSpeed == 1.0f && storedPitch == 1.0f) return // nothing to reapply
        try {
            player.playbackParameters = androidx.media3.common.PlaybackParameters(storedSpeed, storedPitch)
            Log.d(TAG, "Re-applied playback params: speed=$storedSpeed, pitch=$storedPitch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reapply playback parameters: ${e.message}")
        }
    }

    fun setSmartAutoplay(enabled: Boolean) {
        smartAutoplayEnabled = enabled
        prefs?.edit()?.putBoolean("smart_autoplay", enabled)?.apply()
    }

    fun setSmartShuffle(enabled: Boolean) {
        _smartShuffle = enabled
        prefs?.edit()?.putBoolean("smart_shuffle", enabled)?.apply()
    }

    private suspend fun getSmartShuffleNextIndex(): Int {
        val dbObj = db ?: return queue.indices.random()
        val profile = try {
            com.vinmusic.recommendation.RecommendationManager.buildTasteProfile(dbObj)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build taste profile for smart shuffle: ${e.message}")
            null
        }
        
        if (profile == null) {
            return queue.indices.random()
        }

        val dna = profile.tasteDNA
        
        val candidates = queue.indices.filter { it != queueIndex }
        if (candidates.isEmpty()) return if (repeat) queueIndex else -1

        val scoredCandidates = candidates.map { idx ->
            val item = queue[idx]
            val meta = com.vinmusic.recommendation.RecommendationManager.getCachedOrInferredMetadata(dbObj, item)
            val score = com.vinmusic.recommendation.RecommendationManager.calculateTasteSimilarity(meta, dna)
            idx to score
        }

        val sorted = scoredCandidates.sortedByDescending { it.second }
        
        // Pick randomly from the top 3 or top 30% of candidates (whichever is larger)
        val poolSize = maxOf(3, (sorted.size * 0.3).toInt()).coerceAtMost(sorted.size)
        val bestCandidates = sorted.take(poolSize)
        
        return bestCandidates.random().first
    }

    private fun prefetchNextSongs() {
        val ctx = context ?: return
        if (queue.size <= 1) return // Do not prefetch if only 1 song in queue!
        
        prefetchCacheJob?.cancel()
        
        val nextSongs = mutableListOf<VideoItem>()
        for (offset in 1..2) {
            val nextIndex = (queueIndex + offset) % queue.size
            if (nextIndex != queueIndex && nextIndex in queue.indices) {
                val candidate = queue[nextIndex]
                if (candidate.videoId != currentSong?.videoId && nextSongs.none { it.videoId == candidate.videoId }) {
                    nextSongs.add(candidate)
                }
            }
        }
        if (nextSongs.isEmpty()) return
        
        prefetchCacheJob = PlayerCacheManager.prefetchNextSongs(
            ctx = ctx,
            scope = scope,
            db = db,
            quality = prefs?.getString("streaming_quality", "High (256kbps)") ?: "High (256kbps)",
            nextSongs = nextSongs,
            nextStreamUrlDeferred = nextStreamUrlDeferred
        )
    }



    private suspend fun getLocalQueueFallback(): List<VideoItem> = withContext(Dispatchers.IO) {
        val database = db ?: return@withContext emptyList()
        val existingIds = queue.map { it.videoId }.toSet()
        val fallback = mutableListOf<VideoItem>()

        // 1. Most played songs from interaction signals
        try {
            val signals = database.interactionSignalDao().getAll()
                .sortedByDescending { it.playCount }
            for (sig in signals) {
                if (sig.videoId !in existingIds && fallback.none { it.videoId == sig.videoId }) {
                    fallback.add(buildFallbackItem(sig.videoId, sig.title, sig.author, sig.durationText))
                    if (fallback.size >= 20) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local fallback interactionSignals failed: ${e.message}")
        }

        // 2. Fill remaining from recent history
        if (fallback.size < 20) {
            try {
                val seenIds = existingIds + fallback.map { it.videoId }.toSet()
                val history = database.historyDao().getAllHistory()
                for (entry in history) {
                    if (entry.videoId !in seenIds && fallback.none { it.videoId == entry.videoId }) {
                        fallback.add(buildFallbackItem(entry.videoId, entry.title, entry.author, entry.durationText))
                        if (fallback.size >= 20) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Local fallback history failed: ${e.message}")
            }
        }

        fallback.shuffled()
    }

    /** Rebuild playable items for queue fallbacks — history/signals store bare
     *  `local_<mediaStoreId>` ids for local device tracks, and a bare id is not a
     *  playable URI (it used to fall through to the HTTP data source and fail). */
    private fun buildFallbackItem(videoId: String, title: String, author: String, durationText: String): VideoItem {
        val mediaStoreId = if (videoId.startsWith("local_")) videoId.removePrefix("local_").toLongOrNull() else null
        return if (mediaStoreId != null) {
            val uri = android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                mediaStoreId
            )
            VideoItem(videoId, title, author, durationText, localUriString = uri.toString())
        } else {
            VideoItem(videoId, title, author, durationText)
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
            val isCurrentlyLiked = database.likedSongDao().isLiked(song.videoId)
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
            val songKey = com.vinmusic.recommendation.RecommendationManager.generateSongKey(song.author, song.title)
            val realCache = database.songFeatureCacheDao().get(songKey)
            if (realCache != null) {
                val newLikedCount = (realCache.likedByCount + (if (nextLiked) 1 else -1)).coerceAtLeast(0)
                val updatedCache = realCache.copy(likedByCount = newLikedCount)
                database.songFeatureCacheDao().insert(updatedCache)
                Log.d(TAG, "Updated local SongFeatureCache likedByCount to $newLikedCount for $songKey")
            }
            
            context?.let { ctx ->
                if (PlayerCacheManager.isOnline(ctx)) {
                    try {
                        val docRef = com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("songs").document(songKey)
                        val incrementVal = if (nextLiked) 1L else -1L
                        docRef.set(
                            mapOf("likedByCount" to com.google.firebase.firestore.FieldValue.increment(incrementVal)),
                            com.google.firebase.firestore.SetOptions.merge()
                        )
                        Log.d(TAG, "Incremented Firestore likedByCount by $incrementVal for $songKey")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update likedByCount in Firestore: ${e.message}")
                    }
                }
            }

            withContext(Dispatchers.Main) {
                if (currentSong?.videoId == song.videoId) {
                    currentSongLikedForNotification = nextLiked
                    onNotificationActionsChanged?.invoke()
                    context?.let { com.vinmusic.widget.MusicWidgetProvider.updateAllWidgets(it) }
                }
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
            com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()

            // Log song playback event to Firebase Analytics
            context?.let { ctx ->
                val isOffline = database.downloadDao().get(song.videoId)?.status == "completed"
                com.vinmusic.analytics.AnalyticsHelper.logSongPlaybackStarted(
                    context = ctx,
                    videoId = song.videoId,
                    title = song.title,
                    artist = song.author,
                    isOffline = isOffline
                )
            }
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
                com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()
            }
        }
    }

    private fun parseDurationText(durationText: String?): Long {
        if (durationText.isNullOrBlank()) return 0L
        return try {
            val parts = durationText.split(":")
            var seconds = 0L
            for (part in parts) {
                seconds = seconds * 60 + part.toLong()
            }
            seconds * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    private suspend fun getLocalFeatures(database: VinDatabase, songKey: String): com.vinmusic.data.db.SongFeatureCache? {
        return try {
            database.songFeatureCacheDao().get(songKey)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get local features for $songKey", e)
            null
        }
    }

    fun onAnalysisCompleted(
        songKey: String,
        bpm: Float?,
        energy: Float,
        genreTags: List<String>,
        moodTags: List<String>,
        title: String,
        artist: String,
        hasRealTags: Boolean
    ) {
        val database = db ?: return
        
        scope.launch(Dispatchers.IO) {
            try {
                val gson = com.google.gson.Gson()
                val genreTagsJson = gson.toJson(genreTags)
                val moodTagsJson = gson.toJson(moodTags)
                
                val cacheEntry = com.vinmusic.data.db.SongFeatureCache(
                    songKey = songKey,
                    bpmReal = if (bpm != null && bpm in 40f..250f) bpm else 0f,
                    energyReal = energy,
                    genreTags = genreTagsJson,
                    moodTags = moodTagsJson,
                    title = title,
                    artist = artist,
                    synced = false
                )
                database.songFeatureCacheDao().insert(cacheEntry)
                Log.d(TAG, "Saved features locally to Room for $songKey (hasRealTags=$hasRealTags)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete analysis write for $songKey", e)
            }
        }
    }

    fun triggerOfflineSync() {
        val database = db ?: return
        val ctx = context ?: return
        val isAuthenticated = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
        if (!PlayerCacheManager.isOnline(ctx) || !isAuthenticated) return
        
        scope.launch(Dispatchers.IO) {
            try {
                val unsynced = database.songFeatureCacheDao().getUnsynced()
                if (unsynced.isEmpty()) return@launch
                
                Log.d(TAG, "Found ${unsynced.size} unsynced audio features. Starting sync...")
                val gson = com.google.gson.Gson()
                for (item in unsynced) {
                    val genreType = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                    var genres: List<String> = gson.fromJson(item.genreTags, genreType) ?: emptyList()
                    var moods: List<String> = gson.fromJson(item.moodTags, genreType) ?: emptyList()
                    var hasRealTags = false
                    
                    // Re-fetch real tags from Last.fm since we are online now
                    try {
                        val realTags = fetchLastFmTagsLocally(item.artist, item.title)
                        if (realTags != null) {
                            val realGenres = realTags["genres"] ?: emptyList()
                            val realMoods = realTags["moods"] ?: emptyList()
                            if (realGenres.isNotEmpty() || realMoods.isNotEmpty()) {
                                genres = realGenres
                                moods = realMoods
                                hasRealTags = true
                                // Update local Room database with real tags
                                val updatedItem = item.copy(
                                    genreTags = gson.toJson(realGenres),
                                    moodTags = gson.toJson(realMoods)
                                )
                                database.songFeatureCacheDao().insert(updatedItem)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-fetch Last.fm tags during sync for ${item.songKey}: ${e.message}")
                    }
                    
                    if (hasRealTags) {
                        database.songFeatureCacheDao().markSynced(item.songKey)
                        Log.d(TAG, "Synced offline feature cache entry for ${item.songKey}")
                    } else {
                        Log.d(TAG, "Sync skipped for ${item.songKey} because real tags could not be fetched from Last.fm (remains unsynced)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Offline sync failed: ${e.message}")
            }
        }
    }

    /** Local Last.fm tag fetch (no Firestore) */
    internal suspend fun fetchLastFmTagsLocally(artist: String, title: String): Map<String, List<String>>? {
        val apiKey = com.vinmusic.config.RemoteConfigHelper.getLastFmApiKey()
        if (apiKey.isBlank()) return null
        val urlString = "https://ws.audioscrobbler.com/2.0/?method=track.gettoptags" +
                "&artist=${java.net.URLEncoder.encode(artist, "UTF-8")}" +
                "&track=${java.net.URLEncoder.encode(title, "UTF-8")}" +
                "&api_key=$apiKey" +
                "&format=json"
        
        return try {
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = com.google.gson.JsonParser.parseString(responseText).asJsonObject
                val toptags = responseJson.getAsJsonObject("toptags")
                if (toptags != null) {
                    val tagArray = toptags.getAsJsonArray("tag")
                    if (tagArray != null) {
                        val rawTags = tagArray.map { it.asJsonObject.get("name").asString }
                        val result = normalizeTagsLocally(rawTags)
                        if (result["genres"]?.isNotEmpty() == true || result["moods"]?.isNotEmpty() == true) {
                            return result
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch tags from Last.fm locally: ${e.message}")
            null
        }
    }

    internal fun normalizeTagsLocally(rawTags: List<String>): Map<String, List<String>> {
        val genres = mutableSetOf<String>()
        val moods = mutableSetOf<String>()
        
        val genreMappings = mapOf(
            "lofi" to "Lofi", "lo-fi" to "Lofi",
            "rap" to "Rap/Hip-Hop", "hip hop" to "Rap/Hip-Hop", "hip-hop" to "Rap/Hip-Hop", "hiphop" to "Rap/Hip-Hop", "trap" to "Rap/Hip-Hop",
            "bollywood" to "Bollywood", "hindi" to "Bollywood",
            "punjabi" to "Punjabi Folk", "bhangra" to "Punjabi Folk",
            "pop" to "Pop", "dance" to "Pop",
            "indie" to "Indie", "acoustic" to "Indie", "singer-songwriter" to "Indie",
            "rock" to "Rock", "metal" to "Rock", "grunge" to "Rock", "alternative rock" to "Rock"
        )
        
        val moodMappings = mapOf(
            "chill" to "Chill/Relaxed", "relaxed" to "Chill/Relaxed", "relaxing" to "Chill/Relaxed", "mellow" to "Chill/Relaxed", "calm" to "Chill/Relaxed",
            "romantic" to "Romantic", "love" to "Romantic",
            "sad" to "Sad", "melancholy" to "Sad", "depression" to "Sad", "emotional" to "Sad",
            "energetic" to "Energetic", "energy" to "Energetic", "party" to "Energetic", "workout" to "Energetic", "gym" to "Energetic", "hype" to "Energetic",
            "happy" to "Happy", "cheerful" to "Happy", "fun" to "Happy", "upbeat" to "Happy",
            "dark" to "Dark", "heavy" to "Dark", "gothic" to "Dark"
        )
        
        for (tag in rawTags) {
            val cleanTag = tag.lowercase().trim()
            genreMappings.entries.forEach { (keyword, target) ->
                if (cleanTag.contains(keyword)) genres.add(target)
            }
            moodMappings.entries.forEach { (keyword, target) ->
                if (cleanTag.contains(keyword)) moods.add(target)
            }
        }
        
        return mapOf(
            "genres" to genres.take(10).toList(),
            "moods" to moods.take(10).toList()
        )
    }

    private fun checkAndClaimFeatures(song: VideoItem) {
        val database = db ?: return
        val songKey = com.vinmusic.recommendation.RecommendationManager.generateSongKey(song.author, song.title)
        val durationMs = parseDurationText(song.durationText)

        scope.launch(Dispatchers.IO) {
            try {
                // 1. Check local cache
                val local = database.songFeatureCacheDao().get(songKey)
                if (local != null) {
                    Log.d(TAG, "Song features found in local Room cache for key: $songKey")
                    withContext(Dispatchers.Main) {
                        if (currentSong?.videoId == song.videoId) {
                            audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = false)
                        }
                    }
                    triggerOfflineSync()
                    return@launch
                }

                // 2. No remote Firestore - just trigger local analysis
                Log.d(TAG, "Cache miss for key: $songKey, triggering local analysis")
                withContext(Dispatchers.Main) {
                    if (currentSong?.videoId == song.videoId) {
                        audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed in checkAndClaimFeatures for $songKey", e)
                withContext(Dispatchers.Main) {
                    if (currentSong?.videoId == song.videoId) {
                        audioFeatureProcessor.resetForSong(songKey, song.title, song.author, durationMs, shouldAnalyze = false)
                    }
                }
            }
        }
    }

    fun setEightDEnabled(enabled: Boolean) {
        is8dEnabled = enabled
        eightDAudioProcessor.enabled = enabled
        if (enabled) {
            val sessionId = _player?.audioSessionId ?: 0
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                setupAudioEffects(sessionId)
            }
        } else {
            releaseAudioEffects()
        }
    }

    private fun setupAudioEffects(sessionId: Int) {
        releaseAudioEffects()
        try {
            _player?.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(0, 0.0f))
            Log.d(TAG, "8D custom processor active on session $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear aux audio effects: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        try {
            virtualizer?.enabled = false
            virtualizer?.release()
        } catch (_: Exception) {}
        virtualizer = null
        try {
            presetReverb?.enabled = false
            presetReverb?.release()
        } catch (_: Exception) {}
        presetReverb = null
        try {
            _player?.setAuxEffectInfo(androidx.media3.common.AuxEffectInfo(0, 0.0f))
        } catch (_: Exception) {}
    }
}
