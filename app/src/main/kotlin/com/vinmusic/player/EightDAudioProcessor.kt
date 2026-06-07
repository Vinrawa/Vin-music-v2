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
    private val speed = 0.5 // Panning rotation speed

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
                var leftVal = inputBuffer.getShort().toDouble()
                var rightVal = inputBuffer.getShort().toDouble()

                val leftGain = Math.cos(theta) * 0.45 + 0.55
                val rightGain = Math.sin(theta) * 0.45 + 0.55

                leftVal *= leftGain
                rightVal *= rightGain

                outputBuffer.putShort(leftVal.toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(rightVal.toInt().coerceIn(-32768, 32767).toShort())

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
