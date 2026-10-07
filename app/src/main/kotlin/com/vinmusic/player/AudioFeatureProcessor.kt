package com.vinmusic.player

import android.util.Log
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import com.vinmusic.recommendation.dsp.BpmEstimator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFeatureProcessor : BaseAudioProcessor() {
    var currentSongKey: String? = null
        private set
    var currentSongTitle: String? = null
        private set
    var currentSongArtist: String? = null
        private set
    var shouldAnalyze: Boolean = false
        private set
    var durationMs: Long = 0L
        private set

    private var sampleCount = 0L
    private var window1Buffer: FloatArray? = null
    private var window2Buffer: FloatArray? = null
    private var window1WriteIndex = 0
    private var window2WriteIndex = 0

    private var window1StartSample = 0L
    private var window1EndSample = 0L
    private var window2StartSample = 0L
    private var window2EndSample = 0L

    private var isAnalysisTriggered = false

    // Analysis outlives individual queueInput calls but must not outlive the
    // processor: jobs are tracked so onReset can cancel them (previously they were
    // detached and uncancellable, each also pinning ~22 MB of copied buffers).
    private val analysisScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.e("AudioFeatureProcessor", "Analysis crashed: ${e.message}")
        }
    )
    private val activeAnalysisJobs = mutableListOf<Job>()

    fun resetForSong(
        songKey: String,
        title: String,
        artist: String,
        durationMs: Long,
        shouldAnalyze: Boolean
    ) {
        this.currentSongKey = songKey
        this.currentSongTitle = title
        this.currentSongArtist = artist
        this.durationMs = durationMs
        this.shouldAnalyze = shouldAnalyze

        this.window1EndSample = 0L
        this.window1Buffer = null
        this.window2Buffer = null
        this.isAnalysisTriggered = false
        this.sampleCount = 0L
        this.window1WriteIndex = 0
        this.window2WriteIndex = 0

        Log.d("AudioFeatureProcessor", "Reset for song: $title by $artist (shouldAnalyze=$shouldAnalyze, durationMs=$durationMs)")
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // Maintain buffer states on flush to prevent seeking from corrupting capture range
    }

    override fun onReset() {
        synchronized(activeAnalysisJobs) { activeAnalysisJobs.toList() }.forEach { it.cancel() }
        window1Buffer = null
        window2Buffer = null
        window1EndSample = 0L
        isAnalysisTriggered = false
        sampleCount = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        if (!shouldAnalyze || currentSongKey == null) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val sampleRate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount

        if (window1EndSample == 0L) {
            val sRate = sampleRate.toLong()
            window1StartSample = 5 * sRate
            window1EndSample = 35 * sRate
            window1Buffer = FloatArray((30 * sampleRate).toInt())

            val durMs = if (durationMs > 0) durationMs else 240_000L
            val window2StartSec = (durMs * 0.25) / 1000.0
            window2StartSample = (window2StartSec * sampleRate).toLong()
            window2EndSample = ((window2StartSec + 30) * sampleRate).toLong()
            window2Buffer = FloatArray((30 * sampleRate).toInt())

            isAnalysisTriggered = false
            sampleCount = 0L
            window1WriteIndex = 0
            window2WriteIndex = 0
        }

        // 1. Pass-through the original untouched stereo bytes for normal playback
        val outputBuffer = replaceOutputBuffer(remaining)
        val startPos = inputBuffer.position()
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()

        // 2. Reset inputBuffer position and order to read samples for internal analysis
        inputBuffer.position(startPos)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val limit = inputBuffer.limit()
        while (inputBuffer.position() + (channelCount * 2) <= limit) {
            var monoVal = 0.0
            for (c in 0 until channelCount) {
                monoVal += inputBuffer.getShort().toDouble()
            }
            val monoSample = (monoVal / channelCount / 32768.0).toFloat()

            val currentSampleIdx = sampleCount
            sampleCount++

            if (currentSampleIdx in window1StartSample until window1EndSample) {
                window1Buffer?.let { buf ->
                    if (window1WriteIndex < buf.size) {
                        buf[window1WriteIndex++] = monoSample
                    }
                }
            } else if (currentSampleIdx in window2StartSample until window2EndSample) {
                window2Buffer?.let { buf ->
                    if (window2WriteIndex < buf.size) {
                        buf[window2WriteIndex++] = monoSample
                    }
                }
            }

            if (currentSampleIdx >= window2EndSample && !isAnalysisTriggered) {
                isAnalysisTriggered = true
                triggerBackgroundAnalysis(sampleRate)
            }
        }
    }

    override fun onQueueEndOfStream() {
        super.onQueueEndOfStream()
        if (shouldAnalyze && !isAnalysisTriggered && currentSongKey != null) {
            isAnalysisTriggered = true
            triggerBackgroundAnalysis(inputAudioFormat.sampleRate)
        }
    }

    private fun triggerBackgroundAnalysis(sampleRate: Int) {
        val songKey = currentSongKey ?: return
        val title = currentSongTitle ?: ""
        val artist = currentSongArtist ?: ""
        val w1 = window1Buffer?.copyOf()
        val w2 = window2Buffer?.copyOf()

        // Turn off analysis capture immediately and release the ~22 MB capture
        // buffers — the copies above are all the analysis needs.
        shouldAnalyze = false
        window1Buffer = null
        window2Buffer = null

        val job = analysisScope.launch {
            try {
                Log.d("AudioFeatureProcessor", "Starting background analysis for $title - $artist ($songKey)")

                // 1. Calculate BPM via BpmEstimator
                val bpm1 = w1?.let { BpmEstimator.estimateBpm(it, sampleRate) } ?: 0f
                val bpm2 = w2?.let { BpmEstimator.estimateBpm(it, sampleRate) } ?: 0f
                val bpm = if (bpm1 > 0f && bpm2 > 0f) {
                    (bpm1 + bpm2) / 2f
                } else if (bpm1 > 0f) {
                    bpm1
                } else {
                    bpm2
                }

                // 2. Calculate Energy via RMS
                val energy1 = w1?.let { calculateRMS(it) } ?: 0f
                val energy2 = w2?.let { calculateRMS(it) } ?: 0f
                val energy = if (energy1 > 0f && energy2 > 0f) {
                    (energy1 + energy2) / 2f
                } else if (energy1 > 0f) {
                    energy1
                } else {
                    energy2
                }

                Log.d("AudioFeatureProcessor", "Calculated features: BPM=$bpm, Energy=$energy")

                // 3. Fetch Last.fm Tags locally (no Firestore)
                val ctx = PlayerSingleton.context
                val isOnline = ctx != null && PlayerCacheManager.isOnline(ctx)
                
                var genreTags: List<String> = emptyList()
                var moodTags: List<String> = emptyList()
                var hasRealTags = false

                if (isOnline) {
                    Log.d("AudioFeatureProcessor", "Fetching Last.fm tags locally...")
                    val tags = PlayerSingleton.fetchLastFmTagsLocally(artist, title)
                    if (tags != null) {
                        genreTags = tags["genres"] ?: emptyList()
                        moodTags = tags["moods"] ?: emptyList()
                        hasRealTags = true
                        Log.d("AudioFeatureProcessor", "Fetched Last.fm tags: genres=$genreTags, moods=$moodTags")
                    } else {
                        Log.d("AudioFeatureProcessor", "Last.fm tag fetch returned null. Falling back to inferred tags.")
                    }
                } else {
                    Log.d("AudioFeatureProcessor", "Device is offline. Skipping Last.fm fetch.")
                }

                if (!hasRealTags) {
                    val fakeItem = com.vinmusic.innertube.VideoItem("", title, artist, "")
                    val inferred = com.vinmusic.recommendation.RecommendationManager.inferMetadata(fakeItem)
                    genreTags = listOf(inferred.genre)
                    moodTags = listOf(inferred.mood)
                    Log.d("AudioFeatureProcessor", "Using inferred tags: genres=$genreTags, moods=$moodTags")
                }

                PlayerSingleton.onAnalysisCompleted(
                    songKey = songKey,
                    bpm = bpm.takeIf { it in 40f..250f },
                    energy = if (energy > 0f) energy else 0.5f,
                    genreTags = genreTags,
                    moodTags = moodTags,
                    title = title,
                    artist = artist,
                    hasRealTags = hasRealTags
                )
            } catch (e: Exception) {
                Log.e("AudioFeatureProcessor", "Background analysis failed for $songKey", e)
            }
        }
        synchronized(activeAnalysisJobs) { activeAnalysisJobs.add(job) }
        job.invokeOnCompletion {
            synchronized(activeAnalysisJobs) { activeAnalysisJobs.remove(job) }
        }
    }

    private fun calculateRMS(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / samples.size)
        // Normalize RMS: 0.35 RMS is typical for a full compression mix
        return (rms / 0.35).coerceIn(0.0, 1.0).toFloat()
    }
}
