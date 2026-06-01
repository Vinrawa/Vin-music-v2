package com.vinmusic.player

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom

/**
 * Lazy-initialized scratch sound synthesizer.
 * AudioTrack is only created when DJ mode is activated, and released when deactivated.
 * Scratch calls are throttled to prevent coroutine flooding.
 */
object ScratchSoundSynthesizer {
    private const val SAMPLE_RATE = 22050
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var isInitialized = false

    // Throttle: minimum 50ms between scratch sounds to prevent coroutine flooding
    private var lastScratchTimeNs = 0L
    private const val MIN_SCRATCH_INTERVAL_NS = 50_000_000L // 50ms

    // Managed coroutine scope for scratch playback
    private var synthJob = SupervisorJob()
    private var synthScope = CoroutineScope(synthJob + Dispatchers.IO)

    /**
     * Initialize the AudioTrack. Call this when DJ mode is turned ON.
     */
    fun initialize() {
        if (isInitialized) return
        try {
            // Recreate scope if previous one was cancelled
            if (synthJob.isCancelled) {
                synthJob = SupervisorJob()
                synthScope = CoroutineScope(synthJob + Dispatchers.IO)
            }
            val minBufSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize.coerceAtLeast(4096),
                AudioTrack.MODE_STREAM
            )
            audioTrack?.play()
            isInitialized = true
        } catch (_: Exception) {}
    }

    /**
     * Release the AudioTrack. Call this when DJ mode is turned OFF.
     */
    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        isInitialized = false
        synthJob.cancel()
    }

    /**
     * Play a synthesized scratch sound.
     * Throttled to max ~20 calls/second to prevent coroutine flooding.
     * @param velocity The velocity of the scratch rotation (e.g. angle difference in degrees).
     */
    fun playScratch(velocity: Float) {
        val absSpeed = Math.abs(velocity)
        if (absSpeed < 0.8f) return
        if (!isInitialized || audioTrack == null) return

        // Throttle: skip if called too frequently
        val now = System.nanoTime()
        if (now - lastScratchTimeNs < MIN_SCRATCH_INTERVAL_NS) return
        lastScratchTimeNs = now

        // Capture audioTrack to a local val to prevent race conditions
        val track = audioTrack ?: return

        synthScope.launch {
            try {
                val durationMs = 90
                val numSamples = (SAMPLE_RATE * (durationMs / 1000f)).toInt()
                val samples = ShortArray(numSamples)

                val targetFreq = 150.0 + (absSpeed * 65.0).coerceAtMost(800.0)
                var phase = 0.0

                for (i in 0 until numSamples) {
                    val progress = i.toFloat() / numSamples
                    val envelope = if (progress < 0.15f) {
                        progress / 0.15f
                    } else {
                        1f - (progress - 0.15f) / 0.85f
                    }

                    val noise = (ThreadLocalRandom.current().nextGaussian() * 7000.0).toInt().toShort()
                    val sine = (Math.sin(phase) * 11000.0).toInt().toShort()
                    phase += 2.0 * Math.PI * targetFreq / SAMPLE_RATE
                    if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI

                    val mixed = (sine * 0.45f + noise * 0.55f) * envelope * (absSpeed / 12f).coerceAtMost(1f)
                    samples[i] = mixed.toInt().coerceIn(-32768, 32767).toShort()
                }

                track.write(samples, 0, samples.size)
            } catch (_: Exception) {}
        }
    }
}
