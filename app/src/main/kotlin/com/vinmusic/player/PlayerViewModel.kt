package com.vinmusic.player

import android.app.Application
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
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
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.lifecycle.HiltViewModel

@UnstableApi
@HiltViewModel
class PlayerViewModel @Inject constructor(
    app: Application,
    val recommendationRepository: com.vinmusic.recommendation.RecommendationRepository
) : AndroidViewModel(app) {

    // ── Playback state ─────────────────────────────────────────────────────────
    val currentSong    get() = PlayerSingleton.currentSong
    val isPlaying      get() = PlayerSingleton.isPlaying
    val isLoading      get() = PlayerSingleton.isLoading
    val errorMessage   get() = PlayerSingleton.errorMessage
    val queue          get() = PlayerSingleton.queue
    val queueIndex     get() = PlayerSingleton.queueIndex
    
    var repeat: Boolean
        get() = PlayerSingleton.repeat
        set(value) { PlayerSingleton.repeat = value }

    var shuffle: Boolean
        get() = PlayerSingleton.shuffle
        set(value) { PlayerSingleton.shuffle = value }

    var smartShuffle: Boolean
        get() = PlayerSingleton.smartShuffle
        set(value) { PlayerSingleton.setSmartShuffle(value) }

    // ── Progress (isolated — only progress composables recompose) ─────────────
    var progress      by mutableFloatStateOf(0f)
    var currentTimeMs by mutableLongStateOf(0L)
    var durationMs    by mutableLongStateOf(0L)

    // ── Liked songs ────────────────────────────────────────────────────────────
    var likedSongs by mutableStateOf<Set<String>>(emptySet())

    // ── Lyrics ─────────────────────────────────────────────────────────────────
    var lyricsResult      by mutableStateOf<LyricsResult>(LyricsResult.NotFound)
    var isLyricsLoading   by mutableStateOf(false)
    var isTransliterating by mutableStateOf(false)
    var currentLyricIndex by mutableIntStateOf(-1)  // for synced lyrics highlight

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
    var smartAutoplayEnabled: Boolean
        get() = PlayerSingleton.smartAutoplayEnabled
        set(value) { PlayerSingleton.smartAutoplayEnabled = value }


    val isAutoplayLoading get() = PlayerSingleton.isAutoplayLoading

    companion object { const val TAG = "VIN" }

    // ── ExoPlayer via singleton (shared with VinMusicService for notification) ─
    val exoPlayer: ExoPlayer = PlayerSingleton.getOrCreate(app)

    // ── Audio effects ─────────────────────────────────────────────────────────
    private var currentSessionId: Int              = -1
    private var equalizer:   Equalizer?        = null
    private var bassBoostFx: BassBoost?        = null
    private var loudnessFx:  LoudnessEnhancer? = null

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
    private var previousLyricsVideoId: String? = null

    // ── DB ────────────────────────────────────────────────────────────────────
    private val db = VinDatabase.getInstance(app)

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
        })
        startProgressJob()
        loadLikedSongs()

        audioNormalizationEnabled = prefs.getBoolean("audio_normalization", false)
        crossfadeEnabled = prefs.getBoolean("crossfade", false)
        crossfadeSecs = prefs.getInt("crossfade_secs", 3)
        try {
            exoPlayer.skipSilenceEnabled = prefs.getBoolean("skip_silence", false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize skip_silence: ${e.message}")
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Start and Bind to VinMusicService so it lives as long as the app is alive
        // Media3 will automatically promote it to foreground when playback starts
        try {
            val ctx = getApplication<android.app.Application>()
            val intent = android.content.Intent(ctx, VinMusicService::class.java)
            ctx.startService(intent)
            ctx.bindService(intent, object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {}
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
            }, android.content.Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.e(TAG, "Failed to start/bind VinMusicService: ${e.message}") }
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun playSong(song: VideoItem) {
        lyricsResult      = LyricsResult.NotFound
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        currentLyricIndex = -1
        lyricOffsetMs     = 0L
        PlayerSingleton.playSong(song)
    }

    fun setQueue(songs: List<VideoItem>, startIndex: Int = 0) {
        lyricsResult      = LyricsResult.NotFound
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        currentLyricIndex = -1
        lyricOffsetMs     = 0L
        PlayerSingleton.setQueue(songs, startIndex)
    }

    fun setSmartAutoplay(enabled: Boolean) {
        PlayerSingleton.setSmartAutoplay(enabled)
    }



    fun playNext() {
        lyricsResult      = LyricsResult.NotFound
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        currentLyricIndex = -1
        lyricOffsetMs     = 0L
        PlayerSingleton.playNext()
    }

    fun playPrev() {
        lyricsResult      = LyricsResult.NotFound
        progress          = 0f
        currentTimeMs     = 0L
        durationMs        = 0L
        currentLyricIndex = -1
        lyricOffsetMs     = 0L
        PlayerSingleton.playPrev()
    }

    fun togglePlay() {
        PlayerSingleton.togglePlay()
    }

    fun seekTo(fraction: Float) {
        PlayerSingleton.seekTo(fraction)
    }

    fun seekToMs(ms: Long) {
        PlayerSingleton.seekToMs(ms)
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
                delay(150) // Fast updates for smooth synced lyrics tracking
                if (exoPlayer.isPlaying && durationMs > 0) {
                    currentTimeMs = exoPlayer.currentPosition
                    progress = (currentTimeMs.toFloat() / durationMs).coerceIn(0f, 1f)

                    if (crossfadeEnabled && durationMs > 0) {
                        val remainingMs = durationMs - currentTimeMs
                        if (remainingMs <= crossfadeSecs * 1000) {
                            val ratio = (remainingMs.toFloat() / (crossfadeSecs * 1000)).coerceIn(0f, 1f)
                            exoPlayer.volume = Math.sqrt(ratio.toDouble()).toFloat()
                            if (remainingMs <= 500) {
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
                    checkAndResetLyricsForNewSong()
                }
            }
        }
    }

    private fun updateSyncedLyricIndex() {
        val synced = lyricsResult as? LyricsResult.Synced ?: return
        val lines = synced.lines
        if (lines.isEmpty()) return
        val adjustedTime = currentTimeMs + lyricOffsetMs
        val idx = lines.indexOfLast { it.timeMs <= adjustedTime }
        if (idx != currentLyricIndex) currentLyricIndex = idx
    }

    // ── Lyrics ────────────────────────────────────────────────────────────────

    fun loadLyrics() {
        val song = currentSong ?: return
        if (isLyricsLoading || lyricsResult !is LyricsResult.NotFound) return
        isLyricsLoading = true
        previousLyricsVideoId = song.videoId
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cached = db.cachedLyricsDao().get(song.videoId)
                if (cached != null) {
                    val res = when (cached.lyricsType) {
                        "synced" -> {
                            val lines = com.google.gson.Gson().fromJson(cached.content, Array<LyricsLine>::class.java).toList()
                            LyricsResult.Synced(lines, "Local Cache")
                        }
                        "plain" -> LyricsResult.Plain(cached.content, "Local Cache")
                        else -> LyricsResult.NotFound
                    }
                    withContext(Dispatchers.Main) {
                        lyricsResult = res
                    }
                    return@launch
                }

                val prefs = getApplication<Application>().getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                val res = LyricsHelper.fetch(song.title, song.author, song.videoId, provider)
                withContext(Dispatchers.Main) {
                    lyricsResult = res
                }

                val type = when (res) {
                    is LyricsResult.Synced -> "synced"
                    is LyricsResult.Plain -> "plain"
                    is LyricsResult.NotFound -> "not_found"
                }
                val content = when (res) {
                    is LyricsResult.Synced -> com.google.gson.Gson().toJson(res.lines)
                    is LyricsResult.Plain -> res.text
                    is LyricsResult.NotFound -> ""
                }
                db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content))
            } catch (e: Exception) {
                Log.e(TAG, "Load lyrics failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isLyricsLoading = false
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete cached lyrics so we get fresh ones
                db.cachedLyricsDao().delete(song.videoId)

                val prefs = getApplication<Application>().getSharedPreferences("vin_music_prefs", Context.MODE_PRIVATE)
                val provider = prefs.getString("lyrics_provider", "Auto") ?: "Auto"
                val res = LyricsHelper.fetch(song.title, song.author, song.videoId, provider)
                withContext(Dispatchers.Main) {
                    lyricsResult = res
                }

                val type = when (res) {
                    is LyricsResult.Synced -> "synced"
                    is LyricsResult.Plain -> "plain"
                    is LyricsResult.NotFound -> "not_found"
                }
                val content = when (res) {
                    is LyricsResult.Synced -> com.google.gson.Gson().toJson(res.lines)
                    is LyricsResult.Plain -> res.text
                    is LyricsResult.NotFound -> ""
                }
                if (content.isNotEmpty()) {
                    db.cachedLyricsDao().insert(CachedLyricsEntity(song.videoId, type, content))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Refetch lyrics failed", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isLyricsLoading = false
                }
            }
        }
    }

    /** Called from progress loop to auto-detect song changes and reset lyrics */
    fun checkAndResetLyricsForNewSong() {
        val currentId = currentSong?.videoId ?: return
        if (currentId != previousLyricsVideoId) {
            lyricsResult = LyricsResult.NotFound
            currentLyricIndex = -1
            lyricOffsetMs = 0L
            previousLyricsVideoId = currentId
            loadLyrics()
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
        repeat(steps) {
            val ratio = 1f - (it + 1).toFloat() / steps
            exoPlayer.volume = Math.sqrt(ratio.toDouble()).toFloat()
            delay(100)
        }
        exoPlayer.pause()
        exoPlayer.volume = 1f
    }

    // ── EQ — gradual apply to prevent audio tearing ───────────────────────────

    /** Call this from sliders — debounced 80ms so rapid drags don't pop */
    fun applyEQ() {
        eqHandler.removeCallbacks(eqApplyRunnable)
        eqHandler.postDelayed(eqApplyRunnable, 80)
    }

    private fun applyEQInternal() {
        equalizer?.runCatching {
            val n = numberOfBands.toInt()
            val bands = listOf(eqSubBass, eqBass, eqLowMid, eqMid, eqTreble, eqAir)
            bands.forEachIndexed { i, gain ->
                if (i < n) setBandLevel(i.toShort(), (gain * 100).toInt().toShort())
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
    }

    fun applyPreset(preset: EQPreset) {
        eqPreset  = preset.name
        eqSubBass = preset.subBass
        eqBass    = preset.bass
        eqLowMid  = preset.lowMid
        eqMid     = preset.mid
        eqTreble  = preset.treble
        eqAir     = preset.air
        applyEQ()
    }

    fun resetEQ() {
        eqSubBass = 0f; eqBass = 0f; eqLowMid = 0f
        eqMid = 0f; eqTreble = 0f; eqAir = 0f
        bassBoostStr = 0f; loudnessGain = 0f
        eqPreset = "Flat"
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
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing previous AudioEffects", e)
        }
        equalizer = null
        bassBoostFx = null
        loudnessFx = null

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
    }

    override fun onCleared() {
        equalizer?.release()
        bassBoostFx?.release()
        loudnessFx?.release()
        eqHandler.removeCallbacks(eqApplyRunnable)
        pbHandler.removeCallbacks(pbApplyRunnable)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    private fun recordPlay(song: VideoItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val signal = db.interactionSignalDao().get(song.videoId) ?: InteractionSignal(
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
            db.interactionSignalDao().insert(signal)
        }
    }

    private fun recordEnd() {
        val prevId = previousSongId ?: return
        val playDuration = System.currentTimeMillis() - playStartTime
        viewModelScope.launch(Dispatchers.IO) {
            val signal = db.interactionSignalDao().get(prevId) ?: return@launch
            // A skip is defined as playing for less than 30 seconds and not reaching 80% completion
            if (playDuration < 30_000 && !hasLoggedCompleteForCurrent) {
                signal.skipCount += 1
                if (playDuration < 20_000) {
                    signal.skip20sCount += 1
                }
                db.interactionSignalDao().insert(signal)
            }
        }
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
    EQPreset("Vocal",     -2f,   0f,   5f,   5f,   3f,   0f),
    EQPreset("Lofi",       4f,   3f,  -3f,  -3f,  -2f,  -5f)
)
