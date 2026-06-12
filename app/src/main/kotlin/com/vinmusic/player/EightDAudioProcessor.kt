package com.vinmusic.player

import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EightDAudioProcessor : BaseAudioProcessor() {
    var enabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                flush()
            }
        }

    private var theta = 0.0
    private val speed = 0.12 // Panning rotation speed (lower is more relaxing, ~8s per rotation)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        if (!enabled) {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val channelCount = inputAudioFormat.channelCount
        val sampleRate = inputAudioFormat.sampleRate

        if (channelCount == 2) {
            val outputBuffer = replaceOutputBuffer(remaining)
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

            val limit = inputBuffer.limit()
            while (inputBuffer.position() + 3 < limit) {
                val leftVal = inputBuffer.getShort().toDouble()
                val rightVal = inputBuffer.getShort().toDouble()

                // Calculate pan position (-1.0 to 1.0)
                val pan = Math.sin(theta)
                
                // Equal power panning curve
                val panAngle = (pan + 1.0) * Math.PI / 4.0 // 0 to PI/2
                val gainL = Math.cos(panAngle)
                val gainR = Math.sin(panAngle)

                // Create a mono mix to pan across ears
                val mono = (leftVal + rightVal) * 0.5
                
                // Extremeness controls how much we collapse to the panned mono mix.
                // At pan=0 (center), we keep true stereo. At extremes, we use the panned mono mix.
                val extremeness = Math.abs(pan)
                
                // 1.414 compensates for the equal power drop so volume stays consistent
                val targetL = mono * gainL * 1.414
                val targetR = mono * gainR * 1.414

                // Crossfade between original stereo and panned mix
                val outL = leftVal * (1.0 - extremeness) + targetL * extremeness
                val outR = rightVal * (1.0 - extremeness) + targetR * extremeness

                outputBuffer.putShort(outL.toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(outR.toInt().coerceIn(-32768, 32767).toShort())

                // Increment panning angle per stereo frame
                theta += (2.0 * Math.PI * speed) / sampleRate
                if (theta > 2.0 * Math.PI) {
                    theta -= 2.0 * Math.PI
                }
            }
            outputBuffer.flip()
        } else {
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
        }
    }
}
