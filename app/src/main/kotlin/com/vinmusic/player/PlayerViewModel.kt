package com.vinmusic.player

import android.app.Application
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.lyrics.LyricsHelper
import com.vinmusic.lyrics.LyricsLine
import com.vinmusic.lyrics.LyricsResult
import com.vinmusic.lyrics.qualityOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.lifecycle.HiltViewModel

private const val PREF_CONCERT_HALL = "concert_hall"
private const val PRESET_CONCERT_HALL = "Concert Hall"

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    app: Application,
    val recommendationRepository: com.vinmusic.recommendation.RecommendationRepository,
    val tasteProfileManager: com.vinmusic.recommendation.TasteProfileManager
) : AndroidViewModel(app) {

    data class HomeRefreshPayload(
        val recommendationSections: List<Pair<String, List<com.vinmusic.recommendation.RecommendedSong>>>,
        val spotifyMixes: List<com.vinmusic.recommendation.SpotifyMix>,
        val quickPicks: List<VideoItem>,
        val ytMusicSections: List<com.vinmusic.innertube.YTMusicHomeSection>,
        val ytLibraryPlaylists: List<com.vinmusic.innertube.AlbumItem>,
        val recommendedRadio: List<VideoItem>,
        val recommendedAlbums: List<com.vinmusic.innertube.AlbumItem>
    )

    private val _isHomeRefreshing = MutableStateFlow(false)
    val isHomeRefreshing: StateFlow<Boolean> = _isHomeRefreshing.asStateFlow()
    private val _homeRefreshGeneration = MutableStateFlow(0)
    val homeRefreshGeneration: StateFlow<Int> = _homeRefreshGeneration.asStateFlow()
    private val _homeRefreshPayload = MutableStateFlow<HomeRefreshPayload?>(null)
    val homeRefreshPayload: StateFlow<HomeRefreshPayload?> = _homeRefreshPayload.asStateFlow()
    private var homeRefreshJob: Job? = null

    fun refreshHome() {
        if (homeRefreshJob?.isActive == true) return
        homeRefreshJob = viewModelScope.launch {
            Log.d("HomeViewModel", "Home refresh started")
            _isHomeRefreshing.value = true
            try {
                withContext(Dispatchers.IO) {
                    val cache = getApplication<Application>()
                        .getSharedPreferences("vin_music_repository_cache", Context.MODE_PRIVATE)
                    cache.edit().remove("quick_picks").remove("youtube_home").apply()
                    com.vinmusic.recommendation.RecommendationManager.invalidateCache(getApplication())

                    val payload = withTimeout(35_000L) {
                        coroutineScope {
                        val recs = async {
                            com.vinmusic.recommendation.RecommendationManager
                                .getRecommendations(getApplication(), forceRefresh = true)
                        }
                        val mixes = async {
                            com.vinmusic.recommendation.RecommendationManager
                                .getSpotifyMixes(getApplication(), forceRefresh = true)
                        }
                        val quickPicks = async {
                            try { recommendationRepository.getQuickPicks() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                        val ytHome = async {
                            try { recommendationRepository.getYouTubeMusicHomeSections() }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                        val playlists = async {
                            try {
                                if (com.vinmusic.innertube.YTMusicSession.hasCookie(getApplication())) {
                                    recommendationRepository.getLibraryPlaylists()
                                } else emptyList()
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                        val radio = async {
                            val seed = PlayerSingleton.currentSong
                            if (seed == null) emptyList()
                            else try {
                                recommendationRepository.getSongRadio(seed.videoId, seed.title, seed.author)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                        val albums = async {
                            try {
                                val db = com.vinmusic.data.db.VinDatabase.getInstance(getApplication())
                                val profile = com.vinmusic.recommendation.RecommendationManager.buildTasteProfile(db)
                                val genre = profile.topGenres.firstOrNull()?.first
                                    ?.lowercase()?.replace("rap/hip-hop", "rap hip hop")
                                    ?.replace("punjabi folk", "punjabi") ?: "pop"
                                val language = profile.topLanguages.firstOrNull()?.first
                                    ?.lowercase()?.takeIf { it != "unknown" } ?: ""
                                com.vinmusic.innertube.InnerTube
                                    .searchAll("$genre $language best albums".trim().replace(Regex("\\s+"), " "))
                                    .albums.take(6)
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { emptyList() }
                        }
                            HomeRefreshPayload(
                                recs.await(), mixes.await(), quickPicks.await(), ytHome.await(),
                                playlists.await(), radio.await(), albums.await()
                            )
                        }
                    }
                    _homeRefreshPayload.value = payload
                    _homeRefreshGeneration.value++
                }
                Log.d("HomeViewModel", "Home refresh applied")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Refresh failed", e)
            } finally {
                _isHomeRefreshing.value = false
                homeRefreshJob = null
                Log.d("HomeViewModel", "Home refresh finished")
            }
        }
    }

    // ── Playback state ─────────────────────────────────────────────────────────
    val currentSong    get() = PlayerSingleton.currentSong
    val isPlaying      get() = PlayerSingleton.isPlaying
    val isLoading      get() = PlayerSingleton.isLoading
    val errorMessage   get() = PlayerSingleton.errorMessage
    val queue          get() = PlayerSingleton.queue
    val queueIndex     get() = PlayerSingleton.queueIndex

    // ── Lifecycle-owned resources (registered in init, released in onCleared) ──
    private var playerListener: Player.Listener? = null
    private var serviceConnection: android.content.ServiceConnection? = null

    var repeat: Boolean
        get() = PlayerSingleton.repeat
        set(value) { PlayerSingleton.repeat = value }

    var shuffle: Boolean
        get() = PlayerSingleton.shuffle
        set(value) { PlayerSingleton.shuffle = value }

    var smartShuffle: Boolean
        get() = PlayerSingleton.smartShuffle
        set(value) { PlayerSingleton.setSmartShuffle(value) }

    val is8dEnabled get() = PlayerSingleton.is8dEnabled

    fun toggle8dAudio() {
        val shouldEnable = !PlayerSingleton.is8dEnabled
        if (shouldEnable && concertHallEnabled) {
            concertHallEnabled = false
            prefs.edit().putBoolean(PREF_CONCERT_HALL, false).apply()
        }
        PlayerSingleton.setEightDEnabled(shouldEnable)
        applyConcertHallInternal()
    }

    // ── Progress (isolated — only progress composables recompose) ─────────────
    var progress      by mutableFloatStateOf(0f)
    var currentTimeMs by mutableLongStateOf(0L)
    var durationMs    by mutableLongStateOf(0L)

    // ── Liked songs & Library State ────────────────────────────────────────────
    var likedSongs by mutableStateOf<Set<String>>(emptySet())
    var libraryTab by mutableStateOf("Liked")

    // ── Lyrics ─────────────────────────────────────────────────────────────────
    var lyricsResult      by mutableStateOf<LyricsResult>(LyricsResult.NotFound)
    var isLyricsLoading   by mutableStateOf(false)
    var isTransliterating by mutableStateOf(false)
    var currentLyricIndex by mutableIntStateOf(-1)  // for synced lyrics highlight
    var currentSongDescription by mutableStateOf<String?>(null)

    // ── Rich Lyrics — Word-level karaoke ────────────────────────────────────────
    var currentWordIndex by mutableIntStateOf(-1)
    var wordFillFraction by mutableFloatStateOf(0f)
    var lyricsCandidates by mutableStateOf<List<com.vinmusic.lyrics.LyricsCandidate>>(emptyList())
    var selectedLyricsSource by mutableStateOf<String?>(null)

    fun transliterateLyricsToHinglish() {
        val currentResult = lyricsResult
        if (currentResult is LyricsResult.NotFound) return
        if (currentResult is LyricsResult.Synced && currentResult.source.contains("Transliterated")) return
        if (currentResult is LyricsResult.Plain && currentResult.source.contains("Transliterated")) return
        isTransliterating = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newResult = when (currentResult) {
                    is LyricsResult.Synced -> {
                        val newLines = currentResult.lines.map { 
                            it.copy(text = LyricsHelper.transliterateToHinglish(it.text))
                        }
                        LyricsResult.Synced(newLines, currentResult.source + " (Transliterated)")
                    }
                    is LyricsResult.Plain -> {
                        LyricsResult.Plain(LyricsHelper.transliterateToHinglish(currentResult.text), currentResult.source + " (Transliterated)")
                    }
                    else -> currentResult
                }
                withContext(Dispatchers.Main) {
                    lyricsResult = newResult
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transliteration failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isTransliterating = false
                }
            }
        }
    }

    // ── Sleep timer ────────────────────────────────────────────────────────────
    var sleepTimerMinutes by mutableIntStateOf(0)
    var sleepTimerMode    by mutableStateOf(SleepTimerMode.MINUTES)

    // ── EQ — 6 bands ──────────────────────────────────────────────────────────
    var eqEnabled    by mutableStateOf(false)
    var eqSubBass    by mutableFloatStateOf(0f)  // 60Hz
    var eqBass       by mutableFloatStateOf(0f)  // 250Hz
    var eqLowMid     by mutableFloatStateOf(0f)  // 1kHz
    var eqMid        by mutableFloatStateOf(0f)  // 4kHz
    var eqTreble     by mutableFloatStateOf(0f)  // 8kHz
    var eqAir        by mutableFloatStateOf(0f)  // 16kHz
    var bassBoostStr by mutableFloatStateOf(0f)  // 0–1000
    var loudnessGain by mutableFloatStateOf(0f)  // 0–1000 mB
    var eqPreset     by mutableStateOf("Flat")

    var lyricOffsetMs     by mutableLongStateOf(0L)
    var playbackSpeed     by mutableFloatStateOf(1.0f)
    var playbackPitch     by mutableFloatStateOf(1.0f)

    var audioNormalizationEnabled by mutableStateOf(false)
    var crossfadeEnabled by mutableStateOf(false)
    var crossfadeSecs by mutableIntStateOf(3)
    var concertHallEnabled by mutableStateOf(false)
    var smartAutoplayEnabled: Boolean
        get() = PlayerSingleton.smartAutoplayEnabled
        set(value) { PlayerSingleton.smartAutoplayEnabled = value }

    var currentPalette by mutableStateOf(
        com.vinmusic.ui.utils.ColorExtractor.MusicPalette(
            gradTop = androidx.compose.ui.graphics.Color(0x33C5A880),
            gradMid = androidx.compose.ui.graphics.Color(0x1FC5A880),
            gradBottom = androidx.compose.ui.graphics.Color(0xFF0E0E11),
            accent = androidx.compose.ui.graphics.Color(0xFFC5A880)
        )
    )

    val isAutoplayLoading get() = PlayerSingleton.isAutoplayLoading

    companion object { const val TAG = "VIN" }


    // ── ExoPlayer via singleton (shared with VinMusicService for notification) ─
    val exoPlayer: ExoPlayer = PlayerSingleton.getOrCreate(app)

    // ── Audio effects ─────────────────────────────────────────────────────────
    private var currentSessionId: Int              = -1
    private var equalizer:   Equalizer?        = null
    private var bassBoostFx: BassBoost?        = null
    private var loudnessFx:  LoudnessEnhancer? = null
    private var concertHallFx: PresetReverb?   = null
    private var concertHallEnvFx: EnvironmentalReverb? = null

    // EQ apply handler — debounce rapid slider changes to avoid audio artifacts
    private val eqHandler = Handler(Looper.getMainLooper())
    private val eqApplyRunnable = Runnable { applyEQInternal() }

    // Playback-params handler — debounce rapid speed/pitch slider moves (120ms) to prevent native audio crashes
    private val pbHandler = Handler(Looper.getMainLooper())
    private val pbApplyRunnable = Runnable { applyPlaybackParametersInternal() }

    // ── Jobs ──────────────────────────────────────────────────────────────────
    private var fetchJob:    Job? = null
    private var sleepJob:    Job? = null
    private var progressJob: Job? = null
    private var syncLyricsJob: Job? = null
    private var lyricsJob:   Job? = null
    private var lyricsUpgradeJob: Job? = null
    private var previousLyricsVideoId: String? = null
    private var lyricsSuppressedForVideoId: String? = null
    private var cachedPlainTimelineText: String? = null
    private var cachedPlainTimelineDurationMs: Long = 0L
    private var cachedPlainTimeline: List<LyricsLine> = emptyList()

    // ── DB ────────────────────────────────────────────────────────────────────
    private val db = VinDatabase.getInstance(app)
    val topTracksFlow = db.interactionSignalDao().getTopPlayedSongsFlow()

    private fun resetLyricsState(song: VideoItem?) {
        lyricsJob?.cancel()
        lyricsJob = null
        lyricsUpgradeJob?.cancel()
        lyricsUpgradeJob = null
        lyricsResult = LyricsResult.NotFound
        isLyricsLoading = false
        isTransliterating = false
        currentLyricIndex = -1
        currentWordIndex = -1
        wordFillFraction = 0f
        lyricsCandidates = emptyList()
        selectedLyricsSource = null
        lyricOffsetMs = 0L
        currentSongDescription = null
        previousLyricsVideoId = song?.videoId
        cachedPlainTimelineText = null
        cachedPlainTimelineDurationMs = 0L
        cachedPlainTimeline = emptyList()
    }

    // ── Recommendation tracking variables ─────────────────────────────────────
    private var playStartTime: Long = 0L
    private var previousSongId: String? = null
    private var hasLoggedCompleteForCurrent: Boolean = false

    private val prefs by lazy { app.getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE) }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "audio_normalization" -> {
                audioNormalizationEnabled = sharedPreferences.getBoolean(key, false)
                applyEQInternal()
            }
            "crossfade" -> crossfadeEnabled = sharedPreferences.getBoolean(key, false)
            "crossfade_secs" -> crossfadeSecs = sharedPreferences.getInt(key, 3)
            PREF_CONCERT_HALL -> {
                concertHallEnabled = sharedPreferences.getBoolean(key, false)
                applyConcertHallInternal()
            }
            "skip_silence" -> {
                try {
                    exoPlayer.skipSilenceEnabled = sharedPreferences.getBoolean(key, false)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to apply skip_silence: ${e.message}")
                }
            }
        }
    }

    init {
        PlayerSingleton.onSongEndedCallback = {
            if (sleepTimerMode == SleepTimerMode.END_OF_SONG && sleepTimerMinutes == -1) {
                exoPlayer.pause()
                sleepTimerMinutes = 0
                true
            } else {
                false
            }
        }

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = exoPlayer.duration.coerceAtLeast(0L)
                    val sessionId = exoPlayer.audioSessionId
                    if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId > 0) {
                        initAudioFx(sessionId)
                    }
                }
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                initAudioFx(audioSessionId)
            }
        }.also { playerListener = it })
        startProgressJob()
        loadLikedSongs()

        audioNormalizationEnabled = prefs.getBoolean("audio_normalization", false)
        crossfadeEnabled = prefs.getBoolean("crossfade", false)
        crossfadeSecs = prefs.getInt("crossfade_secs", 3)
        concertHallEnabled = prefs.getBoolean(PREF_CONCERT_HALL, false)
        eqEnabled = prefs.getBoolean("eq_enabled", false)
        eqSubBass = prefs.getFloat("eq_60hz", 0f)
        eqBass = prefs.getFloat("eq_230hz", 0f)
        eqLowMid = prefs.getFloat("eq_910hz", 0f)
        eqMid = prefs.getFloat("eq_4khz", 0f)
        eqTreble = prefs.getFloat("eq_8khz", prefs.getFloat("eq_14khz", 0f))
        eqAir = prefs.getFloat("eq_air", 0f)
        try {
            exoPlayer.skipSilenceEnabled = prefs.getBoolean("skip_silence", false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize skip_silence: ${e.message}")
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Monitor song changes to auto-reset and fetch lyrics, palette, and description
        viewModelScope.launch {
            androidx.compose.runtime.snapshotFlow { currentSong }
                .distinctUntilChanged { old, new -> old?.videoId == new?.videoId }
                .collect { song ->
                    resetLyricsState(song)
                if (song != null) {
                    if (lyricsSuppressedForVideoId == song.videoId) {
                        lyricsSuppressedForVideoId = null
                    } else {
                        loadLyrics(force = true)
                    }

                    // Fetch palette in parallel (don't block next song change)
                    launch(Dispatchers.IO) {
                        try {
                            val ctx = getApplication<Application>()
                            val url = song.thumbnailHd.takeIf { it.isNotBlank() } ?: song.thumbnail
                            val extracted = com.vinmusic.ui.utils.ColorExtractor.extractColorsFromUrl(ctx, url)
                            withContext(Dispatchers.Main) {
                                if (currentSong?.videoId == song.videoId) {
                                    currentPalette = extracted
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to extract palette", e)
                        }
                    }

                    // Fetch description in parallel
                    launch(Dispatchers.IO) {
                        try {
                            val desc = com.vinmusic.innertube.InnerTube.getSongDescription(song.videoId)
                            withContext(Dispatchers.Main) {
                                if (currentSong?.videoId == song.videoId) {
                                    currentSongDescription = desc
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to load description", e)
                        }
                    }
                }
            }
        }

        // Start and Bind to VinMusicService so it lives as long as the app is alive
        // Media3 MediaSessionService automatically promotes to foreground with notification when playback starts
        try {
            val ctx = getApplication<android.app.Application>()
            val intent = android.content.Intent(ctx, VinMusicService::class.java)
            ctx.startService(intent)
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {}
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }
            serviceConnection = connection
            ctx.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.e(TAG, "Failed to start/bind VinMusicService: ${e.message}") }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun playSong(song: VideoItem) {
        lyricsSuppressedForVideoId = null
        resetLyricsState(song)
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        PlayerSingleton.playSong(song)
    }

    fun playSongPreview(song: VideoItem, startPositionMs: Long) {
        lyricsSuppressedForVideoId = song.videoId
        resetLyricsState(song)
        progress          = 0f
        currentTimeMs     = startPositionMs.coerceAtLeast(0L)
        durationMs        = 0L
        PlayerSingleton.playSong(song, startPositionMs)
    }

    fun setQueue(songs: List<VideoItem>, startIndex: Int = 0) {
        radioJob?.cancel()
        lyricsSuppressedForVideoId = null
        resetLyricsState(songs.getOrNull(startIndex))
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        PlayerSingleton.setQueue(songs, startIndex)
    }

    /** Skip to a queue index without resetting the queue or cancelling radio. */
    fun skipToIndex(index: Int) {
        lyricsSuppressedForVideoId = null
        resetLyricsState(PlayerSingleton.queue.getOrNull(index))
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        PlayerSingleton.skipToIndex(index)
    }

    fun playNextInQueue(song: VideoItem) {
        PlayerSingleton.playNextInQueue(song)
    }

    fun addToEndOfQueue(song: VideoItem) {
        PlayerSingleton.addToEndOfQueue(song)
    }

    fun moveQueueItem(from: Int, to: Int) {
        PlayerSingleton.moveQueueItem(from, to)
    }

    private var radioJob: Job? = null

    fun playSongWithRadio(song: VideoItem) {
        // Cancel any previous radio fetch so it doesn't pollute our queue
        radioJob?.cancel()

        lyricsSuppressedForVideoId = null
        resetLyricsState(song)
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        
        PlayerSingleton.setQueue(listOf(song), 0)
        PlayerSingleton.isAutoplayLoading = true
        
        radioJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Give primary song resolution 100% network bandwidth first before loading radio queue
                delay(600)
                if (!isActive) return@launch

                // FAST PATH: Direct radio playlist — pre-filtered so the first thing
                // that plays after this song is actually similar, not raw popular drift.
                Log.d(TAG, "playSongWithRadio: fetching instant radio for ${song.videoId}")
                val radioTracks = InnerTube.getWatchNextRadio(song.videoId)
                Log.d(TAG, "playSongWithRadio: instant radio returned ${radioTracks.size} tracks")

                // Check if user already moved to another song
                if (!isActive) return@launch

                val fastRecs = com.vinmusic.recommendation.RecommendationManager
                    .curateRadioCandidates(song, radioTracks)

                if (fastRecs.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        // Only append if the current queue still starts with our song
                        if (PlayerSingleton.queue.firstOrNull()?.videoId == song.videoId) {
                            val currentQueue = PlayerSingleton.queue.toMutableList()
                            val existingIds = currentQueue.map { it.videoId }.toSet()
                            val unique = fastRecs.filter { it.videoId !in existingIds }
                            if (unique.isNotEmpty()) {
                                PlayerSingleton.markAutoplayTail(currentQueue.size, unique.map { it.videoId })
                                currentQueue.addAll(unique)
                                PlayerSingleton.queue = currentQueue
                                Log.d(TAG, "playSongWithRadio: appended ${unique.size} curated radio tracks (raw=${radioTracks.size}), total=${currentQueue.size}")
                            }
                        }
                        PlayerSingleton.isAutoplayLoading = false
                    }
                } else {
                    // Radio returned nothing — seed-aware search fallback (genre blend
                    // instead of plain artist top-songs), same curation before appending.
                    Log.w(TAG, "playSongWithRadio: radio empty, trying seed-aware search fallback")
                    val seedMeta = com.vinmusic.recommendation.RecommendationManager.inferMetadata(song)
                    val genreTerm = seedMeta.genre.lowercase(java.util.Locale.ROOT).replace("rap/hip-hop", "rap hip hop")
                    val searchResults = mutableListOf<VideoItem>()
                    if (song.author.isNotBlank()) {
                        val queries = listOf(
                            "${song.author} $genreTerm songs official",
                            "${song.author} popular tracks"
                        )
                        for (query in queries) {
                            try {
                                for (item in InnerTube.search(query)) {
                                    if (item.videoId != song.videoId && searchResults.none { it.videoId == item.videoId }) {
                                        searchResults.add(item)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "playSongWithRadio: fallback search '$query' failed: ${e.message}")
                            }
                            if (searchResults.size >= 15) break
                        }
                    }

                    if (!isActive) return@launch

                    val curatedFallback = com.vinmusic.recommendation.RecommendationManager
                        .curateRadioCandidates(song, searchResults)

                    if (curatedFallback.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (PlayerSingleton.queue.firstOrNull()?.videoId == song.videoId) {
                                val currentQueue = PlayerSingleton.queue.toMutableList()
                                val existingIds = currentQueue.map { it.videoId }.toSet()
                                val unique = curatedFallback.filter { it.videoId !in existingIds }
                                if (unique.isNotEmpty()) {
                                    PlayerSingleton.markAutoplayTail(currentQueue.size, unique.map { it.videoId })
                                    currentQueue.addAll(unique)
                                    PlayerSingleton.queue = currentQueue
                                    Log.d(TAG, "playSongWithRadio: appended ${unique.size} curated fallback tracks")
                                }
                            }
                            PlayerSingleton.isAutoplayLoading = false
                        }
                    } else {
                        withContext(Dispatchers.Main) { PlayerSingleton.isAutoplayLoading = false }
                    }
                }

                // SLOW PATH (background): full ranked Smart Queue replaces the unranked
                // fast-path tail so similar songs play first. Manual user additions survive.
                if (!isActive) return@launch
                try {
                    val smartRecs = recommendationRepository.getSongRadio(song.videoId, song.title, song.author)
                    if (!isActive) return@launch
                    if (!smartRecs.isNullOrEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (PlayerSingleton.queue.firstOrNull()?.videoId == song.videoId) {
                                PlayerSingleton.replaceAutoplayTail(smartRecs)
                                Log.d(TAG, "playSongWithRadio: Smart Queue replaced autoplay tail (${smartRecs.size} ranked), total=${PlayerSingleton.queue.size}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "playSongWithRadio: Smart Queue enhance failed: ${e.message}")
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "playSongWithRadio: cancelled for ${song.videoId}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playSongWithRadio: EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
                withContext(Dispatchers.Main) { PlayerSingleton.isAutoplayLoading = false }
            }
        }
    }

    fun setSmartAutoplay(enabled: Boolean) {
        PlayerSingleton.setSmartAutoplay(enabled)
    }

    fun smartSortQueueByBPM() {
        val currentQueue = PlayerSingleton.queue
        if (currentQueue.size <= 1) return
        viewModelScope.launch(Dispatchers.Default) {
            val currentSong = PlayerSingleton.currentSong
            val sortedQueue = currentQueue.sortedBy { item ->
                com.vinmusic.recommendation.RecommendationManager.inferMetadata(item).tempo
            }
            val newIndex = sortedQueue.indexOfFirst { it.videoId == currentSong?.videoId }
            withContext(Dispatchers.Main) {
                PlayerSingleton.queue = sortedQueue
                if (newIndex != -1) {
                    PlayerSingleton.queueIndex = newIndex
                }
            }
        }
    }



    fun playNext() {
        resetLyricsState(null)
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        PlayerSingleton.playNext()
    }

    fun playPrev() {
        val willChangeSong = exoPlayer.currentPosition <= 3000
        if (willChangeSong) resetLyricsState(null)
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        PlayerSingleton.playPrev()
    }

    fun togglePlay() {
        PlayerSingleton.togglePlay()
    }

    fun seekTo(fraction: Float) {
        PlayerSingleton.seekTo(fraction)
    }

    fun seekToMs(ms: Long) {
        currentTimeMs = ms.coerceAtLeast(0L)
        PlayerSingleton.seekToMs(ms)
        updateSyncedLyricIndex()
    }

    /**
     * Pause playback without updating isPlaying state.
     * Used for scratching during DJ mode where the playback state should not change.
     */
    fun pauseSilently() {
        exoPlayer.pause()
    }

    /**
     * Resume playback without updating isPlaying state.
     * Used for scratching during DJ mode where the playback state should not change.
     */
    fun playSilently() {
        exoPlayer.play()
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private fun startProgressJob() {
        progressJob = viewModelScope.launch {
            while (true) {
                if (!exoPlayer.isPlaying) {
                    delay(500)
                    continue
                }
                delay(180)
                if (durationMs > 0) {
                    val pos = exoPlayer.currentPosition
                    val prog = (pos.toFloat() / durationMs).coerceIn(0f, 1f)
                    
                    // Only write state if the change is significant (to avoid unnecessary recompositions)
                    if (Math.abs(pos - currentTimeMs) >= 140 || Math.abs(prog - progress) >= 0.001f || pos == 0L) {
                        currentTimeMs = pos
                        progress = prog
                    }

                    if (crossfadeEnabled && durationMs > 0) {
                        val remainingMs = durationMs - currentTimeMs
                        if (remainingMs <= crossfadeSecs * 1000) {
                            val ratio = (remainingMs.toFloat() / (crossfadeSecs * 1000)).coerceIn(0f, 1f)
                            exoPlayer.volume = Math.sqrt(ratio.toDouble()).toFloat()
                            // Repeat-one must not advance the queue — the track loops
                            // and the fade-in branch below restores volume instead.
                            if (remainingMs <= 500 && exoPlayer.repeatMode != Player.REPEAT_MODE_ONE) {
                                playNext()
                            }
                        } else if (currentTimeMs <= crossfadeSecs * 1000) {
                            val ratio = (currentTimeMs.toFloat() / (crossfadeSecs * 1000)).coerceIn(0f, 1f)
                            exoPlayer.volume = Math.sqrt(ratio.toDouble()).toFloat()
                        } else {
                            exoPlayer.volume = 1f
                        }
                    } else if (sleepTimerMode != SleepTimerMode.MINUTES) { // don't override sleep fade
                        exoPlayer.volume = 1f
                    }

                    // Prefetching is safely and completely managed at the process level by PlayerSingleton to ensure it isn't cancelled when this ViewModel is destroyed.

                    // Log completion signal at 80%
                    if (progress >= 0.8f && !PlayerSingleton.hasLoggedCompleteForCurrent) {
                        PlayerSingleton.hasLoggedCompleteForCurrent = true
                        val songId = currentSong?.videoId
                        if (songId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val signal = db.interactionSignalDao().get(songId)
                                if (signal != null) {
                                    signal.completeCount += 1
                                    db.interactionSignalDao().insert(signal)
                                }
                            }
                        }
                    }

                    updateSyncedLyricIndex()
                }
            }
        }
    }

    private fun updateSyncedLyricIndex() {
        val lines = currentLyricsTimeline()
        if (lines.isEmpty()) {
            if (currentLyricIndex != -1) currentLyricIndex = -1
            currentWordIndex = -1
            wordFillFraction = 0f
            return
        }
        val adjustedTime = lyricAdjustedTimeMs()
        val idx = lines.indexOfLast { it.timeMs <= adjustedTime }.coerceAtLeast(0)
        if (idx != currentLyricIndex) currentLyricIndex = idx

        // Word-level karaoke progress
        val activeLine = lines.getOrNull(idx)
        val words = activeLine?.words
        if (!words.isNullOrEmpty()) {
            val (wIdx, fill) = com.vinmusic.lyrics.computeWordProgress(words, adjustedTime)
            currentWordIndex = wIdx
            wordFillFraction = fill
        } else {
            currentWordIndex = -1
            wordFillFraction = 0f
        }
    }

    private fun playbackTimelineMs(): Long {
        val playerPosition = runCatching { exoPlayer.currentPosition }.getOrNull()
        return playerPosition
            ?.takeIf { it >= 0L }
            ?: currentTimeMs
    }

    fun lyricAdjustedTimeMs(): Long = (playbackTimelineMs() + lyricOffsetMs).coerceAtLeast(0L)

    fun currentLyricsTimeline(): List<LyricsLine> {
        return when (val result = lyricsResult) {
            is LyricsResult.Synced -> result.lines
            is LyricsResult.Plain -> {
                val duration = knownSongDurationMs()
                if (cachedPlainTimelineText != result.text || cachedPlainTimelineDurationMs != duration) {
                    cachedPlainTimelineText = result.text
                    cachedPlainTimelineDurationMs = duration
                    cachedPlainTimeline = buildEstimatedTimeline(result.text, duration)
                }
                cachedPlainTimeline
            }
            else -> emptyList()
        }
    }

    private fun buildEstimatedTimeline(text: String, duration: Long): List<LyricsLine> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(160)

        if (lines.isEmpty()) return emptyList()

        val leadIn = (duration * 0.018f).toLong().coerceIn(300L, 3_500L)
        val tail = (duration * 0.03f).toLong().coerceIn(800L, 6_000L)
        val usableDuration = (duration - leadIn - tail).coerceAtLeast(duration / 2)

        val weights = lines.map { line ->
            line.split(Regex("\\s+")).count { it.isNotBlank() }.coerceIn(3, 18)
        }
        val totalWeight = weights.sum().coerceAtLeast(1)
        val unitMs = usableDuration.toDouble() / totalWeight.toDouble()

        var cursor = leadIn
        val maxTime = (duration - 500L).coerceAtLeast(0L)
        return lines.mapIndexed { index, line ->
            val time = cursor.coerceIn(0L, maxTime)
            val lineDuration = (weights[index] * unitMs).toLong().coerceAtLeast(1_200L)
            cursor += lineDuration
            LyricsLine(time, line)
        }
    }

    private fun knownSongDurationMs(): Long {
        val stateDuration = durationMs.takeIf { it > 10_000L }
        val playerDuration = runCatching { exoPlayer.duration }
            .getOrNull()
            ?.takeIf { it > 10_000L && it != C.TIME_UNSET }
        val textDuration = currentSong?.durationText
            ?.let { parseDurationTextMs(it) }
            ?.takeIf { it > 10_000L }

        return stateDuration ?: playerDuration ?: textDuration ?: 180_000L
    }

    private fun parseDurationTextMs(durationText: String): Long? {
        val parts = durationText.split(":")
            .map { it.trim().toLongOrNull() ?: return null }
        if (parts.size < 2) return null
        return parts.fold(0L) { total, part -> total * 60 + part } * 1_000L
    }

    private fun cleanCachedSyncedLines(lines: List<LyricsLine>): List<LyricsLine> {
        return lines
            .map {
                it.copy(
                    text = it.text.trim(),
                    words = it.words
                        ?.filter { word -> word.text.isNotBlank() }
                        ?.sortedBy { word -> word.startMs }
                )
            }
            .filter { it.text.isNotEmpty() && !isNonLyricDisplayLine(it.text) }
            .sortedBy { it.timeMs }
    }

    private fun cleanCachedPlainLyrics(text: String): String {
        return text
            .replace("\u00A0", " ")
            .lines()
            .map { it.trim() }
            .filter { line -> line.isEmpty() || !isNonLyricDisplayLine(line) }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isNonLyricDisplayLine(raw: String): Boolean {
        val text = raw.trim().trim('[', ']', '(', ')', '{', '}').trim()
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (Regex("""^\d+\s+contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^contributors?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^\d+\s*(embed|translations?)$""", RegexOption.IGNORE_CASE).matches(text)) return true
        if (Regex("""^(intro|outro|verse|chorus|pre[-\s]?chorus|post[-\s]?chorus|bridge|hook|refrain|interlude|instrumental|drop|break|spoken|sample|skit|part|segue)(\s+\d+|\s+[ivx]+)?(\s*[:.-].*)?$""", RegexOption.IGNORE_CASE).matches(text)) return true
        val junk = listOf("you might also like", "embed", "read more", "see live", "get tickets", "track info", "produced by", "written by", "release date", "translations", "lyrics", "album", "contributors")
        return junk.any { lower == it || (text.length < 42 && lower.startsWith(it)) }
    }

    private fun normalizeLyricsResult(result: LyricsResult): LyricsResult {
        return when (result) {
            is LyricsResult.Synced -> {
                val lines = cleanCachedSyncedLines(result.lines)
                if (lines.isNotEmpty()) LyricsResult.Synced(lines, result.source) else LyricsResult.NotFound
            }
            is LyricsResult.Plain -> {
                val text = cleanCachedPlainLyrics(result.text)
                if (text.isNotBlank()) LyricsResult.Plain(text, result.source) else LyricsResult.NotFound
            }
            else -> result
        }
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    fun loadLyrics(force: Boolean = false) {
        val song = currentSong ?: return
        if (!force && previousLyricsVideoId == song.videoId && (isLyricsLoading || lyricsResult !is LyricsResult.NotFound)) return
        isLyricsLoading = true
        previousLyricsVideoId = song.videoId
        val fetchVideoId = song.videoId  // capture identity for staleness check
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = db.cachedLyricsDao().get(song.videoId)
                if (cached != null && cached.lyricsType != "not_found") {
                    val rawRes = when (cached.lyricsType) {
                        "synced" -> {
                            val lines = cleanCachedSyncedLines(
                                com.google.gson.Gson().fromJson(cached.content, Array<LyricsLine>::class.java).toList()
                            )
                            LyricsResult.Synced(lines, "Local Cache")
                        }
                        "plain" -> LyricsResult.Plain(cleanCachedPlainLyrics(cached.content), "Local Cache")
                        else -> LyricsResult.NotFound
                    }
                    val res = normalizeLyricsResult(rawRes)
                    withContext(Dispatchers.Main) {
                        // Only write if this song is still playing
                        if (currentSong?.videoId == fetchVideoId) {
                            lyricsResult = res
                            updateSyncedLyricIndex()
                            isLyricsLoading = false
                        }
                    }

                    // Quality upgrade: if cached result is Plain, kick off a
                    // background fetch. If network returns Synced (higher quality),
                    // replace cache and update UI seamlessly.
                    if (res is LyricsResult.Plain) {
                        upgradeCachedLyrics(song.videoId, song.title, song.author, fetchVideoId)
                    }
                    return@launch
                }

                val prefs = getApplication<Application>().getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                val fetched = LyricsHelper.fetch(song.title, song.author, song.videoId, provider, knownSongDurationMs())
                val res = normalizeLyricsResult(fetched)
                val source = when (res) {
                    is LyricsResult.Synced -> res.source
                    is LyricsResult.Plain -> res.source
                    else -> ""
                }
                withContext(Dispatchers.Main) {
                    // Only write if this song is still playing
                    if (currentSong?.videoId == fetchVideoId) {
                        lyricsResult = res
                        selectedLyricsSource = source
                        updateSyncedLyricIndex()
                    }
                }

                if (res !is LyricsResult.NotFound) {
                    val type = when (res) {
                        is LyricsResult.Synced -> "synced"
                        is LyricsResult.Plain -> "plain"
                        else -> "not_found"
                    }
                    val content = when (res) {
                        is LyricsResult.Synced -> com.google.gson.Gson().toJson(res.lines)
                        is LyricsResult.Plain -> res.text
                        else -> ""
                    }
                    db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content, source))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Load lyrics failed", e)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (currentSong?.videoId == fetchVideoId) {
                        isLyricsLoading = false
                    }
                }
            }
        }
    }

    /** Force refetch lyrics from network, clearing any cached version */
    fun refetchLyrics() {
        val song = currentSong ?: return
        lyricsResult = LyricsResult.NotFound
        currentLyricIndex = -1
        lyricOffsetMs = 0L
        isLyricsLoading = true
        previousLyricsVideoId = song.videoId
        val fetchVideoId = song.videoId  // capture identity for staleness check
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete cached lyrics so we get fresh ones
                db.cachedLyricsDao().delete(song.videoId)

                val prefs = getApplication<Application>().getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                val fetched = LyricsHelper.fetch(song.title, song.author, song.videoId, provider, knownSongDurationMs())
                val res = normalizeLyricsResult(fetched)
                withContext(Dispatchers.Main) {
                    if (currentSong?.videoId == fetchVideoId) {
                        lyricsResult = res
                        updateSyncedLyricIndex()
                    }
                }

                val type = when (res) {
                    is LyricsResult.Synced -> "synced"
                    is LyricsResult.Plain -> "plain"
                    is LyricsResult.NotFound -> "not_found"
                }
                val source = when (res) {
                    is LyricsResult.Synced -> res.source
                    is LyricsResult.Plain -> res.source
                    else -> ""
                }
                val content = when (res) {
                    is LyricsResult.Synced -> com.google.gson.Gson().toJson(res.lines)
                    is LyricsResult.Plain -> res.text
                    is LyricsResult.NotFound -> ""
                }
                if (content.isNotEmpty()) {
                    db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content, source))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Refetch lyrics failed", e)
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    if (currentSong?.videoId == fetchVideoId) {
                        isLyricsLoading = false
                    }
                }
            }
        }
    }

    /** Fetch lyrics candidates from all providers in parallel. */
    fun fetchLyricsCandidates() {
        val song = currentSong ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val candidates = LyricsHelper.fetchCandidates(
                    title = song.title,
                    artist = song.author,
                    videoId = song.videoId,
                    durationMs = knownSongDurationMs()
                )
                withContext(Dispatchers.Main) {
                    lyricsCandidates = candidates
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch lyrics candidates", e)
            }
        }
    }

    /** Select a specific lyrics candidate from the source picker. */
    fun selectLyricsCandidate(candidate: com.vinmusic.lyrics.LyricsCandidate) {
        val song = currentSong ?: return
        val normalized = normalizeLyricsResult(candidate.result)
        lyricsResult = normalized
        selectedLyricsSource = candidate.source
        updateSyncedLyricIndex()

        // Persist selection
        viewModelScope.launch(Dispatchers.IO) {
            val type = when (normalized) {
                is LyricsResult.Synced -> "synced"
                is LyricsResult.Plain -> "plain"
                else -> "not_found"
            }
            val content = when (normalized) {
                is LyricsResult.Synced -> com.google.gson.Gson().toJson(normalized.lines)
                is LyricsResult.Plain -> normalized.text
                else -> ""
            }
            if (content.isNotEmpty()) {
                db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content, candidate.source, pinned = true))
            }
        }
    }

    // checkAndResetLyricsForNewSong removed — song changes are now handled
    // reliably via the snapshotFlow collector with proper job cancellation.

    /**
     * Background quality upgrade: fetches lyrics from network and replaces
     * cache if the new result is higher quality than the cached Plain.
     * Non-blocking — the user already sees the cached Plain lyrics.
     */
    private fun upgradeCachedLyrics(
        videoId: String,
        title: String,
        author: String,
        fetchVideoId: String
    ) {
        lyricsUpgradeJob?.cancel()
        lyricsUpgradeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                val fetched = LyricsHelper.fetch(title, author, videoId, provider, knownSongDurationMs())
                val newRes = normalizeLyricsResult(fetched)

                // Only upgrade if: song still playing AND new result is higher quality
                if (currentSong?.videoId != fetchVideoId) return@launch
                if (qualityOf(newRes) <= qualityOf(LyricsResult.Plain("", ""))) return@launch
                // newRes quality must be > 1 (Plain) → i.e., Synced

                if (newRes is LyricsResult.Synced) {
                    // Replace cache
                    db.cachedLyricsDao().insert(CachedLyricsEntity(
                        videoId, "synced", com.google.gson.Gson().toJson(newRes.lines)
                    ))
                    // Update UI on Main thread
                    withContext(Dispatchers.Main) {
                        if (currentSong?.videoId == fetchVideoId) {
                            lyricsResult = newRes
                            updateSyncedLyricIndex()
                            Log.d(TAG, "Lyrics quality upgraded: Plain → Synced via ${newRes.source}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Lyrics quality upgrade failed", e)
            }
        }
    }

    // ── Like ──────────────────────────────────────────────────────────────────

    fun toggleLike(song: VideoItem) {
        PlayerSingleton.toggleLike(song)
    }

    private fun loadLikedSongs() {
        viewModelScope.launch(Dispatchers.IO) {
            db.likedSongDao().getAllFlow().collect { list ->
                likedSongs = list.map { it.videoId }.toSet()
            }
        }
    }

    fun isLiked(id: String) = id in likedSongs

    // ── Sleep timer ───────────────────────────────────────────────────────────

    enum class SleepTimerMode { MINUTES, END_OF_SONG, END_OF_QUEUE }

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepTimerMinutes = minutes
        if (minutes <= 0) return
        sleepTimerMode = SleepTimerMode.MINUTES
        sleepJob = viewModelScope.launch {
            for (i in minutes downTo 1) {
                delay(60_000)
                sleepTimerMinutes = i - 1
            }
            // Fade out before stopping
            fadeOutAndStop()
        }
    }

    fun setSleepTimerEndOfSong() {
        sleepJob?.cancel()
        sleepTimerMode    = SleepTimerMode.END_OF_SONG
        sleepTimerMinutes = -1  // sentinel
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepTimerMinutes = 0
        sleepTimerMode    = SleepTimerMode.MINUTES
    }

    private suspend fun fadeOutAndStop() {
        val steps = 150
        try {
            repeat(steps) {
                val ratio = 1f - (it + 1).toFloat() / steps
                exoPlayer.volume = Math.sqrt(ratio.toDouble()).toFloat()
                delay(100)
            }
            exoPlayer.pause()
        } finally {
            // Restore even if the timer is cancelled mid-fade — otherwise volume
            // stays faded forever, because the progress job skips its reset while
            // a MINUTES timer is armed.
            exoPlayer.volume = 1f
        }
    }

    // ── EQ — gradual apply to prevent audio tearing ───────────────────────────

    /** Call this from sliders — debounced 80ms so rapid drags don't pop */
    fun applyEQ() {
        eqHandler.removeCallbacks(eqApplyRunnable)
        eqHandler.postDelayed(eqApplyRunnable, 80)
    }

    private fun applyEQInternal() {
        val gains = listOf(eqSubBass, eqBass, eqLowMid, eqMid, eqTreble, eqAir)
        val hasBandEq = gains.any { kotlin.math.abs(it) >= 0.05f }
        eqEnabled = hasBandEq

        prefs.edit().apply {
            putBoolean("eq_enabled", eqEnabled)
            putFloat("eq_60hz", eqSubBass)
            putFloat("eq_230hz", eqBass)
            putFloat("eq_910hz", eqLowMid)
            putFloat("eq_4khz", eqMid)
            putFloat("eq_8khz", eqTreble)
            putFloat("eq_air", eqAir)
            apply()
        }

        equalizer?.runCatching {
            enabled = eqEnabled
            val targetsMilliHz = listOf(60_000, 250_000, 1_000_000, 4_000_000, 8_000_000, 16_000_000)
            val range = bandLevelRange
            val minLevel = range.getOrNull(0)?.toInt() ?: -1500
            val maxLevel = range.getOrNull(1)?.toInt() ?: 1500
            val bandCount = numberOfBands.toInt()
            val centers = (0 until bandCount).map { band ->
                runCatching { getCenterFreq(band.toShort()) }.getOrDefault(targetsMilliHz.getOrElse(band) { targetsMilliHz.last() })
            }
            val bandGains = List(bandCount) { mutableListOf<Float>() }
            targetsMilliHz.forEachIndexed { targetIndex, target ->
                val nearestBand = centers.indices.minByOrNull { band ->
                    kotlin.math.abs(centers[band] - target)
                } ?: targetIndex.coerceIn(0, (bandCount - 1).coerceAtLeast(0))
                if (nearestBand in bandGains.indices) {
                    bandGains[nearestBand].add(gains[targetIndex])
                }
            }
            for (band in 0 until bandCount) {
                val gain = bandGains[band].maxByOrNull { kotlin.math.abs(it) } ?: 0f
                val targetLevel = if (eqEnabled) {
                    (gain * 100).toInt().coerceIn(minLevel, maxLevel).toShort()
                } else {
                    0.toShort()
                }
                setBandLevel(band.toShort(), targetLevel)
            }
        }
        bassBoostFx?.runCatching { setStrength(bassBoostStr.toInt().toShort()) }
        loudnessFx?.runCatching  { 
            if (audioNormalizationEnabled) {
                setTargetGain(150) // 150mB as per requirements
            } else {
                setTargetGain(loudnessGain.toInt())
            }
        }
        applyConcertHallInternal()
    }

    fun applyPreset(preset: EQPreset) {
        eqPreset  = preset.name
        eqSubBass = preset.subBass
        eqBass    = preset.bass
        eqLowMid  = preset.lowMid
        eqMid     = preset.mid
        eqTreble  = preset.treble
        eqAir     = preset.air
        updateConcertHallEnabled(preset.name == PRESET_CONCERT_HALL)
        applyEQ()
    }

    fun resetEQ() {
        eqSubBass = 0f; eqBass = 0f; eqLowMid = 0f
        eqMid = 0f; eqTreble = 0f; eqAir = 0f
        bassBoostStr = 0f; loudnessGain = 0f
        eqPreset = "Flat"
        updateConcertHallEnabled(false)
        applyEQ()

        playbackSpeed = 1.0f
        playbackPitch = 1.0f
        isSlowedReverb = false
        applyPlaybackParameters()
    }

    fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        applyPlaybackParameters()
    }

    fun updatePlaybackPitch(pitch: Float) {
        playbackPitch = pitch
        applyPlaybackParameters()
    }

    // ── Slowed + Reverb Mode ──────────────────────────────────────────────────
    var isSlowedReverb by mutableStateOf(false)

    fun toggleSlowedReverb() {
        isSlowedReverb = !isSlowedReverb
        if (isSlowedReverb) {
            // Slowed + Reverb: pitch down, slightly slow, boost bass & sub-bass for warmth
            playbackSpeed = 0.92f
            playbackPitch = 0.85f
            eqSubBass = 8f
            eqBass = 6f
            eqLowMid = 2f
            eqMid = -2f
            eqTreble = -1f
            eqAir = 3f
            loudnessGain = 300f
            eqPreset = "Slowed + Reverb"
            applyEQ()
        } else {
            playbackSpeed = 1.0f
            playbackPitch = 1.0f
            eqSubBass = 0f; eqBass = 0f; eqLowMid = 0f
            eqMid = 0f; eqTreble = 0f; eqAir = 0f
            loudnessGain = 0f
            eqPreset = "Flat"
            applyEQ()
        }
        applyPlaybackParameters()
    }

    private fun applyPlaybackParameters() {
        // Store in singleton immediately so they persist across song transitions
        PlayerSingleton.storedSpeed = playbackSpeed
        PlayerSingleton.storedPitch = playbackPitch
        // Debounce the actual ExoPlayer update — 120ms rate-limit prevents native audio crashes
        pbHandler.removeCallbacks(pbApplyRunnable)
        pbHandler.postDelayed(pbApplyRunnable, 120)
    }

    /** Internal: actually push params to ExoPlayer (called via debounced handler) */
    private fun applyPlaybackParametersInternal() {
        try {
            val p = PlayerSingleton.player
            // Only apply when player is in a stable state
            if (p.playbackState == androidx.media3.common.Player.STATE_READY ||
                p.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                p.playbackParameters = androidx.media3.common.PlaybackParameters(playbackSpeed, playbackPitch)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply playback parameters: ${e.message}")
        }
    }

    fun saveCustomLyrics(content: String, isSynced: Boolean) {
        val song = currentSong ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val type = if (isSynced) "synced" else "plain"
            db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content))
            
            withContext(Dispatchers.Main) {
                lyricsResult = if (isSynced) {
                    val lines = com.google.gson.Gson().fromJson(content, Array<LyricsLine>::class.java).toList()
                    LyricsResult.Synced(lines, "Custom Edit")
                } else {
                    LyricsResult.Plain(content, "Custom Edit")
                }
            }
        }
    }

    private fun initAudioFx(sessionId: Int) {
        Log.d(TAG, "initAudioFx: requested sessionId = $sessionId")
        if (sessionId <= 0) return
        if (currentSessionId == sessionId) {
            // Already initialized for this session, just ensure applied
            applyEQInternal()
            return
        }
        currentSessionId = sessionId

        try {
            equalizer?.release()
            bassBoostFx?.release()
            loudnessFx?.release()
            concertHallFx?.release()
            concertHallEnvFx?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing previous AudioEffects", e)
        }
        equalizer = null
        bassBoostFx = null
        loudnessFx = null
        concertHallFx = null
        concertHallEnvFx = null

        runCatching {
            equalizer = Equalizer(0, sessionId).apply { enabled = true }
            Log.d(TAG, "Equalizer bound to session $sessionId")
        }.onFailure { Log.e(TAG, "Equalizer build failed: ${it.message}") }

        runCatching {
            bassBoostFx = BassBoost(0, sessionId).apply { enabled = true }
            Log.d(TAG, "BassBoost bound to session $sessionId")
        }.onFailure { Log.e(TAG, "BassBoost build failed: ${it.message}") }

        runCatching {
            loudnessFx = LoudnessEnhancer(sessionId).apply { enabled = true }
            Log.d(TAG, "LoudnessEnhancer bound to session $sessionId")
        }.onFailure { Log.e(TAG, "LoudnessEnhancer build failed: ${it.message}") }

        applyEQInternal()
        applyConcertHallInternal()
    }

    fun updateConcertHallEnabled(enabled: Boolean) {
        if (enabled && PlayerSingleton.is8dEnabled) {
            PlayerSingleton.setEightDEnabled(false)
        }
        concertHallEnabled = enabled
        prefs.edit().putBoolean(PREF_CONCERT_HALL, enabled).apply()
        applyConcertHallInternal()
    }

    private fun applyConcertHallInternal() {
        val shouldApply = concertHallEnabled && !PlayerSingleton.is8dEnabled && currentSessionId > 0
        try {
            if (shouldApply) {
                val env = concertHallEnvFx ?: EnvironmentalReverb(0, 0).also { concertHallEnvFx = it }
                env.setRoomLevel((-900).toShort())
                env.setRoomHFLevel((-1600).toShort())
                env.setDecayTime(2400)
                env.setDecayHFRatio((640).toShort())
                env.setReflectionsLevel((-850).toShort())
                env.setReflectionsDelay(38)
                env.setReverbLevel((-650).toShort())
                env.setReverbDelay(72)
                env.setDiffusion((900).toShort())
                env.setDensity((1000).toShort())
                env.enabled = true
                exoPlayer.setAuxEffectInfo(AuxEffectInfo(env.id, 0.18f))
            } else {
                exoPlayer.setAuxEffectInfo(AuxEffectInfo(0, 0f))
                concertHallFx?.enabled = false
                concertHallEnvFx?.enabled = false
            }
        } catch (e: Exception) {
            runCatching {
                val effect = concertHallFx ?: PresetReverb(0, 0).also { concertHallFx = it }
                effect.preset = PresetReverb.PRESET_LARGEHALL
                effect.enabled = shouldApply
                exoPlayer.setAuxEffectInfo(if (shouldApply) AuxEffectInfo(effect.id, 0.20f) else AuxEffectInfo(0, 0f))
            }.onFailure {
                Log.e(TAG, "Failed to apply Concert Hall: ${it.message}")
            }
        }
    }

    override fun onCleared() {
        equalizer?.release()
        bassBoostFx?.release()
        loudnessFx?.release()
        concertHallFx?.release()
        concertHallEnvFx?.release()
        eqHandler.removeCallbacks(eqApplyRunnable)
        pbHandler.removeCallbacks(pbApplyRunnable)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // Detach from the immortal PlayerSingleton so this ViewModel (and its whole
        // DB/handler graph) doesn't leak for the life of the process.
        playerListener?.let { listener ->
            runCatching { exoPlayer.removeListener(listener) }
        }
        playerListener = null
        PlayerSingleton.onSongEndedCallback = null
        serviceConnection?.let { connection ->
            runCatching { getApplication<Application>().unbindService(connection) }
        }
        serviceConnection = null
        super.onCleared()
    }

    fun recordSearchClick(song: VideoItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val signal = db.interactionSignalDao().get(song.videoId) ?: InteractionSignal(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText
            )
            signal.searchClickCount += 1
            db.interactionSignalDao().insert(signal)
            com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()
        }
    }

    /** A left swipe is explicit negative discovery feedback, even without playback. */
    fun recordDiscoverSkip(song: VideoItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val signal = db.interactionSignalDao().get(song.videoId) ?: InteractionSignal(
                videoId = song.videoId,
                title = song.title,
                author = song.author,
                durationText = song.durationText
            )
            signal.skipCount += 1
            db.interactionSignalDao().insert(signal)
            com.vinmusic.recommendation.RecommendationManager.invalidateTasteProfile()
        }
    }
}

// ── EQ Presets ────────────────────────────────────────────────────────────────

data class EQPreset(
    val name: String,
    val subBass: Float, val bass: Float, val lowMid: Float,
    val mid: Float, val treble: Float, val air: Float
)

val EQ_PRESETS = listOf(
    EQPreset("Flat",       0f,   0f,   0f,   0f,   0f,   0f),
    EQPreset("Bass Boost", 6f,   8f,   2f,   0f,  -1f,  -1f),
    EQPreset("Treble+",   -1f,  -1f,   0f,   2f,   7f,   9f),
    EQPreset("Pop",       -1f,   2f,   4f,   3f,   2f,   0f),
    EQPreset("Rock",       4f,   3f,  -1f,   1f,   4f,   3f),
    EQPreset("Classical",  3f,   2f,   0f,  -2f,   3f,   4f),
    EQPreset("Jazz",       3f,   2f,   0f,   2f,   4f,   3f),
    EQPreset("Electronic", 5f,   4f,   0f,   3f,   2f,   2f),
    EQPreset(PRESET_CONCERT_HALL, 2f, 3f, -1f, -1f, 4f, 5f),
    EQPreset("Vocal",     -2f,   0f,   5f,   5f,   3f,   0f),
    EQPreset("Lofi",       4f,   3f,  -3f,  -3f,  -2f,  -5f)
)
