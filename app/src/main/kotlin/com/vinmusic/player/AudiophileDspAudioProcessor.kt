package com.vinmusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AudiophileDspAudioProcessor operates directly on 16-bit PCM stereo streams within ExoPlayer.
 *
 * It provides:
 * 1. Resonant Biquad Low-Shelf Sub-Bass & Kick Boost (40Hz–110Hz, up to +14dB)
 * 2. Psychoacoustic Harmonic Bass Exciter (MaxxBass style) for massive bass perception on earphones
 * 3. Biquad High-Shelf Vocal & Clarity Presence Exciter (3.5kHz–5kHz, up to +8dB)
 * 4. Ultra-smooth algebraic soft-knee anti-distortion limiter (prevents digital clipping at high bass)
 * 5. Zero-overhead bypass when sliders are at zero.
 */
class AudiophileDspAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var bassStrength: Float = 0f // 0.0f to 1.0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                recalculateCoefficients()
            }
        }

    @Volatile
    var clarityStrength: Float = 0f // 0.0f to 1.0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                recalculateCoefficients()
            }
        }

    @Volatile
    var normalizationEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                targetGain = 1.0
                currentGain = 1.0
                runningRms = 0.12
            }
        }

    // Loudness Normalizer State (Target: ~ -14 LUFS / ~0.13 nominal RMS)
    private var runningRms = 0.12
    private var currentGain = 1.0
    private var targetGain = 1.0
    private var blockSampleCount = 0
    private var blockSumSquares = 0.0

    private var sampleRate: Int = 44_100

    // Direct Form II Transposed Biquad Filters
    private class Biquad {
        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a1 = 0.0
        var a2 = 0.0

        var z1 = 0.0
        var z2 = 0.0

        inline fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        fun setCoeffs(newB0: Double, newB1: Double, newB2: Double, newA1: Double, newA2: Double) {
            b0 = newB0
            b1 = newB1
            b2 = newB2
            a1 = newA1
            a2 = newA2
        }

        fun setPassthrough() {
            b0 = 1.0
            b1 = 0.0
            b2 = 0.0
            a1 = 0.0
            a2 = 0.0
        }
    }

    // Left & Right filter channels
    private val bassFilterL = Biquad()
    private val bassFilterR = Biquad()
    private val clarityFilterL = Biquad()
    private val clarityFilterR = Biquad()

    // 1st order RC filters for Harmonic Bass Exciter
    private var exciterLpL = 0.0
    private var exciterLpR = 0.0
    private var exciterHpL = 0.0
    private var exciterHpR = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        recalculateCoefficients()
        return inputAudioFormat
    }

    override fun onFlush() {
        bassFilterL.reset()
        bassFilterR.reset()
        clarityFilterL.reset()
        clarityFilterR.reset()
        exciterLpL = 0.0
        exciterLpR = 0.0
        exciterHpL = 0.0
        exciterHpR = 0.0
        runningRms = 0.12
        currentGain = 1.0
        targetGain = 1.0
        blockSumSquares = 0.0
        blockSampleCount = 0
    }

    override fun onReset() {
        onFlush()
        bassStrength = 0f
        clarityStrength = 0f
        normalizationEnabled = false
    }

    private fun recalculateCoefficients() {
        val sr = sampleRate.toDouble()
        if (sr <= 0) return

        // 1. Calculate Low-Shelf Bass Filter
        if (bassStrength > 0.001f) {
            val dbGain = bassStrength.toDouble() * 7.5 // Clean studio sub-bass boost (up to +7.5dB)
            val f0 = 80.0 // Sub-bass & kick frequency
            val q = 0.707

            val a = Math.pow(10.0, dbGain / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
            val b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
            val b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
            val b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
            val a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
            val a2 = ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0

            bassFilterL.setCoeffs(b0, b1, b2, a1, a2)
            bassFilterR.setCoeffs(b0, b1, b2, a1, a2)
        } else {
            bassFilterL.setPassthrough()
            bassFilterR.setPassthrough()
        }

        // 2. Calculate High-Shelf Clarity Filter
        if (clarityStrength > 0.001f) {
            val dbGain = clarityStrength.toDouble() * 8.5 // Up to +8.5dB
            val f0 = 4200.0 // Vocal presence & treble definition
            val q = 0.707

            val a = Math.pow(10.0, dbGain / 40.0)
            val w0 = 2.0 * PI * f0 / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
            val b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0
            val b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0
            val b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0
            val a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0
            val a2 = ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0

            clarityFilterL.setCoeffs(b0, b1, b2, a1, a2)
            clarityFilterR.setCoeffs(b0, b1, b2, a1, a2)
        } else {
            clarityFilterL.setPassthrough()
            clarityFilterR.setPassthrough()
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        // Zero-overhead bypass when all DSP effects and normalization are disabled
        if (bassStrength <= 0.001f && clarityStrength <= 0.001f && !normalizationEnabled) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val outputBuffer = replaceOutputBuffer(remaining)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val bStr = bassStrength.toDouble()
        val cStr = clarityStrength.toDouble()
        val normActive = normalizationEnabled
        val harmonicBlend = bStr * 0.16 // Gentle psychoacoustic harmonic warmth
        val headroom = if (bStr > 0.001) 1.0 / Math.pow(10.0, (bStr * 3.5) / 20.0) else 1.0
        val lpAlpha = 0.045 // ~100Hz low-pass cutoff at 44.1k/48k

        if (channelCount == 2) {
            val limit = inputBuffer.limit()
            while (inputBuffer.position() + 3 < limit) {
                var left = inputBuffer.getShort().toDouble()
                var right = inputBuffer.getShort().toDouble()

                // Smart Volume Normalization (Target: ~ -14 LUFS / ~0.125 RMS)
                if (normActive) {
                    val normL = left / 32768.0
                    val normR = right / 32768.0
                    blockSumSquares += (normL * normL + normR * normR) * 0.5
                    blockSampleCount++

                    if (blockSampleCount >= 128) {
                        val blockRms = sqrt(blockSumSquares / blockSampleCount)
                        // Ignore extreme silence (< -42dB) to prevent noise boost during quiet breaks
                        if (blockRms > 0.008) {
                            val alpha = if (blockRms > runningRms) 0.015 else 0.007
                            runningRms = runningRms * (1.0 - alpha) + blockRms * alpha
                            val calcGain = 0.125 / runningRms.coerceAtLeast(0.01)
                            targetGain = calcGain.coerceIn(0.35, 1.85)
                        }
                        blockSumSquares = 0.0
                        blockSampleCount = 0
                    }

                    // Smooth per-sample slew interpolation eliminates zipper noise
                    currentGain += (targetGain - currentGain) * 0.00035

                    left *= currentGain
                    right *= currentGain
                }

                // Harmonic Bass Exciter & Sub-bass boost
                if (bStr > 0.001) {
                    exciterLpL += lpAlpha * (left - exciterLpL)
                    exciterLpR += lpAlpha * (right - exciterLpR)

                    // Smooth sinusoidal warmth generates even sub-bass harmonics without overdrive harshness
                    val normL = (exciterLpL / 16000.0).coerceIn(-1.0, 1.0)
                    val normR = (exciterLpR / 16000.0).coerceIn(-1.0, 1.0)
                    val harmL = sin(normL * (PI * 0.5)) * 6000.0
                    val harmR = sin(normR * (PI * 0.5)) * 6000.0

                    // High-pass the harmonics into audible 90Hz–250Hz punch range
                    exciterHpL += 0.09 * (harmL - exciterHpL)
                    exciterHpR += 0.09 * (harmR - exciterHpR)

                    left = (left * headroom) + exciterHpL * harmonicBlend
                    right = (right * headroom) + exciterHpR * harmonicBlend

                    // Apply low-shelf resonant bass boost
                    left = bassFilterL.process(left)
                    right = bassFilterR.process(right)
                }

                // Apply vocal clarity presence filter
                if (cStr > 0.001) {
                    left = clarityFilterL.process(left)
                    right = clarityFilterR.process(right)
                }

                // Apply smooth soft-knee anti-distortion limiter
                outputBuffer.putShort(softLimit(left))
                outputBuffer.putShort(softLimit(right))
            }
        } else {
            // Mono fallback
            val limit = inputBuffer.limit()
            while (inputBuffer.position() + 1 < limit) {
                var sample = inputBuffer.getShort().toDouble()

                if (normActive) {
                    val norm = sample / 32768.0
                    blockSumSquares += norm * norm
                    blockSampleCount++

                    if (blockSampleCount >= 128) {
                        val blockRms = sqrt(blockSumSquares / blockSampleCount)
                        if (blockRms > 0.008) {
                            val alpha = if (blockRms > runningRms) 0.015 else 0.007
                            runningRms = runningRms * (1.0 - alpha) + blockRms * alpha
                            val calcGain = 0.125 / runningRms.coerceAtLeast(0.01)
                            targetGain = calcGain.coerceIn(0.35, 1.85)
                        }
                        blockSumSquares = 0.0
                        blockSampleCount = 0
                    }

                    currentGain += (targetGain - currentGain) * 0.00035
                    sample *= currentGain
                }

                if (bStr > 0.001) {
                    exciterLpL += lpAlpha * (sample - exciterLpL)
                    val norm = (exciterLpL / 16000.0).coerceIn(-1.0, 1.0)
                    val harm = sin(norm * (PI * 0.5)) * 6000.0
                    exciterHpL += 0.09 * (harm - exciterHpL)
                    sample = (sample * headroom) + exciterHpL * harmonicBlend
                    sample = bassFilterL.process(sample)
                }
                if (cStr > 0.001) {
                    sample = clarityFilterL.process(sample)
                }
                outputBuffer.putShort(softLimit(sample))
            }
        }

        outputBuffer.flip()
    }

    /**
     * Smooth analog tape saturation limiter (hyperbolic tangent curve).
     * Eliminates 16-bit PCM integer wrap-around and harsh clipping under deep bass peaks.
     */
    private fun softLimit(sample: Double): Short {
        val norm = sample / 32768.0
        val limited = if (norm > 0.70) {
            0.70 + 0.28 * kotlin.math.tanh((norm - 0.70) / 0.28)
        } else if (norm < -0.70) {
            -0.70 + 0.28 * kotlin.math.tanh((norm + 0.70) / 0.28)
        } else {
            norm
        }
        return (limited * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }
}
